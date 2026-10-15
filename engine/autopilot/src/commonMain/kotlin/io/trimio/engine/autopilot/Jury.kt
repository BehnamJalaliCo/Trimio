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
import io.trimio.engine.motion.score.Geometry
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import kotlinx.serialization.Serializable

/** How much a finding costs its judge's score (out of 10). */
@Serializable
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
@Serializable
data class Finding(val at: Float, val severity: Severity, val what: String, val fix: String)

/** A judge's score (0–10) and the findings behind it. */
@Serializable
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

    /** The cut with this judge's findings fixed, when they are fixed by cutting differently (a pause kept); null otherwise. */
    fun recut(c: Jury.Context, verdict: Verdict): EditPlan? = null

    /** Another music bed, when this judge's findings are fixed by changing the music itself (its mood); null otherwise. */
    fun remood(c: Jury.Context, verdict: Verdict): io.trimio.engine.assets.MusicMood? = null
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
    data class Budget(val rounds: Int = 6, val variations: Int = 3) {
        companion object {
            /**
             * What a device can afford: Standard and High phones 3 rounds and 1 variation, Ultra
             * and Max 4 and 2; desktop, server and web (no tier) 6 and 3. A round tries up to four
             * revisions (each a compile and a sitting, no model), so a fix that needs several steps
             * — another layout, the other band, a plain form — gets them.
             */
            fun forDevice(tier: DeviceTier?): Budget = when (tier) {
                DeviceTier.Standard, DeviceTier.High -> Budget(rounds = 3, variations = 1)
                DeviceTier.Ultra, DeviceTier.Max -> Budget(rounds = 4, variations = 2)
                null -> Budget(rounds = 6, variations = 3)
            }
        }
    }

    /** What the judges' fixes change: the score, the end hold the edit should carry and the cut (null: as they are). */
    data class Revision(val score: Score, val outro: Float? = null, val edit: EditPlan? = null, val music: io.trimio.engine.assets.MusicMood? = null) {
        /** What makes two revisions the same edit. */
        internal val key: Any get() = listOf(score, outro, edit?.segments, music)
    }

    /** Everything the judges look at. Words and [lines] share indices; [words] are on the output clock. */
    data class Context(
        val compiled: Compiler.Output,
        val score: Score,
        val words: List<Word>,
        val lines: List<Lines.Line>,
        val understanding: Understanding,
        val prompt: String,
        val music: MusicMood? = null,
        /** The cue composed to the edit (sections, drop, ending, grid), when there is music. */
        val arrangement: io.trimio.engine.assets.MusicArrangement? = null,
        val edit: EditPlan? = null,
        /** The transcript on the source clock: where cuts fall relative to words. */
        val source: List<Word> = emptyList(),
        /** Words heard in the cold open, on the output clock. */
        val prelude: List<Word> = emptyList(),
        val subject: Subject? = null,
        val footage: Boolean = true,
        /** The speaker stands on full-frame stages cut out of the picture (the person matte): a card above their head is placed, not stranded. */
        val cutout: Boolean = false,
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
        /** Media ids of the pictures and clips the creator attached: each must be in the edit. */
        val assets: List<String> = emptyList(),
        /** Draws each graphic alone to see where it really is, frame by frame ([Geometry]); null judges the slots. */
        val geometry: Geometry? = null,
        /**
         * How well each picture the edit shows fits its moment, by picture id ("noto:1f680",
         * "fluent:rocket"), 0..1 ([VisualPicker.Result.relevance]); empty when nothing was picked.
         */
        val pictures: Map<String, Float> = emptyMap(),
    ) {
        val duration: Float get() = compiled.composition.duration

        /** Every placed beat's ink over its life, when [geometry] can draw (shared by the judges of one panel). */
        val tracks: List<Geometry.Track>? get() = geometry?.let { g -> runCatching { g.traced(compiled) }.getOrNull() }

        /** The voice's own levels (levelled, as the cuts were placed on it), for judging where cuts fall (built once per context). */
        val cuts: CutPoints? by lazy { voice?.let { CutPoints(VoiceLevel.levelled(it)) } }
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

        /**
         * What is not 10, said plainly: one line per judge below 10 (its name in Persian, its score
         * and its worst finding), first; then one indented line per finding, for the report.
         */
        fun findings(): List<String> = shortfalls() + verdicts.flatMap { v ->
            v.findings.map { f -> "  ${v.judge} ${f.severity.name.lowercase()} at ${fmt(f.at)}s: ${f.what} → ${f.fix}" }
        }

        /** What is still wrong, briefly, for the report of a round: the findings that cost points, at most [MAX_DETAILS]. */
        fun details(): List<String> = verdicts.flatMap { v -> v.findings.filter { it.severity.cost > 0f }.map { "    ${v.judge}: ${it.what} at ${fmt(it.at)}s" } }.take(MAX_DETAILS)

        /** One line per judge below 10: «چیدمان ۸ از ۱۰ — …», with its worst finding. */
        fun shortfalls(): List<String> = verdicts.filter { !it.perfect }.sortedBy { it.score }.map { v ->
            val worst = v.findings.maxByOrNull { it.severity.cost }
            "${PERSIAN_NAMES[v.judge] ?: v.judge} ${io.trimio.core.model.text.Numerals.toPersian(fmt(v.score))} از ۱۰ — ${worst?.what ?: v.dimension}"
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
        var edit: EditPlan? = null
        var music: io.trimio.engine.assets.MusicMood? = null
        val held = c.edit?.outro?.length ?: 0f
        // A judge whose fix throws is skipped (its finding stays for the report): the jury never stops the edit.
        for (v in failing(panel)) {
            val judge = judges.firstOrNull { it.name == v.judge } ?: continue
            score = runCatching { judge.revise(c.copy(score = score, edit = edit ?: c.edit), v) }.getOrNull() ?: score
            runCatching { judge.recut(c.copy(edit = edit ?: c.edit), v) }.getOrNull()?.let { edit = it }
            runCatching { judge.outro(c, v) }.getOrNull()?.takeIf { it > held + EPS }?.let { outro = maxOf(outro ?: 0f, it) }
            runCatching { judge.remood(c, v) }.getOrNull()?.takeIf { it != c.music }?.let { music = it }
        }
        return Revision(score, outro, edit, music).takeIf { score != c.score || outro != null || edit != null || music != null }
    }

    /** The judges below 10, lowest score first (scores are plain numbers: a NaN counts as 0). */
    private fun failing(panel: Panel): List<Verdict> =
        panel.verdicts.filter { !it.perfect }.sortedBy { if (it.score.isNaN()) 0f else it.score }

    /**
     * What a round may try, best bet first: every failing judge's fix together ([fixes]), then
     * each failing judge's own fix alone, lowest score first — one judge's fix can undo another's
     * (a graphic moved off the face onto a caption), and alone it may still be a step forward.
     */
    fun options(c: Context, panel: Panel): List<Pair<String, Revision>> {
        val together = fixes(c, panel)?.let { listOf("all fixes" to it) }.orEmpty()
        val held = c.edit?.outro?.length ?: 0f
        val alone = failing(panel).mapNotNull { v ->
            val judge = judges.firstOrNull { it.name == v.judge } ?: return@mapNotNull null
            val score = runCatching { judge.revise(c, v) }.getOrNull() ?: c.score
            val outro = runCatching { judge.outro(c, v) }.getOrNull()?.takeIf { it > held + EPS }
            val edit = runCatching { judge.recut(c, v) }.getOrNull()
            val music = runCatching { judge.remood(c, v) }.getOrNull()?.takeIf { it != c.music }
            (v.judge to Revision(score, outro, edit, music)).takeIf { score != c.score || outro != null || edit != null || music != null }
        }
        // A lone fix equal to the joint one (only one judge could fix anything) is not tried twice.
        return (together + alone.filter { a -> together.none { it.second == a.second } }).take(MAX_TRIES)
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
            // A variation that cannot be judged (it threw) is reported and skipped: the jury never stops the edit.
            val polished = runCatching { polish(candidate, label(variation), context, revise, log) }
                .onFailure { log("jury ${label(variation)}: skipped (${it.message ?: it::class.simpleName})") }.getOrNull()
            if (polished != null) {
                val (current, panel, spent) = polished
                rounds += spent
                if (panel.betterThan(bestPanel)) {
                    best = current
                    bestPanel = panel
                }
            }
            if (polished?.second?.unanimous == true || variation >= budget.variations) break
            variation++
            candidate = runCatching { vary(variation) }.onFailure { log("jury variation $variation: not made (${it.message ?: it::class.simpleName})") }.getOrNull()
        }
        return Outcome(best, bestPanel ?: deliberate(context(best)), rounds, variation)
    }

    /**
     * One candidate through up to [Budget.rounds] revisions. Each round tries the [options] in
     * turn and keeps the first that improves the panel. When none does, one that scores the same
     * may still be taken ([MAX_SIDEWAYS] times, never back to an edit already tried): a fix that
     * climbs a ladder (the other band, then a stage of its own) can need a level step before the
     * one that pays. The best panel seen wins; a round with no step ends it.
     */
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
        panel.details().forEach(log)
        var best = current to panel
        val tried = mutableSetOf(Revision(ctx.score, null, ctx.edit).key)
        // Findings that survived the rounds so far, and for how many: each takes a further remedy ([Remedies]).
        var stuck = emptyMap<String, Int>()
        var sideways = 0
        var round = 0
        while (!panel.unanimous && round < budget.rounds) {
            // Stuck findings first: their own fixes already failed them; the remedies ride on the joint fix.
            val further = runCatching { io.trimio.engine.autopilot.judges.Remedies.beyond(ctx, panel, stuck, fixes(ctx, panel)) }.getOrNull()
                ?.let { listOf("further remedies" to it) }.orEmpty()
            val options = (further + options(ctx, panel)).filter { it.second.key !in tried }
            if (options.isNotEmpty()) round++
            val step = if (options.isEmpty()) {
                null
            } else {
                nextStep(current, panel, options, context, revise, sideways < MAX_SIDEWAYS) { what, p -> log("jury $name round $round ($what): ${p.summary()}") }
            }
            if (step == null) break
            if (!step.better) sideways++
            stuck = io.trimio.engine.autopilot.judges.Remedies.persisting(panel, step.panel, stuck)
            current = step.next
            ctx = step.context
            panel = step.panel
            tried += Revision(ctx.score, null, ctx.edit).key
            if (panel.betterThan(best.second)) best = current to panel
            panel.details().forEach(log)
        }
        return Triple(best.first, best.second, round)
    }

    private class Step<T>(val next: T, val context: Context, val panel: Panel, val better: Boolean)

    /**
     * The first of [options] whose revision of [current] the panel prefers to [panel]; else, when
     * [level] allows, the first that scores the same; null when neither is there.
     */
    private suspend fun <T> nextStep(
        current: T,
        panel: Panel,
        options: List<Pair<String, Revision>>,
        context: suspend (T) -> Context,
        revise: suspend (T, Revision) -> T,
        level: Boolean,
        log: (String, Panel) -> Unit,
    ): Step<T>? {
        var same: Step<T>? = null
        for ((what, fix) in options) {
            // A revision that cannot be made or judged (it threw) is not a step: the next one is tried.
            val made = runCatching { revise(current, fix).let { it to context(it) } }.getOrNull() ?: continue
            val (next, nextCtx) = made
            val nextPanel = deliberate(nextCtx)
            log(what, nextPanel)
            if (nextPanel.betterThan(panel)) return Step(next, nextCtx, nextPanel, better = true)
            if (level && same == null && !panel.betterThan(nextPanel)) same = Step(next, nextCtx, nextPanel, better = false)
        }
        return same
    }

    companion object {
        private const val EPS = 1e-3f

        /** Level steps one candidate may take while it finds no better one. */
        private const val MAX_SIDEWAYS = 2

        /** The judges as the creator reads them. */
        val PERSIAN_NAMES = mapOf(
            "hook" to "هوک", "rhythm" to "ریتم", "motion" to "حرکت و تنوع", "typography" to "تایپوگرافی", "spelling" to "املا و خط",
            "layout" to "چیدمان", "sound" to "صدا", "music" to "موسیقی", "brief" to "پیروی از بریف", "cta" to "دعوت به اقدام",
            // The explainer film's judges (phase 8c).
            "metaphor" to "استعاره‌ی تصویری", "continuity" to "پیوستگی صحنه", "variety" to "تنوع", "timing" to "زمان‌بندی", "pace" to "ضرباهنگ",
        )

        /** Findings a round's report line lists at most. */
        private const val MAX_DETAILS = 14

        /** Revisions tried in one round at most (each is a compile and a sitting), besides the further remedies. */
        private const val MAX_TRIES = 4

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
