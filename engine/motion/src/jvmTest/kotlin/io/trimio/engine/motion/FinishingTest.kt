package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The finishing bands hold: a blur radius that escapes its cap, halation leaking into shadows or
 * preset grain drifting past the sanity ceiling all read wrong on screen and no code review sees
 * it — the clamp, the highlight floor and the bands are pinned here.
 */
class FinishingTest {

    @Test
    fun blurForClampsRadiusAndScalesSamples() {
        val base = Finishing.MotionBlur()
        val still = Finishing.blurFor(0f)
        assertTrue(still.maxRadiusPx > 0f, "radius is clamped to (0, max], never zero")
        assertTrue(still.maxRadiusPx <= base.maxRadiusPx, "radius ${still.maxRadiusPx} above cap ${base.maxRadiusPx}")
        assertEquals(2, still.samples, "a near-still move takes the fewest taps")

        val mid = Finishing.blurFor(20f)
        assertEquals(10f, mid.maxRadiusPx, "half the shutter slice of a 20 px move")
        assertEquals(4, mid.samples, "half the radius budget scales to half the sample band")

        val fast = Finishing.blurFor(10_000f)
        assertEquals(base.maxRadiusPx, fast.maxRadiusPx, "a whip pan clamps to the cap")
        assertEquals(6, fast.samples, "a capped smear takes the most taps")
    }

    @Test
    fun halationLivesAboveTheHighlightFloor() {
        assertFalse(Finishing.halationHighlightsOnly(0.75f), "the floor itself is not a highlight")
        assertFalse(Finishing.halationHighlightsOnly(0f), "shadows never glow")
        assertTrue(Finishing.halationHighlightsOnly(0.76f))
        assertTrue(Finishing.halationHighlightsOnly(1f))
    }

    @Test
    fun presetsHoldThreeNamedLooksAndSaneGrain() {
        assertEquals(setOf("clean", "film", "dream"), Finishing.filmPresets.keys)
        for ((name, look) in Finishing.filmPresets) {
            assertTrue(look.grainAmount <= 0.12f, "$name grain ${look.grainAmount} past the sanity ceiling")
        }
        val film = Finishing.filmPresets.getValue("film")
        assertEquals(0.05f, film.grainAmount, "the house look's grain stays under 0.12")
        assertTrue(Finishing.FilmLook().grainAmount <= 0.12f, "defaults stay under the ceiling too")
    }

    @Test
    fun shutterAngleStaysInTheFilmBand() {
        val angle = Finishing.MotionBlur().shutterAngleDeg
        assertTrue(angle in 90f..270f, "shutter angle $angle outside the film band")
    }
}
