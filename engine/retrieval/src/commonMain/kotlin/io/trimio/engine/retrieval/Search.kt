package io.trimio.engine.retrieval

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A document (by index in its corpus) and a score; higher is better. */
data class Scored(val index: Int, val score: Float)

/** The [k] best of [n] scores (null skips an index), best first; a bounded heap, no full sort. */
internal fun topK(n: Int, k: Int, score: (Int) -> Float?): List<Scored> {
    if (k <= 0 || n == 0) return emptyList()
    val cap = min(k, n)
    val idx = IntArray(cap)
    val sc = FloatArray(cap)
    var size = 0
    fun swap(a: Int, b: Int) {
        val ti = idx[a]
        idx[a] = idx[b]
        idx[b] = ti
        val ts = sc[a]
        sc[a] = sc[b]
        sc[b] = ts
    }
    fun down(start: Int) {
        var i = start
        while (true) {
            val l = 2 * i + 1
            val r = l + 1
            var m = i
            if (l < size && sc[l] < sc[m]) m = l
            if (r < size && sc[r] < sc[m]) m = r
            if (m == i) return
            swap(i, m)
            i = m
        }
    }
    for (i in 0 until n) {
        val s = score(i) ?: continue
        if (size < cap) {
            idx[size] = i
            sc[size] = s
            var c = size++
            while (c > 0 && sc[(c - 1) / 2] > sc[c]) {
                swap(c, (c - 1) / 2)
                c = (c - 1) / 2
            }
        } else if (s > sc[0]) {
            idx[0] = i
            sc[0] = s
            down(0)
        }
    }
    return (0 until size).map { Scored(idx[it], sc[it]) }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.index })
}

/**
 * Okapi BM25 over [Terms] (k1 = 1.2, b = 0.75): an in-memory inverted index, identical on every
 * platform. Corpora here are small (tens of thousands of short documents at most).
 */
class Bm25(texts: List<String>, private val k1: Float = 1.2f, private val b: Float = 0.75f) {
    val size: Int = texts.size
    private val lengths = IntArray(texts.size)
    private val postings: Map<String, Pair<IntArray, IntArray>>
    private val avgLength: Float

    init {
        val build = HashMap<String, MutableMap<Int, Int>>()
        texts.forEachIndexed { d, text ->
            val terms = Terms.of(text)
            lengths[d] = terms.size
            for (t in terms) {
                val tf = build.getOrPut(t) { LinkedHashMap() }
                tf[d] = (tf[d] ?: 0) + 1
            }
        }
        postings = build.mapValues { (_, tf) -> tf.keys.toIntArray() to tf.values.toIntArray() }
        avgLength = if (texts.isEmpty()) 1f else max(1f, lengths.sum().toFloat() / texts.size)
    }

    /** Inverse document frequency of [term] (0 when no document has it). */
    fun idf(term: String): Float {
        val df = postings[term]?.first?.size ?: return 0f
        return ln(1.0 + (size - df + 0.5) / (df + 0.5)).toFloat()
    }

    /** The best [limit] documents for [query] (only those [allow] admits), best first; documents sharing no term are left out. */
    fun search(query: String, limit: Int, allow: ((Int) -> Boolean)? = null): List<Scored> {
        val scores = HashMap<Int, Float>()
        for (t in Terms.of(query).distinct()) {
            val (docs, tfs) = postings[t] ?: continue
            val w = idf(t)
            for (i in docs.indices) {
                val d = docs[i]
                if (allow != null && !allow(d)) continue
                val tf = tfs[i].toFloat()
                val norm = tf * (k1 + 1) / (tf + k1 * (1 - b + b * lengths[d] / avgLength))
                scores[d] = (scores[d] ?: 0f) + w * norm
            }
        }
        val keys = scores.keys.toIntArray()
        return topK(keys.size, limit) { scores[keys[it]] }.map { Scored(keys[it.index], it.score) }
    }
}

/**
 * Unit vectors stored as int8 with one scale each (symmetric: value = byte × scale), 4× smaller
 * than floats and searched by brute force — 30k × 768 is ~23 MB and a few milliseconds. The query
 * stays in floats; scores are cosine similarities (vectors are unit length).
 */
class Int8Vectors(val dims: Int, capacity: Int = 16) {
    private var data = ByteArray(dims * capacity)
    private var scales = FloatArray(capacity)
    var size: Int = 0
        private set

    /** Appends [v] (any length; unit length expected); returns its index. */
    fun add(v: FloatArray): Int {
        require(v.size == dims) { "vector of ${v.size} dims in a $dims-dim index" }
        if (size == scales.size) {
            data = data.copyOf(data.size * 2)
            scales = scales.copyOf(scales.size * 2)
        }
        put(size, quantize(v), scaleOf(v))
        return size++
    }

    /** Appends an already quantised vector (from a pack). */
    fun addQuantised(bytes: ByteArray, scale: Float): Int {
        require(bytes.size == dims)
        if (size == scales.size) {
            data = data.copyOf(data.size * 2)
            scales = scales.copyOf(scales.size * 2)
        }
        put(size, bytes, scale)
        return size++
    }

    private fun put(i: Int, bytes: ByteArray, scale: Float) {
        bytes.copyInto(data, i * dims)
        scales[i] = scale
    }

    fun bytes(i: Int): ByteArray = data.copyOfRange(i * dims, (i + 1) * dims)

    fun scale(i: Int): Float = scales[i]

    /** Cosine of [query] (unit, [dims] floats) with vector [i]. */
    fun dot(i: Int, query: FloatArray): Float {
        var s = 0f
        val off = i * dims
        for (j in 0 until dims) s += query[j] * data[off + j]
        return s * scales[i]
    }

    /** Vector [i] back in floats (approximately). */
    fun vector(i: Int): FloatArray = FloatArray(dims) { data[i * dims + it] * scales[i] }

    fun search(query: FloatArray, limit: Int, allow: ((Int) -> Boolean)? = null): List<Scored> {
        require(query.size == dims) { "query of ${query.size} dims in a $dims-dim index" }
        return topK(size, limit) { i -> if (allow != null && !allow(i)) null else dot(i, query) }
    }

    companion object {
        private const val LEVELS = 127f

        fun scaleOf(v: FloatArray): Float = (v.maxOfOrNull { kotlin.math.abs(it) } ?: 0f).let { if (it > 0f) it / LEVELS else 1f }

        fun quantize(v: FloatArray): ByteArray {
            val s = scaleOf(v)
            return ByteArray(v.size) { (v[it] / s).roundToInt().coerceIn(-LEVELS.toInt(), LEVELS.toInt()).toByte() }
        }

        /** [v] scaled to unit length (a zero vector stays zero). */
        fun unit(v: FloatArray): FloatArray {
            val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return if (n > 0f) FloatArray(v.size) { v[it] / n } else v
        }
    }
}

/** Reciprocal-rank fusion (Cormack et al.): each list votes 1 / (k + rank) for its documents. */
object Fusion {
    const val K = 60

    fun rrf(lists: List<List<Int>>, k: Int = K): List<Scored> {
        val score = HashMap<Int, Float>()
        for (list in lists) list.forEachIndexed { rank, d -> score[d] = (score[d] ?: 0f) + 1f / (k + rank + 1) }
        return score.entries.map { Scored(it.key, it.value) }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.index })
    }
}
