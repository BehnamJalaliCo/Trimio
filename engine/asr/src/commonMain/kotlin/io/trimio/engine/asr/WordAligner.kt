package io.trimio.engine.asr

import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word

/**
 * Tightens recogniser word boundaries against the audio's energy envelope. Whisper timestamps are
 * often 50-150 ms late or early; captions that pop exactly on the syllable onset are what make
 * kinetic typography feel "locked" to the voice.
 *
 * Each start snaps to the onset (first frame rising above the speech threshold) inside a search
 * window, each end to where energy falls back. Words never overlap or reorder.
 * A CTC forced aligner (wav2vec2) can replace this behind the same function later.
 */
class WordAligner(
    private val searchBeforeMs: Long = 150,
    private val searchAfterMs: Long = 150,
    /** dB above the recording's noise floor that counts as voiced. */
    private val onsetMarginDb: Float = 12f,
) {
    fun align(words: List<Word>, features: AudioFeatures): List<Word> {
        if (words.isEmpty() || features.frameCount == 0) return words
        val floor = noiseFloor(features)
        val threshold = floor + onsetMarginDb
        val hop = features.hopMs.toLong()
        val loud = BooleanArray(features.frameCount) { features.energyDb[it] > threshold }

        val refined = words.map { w ->
            val start = snapStart(w.range.startMs, loud, hop)
            val end = snapEnd(w.range.endMs, maxOf(start + hop, w.range.startMs), loud, hop)
            w.copy(range = TimeRange(start, maxOf(end, start + MIN_WORD_MS)))
        }

        // Resolve overlaps: a word may not start before the previous ends.
        val out = ArrayList<Word>(refined.size)
        for (w in refined) {
            val prev = out.lastOrNull()
            if (prev != null && w.range.startMs < prev.range.endMs) {
                val boundary = (prev.range.endMs + w.range.startMs) / 2
                out[out.lastIndex] = prev.copy(range = TimeRange(prev.range.startMs, maxOf(prev.range.startMs + MIN_WORD_MS, boundary)))
                val start = out.last().range.endMs
                out += w.copy(range = TimeRange(start, maxOf(w.range.endMs, start + MIN_WORD_MS)))
            } else {
                out += w
            }
        }
        return out
    }

    /** The onset nearest the recogniser's guess: first loud frame after a quiet one inside the window. */
    private fun snapStart(guessMs: Long, loud: BooleanArray, hop: Long): Long {
        val from = ((guessMs - searchBeforeMs) / hop).toInt().coerceAtLeast(0)
        val to = ((guessMs + searchAfterMs) / hop).toInt().coerceAtMost(loud.lastIndex)
        var best = -1
        var bestDistance = Long.MAX_VALUE
        for (f in from..to) {
            val onset = loud[f] && (f == 0 || !loud[f - 1])
            if (onset) {
                val d = kotlin.math.abs(f * hop - guessMs)
                if (d < bestDistance) { best = f; bestDistance = d }
            }
        }
        return if (best >= 0) best * hop else guessMs
    }

    /** The release nearest the guess: last loud frame before a quiet one inside the window. */
    private fun snapEnd(guessMs: Long, notBeforeMs: Long, loud: BooleanArray, hop: Long): Long {
        val from = ((guessMs - searchBeforeMs) / hop).toInt().coerceAtLeast((notBeforeMs / hop).toInt()).coerceAtLeast(0)
        val to = ((guessMs + searchAfterMs) / hop).toInt().coerceAtMost(loud.lastIndex)
        var best = -1
        var bestDistance = Long.MAX_VALUE
        for (f in from..to) {
            val release = loud[f] && (f == loud.lastIndex || !loud[f + 1])
            if (release) {
                val d = kotlin.math.abs((f + 1) * hop - guessMs)
                if (d < bestDistance) { best = f; bestDistance = d }
            }
        }
        return if (best >= 0) (best + 1) * hop else guessMs
    }

    private fun noiseFloor(f: AudioFeatures): Float {
        val sorted = f.energyDb.sortedArray()
        return sorted[(sorted.size * 0.1).toInt().coerceAtMost(sorted.lastIndex)]
    }

    private companion object {
        const val MIN_WORD_MS = 60L
    }
}
