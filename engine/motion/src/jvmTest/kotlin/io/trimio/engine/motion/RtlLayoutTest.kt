package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direction and visual order must stay predictable: first strong character decides, pure RTL is
 * identity (the shaper already reads it left-to-right in logical order), and only mixed RTL
 * lines get the conservative word reversal used by measurement fallbacks.
 */
class RtlLayoutTest {

    @Test
    fun resolveDirectionFollowsTheFirstStrongCharacter() {
        assertEquals(RtlLayout.TextDirection.Rtl, RtlLayout.resolveDirection("سلام"))
        assertEquals(RtlLayout.TextDirection.Rtl, RtlLayout.resolveDirection("کتاب خوب است"))
        assertEquals(RtlLayout.TextDirection.Ltr, RtlLayout.resolveDirection("Hello"))
        assertEquals(RtlLayout.TextDirection.Ltr, RtlLayout.resolveDirection(""))
        assertEquals(RtlLayout.TextDirection.Ltr, RtlLayout.resolveDirection("123"))
        assertEquals(RtlLayout.TextDirection.Rtl, RtlLayout.resolveDirection("۱۲۳ سلام"))
        assertEquals(RtlLayout.TextDirection.Rtl, RtlLayout.resolveDirection("سلام Hello"))
        assertEquals(RtlLayout.TextDirection.Ltr, RtlLayout.resolveDirection("Hello سلام"))
    }

    @Test
    fun startAnchorsToTheReadingEdge() {
        // Align.Start stays logical: right edge in RTL, left edge in LTR — never flip to End.
        assertEquals(RtlLayout.Align.Start, RtlLayout.alignFor(RtlLayout.TextDirection.Ltr))
        assertEquals(RtlLayout.Align.Start, RtlLayout.alignFor(RtlLayout.TextDirection.Rtl))
    }

    @Test
    fun pureRtlIsReturnedAsIs() {
        val line = "کتاب خوب است"
        assertEquals(line, RtlLayout.reorderLogicalToVisual(line))
    }

    @Test
    fun ltrLineIsReturnedAsIs() {
        assertEquals("hello world", RtlLayout.reorderLogicalToVisual("hello world"))
        // LTR paragraph with an RTL island: the shaper owns the island; this fallback does not.
        assertEquals("hello دنیا world", RtlLayout.reorderLogicalToVisual("hello دنیا world"))
    }

    @Test
    fun mixedRtlLineReversesWordOrder() {
        assertEquals("هستم ABC من", RtlLayout.reorderLogicalToVisual("من ABC هستم"))
        assertEquals("world hello سلام", RtlLayout.reorderLogicalToVisual("سلام hello world"))
    }

    @Test
    fun singleLtrIslandStillReversesWordOrder() {
        // One island is enough to flip the line into mixed mode; words reverse together.
        assertEquals("است ABC کتاب", RtlLayout.reorderLogicalToVisual("کتاب ABC است"))
    }
}
