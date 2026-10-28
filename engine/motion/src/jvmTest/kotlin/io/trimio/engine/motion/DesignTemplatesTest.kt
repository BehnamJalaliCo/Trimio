package io.trimio.engine.motion

import io.trimio.engine.motion.recipe.Cue
import io.trimio.engine.motion.recipe.DesignTemplates
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.recipe.TransitionPresets
import io.trimio.engine.motion.style.StyleBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The designed template shelf's floors: registered and reachable, built readably in Persian and
 * English on a stage and over the speaker, gone by the end of the cue, every craft rule clean
 * (no linear motion, no unread flash-by, no opacity bounce, no uniform entry), timing inside the
 * designed bands (entry 200-350 ms, exit 65-75%), and enough variety between templates that the
 * shelf does not read as one look in ten colours. Transition presets are designed, not random.
 */
class DesignTemplatesTest {

    private val templates = DesignTemplates.all
    private val looks = listOf("noir", "paper", "lumen").map { Look.named(it) }

    private fun cueFor(name: String, rtl: Boolean, over: Boolean, energy: Float = 0.7f): Cue {
        val w = FamilyLab.W.toFloat()
        val h = FamilyLab.H.toFloat()
        val base = Cue(
            at = 0f, out = 5f,
            x = w / 2f, y = h * 0.35f, width = w * 0.82f, height = h * 0.18f,
            energy = energy, overMedia = over, rtl = rtl, seed = 5,
            variety = 7, frameWidth = w, frameHeight = h,
        )
        return Samples.fill(name, base, rtl)
    }

    @Test
    fun everyTemplateIsRegisteredWithAUniqueName() {
        assertTrue(templates.size >= 10, "templates: ${templates.map { it.name }}")
        val names = templates.map { it.name }
        assertEquals(names.toSet().size, names.size, "duplicate template names")
        for (t in templates) {
            assertEquals(t, Recipes.named(t.name), "registered: ${t.name}")
            assertTrue(t.minHold >= 2.0f, "${t.name}: minHold ${t.minHold} is too short to read")
        }
    }

    @Test
    fun looseNamesResolveToTheShelf() {
        val loose = listOf(
            "lt-simple" to "name-bar", "nameplate" to "name-plate", "lt-hero" to "name-hero",
            "cascade" to "word-cascade", "pop-keyword" to "keyword-pop", "word-cards" to "word-blocks",
            "payoff-first" to "payoff", "hook-slam" to "title-slam",
            "counter-pulse" to "stat-pulse", "bar-chart" to "stat-bars",
        )
        for ((alias, name) in loose) {
            assertEquals(name, Recipes.named(alias)?.name, alias)
        }
    }

    @Test
    fun everyTemplateBuildsReadablyInEveryDirection() {
        val problems = mutableListOf<String>()
        val ways = looks.flatMap { look -> listOf(true, false).flatMap { rtl -> listOf(false, true).map { over -> Triple(look, rtl, over) } } }
        for (t in templates) for ((look, rtl, over) in ways) one(t, look, rtl, over, problems)
        val kinds = problems.distinct().groupBy { it.substringAfter(": ", it).take(48) }.values.map { it.first() }
        assertTrue(problems.isEmpty(), kinds.take(40).joinToString("\n") + "\n(${problems.size} problems)")
    }

    private fun one(t: io.trimio.engine.motion.recipe.Recipe, look: Look, rtl: Boolean, over: Boolean, problems: MutableList<String>) {
        val cue = cueFor(t.name, rtl, over)
        val built = runCatching { t.build(cue, look, FamilyLab.fitter) }
            .getOrElse { problems += "${t.name} ${look.name} rtl=$rtl over=$over: ${it.message}"; return }
        val where = "${t.name} ${look.name} ${if (rtl) "fa" else "en"}${if (over) " over" else ""}"
        if (built.nodes.isEmpty()) problems += "$where draws nothing"
        if (built.sfx.isEmpty()) problems += "$where is silent"
        for (n in built.nodes) {
            if (n.end > cue.out + 0.05f) problems += "$where outlives its cue"
        }
        val box = built.box
        if (box == null || !box.inside(FamilyLab.W.toFloat(), FamilyLab.H.toFloat(), 4f)) problems += "$where box $box leaves the frame"
    }

    @Test
    fun everyTemplatePassesTheCraftGate() {
        val problems = mutableListOf<String>()
        for (t in templates) for (rtl in listOf(true, false)) {
            val cue = cueFor(t.name, rtl, over = false)
            val look = Look.Noir
            val built = runCatching { t.build(cue, look, FamilyLab.fitter) }.getOrElse { problems += "${t.name}: ${it.message}"; continue }
            val root = Group(built.nodes, name = "root")
            val comp = Composition(FamilyLab.W, FamilyLab.H, 30, cue.out + 0.5f, look.canvas, root)
            val result = CraftGate.audit(comp, hasAudio = built.sfx.isNotEmpty())
            for (f in result.findings) {
                if (f.rule == "film_silent") continue
                problems += "${t.name} rtl=$rtl: ${f.rule} ${f.message}"
            }
        }
        assertTrue(problems.isEmpty(), problems.take(30).joinToString("\n") + "\n(${problems.size} problems)")
    }

    @Test
    fun everyTemplateHasThreeLayersOfMotion() {
        val problems = mutableListOf<String>()
        for (t in templates) {
            val cue = cueFor(t.name, rtl = true, over = false)
            val built = t.build(cue, Look.Noir, FamilyLab.fitter)
            val root = built.nodes.firstOrNull() as? Group
            if (root == null) {
                problems += "${t.name}: no staged group"
                continue
            }
            val tr = root.transform
            val hasFade = tr.opacity.at(cue.at - 0.01f) < 0.5f && tr.opacity.at(cue.at + 0.5f) > 0.9f
            val hasTravel = tr.x.at(cue.at - 0.01f) != tr.x.at(cue.at + 0.4f) || tr.y.at(cue.at - 0.01f) != tr.y.at(cue.at + 0.4f) ||
                tr.scale.at(cue.at - 0.01f) != tr.scale.at(cue.at + 0.4f)
            if (!hasFade) problems += "${t.name}: entrance has no overlapping fade"
            if (!hasTravel && t.name !in setOf("payoff", "stat-pulse")) problems += "${t.name}: entrance has no primary travel"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun theShelfIsVariedNotOneLookInTenColours() {
        val signatures = templates.map { t ->
            val cue = cueFor(t.name, rtl = true, over = false)
            val built = t.build(cue, Look.Noir, FamilyLab.fitter)
            listOf(
                built.nodes.size,
                built.camera.size,
                built.overlays.size,
                built.sfx.map { it.kind }.toSet().size,
                (t.preferredHeight * 100).toInt(),
                built.nodes.flatMap { flattenNames(it) }.size,
            )
        }
        val distinct = signatures.toSet()
        assertTrue(distinct.size >= templates.size - 2, "only ${distinct.size} distinct signatures for ${templates.size} templates")
    }

    private fun flattenNames(n: Node): List<String> = when (n) {
        is Group -> listOf(n.name) + n.children.flatMap { flattenNames(it) }
        else -> listOf(n.name)
    }

    @Test
    fun transitionPresetsAreDesignedNotRandom() {
        assertTrue(TransitionPresets.all.size >= 7, "presets: ${TransitionPresets.all.keys}")
        val look = Look.Noir
        val overlays1 = mutableListOf<Node>()
        val sfx1 = mutableListOf<io.trimio.engine.motion.recipe.Sfx>()
        val overlays2 = mutableListOf<Node>()
        val sfx2 = mutableListOf<io.trimio.engine.motion.recipe.Sfx>()
        assertTrue(TransitionPresets.draw("whip", 2f, look, 1080f, 1920f, overlays1, sfx1))
        assertTrue(TransitionPresets.draw("whip", 2f, look, 1080f, 1920f, overlays2, sfx2))
        assertEquals(overlays1.size, overlays2.size, "same whip, same number of overlays")
        assertEquals(sfx1.size, sfx2.size, "same whip, same number of sounds")
        for (name in listOf("whip", "whip-tight", "zoom-punch", "zoom-impact", "glitchlite-soft", "glitchlite-hard")) {
            val o = mutableListOf<Node>()
            val s = mutableListOf<io.trimio.engine.motion.recipe.Sfx>()
            assertTrue(TransitionPresets.draw(name, 2f, look, 1080f, 1920f, o, s), name)
            assertTrue(o.isNotEmpty(), "$name draws no picture")
            assertTrue(s.isNotEmpty(), "$name makes no sound")
            assertTrue(o.all { it.end <= 2f + 1f }, "$name runs past a second")
        }
        assertTrue(!TransitionPresets.draw("unknown-cut", 2f, look, 1080f, 1920f, mutableListOf(), mutableListOf()))
    }

    @Test
    fun transitionPresetsKeepTheDesignedBands() {
        assertTrue(TransitionPresets.WhipRtl.span in 0.28f..0.36f, "whip span ${TransitionPresets.WhipRtl.span}")
        assertTrue(TransitionPresets.WhipTight.span in 0.28f..0.36f, "tight whip span ${TransitionPresets.WhipTight.span}")
        assertTrue(TransitionPresets.ZoomPunch.span in 0.28f..0.36f, "zoom span ${TransitionPresets.ZoomPunch.span}")
        assertTrue(TransitionPresets.ZoomImpact.peakScale in 1.12f..1.22f, "zoom peak ${TransitionPresets.ZoomImpact.peakScale}")
        val g = TransitionPresets.GlitchLiteSoft
        val total = g.bands * 0.012f * 2f + g.bandLife
        assertTrue(total <= 0.12f, "glitch-lite burst $total exceeds 120 ms")
        assertTrue(g.bandLife <= 2f / 30f + 0.001f, "band life ${g.bandLife}")
    }

    @Test
    fun theShelfSurvivesEveryStyle() {
        val problems = mutableListOf<String>()
        for (style in StyleBook.all) {
            val look = style.look
            for (t in templates) {
                val cue = cueFor(t.name, rtl = true, over = true)
                runCatching { t.build(cue, look, FamilyLab.fitter) }
                    .onFailure { problems += "${t.name} in ${style.id}: ${it.message}" }
            }
        }
        assertTrue(problems.isEmpty(), problems.take(20).joinToString("\n"))
    }
}
