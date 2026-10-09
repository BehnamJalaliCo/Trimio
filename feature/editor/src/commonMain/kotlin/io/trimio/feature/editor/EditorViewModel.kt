package io.trimio.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.MusicClip
import io.trimio.core.model.timeline.SfxClip
import io.trimio.core.model.timeline.Timeline
import io.trimio.engine.director.EditPlan
import io.trimio.engine.director.QualityControl
import io.trimio.engine.director.TimelineEditor
import io.trimio.engine.render.PreviewClock
import io.trimio.engine.styles.StylePack
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditorTab { Text, Style, Sound }

data class EditorState(
    val project: Project? = null,
    val timeline: Timeline? = null,
    val style: StyleSpec? = null,
    val packs: List<StylePack> = emptyList(),
    val tab: EditorTab = EditorTab.Text,
    val selectedWord: Int? = null,
    val canUndo: Boolean = false,
    val saved: Boolean = true,
) {
    /** Transcript word indices currently cut out of the edit by the user. */
    val removedWords: Set<Int>
        get() {
            val shown = timeline?.clipsOf<CaptionClip>()?.map { it.wordIndex }?.toSet() ?: return emptySet()
            return project?.transcript?.words?.indices?.filter { it !in shown }?.toSet().orEmpty()
        }

    val emphasized: Set<Int>
        get() {
            val threshold = style?.captions?.emphasis?.threshold ?: 0.6f
            return timeline?.clipsOf<CaptionClip>()?.filter { it.emphasis >= threshold }?.map { it.wordIndex }?.toSet().orEmpty()
        }

    val musicMood: String get() = timeline?.clipsOf<MusicClip>()?.firstOrNull()?.assetId?.substringAfter("music/") ?: "none"
    val sfxOn: Boolean get() = timeline?.clipsOf<SfxClip>()?.isNotEmpty() == true
}

/**
 * Text-first editing of a finished project. Every change is a pure [TimelineEditor] operation on
 * the timeline, kept in an undo history and saved shortly after the user stops editing.
 */
class EditorViewModel(
    private val projectId: String,
    private val projects: ProjectRepository,
    private val styles: StylePackRepository,
    private val qc: QualityControl = QualityControl(),
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()
    val clock = PreviewClock()

    private val history = ArrayDeque<Pair<Timeline, StyleSpec>>()
    /** The Director's original edit: restores removed words and sound effects. */
    private var original: Timeline? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            projects.load()
            val project = projects.get(projectId)
            original = project?.timeline
            _state.update { it.copy(project = project, timeline = project?.timeline, style = project?.style, packs = styles.all()) }
        }
    }

    fun selectTab(tab: EditorTab) = _state.update { it.copy(tab = tab) }

    fun selectWord(index: Int?) {
        _state.update { it.copy(selectedWord = index) }
        val range = index?.let { i -> _state.value.timeline?.clipsOf<CaptionClip>()?.firstOrNull { it.wordIndex == i }?.range }
        if (range != null) clock.seekTo(range.startMs)
    }

    fun togglePlay() {
        clock.playing = !clock.playing
    }

    fun seek(ms: Long) = clock.seekTo(ms)

    fun setWordText(index: Int, text: String) = edit { t, s -> TimelineEditor.setWordText(t, index, text) to s }

    fun toggleEmphasis(index: Int) = edit { t, s -> TimelineEditor.setEmphasis(t, index, index !in _state.value.emphasized) to s }

    fun toggleRemoved(index: Int) {
        val project = _state.value.project ?: return
        val transcript = project.transcript ?: return
        val removed = index in _state.value.removedWords
        edit { t, s ->
            val next = if (removed) TimelineEditor.restoreWords(t, transcript, setOf(index), project.input.durationMs, original ?: t)
            else TimelineEditor.removeWords(t, transcript, setOf(index), project.input.durationMs)
            next to s
        }
    }

    fun setStyle(pack: StylePack) {
        val project = _state.value.project ?: return
        edit { t, _ ->
            val tuned = qc.tuneStyle(pack.spec, EditPlan(styleId = pack.id, energy = pack.spec.motion.energy), project.input).first
            TimelineEditor.setStyle(t, pack.id) to tuned
        }
    }

    fun setMusic(mood: String) = edit { t, s -> TimelineEditor.setMusic(t, mood) to s }

    fun setSfx(enabled: Boolean) = edit { t, s -> TimelineEditor.setSoundEffects(t, enabled, original ?: t) to s }

    fun undo() {
        val (t, s) = history.removeLastOrNull() ?: return
        _state.update { it.copy(timeline = t, style = s, canUndo = history.isNotEmpty(), saved = false) }
        scheduleSave()
    }

    /** Saves immediately (before export or leaving). */
    suspend fun flush(): Project? {
        saveJob?.cancel()
        return persist()
    }

    private fun edit(change: (Timeline, StyleSpec) -> Pair<Timeline, StyleSpec>) {
        val s = _state.value
        val timeline = s.timeline ?: return
        val style = s.style ?: return
        val (t, st) = change(timeline, style)
        if (t == timeline && st == style) return
        history.addLast(timeline to style)
        if (history.size > MAX_HISTORY) history.removeFirst()
        _state.update { it.copy(timeline = t, style = st, canUndo = true, saved = false) }
        if (clock.positionMs >= t.durationMs) clock.seekTo(0)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            persist()
        }
    }

    private suspend fun persist(): Project? {
        val s = _state.value
        val saved = projects.update(projectId) { it.copy(timeline = s.timeline ?: it.timeline, style = s.style ?: it.style) }
        _state.update { it.copy(project = saved ?: it.project, saved = true) }
        return saved
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}
