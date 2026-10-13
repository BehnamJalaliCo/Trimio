package io.trimio.engine.motion

import io.trimio.engine.motion.recipe.Cue
import io.trimio.engine.motion.recipe.Family
import io.trimio.engine.motion.recipe.Kit
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.recipe.Transitions
import io.trimio.engine.motion.recipe.Variety
import io.trimio.engine.motion.style.StyleBook
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The premium families' floors, for every family, treatment, look and direction: registered and
 * reachable by loose names; at least three layouts and three choreographies, every look allowing
 * three or more; built in Persian and English, on a stage and over the speaker; gone by the end
 * of the cue; every word held to 4.5:1 on what it sits on; no distortion filter over words; no
 * English words of the family's own on a Persian piece; nothing touching the frame's edge at any
 * moment of the entrance, the hold or the exit; and the treatment chosen deterministically from
 * the seed and the content. With TRIMIO_SHOTS set, stills of each family land in build/family-shots/.
 */
class FamiliesTest {
    private val families = FamilyLab.families()
    private val looks = listOf("noir", "paper", "lumen", "finance-terminal", "playful-pop", "bold-brutalist", "luxury-gold", "neon-cyber").map { Look.named(it) }

    @Test
    fun everyFamilyIsRegisteredWithLayoutsAndMoves() {
        assertTrue(families.size >= 24, "families: ${families.map { it.name }}")
        for (f in families) {
            assertEquals(f, Recipes.named(f.name), "registered: ${f.name}")
            assertTrue(f.layouts >= 3, "${f.name}: ${f.layouts} layouts")
            assertTrue(f.moves.toSet().size >= 3, "${f.name}: moves ${f.moves}")
            for (look in StyleBook.all.map { it.look }) {
                val cue = FamilyLab.cue(f.name, true, false, 0)
                val live = f.variantWeights(look, cue).count { it > 0f }
                assertTrue(live >= 3, "${f.name} in ${look.name}: only $live treatments allowed")
            }
        }
        val loose = listOf(
            "leaderboard" to "ranking", "vs" to "versus", "candlestick" to "candles", "pin" to "map-pin", "invoice" to "receipt", "question" to "poll",
            "gauge" to "ring", "position" to "order",
        )
        for ((alias, name) in loose) {
            assertEquals(name, Recipes.named(alias)?.name, alias)
        }
        assertEquals("ui-chat", Recipes.named("notifications")?.name, "the interface recipes keep their names")
        assertEquals("meter", Recipes.named("compare")?.name)
    }

    @Test
    fun everyTreatmentBuildsReadablyInEveryDirection() {
        val problems = mutableListOf<String>()
        val ways = looks.flatMap { look -> listOf(true, false).flatMap { rtl -> listOf(false, true).map { over -> Triple(look, rtl, over) } } }
        for (f in families) for ((look, rtl, over) in ways) for (v in 0 until f.variants) treatment(f, look, rtl, over, v, problems)
        val kinds = problems.distinct().groupBy { it.substringAfter(": ", it.substringAfter(" box ")).take(40) }.values.map { it.first() }
        assertTrue(problems.isEmpty(), kinds.take(60).joinToString("\n") + "\n(${problems.size} problems)")
    }

    /** One treatment of [f] built and checked: it draws, sounds, leaves on time, stays in frame, reads. */
    private fun treatment(f: Family, look: Look, rtl: Boolean, over: Boolean, v: Int, problems: MutableList<String>) {
        val cue = FamilyLab.cue(f.name, rtl, over, v)
        val built = runCatching { f.build(cue, look, FamilyLab.fitter) }.getOrElse { problems += "${f.name}#$v ${look.name} rtl=$rtl over=$over: ${it.message}"; return }
        val where = "${f.name}#$v ${look.name} ${if (rtl) "fa" else "en"}${if (over) " over" else ""}"
        if (built.nodes.isEmpty()) problems += "$where draws nothing"
        if (built.sfx.isEmpty()) problems += "$where is silent"
        for (n in built.nodes) {
            if (n.end > cue.out + 0.05f) problems += "$where outlives its cue"
            if (n.transform.opacity.at(cue.out) > 0.01f) problems += "$where has not faded by its out"
        }
        val box = built.box
        if (box == null || !box.inside(FamilyLab.W.toFloat(), FamilyLab.H.toFloat(), 4f)) problems += "$where box $box leaves the frame"
        walk(built.nodes) { node -> check(node, cue, rtl, where, problems) }
    }

    private fun walk(nodes: List<Node>, visit: (Node) -> Unit) {
        for (n in nodes) {
            visit(n)
            if (n is Group) walk(n.children, visit)
        }
    }

    private fun check(node: Node, cue: Cue, rtl: Boolean, where: String, problems: MutableList<String>) {
        val t = cue.out - 1f
        when (node) {
            is Group -> if (node.filter != null && contains(node) { it is TextNode || it is CounterNode }) problems += "$where: a ${node.filter.family} filter over words"
            is TextNode -> {
                val ink = (node.fill as? Fill.Solid)?.color?.at(t)
                node.ground?.let { g -> if (ink != null && ink.alpha > 0.5f && Kit.ratio(ink, g) < Kit.FLOOR - 0.05f) problems += "$where «${node.text}» ${"%.2f".format(Kit.ratio(ink, g))}:1" }
                if (rtl) englishWord(node.text)?.let { problems += "$where: English «$it» on a Persian piece" }
                if (rtl && Kit.persian(node.text) && node.type.tracking != 0f) problems += "$where: Persian «${node.text}» is tracked"
            }
            is CounterNode -> {
                val ink = (node.fill as? Fill.Solid)?.color?.at(t)
                node.ground?.let { g -> if (ink != null && Kit.ratio(ink, g) < Kit.FLOOR - 0.05f) problems += "$where figure ${"%.2f".format(Kit.ratio(ink, g))}:1" }
                if (rtl && !node.persianDigits) problems += "$where: Latin digits on a Persian piece"
            }
            else -> Unit
        }
    }

    /** A lower-case Latin word of three letters or more that is not part of a symbol or an address. */
    private fun englishWord(s: String): String? = s.split(' ', '/', '·', '…').map { it.trim('(', ')', ',', '.', '!', '?') }
        .firstOrNull { w -> w.length >= 3 && w.all { it in 'a'..'z' || it in 'A'..'Z' } && w.any { it in 'a'..'z' } }

    private fun contains(node: Node, test: (Node) -> Boolean): Boolean = test(node) || (node is Group && node.children.any { contains(it, test) })

    /** Nothing reaches the frame's edge at any moment: entrance, hold, exit. */
    @Test
    fun nothingTouchesTheFrameEdge() {
        val out = System.getenv("TRIMIO_SHOTS")?.let { File("build/family-shots").apply { mkdirs() } }
        val problems = mutableListOf<String>()
        for (f in families) for (layout in 0 until f.layouts) for (over in listOf(true, false)) edges(f, layout, over, out, problems)
        assertTrue(problems.isEmpty(), problems.take(80).joinToString("\n") + "\n(${problems.size} problems)")
    }

    /** Layout [layout] of [f] at its entrance, hold and exit: where it reaches the frame's edge. */
    private fun edges(f: Family, layout: Int, over: Boolean, out: File?, problems: MutableList<String>) {
        val v = layout + (layout % f.moves.size) * f.layouts
        val cue = FamilyLab.cue(f.name, true, over, v)
        for (dt in listOf(0.12f, 0.3f, 2.2f)) {
            val img = FamilyLab.alone(f.name, Look.Noir, cue, cue.at + dt)
            edge(img)?.let { problems += "${f.name} L$layout${if (over) " over" else ""} at +${dt}s: $it" }
            if (dt == 2.2f && !over && out != null) out.resolve("${f.name}-L$layout.png").writeBytes(FamilyLab.frame(f.name, Look.Noir, cue, cue.at + dt).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        val exit = FamilyLab.alone(f.name, Look.Noir, cue, cue.out - Kit.EXIT / 2f)
        edge(exit)?.let { problems += "${f.name} L$layout${if (over) " over" else ""} exit: $it" }
    }

    /** Where a drawn pixel sits within [EDGE] px of the frame's edge, or null. */
    private fun edge(img: org.jetbrains.skia.Image): String? {
        val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
        val w = bmp.width
        val h = bmp.height
        for (y in 0 until h) for (x in 0 until w) {
            if (x in EDGE until w - EDGE && y in EDGE until h - EDGE) continue
            val c = bmp.getColor(x, y)
            if (maxOf(org.jetbrains.skia.Color.getR(c), org.jetbrains.skia.Color.getG(c), org.jetbrains.skia.Color.getB(c)) > LIT) return "pixel at $x,$y"
        }
        return null
    }

    @Test
    fun theTreatmentFollowsTheSeedAndTheContent() {
        for (f in families) {
            val cue = FamilyLab.cue(f.name, true, false, 0)
            val a = (1..60).map { seed -> Variety.pick(f, Look.Noir, cue, seed, null, 77) }
            assertEquals(a, (1..60).map { seed -> Variety.pick(f, Look.Noir, cue, seed, null, 77) }, "${f.name}: same seed, same treatment")
            assertTrue(a.toSet().size >= 3, "${f.name}: seeds give ${a.toSet()}")
            assertTrue(a.map { it % f.layouts }.toSet().size >= 3, "${f.name}: every layout is reached")
            assertEquals(0, Variety.pick(f, Look.Noir, cue, 0, null, 77), "${f.name}: no variety seed keeps the signature")
            val byContent = (1..40).map { salt -> Variety.pick(f, Look.Noir, cue, 5, null, salt) }.toSet()
            assertTrue(byContent.size >= 3, "${f.name}: what a beat says steers the pick too")
        }
    }

    @Test
    fun newCutsAndMarksAreInTheVocabulary() {
        assertTrue(StyleBook.TRANSITIONS.containsAll(Transitions.KINDS))
        assertTrue(StyleBook.all.flatMap { StyleBook.check(it) }.isEmpty(), StyleBook.all.flatMap { StyleBook.check(it) }.joinToString("\n"))
        val overlays = mutableListOf<Node>()
        val sfx = mutableListOf<io.trimio.engine.motion.recipe.Sfx>()
        for (k in Transitions.KINDS) assertTrue(Transitions.overlay(k, 2f, Look.Noir, 1080f, 1920f, overlays, sfx), k)
        assertTrue(overlays.size >= Transitions.KINDS.size && sfx.size >= Transitions.KINDS.size)
        assertTrue(overlays.all { it.end <= 3f }, "cuts are over within a second")
    }

    private companion object {
        const val EDGE = 6
        const val LIT = 40
    }
}
