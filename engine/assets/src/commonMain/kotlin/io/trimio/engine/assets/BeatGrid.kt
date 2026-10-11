package io.trimio.engine.assets

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A constant-tempo beat grid in seconds: beat k is at [offset] + k × [period], and every
 * [beatsPerBar]-th beat from [offset] is a downbeat. What cuts, transitions and graphic hits snap
 * to (the Score's `bpm`/`beatOffset`), and what the composer renders on, so picture and music
 * share one clock to the sample.
 */
data class BeatGrid(
    val bpm: Double,
    /** A downbeat's time (any downbeat; the grid extends both ways). */
    val offset: Double = 0.0,
    val beatsPerBar: Int = 4,
) {
    init {
        require(bpm > 0.0) { "bpm must be positive" }
    }

    val period: Double get() = 60.0 / bpm
    val bar: Double get() = period * beatsPerBar

    /** Beats in [from, to). */
    fun beats(from: Double, to: Double): List<Double> = stepped(from, to, period, offset)

    /** Downbeats in [from, to). */
    fun downbeats(from: Double, to: Double): List<Double> = stepped(from, to, bar, offset)

    fun nearestBeat(t: Double): Double = offset + ((t - offset) / period).roundToInt() * period

    fun nearestDownbeat(t: Double): Double = offset + ((t - offset) / bar).roundToInt() * bar

    /** The downbeat at or before [t] (within a microsecond). */
    fun downbeatAtOrBefore(t: Double): Double = offset + floor((t - offset) / bar + EPS) * bar

    /** The downbeat at or after [t]. */
    fun downbeatAtOrAfter(t: Double): Double = offset + ceil((t - offset) / bar - EPS) * bar

    fun isDownbeat(t: Double, tolerance: Double = 0.01): Boolean = abs(nearestDownbeat(t) - t) <= tolerance

    /** Index of the beat within its bar (0 = downbeat) for the beat nearest [t]. */
    fun beatInBar(t: Double): Int = ((t - offset) / period).roundToInt().mod(beatsPerBar)

    /** [t] moved onto the nearest beat when one is within [window] seconds. */
    fun snap(t: Double, window: Double): Double = nearestBeat(t).let { if (abs(it - t) <= window) it else t }

    /** The Score's beat offset: the first beat at or after zero (the Compiler's phase). */
    val scoreOffset: Float get() = (offset - floor(offset / period) * period).toFloat()

    private fun stepped(from: Double, to: Double, step: Double, phase: Double): List<Double> {
        if (to <= from) return emptyList()
        val first = ceil((from - phase) / step - EPS).toLong()
        val out = ArrayList<Double>()
        var k = first
        while (true) {
            val t = phase + k * step
            if (t >= to - EPS) break
            out += t
            k++
        }
        return out
    }

    private companion object {
        const val EPS = 1e-6
    }
}
