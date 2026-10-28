package io.trimio.engine.motion

import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Sfx
import io.trimio.engine.motion.recipe.SfxKind
import io.trimio.engine.motion.recipe.Transitions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cuts drawn by the recipes: a glitch or ripple cut must leave a picture on the frame — not
 * only a sound — inside the short burst a cut is allowed.
 */
class TransitionsCutTest {

    @Test
    fun glitchCutDrawsPictureAndSound() {
        val (nodes, sfx) = cut("glitch")
        assertTrue(nodes.any { it.name == "glitch-lite" }, "the glitch cut draws its glitch-lite picture")
        assertTrue(sfx.any { it.kind == SfxKind.Swish }, "the glitch cut keeps its swish")
    }

    @Test
    fun rippleCutDrawsPictureAndSound() {
        val (nodes, sfx) = cut("ripple")
        assertTrue(nodes.any { it.name == "ripple-ring" }, "the ripple cut draws its ring")
        assertTrue(nodes.any { it.name == "ripple-band" }, "the ripple cut draws its wave bands")
        assertTrue(sfx.any { it.kind == SfxKind.Shimmer }, "the ripple cut keeps its shimmer")
    }

    @Test
    fun glitchLiteStaysInsideItsShortWindow() {
        val s = 2f
        val (nodes, _) = cut("glitch", s)
        val open = nodes.minOf { it.start }
        val close = nodes.maxOf { it.end }
        assertTrue(close - open in 0.08f..0.12f, "glitch-lite is an 80–120 ms burst, was ${(close - open) * 1000} ms")
        assertTrue(open <= s && close >= s, "the burst covers the cut")
    }

    @Test
    fun olderCutsStillOverlayAndSound() {
        for (kind in listOf("burn", "slice", "dip", "leak-cool")) {
            assertTrue(kind in Transitions.KINDS, "$kind stays a known cut")
            val (nodes, sfx) = cut(kind)
            assertTrue(nodes.isNotEmpty(), "$kind draws its overlay")
            assertTrue(sfx.isNotEmpty(), "$kind keeps its sound")
        }
    }

    @Test
    fun unknownKindIsRejectedWithoutATrace() {
        val overlays = mutableListOf<Node>()
        val sfx = mutableListOf<Sfx>()
        val drawn = Transitions.overlay("not-a-cut", 1f, Look.Noir, 1080f, 1920f, overlays, sfx)
        assertEquals(false, drawn, "an unknown cut draws nothing")
        assertTrue(overlays.isEmpty(), "an unknown cut leaves no nodes")
        assertTrue(sfx.isEmpty(), "an unknown cut leaves no sound")
    }

    private fun cut(kind: String, s: Float = 2f): Pair<List<Node>, List<Sfx>> {
        val overlays = mutableListOf<Node>()
        val sfx = mutableListOf<Sfx>()
        assertTrue(Transitions.overlay(kind, s, Look.Noir, 1080f, 1920f, overlays, sfx), "$kind is drawn by the recipes' cuts")
        return overlays to sfx
    }
}
