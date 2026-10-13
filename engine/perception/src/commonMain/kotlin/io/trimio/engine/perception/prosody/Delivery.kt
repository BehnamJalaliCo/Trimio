package io.trimio.engine.perception.prosody

import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.transcript.Word
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How a stretch of speech is delivered: pace, energy and the words that carry the stress. */
data class LineDelivery(
    /** Index range of the line in the word list. */
    val words: IntRange,
    /** Letters per second of speech (pauses inside the line excluded) against the speaker's median: 1 is their normal pace. */
    val pace: Float,
    /** 1 (calm) .. 5 (intense): loudness and pitch range against the speaker's own. */
    val energy: Int,
    /** Silence after the line, seconds: a long one is a natural place for a cut or a beat of stillness. */
    val pauseAfter: Float,
    /** Word indexes (into the whole list) stressed hardest in this line, strongest first. */
    val stressed: List<Int>,
)

/**
 * Emphasis and pacing from the voice alone — loudness, pitch excursion (semitones), lengthening and
 * pauses, each against the speaker's own baseline — so visual hits land on stressed words and the
 * edit breathes with the speaker. No emotion model: none of the open ones has a licence that
 * clearly allows commercial use (emotion2vec: FunASR model licence; the Persian HuBERT/ShEMO one: the
 * dataset carries no licence), so this stays signal processing we own.
 */
object Delivery {
    /**
     * Per-line delivery. [emphasis] is the per-word stress 0..1 (the engine's ProsodyAnalyzer);
     * [lines] are index ranges into [words] (caption lines or sentences).
     */
    fun of(words: List<Word>, emphasis: List<Float>, features: AudioFeatures?, lines: List<IntRange>): List<LineDelivery> {
        if (words.isEmpty()) return emptyList()
        val rates = lines.map { rate(words, it) }
        val medianRate = rates.filter { it > 0f }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] }
        val dynamics = lines.map { dynamics(features, words, it) }
        val dz = zScores(dynamics)
        return lines.mapIndexed { i, r ->
            val next = words.getOrNull(r.last + 1)
            val pause = next?.let { (it.range.startMs - words[r.last].range.endMs) / MS } ?: 0f
            LineDelivery(
                words = r,
                pace = if (rates[i] <= 0f) 1f else rates[i] / medianRate,
                energy = (THREE + dz[i] * ENERGY_STEP).toInt().coerceIn(1, FIVE),
                pauseAfter = max(0f, pause),
                stressed = r.filter { emphasis.getOrElse(it) { 0f } >= STRESS }.sortedByDescending { emphasis[it] }.take(MAX_STRESSED),
            )
        }
    }

    /**
     * The words to hit with a visual accent: stressed above [threshold], at least [minGapMs] apart
     * (the stronger wins), at most one per [perSeconds] on average.
     */
    fun hits(words: List<Word>, emphasis: List<Float>, threshold: Float = STRESS, minGapMs: Long = 1200, perSeconds: Float = 2.5f): List<Int> {
        if (words.isEmpty()) return emptyList()
        val span = (words.last().range.endMs - words.first().range.startMs) / MS
        val budget = max(1, (span / perSeconds).toInt())
        val picked = ArrayList<Int>()
        for (i in words.indices.filter { emphasis.getOrElse(it) { 0f } >= threshold }.sortedByDescending { emphasis[it] }) {
            if (picked.size >= budget) break
            if (picked.none { kotlin.math.abs(words[it].range.startMs - words[i].range.startMs) < minGapMs }) picked += i
        }
        return picked.sorted()
    }

    private fun rate(words: List<Word>, r: IntRange): Float {
        var letters = 0
        var ms = 0L
        for (i in r) {
            letters += words[i].text.count { it.isLetterOrDigit() }
            ms += words[i].range.durationMs
        }
        return if (ms <= 0) 0f else letters / (ms / MS)
    }

    /** Mean loudness plus half the pitch range in semitones over the line's frames. */
    private fun dynamics(features: AudioFeatures?, words: List<Word>, r: IntRange): Float {
        if (features == null || features.frameCount == 0) return 0f
        val first = features.frameAt(words[r.first].range.startMs)
        val last = max(first, features.frameAt(words[r.last].range.endMs))
        var db = 0f
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (f in first..last) {
            db += features.energyDb[f]
            val p = features.pitchHz[f]
            if (p > 0f) {
                lo = min(lo, p)
                hi = max(hi, p)
            }
        }
        val range = if (hi > 0f && lo < Float.MAX_VALUE) SEMITONES * ln(hi / lo) / ln(2f) else 0f
        return db / (last - first + 1) + range / 2f
    }

    private fun zScores(values: List<Float>): List<Float> {
        if (values.size < 2) return values.map { 0f }
        val mean = values.average().toFloat()
        val sd = sqrt(values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size).toFloat()
        return if (sd < 1e-4f) values.map { 0f } else values.map { (it - mean) / sd }
    }

    private const val MS = 1000f
    private const val STRESS = 0.6f
    private const val MAX_STRESSED = 2
    private const val THREE = 3.5f
    private const val FIVE = 5
    private const val ENERGY_STEP = 1.2f
    private const val SEMITONES = 12f
}
