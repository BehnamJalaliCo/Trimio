package io.trimio.engine.autopilot.workshop

import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.SpeechAnalysis
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every workshop scenario through the whole autopilot (rules, no model) with seeds 1–3, holding
 * only the invariants no edit may break: it does not crash, it has a score, captions are there
 * unless the brief turned them off, and an ask for a keyword ends on the comment field. Whether
 * an edit is good is the jury's call, not this test's.
 *
 * ./gradlew :engine:autopilot:jvmTest --tests '*WorkshopTest*' [-Pworkshop.stills=true] [-Pworkshop.only=fa-crypto,en-]
 *   [-Pworkshop.seeds=1,2,3] [-Pvisuals.dir=…] [-Pauto.llm=… -Pauto.native=…]
 * Reports, scores and (with stills) PNGs land in build/workshop/<id>/seed-N, a summary in build/workshop/summary.txt.
 */
class WorkshopTest {
    @Test
    fun scenariosAreVariedAndHearable() {
        val all = Workshop.scenarios()
        assertTrue(all.size >= 20, "about twenty scenarios: ${all.size}")
        assertEquals(all.size, all.map { it.id }.toSet().size, "ids are unique")
        assertTrue(all.count { it.language == "en" } in 4..6 && all.count { it.language == "fa" } >= 13, "Persian and English")
        assertTrue(all.minOf { it.seconds } in 12f..20f && all.maxOf { it.seconds } in 80f..100f, "15 s to 90 s: ${all.map { it.seconds }}")
        assertTrue(all.map { it.domain }.toSet().size >= 12, "many domains")
        for (s in all) {
            assertTrue(s.words.isNotEmpty() && s.truth.isNotEmpty(), s.id)
            assertTrue(s.words.zipWithNext().all { (a, b) -> a.range.startMs <= b.range.startMs }, "${s.id}: words in order")
            assertTrue(s.errors.isNotEmpty(), "${s.id}: the recogniser makes mistakes")
        }
        // The voice is speech to the analysis: words, real pauses, nothing invented.
        val s = Workshop.scenario("fa-crypto-bitcoin")
        assertEquals((s.seconds * 48_000).toInt(), s.voice.samples.size, "voice as long as the piece")
        val heard = SpeechAnalysis.of(s.voice, s.transcript)
        assertTrue(heard.pauses.size >= 8, "pauses heard: ${heard.pauses.size}")
        assertTrue(heard.edit.duration < s.seconds - 1f, "long pauses are cut: ${heard.edit.duration} of ${s.seconds}")
        assertTrue(heard.transcript.words.count { it.emphasis > 0.6f } in 3..heard.transcript.words.size / 2, "some words stressed, not all")
    }

    @Test
    fun everyScenarioGetsAWellFormedEdit() {
        val seeds = System.getProperty("workshop.seeds")?.split(',')?.map { it.trim().toLong() } ?: listOf(1L, 2L, 3L)
        val only = System.getProperty("workshop.only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val stills = System.getProperty("workshop.stills") == "true"
        val scenarios = Workshop.scenarios().filter { s -> only == null || only.any { s.id.startsWith(it) } }
        val failures = mutableListOf<String>()
        val summary = StringBuilder()
        val t0 = System.nanoTime()
        for (s in scenarios) for (seed in seeds) {
            val t1 = System.nanoTime()
            val run = runCatching { Workshop.run(s, seed) }
            val p = run.getOrNull()
            if (p == null) {
                failures += "${s.id} seed $seed: crashed: ${run.exceptionOrNull()?.stackTraceToString()?.lines()?.take(8)?.joinToString("\n    ")}"
                continue
            }
            val took = (System.nanoTime() - t1) / 1e9
            Workshop.save(s, seed, p, stills, took)
            failures += invariants(s, seed, p)
            summary.appendLine(line(s, seed, p, took))
        }
        summary.appendLine("\n${scenarios.size} scenarios × ${seeds.size} seeds in ${"%.1f".format((System.nanoTime() - t0) / 1e9)}s; ${failures.size} failures")
        failures.forEach { summary.appendLine("FAIL $it") }
        File(Workshop.out, "summary.txt").apply { parentFile.mkdirs() }.writeText(summary.toString())
        println(summary)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun invariants(s: Scenario, seed: Long, p: Autopilot.Production): List<String> {
        val out = mutableListOf<String>()
        val c = p.compiled
        val graphics = c.beats.filter { it.recipe != CAPTIONS }
        if (p.score.scenes.isEmpty() || graphics.isEmpty()) out += "${s.id} seed $seed: empty score (${p.score.scenes.size} scenes, ${graphics.size} graphics)"
        val captions = c.beats.any { it.recipe == CAPTIONS }
        if (!s.captionsOff && !captions) out += "${s.id} seed $seed: no captions, the brief did not turn them off"
        if (s.captionsOff && captions) out += "${s.id} seed $seed: captions shown, the brief said none"
        val keyword = s.ask?.keyword.orEmpty()
        if (keyword.isNotEmpty()) {
            val end = c.composition.duration
            val field = graphics.filter { it.recipe == "comment" && it.out >= end - 1.5f }
            val shown = graphics.filter { it.recipe == "comment" }.map { "${it.at}–${it.out} ${it.text}" }
            if (field.isEmpty()) out += "${s.id} seed $seed: the ask «$keyword» is not on the last screen (comment beats: $shown, end $end)"
        }
        return out
    }

    private fun line(s: Scenario, seed: Long, p: Autopilot.Production, took: Double): String {
        val c = p.compiled
        val graphics = c.beats.filter { it.recipe != CAPTIONS }
        val last = graphics.filter { it.out >= c.composition.duration - 1.5f }.joinToString("+") { it.recipe }.ifEmpty { "-" }
        return "${s.id.padEnd(24)} s$seed ${"%5.1f".format(s.seconds)}→${"%5.1f".format(c.composition.duration)}s ${"%4.1f".format(took)}s " +
            "${p.understanding.domain}/${p.score.look} cta=${p.understanding.cta?.let { "${it.action}:${it.keyword}" } ?: "-"} last=$last | " +
            graphics.joinToString(" ") { it.recipe }
    }

    private companion object {
        const val CAPTIONS = "pop-captions"
    }
}
