package io.trimio.engine.retrieval

import io.trimio.engine.llm.EmbedRole
import io.trimio.engine.llm.EmbeddingFamily
import io.trimio.engine.llm.TextEmbedder
import io.trimio.engine.llm.TextReranker
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deterministic stand-in embedder: bag of folded terms hashed into [dims], unit length. */
class FakeEmbedder(override val dims: Int = 64, override val id: String = "fake-embedder") : TextEmbedder {
    override val family = EmbeddingFamily.BgeM3
    var calls = 0

    override suspend fun embed(texts: List<String>, role: EmbedRole, titles: List<String?>): List<FloatArray> {
        calls += texts.size
        return texts.mapIndexed { i, t ->
            val v = FloatArray(dims)
            for (term in Terms.of((titles.getOrNull(i) ?: "") + " " + t)) v[(fnv(term) and Int.MAX_VALUE) % dims] += 1f
            Int8Vectors.unit(v)
        }
    }
}

/** A reranker that says yes to documents containing [yes]. */
class FakeReranker(private val yes: String) : TextReranker {
    override val id = "fake-reranker"

    override suspend fun rerank(query: String, documents: List<String>, instruction: String): List<Float> =
        documents.map { if (yes in it) 0.9f else 0.1f }
}

class RetrievalCoreTest {
    private val docs = listOf(
        Doc("noto:rocket", "animation", "rocket", "rocket, launch, space, growth", "موشک، پرتاب، رشد"),
        Doc("noto:chart-up", "animation", "chart increasing", "chart increasing, profit, trend up", "نمودار صعودی، سود"),
        Doc("noto:cry", "animation", "loudly crying", "crying, sad, tears", "گریه، غمگین"),
        Doc("noto:pizza", "animation", "pizza", "pizza, food, slice", "پیتزا، غذا"),
        Doc("primitive:meter", "primitive", "Meter", "a gauge filling or draining, a usage limit", "نشانگر سهمیه"),
    )

    @Test
    fun termsFoldStemAndDropStopWords() {
        val t = Terms.of("کتاب‌های من و Rockets")
        assertTrue("کتابهای" in t && "کتاب" in t, t.toString())
        assertTrue("rockets" in t && "rocket" in t, t.toString())
        assertTrue("من" !in t && "و" !in t)
        // A stem never drops below two letters: «ها» alone stays a stop word.
        assertEquals(listOf("ماهی"), Terms.of("ماهی"))
    }

    @Test
    fun bm25RanksByRarityAndRespectsFilters() {
        val bm = Bm25(docs.map(Doc::searchText))
        val hits = bm.search("رشد موشک", 3)
        assertEquals(0, hits.first().index)
        assertTrue(bm.idf("موشک") > bm.idf("animation".lowercase()) || bm.idf("animation") == 0f)
        assertTrue(bm.search("موشک", 3) { it != 0 }.none { it.index == 0 })
        assertTrue(bm.search("کلمه‌ای که نیست", 3).isEmpty())
    }

    @Test
    fun topKKeepsTheBestInOrder() {
        val scores = floatArrayOf(0.1f, 0.9f, 0.5f, 0.9f, -1f, 0.7f)
        val top = topK(scores.size, 3) { scores[it] }
        assertEquals(listOf(1, 3, 5), top.map { it.index })
        assertEquals(scores.size, topK(scores.size, 99) { scores[it] }.size)
        assertTrue(topK(0, 3) { 0f }.isEmpty())
    }

    @Test
    fun int8VectorsKeepCosinesWithinAPercent() {
        val rnd = Random(3)
        val vs = List(200) { Int8Vectors.unit(FloatArray(128) { rnd.nextFloat() - 0.5f }) }
        val index = Int8Vectors(128, 4)
        vs.forEach { index.add(it) }
        val q = vs[17]
        for (i in 0 until 50) {
            val exact = vs[i].indices.sumOf { (vs[i][it] * q[it]).toDouble() }.toFloat()
            assertTrue(abs(index.dot(i, q) - exact) < 0.01f, "int8 cosine off at $i")
        }
        assertEquals(17, index.search(q, 1).single().index)
        assertFailsWith<IllegalArgumentException> { index.add(FloatArray(3)) }
    }

    @Test
    fun reciprocalRankFusionRewardsAgreement() {
        val fused = Fusion.rrf(listOf(listOf(1, 2, 3), listOf(3, 1, 4)))
        assertEquals(1, fused.first().index)
        assertEquals(setOf(1, 2, 3, 4), fused.map { it.index }.toSet())
    }

    @Test
    fun vectorPacksRoundTripAndBundle() {
        val a = VectorPack("e/4/p1", "visuals", 4, listOf(VectorPack.Entry("x", 7, 0.01f, byteArrayOf(1, -2, 3, -127)), VectorPack.Entry("بیت", -1, 1f, byteArrayOf(0, 0, 0, 0))))
        val b = VectorPack("e/4/p1", "sounds", 4, emptyList())
        val back = VectorPack.read(a.write())
        assertEquals("e/4/p1", back.space)
        assertEquals("بیت", back.entries[1].id)
        assertEquals(-127, back.entries[0].bytes[3].toInt())
        assertEquals(0.01f, back.entries[0].scale)
        val all = VectorPack.readAll(VectorPack.writeAll(listOf(a, b)))
        assertEquals(listOf("visuals", "sounds"), all.map { it.corpus })
        assertEquals(1, VectorPack.readAll(a.write()).size)
        assertFailsWith<IllegalArgumentException> { VectorPack.read("nope".encodeToByteArray()) }
    }

    @Test
    fun packsAreReusedOnlyForTheSameSpaceAndText() = runTest {
        val e = FakeEmbedder()
        val first = CorpusIndex("visuals", docs)
        assertEquals(docs.size, first.attach(e))
        val pack = VectorPack.read(first.pack()!!.write())
        // The same documents: nothing embedded again.
        val second = CorpusIndex("visuals", docs)
        assertEquals(0, second.attach(e, pack))
        // One document changed: only it is embedded.
        val changed = docs.map { if (it.id == "noto:pizza") it.copy(text = "pizza, food, cheese") else it }
        assertEquals(1, CorpusIndex("visuals", changed).attach(e, pack))
        // Another embedder's pack is ignored.
        assertEquals(docs.size, CorpusIndex("visuals", docs).attach(FakeEmbedder(id = "other"), pack))
    }

    @Test
    fun hybridSearchRerankFloorAndChoices() = runTest {
        val e = FakeEmbedder()
        val index = CorpusIndex("visuals", docs).also { it.attach(e) }
        // Words only: no floor.
        val lexical = Retriever().search(index, Query("موشک"))
        assertEquals(Signal.Lexical, lexical.signal)
        assertEquals("noto:rocket", lexical.hits.first().doc.id)
        // Dense: the floor applies.
        val dense = Retriever(e).search(index, Query("موشک رشد"))
        assertEquals(Signal.Dense, dense.signal)
        assertEquals("noto:rocket", dense.best?.doc?.id)
        // Reranked: only what the reranker says yes to passes; the rest is below the floor.
        val reranked = Retriever(e, FakeReranker("rocket")).search(index, Query("موشک رشد"))
        assertEquals(Signal.Reranked, reranked.signal)
        assertEquals(listOf("noto:rocket"), reranked.passing.map { it.doc.id })
        val choices = reranked.choiceSchema()["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("noto:rocket", "none"), choices)
        val nothing = Retriever(e, FakeReranker("zzz")).search(index, Query("موشک رشد"))
        assertTrue(nothing.belowFloor)
        assertNull(nothing.best)
        assertEquals(listOf("none"), nothing.choiceSchema()["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Kinds and exclusions.
        val primitives = Retriever(e).search(index, Query("سهمیه نشانگر", kinds = setOf("primitive")))
        assertTrue(primitives.hits.all { it.doc.kind == "primitive" })
        assertTrue(Retriever(e).search(index, Query("موشک", exclude = setOf("noto:rocket"))).hits.none { it.doc.id == "noto:rocket" })
    }

    @Test
    fun fitPutsTheFloorAtOneHalf() {
        assertEquals(0.5f, Retriever.fit(0.4f, 0.4f))
        assertEquals(1f, Retriever.fit(1f, 0.4f))
        assertEquals(0f, Retriever.fit(0f, 0.4f))
        assertTrue(Retriever.fit(0.3f, 0.4f) < 0.5f && Retriever.fit(0.6f, 0.4f) > 0.5f)
    }

    @Test
    fun diverseAvoidsNearDuplicates() = runTest {
        val e = FakeEmbedder()
        val near = docs + Doc("noto:rocket2", "animation", "rocket", "rocket, launch, space, growth", "موشک، پرتاب، رشد")
        val index = CorpusIndex("visuals", near).also { it.attach(e) }
        val hits = Retriever(e).search(index, Query("موشک رشد سود")).hits
        val picked = Retriever(e).diverse(index, hits, 2, lambda = 0.5f)
        assertTrue(picked.map { it.doc.title }.distinct().size == 2, picked.map { it.doc.id }.toString())
    }

    @Test
    fun tasteHeadLearnsWhatTheJuryLikes() {
        val rnd = Random(11)
        val dims = 16
        fun unit() = Int8Vectors.unit(FloatArray(dims) { rnd.nextFloat() - 0.5f })
        // The jury likes picks that agree with the sentence on the first four dimensions.
        val examples = List(600) {
            val q = unit()
            val p = unit()
            val agree = (0 until 4).sumOf { (q[it] * p[it]).toDouble() } > 0
            TasteHead.Example(q, p, floatArrayOf(0f, 0f, 0f), agree)
        }
        val head = TasteHead.train(examples.take(500), dims)
        val held = examples.drop(500)
        val acc = held.count { (head.predict(it.query, it.pick, it.signals) > 0.5f) == it.good }.toFloat() / held.size
        assertTrue(acc > 0.75f, "held-out accuracy $acc")
        assertTrue(TasteHead.loss(head, held) < 0.6f)
        val back = TasteHead.read(head.write())
        assertEquals(head.predict(held[0].query, held[0].pick, held[0].signals), back.predict(held[0].query, held[0].pick, held[0].signals))
        assertTrue(sqrt(head.weights.sumOf { (it * it).toDouble() }) > 0.0)
    }
}
