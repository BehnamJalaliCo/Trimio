package io.trimio.engine.motion.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Style-token micro-variation: 2–3 accent/mark/grid hands per scene, deterministic in the seeds. */
class VarietyTest {

    @Test
    fun withoutVarietyScenesWearTheSignatureHands() {
        assertEquals(listOf(0), Variety.hands(cue(variety = 0), "accent", 5), "no variety keeps the signature hand")
        assertEquals(SceneTokens(0, 0, 0), Variety.scene(cue(variety = 0), accents = 4, marks = 3, grids = 3), "no variety wears the signature tokens")
    }

    @Test
    fun tokenHandsAreTwoOrThreeAndDistinct() {
        for (seed in 1..12) {
            val hands = Variety.hands(cue(variety = 7, seed = seed), "accent", 5)
            assertTrue(hands.size in 2..3, "seed $seed gave ${hands.size} hands")
            assertEquals(hands.size, hands.toSet().size, "seed $seed repeated a hand: $hands")
            assertTrue(hands.all { it in 0 until 5 }, "seed $seed gave a hand out of range: $hands")
        }
        val two = Variety.hands(cue(variety = 3), "mark", 2)
        assertEquals(2, two.size, "two variants give two hands")
    }

    @Test
    fun sceneTokensAreDeterministicInTheSeeds() {
        val first = Variety.scene(cue(variety = 4, seed = 11), accents = 4, marks = 3, grids = 3)
        val again = Variety.scene(cue(variety = 4, seed = 11), accents = 4, marks = 3, grids = 3)
        assertEquals(first, again, "the same seeds dress a scene the same way")
        val other = Variety.scene(cue(variety = 5, seed = 11), accents = 4, marks = 3, grids = 3)
        assertTrue(first != other, "a new variety seed dresses the scene anew")
    }

    @Test
    fun theWornTokenIsOneOfTheSceneOwnHands() {
        val cue = cue(variety = 9, seed = 2)
        val tokens = Variety.scene(cue, accents = 6, marks = 4, grids = 3)
        assertTrue(tokens.accent in Variety.hands(cue, "accent", 6), "the worn accent is one of the scene's hands")
        assertTrue(tokens.mark in Variety.hands(cue, "mark", 4), "the worn mark is one of the scene's hands")
        assertTrue(tokens.grid in Variety.hands(cue, "grid", 3), "the worn grid is one of the scene's hands")
        assertTrue(tokens.accent in 0 until 6 && tokens.mark in 0 until 4 && tokens.grid in 0 until 3, "worn hands stay in range")
    }

    @Test
    fun finishingTouchesStayDeterministicToo() {
        val cue = cue(variety = 6, seed = 5)
        assertEquals(Variety.touch(cue, "grain", 3), Variety.touch(cue, "grain", 3), "one touch, one hand")
        assertTrue(Variety.touch(cue(variety = 0), "grain", 3) == 0, "no variety keeps the signature touch")
    }

    private fun cue(variety: Int, seed: Int = 1) = Cue(
        at = 0f, out = 1f, x = 0f, y = 0f, width = 1f, height = 1f,
        variety = variety, seed = seed,
    )
}
