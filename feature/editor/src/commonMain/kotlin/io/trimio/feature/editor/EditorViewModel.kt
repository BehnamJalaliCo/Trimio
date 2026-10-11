package io.trimio.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.engine.production.AddedElement
import io.trimio.engine.production.AutoEditor
import io.trimio.engine.production.CaptionDensity
import io.trimio.engine.production.Edit
import io.trimio.engine.production.EditChange
import io.trimio.engine.production.EditFacts
import io.trimio.engine.production.EditHistory
import io.trimio.engine.production.EditOverrides
import io.trimio.engine.production.EditPlayback
import io.trimio.engine.production.ElementKind
import io.trimio.engine.production.Opened
import io.trimio.engine.production.ProducedEdits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * The editor over one produced edit ([ProducedEdits] opens it by the id the screen was opened
 * with: held in memory, or made again from what its run kept). Every tool action becomes a typed
 * [EditChange] in an [EditHistory] that starts at the version's own changes; the engine applies
 * the overrides to the director's cut ([AutoEditor.touch]): the picture first (milliseconds),
 * then the mixed sound and the jury's verdict. The player draws the edit as it is now, frame by
 * frame, with the export's renderer ([EditPlayback]), and plays its mix.
 *
 * Changes not yet rendered are kept in [drafts] as they are made; leaving with changes ([leave])
 * hands them over to be rendered as the project's next version.
 */
class EditorViewModel(
    private val id: String,
    private val edits: ProducedEdits,
    private val editor: AutoEditor,
    private val drafts: EditDrafts? = null,
) : ViewModel() {

    /** What the screen shows: the editor, or why the edit cannot be opened. */
    sealed interface State {
        data object Opening : State

        data class Unavailable(val why: NotOpen) : State

        data class Open(val ui: EditorUi) : State
    }

    private val _state = MutableStateFlow<State>(State.Opening)
    val state: StateFlow<State> = _state

    private val _frame = MutableStateFlow<ImageBitmap?>(null)

    /** The edit drawn at the playhead. */
    val frame: StateFlow<ImageBitmap?> = _frame

    private var original: Edit? = null
    private var current: Edit? = null

    /** The changes the opened version was made with: leaving with anything else renders a new version. */
    private var committed = EditOverrides()
    private var keeping: Job? = null
    private var facts: EditFacts? = null
    private var history = EditHistory()
    private var playback: EditPlayback? = null

    private var tool = EditorTool.Text
    private var folded = false
    private var selection: IntRange? = null
    private var comparing = false
    private var adding = false
    private var suggestionsShown = SUGGESTIONS
    private var working = false
    private var mixing = false

    private var position = 0f
    private var playing = false
    private var playFrom = 0f
    private var playMark = TimeSource.Monotonic.markNow()

    /** Bumped on anything that needs a new frame. */
    private val tick = MutableStateFlow(0)
    private var width = DEFAULT_WIDTH
    private var picture: Job? = null
    private var mix: Job? = null
    private var cached: Pair<Any, EditorUi>? = null
    private var published = 0f

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val opened = edits.open(id)
        val edit = (opened as? Opened.Ready)?.edit?.takeIf { it.editable } ?: run {
            // An edit made without the director's workbench cannot be changed: as on a platform without the engine.
            _state.value = State.Unavailable(if (opened is Opened.Ready) NotOpen.Unsupported else NotOpen.of(opened))
            return
        }
        original = edit
        current = edit
        committed = edit.overrides
        // Undo goes back as far as the version as it was made.
        history = EditHistory(edit.overrides)
        playback = runCatching { editor.playback(edit) }.getOrNull()
        facts = withContext(Dispatchers.Default) { EditFacts.of(edit) }
        // Changes left unsaved last time are back, one undo away from the version.
        val draft = drafts?.draft(id)?.takeIf { it != committed }
        if (draft != null) render(history.restore(draft)) else publish()
        viewModelScope.launch { frames() }
    }

    // ------------------------------------------------------------------ the session

    fun tool(t: EditorTool) {
        // The open tool tapped again folds the panel away (the whole frame shows).
        folded = if (t == tool) !folded else false
        tool = t
        adding = false
        publish()
    }

    fun word(index: Int, extend: Boolean) {
        val s = selection
        selection = when {
            extend && s != null -> minOf(s.first, index)..maxOf(s.last, index)
            s != null && s.first == index && s.last == index -> null
            else -> index..index
        }
        facts?.words?.getOrNull(index)?.takeIf { !it.cut }?.let { seek(it.start, publishNow = false) }
        publish()
    }

    /** The picked words, or the word at the playhead. */
    private fun span(): IntRange? = selection ?: facts?.wordAt(position)?.let { it..it }

    fun emphasise() = span()?.let { s ->
        val on = facts?.words?.slice(s)?.all { it.emphasised } != true
        change(EditChange.Emphasise(s.first, s.last, on))
    } ?: Unit

    fun editText(text: String) = span()?.let { s -> change(EditChange.Edit(s.first, s.last, text)) } ?: Unit

    fun hit() = span()?.let { s ->
        val on = facts?.words?.getOrNull(s.first)?.hit != true
        change(EditChange.Hit(s.first, on))
    } ?: Unit

    fun delete() = span()?.let { s ->
        val on = facts?.words?.slice(s)?.all { it.cut } != true
        change(EditChange.Delete(s.first, s.last, on))
    } ?: Unit

    fun style(id: String) = change(EditChange.Style(id.takeIf { it != original?.base?.score?.look }))

    /** A drag is one undo step: every move merges, lifting the finger seals it. */
    fun energy(value: Float, done: Boolean) = if (done) history.seal() else change(EditChange.Energy(value), merge = "energy")

    fun density(d: CaptionDensity) = change(EditChange.Captions(d))

    fun compare(on: Boolean) {
        comparing = on
        original?.let { o -> playback?.show(if (on) Edit(o.request, o.base, o.output, o.transcript) else current ?: o) }
        restartSound()
        publish()
    }

    fun music(mood: String?) = change(EditChange.Music(mood ?: EditOverrides.NO_MUSIC))

    fun musicDb(db: Float, done: Boolean) = if (done) history.seal() else change(EditChange.MusicLevel(db), merge = "music-db")

    fun effects(on: Boolean) = change(EditChange.Effects(on))

    fun autoMix() = change(EditChange.AutoMix)

    fun element(key: String, on: Boolean) = change(EditChange.Element(key, on))

    fun adding(open: Boolean) {
        adding = open
        publish()
    }

    fun add(kind: ElementKind) {
        val s = span() ?: return
        val words = facts?.words ?: return
        val text = words.slice(s).filter { it.text.isNotEmpty() }.joinToString(" ") { it.text.trimEnd('.', '،', ',') }
        adding = false
        change(EditChange.Add(AddedElement(kind, s.first, s.last, text)))
    }

    fun more() {
        suggestionsShown += SUGGESTIONS
        publish()
    }

    fun silences(on: Boolean) = change(EditChange.Silences(on))
    fun fillers(on: Boolean) = change(EditChange.Fillers(on))
    fun beatCuts(on: Boolean) = change(EditChange.BeatCuts(on))

    fun undo() = render(history.undo())
    fun redo() = render(history.redo())

    private fun change(c: EditChange, merge: String? = null) {
        if (current == null) return
        val before = history.current
        val after = history.apply(c, merge)
        if (after != before) render(after)
    }

    /** The edit with [overrides]: the picture now, the sound when it is mixed. */
    private fun render(overrides: EditOverrides) {
        val base = original ?: return
        keep(overrides)
        picture?.cancel()
        mix?.cancel()
        working = true
        mixing = true
        comparing = false
        publish()
        picture = viewModelScope.launch {
            // A change the engine cannot make leaves the edit as it was (the panel stays usable).
            val shown = runCatching { editor.touch(base, overrides, sound = false) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (shown == null) {
                working = false
                mixing = false
                publish()
                return@launch
            }
            show(shown)
            working = false
            publish()
            mix = viewModelScope.launch {
                delay(MIX_DEBOUNCE_MS)
                val mixed = runCatching { editor.touch(base, overrides) }.getOrNull()
                if (mixed != null && history.current == overrides) {
                    show(mixed)
                    restartSound()
                }
                mixing = false
                publish()
            }
        }
    }

    private suspend fun show(edit: Edit) {
        current = edit
        facts = withContext(Dispatchers.Default) { EditFacts.of(edit) }
        if (!comparing) playback?.show(edit)
        position = position.coerceIn(0f, facts?.duration ?: position)
        cached = null
        tick.update { it + 1 }
    }

    // ------------------------------------------------------------------ the player

    fun play() {
        val f = facts ?: return
        if (playing) {
            position = clock()
            playing = false
            playback?.pause()
        } else {
            if (position >= f.duration - END_EPS) position = 0f
            playing = true
            playFrom = position
            playMark = TimeSource.Monotonic.markNow()
            playback?.play(position)
        }
        publish()
        tick.update { it + 1 }
    }

    fun seek(t: Float, publishNow: Boolean = true) {
        val f = facts ?: return
        position = t.coerceIn(0f, f.duration)
        if (playing) {
            playFrom = position
            playMark = TimeSource.Monotonic.markNow()
            playback?.play(position)
        }
        if (publishNow) publish()
        tick.update { it + 1 }
    }

    /** The frame width the screen wants (pixels). */
    fun frameWidth(px: Int) {
        val w = px.coerceIn(MIN_WIDTH, MAX_WIDTH)
        if (abs(w - width) > WIDTH_STEP) {
            width = w
            tick.update { it + 1 }
        }
    }

    /** The playhead: the speaker's while it plays, else the wall clock from where play started. */
    private fun clock(): Float = if (!playing) {
        position
    } else {
        playback?.position ?: (playFrom + playMark.elapsedNow().inWholeMilliseconds / MS)
    }

    private fun restartSound() {
        if (playing) {
            position = clock()
            playFrom = position
            playMark = TimeSource.Monotonic.markNow()
            playback?.play(position)
        }
    }

    /** Draws the frame at the playhead whenever it moves or the edit changes; two buffers in turn. */
    private suspend fun frames() {
        val buffers = arrayOfNulls<ImageBitmap>(2)
        var k = 0
        var drawn: Triple<Float, Int, Int>? = null
        while (viewModelScope.isActive) {
            val seen = tick.value
            val f = facts
            if (playing && f != null) {
                position = clock().coerceAtMost(f.duration)
                if (position >= f.duration - END_EPS) {
                    playing = false
                    playback?.pause()
                }
                // The time and the panels follow at a quarter of the frame rate; the picture at full rate.
                if (!playing || abs(position - published) >= PUBLISH_STEP) publish()
            }
            val want = Triple(position, seen, width)
            if (want != drawn) {
                val into = buffers[k]
                val at = position
                val image = withContext(Dispatchers.Default) { runCatching { playback?.frame(at, width, into) }.getOrNull() }
                if (image != null) {
                    buffers[k] = image
                    _frame.value = image
                    k = 1 - k
                }
                drawn = want
            } else if (!playing) {
                tick.first { it != seen }
            } else {
                delay(FRAME_MS)
            }
        }
    }

    // ------------------------------------------------------------------ keeping

    /** Keeps the changes as the draft a moment after the last one (a slider drag is one write). */
    private fun keep(overrides: EditOverrides) {
        val d = drafts ?: return
        keeping?.cancel()
        keeping = viewModelScope.launch {
            delay(DRAFT_MS)
            d.keep(id, overrides.takeIf { it != committed })
        }
    }

    /** Whether the edit differs from the version it was opened as. */
    val unsaved: Boolean get() = original != null && history.current != committed

    /**
     * Leaving the editor: the changes to render as the next version, or null when there are none
     * (the draft is settled either way: kept until the render is done, or forgotten).
     */
    fun leave(): EditOverrides? {
        if (original == null) return null
        keeping?.cancel()
        val changes = history.current.takeIf { it != committed }
        drafts?.keep(id, changes)
        return changes
    }

    /** The edit the editor was opened with (the next version is rendered from it). */
    fun opened(): Edit? = original

    // ------------------------------------------------------------------ export

    /**
     * The edit as it stands, mixed, and the id the export finds it by: the version itself when
     * nothing changed, else the changes held under a draft id of their own (the version stays as
     * it is until the editor renders them). Null while nothing is open.
     */
    suspend fun publishForExport(): String? {
        val base = original ?: return null
        mix?.join()
        val overrides = history.current
        if (overrides == committed) return id
        val ready = current?.takeIf { it.overrides == overrides && it.mixed } ?: editor.touch(base, overrides)
        return (DRAFT + id).also { edits.hold(it, ready) }
    }

    /** The edit as it stands (for a rewrite through the director). */
    fun edit(): Edit? = current

    // ------------------------------------------------------------------ state

    private fun publish() {
        val f = facts ?: return
        published = position
        val line = f.lineAt(position)
        val key = listOf(f, selection, line, comparing, suggestionsShown, if (selection == null) f.wordAt(position) else null)
        val base = cached?.takeIf { it.first == key }?.second ?: EditorUi.of(f, position, selection, comparing, suggestionsShown).also { cached = key to it }
        _state.value = State.Open(
            base.copy(
                tool = tool, folded = folded, position = position, playing = playing,
                canUndo = history.canUndo, canRedo = history.canRedo, working = working, mixing = mixing,
                elements = base.elements.copy(adding = adding), unsaved = unsaved,
            ),
        )
    }

    override fun onCleared() {
        playback?.close()
        playback = null
    }

    companion object {
        /** The id prefix the editor's unsaved changes are held under for the export screen. */
        const val DRAFT = "draft-"
        private const val DRAFT_MS = 600L
        private const val SUGGESTIONS = 2
        private const val DEFAULT_WIDTH = 540
        private const val MIN_WIDTH = 270
        private const val MAX_WIDTH = 720
        private const val WIDTH_STEP = 40
        private const val FRAME_MS = 8L
        private const val MIX_DEBOUNCE_MS = 350L
        private const val MS = 1000f
        private const val END_EPS = 0.04f
        private const val PUBLISH_STEP = 0.1f
    }
}
