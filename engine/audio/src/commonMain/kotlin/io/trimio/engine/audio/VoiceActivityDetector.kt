package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.time.TimeRange
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Energy-based speech detector with an adaptive noise floor. Robust enough for jump cuts
 * and caption timing on phone recordings; a neural VAD (Silero) can replace it behind
 * the same output later.
 *
 * Frames louder than the noise floor by [marginDb] count as speech; [hangoverMs] keeps
 * word endings, short gaps are merged and short blips dropped.
 */
class VoiceActivityDetector(
    private val frameMs: Int = 30,
    private val hopMs: Int = 10,
    private val marginDb: Float = 10f,
    private val minFloorDb: Float = -65f,
    private val hangoverMs: Int = 150,
    private val mergeGapMs: Int = 200,
    /** Isolated sounds whose loud part is shorter than this are clicks or bumps, not speech. */
    private val minSpeechMs: Int = 100,
    /**
     * When set, frames this far below the speaker's typical level also count as silence, even
     * above the noise floor: breaths and room tone in a pause, which a cut can remove.
     */
    private val belowSpeechDb: Float? = null,
) {
    /** A detected region; [coreMs] is its loud part before hangover padding. */
    private class Segment(val range: TimeRange, val coreMs: Long, val parts: Int = 1)

    fun detect(audio: PcmAudio): List<TimeRange> {
        val energies = frameEnergiesDb(audio, frameMs, hopMs)
        if (energies.isEmpty()) return emptyList()

        val floor = maxOf(percentile(energies, 0.10f), minFloorDb)
        val threshold = belowSpeechDb?.let { maxOf(floor + marginDb, percentile(energies, 0.7f) - it) } ?: (floor + marginDb)
        val hangoverFrames = hangoverMs / hopMs

        val raw = mutableListOf<Segment>()
        var start = -1
        var lastLoud = -1
        for (i in energies.indices) {
            if (energies[i] > threshold) {
                if (start < 0) start = i
                lastLoud = i
            } else if (start >= 0 && i - lastLoud > hangoverFrames) {
                raw += frames(start, lastLoud, hangoverFrames, audio.durationMs)
                start = -1
            }
        }
        if (start >= 0) raw += frames(start, lastLoud, hangoverFrames, audio.durationMs)

        // Judge length on the loud core: hangover padding must not turn a click into "speech".
        // Merged groups are kept, so short words next to other speech survive.
        return merge(raw).filter { it.parts > 1 || it.coreMs >= minSpeechMs }.map { it.range }
    }

    /** [first]..[lastLoud] are loud frames; the range is padded by the hangover. */
    private fun frames(first: Int, lastLoud: Int, hangover: Int, totalMs: Long): Segment {
        val startMs = first.toLong() * hopMs
        val endMs = minOf((lastLoud + hangover).toLong() * hopMs + frameMs, totalMs)
        val coreMs = (lastLoud - first).toLong() * hopMs + frameMs
        return Segment(TimeRange(startMs, maxOf(startMs, endMs)), coreMs)
    }

    private fun merge(segments: List<Segment>): List<Segment> {
        val merged = mutableListOf<Segment>()
        for (seg in segments) {
            val last = merged.lastOrNull()
            if (last != null && seg.range.startMs - last.range.endMs <= mergeGapMs) {
                merged[merged.lastIndex] = Segment(
                    TimeRange(last.range.startMs, maxOf(last.range.endMs, seg.range.endMs)),
                    last.coreMs + seg.coreMs,
                    last.parts + seg.parts,
                )
            } else {
                merged += seg
            }
        }
        return merged
    }

    companion object {
        /** RMS level per frame in dBFS, floored at -100. */
        fun frameEnergiesDb(audio: PcmAudio, frameMs: Int, hopMs: Int): FloatArray {
            val frame = audio.sampleRate * frameMs / 1000
            val hop = audio.sampleRate * hopMs / 1000
            if (audio.size < frame || hop <= 0) return FloatArray(0)
            val count = (audio.size - frame) / hop + 1
            val s = audio.samples
            return FloatArray(count) { f ->
                var sum = 0.0
                val off = f * hop
                for (i in off until off + frame) sum += s[i] * s[i]
                val rms = sqrt(sum / frame)
                if (rms <= 1e-5) -100f else (20 * log10(rms)).toFloat()
            }
        }

        internal fun percentile(values: FloatArray, p: Float): Float {
            val sorted = values.sortedArray()
            return sorted[((sorted.size - 1) * p).toInt()]
        }
    }
}
