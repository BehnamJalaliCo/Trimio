package io.trimio.engine.production

import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.SpeechRuntime
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
import io.trimio.engine.autopilot.explainer.ExplainerPilot
import io.trimio.engine.autopilot.longform.LongFormKind
import io.trimio.engine.autopilot.longform.LongFormPilot
import io.trimio.engine.autopilot.longform.SubtitleReel
import io.trimio.engine.autopilot.explainer.judges.FilmJury
import io.trimio.engine.motion.explainer.IconResolver
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.recipe.SubtitleStyle
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.visual.VisualLibrary
import io.trimio.engine.perception.AddOnPass
import io.trimio.engine.production.diagnostics.Fact
import io.trimio.engine.production.diagnostics.RenderTime
import io.trimio.engine.production.diagnostics.RunOutcome
import io.trimio.engine.production.diagnostics.RunRecorder
import io.trimio.engine.production.diagnostics.RunReport
import io.trimio.engine.production.diagnostics.Runtime
import io.trimio.engine.production.diagnostics.SpeechTime
import io.trimio.engine.production.diagnostics.Watchdog
import io.trimio.engine.production.diagnostics.wallClock
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
    /** The app's version and platform, for the technical report. */
    private val app: String = "",
    /** Wall clock (epoch ms) for the technical report; tests pass a fake one. */
    private val clock: () -> Long = ::wallClock,
    /** A voice recording becomes an explainer film ([ExplainerPilot]); false keeps the motion edit over a plain background. */
    private val explainers: Boolean = true,
) : AutoEditor {

    /** The run being recorded now, for the watchdog's notes ([stalled]). */
    @kotlin.concurrent.Volatile
    private var recording: RunRecorder? = null

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
        saved?.film?.let { cut -> return@withContext reopenFilm(request, cut, saved.transcript, overrides) }
        // A film of an earlier version kept nothing to draw it again from.
        if (saved?.delivery == null && request.kind != SourceKind.Video && explainers) return@withContext Opened.Explainer
        val kept = saved?.delivery?.takeIf { it.version == Delivery.VERSION } ?: return@withContext Opened.NotKept
        // The creator's own files are read again: a video, recording or attachment that is gone is said so.
        val media = readable {
            val voice = platform.audio(request.media, voiceRate(request))
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

    /** An explainer film drawn and mixed again from what was kept of it ([ExplainerPilot.restore]), over the creator's recording. */
    private suspend fun reopenFilm(request: ProductionRequest, cut: io.trimio.engine.autopilot.explainer.FilmCut, words: Transcript, overrides: EditOverrides): Opened {
        val voice = readable { platform.audio(request.media, voiceRate(request)).also { check(it.samples.isNotEmpty()) { "no sound in ${request.media}" } } }
            ?: return Opened.SourceMissing
        val production = ExplainerPilot(filmStage(engines())).restore(cut, voice)
        val director = Edit(request, production, request.output ?: platform.outputFor(request), words)
        return Opened.Ready(if (overrides.isEmpty) director else touch(director, overrides))
    }

    /** The explainer stage with the illustration layer's pictures (and the generator add-on when it is installed). */
    private fun filmStage(e: Engines) =
        IllustratedStage(e.text, IconResolver.of(visuals), { io.trimio.engine.illustration.Illustrator.create(engine = platform.diffusion()) }, checkpoints.pictures)

    override suspend fun kept(request: ProductionRequest): Boolean =
        withContext(Dispatchers.Default) { checkpoints.load(request.key)?.let { it.delivery?.version == Delivery.VERSION || it.film != null } == true }

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

    override suspend fun lighter(current: String?): DirectorChoice = platform.lighter(current)

    override fun stallLimitMs(): Long = Watchdog.limitFor(platform.device().tier)

    override fun stalled(key: String, step: String, minutes: Int, choice: String?) {
        val recorder = recording?.takeIf { it.key == key } ?: return
        if (choice == null) recorder.stall(step, minutes) else recorder.stallChoice(choice)
    }

    private fun run(request: ProductionRequest, previous: Edit?, taste: Taste, feedback: String? = null): Flow<ProductionState> = channelFlow {
        val state = MutableStateFlow(ProductionState(request.key))
        // Every distinct state goes out in order; the flow ends after the final one.
        val forward = launch { state.first { s -> send(s); s.finished } }
        val recorder = RunRecorder(
            request.key, checkpoints.reports, SESSION, clock, platform::memory,
            onChange = { report -> state.update { it.copy(technical = report) } },
            onBeat = { beat -> state.update { it.copy(heartbeat = beat) } },
        )
        recording = recorder
        try {
            withContext(Dispatchers.Default) {
                val died = recorder.begin(app, readable { platform.about() }.orEmpty())
                if (died != null) {
                    // The process ended twice in the same step (memory, the native engine): say so instead of dying in a loop.
                    recorder.finish(RunOutcome.Failed)
                    state.update { it.copy(status = ProductionStatus.Failed, error = ProductionState.DIED + died) }
                } else {
                    work(Run(request, previous, taste, feedback, state, recorder))
                }
            }
        } catch (e: CancellationException) {
            recorder.finish(RunOutcome.Cancelled)
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Any engine failure ends the run with its message (and its stack in the report); the screen offers to retry.
            recorder.error(e)
            recorder.finish(RunOutcome.Failed)
            state.update { it.copy(status = ProductionStatus.Failed, error = e.message ?: e::class.simpleName) }
        } finally {
            if (recording === recorder) recording = null
        }
        forward.join()
    }.conflate()

    private class Run(
        val request: ProductionRequest,
        val previous: Edit?,
        val taste: Taste,
        val feedback: String?,
        val state: MutableStateFlow<ProductionState>,
        val recorder: RunRecorder,
    ) {
        /** Where whisper ran, once it has (null when the words came from a checkpoint). */
        var speech: SpeechRuntime? = null

        fun at(stage: Stage, within: Float, step: String) {
            if (step != "done") recorder.step(step)
            state.update { s ->
                val p = Stage.entries.takeWhile { it != stage }.sumOf { it.weight.toDouble() }.toFloat() + stage.weight * within.coerceIn(0f, 1f)
                s.copy(stage = stage, step = step, progress = maxOf(s.progress, p.coerceAtMost(1f)))
            }
        }
    }

    private suspend fun ProducerScope<ProductionState>.work(r: Run) {
        val request = r.request
        val job = currentCoroutineContext()[Job]
        val recorder = r.recorder
        r.at(Stage.Prepare, 0f, "prepare")
        val e = engines()
        val video = request.kind == SourceKind.Video
        val voice = recorder.native("decode sound") { platform.audio(request.media, voiceRate(request)) }
        val frames = if (video) recorder.native("decode frames") { platform.frames(request.media, SUBJECT_FRAMES, LOOK_W) } else emptyList()
        // Installed perception add-ons only (none installed: null, and every hook below returns its input).
        // Each runs under its own marker; an attempt after a death inside one runs without them.
        val skipAddOns = addOnDeath(recorder)
        val addOns = if (skipAddOns) null else recorder.native(ADD_ONS) { platform.perception()?.let(::AddOnPass) }
        val pixels = recorder.native("subject") { SubjectFinder.locate(frames) }
        val found = recorder.native(ADD_ONS) { addOns.located(frames, pixels) }
        r.at(Stage.Prepare, HALF, "attachments")
        val assets = recorder.native("attachments") { request.assets.mapIndexed { i, a -> platform.asset(a.source, "asset-$i", a.caption) } }

        // The words: the previous edit's, the checkpoint's, or whisper's (unloaded before the director loads: phones hold one model).
        val saved = if (r.previous == null) checkpoints.load(request.key) else null
        val heard = r.previous?.transcript ?: saved?.transcript
        val transcript = heard ?: transcribe(r, voice).let { t -> recorder.native(ADD_ONS) { addOns.refined(t, voice, WHISPER_RATE) } }.also {
            checkpoints.save(request.key, Checkpoints.Saved(it))
        }

        // Two voices or more (the speaker add-ons): each one's captions get their colour.
        val voices = if (video) recorder.native(ADD_ONS) { addOns.voices(transcript, voice, WHISPER_RATE) } else emptyList()
        // The video's own music (a steady beat under the voice): no music of ours on top, hits on its beat.
        val tempo = if (video && request.edit == EditKind.Reel) recorder.native(ADD_ONS) { addOns.sourceTempo(voice) } else null

        // The director loads (and its eyes find the speaker) within "direct": the screen counts it as listening.
        r.at(Stage.Direct, 0f, "direct")
        // Killed for memory inside the director on an earlier attempt, or little free now: a lean run (no eyes, no web
        // animations, no live previews), which the report says.
        MemoryPressure.warned = false
        val lean = directorDeath(recorder) || MemoryPressure.low()
        val director = directorOf(request.director, recorder)
        recorder.models(
            modelFacts(director, if (heard == null) platform.speechModel() else null, request.director) + addOns.facts() +
                listOfNotNull(
                    Fact("add-on", "skipped: the app ended inside an add-on on an earlier attempt").takeIf { skipAddOns },
                    Fact("lean run", if (directorDeath(recorder)) "the system ended an earlier attempt inside the director" else "little free memory").takeIf { lean },
                ),
        )
        val subject = director.eyes?.takeUnless { lean }?.let { SubjectGrounding(it).ground(frames, found) } ?: found?.subject
        // The eyes found the speaker: their projector (about 1 GB) is freed until they look again.
        director.eyes?.let { eyes -> runCatching { if (eyes === director.model) eyes.releaseSight() else eyes.release() } }
        r.at(Stage.Direct, 0f, "direct")
        val taste = r.feedback?.let { r.taste.adjust(Collaborator(director.model).read(it)) } ?: r.taste
        val latest = MutableStateFlow<Compiler.Output?>(null)
        val renderLock = Mutex()
        val media = Footage { w, h -> platform.media(request, assets, FPS, w, h) }
        val previews = launch { if (!lean) preview(latest, r, e.previewer, media, renderLock) }
        val grabber = director.eyes?.let { FrameGrabber { output, times -> renderLock.withLock { times.map { t -> e.previewer.rgb(output, media.of(output), t, LOOK_W, LOOK_H) } } } }
        val autopilot = Autopilot(
            e.text, e.brands, visuals, director.model, knowledge, taste, director.eyes, grabber,
            jury = recorder.instrument(jury ?: Juries.forDevice(platform.device())),
            onCompiled = { job?.ensureActive(); latest.value = it },
            onDraft = { d -> checkpoints.save(request.key, Checkpoints.Saved(transcript, draft = d, by = director.model?.id)) },
            // The whole reading the moment it is done: an attempt after a kill starts at the plan.
            onUnderstood = { u -> checkpoints.save(request.key, Checkpoints.Saved(transcript, u, by = director.model?.id)) },
            lean = { lean || MemoryPressure.low() },
        )
        // A reading kept from an earlier run is reused only when the same director made it: a run without a model
        // (or with another one) never stands in for the director now loaded.
        val kept = saved?.takeIf { it.by == director.model?.id }
        val understanding = r.previous?.production?.understanding ?: kept?.understanding
        val material = Material(transcript, voice, frames, assets, subject, voices, tempo, understanding, kept?.draft)
        val production = direct(r, material, director, autopilot, e) { step, p -> job?.ensureActive(); r.at(Stage.Direct, p, step) }
        if (understanding == null) checkpoints.save(request.key, Checkpoints.Saved(transcript, production.understanding, by = director.model?.id))
        latest.value = production.compiled
        r.state.update { it.copy(panel = production.panel?.let(PanelSummary::of), report = production.report) }
        previews.cancel()
        renderLock.withLock { media.close() }
        // Where the engines ran (build, GPU, layers, threads), as they report it once they have worked.
        val runtime = Runtime.of(director.model?.runtime, r.speech)
        recorder.runtime(runtime)

        r.at(Stage.Render, 0f, "render")
        val renderStart = clock()
        var frameCount = 0
        val out = recorder.native("render") {
            platform.export(production, request, assets, request.output ?: platform.outputFor(request)) { done, total ->
                job?.ensureActive()
                frameCount = done
                r.at(Stage.Render, done / total.coerceAtLeast(1).toFloat(), "render $done/$total")
            }
        }
        val c = production.compiled.composition
        recorder.render(RenderTime(frameCount, (clock() - renderStart) / MS, c.width, c.height))
        // A long-form edit's subtitle files and its YouTube text go beside the video.
        production.youtube?.let { YouTubeFiles.write(out, it, persian(transcript)) }
        // The finished edit is kept whole: it opens again after a restart (editor, exports) without whisper or the model.
        checkpoints.save(
            request.key,
            Checkpoints.Saved(transcript, production.understanding, production.delivery(), by = director.model?.id, film = production.filmBench?.cut),
        )
        recorder.finish(RunOutcome.Done)
        checkpoints.reports.saveEngine(recorder.engine(runtime, checkpoints.reports.engine()))
        r.state.update {
            it.copy(
                status = ProductionStatus.Done, stage = Stage.Done, step = "done", progress = 1f, output = out,
                edit = Edit(request, production, out, transcript, taste, assets = assets),
            )
        }
    }

    /** What the director works from: the words, the sound, the footage and what was found in it. */
    private class Material(
        val transcript: Transcript,
        val voice: io.trimio.core.model.audio.PcmAudio,
        val frames: List<io.trimio.engine.llm.RgbImage>,
        val assets: List<UserAsset>,
        val subject: io.trimio.engine.motion.score.Subject?,
        val voices: List<io.trimio.engine.motion.score.Voice>,
        val tempo: Pair<Float, Float>?,
        val understanding: io.trimio.engine.autopilot.Understanding?,
        val draft: io.trimio.engine.autopilot.UnderstandingDraft?,
    )

    /** The director the request asked for, every call of it (and of its eyes) on the run's record. */
    private suspend fun directorOf(choice: DirectorChoice, recorder: RunRecorder): Director {
        val d = platform.director(choice)
        val model = d.model?.let(recorder::instrument)
        val eyes = d.eyes?.let { if (it === d.model) model else recorder.instrument(it) }
        return Director(model, eyes, d.note)
    }

    /**
     * The edit itself: a voice alone becomes an explainer film (phase 8c), a long YouTube video keeps its recording
     * with subtitles and, for the pro kind, YouTube's devices (phase 9.7); everything else is the reel.
     */
    private suspend fun direct(r: Run, m: Material, director: Director, autopilot: Autopilot, e: Engines, progress: (String, Float) -> Unit): Autopilot.Production {
        val request = r.request
        val video = request.kind == SourceKind.Video
        if (!video && explainers) {
            val budget = (jury ?: Juries.forDevice(platform.device())).budget
            val stage = filmStage(e)
            val others = listOfNotNull(r.previous?.production?.film?.art)
            val ask = ExplainerPilot.Request(m.transcript, m.voice, request.brief, request.seed, m.understanding, FPS, others = others)
            return ExplainerPilot(stage, director.model, FilmJury(budget = budget)).produce(ask, progress)
        }
        val style = SubtitleStyle.of(request.subtitles)
        if (video && request.edit == EditKind.Subtitles) {
            // A plain reel: its silences cut and its words in the picked subtitle style, nothing else (phase 9.9).
            val ask = SubtitleReel.Request(
                m.transcript, m.voice, request.brief, request.seed, style ?: SubtitleStyle.Pop,
                footage = FOOTAGE, fps = FPS, subject = m.subject, voices = m.voices,
            )
            return SubtitleReel(e.text, e.brands, visuals, director.model).produce(ask, progress)
        }
        if (video && request.edit.longForm) {
            val kind = if (request.edit == EditKind.YouTubePro) LongFormKind.Pro else LongFormKind.Subtitles
            val format = if (landscape(m.frames)) "16:9" else "9:16"
            val logo = logoOf(m.assets)?.let { it.id to it.aspect }
            val ask = LongFormPilot.Request(
                m.transcript, m.voice, request.brief, request.seed, kind, format = format,
                footage = FOOTAGE, fps = FPS, subject = m.subject, logo = logo, voices = m.voices, style = style ?: SubtitleStyle.Classic,
            )
            return LongFormPilot(e.text, e.brands, visuals, director.model).produce(ask, progress)
        }
        // The video's own music: no music of ours on top.
        val brief = if (m.tempo != null) request.brief + "\n\nno music (the video has its own)" else request.brief
        val ask = Autopilot.Request(
            m.transcript, m.voice, brief, request.seed, footage = if (video) FOOTAGE else null, subject = m.subject,
            understanding = m.understanding, assets = m.assets, draft = m.draft, voices = m.voices, sourceTempo = m.tempo,
        )
        return autopilot.produce(ask, progress)
    }

    /** Whether most words are written in Persian letters (the YouTube text is then written in Persian). */
    private fun persian(transcript: Transcript): Boolean =
        transcript.words.count { w -> w.text.any { ch -> ch in '\u0600'..'\u06FF' } } * 2 > transcript.words.size

    /** Small frames of each newly compiled edit while the director works (one at a time, the newest wins). */
    private suspend fun preview(latest: MutableStateFlow<Compiler.Output?>, r: Run, previewer: Previewer, media: Footage, lock: Mutex) {
        var k = 0
        latest.filterNotNull().conflate().collect { output ->
            // A preview is a decoded frame and a render: when memory runs low it waits for the next edit.
            if (MemoryPressure.low()) return@collect
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
        val audio = if (voice.sampleRate == WHISPER_RATE) voice else io.trimio.engine.audio.Resampler().resample(voice, WHISPER_RATE)
        val threads = threads()
        val start = clock()
        return try {
            r.recorder.native("transcribe") {
                // The percent as the step's detail: the run report (and its memory samples) moves while Whisper reads.
                val options = RecognitionOptions(Language.Persian, hint(r.request.brief), threads)
                recognizer.transcribe(audio, options, onProgress = { p -> r.at(Stage.Transcribe, p, "transcribe ${(p * PERCENT).toInt()}%") })
            }.also { t ->
                r.speech = recognizer.runtime
                r.recorder.speechRuntime = recognizer.runtime
                r.recorder.liveRuntime(null)
                val seconds = audio.samples.size / audio.sampleRate.toFloat()
                r.recorder.speech(SpeechTime(platform.speechModel(), seconds, (clock() - start) / MS, recognizer.runtime?.threads ?: threads, t.words.size))
            }
        } finally {
            (recognizer as? AutoCloseable)?.close()
        }
    }

    /**
     * The rate the voice is read at: a long-form edit keeps the recording's own sound in its file ([sourceSound]), so
     * its voice is only heard at the speech rate (an hour at 48 kHz would not fit a phone's heap).
     */
    private fun voiceRate(request: ProductionRequest): Int =
        if (request.kind == SourceKind.Video && request.edit.longForm) WHISPER_RATE else VOICE_RATE

    /** Whether the footage is wider than tall (its sampled frames), so a long-form edit keeps its 16:9 frame. */
    private fun landscape(frames: List<io.trimio.engine.llm.RgbImage>): Boolean = frames.firstOrNull()?.let { it.width >= it.height } ?: true

    /** The channel's logo among the attachments: a picture read as a logo, with transparency, or named so. */
    private fun logoOf(assets: List<UserAsset>): UserAsset? = assets.firstOrNull { a ->
        a.kind == UserAsset.Kind.Logo || (a.kind == UserAsset.Kind.Image && (a.alpha != null || listOfNotNull(a.caption, a.fileName).any { "logo" in it.lowercase() || "لوگو" in it }))
    }

    /** Who directed, who looked and who listened, for the technical report. */
    private fun modelFacts(director: Director, speech: String?, choice: DirectorChoice): List<Fact> = listOfNotNull(
        Fact(RunReport.DIRECTOR, director.model?.let { "${it.id} (${if (it.isLocal) "on device" else "cloud"})" } ?: ("rules (no model)" + (director.note?.let { ": $it" } ?: ""))),
        Fact("asked for", choice.toString()),
        director.eyes?.let { Fact(RunReport.EYES, if (it === director.model) "${it.id} (the director's own)" else it.id) },
        speech?.let { Fact(RunReport.SPEECH, it) },
    )

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
        /** This process: a death marker another session left means that process ended mid-run. */
        val SESSION: String = kotlin.random.Random.nextLong().toULong().toString(36)
        private const val MS = 1000f
        const val VOICE_RATE = 48_000
        const val WHISPER_RATE = 16_000
        const val FOOTAGE = "main"
        const val FPS = 30
        private val RETOUCHED = Regex("-e[0-9a-f]{8}\\.mp4$")
        private const val LOOK_W = 288
        private const val LOOK_H = 512
        private const val SUBJECT_FRAMES = 5
        private const val HALF = 0.5f
        private const val PERCENT = 100
        private const val DEFAULT_THREADS = 4

        /**
         * Whisper's conditioning: a short, well-punctuated sentence in the spoken language naming the
         * brief's names and the terms of its field ([io.trimio.engine.autopilot.Loanwords.prompt]):
         * the terms are heard as they are written, not repaired afterwards.
         */
        fun hint(brief: String): String = io.trimio.engine.autopilot.Loanwords.prompt(brief)
    }
}
