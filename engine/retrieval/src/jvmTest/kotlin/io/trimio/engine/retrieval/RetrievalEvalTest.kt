package io.trimio.engine.retrieval

import io.trimio.engine.assets.art.ArtCatalogue
import io.trimio.engine.llm.EmbedRole
import io.trimio.engine.llm.EmbeddingFamily
import io.trimio.engine.llm.RerankPrompt
import io.trimio.engine.llm.TextEmbedder
import io.trimio.engine.llm.local.LlamaEmbedder
import io.trimio.engine.llm.local.LlamaReranker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.ln
import kotlin.test.Test

/**
 * The labelled retrieval check (tools/retrieval/build_eval.py → resources/retrieval/eval.json):
 * 328 Persian and English lines, each labelled with the picture families that would show it (or
 * none: abstract lines). Every system ranks the bundled picture catalogue (1742 animated
 * pictures) for every line, in two modes — the sentence alone (an audio-only explainer reading its
 * narration), and the sentence plus the English picture idea a director writes — and is scored by
 * Recall@10, nDCG@10, MRR and P@1 against the labels, then by what it would actually show: the
 * irrelevant-pick rate with its relevance floor, the floors calibrated by two-fold cross-validation.
 *
 * Run: `./gradlew :engine:retrieval:jvmTest --tests '*RetrievalEvalTest*' -Pretrieval.encoders=<models dir>
 * -Pretrieval.native=<dir with libtrimio_llama> -Pretrieval.out=<dir>`. Skipped without the models.
 */
class RetrievalEvalTest {
    private val modelsDir = System.getProperty("retrieval.encoders")?.let(::File)?.takeIf { it.isDirectory }
    private val outDir = System.getProperty("retrieval.out")?.let(::File)
    private val only = System.getProperty("retrieval.only")?.split(',')?.map(String::trim)?.toSet()

    class Line(val text: String, val lang: String, val hint: String, val relevant: Set<String>) {
        val abstract: Boolean get() = relevant.isEmpty()
    }

    /** One system's ranking of a line, with the score its top pick would be shown by (null: always shown when present). */
    class Ranked(val ids: List<String>, val topScore: Float?, val oldShown: Boolean? = null)

    private val json = Json { ignoreUnknownKeys = true }

    private fun lines(): List<Line> {
        val o = json.parseToJsonElement(javaClass.getResource("/retrieval/eval.json")!!.readText()).jsonObject
        return o.getValue("lines").jsonArray.map { e ->
            val l = e.jsonObject
            Line(l.getValue("line").jsonPrimitive.content, l.getValue("lang").jsonPrimitive.content, l.getValue("hint").jsonPrimitive.content,
                l.getValue("relevant").jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
    }

    private fun index(): String = File("../autopilot/src/commonMain/composeResources/files/art/index.json").readText()

    @Test
    fun evaluate() = runBlocking {
        val dir = modelsDir ?: return@runBlocking
        val out = (outDir ?: File("build/retrieval-eval")).also { it.mkdirs() }
        val session = Session(dir, out, lines(), index())
        session.old()
        session.embedders()
        session.rerank()
        session.write()
    }

    /** One evaluation: the lines, the catalogue, every system's metrics and the report. */
    private inner class Session(private val dir: File, private val out: File, private val lines: List<Line>, private val indexText: String) {
        private val docs = Corpora.art(Corpora.pictures(indexText))
        private val report = StringBuilder()
        private val results = mutableMapOf<String, Metrics>()
        private val indexes = mutableMapOf<String, Pair<CorpusIndex, Map<String, FloatArray>>>()

        fun run(name: String, rank: (Line, Boolean) -> Ranked) {
            if (only != null && name.substringBefore('/') !in only) return
            for (hinted in listOf(false, true)) {
                val key = "$name/${if (hinted) "B" else "A"}"
                val t0 = System.nanoTime()
                val ranked = lines.map { rank(it, hinted) }
                val ms = (System.nanoTime() - t0) / 1e6 / lines.size
                results[key] = Metrics.of(lines, ranked, ms)
                println("eval $key ${results[key]}")
            }
        }

        /** The matcher the app used before (words and character n-grams over names and keywords), and BM25 alone. */
        fun old() {
            val old = ArtCatalogue.parse(indexText)
            run("old") { l, hinted ->
                val query = listOfNotNull(l.hint.takeIf { hinted && it.isNotBlank() }, l.text.takeIf { l.lang == "en" }).joinToString(" ")
                val persian = if (l.lang == "fa") l.text else ""
                val ranked = old.search(query, persian, limit = 10, minRelevance = 0f).map { it.entry.id }
                Ranked(ranked, null, old.search(query, persian, limit = 1).isNotEmpty())
            }
            val corpus = CorpusIndex(Corpora.VISUALS, docs)
            run("bm25") { l, hinted -> Ranked(corpus.bm25.search(q(l, hinted), 10).map { docs[it.index].id }, null) }
        }

        /** Each installed embedder alone (dense) and fused with BM25 (hybrid); one model in memory at a time. */
        suspend fun embedders() {
            val all = listOf(
                "eg2" to ("embeddinggemma-2-Q8_0.gguf" to EmbeddingFamily.EmbeddingGemma),
                "bgem3" to ("bge-m3-q8_0.gguf" to EmbeddingFamily.BgeM3),
                "qwen3e06" to ("Qwen3-Embedding-0.6B-Q8_0.gguf" to EmbeddingFamily.Qwen3Embedding),
                "eg1" to ("embeddinggemma-300M-Q8_0.gguf" to EmbeddingFamily.EmbeddingGemma),
            ).filter { (name, f) -> File(dir, f.first).isFile && (only == null || name in only || "rerank" in only) }
            for ((name, file) in all) {
                val e = LlamaEmbedder(name, File(dir, file.first).absolutePath, file.second, contextSize = 1024, sequences = 16)
                val cached = File(out, "$name.trvb").takeIf { it.isFile }?.let { VectorPack.read(it.readBytes()) }
                val ci = CorpusIndex(Corpora.VISUALS, docs)
                val t0 = System.nanoTime()
                val embedded = ci.attach(e, cached)
                if (embedded > 0) {
                    ci.pack()?.let { File(out, "$name.trvb").writeBytes(it.write()) }
                    report.append("$name: embedded $embedded catalogue documents in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s\n")
                }
                val queries = embedQueries(e, lines)
                indexes[name] = ci to queries
                e.close()
                val vectors = requireNotNull(ci.vectors)
                run("dense-$name") { l, hinted ->
                    val hits = vectors.search(queries.getValue(q(l, hinted)), 10)
                    Ranked(hits.map { docs[it.index].id }, hits.firstOrNull()?.score)
                }
                run("hybrid-$name") { l, hinted ->
                    val qv = queries.getValue(q(l, hinted))
                    val fused = fused(ci, l, hinted, qv).take(10)
                    Ranked(fused.map { docs[it.index].id }, fused.firstOrNull()?.let { vectors.dot(it.index, qv) })
                }
            }
        }

        private fun fused(ci: CorpusIndex, l: Line, hinted: Boolean, qv: FloatArray): List<Scored> {
            val lex = ci.bm25.search(q(l, hinted), 50).map { it.index }
            val dense = requireNotNull(ci.vectors).search(qv, 50).map { it.index }
            return Fusion.rrf(listOf(lex, dense))
        }

        /** The reranker over each embedder's hybrid top 20, with the picture instruction (scores cached across runs). */
        fun rerank() {
            val rerankerFile = File(dir, "qwen3-reranker-0.6b-q8_0.gguf")
            if (!rerankerFile.isFile || (only != null && "rerank" !in only)) return
            val cacheFile = File(out, "rerank-cache.json")
            val cache = HashMap<String, Float>()
            if (cacheFile.isFile) json.parseToJsonElement(cacheFile.readText()).jsonObject.forEach { (k, v) -> cache[k] = v.jsonPrimitive.content.toFloat() }
            LlamaReranker("qwen3-reranker-0.6b-q8", rerankerFile.absolutePath).use { rr ->
                for ((name, pair) in indexes) {
                    val (ci, queries) = pair
                    var pairs = 0
                    var rerankMs = 0.0
                    run("rerank-$name") { l, hinted ->
                        val top = fused(ci, l, hinted, queries.getValue(q(l, hinted))).take(20)
                        val query = Query(l.text, if (hinted) l.hint else "")
                        fun key(i: Int) = "${query.rerankText}|${docs[i].id}"
                        val missing = top.filter { key(it.index) !in cache }
                        if (missing.isNotEmpty()) {
                            val t0 = System.nanoTime()
                            val got = runBlocking { rr.rerank(query.rerankText, missing.map { docs[it.index].rerankText }, RerankPrompt.VISUAL) }
                            rerankMs += (System.nanoTime() - t0) / 1e6
                            pairs += missing.size
                            missing.forEachIndexed { k, s -> cache[key(s.index)] = got[k] }
                        }
                        val scored = top.map { docs[it.index].id to cache.getValue(key(it.index)) }.sortedByDescending { it.second }
                        Ranked(scored.map { it.first }.take(10), scored.firstOrNull()?.second)
                    }
                    if (pairs > 0) report.append("rerank-$name: $pairs pairs in ${"%.0f".format(rerankMs / 1000)} s (${"%.1f".format(rerankMs / pairs)} ms per pair)\n")
                    cacheFile.writeText(JsonObject(cache.mapValues { JsonPrimitive(it.value) }).toString())
                }
            }
        }

        fun write() {
            report.append(Metrics.table(results))
            File(out, "report.md").writeText(report.toString())
            File(out, "metrics.json").writeText(JsonObject(results.mapValues { JsonPrimitive(it.value.toString()) }).toString())
            println(report)
        }
    }

    private fun q(l: Line, hinted: Boolean) = if (hinted && l.hint.isNotBlank()) "${l.text} | ${l.hint}" else l.text

    private suspend fun embedQueries(e: TextEmbedder, lines: List<Line>): Map<String, FloatArray> {
        val texts = lines.flatMap { listOf(q(it, false), q(it, true)) }.distinct()
        val t0 = System.nanoTime()
        val v = e.embed(texts, EmbedRole.Query)
        println("eval ${e.id}: ${texts.size} queries embedded, ${"%.1f".format((System.nanoTime() - t0) / 1e6 / texts.size)} ms each")
        return texts.zip(v).toMap()
    }

    /** Ranking quality and what would be shown. Floors for scored systems are two-fold cross-validated. */
    data class Metrics(
        val recall10: Double, val ndcg10: Double, val mrr: Double, val p1: Double,
        val shown: Double, val irrelevantPicks: Double, val precisionShown: Double, val abstractShown: Double, val floor: String, val msPerLine: Double,
    ) {
        override fun toString() = "R@10 ${p(recall10)} nDCG@10 ${p(ndcg10)} MRR ${p(mrr)} P@1 ${p(p1)} | shown ${p(shown)} irrelevant ${p(irrelevantPicks)} " +
            "precision ${p(precisionShown)} abstract-shown ${p(abstractShown)} floor $floor | ${"%.1f".format(msPerLine)} ms/line"

        companion object {
            private fun p(x: Double) = "%.3f".format(x)

            fun of(lines: List<Line>, ranked: List<Ranked>, ms: Double): Metrics {
                val labelled = lines.indices.filter { !lines[it].abstract }
                var recall = 0.0
                var ndcg = 0.0
                var mrr = 0.0
                var p1 = 0.0
                for (i in labelled) {
                    val rel = lines[i].relevant
                    val ids = ranked[i].ids.take(10)
                    val hits = ids.map { it in rel }
                    recall += if (hits.any { it }) 1.0 else 0.0
                    val dcg = hits.withIndex().sumOf { (k, h) -> if (h) 1.0 / log2(k + 2.0) else 0.0 }
                    val ideal = (0 until minOf(10, rel.size)).sumOf { 1.0 / log2(it + 2.0) }
                    ndcg += dcg / ideal
                    mrr += hits.indexOfFirst { it }.let { if (it < 0) 0.0 else 1.0 / (it + 1) }
                    p1 += if (hits.firstOrNull() == true) 1.0 else 0.0
                }
                val n = labelled.size.toDouble()
                // What would be shown: the top pick when it passes the floor (old: its own floor).
                val (floor, shownFlags) = shownWithFloor(lines, ranked)
                val shownCount = shownFlags.count { it }
                val irrelevant = lines.indices.count { shownFlags[it] && ranked[it].ids.firstOrNull() !in lines[it].relevant }
                val abstract = lines.indices.filter { lines[it].abstract }
                return Metrics(
                    recall / n, ndcg / n, mrr / n, p1 / n,
                    shownCount.toDouble() / lines.size, irrelevant.toDouble() / lines.size,
                    if (shownCount == 0) 0.0 else (shownCount - irrelevant).toDouble() / shownCount,
                    if (abstract.isEmpty()) 0.0 else abstract.count { shownFlags[it] }.toDouble() / abstract.size, floor, ms,
                )
            }

            /**
             * Each line's show decision. Scored systems: a floor chosen on one half of the lines (odd or
             * even) to maximise correct decisions (show a relevant pick, or show nothing when the top is
             * irrelevant), applied to the other half. Unscored systems show their top pick whenever they have one.
             */
            private fun shownWithFloor(lines: List<Line>, ranked: List<Ranked>): Pair<String, List<Boolean>> {
                if (ranked.all { it.oldShown != null }) return "own" to ranked.map { it.oldShown!! }
                if (ranked.all { it.topScore == null }) return "none" to ranked.map { it.ids.isNotEmpty() }
                val good = lines.indices.map { ranked[it].ids.firstOrNull() in lines[it].relevant }
                fun best(train: List<Int>): Float {
                    val candidates = train.mapNotNull { ranked[it].topScore }.distinct().sorted()
                    return candidates.maxByOrNull { f -> train.count { i -> (ranked[i].topScore ?: -1f) >= f == good[i] } } ?: 0f
                }
                val even = lines.indices.filter { it % 2 == 0 }
                val odd = lines.indices.filter { it % 2 == 1 }
                val fEven = best(odd)
                val fOdd = best(even)
                val shown = lines.indices.map { i -> (ranked[i].topScore ?: -1f) >= if (i % 2 == 0) fEven else fOdd }
                val all = best(lines.indices.toList())
                return "%.3f/%.3f (all %.3f)".format(fEven, fOdd, all) to shown
            }

            private fun log2(x: Double) = ln(x) / ln(2.0)

            fun table(m: Map<String, Metrics>): String = buildString {
                append("\n| system | mode | R@10 | nDCG@10 | MRR | P@1 | shown | irrelevant picks | precision of shown | abstract shown | floor | ms/line |\n")
                append("|---|---|---|---|---|---|---|---|---|---|---|---|\n")
                for ((k, v) in m) {
                    append("| ${k.substringBefore('/')} | ${k.substringAfter('/')} | ${p(v.recall10)} | ${p(v.ndcg10)} | ${p(v.mrr)} | ${p(v.p1)} | ${p(v.shown)} | ")
                    append("${p(v.irrelevantPicks)} | ${p(v.precisionShown)} | ${p(v.abstractShown)} | ${v.floor} | ${"%.1f".format(v.msPerLine)} |\n")
                }
            }
        }
    }
}
