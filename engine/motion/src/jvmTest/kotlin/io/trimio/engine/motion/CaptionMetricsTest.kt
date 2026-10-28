package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The BBC bands hold their numbers: a drift of a percent in line pitch or reading rate reads wrong
 * on screen and no code review sees it.
 */
class CaptionMetricsTest {

    @Test
    fun lineHeightSitsInTheBroadcastBand() {
        assertEquals(0.07f..0.08f, CaptionMetrics.lineHeightFraction(isVertical = false))
        assertEquals(0.039f..0.045f, CaptionMetrics.lineHeightFraction(isVertical = true))
        // 9:16 stacks one line more: its pitch is tighter than the landscape band is wide.
        assertTrue(CaptionMetrics.lineHeightFraction(true).endInclusive < CaptionMetrics.lineHeightFraction(false).start)
    }

    @Test
    fun verticalStacksOneLineMore() {
        assertEquals(2, CaptionMetrics.maxLines(isVertical = false))
        assertEquals(3, CaptionMetrics.maxLines(isVertical = true))
    }

    @Test
    fun lineLengthFollowsTheFormat() {
        assertEquals(0.68f, CaptionMetrics.lineLengthFraction(isVertical = false))
        assertEquals(0.9f, CaptionMetrics.lineLengthFraction(isVertical = true))
    }

    @Test
    fun readingRateIsTheBandMidpoint() {
        assertEquals(160f..180f, CaptionMetrics.wordsPerMinute)
        assertEquals(0.9f, CaptionMetrics.ReadableSlack)
        assertEquals(60.0 / 170.0, CaptionMetrics.secondsPerWord().toDouble(), absoluteTolerance = 1e-6)
    }

    @Test
    fun fitNeedsTheReadingFloorWithSlack() {
        val floor = 4 * CaptionMetrics.secondsPerWord() * CaptionMetrics.ReadableSlack
        assertTrue(CaptionMetrics.fits(4, floor + 0.01f, isVertical = false))
        assertTrue(!CaptionMetrics.fits(4, floor - 0.01f, isVertical = false))
        assertTrue(CaptionMetrics.fits(0, 0f, isVertical = true))
        // 0.3 s is under one word's floor (0.3176 s) — unreadable in both formats alike.
        assertTrue(!CaptionMetrics.fits(1, 0.3f, isVertical = true))
        assertTrue(!CaptionMetrics.fits(1, 0.3f, isVertical = false))
        assertTrue(CaptionMetrics.fits(1, 0.32f, isVertical = true))
    }

    @Test
    fun holdsBridgeToTheCraftRulebook() {
        // MotionSpec owns the hold numbers; the BBC rate only ever raises the floor.
        assertEquals(
            MotionSpec.Time.holdForLine(3, isLast = false).toDouble(),
            CaptionMetrics.holdForLine(3, isLast = false).toDouble(),
            absoluteTolerance = 1e-5,
        )
        assertEquals(2.0, CaptionMetrics.holdForLine(2, isLast = true).toDouble(), absoluteTolerance = 1e-5)
        // Past ~28 words the reading floor overtakes the craft hold and the floor wins.
        val bbc = 100 * CaptionMetrics.secondsPerWord() * CaptionMetrics.ReadableSlack
        assertEquals(bbc.toDouble(), CaptionMetrics.holdForLine(100, isLast = false).toDouble(), absoluteTolerance = 1e-4)
        assertTrue(CaptionMetrics.holdForLine(100, isLast = false) > MotionSpec.Time.holdForLine(100, isLast = false))
    }
}
