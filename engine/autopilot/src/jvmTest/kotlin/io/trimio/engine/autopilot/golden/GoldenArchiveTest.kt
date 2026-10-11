package io.trimio.engine.autopilot.golden

import io.trimio.engine.autopilot.workshop.StyleAxis
import io.trimio.engine.autopilot.workshop.Workshop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The regression gate: every workshop scenario edited and judged, against the archived scores.
 * Until a baseline is stored the archive only reports (build/golden/report.txt: a per-scenario,
 * per-judge table and every finding); once one is, any drop or any run under the floor fails.
 *
 * ./gradlew :engine:autopilot:jvmTest --tests '*GoldenArchiveTest*' [-Pgolden.only=fa-crypto,en-] [-Pgolden.seeds=1,2]
 * ./gradlew :engine:autopilot:jvmTest --tests '*GoldenArchiveTest*' -Pgolden.update=true   (stores the baseline)
 *
 * The style axis ([StyleAxis]: each of the 28 styles on two or three scenarios, `id~style/seed`)
 * runs too; `-Pgolden.styles=false` skips it, `-Pgolden.styles=only` runs nothing else, and
 * `-Pgolden.only` also takes style ids. A new run enters the archive only when unanimous.
 */
class GoldenArchiveTest {
    @Test
    fun noJudgeScoresLowerThanTheArchive() {
        val seeds = System.getProperty("golden.seeds")?.split(',')?.map { it.trim().toLong() } ?: listOf(1L)
        val only = System.getProperty("golden.only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val styles = System.getProperty("golden.styles") ?: "true"
        val scenarios = Workshop.scenarios().filter { s -> styles != "only" && (only == null || only.any { s.id.startsWith(it) }) }
        val axis = if (styles == "false") emptyList() else StyleAxis.runs(only).map { (s, style) -> s to style.id }
        val runs = GoldenArchive.judge(scenarios, seeds) + GoldenArchive.judgeStyles(axis, seeds)
        val current = runs.associate { it.key to it.entry }
        val baseline = GoldenArchive.load()
        val table = GoldenArchive.table(runs, baseline)
        GoldenArchive.report.apply { parentFile.mkdirs() }.writeText(table)
        println(table)
        if (System.getProperty("golden.update") == "true") {
            val saved = GoldenArchive.update(current)
            println("golden: ${current.size} runs written to ${GoldenArchive.source.absolutePath} (${saved.runs.size} in the archive)")
            return
        }
        if (baseline == null) {
            println("golden: no baseline stored yet — reporting only (store one with -Pgolden.update=true)")
            return
        }
        current.keys.filter { it !in baseline.runs }.forEach { println("golden: $it is new (not in the archive yet)") }
        val problems = GoldenArchive.compare(baseline, current)
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
        val table = GoldenArchive.table(listOf(GoldenArchive.Run("x/1", dropped.getValue("x/1"), listOf("  hook major …"), emptyList(), 1.0)), base)
        assertTrue("9(10)" in table, "the table shows the archived score next to a changed one:\n$table")
    }

    @Test
    fun wordsOnScreenAreCheckedAgainstTheTruth() {
        assertEquals(GoldenArchive.norm("می‌تونید"), GoldenArchive.norm("میتونید"), "half-spaces do not count as errors here")
        assertEquals("need", GoldenArchive.norm("Need,"))
    }
}
