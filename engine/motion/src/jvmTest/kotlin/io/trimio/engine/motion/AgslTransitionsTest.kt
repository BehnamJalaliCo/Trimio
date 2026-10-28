package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalog must stay a closed set over real programs: a name in a recipe either is verified or
 * becomes a verified neighbour, and the same stranger always becomes the same neighbour.
 */
class AgslTransitionsTest {

    @Test
    fun verifiedPoolIsUniqueAndNonEmpty() {
        val v = AgslTransitions.verified
        assertTrue(v.isNotEmpty(), "the pool must offer something")
        assertEquals(v.size, v.toSet().size, "catalog names must be unique")
        for (name in v) {
            assertTrue(name.isNotBlank(), "blank catalog id")
            assertEquals(name.lowercase(), name, "ids are lowercase")
            assertTrue(name in TransitionShaders.programs, "$name must resolve to a shipped program")
        }
    }

    @Test
    fun everyFallbackMapsIntoVerified() {
        val v = AgslTransitions.verified
        val probe = v + listOf("burn", "wind", "swap", "luma", "circleopen", "glitch", "ripple", "not-a-transition", "")
        for (name in probe) {
            val landed = AgslTransitions.fallbackFor(name)
            assertTrue(landed in v, "$name -> $landed")
        }
    }

    @Test
    fun unknownNameFallsBackDeterministically() {
        val stranger = "not-a-transition"
        assertEquals(AgslTransitions.fallbackFor(stranger), AgslTransitions.fallbackFor(stranger), "the same stranger lands the same way twice")
        assertTrue(AgslTransitions.fallbackFor(stranger) in AgslTransitions.verified, "a stranger lands inside the pool")
        assertEquals("fade", AgslTransitions.fallbackFor("FADE"), "casing is folded, not invented")
        assertEquals("fade", AgslTransitions.fallbackFor("fade"), "a verified name is itself")
        assertEquals("fadecolor", AgslTransitions.fallbackFor("fadeColor"), "case folding finds the neighbour table")
        assertEquals("fadecolor", AgslTransitions.fallbackFor("burn"), "a colour wash becomes the coloured fade")
        assertEquals("directionalwarp", AgslTransitions.fallbackFor("wind"), "a wipe becomes the directional warp")
        assertEquals("crosswarp", AgslTransitions.fallbackFor("swap"), "a morph becomes the warp family")
        assertEquals("lumawipe", AgslTransitions.fallbackFor("luma"), "a luma wipe stays a luma dissolve")
        assertEquals("glitchlite", AgslTransitions.fallbackFor("glitch"), "the glitch cut keeps its picture")
    }

    @Test
    fun everyVerifiedNameResolvesToItsOwnProgram() {
        for (name in AgslTransitions.verified) {
            assertEquals(name, AgslTransitions.programFor(name).id, "$name resolves to its own program")
        }
    }
}
