package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.log10
import kotlin.math.pow

/**
 * The voice with its slow level evened out, for deciding where speech is and where it may be cut.
 * A video joined from takes recorded at different distances (or a speaker who turns away) can be
 * 10–15 dB quieter in one part: judged against the whole video's level, every word of the quiet
 * part looks like a pause and the cut list removes speech. Each region is lifted to the level of
 * the loudest speech around it ([WINDOW_S] each side), never more than [MAX_LIFT_DB] and never
 * lowered, so a pause stays a pause and a quiet take speaks as loud as the rest. What is played
 * is the original voice; this is only what the cuts are measured on.
 */
internal object VoiceLevel {

    /** [voice] levelled (one entry is kept: every judge of a production asks for the same voice). */
    fun levelled(voice: PcmAudio): PcmAudio {
        cache?.let { (from, to) -> if (from === voice) return to }
        return level(voice).also { cache = voice to it }
    }

    private var cache: Pair<PcmAudio, PcmAudio>? = null

    private fun level(voice: PcmAudio): PcmAudio {
        val rate = voice.sampleRate
        val x = voice.samples
        val hop = (rate * FRAME_S).toInt().coerceAtLeast(1)
        val frames = FloatArray(x.size / hop) { k ->
            var sum = 0.0
            for (i in k * hop until (k + 1) * hop) sum += x[i].toDouble() * x[i]
            (10.0 * log10(sum / hop + 1e-12)).toFloat()
        }
        if (frames.size < MIN_FRAMES) return voice
        val loud = percentile(frames, LOUD)
        val step = (STEP_S / FRAME_S).toInt()
        val reach = (WINDOW_S / FRAME_S).toInt()
        // The loud speech around each point: a high percentile of its neighbourhood (pauses do not pull it down).
        val centres = (0 until frames.size step step).toList()
        val lift = centres.map { c ->
            val a = (c - reach).coerceAtLeast(0)
            val b = (c + reach).coerceAtMost(frames.size)
            val local = percentile(frames.copyOfRange(a, b), LOUD)
            (loud - local).coerceIn(0f, MAX_LIFT_DB)
        }
        if (lift.all { it < MIN_LIFT_DB }) return voice
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val f = i.toFloat() / hop / step
            val k = f.toInt().coerceIn(0, lift.lastIndex)
            val next = (k + 1).coerceAtMost(lift.lastIndex)
            val db = lift[k] + (lift[next] - lift[k]) * (f - k).coerceIn(0f, 1f)
            out[i] = x[i] * 10f.pow(db / DB_TO_GAIN)
        }
        return PcmAudio(out, rate)
    }

    private fun percentile(values: FloatArray, p: Float): Float {
        val sorted = values.sortedArray()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    private const val FRAME_S = 0.01f
    private const val STEP_S = 0.5f
    private const val WINDOW_S = 3f
    private const val LOUD = 0.9f
    private const val MAX_LIFT_DB = 18f
    private const val MIN_LIFT_DB = 1f
    private const val MIN_FRAMES = 100
    private const val DB_TO_GAIN = 20f
}
