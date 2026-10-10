package io.trimio.engine.autopilot.golden

import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.workshop.Scenario
import io.trimio.engine.autopilot.workshop.Workshop
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The golden archive (آرشیو طلایی): the jury's scores for every workshop scenario, kept as a
 * baseline, so a change anywhere in the pipeline cannot quietly make an edit worse. A run is
 * judged by the same panel the app uses ([Autopilot.Production.panel]); against the baseline it
 * fails when any judge's score drops, and on its own when a scenario's average falls under
 * [FLOOR]. Scenarios and voices are synthetic ([Workshop]); nothing real is stored.
 */
object GoldenArchive {
    /** No scenario may average below this, whatever the baseline says. */
    const val FLOOR = 9.5f

    /** Scores are compared at this resolution (half points are the smallest cost). */
    private const val EPS = 0.01f

    /** One judged run: the panel's minimum, mean and each judge's score. */
    @Serializable
    data class Entry(val min: Float, val mean: Float, val judges: Map<String, Float>)

    @Serializable
    data class Baseline(
        val note: String = "Jury panel scores per workshop scenario and seed (synthetic). Rewrite with -Pgolden.update=true.",
        val runs: Map<String, Entry> = emptyMap(),
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /** The baseline as committed (on the test classpath), or null before the first update. */
    fun load(): Baseline? = GoldenArchive::class.java.getResource("/golden/baseline.json")?.readText()?.let { json.decodeFromString(Baseline.serializer(), it) }

    /** The source file the baseline is written to (the test runs in the module directory). */
    val source: File get() = File("src/jvmTest/resources/golden/baseline.json")

    fun key(scenario: Scenario, seed: Long) = "${scenario.id}/$seed"

    fun entry(panel: Jury.Panel) = Entry(round(panel.min), round(panel.mean), panel.verdicts.associate { it.judge to round(it.score) })

    /** Every scenario edited and judged with every seed. */
    fun judge(scenarios: List<Scenario>, seeds: List<Long>, log: (String) -> Unit = {}): Map<String, Entry> =
        scenarios.flatMap { s -> seeds.map { seed -> s to seed } }.associate { (s, seed) ->
            val p = Workshop.run(s, seed)
            val panel = requireNotNull(p.panel) { "${s.id}: the autopilot ran without a jury" }
            log("${key(s, seed).padEnd(28)} ${panel.summary()}")
            key(s, seed) to entry(panel)
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

    /** The baseline with [current] written over its runs (others kept), saved to [source]. */
    fun update(current: Map<String, Entry>): Baseline {
        val merged = Baseline(runs = ((load()?.runs ?: emptyMap()) + current).toSortedMap())
        source.apply { parentFile.mkdirs() }.writeText(json.encodeToString(Baseline.serializer(), merged) + "\n")
        return merged
    }

    private fun round(v: Float) = kotlin.math.round(v * 100f) / 100f
}
