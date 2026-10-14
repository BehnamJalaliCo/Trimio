package io.trimio.feature.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.MediaKind
import io.trimio.core.data.SettingsRepository
import io.trimio.core.data.ThermalLevel
import io.trimio.core.data.ThermalMonitor
import io.trimio.core.model.input.AspectRatio
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.MediaUri
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.engine.director.Brief
import io.trimio.engine.director.BriefParser
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.media.MediaProbe
import io.trimio.engine.models.SpeedMode
import io.trimio.engine.models.SpeedPlan
import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.styles.StylePack
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateState(
    val kind: MediaKind,
    val probing: Boolean = true,
    val info: MediaInfo? = null,
    val probeError: String? = null,
    val prompt: String = "",
    val brief: Brief = BriefParser.parse(""),
    val packs: List<StylePack> = emptyList(),
    /** Null lets the Director choose from the prompt. */
    val styleId: String? = null,
    val director: DirectorBackend = DirectorBackend.OnDevice,
    val cloudConnected: Boolean = false,
    val aspect: AspectRatio = AspectRatio.Portrait9x16,
    val creating: Boolean = false,
    /** The phone is throttling: builds will be slower and use fewer cores. */
    val hot: Boolean = false,
    /** Expert mode: a full brief (hook, destination, emphasis, music) instead of one line. */
    val expert: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    /** Exact spellings (sites, brands, technical terms) the director must use. */
    val glossary: List<String> = emptyList(),
    /** No media yet (opened without a file): the screen asks for a video or an audio. */
    val hasMedia: Boolean = true,
    /** «سریع / متعادل / حرفه‌ای» on this device: the installed director each one runs. */
    val speeds: SpeedPlan = SpeedPlan.None,
    val speed: SpeedMode = speeds.default,
    /** For a video: a reel, or a long YouTube video (subtitles only, or with YouTube's devices). */
    val edit: EditChoice = EditChoice.Reel,
    /** The subtitle look of a subtitled edit; null: the kind's own default ([SubtitleChoice.default]). */
    val subtitles: SubtitleChoice? = null,
) {
    /** The on-device director the chosen speed runs (null: none installed, the engine decides). */
    val speedModel: String? get() = speeds.of(speed).model?.id

    val canCreate: Boolean get() = info != null && !creating

    /** The text the engine reads: prompt, glossary and attachment captions together. */
    val enginePrompt: String get() = EnginePrompt.compose(prompt, glossary, attachments)
}

class CreateViewModel(
    private val uri: String,
    kind: MediaKind,
    private val probe: MediaProbe,
    private val styles: StylePackRepository,
    private val cloud: CloudModels,
    settings: SettingsRepository,
    initialStyle: String? = null,
    thermal: ThermalMonitor = ThermalMonitor.None,
    private val attachmentPicker: AttachmentPicker = AttachmentPicker.None,
    /** Words to start the brief with (an idea from home, or the composer's wish). */
    initialPrompt: String? = null,
    /** The installed models and this device, for the three speeds; none in previews. */
    private val speeds: SpeedSource? = null,
) : ViewModel() {
    /** Whether the creator picked a speed (a later plan, with the card's memory known, keeps it). */
    private var picked = false

    private val _state = MutableStateFlow(
        CreateState(
            kind, probing = uri.isNotBlank(), director = settings.settings.value.director, styleId = initialStyle?.let(::languageOf), hasMedia = uri.isNotBlank(),
            prompt = initialPrompt.orEmpty(), brief = BriefParser.parse(initialPrompt.orEmpty()),
        ),
    )
    val state: StateFlow<CreateState> = _state.asStateFlow()

    init {
        if (uri.isNotBlank()) viewModelScope.launch {
            val info = runCatching { probe.probe(MediaUri(uri)) }
            _state.update { it.copy(probing = false, info = info.getOrNull(), probeError = info.exceptionOrNull()?.message) }
        }
        viewModelScope.launch { _state.update { it.copy(packs = styles.all()) } }
        viewModelScope.launch { thermal.level.collect { level -> _state.update { it.copy(hot = level >= ThermalLevel.Hot) } } }
        speeds?.let { source -> viewModelScope.launch { source.plans(settings.settings.value.localModelId).collect(::plan) } }
        viewModelScope.launch {
            val connected = cloud.firstAvailable() != null
            _state.update { it.copy(cloudConnected = connected, director = if (connected) it.director else DirectorBackend.OnDevice) }
        }
    }

    private fun plan(plan: SpeedPlan) = _state.update { s ->
        val keep = picked && plan.of(s.speed).available
        s.copy(speeds = plan, speed = if (keep) s.speed else plan.default)
    }

    /** Runs the next edit at [mode]; false when its model is not installed (the screen offers the download). */
    fun setSpeed(mode: SpeedMode): Boolean {
        if (!_state.value.speeds.of(mode).available) return false
        picked = true
        _state.update { it.copy(speed = mode) }
        return true
    }

    fun setEdit(choice: EditChoice) = _state.update { it.copy(edit = choice) }

    fun setSubtitles(choice: SubtitleChoice) = _state.update { it.copy(subtitles = choice) }

    fun setPrompt(text: String) = _state.update { it.copy(prompt = text, brief = BriefParser.parse(text)) }

    fun setStyle(id: String?) = _state.update { it.copy(styleId = id) }

    fun setDirector(backend: DirectorBackend) = _state.update { it.copy(director = backend) }

    fun setAspect(aspect: AspectRatio) = _state.update { it.copy(aspect = aspect) }

    fun setExpert(expert: Boolean) = _state.update { it.copy(expert = expert) }

    /** Adds what the user typed as a glossary chip; duplicates and blanks are ignored. */
    fun addTerm(raw: String) {
        val term = raw.trim()
        if (term.isEmpty()) return
        _state.update { s -> if (s.glossary.any { it.equals(term, ignoreCase = true) }) s else s.copy(glossary = s.glossary + term) }
    }

    fun removeTerm(term: String) = _state.update { s -> s.copy(glossary = s.glossary - term) }

    fun attach(kind: AttachmentKind) {
        viewModelScope.launch {
            val picked = attachmentPicker.pick(kind) ?: return@launch
            _state.update { s -> s.copy(attachments = s.attachments + picked) }
        }
    }

    fun detach(id: String) = _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == id }) }

    fun setCaption(id: String, caption: String) = _state.update { s ->
        s.copy(attachments = s.attachments.map { if (it.id == id) it.copy(caption = caption) else it })
    }

    /**
     * "Improve prompt": turns a short wish into a complete brief the Director can follow, keeping
     * every explicit instruction the user wrote. Deterministic, instant and offline.
     */
    fun improvePrompt(persian: Boolean) {
        val current = _state.value.prompt.trim()
        val brief = _state.value.brief
        val additions = buildList {
            if (brief.energy == null) add(if (persian) "ریتم پرانرژی و سریع" else "energetic, fast pacing")
            add(if (persian) "هوک قوی در ۳ ثانیه\u0654 اول" else "a strong hook in the first 3 seconds")
            add(if (persian) "تأکید روی اعداد و کلمات کلیدی با المان متحرک" else "emphasise numbers and key words with animated elements")
            if (brief.cutSilences) add(if (persian) "حذف سکوت\u200Cها و مکث\u200Cها" else "cut silences and hesitations")
            add(if (persian) "دعوت به اقدام در پایان" else "a call to action at the end")
        }
        val joined = additions.joinToString(if (persian) "، " else ", ")
        setPrompt(if (current.isEmpty()) joined else "$current${if (persian) "؛ " else "; "}$joined")
    }

    /** A gallery pack id ("brutalism") or a language id ("bold-brutalist") as the language id. */
    private fun languageOf(id: String): String = StyleBook.all.firstOrNull { it.pack == id }?.id ?: id
}
