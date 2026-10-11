package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.Arranger
import io.trimio.engine.assets.MusicArrangement
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Score

/**
 * The music cut to the edit: where the drop goes (the cold open's restart, else the payoff), where
 * the breakdown starts (under the call to action), and the grid that results — the one the
 * Compiler snaps graphic hits to, so picture, cuts and music share a clock.
 */
object MusicEdit {
    /** Roles that pay a piece off when no line is a punchline. */
    private val PAYOFF_ROLES = setOf("punchline", "number", "proof", "benefit")
    private const val MIN_DROP_S = 1.2

    /** [words] on the output clock, sharing indices with [lines]; null without music. */
    fun arrange(mood: MusicMood?, edit: EditPlan, words: List<Word>, lines: List<Lines.Line>, u: Understanding): MusicArrangement? {
        mood ?: return null
        val start = edit.preludeLength.toDouble()
        val end = edit.duration.toDouble()
        val drop = if (start > 0.0) start else payoff(words, lines, u, end)
        return Arranger.fit(mood, start, end, drop = drop, breakdown = cta(words, lines, u))
    }

    /**
     * Where the payoff lands: the punchline (else the strongest result line) in the first two
     * thirds, else the line after the hook — the moment the body kicks in. Null when the piece is
     * too short for an intro.
     */
    fun payoff(words: List<Word>, lines: List<Lines.Line>, u: Understanding, end: Double): Double? {
        fun startOf(k: Int) = lines.getOrNull(k)?.let { words.getOrNull(it.first) }?.range?.startMs?.div(1000.0)
        val reads = u.lines
        val candidates = lines.indices.filter { k -> k > 0 && (startOf(k) ?: end) < end * 2 / 3 }
        val punch = candidates.firstOrNull { reads.getOrNull(it)?.role == "punchline" }
            ?: candidates.filter { reads.getOrNull(it)?.role in PAYOFF_ROLES }.maxByOrNull { reads[it].energy }
        val k = punch ?: ((u.hook?.line ?: 0) + 1)
        return startOf(k)?.takeIf { it >= MIN_DROP_S && it < end * 2 / 3 }
    }

    /** Where the call to action starts: the first line read as one. */
    fun cta(words: List<Word>, lines: List<Lines.Line>, u: Understanding): Double? {
        val k = u.lines.indexOfFirst { it.role == "cta" }.takeIf { it >= 0 } ?: return null
        return lines.getOrNull(k)?.let { words.getOrNull(it.first) }?.range?.startMs?.div(1000.0)
    }

    /** [score] on [arrangement]'s grid (tempo and phase), so the Compiler's snapping lands hits on the music's beats. */
    fun gridded(score: Score, arrangement: MusicArrangement?): Score =
        arrangement?.let { score.copy(bpm = it.grid.bpm.toFloat(), beatOffset = it.grid.scoreOffset) } ?: score
}
