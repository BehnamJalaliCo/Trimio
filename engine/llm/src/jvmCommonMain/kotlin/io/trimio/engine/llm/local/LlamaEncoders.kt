package io.trimio.engine.llm.local

import io.trimio.engine.llm.EmbedRole
import io.trimio.engine.llm.EmbeddingFamily
import io.trimio.engine.llm.LanguageModelException
import io.trimio.engine.llm.RerankPrompt
import io.trimio.engine.llm.TextEmbedder
import io.trimio.engine.llm.TextReranker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * An on-device text embedder (GGUF through llama.cpp, CPU): pooling as the GGUF says, L2-normalised
 * vectors, many texts per decode. It loads on first use and stays resident (a few hundred MB); a
 * probe embedding on load rejects a build that gives NaNs (EmbeddingGemma in f16: use Q8_0 or BF16).
 *
 * [truncate]: Matryoshka models (EmbeddingGemma: 768 → 512/256/128) may keep only the first dims,
 * renormalised; null keeps them all.
 */
class LlamaEmbedder(
    override val id: String,
    private val modelPath: String,
    override val family: EmbeddingFamily,
    private val truncate: Int? = null,
    /** Tokens per decode (several texts share one). */
    private val contextSize: Int = 2048,
    private val sequences: Int = 32,
    private val threads: Int = LlamaLanguageModel.defaultThreads(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TextEmbedder, AutoCloseable {
    private val mutex = Mutex()
    private var handle = 0L
    private var native = 0

    override val dims: Int get() = truncate ?: native.takeIf { it > 0 } ?: error("$id: dims are known once the model is loaded (call load())")

    /** How the encoder was opened (key=value lines), once loaded. */
    var info: Map<String, List<String>> = emptyMap()
        private set

    /** Loads the model now (normally on the first [embed]); returns its native dims. */
    suspend fun load(): Int = mutex.withLock { withContext(dispatcher) { ensureLoaded() }.let { native } }

    override suspend fun embed(texts: List<String>, role: EmbedRole, titles: List<String?>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return mutex.withLock {
            withContext(dispatcher) {
                val engine = ensureLoaded()
                val prompts = texts.mapIndexed { i, t -> family.format(t, role, titles.getOrNull(i)) }
                val out = ArrayList<FloatArray>(texts.size)
                for (chunk in prompts.chunked(CALL)) {
                    coroutineContext.ensureActive()
                    val flat = LlamaNative.encode(engine, chunk.map { it.encodeToByteArray() }.toTypedArray(), true)
                        ?: throw LanguageModelException(LanguageModelException.Kind.Other, "$id: embedding failed or gave non-finite values")
                    for (k in chunk.indices) out += cut(flat.copyOfRange(k * native, (k + 1) * native))
                }
                out
            }
        }
    }

    private fun cut(v: FloatArray): FloatArray {
        val d = truncate ?: return v
        if (d >= v.size) return v
        val w = v.copyOf(d)
        val norm = sqrt(w.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in w.indices) w[i] /= norm
        return w
    }

    private fun ensureLoaded(): Long {
        if (handle != 0L) return handle
        if (!File(modelPath).isFile) throw LanguageModelException(LanguageModelException.Kind.Unavailable, "Model not installed: $modelPath")
        val h = LlamaNative.encoderInit(modelPath, contextSize, threads, sequences, LlamaNative.ENCODE_EMBED)
        if (h == 0L) throw LanguageModelException(LanguageModelException.Kind.Unavailable, "Could not load $modelPath as an embedder")
        info = LlamaRuntime.lines(LlamaNative.encoderInfo(h))
        native = info["dims"]?.firstOrNull()?.toIntOrNull() ?: 0
        // The model card's warning: a wrong precision gives NaNs (or zeros) instead of failing.
        val probe = LlamaNative.encode(h, arrayOf(family.format("probe", EmbedRole.Query).encodeToByteArray()), true)
        if (native <= 0 || probe == null || probe.all { it == 0f }) {
            LlamaNative.encoderFree(h)
            throw LanguageModelException(LanguageModelException.Kind.Unavailable, "$id gives no usable vectors (use the Q8_0 or BF16 file, never F16)")
        }
        handle = h
        return h
    }

    override suspend fun release() = mutex.withLock { close() }

    override fun close() {
        if (handle != 0L) {
            LlamaNative.encoderFree(handle)
            handle = 0L
        }
    }

    private companion object {
        /** Texts per native call: cancellation is checked between calls. */
        const val CALL = 128
    }
}

/**
 * An on-device reranker (Qwen3-Reranker / Qwen3-VL-Reranker text side, GGUF through llama.cpp,
 * CPU): P(yes) that each document meets the [instruction] for the query, the instruction and query
 * read once and shared by every document. Loaded on first use, released before rendering.
 */
class LlamaReranker(
    override val id: String,
    private val modelPath: String,
    private val contextSize: Int = 4096,
    private val sequences: Int = 16,
    private val threads: Int = LlamaLanguageModel.defaultThreads(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Characters of a document kept (titles and descriptions are short; a page is cut). */
    private val maxDocumentChars: Int = 600,
) : TextReranker, AutoCloseable {
    private val mutex = Mutex()
    private var handle = 0L

    override suspend fun rerank(query: String, documents: List<String>, instruction: String): List<Float> {
        if (documents.isEmpty()) return emptyList()
        return mutex.withLock {
            withContext(dispatcher) {
                val engine = ensureLoaded()
                val head = RerankPrompt.head(instruction, query.take(maxDocumentChars)).encodeToByteArray()
                val out = ArrayList<Float>(documents.size)
                for (chunk in documents.chunked(CALL)) {
                    coroutineContext.ensureActive()
                    val tails = chunk.map { RerankPrompt.tail(it.take(maxDocumentChars)).encodeToByteArray() }.toTypedArray()
                    val scores = LlamaNative.judge(engine, head, tails)
                        ?: throw LanguageModelException(LanguageModelException.Kind.Other, "$id: reranking failed")
                    out += scores.toList()
                }
                out
            }
        }
    }

    private fun ensureLoaded(): Long {
        if (handle != 0L) return handle
        if (!File(modelPath).isFile) throw LanguageModelException(LanguageModelException.Kind.Unavailable, "Model not installed: $modelPath")
        handle = LlamaNative.encoderInit(modelPath, contextSize, threads, sequences, LlamaNative.ENCODE_JUDGE)
        if (handle == 0L) throw LanguageModelException(LanguageModelException.Kind.Unavailable, "Could not load $modelPath as a reranker")
        return handle
    }

    override suspend fun release() = mutex.withLock { close() }

    override fun close() {
        if (handle != 0L) {
            LlamaNative.encoderFree(handle)
            handle = 0L
        }
    }

    private companion object {
        const val CALL = 64
    }
}
