package io.trimio.engine.llm

import io.trimio.engine.llm.local.LlamaEmbedder
import io.trimio.engine.llm.local.LlamaNative
import io.trimio.engine.llm.local.LlamaReranker
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The retrieval add-ons through JNI with the real models. Run with
 * `./gradlew :engine:llm:jvmTest -Ptrimio.nativeTests -Ptrimio.encoders=<dir> --tests '*LlamaEncodersTest*'`
 * where <dir> holds embeddinggemma-2-Q8_0.gguf, bge-m3-q8_0.gguf and qwen3-reranker-0.6b-q8_0.gguf.
 * Each test is skipped when its file is missing.
 */
class LlamaEncodersTest {
    private val dir = System.getProperty("trimio.encoders")?.let(::File)?.takeIf { it.isDirectory }

    private fun file(name: String): String? = dir?.let { File(it, name) }?.takeIf { it.isFile }?.absolutePath

    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()

    private val growth = "قیمت بیت‌کوین در یک هفته دو برابر شد و مثل موشک بالا رفت"
    private val docs = listOf(
        "rocket launching upward, growth, increase, to the moon",
        "crying face, sad, tears",
        "chart increasing, upward trend, profit",
        "fork and knife, food, restaurant",
    )

    private fun checkEmbedder(name: String, family: EmbeddingFamily, dims: Int): Unit = runBlocking {
        val path = file(name) ?: return@runBlocking
        LlamaEmbedder(name, path, family).use { e ->
            val t0 = System.nanoTime()
            val q = e.embed(listOf(growth), EmbedRole.Query).single()
            val d = e.embed(docs, EmbedRole.Document)
            val ms = (System.nanoTime() - t0) / 1e6
            assertEquals(dims, e.dims)
            assertEquals(dims, q.size)
            assertTrue(abs(sqrt(dot(q, q)) - 1f) < 1e-3f, "unit length")
            val sims = d.map { dot(q, it) }
            println("$name: ${e.info["model"]} pooling=${e.info["pooling"]} sims=${sims.map { "%.3f".format(it) }} in ${"%.0f".format(ms)} ms")
            assertTrue(sims[0] > sims[1] && sims[2] > sims[1] && sims[0] > sims[3], "growth beats sadness and food: $sims")
            // A text alone and the same text in a batch get the same vector (sequences never leak into each other).
            val alone = e.embed(listOf(docs[2]), EmbedRole.Document).single()
            assertTrue(dot(alone, d[2]) > 0.999f, "batched = alone: ${dot(alone, d[2])}")
            // Speed: a typical query batch.
            val many = (0 until 64).map { "سطر شماره $it درباره قیمت طلا و دلار" }
            val t1 = System.nanoTime()
            e.embed(many, EmbedRole.Document)
            println("$name: 64 short texts in ${"%.0f".format((System.nanoTime() - t1) / 1e6)} ms")
        }
    }

    @Test
    fun embeddingGemma2EmbedsPersianWithMeaning() = checkEmbedder("embeddinggemma-2-Q8_0.gguf", EmbeddingFamily.EmbeddingGemma, 768)

    @Test
    fun bgeM3EmbedsPersianWithMeaning() = checkEmbedder("bge-m3-q8_0.gguf", EmbeddingFamily.BgeM3, 1024)

    @Test
    fun matryoshkaTruncationKeepsUnitVectors(): Unit = runBlocking {
        val path = file("embeddinggemma-2-Q8_0.gguf") ?: return@runBlocking
        LlamaEmbedder("eg2-256", path, EmbeddingFamily.EmbeddingGemma, truncate = 256).use { e ->
            val v = e.embed(docs, EmbedRole.Document)
            assertTrue(v.all { it.size == 256 && abs(sqrt(dot(it, it)) - 1f) < 1e-3f })
        }
    }

    @Test
    fun rerankerJudgesRelevanceAndMatchesTheRankHead(): Unit = runBlocking {
        val path = file("qwen3-reranker-0.6b-q8_0.gguf") ?: return@runBlocking
        LlamaReranker("qwen3-reranker-0.6b-q8", path).use { r ->
            val t0 = System.nanoTime()
            val scores = r.rerank(growth, docs, RerankPrompt.VISUAL)
            println("reranker: ${scores.map { "%.3f".format(it) }} in ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms")
            assertTrue(scores.all { it in 0f..1f })
            assertTrue(scores[0] > scores[1] && scores[0] > scores[3], "rocket beats sadness and food: $scores")
            // The shared-head judge gives the GGUF rank head's number (llama.cpp `--pooling rank`), each
            // pair read alone: within a small margin of logit (batch composition moves llama.cpp's own
            // rank scores by about as much).
            val h = LlamaNative.encoderInit(path, 4096, 4, 1, LlamaNative.ENCODE_RANK)
            assertTrue(h != 0L)
            try {
                val pooled = docs.map { d ->
                    requireNotNull(LlamaNative.encode(h, arrayOf(RerankPrompt.qwen3(RerankPrompt.VISUAL, growth, d).encodeToByteArray()), false))[0]
                }
                println("rank head: ${pooled.map { "%.3f".format(it) }}")
                fun logit(p: Float) = ln(p / (1 - p))
                docs.indices.forEach { assertTrue(abs(logit(pooled[it]) - logit(scores[it])) < 0.3f, "judge ${scores[it]} vs rank ${pooled[it]}") }
            } finally {
                LlamaNative.encoderFree(h)
            }
            // Many documents: more than one group of sequences.
            val many = (0 until 40).map { docs[it % docs.size] + " #$it" }
            val t1 = System.nanoTime()
            val again = r.rerank(growth, many, RerankPrompt.VISUAL)
            println("reranker: 40 docs in ${"%.0f".format((System.nanoTime() - t1) / 1e6)} ms")
            assertTrue(again[0] > again[1])
        }
    }
}
