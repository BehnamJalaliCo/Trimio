package io.trimio.engine.motion.score

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.abs
import kotlin.math.log10

/**
 * Cut points on the voice itself, not on the recogniser's clock. Word edges from whisper are often
 * 100–200 ms off: a cut placed on them clips an onset or the tail of a final consonant, or leaves a
 * breath of the next word. An editor scrubs to the quiet valley between words; this does the same
 * with a short-window RMS envelope: within [search] of the recognised edge, the point where the
 * [fade] the soundtrack puts on the cut is quietest, never past the neighbouring word, and never
 * inside a word that is still sounding (a consonant's burst after its closure).
 *
 * Every decision is deterministic and relative to the speaker's own level ([speechDb]), so a quiet
 * room and a loud one cut alike. Used by the cold open; ordinary silence cuts can use it too.
 */
class CutPoints(private val voice: PcmAudio, private val fade: Float = FADE) {

    data class Cut(
        /** Seconds on the source clock. */
        val at: Float,
        /** Energy under the fade at the cut, dBFS. */
        val db: Float,
        /** The same relative to the speaker's level (negative: below it). */
        val relative: Float,
        /** Speech had decayed (or not yet begun) [DECAY_DB] under the speaker's level: an inaudible cut. */
        val clean: Boolean,
    )

    private val rate = voice.sampleRate
    private val x = voice.samples

    /**
     * The speaker's level: the median of the speech frames (10 ms frames within 25 dB of the
     * loudest 5%). Room tone and pauses do not pull it down, a shout does not push it up.
     */
    val speechDb: Float = run {
        val hop = (rate * WINDOW).toInt().coerceAtLeast(1)
        val frames = (0 until x.size / hop).map { k -> rms(k * hop, (k + 1) * hop) }.sorted()
        if (frames.isEmpty()) return@run SILENT_DB
        val loud = frames[((frames.size - 1) * LOUD_PERCENTILE).toInt()]
        val speech = frames.filter { it >= loud - SPEECH_RANGE_DB }
        speech[speech.size / 2]
    }

    /** Short-window (10 ms) RMS energy centred on [t], dBFS. */
    fun energyDb(t: Float): Float = db(t - WINDOW / 2f, t + WINDOW / 2f)

    /** RMS energy of the source between [a] and [b] seconds, dBFS. */
    fun db(a: Float, b: Float): Float = rms((a * rate).toInt(), (b * rate).toInt())

    /**
     * Where to start playing so that a word recognised at [start] begins whole: the quietest
     * fade-in window before its onset, after [previousEnd] (the word before, when there is one).
     */
    fun cutIn(start: Float, previousEnd: Float? = null, search: Float = SEARCH): Cut {
        val floor = maxOf(0f, previousEnd?.minus(GUARD) ?: 0f)
        val lo = maxOf(0f, start - search, minOf(floor, start - STEP))
        val hi = maxOf(lo, start + search)
        var at = best(lo, hi, start) { t -> under(t, t + fade) + if (previousEnd != null && t + fade < previousEnd) NEIGHBOUR_DB else 0f }
        // Speech sounding just before the cut, too late to be the word before, is this word's soft
        // onset (a breath, an «س»): the cut goes to the clean valley before it. A cut that is not
        // clean looks a little further back for one.
        val alone = previousEnd == null || at - TAIL > previousEnd + GUARD
        val onset = alone && sounding(at - TAIL, at)
        if (onset || !quiet(under(at, at + fade))) {
            val limit = maxOf(0f, start - 2 * search, previousEnd?.plus(NEAR) ?: 0f)
            cleanValley(at, limit, forward = false, pastSound = onset)?.let { at = it }
        }
        return cut(at, under(at, at + fade))
    }

    /**
     * Where to stop so that a word recognised as ending at [end] ends whole: the quietest
     * fade-out window after its tail, before [nextStart] (the next word, when there is one).
     */
    fun cutOut(end: Float, nextStart: Float? = null, search: Float = SEARCH): Cut {
        val duration = x.size / rate.toFloat()
        val ceiling = minOf(duration, nextStart?.plus(GUARD) ?: duration)
        val hi = minOf(duration, end + search, maxOf(ceiling, end - search + STEP))
        val lo = minOf(hi, maxOf(0f, end - search))
        var at = best(lo, hi, end) { t -> under(t - fade, t) + if (nextStart != null && t - fade > nextStart) NEIGHBOUR_DB else 0f }
        // Sound right after the cut, too early to be the next word, is a consonant's release after
        // its closure: the word was not over, the cut goes past it. A tail still ringing (not
        // clean) is followed until it has decayed — never into the next word.
        val alone = nextStart == null || at + TAIL < nextStart - GUARD
        val release = alone && sounding(at, at + TAIL)
        if (release || !quiet(under(at - fade, at))) {
            val limit = minOf(duration, end + 2 * search, nextStart?.minus(NEAR) ?: duration)
            cleanValley(at, limit, forward = true, pastSound = release)?.let { at = it }
        }
        return cut(at, under(at - fade, at))
    }

    private fun cut(at: Float, db: Float) = Cut(at, db, db - speechDb, quiet(db))

    /** The candidate in [lo, hi] (1 ms steps) with the lowest cost, gently pulled toward [target]. */
    private inline fun best(lo: Float, hi: Float, target: Float, cost: (Float) -> Float): Float {
        var at = lo
        var low = Float.MAX_VALUE
        val steps = ((hi - lo) / STEP).toInt()
        for (k in 0..steps) {
            val t = lo + k * STEP
            val c = cost(t) + SLOPE_DB * abs(t - target)
            if (c < low) { low = c; at = t }
        }
        return at
    }

    private fun quiet(db: Float) = db <= speechDb - DECAY_DB

    /**
     * The first point from [from] toward [limit] whose fade window is clean, or null; with
     * [pastSound], only once a speech-level sound on the way has been passed.
     */
    private fun cleanValley(from: Float, limit: Float, forward: Boolean, pastSound: Boolean): Float? {
        val steps = ((if (forward) limit - from else from - limit) / STEP).toInt()
        // The last speech-level moment met on the way: the fade must lie wholly beyond it.
        var loud: Float? = null
        for (k in 1..steps) {
            val t = if (forward) from + k * STEP else from - k * STEP
            if (pastSound && energyDb(t) >= speechDb - BURST_DB) loud = t
            val beyond = !pastSound || loud?.let { if (forward) t - fade > it + WINDOW / 2f else t + fade < it - WINDOW / 2f } == true
            if (beyond && quiet(if (forward) under(t - fade, t) else under(t, t + fade))) return t
        }
        return null
    }

    /** Speech-level sound anywhere in [a, b] (10 ms frames). */
    private fun sounding(a: Float, b: Float): Boolean {
        var t = a
        while (t < b) {
            if (energyDb(t + WINDOW / 2f) >= speechDb - BURST_DB) return true
            t += WINDOW / 2f
        }
        return false
    }

    /** Energy under a fade: the louder half decides (a fade over a burst is not a quiet fade). */
    private fun under(a: Float, b: Float): Float = maxOf(db(a, (a + b) / 2f), db((a + b) / 2f, b))

    private fun rms(from: Int, to: Int): Float {
        val i = from.coerceIn(0, x.size)
        val j = to.coerceIn(i, x.size)
        if (j <= i) return SILENT_DB
        var sum = 0.0
        for (k in i until j) sum += x[k].toDouble() * x[k]
        return (10.0 * log10(sum / (j - i) + 1e-12)).toFloat().coerceAtLeast(SILENT_DB)
    }

    companion object {
        /** The equal-power fade the soundtrack puts on every cut (seconds). */
        const val FADE = 0.02f
        /** How far from the recognised edge a cut may move (seconds). */
        const val SEARCH = 0.15f
        /** A cut this far below the speaker's level is inaudible (VAD's own speech margin). */
        const val DECAY_DB = 24f
        private const val WINDOW = 0.01f
        private const val STEP = 0.001f
        /** Preference for the recognised edge: 3 dB over the whole ±150 ms. */
        private const val SLOPE_DB = 20f
        /** Cost of fading over the neighbouring word's start. */
        private const val NEIGHBOUR_DB = 12f
        /** Slack around the neighbouring word's recognised edge (it is off too). */
        private const val GUARD = 0.04f
        /** A cut's search never comes closer than this to the neighbouring word's recognised edge. */
        private const val NEAR = 0.02f
        /** How long after an out cut (before an in cut) a consonant can still sound. */
        private const val TAIL = 0.06f
        /** Within this of the speaker's level is speech, not a tail. */
        private const val BURST_DB = 16f
        private const val LOUD_PERCENTILE = 0.95f
        private const val SPEECH_RANGE_DB = 25f
        private const val SILENT_DB = -120f
    }
}
