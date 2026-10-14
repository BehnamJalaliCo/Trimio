package io.trimio.engine.production

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Whether a video carries its own music under the voice (phase 9.3): Beat This! hears a steady beat — 50 to 200 bpm —
 * across most of it. Speech alone has no such pulse. Then the edit adds no music of its own (two musics clash) and its
 * hits land on the video's own beat.
 *
 * Steady means the beats sit on one grid: the tempo and phase that put the most heard beats within a few hundredths of
 * a second of a grid line, with most of the grid's lines heard. Intervals alone mislead under a voice: the finder misses
 * beats and hears extra onsets, and on the owner's talk with a 100 bpm bed under it the interval rule said «no music»
 * while the grid holds 60 % of its beats (the owner's five talks without music: 13–45 %, their grids a third heard).
 */
internal object SourceMusic {
    /** (bpm, offset of the first beat in seconds) of the video's own music, or null when there is none. */
    fun tempo(beats: List<Double>, duration: Float): Pair<Float, Float>? {
        if (beats.size < MIN_BEATS || duration <= 0f || beats.last() - beats.first() < duration * MIN_SHARE) return null
        val best = (SLOWEST_BPM * STEPS_PER_BPM..FASTEST_BPM * STEPS_PER_BPM).map { fit(beats, SECONDS_PER_MINUTE * STEPS_PER_BPM / it) }
            .maxByOrNull { it.onBeat * minOf(1.0, it.cover) } ?: return null
        if (best.onBeat < MIN_ON_BEAT || best.cover < MIN_COVER) return null
        return (SECONDS_PER_MINUTE / best.period).toFloat() to best.phase.toFloat()
    }

    /** How well a grid of [period] seconds holds [beats]: its phase, the share of beats on it and of its lines heard. */
    private class Fit(val period: Double, val phase: Double, val onBeat: Double, val cover: Double)

    private fun fit(beats: List<Double>, period: Double): Fit {
        // The phase is the circular mean of every beat's place within the period.
        var c = 0.0
        var s = 0.0
        for (b in beats) {
            val a = 2 * PI * (b % period) / period
            c += cos(a)
            s += sin(a)
        }
        val phase = ((atan2(s, c) + 2 * PI) % (2 * PI)) / (2 * PI) * period
        val tolerance = minOf(MAX_OFF, OFF_SHARE * period)
        val on = beats.count { b -> abs(((b - phase + period / 2) % period + period) % period - period / 2) <= tolerance }
        val lines = ((beats.last() - beats.first()) / period).toInt() + 1
        return Fit(period, phase, on / beats.size.toDouble(), on / lines.toDouble())
    }

    private const val MIN_BEATS = 24
    private const val MIN_SHARE = 0.6
    private const val SLOWEST_BPM = 50
    private const val FASTEST_BPM = 200
    private const val STEPS_PER_BPM = 2
    private const val MAX_OFF = 0.07
    private const val OFF_SHARE = 0.12
    private const val MIN_ON_BEAT = 0.55
    private const val MIN_COVER = 0.5
    private const val SECONDS_PER_MINUTE = 60.0
}
