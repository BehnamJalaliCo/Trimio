package io.trimio.engine.audio

import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Builds the shared [AudioFeatures] (energy + pitch contour) and scores how strongly each word
 * was stressed. Stress in Persian and English is carried by loudness, pitch excursion and
 * lengthening, measured against the speaker's own baseline so quiet and loud speakers score alike.
 */
class ProsodyAnalyzer(
    private val hopMs: Int = 10,
    private val frameMs: Int = 30,
    private val pitchTracker: PitchTracker = PitchTracker(),
) {
    fun features(audio: PcmAudio, speech: List<TimeRange>, integratedLufs: Float, appliedGainDb: Float): AudioFeatures {
        val energy = VoiceActivityDetector.frameEnergiesDb(audio, frameMs, hopMs)
            .map { it.coerceAtLeast(AudioFeatures.SILENCE_DB) }.toFloatArray()
        val inSpeech = BooleanArray(energy.size) { f -> speech.any { (f.toLong() * hopMs) in it } }
        val pitch = pitchTracker.track(audio, hopMs, energy.size) { f -> inSpeech[f] }
        return AudioFeatures(hopMs, energy, pitch, integratedLufs, appliedGainDb, speech)
    }

    /** Emphasis 0..1 per word, aligned with [words]. */
    fun emphasis(features: AudioFeatures, words: List<Word>): List<Float> {
        if (words.isEmpty()) return emptyList()
        val stats = words.map { wordStats(features, it) }

        val energyZ = zScores(stats.map { it.meanDb })
        val pitchZ = zScores(stats.map { it.peakSemitones })
        // Lengthening: duration per character relative to the speaker's speaking rate.
        val stretchZ = zScores(words.map { it.range.durationMs.toFloat() / it.text.length.coerceAtLeast(1) })

        return stats.indices.map { i ->
            val pitchTerm = if (stats[i].voiced) pitchZ[i] else 0f
            val score = 1.0f * energyZ[i] + 0.8f * pitchTerm + 0.35f * stretchZ[i] - 1.2f
            sigmoid(score * 1.6f)
        }
    }

    private class WordStats(val meanDb: Float, val peakSemitones: Float, val voiced: Boolean)

    private fun wordStats(features: AudioFeatures, word: Word): WordStats {
        val first = features.frameAt(word.range.startMs)
        val last = features.frameAt(word.range.endMs).coerceAtLeast(first)
        var sum = 0f
        var peak = 0f
        for (f in first..last) {
            sum += features.energyDb[f]
            peak = maxOf(peak, features.pitchHz[f])
        }
        val voiced = peak > 0f
        // Semitones are the perceptual pitch scale; 100 Hz reference is arbitrary (z-scored later).
        val semitones = if (voiced) (12 * ln(peak / 100f) / ln(2f)) else 0f
        return WordStats(sum / (last - first + 1), semitones, voiced)
    }

    private fun zScores(values: List<Float>): List<Float> {
        if (values.size < 2) return values.map { 0f }
        val mean = values.average().toFloat()
        val sd = sqrt(values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size).toFloat()
        return if (sd < 1e-4f) values.map { 0f } else values.map { (it - mean) / sd }
    }

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))
}
