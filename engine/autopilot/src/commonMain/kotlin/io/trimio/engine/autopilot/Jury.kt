package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.autopilot.judges.BriefJudge
import io.trimio.engine.autopilot.judges.CtaJudge
import io.trimio.engine.autopilot.judges.HookJudge
import io.trimio.engine.autopilot.judges.LayoutJudge
import io.trimio.engine.autopilot.judges.MotionJudge
import io.trimio.engine.autopilot.judges.MusicJudge
import io.trimio.engine.autopilot.judges.RhythmJudge
import io.trimio.engine.autopilot.judges.SoundJudge
import io.trimio.engine.autopilot.judges.SpellingJudge
import io.trimio.engine.autopilot.judges.TypographyJudge
import io.trimio.engine.models.DeviceTier
import io.trimio.engine.motion.TypeSpec
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.CutPoints
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject

/** How much a finding costs its judge's score (out of 10). */
enum class Severity(val cost: Float) {
    /** Worth reporting, costs nothing. */
    Note(0f),
    /** A detail a senior editor would still fix before delivery. */
    Minor(0.5f),
    /** Visible to a viewer: a misspelling, a cut into a word, a graphic over the face. */
    Major(1f),
    /** The piece fails at its job: no hook, no call to action. */
    Critical(3f),
}

/** One thing a judge saw: when, how bad, and what an editor would do about it. */
data class Finding(val at: Float, val severity: Severity, val what: String, val fix: String)

/** A judge's score (0–10) and the findings behind it. */
data class Verdict(val judge: String, val dimension: String, val score: Float, val findings: List<Finding>) {
    val perfect: Boolean get() = score >= PERFECT

    companion object {
        const val PERFECT = 10f

        /** 10 minus what the findings cost, never below 0. */
        fun of(judge: Judge, findings: List<Finding>) =
            Verdict(judge.name, judge.dimension, (PERFECT - findings.sumOf { it.severity.cost.toDouble() }.toFloat()).coerceIn(0f, PERFECT), findings)
    }
}

/**
 * One expert on the panel. Judges are pure and fast (no model, no rendering): they read the
 * compiled edit, the score and the measured sound, and may offer a revised [Score] that fixes
 * what they found — like [Critic.review] does.
 */
interface Judge {
    val name: String
    val dimension: String

    fun score(c: Jury.Context): Verdict

    /** The score with this judge's findings fixed, when they can be fixed in the score; null otherwise. */
    fun revise(c: Jury.Context, verdict: Verdict): Score? = null

    /**
     * Seconds of end hold (picture and music after the last word, [EditPlan.withOutro]) this judge
     * needs, when its findings are fixed by the edit rather than the score; null otherwise.
     */
    fun outro(c: Jury.Context, verdict: Verdict): Float? = null
}

/** Lines a text takes when laid out at [type] inside [maxWidth] (the real text engine, when the caller has one). */
fun interface LineMeasure {
    fun lines(text: String, type: TypeSpec, maxWidth: Float, rtl: Boolean): Int
}

/**
 * The jury (هیئت داوران): ten judges — hook, rhythm, motion, typography, spelling, layout, sound,
 * music, brief and the call to action — score every automatic edit out of 10. An edit is
 * delivered when every judge gives 10; until then the jury revises it with the judges' own fixes
 * and recompiles ([loop]), and when fixes run out it tries another variation seed, keeping the
 * best panel. Compute is bounded by [budget] so the loop stays cheap on a phone.
 */
class Jury(val judges: List<Judge> = standard(), val budget: Budget = Budget()) {

    /** At most [rounds] revisions per variation and [variations] extra seeds. */
    data class Budget(val rounds: Int = 3, val variations: Int = 3) {
        companion object {
            /**
             * What a device can afford: Standard and High phones 2 rounds and 1 variation, Ultra
             * and Max 3 and 2; desktop, server and web (no tier) 3 and 3.
             */
            fun forDevice(tier: DeviceTier?): Budget = when (tier) {
                DeviceTier.Standard, DeviceTier.High -> Budget(rounds = 2, variations = 1)
                DeviceTier.Ultra, DeviceTier.Max -> Budget(rounds = 3, variations = 2)
                null -> Budget(rounds = 3, variations = 3)
            }
        }
    }

    /** What the judges' fixes change: the score, and the end hold the edit should carry (null: as it is). */
    data class Revision(val score: Score, val outro: Float? = null)

    /** Everything the judges look at. Words and [lines] share indices; [words] are on the output clock. */
    data class Context(
        val compiled: Compiler.Output,
        val score: Score,
        val words: List<Word>,
        val lines: List<Lines.Line>,
        val understanding: Understanding,
        val prompt: String,
        val music: MusicMood? = null,
        val edit: EditPlan? = null,
        /** The transcript on the source clock: where cuts fall relative to words. */
        val source: List<Word> = emptyList(),
        /** Words heard in the cold open, on the output clock. */
        val prelude: List<Word> = emptyList(),
        val subject: Subject? = null,
        val footage: Boolean = true,
        /** The mastered mix, when it exists; judges fall back to the plan before mastering. */
        val sound: Soundtrack.Result? = null,
        val levels: Soundtrack.Levels = Soundtrack.Levels(),
        /** What the eyes saw ([VisionCritic]), when they looked. */
        val looks: List<VisionCritic.Look> = emptyList(),
        val lexicon: PersianSpelling? = null,
        val measure: LineMeasure? = null,
        /** The voice on the source clock: cuts are judged on the waveform when it is here, else on word timings. */
        val voice: PcmAudio? = null,
        /** Whether a Latin word is a brand the brand library has a mark for. */
        val brand: (String) -> Boolean = { false },
    ) {
        val duration: Float get() = compiled.composition.duration

        /** The voice's own levels, for judging where cuts fall (built once per context). */
        val cuts: CutPoints? by lazy { voice?.let { CutPoints(it) } }
    }

    /** The panel's result: per-judge verdicts, the lowest score (what gates delivery) and the mean. */
    data class Panel(val verdicts: List<Verdict>) {
        val min: Float get() = verdicts.minOfOrNull { it.score } ?: Verdict.PERFECT
        val mean: Float get() = if (verdicts.isEmpty()) Verdict.PERFECT else verdicts.sumOf { it.score.toDouble() }.toFloat() / verdicts.size
        val unanimous: Boolean get() = verdicts.all { it.perfect }

        fun of(judge: String): Verdict? = verdicts.firstOrNull { it.judge == judge }

        /** Higher minimum first, then higher mean. */
        fun betterThan(other: Panel?): Boolean = other == null || min > other.min + EPS || (kotlin.math.abs(min - other.min) <= EPS && mean > other.mean + EPS)

        fun summary(): String = "min ${fmt(min)} mean ${fmt(mean)} — " + verdicts.joinToString(" ") { "${it.judge} ${fmt(it.score)}" }

        /** One line per finding, for the production report. */
        fun findings(): List<String> = verdicts.flatMap { v ->
            v.findings.map { f -> "  ${v.judge} ${f.severity.name.lowercase()} at ${fmt(f.at)}s: ${f.what} → ${f.fix}" }
        }
    }

    /** What [loop] delivers: the best candidate, its panel and how much of the budget it took. */
    data class Outcome<T>(val best: T, val panel: Panel, val rounds: Int, val variations: Int)

    fun deliberate(c: Context): Panel = Panel(
        judges.map { j ->
            // A judge that cannot score abstains (it never blocks delivery on its own bug).
            runCatching { j.score(c) }.getOrElse { e -> Verdict(j.name, j.dimension, Verdict.PERFECT, listOf(Finding(0f, Severity.Note, "abstained: ${e.message}", "-"))) }
        },
    )

    /** Every failing judge's fix applied in turn (lowest score first); null when nobody can fix anything. */
    fun fixes(c: Context, panel: Panel): Revision? {
        var score = c.score
        var outro: Float? = null
        val held = c.edit?.outro?.length ?: 0f
        for (v in panel.verdicts.filter { !it.perfect }.sortedBy { it.score }) {
            val judge = judges.firstOrNull { it.name == v.judge } ?: continue
            score = runCatching { judge.revise(c.copy(score = score), v) }.getOrNull() ?: score
            runCatching { judge.outro(c, v) }.getOrNull()?.takeIf { it > held + EPS }?.let { outro = maxOf(outro ?: 0f, it) }
        }
        return Revision(score, outro).takeIf { score != c.score || outro != null }
    }

    /**
     * Score [first]; while a judge is below 10, apply the judges' fixes and [revise] (recompile)
     * up to [Budget.rounds] times; a revision that does not improve the panel is dropped. Then,
     * if the panel is still not unanimous, [vary] k (1, 2, …) gives another variation to try, up
     * to [Budget.variations]. The best panel wins. [context] turns a candidate into what the
     * judges read.
     */
    suspend fun <T> loop(
        first: T,
        context: suspend (T) -> Context,
        revise: suspend (T, Revision) -> T,
        vary: suspend (Int) -> T?,
        log: (String) -> Unit = {},
    ): Outcome<T> {
        var best = first
        var bestPanel: Panel? = null
        var rounds = 0
        var variation = 0
        var candidate: T? = first
        while (candidate != null) {
            val (current, panel, spent) = polish(candidate, label(variation), context, revise, log)
            rounds += spent
            if (panel.betterThan(bestPanel)) {
                best = current
                bestPanel = panel
            }
            if (panel.unanimous || variation >= budget.variations) break
            variation++
            candidate = vary(variation)
        }
        return Outcome(best, bestPanel ?: deliberate(context(best)), rounds, variation)
    }

    /** One candidate through up to [Budget.rounds] revisions; a revision that does not improve the panel ends it. */
    private suspend fun <T> polish(
        first: T,
        name: String,
        context: suspend (T) -> Context,
        revise: suspend (T, Revision) -> T,
        log: (String) -> Unit,
    ): Triple<T, Panel, Int> {
        var current = first
        var ctx = context(current)
        var panel = deliberate(ctx)
        log("jury $name round 0: ${panel.summary()}")
        var round = 0
        var improving = true
        while (improving && !panel.unanimous && round < budget.rounds) {
            val fixed = fixes(ctx, panel) ?: break
            round++
            val next = revise(current, fixed)
            val nextCtx = context(next)
            val nextPanel = deliberate(nextCtx)
            log("jury $name round $round: ${nextPanel.summary()}")
            improving = nextPanel.betterThan(panel)
            if (improving) {
                current = next
                ctx = nextCtx
                panel = nextPanel
            }
        }
        return Triple(current, panel, round)
    }

    companion object {
        private const val EPS = 1e-3f

        /** The ten judges, in the order they report. */
        fun standard(): List<Judge> = listOf(
            HookJudge(), RhythmJudge(), MotionJudge(), TypographyJudge(), SpellingJudge(),
            LayoutJudge(), SoundJudge(), MusicJudge(), BriefJudge(), CtaJudge(),
        )

        /** The desktop panel (the most generous budget); phones take [forDevice]. */
        val Default: Jury = Jury()

        fun forDevice(tier: DeviceTier?): Jury = Jury(budget = Budget.forDevice(tier))

        private fun label(variation: Int) = if (variation == 0) "first cut" else "variation $variation"

        fun fmt(v: Float): String {
            val r = kotlin.math.round(v * 10f) / 10f
            return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }
    }
}
