package io.trimio.engine.llm.local

import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.ChatTemplates
import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.JsonSchemaGrammar
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.LanguageModelException
import io.trimio.engine.llm.StopReason
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
    private val GPU_VARIANTS = setOf("vulkan", "cuda", "metal")
    private const val ALL_LAYERS = 999

    /**
     * The build that loaded. `-Dtrimio.native.variants` lists subdirectories of `trimio.native.dir`
     * best first (the desktop app: "vulkan,cpu"); a GPU build whose runtime is missing fails to
     * load and the next is tried, the flat directory last (docs/DESKTOP.md).
     */
    val variant: String = load()

    /** Layers offloaded to the GPU: all of them with a GPU build, unless `-Dtrimio.llama.gpuLayers` says otherwise. */
    val gpuLayers: Int get() = System.getProperty("trimio.llama.gpuLayers")?.toIntOrNull() ?: if (variant in GPU_VARIANTS) ALL_LAYERS else 0

    private fun load(): String {
        val dir = System.getProperty("trimio.native.dir")
        val variants = System.getProperty("trimio.native.variants").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty) + ""
        var failure: Throwable? = null
        for (variant in variants) {
            val file = dir?.let { d -> FILES.map { File(File(d, variant), it) }.firstOrNull(File::isFile) } ?: continue
            if (runCatching { System.load(file.absolutePath) }.onFailure { failure = it }.isSuccess) return variant
        }
        failure?.takeIf { dir != null }?.let { throw it }
        System.loadLibrary("trimio_llama")
        return ""
    }

    external fun init(modelPath: String, contextSize: Int, threads: Int, gpuLayers: Int): Long
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
            Generation(out.toString(), stop, servedBy = id)
        }
    }

    private fun ensureLoaded(): Long {
        if (handle != 0L) return handle
        if (!File(modelPath).isFile) throw LanguageModelException(LanguageModelException.Kind.Unavailable, "Model not installed: $modelPath")
        handle = LlamaNative.init(modelPath, contextSize, threads, LlamaNative.gpuLayers)
        if (handle == 0L) throw LanguageModelException(LanguageModelException.Kind.Unavailable, "Could not load $modelPath")
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
        /** Big cores only: little cores slow the whole decode down to their pace. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

        fun systemInfo(): String = LlamaNative.systemInfo()

        /** Start/end tokens around each image's tokens. */
        private const val IMAGE_FRAME_TOKENS = 4
    }
}
