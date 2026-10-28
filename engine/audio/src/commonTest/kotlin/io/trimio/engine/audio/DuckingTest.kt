package io.trimio.engine.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bed under the voice: down before the word lands, held through short gaps, back after the phrase. */
class DuckingTest {
    private val rate = 48_000

    /** The voice's envelope: up from 1 s to 2 s, quiet before and after. */
    private fun speech(): FloatArray {
        val out = FloatArray(3 * rate)
        for (i in rate until 2 * rate) out[i] = 1f
        return out
    }

    @Test
    fun theBedFallsWithLookaheadHoldsAndReleases() {
        val g = Ducking.envelope(rate, speech(), under = 0.25f, gap = 1f)
        val look = rate * Ducking.LOOKAHEAD_MS / 1000
        val hold = rate * Ducking.HOLD_MS / 1000
        val release = rate * Ducking.RELEASE_MS / 1000
        // Attack: the fall starts a look-ahead before the onset and reaches the floor as it lands.
        assertEquals(1f, g[rate - look], 0.02f, "open before the look-ahead window")
        val midAttack = rate - look / 2
        assertTrue(g[midAttack] in 0.3f..0.9f, "mid-attack sits between the levels, at ${g[midAttack]}")
        assertEquals(0.25f, g[rate], 0.01f, "fully down as the word lands")
        // Hold: the bed does not breathe up in the gap the hold covers.
        assertEquals(0.25f, g[2 * rate + hold / 2], 0.01f, "held past the phrase")
        // Release: climbing after the hold, open by its end.
        val climb = 2 * rate + hold + release / 2
        assertTrue(g[climb] in 0.3f..0.9f, "mid-release sits between the levels, at ${g[climb]}")
        assertEquals(1f, g[2 * rate + hold + release - 1], 0.02f, "open again after the release")
    }

    @Test
    fun theHoldBridgesAGapBetweenWords() {
        val said = FloatArray(3 * rate)
        for (i in (1.0f * rate).toInt() until (1.5f * rate).toInt()) said[i] = 1f
        for (i in (1.65f * rate).toInt() until (2.0f * rate).toInt()) said[i] = 1f
        val g = Ducking.envelope(rate, said, under = 0.25f, gap = 1f)
        assertEquals(0.25f, g[(1.575f * rate).toInt()], 0.01f, "the 150 ms gap between words does not lift the bed")
        assertEquals(1f, g[(0.7f * rate).toInt()], 0.02f, "open before the first word's look-ahead")
    }

    @Test
    fun theSidechainDepthScalesTheDuck() {
        val soft = FloatArray(2 * rate).also { for (i in rate / 2 until rate) it[i] = 0.5f }
        val g = Ducking.envelope(rate, soft, under = 0.25f, gap = 1f)
        assertEquals(0.625f, g[(0.6f * rate).toInt()], 0.02f, "half the envelope is half the duck")
    }
}
