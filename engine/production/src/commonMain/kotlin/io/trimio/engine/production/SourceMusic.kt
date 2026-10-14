package io.trimio.engine.production

import kotlin.math.sqrt

/**
 * Whether a video carries its own music under the voice (phase 9.3): Beat This! hears a steady beat — 50 to 200 bpm,
 * intervals within [MAX_SPREAD] of each other — across most of it. Speech alone has no such pulse. Then the edit adds
 * no music of its own (two musics clash) and its hits land on the video's own beat.
 */
internal object SourceMusic {
    /** (bpm, offset of the first beat in seconds) of the video's own music, or null when there is none. */
    fun tempo(beats: List<Double>, duration: Float): Pair<Float, Float>? {
        if (beats.size < MIN_BEATS || duration <= 0f) return null
        val gaps = beats.zipWithNext { a, b -> b - a }
        val mean = gaps.average()
        if (mean !in FASTEST..SLOWEST) return null
        val spread = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size) / mean
        if (spread > MAX_SPREAD || beats.last() - beats.first() < duration * MIN_SHARE) return null
        return (SECONDS_PER_MINUTE / mean).toFloat() to (beats.first() % mean).toFloat()
    }

    private const val MIN_BEATS = 24
    private const val FASTEST = 0.3
    private const val SLOWEST = 1.2
    private const val MAX_SPREAD = 0.08
    private const val MIN_SHARE = 0.6
    private const val SECONDS_PER_MINUTE = 60.0
}
