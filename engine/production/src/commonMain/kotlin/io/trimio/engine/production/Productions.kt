package io.trimio.engine.production

import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Collaborator
import io.trimio.engine.autopilot.Delivery
import io.trimio.engine.autopilot.FrameGrabber
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Knowledge
import io.trimio.engine.autopilot.SubjectFinder
import io.trimio.engine.autopilot.SubjectGrounding
import io.trimio.engine.autopilot.Taste
import io.trimio.engine.autopilot.UserAsset
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The [AutoEditor]: prepare (voice, frames, the speaker's head, attachments), transcribe, direct
 * with the [Autopilot] (previewing every compiled edit as it forms), render. Words and the reading
 * are checkpointed as they finish, so a restarted run skips whisper and the model; a finished
 * run keeps the director's whole decision ([Delivery]), so its edit opens again after the app
 * restarts ([reopen]) without either.
 *
 * [jury] overrides the device's budget (tests); by default [Juries.forDevice] reads the device
 * when each run starts, so a hot phone gets a lighter jury.
 */
class Productions(
    private val platform: ProductionPlatform,
    private val checkpoints: Checkpoints = Checkpoints.None,
    private val knowledge: Knowledge = Knowledge.Offline,
    private val visuals: VisualLibrary = VisualLibrary.Empty,
    private val jury: Jury? = null,
    /** Recognition threads, read when each run starts (fewer on a warm phone). */
    private val threads: () -> Int = { DEFAULT_THREADS },
) : AutoEditor {

    private var engines: Engines? = null
    private val engineLock = Mutex()

    /** Fonts, the text engine and the bundled marks: loaded once, shared by every run. */
    private class Engines(val text: TextLayoutEngine, val previewer: Previewer, val brands: BrandLibrary, val measurer: androidx.compose.ui.text.TextMeasurer, val fonts: MotionFonts)

    private suspend fun engines(): Engines = engineLock.withLock {
        engines ?: run {
            val fonts = MotionFonts.load()
            val measurer = platform.measurer()
            Engines(TextLayoutEngine(measurer, fonts), Previewer(measurer, fonts, platform.previewShaders), BrandLibrary.load(), measurer, fonts)
        }.also { engines = it }
    }

    override fun produce(request: ProductionRequest): Flow<ProductionState> = run(request, previous = null, taste = Taste())

    override fun vary(edit: Edit, seed: Long): Flow<ProductionState> = run(edit.request.copy(seed = seed), edit, edit.taste)

    override fun revise(edit: Edit, feedback: String): Flow<ProductionState> = run(edit.request.copy(seed = edit.request.seed + 1), edit, edit.taste, feedback)

    override suspend fun touch(edit: Edit, overrides: EditOverrides, sound: Boolean): Edit = withContext(Dispatchers.Default) {
        val director = directorOutput(edit)
        if (overrides.isEmpty) return@withContext Edit(edit.request, edit.base, director, edit.transcript, edit.taste, edit.base, overrides, edit.assets)
        val result = Retouch.apply(edit.base, overrides, sound)
        Edit(edit.request, result.production, retouchedOutput(director, overrides), edit.transcript, edit.taste, edit.base, overrides, edit.assets, result.warnings, result.mixed)
    }

    override fun retouch(edit: Edit, overrides: EditOverrides): Flow<ProductionState> = channelFlow {
        val key = edit.request.key
        val state = MutableStateFlow(ProductionState(key, stage = Stage.Render, step = "render", progress = 0f))
        val forward = launch { state.first { s -> send(s); s.finished } }
        try {
            val touched = touch(edit, overrides)
            // The new version's poster: a frame of the edit as the creator left it.
            val poster = still(touched)
            state.update { it.copy(panel = touched.production.panel?.let(PanelSummary::of), report = touched.production.report, previews = listOfNotNull(poster)) }
            val out = platform.export(touched.production, touched.request, touched.assets, touched.output) { done, total ->
                state.update { it.copy(step = "render $done/$total", progress = done / total.coerceAtLeast(1).toFloat()) }
            }
            val done = Edit(touched.request, touched.production, out, touched.transcript, touched.taste, touched.base, touched.overrides, touched.assets, touched.warnings)
            state.update { it.copy(status = ProductionStatus.Done, stage = Stage.Done, step = "done", progress = 1f, output = out, edit = done) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            state.update { it.copy(status = ProductionStatus.Failed, error = e.message ?: e::class.simpleName) }
        }
        forward.join()
    }.conflate()

    override suspend fun reopen(request: ProductionRequest, overrides: EditOverrides): Opened = withContext(Dispatchers.Default) {
        val saved = checkpoints.load(request.key)
        val kept = saved?.delivery?.takeIf { it.version == Delivery.VERSION } ?: return@withContext Opened.NotKept
        // The creator's own files are read again: a video, recording or attachment that is gone is said so.
        val media = readable {
            val voice = platform.audio(request.media, VOICE_RATE)
            check(voice.samples.isNotEmpty()) { "no sound in ${request.media}" }
            voice to request.assets.mapIndexed { i, a -> platform.asset(a.source, "asset-$i", a.caption) }
        } ?: return@withContext Opened.SourceMissing
        val (voice, assets) = media
        val e = engines()
        val autopilot = Autopilot(e.text, e.brands, visuals, jury = jury ?: Juries.forDevice(platform.device()))
        val production = autopilot.restore(kept, voice, assets)
        val director = Edit(request, production, request.output ?: platform.outputFor(request), saved.transcript, assets = assets)
        Opened.Ready(if (overrides.isEmpty) director else touch(director, overrides))
    }

    override suspend fun kept(request: ProductionRequest): Boolean =
        withContext(Dispatchers.Default) { checkpoints.load(request.key)?.delivery?.version == Delivery.VERSION }

    /** [block]'s result, or null when the platform could not read the creator's media. */
    private suspend fun <T> readable(block: suspend () -> T): T? =
        runCatching { block() }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    /** A frame of [edit] for its poster (null when it cannot be drawn). */
    private suspend fun still(edit: Edit): ImageBitmap? = readable {
        val e = engines()
        val output = edit.production.compiled
        val c = output.composition
        val media = platform.media(edit.request, edit.assets, FPS, c.width, c.height)
        try {
            e.previewer.frame(output, media, Previewer.time(0, c.duration), Previewer.WIDTH)
        } finally {
            (media as? AutoCloseable)?.close()
        }
    }

    override fun playback(edit: Edit): EditPlayback = EditPlayback(
        engines = { engines().let { it.measurer to it.fonts } },
        shaders = platform.previewShaders,
        open = { w, h -> platform.media(edit.request, edit.assets, FPS, w, h) },
        speaker = platform.speaker(),
        edit = edit,
    )

    /** The director's own render of [edit] (before any change in the editor). */
    private fun directorOutput(edit: Edit): String =
        if (edit.overrides.isEmpty) edit.output else edit.output.replace(RETOUCHED, ".mp4")

    /** The file an edit with [overrides] renders to: one per state (in every process), so a render already made is this exact edit. */
    private fun retouchedOutput(director: String, overrides: EditOverrides): String =
        director.removeSuffix(".mp4") + "-e" + overrides.fingerprint + ".mp4"

    private fun run(request: ProductionRequest, previous: Edit?, taste: Taste, feedback: String? = null): Flow<ProductionState> = channelFlow {
        val state = MutableStateFlow(ProductionState(request.key))
        // Every distinct state goes out in order; the flow ends after the final one.
        val forward = launch { state.first { s -> send(s); s.finished } }
        try {
            withContext(Dispatchers.Default) { work(Run(request, previous, taste, feedback, state)) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Any engine failure ends the run with its message; the screen offers to retry.
            state.update { it.copy(status = ProductionStatus.Failed, error = e.message ?: e::class.simpleName) }
        }
        forward.join()
    }.conflate()

    private class Run(
        val request: ProductionRequest,
        val previous: Edit?,
        val taste: Taste,
        val feedback: String?,
        val state: MutableStateFlow<ProductionState>,
    ) {
        fun at(stage: Stage, within: Float, step: String) = state.update { s ->
            val p = Stage.entries.takeWhile { it != stage }.sumOf { it.weight.toDouble() }.toFloat() + stage.weight * within.coerceIn(0f, 1f)
            s.copy(stage = stage, step = step, progress = maxOf(s.progress, p.coerceAtMost(1f)))
        }
    }

    private suspend fun ProducerScope<ProductionState>.work(r: Run) {
        val request = r.request
        val job = currentCoroutineContext()[Job]
        r.at(Stage.Prepare, 0f, "prepare")
        val e = engines()
        val voice = platform.audio(request.media, VOICE_RATE)
        val video = request.kind == SourceKind.Video
        val frames = if (video) platform.frames(request.media, SUBJECT_FRAMES, LOOK_W) else emptyList()
        val found = SubjectFinder.locate(frames)
        r.at(Stage.Prepare, HALF, "attachments")
        val assets = request.assets.mapIndexed { i, a -> platform.asset(a.source, "asset-$i", a.caption) }

        // The words: the previous edit's, the checkpoint's, or whisper's (unloaded before the director loads: phones hold one model).
        val saved = if (r.previous == null) checkpoints.load(request.key) else null
        val transcript = r.previous?.transcript ?: saved?.transcript ?: transcribe(r, voice).also {
            checkpoints.save(request.key, Checkpoints.Saved(it))
        }

        val director = platform.director(request.director)
        val subject = director.eyes?.let { SubjectGrounding(it).ground(frames, found) } ?: found?.subject
        r.at(Stage.Direct, 0f, "direct")
        val taste = r.feedback?.let { r.taste.adjust(Collaborator(director.model).read(it)) } ?: r.taste
        val latest = MutableStateFlow<Compiler.Output?>(null)
        val renderLock = Mutex()
        val media = Footage { w, h -> platform.media(request, assets, FPS, w, h) }
        val previews = launch { preview(latest, r, e.previewer, media, renderLock) }
        val grabber = director.eyes?.let { FrameGrabber { output, times -> renderLock.withLock { times.map { t -> e.previewer.rgb(output, media.of(output), t, LOOK_W, LOOK_H) } } } }
        val autopilot = Autopilot(
            e.text, e.brands, visuals, director.model, knowledge, taste, director.eyes, grabber,
            jury = jury ?: Juries.forDevice(platform.device()),
            onCompiled = { job?.ensureActive(); latest.value = it },
        )
        val understanding = r.previous?.production?.understanding ?: saved?.understanding
        val production = autopilot.produce(
            Autopilot.Request(transcript, voice, request.brief, request.seed, footage = if (video) FOOTAGE else null, subject = subject, understanding = understanding, assets = assets),
        ) { step, p -> job?.ensureActive(); r.at(Stage.Direct, p, step) }
        if (understanding == null) checkpoints.save(request.key, Checkpoints.Saved(transcript, production.understanding))
        latest.value = production.compiled
        r.state.update { it.copy(panel = production.panel?.let(PanelSummary::of), report = production.report) }
        previews.cancel()
        renderLock.withLock { media.close() }

        r.at(Stage.Render, 0f, "render")
        val out = platform.export(production, request, assets, request.output ?: platform.outputFor(request)) { done, total ->
            job?.ensureActive()
            r.at(Stage.Render, done / total.coerceAtLeast(1).toFloat(), "render $done/$total")
        }
        // The finished edit is kept whole: it opens again after a restart (editor, exports) without whisper or the model.
        checkpoints.save(request.key, Checkpoints.Saved(transcript, production.understanding, production.delivery()))
        r.state.update {
            it.copy(
                status = ProductionStatus.Done, stage = Stage.Done, step = "done", progress = 1f, output = out,
                edit = Edit(request, production, out, transcript, taste, assets = assets),
            )
        }
    }

    /** Small frames of each newly compiled edit while the director works (one at a time, the newest wins). */
    private suspend fun preview(latest: MutableStateFlow<Compiler.Output?>, r: Run, previewer: Previewer, media: Footage, lock: Mutex) {
        var k = 0
        latest.filterNotNull().conflate().collect { output ->
            val image = lock.withLock { runCatching { previewer.frame(output, media.of(output), Previewer.time(k, output.composition.duration), Previewer.WIDTH) }.getOrNull() }
            if (image != null) {
                k++
                r.state.update { it.copy(previews = (it.previews + image).takeLast(ProductionState.MAX_PREVIEWS)) }
            }
        }
    }

    private suspend fun transcribe(r: Run, voice: io.trimio.core.model.audio.PcmAudio): Transcript {
        r.at(Stage.Transcribe, 0f, "transcribe")
        val recognizer = platform.recognizer() ?: error("No speech model is installed: download one in Settings → Models")
        val audio = io.trimio.engine.audio.Resampler().resample(voice, WHISPER_RATE)
        return try {
            recognizer.transcribe(audio, RecognitionOptions(Language.Persian, hint(r.request.brief), threads()), onProgress = { p -> r.at(Stage.Transcribe, p, "transcribe") })
        } finally {
            (recognizer as? AutoCloseable)?.close()
        }
    }

    /**
     * The footage for previews and the eyes, opened on the first frame drawn at the edit's own
     * size (the format decides it) and reopened only if a later edit changes it.
     */
    private class Footage(private val open: (Int, Int) -> MediaSource) {
        private var source: MediaSource? = null
        private var size = 0 to 0

        fun of(output: Compiler.Output): MediaSource {
            val c = output.composition
            val current = source
            if (current != null && size == c.width to c.height) return current
            close()
            size = c.width to c.height
            return open(c.width, c.height).also { source = it }
        }

        fun close() {
            (source as? AutoCloseable)?.close()
            source = null
        }
    }

    companion object {
        const val VOICE_RATE = 48_000
        const val WHISPER_RATE = 16_000
        const val FOOTAGE = "main"
        const val FPS = 30
        private val RETOUCHED = Regex("-e[0-9a-f]{8}\\.mp4$")
        private const val LOOK_W = 288
        private const val LOOK_H = 512
        private const val SUBJECT_FRAMES = 5
        private const val HALF = 0.5f
        private const val DEFAULT_THREADS = 4

        /**
         * Whisper's conditioning: a short, well-punctuated sentence in the spoken language (better
         * punctuation and spelling), naming the brief's Latin names so they are spelled right.
         */
        fun hint(brief: String): String {
            val names = Regex("[A-Za-z][A-Za-z0-9.+-]*(?: [A-Z][A-Za-z0-9.+-]*)*").findAll(brief).map { it.value }.distinct().take(MAX_NAMES).toList()
            return if (names.isEmpty()) "سلام دوستان، امروز می‌خواهم یک نکته‌ی مهم را بگویم." else "در این ویدیو درباره‌ی " + names.joinToString("، ") + " صحبت می‌کنم."
        }

        private const val MAX_NAMES = 12
    }
}
