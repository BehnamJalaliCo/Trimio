package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Words

/**
 * The first seconds decide whether anyone watches the rest. Something strong is on screen by
 * [hookBy] (1.5 s; a graphic only after [lateBy] is a failed hook); when the brief asks for the
 * payoff first, the opening shows the figure (or the cold open plays it); the first frame is
 * never an empty stage, and a graphic has landed by [thumbnailBy] so the first frame works as a
 * thumbnail. A cold open is cut on word boundaries — the owner's 9/10 for an earlier version
 * was exactly an imprecise cold-open cut — and ends on a word that means something.
 */
class HookJudge(
    private val hookBy: Float = 1.5f,
    private val lateBy: Float = 2.5f,
    private val thumbnailBy: Float = 0.8f,
) : Judge {
    override val name = "hook"
    override val dimension = "first seconds"

    override fun score(c: Jury.Context): Verdict {
        val f = mutableListOf<Finding>()
        val graphics = Bench.graphics(c).sortedBy { it.at }
        val first = graphics.firstOrNull()
        when {
            first == null || first.at > lateBy -> f += Bench.finding(0f, Severity.Critical, "nothing but captions in the first ${lateBy}s", "open with the payoff as a title or figure by 1.0 s")
            first.at > hookBy -> f += Bench.finding(first.at, Severity.Major, "the first graphic lands at ${Bench.r1(first.at)}s", "land the hook by $hookBy s")
            first.recipe in SOFT -> f += Bench.finding(first.at, Severity.Minor, "the hook is a soft ${first.recipe}", "a slam, a figure or a card opens harder")
        }
        if (first != null && asked(c.prompt) && !payoff(c, first)) {
            f += Bench.finding(first.at, Severity.Major, "the brief asks for the payoff first; the opening is ${first.recipe} «${first.text}»", "open on the payoff's figure or play it as a cold open")
        }
        val stage = !c.footage || !Bench.overFootage(c, 0)
        if (stage && c.compiled.beats.none { it.at <= EMPTY_FRAME }) {
            f += Bench.finding(0f, Severity.Major, "the first frame is an empty stage", "land a graphic on the first frame or open on the speaker")
        }
        if (first != null && first.at <= hookBy && graphics.none { it.at <= thumbnailBy }) {
            f += Bench.finding(0f, Severity.Minor, "the first frame is only the speaker (first graphic at ${Bench.r1(first.at)}s)", "land the hook by $thumbnailBy s so the first frame is a thumbnail")
        }
        f += coldOpen(c, graphics)
        return Verdict.of(this, f)
    }

    /** A cold open cut on word boundaries (on the voice when it is here), ending on a content word, with its figure on screen. */
    private fun coldOpen(c: Jury.Context, graphics: List<Compiler.Placed>): List<Finding> {
        // What the cold open plays: everything played before the body's own segments.
        val pieces = c.edit?.let { e -> e.played.dropLast(e.segments.size) }.orEmpty()
        if (pieces.isEmpty()) return emptyList()
        val length = c.edit?.preludeLength ?: 0f
        val out = mutableListOf<Finding>()
        for (p in pieces) {
            Bench.cutInto(c, p.sourceStart, entering = true)?.let { out += Bench.finding(p.outStart, Severity.Major, "the cold open cuts in on «$it»", "start on the word's onset") }
            Bench.cutInto(c, p.sourceEnd, entering = false)?.let { out += Bench.finding(p.outEnd, Severity.Major, "the cold open cuts out on «$it»", "end after the word's tail") }
        }
        fun mid(w: io.trimio.core.model.transcript.Word) = (w.range.startMs + w.range.endMs) / 2000f
        val inside = c.source.filter { w -> pieces.any { mid(w) in it.sourceStart..it.sourceEnd } }
        if (inside.isEmpty()) return out + Bench.finding(0f, Severity.Major, "the cold open holds no whole word", "cut the cold open on the payoff's words")
        if (!Words.isContent(inside.last().text)) {
            out += Bench.finding(length, Severity.Minor, "the cold open ends on «${inside.last().text}»", "end on the word that names the payoff")
        }
        val lead = inside.first().range.startMs / 1000f - pieces.first().sourceStart
        val tail = pieces.last().sourceEnd - inside.last().range.endMs / 1000f
        if (lead > AIR || tail > AIR) {
            out += Bench.finding(0f, Severity.Minor, "the cold open carries dead air around its words", "trim the cold open to its words plus a breath")
        }
        if (graphics.none { it.at < length }) {
            out += Bench.finding(0f, Severity.Minor, "the cold open plays without its figure", "show the payoff's figure while it is said")
        }
        return out.distinctBy { it.what }
    }

    /** The opening shows the payoff: a figure, a number, the hook's own words, or a cold open. */
    private fun payoff(c: Jury.Context, first: Compiler.Placed): Boolean {
        if ((c.edit?.preludeLength ?: 0f) > 0f || first.recipe in Bench.DATA) return true
        val shown = first.text.split(' ').map(NumberWords::normalize)
        if (NumberWords.findAll(first.text.split(' ')).isNotEmpty()) return true
        val title = c.understanding.hook?.title.orEmpty().split(' ').map(NumberWords::normalize).filter { it.isNotEmpty() }
        return title.isNotEmpty() && title.any { it in shown }
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        val graphics = Bench.graphics(c)
        val missing = graphics.none { it.at <= hookBy } || verdict.findings.any { "empty stage" in it.what }
        if (!missing || c.score.scenes.isEmpty()) return null
        val text = phrase(c) ?: return null
        return Bench.addBeat(c.score, 0, BeatScore(recipe = "slam", text = text, time = HOOK_AT, hold = HOOK_HOLD, energy = 0.9f, place = "top"))
    }

    /** The hook's words: the model's hook title when it is said, else the first line's most stressed phrase. */
    private fun phrase(c: Jury.Context): String? {
        val said = c.words.map { NumberWords.normalize(it.text).trimEnd('.', '،', ',') }.toSet()
        c.understanding.hook?.title?.takeIf { t -> t.isNotBlank() && t.split(' ').all { NumberWords.normalize(it) in said } }?.let { return it }
        val line = c.lines.firstOrNull() ?: return null
        val peak = line.range.filter { Words.isContent(c.words[it].text) }.maxByOrNull { Words.weight(c.words[it]) } ?: return null
        return (peak..minOf(line.last, peak + 1)).joinToString(" ") { c.words[it].text.trimEnd('.', '،', ',') }
    }

    companion object {
        private val SOFT = setOf("blur-in", "type-on", "lower-third", "icon")
        private const val EMPTY_FRAME = 0.25f
        private const val AIR = 0.35f
        private const val HOOK_AT = 0.25f
        private const val HOOK_HOLD = 2.2f

        /** Words with which a brief asks for the payoff up front. */
        private val ASKED = listOf(
            "هوک", "hook", "ثانیه اول", "ثانیه ی اول", "ثانیهی اول", "اول ویدیو", "اول ویدئو", "کلد اوپن", "first second", "cold open", "open with", "نه مقدمه",
        )

        fun asked(prompt: String): Boolean {
            val p = prompt.lowercase().replace("ٔ", "").replace("‌", " ")
            return ASKED.any { it in p }
        }
    }
}
