package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.CutPoints
import io.trimio.engine.motion.score.EditPlan

/**
 * The silence cut made humane and clean, measured on the levelled voice ([VoiceLevel]):
 *
 * - every cut lands where the voice is quiet on both sides — the fade before the cut-out and after
 *   the cut-in are [CLEAN_DB] under the speaker's level, found by sliding the cut inside its pause
 *   (recognised word edges overlap and are 100–200 ms off); a pause with no quiet room is kept;
 * - no shot shorter than [MIN_SHOT] between two cuts (a flash): the shorter pause beside it stays;
 * - at most [MAX_PER_10S] jump cuts in any ten seconds: the shortest pauses of a crowded stretch stay.
 *
 * The same test of a cut ([inWord]) is the rhythm judge's, so what is delivered is what is judged.
 */
internal object JumpCuts {

    /** Within this of the speaker's level the voice is still a word (the judges' and CutPoints' burst margin). */
    const val LOUD_DB = 16f

    /** A cut is placed only where the fade is this far under the speaker's level. */
    private const val CLEAN_DB = 21f
    private const val SCAN = 0.005f
    private const val MIN_GAP = 0.06f
    const val MIN_SHOT = 0.45f
    const val MAX_PER_10S = 6
    private const val WINDOW = 10f

    /** Whether a cut at source time [t] sounds inside a word: the fade on its playing side is near the speaker's level. */
    fun inWord(cuts: CutPoints, t: Float, entering: Boolean, margin: Float = LOUD_DB): Boolean {
        val db = if (entering) cuts.db(t, t + CutPoints.FADE) else cuts.db(t - CutPoints.FADE, t)
        return db > cuts.speechDb - margin
    }

    /** [edit]'s body cut clean, without flashes, and with room to breathe (its cold open and end hold kept). */
    fun clean(edit: EditPlan, cuts: CutPoints): EditPlan {
        if (edit.segments.size < 2) return edit
        var spans = quiet(edit.segments.map { it.sourceStart to it.sourceEnd }, cuts)
        spans = noFlashes(spans)
        spans = breathing(spans)
        return rebuilt(edit, spans)
    }

    /** [edit] with the pause after body segment [i] kept (segments i and i+1 played as one). */
    fun joined(edit: EditPlan, i: Int): EditPlan {
        if (i !in 0 until edit.segments.lastIndex) return edit
        val spans = edit.segments.map { it.sourceStart to it.sourceEnd }.toMutableList()
        spans[i] = spans[i].first to spans[i + 1].second
        spans.removeAt(i + 1)
        return rebuilt(edit, spans)
    }

    /** Every cut moved into the quiet of its pause; a pause with no quiet room for both fades is kept whole. */
    private fun quiet(spans: List<Pair<Float, Float>>, cuts: CutPoints): List<Pair<Float, Float>> {
        val out = mutableListOf(spans.first())
        for (k in 1 until spans.size) {
            val (prevStart, prevEnd) = out.last()
            val (start, end) = spans[k]
            val stop = firstQuiet(cuts, prevEnd, start, out = true)
            val resume = stop?.let { s -> lastQuiet(cuts, start, s + MIN_GAP) }
            if (stop != null && resume != null) {
                out[out.lastIndex] = prevStart to stop
                out += resume to end
            } else {
                out[out.lastIndex] = prevStart to end
            }
        }
        return out
    }

    /** The first point from [from] toward [limit] where a cut-out's fade is clean. */
    private fun firstQuiet(cuts: CutPoints, from: Float, limit: Float, out: Boolean): Float? {
        var t = from
        while (t <= limit - MIN_GAP) {
            if (!inWord(cuts, t, entering = !out, margin = CLEAN_DB)) return t
            t += SCAN
        }
        return null
    }

    /** The last point from [from] back toward [floor] where a cut-in's fade is clean. */
    private fun lastQuiet(cuts: CutPoints, from: Float, floor: Float): Float? {
        var t = from
        while (t >= floor) {
            if (!inWord(cuts, t, entering = true, margin = CLEAN_DB)) return t
            t -= SCAN
        }
        return null
    }

    /** A shot shorter than [MIN_SHOT] between two cuts is joined to the neighbour across the shorter pause. */
    private fun noFlashes(spans: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val s = spans.toMutableList()
        var k = 1
        while (k < s.lastIndex) {
            if (s[k].second - s[k].first >= MIN_SHOT) { k++; continue }
            val before = s[k].first - s[k - 1].second
            val after = s[k + 1].first - s[k].second
            if (before <= after) {
                s[k - 1] = s[k - 1].first to s[k].second
                s.removeAt(k)
            } else {
                s[k] = s[k].first to s[k + 1].second
                s.removeAt(k + 1)
            }
            k = maxOf(1, k - 1)
        }
        return s
    }

    /** No ten seconds with more than [MAX_PER_10S] cuts: the shortest pauses of a crowded stretch are kept. */
    private fun breathing(spans: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val s = spans.toMutableList()
        while (true) {
            val starts = outStarts(s)
            val from = (1 until s.size).firstOrNull { a -> (a until s.size).count { starts[it] < starts[a] + WINDOW } > MAX_PER_10S } ?: break
            val crowd = (from until s.size).filter { starts[it] < starts[from] + WINDOW }
            // The cut before shot k removes the pause between k-1 and k; the shortest one goes back in.
            val k = crowd.minBy { s[it].first - s[it - 1].second }
            s[k - 1] = s[k - 1].first to s[k].second
            s.removeAt(k)
        }
        return s
    }

    private fun outStarts(spans: List<Pair<Float, Float>>): List<Float> {
        var out = 0f
        return spans.map { (a, b) -> out.also { out += b - a } }
    }

    private fun rebuilt(edit: EditPlan, spans: List<Pair<Float, Float>>): EditPlan {
        var out = edit.segments.first().outStart
        val segments = spans.map { (a, b) -> EditPlan.Segment(a, b, out).also { out += b - a } }
        return EditPlan(segments, edit.opening, edit.outro)
    }
}
