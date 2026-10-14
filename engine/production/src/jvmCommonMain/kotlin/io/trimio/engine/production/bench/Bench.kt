package io.trimio.engine.production.bench

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.whisper.WhisperCppRecognizer
import io.trimio.engine.illustration.generate.SdNative
import io.trimio.engine.llm.local.LlamaProbe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * One measurement of «سنجش سرعت این دستگاه». On a phone each runs in its own process ([BenchRunner]): a GPU driver that
 * crashes takes that process down, not the app, and the way it was trying is marked unusable.
 */
@Serializable
sealed interface BenchStep {
    /**
     * The director loaded once with [layers] on the GPU (0 none, -1 all that fit) and [draft] MTP guesses, each of
     * [threads] processor thread counts timed [passes] times on that one load.
     */
    @Serializable
    @SerialName("director")
    data class Director(val model: String, val threads: List<Int>, val layers: Int, val draft: Int = 0, val passes: Int = PASSES) : BenchStep

    /** Whisper loaded once, warmed up, then reading [SPEECH_SECONDS] of sound at each of [threads]. */
    @Serializable
    @SerialName("speech")
    data class Speech(val model: String, val threads: List<Int>) : BenchStep

    /**
     * The picture engine drawing a small picture: its [files] (one, or the diffusion model, text encoder and VAE), each
     * part placed by [backend] (sd.cpp's assignments: "cpu", "all=GPUOpenCL,te=cpu"), [maxVram] the GPU budget,
     * [gpuLibrary] the GPU build of the engine.
     */
    @Serializable
    @SerialName("picture")
    data class Picture(
        val files: List<String>,
        val threads: Int,
        val backend: String,
        val maxVram: String = "",
        val gpuLibrary: Boolean = false,
        val size: Int = PICTURE_SIZE,
        val steps: Int = PICTURE_STEPS,
    ) : BenchStep

    /** What the picture engine's GPU build can run on («CPU», «GPUOpenCL», «Vulkan0»). */
    @Serializable
    @SerialName("devices")
    data object Devices : BenchStep

    companion object {
        const val PASSES = 2
        const val SPEECH_SECONDS = 15
        const val PICTURE_SIZE = 256
        const val PICTURE_STEPS = 1
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(step: BenchStep): String = json.encodeToString(serializer(), step)

        fun decode(text: String): BenchStep = json.decodeFromString(serializer(), text)
    }
}

/** What a [BenchStep] measured; [crashed] when its process died, [note] why it gave nothing. */
@Serializable
data class BenchResult(
    val ok: Boolean,
    val read: Double = 0.0,
    val write: Double = 0.0,
    val onGpu: Boolean = false,
    val offloaded: Int = 0,
    val totalLayers: Int = 0,
    val draft: Int = 0,
    /** The director's engine is a GPU build (it can try layers on the GPU at all). */
    val gpuBuild: Boolean = false,
    val seconds: Double = 0.0,
    val devices: List<String> = emptyList(),
    val crashed: Boolean = false,
    /** Given up past its time limit: far slower than the way it was measured against. */
    val tooSlow: Boolean = false,
    val note: String = "",
    /** Each thread count timed on the one load (director: read and write; speech: [Rate.seconds]). */
    val rates: List<Rate> = emptyList(),
    /** The director model has an MTP head, so guessing ahead is worth timing. */
    val mtpHead: Boolean = false,
) {
    @Serializable
    data class Rate(val threads: Int, val read: Double = 0.0, val write: Double = 0.0, val seconds: Double = 0.0)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(result: BenchResult): String = json.encodeToString(serializer(), result)

        fun decode(text: String): BenchResult = json.decodeFromString(serializer(), text)

        fun crash(why: String) = BenchResult(ok = false, crashed = true, note = why)
    }
}

/** Runs [BenchStep]s: here ([LocalBench]), or in a process of their own on a phone. */
fun interface BenchRunner {
    /** Runs [step]; a runner that can stop a measurement gives it up after [limitMs] ([BenchResult.tooSlow]). */
    suspend fun run(step: BenchStep, limitMs: Long?): BenchResult
}

/**
 * The measurements in this process: the desktop's speed test, and the phone's bench process itself. A native call
 * cannot be stopped in the middle here, so the time limit is not kept.
 */
object LocalBench : BenchRunner {
    override suspend fun run(step: BenchStep, limitMs: Long?): BenchResult = run(step)

    suspend fun run(step: BenchStep): BenchResult = runCatching {
        when (step) {
            is BenchStep.Director -> director(step)
            is BenchStep.Speech -> speech(step)
            is BenchStep.Picture -> picture(step)
            BenchStep.Devices -> BenchResult(ok = true, devices = devices())
        }
    }.getOrElse { e -> BenchResult(ok = false, note = e.message ?: e::class.simpleName.orEmpty()) }

    private fun director(s: BenchStep.Director): BenchResult {
        val sweep = LlamaProbe.sweep(s.model, s.threads, s.layers, s.draft, s.passes) ?: return BenchResult(ok = false, note = "the model did not load")
        val (_, p) = sweep.rates.minBy { (_, probe) -> probe.callSeconds }
        return BenchResult(
            ok = true, read = p.readPerSecond, write = p.writePerSecond, onGpu = p.onGpu, offloaded = p.offloaded, totalLayers = p.totalLayers,
            draft = p.draft, gpuBuild = LlamaProbe.gpuBuild, mtpHead = sweep.mtpHead,
            rates = sweep.rates.map { (t, probe) -> BenchResult.Rate(t, probe.readPerSecond, probe.writePerSecond) },
        )
    }

    private suspend fun speech(s: BenchStep.Speech): BenchResult {
        // Quiet noise: the encoder works on a full window whatever is said, which is what the test times.
        val rng = Random(1)
        val sound = PcmAudio(FloatArray(BenchStep.SPEECH_SECONDS * PcmAudio.WHISPER_RATE) { (rng.nextFloat() - 0.5f) * NOISE }, PcmAudio.WHISPER_RATE)
        val rates = WhisperCppRecognizer(s.model).use { w ->
            // The first read loads the model and warms the caches; each thread count after it is timed once.
            w.transcribe(PcmAudio(sound.samples.copyOf(PcmAudio.WHISPER_RATE * WARM_SECONDS), PcmAudio.WHISPER_RATE), options(s.threads.first()))
            s.threads.map { t ->
                val start = System.nanoTime()
                w.transcribe(sound, options(t))
                BenchResult.Rate(t, seconds = (System.nanoTime() - start) / NANOS)
            }
        }
        return BenchResult(ok = true, seconds = rates.minOf { it.seconds }, rates = rates)
    }

    private fun options(threads: Int) = RecognitionOptions(Language.Persian, threads = threads, exactThreads = true)

    private fun picture(s: BenchStep.Picture): BenchResult {
        SdNative.wantGpu = s.gpuLibrary
        if (!SdNative.available) return BenchResult(ok = false, note = "the picture engine is not in this build")
        val handle = if (s.files.size > 1) {
            SdNative.initParts(s.files[0], s.files[1], s.files.getOrElse(2) { "" }, s.threads, s.backend, s.maxVram)
        } else {
            SdNative.init(s.files[0], s.threads, s.backend, s.maxVram)
        }
        if (handle == 0L) return BenchResult(ok = false, note = "the picture model did not load this way")
        return try {
            // A tiny first picture reads every weight in and builds the graphs (cheap: few pixels); the small one is timed.
            draw(handle, s, WARM_SIZE)
            val start = System.nanoTime()
            val out = draw(handle, s, s.size) ?: return BenchResult(ok = false, note = "no picture came out this way")
            BenchResult(ok = out.isNotEmpty(), seconds = (System.nanoTime() - start) / NANOS, onGpu = s.backend != "cpu")
        } finally {
            SdNative.free(handle)
        }
    }

    private fun draw(handle: Long, s: BenchStep.Picture, size: Int): ByteArray? =
        SdNative.generate(handle, PROMPT, "", size, size, SEED, s.steps, 1f, null, 0f, null, null)

    private fun devices(): List<String> {
        SdNative.wantGpu = true
        if (!SdNative.available) return emptyList()
        return SdNative.devices().lines().map { it.substringBefore('\t').trim() }.filter { it.isNotEmpty() }
    }

    private const val NOISE = 0.002f
    private const val NANOS = 1e9
    private const val WARM_SECONDS = 3
    private const val WARM_SIZE = 64
    private const val SEED = 7L
    private const val PROMPT = "pencil sketch of a camel in the desert, plain white background"
}
