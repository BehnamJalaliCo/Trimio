package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.motion.score.Compiler

/**
 * The jury's guard against a loop that goes nowhere. A finding still there after a round in which
 * the judges' own fixes were applied is "stuck"; each stuck finding takes the next remedy, one
 * step further every round it survives, never back:
 *
 * - a graphic at fault: the craft's ladder ([Bench.Ladder]: the other band, another layout, a plain
 *   treatment of the same words), and from [REMOVE_AT] rounds on it is left out (never the call to
 *   action or the end card);
 * - a cut at fault (inside a word, a flash shot, a crowded run): the pause behind it is kept ([RhythmJudge.rejoin]);
 * - dead air: a punch-in at the moment itself ([Bench.punchAtTime]).
 *
 * What none of these can reach (a global finding, a caption that is what was heard) stays in the
 * report as it is: honestly, not hidden.
 */
internal object Remedies {
    private const val REMOVE_AT = 2
    private const val NEAR = 0.4f
    private val KEEP = setOf(Bench.COMMENT, "endcard")
    private val DEAD = Regex("^([0-9.]+)s over")

    /** What identifies a finding from one round to the next: its judge, its words without figures, its moment. */
    fun key(judge: String, f: Finding): String = judge + "|" + f.what.replace(Regex("[0-9.]+"), "#") + "|" + kotlin.math.round(f.at)

    /** [stuck] carried to the next round: findings in both [before] and [after] count one more round; the rest are cleared. */
    fun persisting(before: Jury.Panel, after: Jury.Panel, stuck: Map<String, Int>): Map<String, Int> {
        val was = costly(before).toSet()
        return costly(after).filter { it in was }.associateWith { (stuck[it] ?: 0) + 1 }
    }

    private fun costly(p: Jury.Panel) = p.verdicts.flatMap { v -> v.findings.filter { it.severity.cost > 0f }.map { key(v.judge, it) } }

    /** The judges' joint fix ([base]) with a further step for every stuck finding; null when nothing is stuck or nothing changes. */
    fun beyond(c: Jury.Context, panel: Jury.Panel, stuck: Map<String, Int>, base: Jury.Revision? = null): Jury.Revision? {
        if (stuck.isEmpty()) return null
        var score = base?.score ?: c.score
        var edit = base?.edit ?: c.edit
        val graphics = Bench.graphics(c)
        val held = panel.verdicts.flatMap { v -> v.findings.mapNotNull { f -> stuck[key(v.judge, f)]?.let { f to it } } }
        for ((f, level) in held) {
            when {
                cut(f) -> edit = edit?.let { RhythmJudge.rejoin(it, f) } ?: edit
                "without a visual change" in f.what -> {
                    val length = DEAD.find(f.what)?.groupValues?.get(1)?.toFloatOrNull() ?: 2f
                    score = Bench.punchAtTime(score, c.words, f.at + length / 2f) ?: score
                }
                else -> score = stepped(score, c, graphics, f, level)
            }
        }
        val recut = edit?.takeIf { it.segments != c.edit?.segments }
        return Jury.Revision(score, base?.outro, recut, base?.music).takeIf { score != c.score || recut != null }
    }

    /** The graphic [f] is about, one step further down the ladder (left out from [REMOVE_AT] rounds on). */
    private fun stepped(score: io.trimio.engine.motion.score.Score, c: Jury.Context, graphics: List<Compiler.Placed>, f: Finding, level: Int): io.trimio.engine.motion.score.Score {
        val g = culprit(graphics, f) ?: return score
        val at = Bench.locate(score, c.words, g) ?: return score
        return Bench.mapBeat(score, at) { b ->
            when {
                b.recipe in KEEP -> b
                level >= REMOVE_AT -> null
                else -> Bench.Ladder.next(b, g, moveFirst = true)
            }
        }
    }

    private fun cut(f: Finding) = listOf("cut falls inside", "flashes between", "jump cuts in ten seconds").any { it in f.what }

    /** The graphic a located finding is about: one landing at its moment that the finding names (by device or by its words). */
    private fun culprit(graphics: List<Compiler.Placed>, f: Finding): Compiler.Placed? {
        if (f.at < NEAR / 2) return null
        val quoted = Regex("«([^»]+)»").findAll(f.what).map { it.groupValues[1] }.toList()
        return graphics.filter { kotlin.math.abs(it.at - f.at) <= NEAR }
            .filter { g -> g.recipe in f.what || quoted.any { q -> q.isNotBlank() && (q in g.text || g.text in q && g.text.isNotBlank()) } }
            .minByOrNull { kotlin.math.abs(it.at - f.at) }
    }
}
