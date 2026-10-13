package io.trimio.engine.retrieval

import io.trimio.engine.llm.EmbedRole
import io.trimio.engine.llm.RerankPrompt
import io.trimio.engine.llm.TextEmbedder
import io.trimio.engine.llm.TextReranker
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What to look for: the sentence as said ([text], Persian or English), optionally the director's
 * own English picture idea ([hint], HyDE-style) and the surrounding lines ([context], for the
 * reranker), within some document kinds, never offering [exclude] again.
 */
data class Query(
    val text: String,
    val hint: String = "",
    val context: String = "",
    val kinds: Set<String>? = null,
    val exclude: Set<String> = emptySet(),
    val instruction: String = RerankPrompt.VISUAL,
) {
    /** What BM25 and the embedder read. */
    val searchText: String get() = if (hint.isBlank()) text else "$text | $hint"

    /** What the reranker reads as the query. */
    val rerankText: String get() = buildString {
        append(text.trim())
        if (hint.isNotBlank()) append(" (idea: ").append(hint.trim()).append(')')
        if (context.isNotBlank()) append("\nContext: ").append(context.trim())
    }
}

/**
 * One candidate: [relevance] is the strongest signal available — the reranker's P(yes), else the
 * dense cosine, else a BM25 squash — and [fit] maps it onto one 0..1 scale on which the relevance
 * floor is 0.5 (the jury's picture-fit line), whatever produced it.
 */
data class Hit(
    val doc: Doc,
    val relevance: Float,
    val fit: Float,
    val lexical: Float,
    val dense: Float?,
    val rerank: Float?,
    val fused: Float,
)

/** How a [Shortlist] was made. */
enum class Signal { Lexical, Dense, Reranked }

/** Candidates best first, with the [floor] their [Hit.relevance] must reach to be offered. */
data class Shortlist(val hits: List<Hit>, val floor: Float, val signal: Signal) {
    /** The candidates worth offering (at or above the floor; none at all when lexical-only and nothing matched). */
    val passing: List<Hit> get() = hits.filter { it.relevance >= floor }

    val best: Hit? get() = passing.firstOrNull()

    /** Nothing reaches the floor: show typography or a primitive, never an irrelevant picture. */
    val belowFloor: Boolean get() = passing.isEmpty()

    /** A JSON-schema string enum of the passing ids plus "none": a grammar-constrained model can only pick among them. */
    fun choiceSchema(none: String = NONE): JsonObject = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray((passing.map { it.doc.id } + none).distinct().map(::JsonPrimitive)))
    }

    companion object {
        const val NONE = "none"
        val Empty = Shortlist(emptyList(), 0f, Signal.Lexical)
    }
}

/**
 * Floors and depths. Floors are calibrated on the labelled set (RetrievalEvalTest): the reranker's
 * P(yes) and each embedder's cosine at which a pick stops being more likely relevant than not.
 */
data class RetrievalConfig(
    val lexicalDepth: Int = 50,
    val denseDepth: Int = 50,
    val rerankDepth: Int = 20,
    val rerankFloor: Float = 0.5f,
    /** Cosine floors by embedder id prefix; [defaultDenseFloor] for others. */
    val denseFloors: Map<String, Float> = DENSE_FLOORS,
    val defaultDenseFloor: Float = 0.5f,
    /** BM25 score that maps to the middle of the fit scale when only words are matched. */
    val lexicalHalf: Float = 6f,
) {
    fun denseFloor(embedderId: String): Float = denseFloors.entries.firstOrNull { embedderId.startsWith(it.key) }?.value ?: defaultDenseFloor

    companion object {
        val DENSE_FLOORS = mapOf("embeddinggemma-2" to 0.5f, "bge-m3" to 0.5f, "qwen3-embedding" to 0.5f)
    }
}

/**
 * Hybrid retrieval: BM25 and (when an embedder is installed) dense search, fused by reciprocal
 * rank; the top of the fusion reranked (when a reranker is installed) with an instruction made for
 * the job ("would this visual make the sentence instantly clear?"); then the relevance floor.
 * Without either model it is BM25 alone, with no floor (the caller's older matching decides).
 */
class Retriever(
    val embedder: TextEmbedder? = null,
    val reranker: TextReranker? = null,
    val config: RetrievalConfig = RetrievalConfig(),
) {
    suspend fun search(index: CorpusIndex, query: Query, limit: Int = DEFAULT_LIMIT): Shortlist {
        val allow = allowed(index, query)
        val lexical = index.bm25.search(query.searchText, config.lexicalDepth, allow)
        val e = embedder
        val vectors = index.vectors?.takeIf { e != null && index.space == e.space }
        val q = if (vectors != null && e != null) e.embed(listOf(query.searchText), EmbedRole.Query).single() else null
        val dense = if (vectors != null && q != null) vectors.search(q, config.denseDepth, allow) else emptyList()
        val fused = Fusion.rrf(listOf(lexical.map { it.index }, dense.map { it.index }))
        if (fused.isEmpty()) return Shortlist(emptyList(), 0f, Signal.Lexical)
        val lexBy = lexical.associate { it.index to it.score }
        fun cosine(i: Int): Float? = if (vectors != null && q != null) vectors.dot(i, q) else null
        val rr = reranker
        return when {
            rr != null -> {
                val top = fused.take(config.rerankDepth)
                val scores = rr.rerank(query.rerankText, top.map { index.docs[it.index].rerankText }, query.instruction)
                val hits = top.mapIndexed { k, s ->
                    val p = scores[k]
                    Hit(index.docs[s.index], p, fit(p, config.rerankFloor), lexBy[s.index] ?: 0f, cosine(s.index), p, s.score)
                }.sortedByDescending { it.relevance }.take(limit)
                Shortlist(hits, config.rerankFloor, Signal.Reranked)
            }
            vectors != null && e != null -> {
                val floor = config.denseFloor(e.id)
                val hits = fused.take(config.rerankDepth).map { s ->
                    val c = cosine(s.index) ?: 0f
                    Hit(index.docs[s.index], c, fit(c, floor), lexBy[s.index] ?: 0f, c, null, s.score)
                }.sortedByDescending { it.relevance }.take(limit)
                Shortlist(hits, floor, Signal.Dense)
            }
            else -> {
                val hits = lexical.take(limit).map { s ->
                    val r = s.score / (s.score + config.lexicalHalf)
                    Hit(index.docs[s.index], r, r, s.score, null, null, 0f)
                }
                Shortlist(hits, 0f, Signal.Lexical)
            }
        }
    }

    /**
     * [hits] reordered for variety (maximal marginal relevance): each next pick trades its relevance
     * against its likeness to those already picked ([lambda] 1 = relevance only).
     */
    fun diverse(index: CorpusIndex, hits: List<Hit>, k: Int, lambda: Float = MMR_LAMBDA): List<Hit> {
        val picked = mutableListOf<Hit>()
        val left = hits.toMutableList()
        val vectors = index.vectors
        fun like(a: Hit, b: Hit): Float {
            val ia = index.indexOf(a.doc.id)
            val ib = index.indexOf(b.doc.id)
            if (vectors != null && ia != null && ib != null) return vectors.dot(ia, vectors.vector(ib))
            val ta = Terms.of(a.doc.searchText).toSet()
            val tb = Terms.of(b.doc.searchText).toSet()
            return if (ta.isEmpty() || tb.isEmpty()) 0f else (ta intersect tb).size.toFloat() / (ta union tb).size
        }
        while (picked.size < k && left.isNotEmpty()) {
            val next = left.maxBy { h -> lambda * h.fit - (1 - lambda) * (picked.maxOfOrNull { like(h, it) } ?: 0f) }
            picked += next
            left -= next
        }
        return picked
    }

    private fun allowed(index: CorpusIndex, query: Query): ((Int) -> Boolean)? {
        if (query.kinds == null && query.exclude.isEmpty()) return null
        return { i -> index.docs[i].let { d -> (query.kinds == null || d.kind in query.kinds) && d.id !in query.exclude } }
    }

    companion object {
        const val DEFAULT_LIMIT = 8
        const val MMR_LAMBDA = 0.7f

        /** [x] on the fit scale: [floor] maps to 0.5, 1 to 1, 0 to 0 (piecewise linear, monotone). */
        fun fit(x: Float, floor: Float): Float = when {
            floor <= 0f -> x.coerceIn(0f, 1f)
            x >= floor -> (0.5f + 0.5f * (x - floor) / (1f - floor).coerceAtLeast(EPS)).coerceAtMost(1f)
            else -> (0.5f * x / floor).coerceAtLeast(0f)
        }

        private const val EPS = 1e-4f
    }
}
