package io.trimio.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.GallerySaver
import io.trimio.core.data.SettingsRepository
import io.trimio.core.data.Sharer
import io.trimio.core.model.input.Resolution
import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.production.Edit
import io.trimio.engine.production.EditExports
import io.trimio.engine.production.ProducedEdits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlin.time.TimeSource

/** What the export screen is opened with: the edit's id and the platform's habits. */
data class ExportArgs(val id: String, val persian: Boolean = true, val desktop: Boolean = false)

/**
 * Exports of one finished edit, found by the id the screen was opened with ([ProducedEdits]): the
 * video again at a destination's frame, a size and a rate (laid out anew by the motion engine and
 * rendered by the platform's encoder), saved to the gallery or shared; the captions as SRT; and
 * the package for professional editors. The choices are remembered in settings.
 */
class ExportViewModel(
    private val args: ExportArgs,
    edits: ProducedEdits,
    private val engine: EditExports?,
    private val settings: SettingsRepository,
    private val saver: GallerySaver? = null,
    private val sharer: Sharer? = null,
) : ViewModel() {
    private val edit: Edit? = edits.find(args.id)?.takeIf { engine != null }
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<ExportUi> = _state.asStateFlow()
    private var running: Job? = null

    init {
        withEdit { e, exports -> viewModelScope.launch { exports.poster(e)?.let { p -> _state.update { it.copy(poster = p) } } } }
    }

    private fun initial(): ExportUi {
        val s = settings.settings.value
        val e = edit ?: return ExportUi(missing = true, desktop = args.desktop)
        val exports = engine ?: return ExportUi(missing = true, desktop = args.desktop)
        val p = e.production
        val style = StyleBook.of(p.score.look)
        return ExportUi(
            title = title(e),
            scenes = p.score.scenes.size.coerceAtLeast(1),
            styleName = style?.let { if (args.persian) it.nameFa else it.nameEn },
            durationMs = (p.compiled.composition.duration * MS).toLong(),
            verdict = p.panel?.let { if (it.unanimous) ExportVerdict.Approved else ExportVerdict.Best(it.min) } ?: ExportVerdict.None,
            destination = ExportDestination.of(p.score.format),
            quality = ExportQuality.of(s.exportResolution.shortEdge),
            fps = s.exportFrameRate.takeIf { it in ExportUi.Rates } ?: p.compiled.composition.fps,
            unsupported = ExportQuality.entries.flatMap { q -> ExportUi.Rates.map { q to it } }
                .filterNot { (q, r) -> exports.canEncode(ExportUi(destination = ExportDestination.of(p.score.format), quality = q, fps = r).spec) }.toSet(),
            desktop = args.desktop,
        )
    }

    fun setDestination(d: ExportDestination) = choose { it.copy(destination = d) }

    fun setQuality(q: ExportQuality) {
        choose { it.copy(quality = q) }
        viewModelScope.launch { settings.update { it.copy(exportResolution = Resolution.entries.firstOrNull { r -> r.shortEdge == q.shortEdge } ?: it.exportResolution) } }
    }

    fun setFps(fps: Int) {
        choose { it.copy(fps = fps) }
        viewModelScope.launch { settings.update { it.copy(exportFrameRate = fps) } }
    }

    /** Changing a choice after a run starts over (a finished file stays where it was saved). */
    private fun choose(change: (ExportUi) -> ExportUi) = _state.update { s -> if (s.busy) s else change(s).copy(job = null) }

    /** Renders the video at the chosen frame, size and rate. */
    fun export() = withEdit { e, exports ->
        val ui = _state.value
        val spec = ui.spec
        val (w, h) = spec.size
        val out = Path(exports.folder(e), "${fileName(ui)}-${ui.destination.name.lowercase()}-${w}x$h-${spec.fps}.mp4").toString()
        val frames = (ui.durationMs / MS * spec.fps).toInt().coerceAtLeast(1)
        launchJob(ExportKind.Video) { progress ->
            val clock = TimeSource.Monotonic.markNow()
            val file = exports.video(e, spec, out) { p ->
                val elapsed = clock.elapsedNow().inWholeMilliseconds / MS
                progress(p, if (p > MIN_FOR_ETA) (elapsed * (1 - p) / p).toInt() else null)
            }
            // The pace this device kept, for the next estimate (per frame at 1080p).
            val took = clock.elapsedNow().inWholeMilliseconds / MS
            val pixels = w.toFloat() * h / HD_PIXELS
            if (took > MIN_MEASURED) _state.update { it.copy(secondsPerFrame = took / frames / pixels) }
            listOf(file)
        }
    }

    /** The package for Premiere, Final Cut and DaVinci, then hands it on. */
    fun exportProject() = withEdit { e, exports ->
        val ui = _state.value
        val name = fileName(ui)
        val folder = Path(exports.folder(e), "$name-${ui.destination.name.lowercase()}-project").toString()
        launchJob(ExportKind.Project) { progress ->
            exports.project(e, ui.spec, folder, name) { p -> progress(p, null) }.files.also { sharer?.sharePaths(it, ui.title) }
        }
    }

    /** Captions only, as SubRip, handed on at once. */
    fun exportCaptions() = withEdit { e, exports ->
        val ui = _state.value
        launchJob(ExportKind.Captions) { _ ->
            listOf(exports.srtFile(e, Path(exports.folder(e), "${fileName(ui)}.srt").toString())).also { sharer?.sharePaths(it, ui.title) }
        }
    }

    fun cancel() {
        running?.cancel()
        running = null
        _state.update { it.copy(job = null) }
    }

    /** Saves the finished video to the gallery (or the Videos folder); shares it where there is none. */
    fun save() {
        val job = _state.value.job?.takeIf { it.done && it.kind == ExportKind.Video } ?: return
        val file = job.files.first()
        viewModelScope.launch {
            val saved = saver?.save(file, _state.value.title) ?: run { sharer?.share(file, _state.value.title); false }
            _state.update { s -> s.copy(job = s.job?.copy(saved = saved)) }
        }
    }

    fun share() {
        val job = _state.value.job?.takeIf { it.done } ?: return
        if (job.kind == ExportKind.Video) sharer?.share(job.files.first(), _state.value.title) else sharer?.sharePaths(job.files, _state.value.title)
    }

    private fun launchJob(kind: ExportKind, work: suspend (progress: (Float, Int?) -> Unit) -> List<String>) {
        if (_state.value.busy) return
        _state.update { it.copy(job = ExportJob(kind)) }
        running = viewModelScope.launch {
            try {
                val files = work { p, eta -> _state.update { s -> s.copy(job = s.job?.copy(progress = p.coerceIn(0f, 1f), etaSeconds = eta)) } }
                _state.update { s -> s.copy(job = s.job?.copy(progress = 1f, files = files, etaSeconds = null)) }
            } catch (c: CancellationException) {
                throw c
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                // The encoder, ffmpeg or the file system said no: the screen says so and offers to retry.
                _state.update { s -> s.copy(job = s.job?.copy(error = t.message ?: t::class.simpleName ?: "error")) }
            }
        }
    }

    /** Runs [block] with the edit and the exporter, when both are there. */
    private inline fun withEdit(block: (Edit, EditExports) -> Unit) {
        val e = edit ?: return
        val x = engine ?: return
        block(e, x)
    }

    private fun title(e: Edit): String =
        e.production.understanding.title.trim().ifEmpty { e.request.prompt.trim().take(TITLE) }.ifEmpty { "Trimio" }

    /** A file-safe name from the title (letters and digits of any script). */
    private fun fileName(ui: ExportUi): String =
        ui.title.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(NAME).ifEmpty { "trimio" }

    private companion object {
        const val MS = 1000f
        const val TITLE = 48
        const val NAME = 40
        const val MIN_FOR_ETA = 0.04f
        const val MIN_MEASURED = 2f
        const val HD_PIXELS = 1080f * 1920f
    }
}
