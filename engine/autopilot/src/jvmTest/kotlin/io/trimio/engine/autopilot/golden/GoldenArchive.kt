package io.trimio.engine.autopilot.golden

import io.trimio.core.model.text.Numerals
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.workshop.Scenario
import io.trimio.engine.autopilot.workshop.StyleAxis
import io.trimio.engine.autopilot.workshop.Workshop
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The golden archive (آرشیو طلایی): the jury's scores for every workshop scenario, kept as a
 * baseline, so a change anywhere in the pipeline cannot quietly make an edit worse. A run is
 * judged by the same panel the app uses ([Autopilot.Production.panel]), plus one thing only a
 * workshop can know: on-screen word accuracy against the scenario's [Scenario.truth] (a recogniser
 * error that reaches the screen). Against the baseline it fails when any judge's score (or the
 * accuracy) drops, and when a scenario's average falls under [FLOOR]. Scenarios and voices are
 * synthetic ([Workshop]); nothing real is stored.
 */
object GoldenArchive {
    /** No scenario may average below this once the archive gates. */
    const val FLOOR = 9.5f

    /** Scores are compared at this resolution (half points are the smallest cost). */
    private const val EPS = 0.01f

    /** Name of the truth-accuracy column (0–10), next to the judges. */
    const val ACCURACY = "words"

    /** One judged run: the panel's minimum, mean and each judge's score (plus [ACCURACY] when there is truth). */
    @Serializable
    data class Entry(val min: Float, val mean: Float, val judges: Map<String, Float>)

    @Serializable
    data class Baseline(
        val note: String = "Jury panel scores per workshop scenario and seed (synthetic). Rewrite with -Pgolden.update=true.",
        val runs: Map<String, Entry> = emptyMap(),
    )

    /** A judged run with what a reader needs: the panel's findings and the wrong words on screen. */
    class Run(val key: String, val entry: Entry, val findings: List<String>, val wrong: List<String>, val seconds: Double)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /** The baseline as committed (on the test classpath), or null until the supervisor stores one. */
    fun load(): Baseline? = GoldenArchive::class.java.getResource("/golden/baseline.json")?.readText()?.let { json.decodeFromString(Baseline.serializer(), it) }

    /** The source file the baseline is written to (the test runs in the module directory). */
    val source: File get() = File("src/jvmTest/resources/golden/baseline.json")

    /** Where the readable report goes (build output, never committed). */
    val report: File get() = File("build/golden/report.txt")

    fun key(scenario: Scenario, seed: Long) = "${scenario.id}/$seed"

    /** Every scenario edited and judged with every seed. */
    fun judge(scenarios: List<Scenario>, seeds: List<Long>): List<Run> = scenarios.flatMap { s -> seeds.map { seed -> judgeOne(s, seed, null) } }

    /** The style axis ([StyleAxis]): each style on its scenarios, keyed `id~style/seed`. */
    fun judgeStyles(runs: List<Pair<Scenario, String>>, seeds: List<Long>): List<Run> = runs.flatMap { (s, style) -> seeds.map { seed -> judgeOne(s, seed, style) } }

    private fun judgeOne(s: Scenario, seed: Long, style: String?): Run {
        val t0 = System.nanoTime()
        val p = Workshop.run(s, seed, style = style)
        val panel = requireNotNull(p.panel) { "${s.id}: the autopilot ran without a jury" }
        val (accuracy, wrong) = accuracy(s, p)
        val judges = panel.verdicts.associate { it.judge to round(it.score) } + listOfNotNull(accuracy?.let { ACCURACY to round(it) })
        val findings = listOf("  style ${p.score.look}") + panel.findings().filterNot { " note " in it } + wrong.map { "  $ACCURACY: «$it» on screen is a recognition error" }
        val key = if (style == null) key(s, seed) else "${StyleAxis.key(s, style)}/$seed"
        return Run(key, Entry(round(panel.min), round(panel.mean), judges), findings, wrong, (System.nanoTime() - t0) / 1e9)
    }

    /**
     * On-screen word accuracy against the truth, 0–10: of the words shown (graphics, labels,
     * captions) that are either in the truth or a recognition error (heard, not in the truth), the
     * share that is right. Words the director wrote itself (labels, brief words) count neither way.
     */
    fun accuracy(s: Scenario, p: Autopilot.Production): Pair<Float?, List<String>> {
        val truth = s.truth.map { norm(it.text) }.toSet()
        val errors = s.words.map { norm(it.text) }.filter { it !in truth }.toSet()
        val shown = (p.compiled.beats.map { it.text } + p.score.scenes.flatMap { sc -> sc.beats.flatMap { listOfNotNull(it.label) + it.items } })
            .flatMap { it.split(' ') }.map(::norm).filter { w -> w.isNotEmpty() && w.none { it.isDigit() } }
        val judged = shown.filter { it in truth || it in errors }
        if (judged.isEmpty()) return null to emptyList()
        val wrong = judged.filter { it in errors && it !in truth }
        return 10f * (judged.size - wrong.size) / judged.size to wrong.distinct()
    }

    /** What got worse: any judge below its baseline score, any run averaging under [FLOOR]. */
    fun compare(baseline: Baseline, current: Map<String, Entry>): List<String> = current.flatMap { (k, e) ->
        val base = baseline.runs[k]
        val drops = base?.judges.orEmpty().mapNotNull { (judge, was) ->
            val now = e.judges[judge] ?: return@mapNotNull "$k: $judge no longer scores (was $was)"
            "$k: $judge dropped $was → $now".takeIf { now < was - EPS }
        }
        drops + listOfNotNull("$k: average ${e.mean} under the floor $FLOOR".takeIf { e.mean < FLOOR - EPS })
    }

    /**
     * The baseline with [current] written over its runs (others kept), saved to [source]. It only
     * ratchets up: a run new to the archive enters when every judge gave it 10, an archived run is
     * rewritten only when nothing in it dropped (a regression stays visible against the old scores).
     */
    fun update(current: Map<String, Entry>): Baseline {
        val old = load()?.runs ?: emptyMap()
        val admitted = current.filter { (k, e) ->
            old[k]?.let { was -> compare(Baseline(runs = mapOf(k to was)), mapOf(k to e)).isEmpty() } ?: (e.min >= 10f - EPS)
        }
        val merged = Baseline(note = load()?.note ?: Baseline().note, runs = (old + admitted).toSortedMap())
        source.apply { parentFile.mkdirs() }.writeText(json.encodeToString(Baseline.serializer(), merged) + "\n")
        return merged
    }

    /**
     * A deliberate re-base when the jury itself got stricter: every archived run is rewritten with
     * [current] (a drop included), new runs still enter only when unanimous, and [note] records why
     * and what moved. After it the archive ratchets up again ([update]).
     */
    fun rebase(current: Map<String, Entry>, note: String): Baseline {
        val old = load()?.runs ?: emptyMap()
        val admitted = current.filter { (k, e) -> k in old || e.min >= 10f - EPS }
        val merged = Baseline(note = note, runs = (old + admitted).toSortedMap())
        source.apply { parentFile.mkdirs() }.writeText(json.encodeToString(Baseline.serializer(), merged) + "\n")
        return merged
    }

    /** A per-scenario, per-judge table (baseline in brackets when it differs), then each run's findings. */
    fun table(runs: List<Run>, baseline: Baseline?): String = buildString {
        val judges = runs.flatMap { it.entry.judges.keys }.distinct()
        val width = maxOf(10, runs.maxOfOrNull { it.key.length } ?: 0) + 1
        appendLine("scenario".padEnd(width) + "min".padStart(5) + "mean".padStart(6) + judges.joinToString("") { it.take(COLUMN - 1).padStart(COLUMN) })
        for (r in runs) {
            val base = baseline?.runs?.get(r.key)
            fun cell(v: Float?, was: Float?) = (v?.let(::fmt) ?: "-") + (if (was != null && v != null && kotlin.math.abs(was - v) > EPS) "(${fmt(was)})" else "")
            append(r.key.padEnd(width) + cell(r.entry.min, base?.min).padStart(5) + cell(r.entry.mean, base?.mean).padStart(6))
            appendLine(judges.joinToString("") { j -> cell(r.entry.judges[j], base?.judges?.get(j)).padStart(COLUMN) })
        }
        val all = runs.map { it.entry }
        if (all.isNotEmpty()) {
            append("average".padEnd(width) + fmt(all.map { it.min }.average().toFloat()).padStart(5) + fmt(all.map { it.mean }.average().toFloat()).padStart(6))
            appendLine(judges.joinToString("") { j -> fmt(all.mapNotNull { it.judges[j] }.average().toFloat()).padStart(COLUMN) })
            appendLine("unanimous 10: ${all.count { it.min >= 10f - EPS }} of ${all.size}")
        }
        for (r in runs) {
            appendLine("\n${r.key} (${"%.1f".format(r.seconds)}s)")
            r.findings.forEach { appendLine(it) }
        }
    }

    private const val COLUMN = 11

    /** Lower case, Persian letter forms, no half-space, diacritics or punctuation, Latin digits. */
    fun norm(w: String): String = Numerals.toLatin(w).lowercase()
        .replace('ي', 'ی').replace('ك', 'ک').replace("‌", "")
        .filter { it.isLetterOrDigit() && it !in 'ً'..'ْ' }

    private fun fmt(v: Float): String = Jury.fmt(v)

    private fun round(v: Float) = kotlin.math.round(v * 100f) / 100f
}
