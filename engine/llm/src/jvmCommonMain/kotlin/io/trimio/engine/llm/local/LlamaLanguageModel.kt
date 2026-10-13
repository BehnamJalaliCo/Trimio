package io.trimio.engine.llm.local

import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.ChatTemplates
import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.JsonSchemaGrammar
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.LanguageModelException
import io.trimio.engine.llm.ModelRuntime
import io.trimio.engine.llm.StopReason
import io.trimio.engine.llm.Usage
import io.trimio.engine.models.ChatFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Receives generated text from native code, on the generating thread. */
fun interface LlamaCallback {
    /** UTF-8 bytes of one or more complete characters; return false to stop. */
    fun onText(bytes: ByteArray): Boolean
}

/** JNI entry points of `libtrimio_llama` (native/llama/trimio_llama_jni.cpp). */
internal object LlamaNative {
    // Initialised before [variant], which reads them while the object initialises.
    private val FILES = listOf("libtrimio_llama.so", "libtrimio_llama.dylib", "trimio_llama.dll")
    private val GPU_VARIANTS = setOf("vulkan", "cuda", "metal", "opencl")
    /** Native side: as many layers as the GPU's free memory holds, the rest on the CPU. */
    private const val FITTING_LAYERS = -1

    /**
     * The build that loaded. `-Dtrimio.native.variants` lists subdirectories of `trimio.native.dir`
     * best first (the desktop app: "vulkan,cpu"); a GPU build whose runtime is missing fails to
     * load and the next is tried, the flat directory last (docs/DESKTOP.md).
     */
    val variant: String = load()

    /**
     * Layers offloaded to the GPU: with a GPU build, as many as fit in its free memory (the rest run
     * on the CPU from RAM), unless `-Dtrimio.llama.gpuLayers` says otherwise.
     */
    val gpuLayers: Int get() = System.getProperty("trimio.llama.gpuLayers")?.toIntOrNull() ?: if (variant in GPU_VARIANTS) FITTING_LAYERS else 0

    private fun load(): String {
        val dir = System.getProperty("trimio.native.dir")
        val variants = System.getProperty("trimio.native.variants").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty) + ""
        var failure: Throwable? = null
        for (variant in variants) {
            val file = dir?.let { d -> FILES.map { File(File(d, variant), it) }.firstOrNull(File::isFile) } ?: continue
            if (runCatching { System.load(file.absolutePath) }.onFailure { failure = it }.isSuccess) return variant
        }
        failure?.takeIf { dir != null }?.let { throw it }
        // Android: the GPU (OpenCL) engine when the phone has the driver, else the CPU one.
        if (dir == null && System.getProperty("trimio.llama.gpu") != "off" &&
            runCatching { System.loadLibrary("trimio_llama_gpu") }.isSuccess
        ) {
            return "opencl"
        }
        System.loadLibrary("trimio_llama")
        return ""
    }

    /**
     * Engine options, from `-Dtrimio.llama.*` (or their defaults) in the order the native side
     * reads them: KV cache type (ggml type: 1 f16, 8 q8_0), flash attention (-1 auto, 0, 1),
     * n_batch, n_ubatch, prompt-head reuse, lazy grammar, the share of the weights (percent)
     * a GPU must be able to run before a model is put on it, and the tokens the model's own MTP
     * head guesses per step (`-Dtrimio.llama.draft`; only models built with NextN layers use it,
     * e.g. Qwen3.5 MTP GGUFs: the output is the same, the decode faster when guesses hold).
     */
    fun options(): IntArray = intArrayOf(
        when (prop("kv")) { "f16" -> F16; else -> Q8_0 },
        when (prop("flash")) { "on" -> 1; "off" -> 0; else -> -1 },
        prop("batch")?.toIntOrNull() ?: BATCH,
        prop("ubatch")?.toIntOrNull() ?: BATCH,
        if (prop("prefixCache") == "off") 0 else 1,
        if (prop("lazyGrammar") == "off") 0 else 1,
        prop("minGpuCoverage")?.toIntOrNull() ?: MIN_GPU_COVERAGE,
        prop("draft")?.toIntOrNull() ?: DRAFT,
    )

    private fun prop(name: String): String? = System.getProperty("trimio.llama.$name")?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

    private const val F16 = 1
    private const val Q8_0 = 8
    private const val BATCH = 512
    private const val MIN_GPU_COVERAGE = 90

    /** MTP guesses per step by default (0: off until measured on the devices). */
    private const val DRAFT = 0

    external fun init(modelPath: String, contextSize: Int, threads: Int, gpuLayers: Int, options: IntArray): Long
    external fun runtimeInfo(handle: Long): String

    /** The last call: prompt tokens, reused tokens, generated tokens, prompt µs, generate µs, grammar resamples, MTP guesses, guesses kept. */
    external fun lastCall(handle: Long): LongArray
    external fun free(handle: Long)
    external fun contextSize(handle: Long): Int
    external fun countTokens(handle: Long, text: ByteArray): Int
    external fun applyTemplate(handle: Long, roles: Array<String>, contents: Array<ByteArray>): ByteArray?
    external fun generate(
        handle: Long, prompt: ByteArray, grammar: String?, maxTokens: Int,
        temperature: Float, topP: Float, minP: Float, seed: Int, callback: LlamaCallback,
    ): Int
    external fun generateWithImages(
        handle: Long, prompt: ByteArray, images: Array<ByteArray>, widths: IntArray, heights: IntArray, grammar: String?, maxTokens: Int,
        temperature: Float, topP: Float, minP: Float, seed: Int, callback: LlamaCallback,
    ): Int
    external fun initVision(handle: Long, projectorPath: String, threads: Int, maxImageTokens: Int): Boolean
    external fun systemInfo(): String

    // Encoders (native `Encoder`): embedders and relevance judges, on the CPU.

    /** [mode]: [ENCODE_EMBED], [ENCODE_RANK] or [ENCODE_JUDGE]; 0 when the file is not that kind of model. */
    external fun encoderInit(modelPath: String, contextSize: Int, threads: Int, sequences: Int, mode: Int): Long
    external fun encoderFree(handle: Long)
    external fun encoderInfo(handle: Long): String

    /** Pooled vectors of [texts], n × dims; null on failure or a non-finite value. */
    external fun encode(handle: Long, texts: Array<ByteArray>, normalise: Boolean): FloatArray?

    /** P(yes) of each document continuing the shared [head]; null on failure. */
    external fun judge(handle: Long, head: ByteArray, docs: Array<ByteArray>): FloatArray?
    external fun encoderTokens(handle: Long, text: ByteArray, special: Boolean): Int

    const val ENCODE_EMBED = 0
    const val ENCODE_RANK = 1
    const val ENCODE_JUDGE = 2

    /** Placeholder llama.cpp's multimodal tokenizer replaces with an image's tokens. */
    const val MEDIA_MARKER = "<__media__>"
}

/**
 * An on-device director model (GGUF through llama.cpp). The model is loaded on first use and
 * stays resident until [close], because the pipeline may ask twice (plan + one correction);
 * the Direction stage closes it before rendering so its gigabytes are free for the encoder.
 *
 * Structured requests are constrained by a grammar compiled from the schema, so even a 4B model
 * returns a valid plan every time.
 */
class LlamaLanguageModel(
    override val id: String,
    private val modelPath: String,
    private val format: ChatFormat,
    private val contextSize: Int = 12_288,
    private val threads: Int = defaultThreads(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** The model's vision projector (mmproj): with it the same model also looks at images. */
    private val visionPath: String? = null,
    /** Token budget per image (dynamic-resolution models): bounds the cost of looking on a phone. */
    private val maxImageTokens: Int = 256,
    /** Written after the assistant header, e.g. an empty reasoning block ([io.trimio.engine.models.DirectorProfile.assistantPrefix]). */
    private val assistantPrefix: String = "",
) : LanguageModel, AutoCloseable {
    override val isLocal = true
    override val canSee: Boolean get() = visionPath != null && File(visionPath).isFile

    private val mutex = Mutex()
    private var handle = 0L

    override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation = mutex.withLock {
        withContext(dispatcher) {
            val engine = ensureLoaded()
            val prompt = formatPrompt(engine, request)
            val grammar = request.schema?.let(JsonSchemaGrammar::compile)
            val imageTokens = if (canSee) request.messages.sumOf { it.images.size } * (maxImageTokens + IMAGE_FRAME_TOKENS) else 0
            val budget = LlamaNative.contextSize(engine) - LlamaNative.countTokens(engine, prompt) - imageTokens - 16
            if (budget < 256) throw LanguageModelException(LanguageModelException.Kind.ContextTooLong, "Prompt leaves only $budget tokens")

            val context = currentCoroutineContext()
            val out = StringBuilder()
            val callback = LlamaCallback { bytes ->
                val text = bytes.decodeToString()
                out.append(text)
                onText(text)
                context.isActive
            }
            val images = request.messages.flatMap { it.images }
            val status = if (images.isNotEmpty() && ensureVision(engine)) {
                LlamaNative.generateWithImages(
                    engine, prompt, images.map { it.rgb }.toTypedArray(), images.map { it.width }.toIntArray(), images.map { it.height }.toIntArray(),
                    grammar, minOf(request.maxTokens, budget), request.temperature, 0.95f, 0.05f, request.seed, callback,
                )
            } else {
                LlamaNative.generate(engine, prompt, grammar, minOf(request.maxTokens, budget), request.temperature, 0.95f, 0.05f, request.seed, callback)
            }
            val stop = when (status) {
                0 -> StopReason.EndTurn
                1 -> StopReason.MaxTokens
                2 -> StopReason.Cancelled
                -1 -> throw LanguageModelException(LanguageModelException.Kind.ContextTooLong, "Prompt does not fit the context")
                -3 -> throw LanguageModelException(LanguageModelException.Kind.Other, "Grammar rejected by llama.cpp")
                -4 -> throw LanguageModelException(LanguageModelException.Kind.Unavailable, "No vision projector loaded")
                -5 -> throw LanguageModelException(LanguageModelException.Kind.Other, "An image could not be read")
                else -> throw LanguageModelException(LanguageModelException.Kind.Other, "llama.cpp decode failed ($status)")
            }
            val c = LlamaNative.lastCall(engine)
            val usage = Usage(1, c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3] / US, c[4] / US, c.getOrElse(DRAFTED) { 0 }.toInt(), c.getOrElse(DRAFTED + 1) { 0 }.toInt())
            total += usage
            if (log) System.err.println("trimio llm: ${usage.summary()}, grammar resamples ${c[RESAMPLES]}")
            Generation(out.toString(), stop, servedBy = id, usage = usage)
        }
    }

    private var total = Usage(calls = 0)
    private var info: Map<String, List<String>> = emptyMap()

    /** Where this model runs (GPU, layers, threads, cache) and its calls so far; null until it loads. */
    override val runtime: ModelRuntime?
        get() = info.takeIf { it.isNotEmpty() }?.let { LlamaRuntime.parse(LlamaNative.variant, it, total) }

    private fun ensureLoaded(): Long {
        if (handle != 0L) return handle
        if (!File(modelPath).isFile) throw LanguageModelException(LanguageModelException.Kind.Unavailable, "Model not installed: $modelPath")
        handle = LlamaNative.init(modelPath, contextSize, threads, LlamaNative.gpuLayers, LlamaNative.options())
        if (handle == 0L) throw LanguageModelException(LanguageModelException.Kind.Unavailable, "Could not load $modelPath")
        info = LlamaRuntime.lines(LlamaNative.runtimeInfo(handle))
        runtime?.let { System.err.println("trimio " + it.summary()) }
        return handle
    }

    private var visionReady: Boolean? = null

    /** Loads the projector once; false (and images ignored) when there is none or it does not fit. */
    private fun ensureVision(engine: Long): Boolean {
        visionReady?.let { return it }
        val ok = canSee && LlamaNative.initVision(engine, visionPath!!, threads, maxImageTokens)
        visionReady = ok
        return ok
    }

    private fun formatPrompt(engine: Long, request: GenerationRequest): ByteArray {
        // Images go in as markers ahead of their turn's text; the vision projector fills them in.
        val see = request.messages.any { it.images.isNotEmpty() } && canSee
        val messages = request.messages.map { m ->
            if (see && m.images.isNotEmpty()) m.copy(text = LlamaNative.MEDIA_MARKER.repeat(m.images.size) + "\n" + m.text) else m
        }
        // The model's own template first (exactly what it was trained on), the catalogue format otherwise.
        val roles = arrayOf("system") + messages.map { if (it.role == ChatRole.User) "user" else "assistant" }
        val contents = arrayOf(request.system.encodeToByteArray()) + messages.map { it.text.encodeToByteArray() }
        val formatted = LlamaNative.applyTemplate(engine, roles, contents)
            ?: ChatTemplates.format(format, request.system, messages).encodeToByteArray()
        return if (assistantPrefix.isEmpty()) formatted else formatted + assistantPrefix.encodeToByteArray()
    }

    /** Frees the model's memory once no generation is running; it reloads on the next request. */
    override suspend fun release() = mutex.withLock { close() }

    override fun close() {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
            visionReady = null
        }
    }

    companion object {
        /**
         * Big cores only: little cores slow the whole decode down to their pace ([FastCores] where
         * measurable), less one, left to the app (previews, the screen, the stream). ggml's threads
         * meet at a spinning barrier after every operation: when one of them is put aside for
         * another program, all of them wait for its turn. On this repo's busy 4-core test server
         * whisper with 4 threads took over 600 s for what 1 thread did in 4.5 s.
         */
        fun defaultThreads(): Int = FastCores.count()?.let { (it - 1).coerceIn(2, MAX_THREADS) } ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

        private const val MAX_THREADS = 8
        private const val US = 1000L
        private const val RESAMPLES = 5
        private const val DRAFTED = 6

        /** `-Dtrimio.llama.log=calls`: one line per call (tokens, speed) on stderr, for measurements. */
        private val log = System.getProperty("trimio.llama.log") == "calls"

        fun systemInfo(): String = LlamaNative.systemInfo()

        /** Start/end tokens around each image's tokens. */
        private const val IMAGE_FRAME_TOKENS = 4
    }
}
