package io.trimio.engine.autopilot.workshop

import io.trimio.engine.motion.score.Compiler
import java.io.File
import kotlin.test.Test

/**
 * A census of how varied the edits are: every workshop scenario through the whole autopilot with
 * several seeds, counting the treatments on screen (recipe and its variant), the bands they sit
 * in, the transitions, and how much a new seed changes the same piece. Off unless
 * TRIMIO_VARIETY_CENSUS names the report file (it takes minutes):
 *
 * TRIMIO_VARIETY_CENSUS=/tmp/census.txt ./gradlew :engine:autopilot:jvmTest --tests '*VarietyCensusTest*'
 */
class VarietyCensusTest {
    private class Run(val id: String, val seed: Long, val look: String, val graphics: List<Compiler.Placed>, val transitions: List<String>)

    private fun token(p: Compiler.Placed) = "${p.recipe}#${p.variant}"

    @Test
    fun census() {
        val target = System.getenv("TRIMIO_VARIETY_CENSUS")?.takeIf { it.isNotBlank() } ?: return
        val seeds = (System.getenv("TRIMIO_VARIETY_SEEDS") ?: "1,2,3").split(',').map { it.trim().toLong() }
        val only = System.getenv("TRIMIO_VARIETY_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val runs = Workshop.scenarios().filter { s -> only == null || only.any { s.id.startsWith(it) } }.flatMap { s ->
            seeds.map { seed ->
                val p = Workshop.run(s, seed)
                Run(s.id, seed, p.score.look, p.compiled.beats.filter { it.recipe != "pop-captions" }, p.score.scenes.mapNotNull { it.transition })
            }
        }
        File(target).apply { parentFile?.mkdirs() }.writeText(report(runs))
    }

    private fun report(runs: List<Run>): String = buildString {
        val all = runs.flatMap { it.graphics }
        val families = all.map { it.recipe }.toSet()
        val tokens = all.map(::token).toSet()
        appendLine("runs ${runs.size}, graphics ${all.size}")
        appendLine("distinct families on screen: ${families.size} ${families.sorted()}")
        appendLine("distinct treatments (family#variant): ${tokens.size}")
        val perFamily = all.groupBy { it.recipe }.mapValues { (_, v) -> v.map { it.variant }.toSet().size }
        appendLine("variants seen per family: ${perFamily.toSortedMap()}")
        appendLine("mean variants per family: ${"%.2f".format(perFamily.values.average())}")
        // The mix: each family's share of all graphics (no kind may dominate).
        val shares = all.groupingBy { it.recipe }.eachCount().entries.sortedByDescending { it.value }
        appendLine("share of graphics by family: " + shares.joinToString(", ") { "${it.key} ${"%.1f".format(100.0 * it.value / all.size.coerceAtLeast(1))}%" })
        appendLine("largest share in any one edit: " + runs.filter { it.graphics.size >= 4 }.maxOfOrNull { r -> r.graphics.groupingBy { it.recipe }.eachCount().values.max() * 100 / r.graphics.size }?.let { "$it%" })
        appendLine("bands: ${all.groupingBy { it.zone }.eachCount().toSortedMap()}")
        val transitions = runs.flatMap { it.transitions }
        appendLine("transitions: ${transitions.groupingBy { it }.eachCount().toSortedMap()}")
        appendLine("looks: ${runs.groupingBy { it.look }.eachCount().toSortedMap()}")
        appendLine("mean distinct families per edit: ${"%.2f".format(runs.map { r -> r.graphics.map { it.recipe }.toSet().size }.average())}")
        appendLine("mean distinct treatments per edit: ${"%.2f".format(runs.map { r -> r.graphics.map(::token).toSet().size }.average())}")
        appendLine("mean graphics per edit: ${"%.2f".format(runs.map { it.graphics.size }.average())}")
        // A new seed on the same piece: how much of what is on screen changes.
        val pairs = runs.groupBy { it.id }.values.flatMap { rs -> rs.indices.flatMap { i -> (i + 1 until rs.size).map { rs[i] to rs[it] } } }
        appendLine("seed pairs ${pairs.size}:")
        appendLine("  look changes: ${"%.0f".format(100.0 * pairs.count { (a, b) -> a.look != b.look } / pairs.size.coerceAtLeast(1))}%")
        appendLine("  family-set distance (Jaccard): ${"%.2f".format(pairs.map { (a, b) -> jaccard(a.graphics.map { it.recipe }, b.graphics.map { it.recipe }) }.average())}")
        appendLine("  treatment-set distance (Jaccard): ${"%.2f".format(pairs.map { (a, b) -> jaccard(a.graphics.map(::token), b.graphics.map(::token)) }.average())}")
        appendLine("  moments that look different (same time ±1 s, other treatment or none): ${"%.0f".format(100.0 * pairs.map { (a, b) -> moved(a, b) }.average())}%")
        appendLine()
        for (r in runs) appendLine("${r.id}/${r.seed} ${r.look}: " + r.graphics.joinToString(" ") { "${token(it)}@${it.zone}" } + " | " + r.transitions.joinToString(","))
    }

    /** 1 − |A∩B| / |A∪B| over multisets. */
    private fun jaccard(a: List<String>, b: List<String>): Double {
        val ca = a.groupingBy { it }.eachCount()
        val cb = b.groupingBy { it }.eachCount()
        val keys = ca.keys + cb.keys
        val inter = keys.sumOf { minOf(ca[it] ?: 0, cb[it] ?: 0) }
        val union = keys.sumOf { maxOf(ca[it] ?: 0, cb[it] ?: 0) }
        return if (union == 0) 0.0 else 1.0 - inter.toDouble() / union
    }

    /** Share of [a]'s graphics with no graphic of the same treatment in [b] within a second. */
    private fun moved(a: Run, b: Run): Double {
        if (a.graphics.isEmpty()) return 0.0
        return a.graphics.count { g -> b.graphics.none { h -> token(h) == token(g) && kotlin.math.abs(h.at - g.at) <= 1f } }.toDouble() / a.graphics.size
    }
}
