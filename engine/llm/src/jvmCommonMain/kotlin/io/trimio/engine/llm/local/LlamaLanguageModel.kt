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
    init {
        val dir = System.getProperty("trimio.native.dir")
        val file = dir?.let { File(it, "libtrimio_llama.so").takeIf(File::isFile) ?: File(it, "libtrimio_llama.dylib").takeIf(File::isFile) }
        if (file != null) System.load(file.absolutePath) else System.loadLibrary("trimio_llama")
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
    external fun systemInfo(): String
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
) : LanguageModel, AutoCloseable {
    override val isLocal = true

    private val mutex = Mutex()
    private var handle = 0L

    override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation = mutex.withLock {
        withContext(dispatcher) {
            val engine = ensureLoaded()
            val prompt = formatPrompt(engine, request)
            val grammar = request.schema?.let(JsonSchemaGrammar::compile)
            val budget = LlamaNative.contextSize(engine) - LlamaNative.countTokens(engine, prompt) - 16
            if (budget < 256) throw LanguageModelException(LanguageModelException.Kind.ContextTooLong, "Prompt leaves only $budget tokens")

            val context = currentCoroutineContext()
            val out = StringBuilder()
            val status = LlamaNative.generate(
                engine, prompt, grammar, minOf(request.maxTokens, budget),
                request.temperature, 0.95f, 0.05f, request.seed,
            ) { bytes ->
                val text = bytes.decodeToString()
                out.append(text)
                onText(text)
                context.isActive
            }
            val stop = when (status) {
                0 -> StopReason.EndTurn
                1 -> StopReason.MaxTokens
                2 -> StopReason.Cancelled
                -1 -> throw LanguageModelException(LanguageModelException.Kind.ContextTooLong, "Prompt does not fit the context")
                -3 -> throw LanguageModelException(LanguageModelException.Kind.Other, "Grammar rejected by llama.cpp")
                else -> throw LanguageModelException(LanguageModelException.Kind.Other, "llama.cpp decode failed ($status)")
            }
            Generation(out.toString(), stop, servedBy = id)
        }
    }

    private fun ensureLoaded(): Long {
        if (handle != 0L) return handle
        if (!File(modelPath).isFile) throw LanguageModelException(LanguageModelException.Kind.Unavailable, "Model not installed: $modelPath")
        handle = LlamaNative.init(modelPath, contextSize, threads, gpuLayers = 0)
        if (handle == 0L) throw LanguageModelException(LanguageModelException.Kind.Unavailable, "Could not load $modelPath")
        return handle
    }

    private fun formatPrompt(engine: Long, request: GenerationRequest): ByteArray {
        // The model's own template first (exactly what it was trained on), the catalogue format otherwise.
        val roles = arrayOf("system") + request.messages.map { if (it.role == ChatRole.User) "user" else "assistant" }
        val contents = arrayOf(request.system.encodeToByteArray()) + request.messages.map { it.text.encodeToByteArray() }
        return LlamaNative.applyTemplate(engine, roles, contents)
            ?: ChatTemplates.format(format, request.system, request.messages).encodeToByteArray()
    }

    /** Frees the model's memory once no generation is running; it reloads on the next request. */
    override suspend fun release() = mutex.withLock { close() }

    override fun close() {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
    }

    companion object {
        /** Big cores only: little cores slow the whole decode down to their pace. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

        fun systemInfo(): String = LlamaNative.systemInfo()
    }
}
