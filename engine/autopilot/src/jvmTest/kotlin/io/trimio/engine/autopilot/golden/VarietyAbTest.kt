package io.trimio.engine.autopilot.golden

import io.trimio.engine.autopilot.Shows
import io.trimio.engine.autopilot.workshop.StyleAxis
import io.trimio.engine.autopilot.workshop.Workshop
import java.io.File
import kotlin.test.Test

/**
 * The jury on the same tree with the premium families and treatment variety off (every device in
 * its signature form) and on, run by run: what the variety itself changes, apart from anything
 * else that moved. Off unless TRIMIO_VARIETY_AB names the report file.
 *
 * TRIMIO_VARIETY_AB=/tmp/ab.txt [TRIMIO_VARIETY_ONLY=fa-crypto,en-] ./gradlew :engine:autopilot:jvmTest --tests '*VarietyAbTest*'
 */
class VarietyAbTest {
    @Test
    fun abJury() {
        val target = System.getenv("TRIMIO_VARIETY_AB")?.takeIf { it.isNotBlank() } ?: return
        val only = System.getenv("TRIMIO_VARIETY_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val scenarios = Workshop.scenarios().filter { s -> only == null || only.any { s.id.startsWith(it) } }
        val axis = StyleAxis.runs(only).map { (s, style) -> s to style.id }
        val findings = mutableMapOf<String, List<String>>()
        fun judge(on: Boolean): Map<String, GoldenArchive.Entry> {
            Shows.enabled = on
            return try {
                val runs = GoldenArchive.judge(scenarios, listOf(1L)) + GoldenArchive.judgeStyles(axis, listOf(1L))
                if (on) runs.forEach { findings[it.key] = it.findings.filter { f -> " minor " in f || " major " in f } }
                runs.associate { it.key to it.entry }
            } finally {
                Shows.enabled = true
            }
        }
        val off = judge(false)
        val on = judge(true)
        val lines = off.keys.map { k ->
            val a = off.getValue(k)
            val b = on[k]
            val diffs = a.judges.keys.mapNotNull { j -> val x = a.judges[j]; val y = b?.judges?.get(j); if (x != y) "$j $x→$y" else null }
            "$k: mean ${a.mean}→${b?.mean} ${diffs.joinToString(" ")}" + if (diffs.isEmpty()) "" else "\n" + findings[k].orEmpty().joinToString("\n")
        }
        val worse = off.keys.count { k -> (on[k]?.mean ?: 0f) < off.getValue(k).mean - 0.01f }
        val better = off.keys.count { k -> (on[k]?.mean ?: 0f) > off.getValue(k).mean + 0.01f }
        File(target).writeText("runs ${off.size}: families on worse $worse, better $better\n" + lines.joinToString("\n") + "\n")
    }
}
