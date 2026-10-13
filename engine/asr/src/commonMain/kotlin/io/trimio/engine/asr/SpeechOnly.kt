package io.trimio.engine.asr

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Speech-only audio for the recogniser, with the way back to the source timeline.
 *
 * Silero VAD finds the speech; the silences between spans are cut down to [gapMs] (a short pause
 * keeps whisper's segmenting natural) so the decoder never hallucinates in long silences and spends
 * no time on them. Every token time the decoder reports on the cut timeline is then mapped back:
 * inside a kept piece by its offset, inside a shortened gap to the nearest piece edge (a token's
 * start to the next piece, its end to the previous one). whisper.cpp's own `vad` option maps only
 * segment times back, not token times, which are what word timing is built from.
 */
class SpeechOnly private constructor(val audio: PcmAudio, private val pieces: List<Piece>, val speechSeconds: Float, val sourceSeconds: Float) {
    /** A kept stretch: where it starts on the source and on the cut timeline, and its length, in ms. */
    data class Piece(val sourceMs: Long, val cutMs: Long, val lengthMs: Long)

    /** Source time for a token boundary at [cutMs] on the cut timeline. */
    fun toSource(cutMs: Long, isStart: Boolean): Long {
        if (pieces.isEmpty()) return cutMs
        val inside = pieces.lastOrNull { cutMs >= it.cutMs } ?: return pieces.first().sourceMs
        val offset = cutMs - inside.cutMs
        if (offset <= inside.lengthMs) return inside.sourceMs + offset
        // In the shortened gap after [inside].
        val next = pieces.getOrNull(pieces.indexOf(inside) + 1)
        return if (isStart && next != null) next.sourceMs else inside.sourceMs + inside.lengthMs
    }

    companion object {
        /** Cuts below this share of speech are not worth it: the whole audio is recognised as is. */
        const val WORTH = 0.97f

        /**
         * The speech of [audio] at [spans] (seconds, sorted, as Silero reports them), joined with
         * [gapMs] of silence; null when there is no speech or almost all of it is speech.
         */
        fun of(audio: PcmAudio, spans: List<ClosedFloatingPointRange<Float>>, gapMs: Long = 200): SpeechOnly? {
            val rate = audio.sampleRate
            val total = audio.size / rate.toFloat()
            val clean = merge(spans.map { max(0f, it.start)..min(total, it.endInclusive) }.filter { it.endInclusive > it.start }, gapMs / MS)
            val speech = clean.sumOf { (it.endInclusive - it.start).toDouble() }.toFloat()
            if (clean.isEmpty() || speech >= total * WORTH) return null
            val gap = (gapMs * rate / MS.toLong()).toInt()
            val lengths = clean.map { ((it.endInclusive - it.start) * rate).toInt() }
            val out = FloatArray(lengths.sum() + gap * (clean.size - 1))
            val pieces = ArrayList<Piece>()
            var at = 0
            clean.forEachIndexed { i, s ->
                val from = (s.start * rate).toInt().coerceIn(0, audio.size)
                val n = min(lengths[i], audio.size - from)
                audio.samples.copyInto(out, at, from, from + n)
                pieces += Piece((s.start * MS).roundToLong(), at * MS.toLong() / rate, n * MS.toLong() / rate)
                at += lengths[i] + gap
            }
            return SpeechOnly(PcmAudio(out, rate), pieces, speech, total)
        }

        /** Spans closer than [gap] seconds become one: cutting a shorter pause gains nothing. */
        private fun merge(spans: List<ClosedFloatingPointRange<Float>>, gap: Float): List<ClosedFloatingPointRange<Float>> {
            val out = ArrayList<ClosedFloatingPointRange<Float>>()
            for (s in spans.sortedBy { it.start }) {
                val last = out.lastOrNull()
                if (last != null && s.start - last.endInclusive <= gap) out[out.lastIndex] = last.start..max(last.endInclusive, s.endInclusive) else out += s
            }
            return out
        }

        private const val MS = 1000f
    }
}
