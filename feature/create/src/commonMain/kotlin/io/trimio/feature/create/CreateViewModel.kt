package io.trimio.feature.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.Clock
import io.trimio.core.data.JobRunner
import io.trimio.core.data.MediaKind
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.SettingsRepository
import io.trimio.core.data.ThermalLevel
import io.trimio.core.data.ThermalMonitor
import io.trimio.core.model.input.AspectRatio
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.MediaUri
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.engine.director.Brief
import io.trimio.engine.director.BriefParser
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.media.MediaProbe
import io.trimio.engine.styles.StylePack
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

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
) {
    val canCreate: Boolean get() = info != null && !creating
}

class CreateViewModel(
    private val uri: String,
    kind: MediaKind,
    private val probe: MediaProbe,
    private val styles: StylePackRepository,
    private val cloud: CloudModels,
    private val settings: SettingsRepository,
    private val projects: ProjectRepository,
    private val runner: JobRunner,
    initialStyle: String? = null,
    thermal: ThermalMonitor = ThermalMonitor.None,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateState(kind, director = settings.settings.value.director, styleId = initialStyle))
    val state: StateFlow<CreateState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val info = runCatching { probe.probe(MediaUri(uri)) }
            _state.update { it.copy(probing = false, info = info.getOrNull(), probeError = info.exceptionOrNull()?.message) }
        }
        viewModelScope.launch { _state.update { it.copy(packs = styles.all()) } }
        viewModelScope.launch { thermal.level.collect { level -> _state.update { it.copy(hot = level >= ThermalLevel.Hot) } } }
        viewModelScope.launch {
            val connected = cloud.firstAvailable() != null
            _state.update { it.copy(cloudConnected = connected, director = if (connected) it.director else DirectorBackend.OnDevice) }
        }
    }

    fun setPrompt(text: String) = _state.update { it.copy(prompt = text, brief = BriefParser.parse(text)) }

    fun setStyle(id: String?) = _state.update { it.copy(styleId = id) }

    fun setDirector(backend: DirectorBackend) = _state.update { it.copy(director = backend) }

    fun setAspect(aspect: AspectRatio) = _state.update { it.copy(aspect = aspect) }

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

    fun create(onCreated: (String) -> Unit) {
        val s = _state.value
        val info = s.info ?: return
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            val input = when {
                s.kind == MediaKind.Video && info.video != null -> InputSource.Video(MediaUri(uri), info.durationMs, info.video!!)
                else -> InputSource.AudioOnly(MediaUri(uri), info.durationMs, CanvasSpec(s.aspect, settings.settings.value.exportResolution))
            }
            val now = Clock.now()
            val project = Project(
                id = "p" + now.toString(36) + Random.nextInt(1_000, 9_999),
                title = titleFrom(s.prompt, s.kind),
                createdAt = now,
                updatedAt = now,
                input = input,
                prompt = s.prompt.trim(),
                styleId = s.styleId ?: s.brief.styleId,
                director = s.director,
            )
            projects.save(project)
            runner.build(project)
            onCreated(project.id)
        }
    }

    private fun titleFrom(prompt: String, kind: MediaKind): String {
        val words = prompt.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> if (kind == MediaKind.Video) "ویدیوی جدید" else "صدای جدید"
            else -> words.take(5).joinToString(" ") + if (words.size > 5) "…" else ""
        }
    }
}
