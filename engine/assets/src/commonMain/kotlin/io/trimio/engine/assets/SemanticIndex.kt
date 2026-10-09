package io.trimio.engine.assets

import io.trimio.core.model.text.TextKey
import kotlin.math.sqrt

/**
 * Lightweight multilingual text embedding: hashed character n-grams (2–4) of the normalised text,
 * L2-normalised. It understands inflection and compounds ("رشدش", "rockets", "بیت\u200Cکوینی") without
 * a model download, runs in microseconds and is identical on every platform. A neural embedder can
 * replace [embed] later without changing callers.
 */
class SemanticIndex<T>(entries: List<Pair<T, List<String>>>, private val dims: Int = 1024) {

    // One vector per text: an item matches through its single closest synonym, not their average.
    private val vectors: List<Pair<T, FloatArray>> = entries.flatMap { (item, texts) -> texts.map { item to embed(it) } }

    /** Best match for [query] with cosine similarity of at least [minScore], or null. */
    fun best(query: String, minScore: Float = 0.35f): Pair<T, Float>? {
        val words = query.split(' ').filter { it.isNotBlank() }
        // Whole query and each of its words, so one strong word in a phrase still matches.
        val queries = (listOf(query) + words).distinct().map(::embed)
        return vectors.map { (item, v) -> item to queries.maxOf { dot(it, v) } }.maxByOrNull { it.second }?.takeIf { it.second >= minScore }
    }

    fun embed(text: String): FloatArray = normalise(embedRaw(text))

    private fun embedRaw(text: String): FloatArray {
        val v = FloatArray(dims)
        val key = "^" + TextKey.of(text) + "$"
        for (n in 2..4) {
            for (i in 0..key.length - n) {
                val gram = key.substring(i, i + n)
                v[(gram.hashCode() and Int.MAX_VALUE) % dims] += if (n == 2) 0.5f else 1f
            }
        }
        return v
    }

    private fun normalise(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
