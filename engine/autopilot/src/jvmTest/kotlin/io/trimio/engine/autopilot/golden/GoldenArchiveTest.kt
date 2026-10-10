package io.trimio.engine.autopilot.golden

import io.trimio.engine.autopilot.workshop.Workshop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The regression gate: every workshop scenario edited and judged, against the archived scores.
 *
 * ./gradlew :engine:autopilot:jvmTest --tests '*GoldenArchiveTest*' [-Pgolden.only=fa-crypto,en-] [-Pgolden.seeds=1,2]
 * ./gradlew :engine:autopilot:jvmTest --tests '*GoldenArchiveTest*' -Pgolden.update=true   (rewrites the baseline)
 */
class GoldenArchiveTest {
    @Test
    fun noJudgeScoresLowerThanTheArchive() {
        val seeds = System.getProperty("golden.seeds")?.split(',')?.map { it.trim().toLong() } ?: listOf(1L)
        val only = System.getProperty("golden.only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val scenarios = Workshop.scenarios().filter { s -> only == null || only.any { s.id.startsWith(it) } }
        val current = GoldenArchive.judge(scenarios, seeds, ::println)
        if (System.getProperty("golden.update") == "true") {
            val saved = GoldenArchive.update(current)
            println("golden: ${current.size} runs written to ${GoldenArchive.source.absolutePath} (${saved.runs.size} in the archive)")
            return
        }
        val baseline = GoldenArchive.load() ?: fail("no golden baseline yet: run with -Pgolden.update=true")
        val problems = GoldenArchive.compare(baseline, current)
        current.keys.filter { it !in baseline.runs }.forEach { println("golden: $it is new (not in the archive yet)") }
        assertTrue(problems.isEmpty(), "the jury scores lower than the archive:\n" + problems.joinToString("\n"))
    }

    @Test
    fun aDropOrALowAverageIsCaught() {
        val base = GoldenArchive.Baseline(runs = mapOf("x/1" to GoldenArchive.Entry(9.5f, 9.95f, mapOf("hook" to 10f, "layout" to 9.5f))))
        val same = mapOf("x/1" to GoldenArchive.Entry(9.5f, 9.95f, mapOf("hook" to 10f, "layout" to 9.5f)))
        assertTrue(GoldenArchive.compare(base, same).isEmpty())
        val better = mapOf("x/1" to GoldenArchive.Entry(10f, 10f, mapOf("hook" to 10f, "layout" to 10f)))
        assertTrue(GoldenArchive.compare(base, better).isEmpty(), "improving never fails")
        val dropped = mapOf("x/1" to GoldenArchive.Entry(9f, 9.6f, mapOf("hook" to 9f, "layout" to 9.5f)))
        assertEquals(listOf("x/1: hook dropped 10.0 → 9.0"), GoldenArchive.compare(base, dropped))
        val low = mapOf("y/1" to GoldenArchive.Entry(8f, 9.2f, mapOf("hook" to 8f)))
        assertTrue(GoldenArchive.compare(base, low).single().contains("under the floor"), "a new run is still held to the floor")
    }
}
