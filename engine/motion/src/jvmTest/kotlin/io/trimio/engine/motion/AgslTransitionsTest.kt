package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalog must stay a closed set: a name in a recipe either is verified or becomes a verified
 * neighbour, and the same stranger always becomes the same neighbour — a picker that wanders on a
 * typo is worse than one that refuses.
 */
class AgslTransitionsTest {

    @Test
    fun verifiedPoolIsUniqueAndNonEmpty() {
        val v = AgslTransitions.verified
        assertTrue(v.isNotEmpty(), "the pool must offer something")
        assertEquals(v.size, v.toSet().size, "catalog names must be unique")
        for (name in v) {
            assertTrue(name.isNotBlank(), "blank catalog id")
            assertEquals(name.lowercase(), name, "ids are the lowercase 1.x catalog names")
        }
    }

    @Test
    fun everyFallbackMapsIntoVerified() {
        val v = AgslTransitions.verified
        val probe = v + listOf("burn", "wind", "swap", "luma", "circleopen", "not-a-transition", "")
        for (name in probe) {
            val landed = AgslTransitions.fallbackFor(name)
            assertTrue(landed in v, "$name -> $landed")
        }
    }

    @Test
    fun unknownNameFallsBackDeterministically() {
        val stranger = "not-a-transition"
        assertEquals(AgslTransitions.fallbackFor(stranger), AgslTransitions.fallbackFor(stranger))
        assertTrue(AgslTransitions.fallbackFor(stranger) in AgslTransitions.verified)
        // Verified names come back as themselves; casing is folded, not invented.
        assertEquals("fade", AgslTransitions.fallbackFor("FADE"))
        assertEquals("fade", AgslTransitions.fallbackFor("fade"))
        assertEquals("fadecolor", AgslTransitions.fallbackFor("fadeColor"))
        // Listed strangers land on their documented neighbour, every time.
        assertEquals("fadecolor", AgslTransitions.fallbackFor("burn"))
        assertEquals("directionalwarp", AgslTransitions.fallbackFor("wind"))
        assertEquals("morph", AgslTransitions.fallbackFor("swap"))
    }
}
