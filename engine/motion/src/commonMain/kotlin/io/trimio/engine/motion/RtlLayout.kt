package io.trimio.engine.motion

/**
 * Pure RTL helpers for the text pipeline. No Skia types here so planners and tests can reason
 * about direction without a shaped layout. SkParagraph owns real bidi; everything below is a
 * conservative measurement fallback.
 */
object RtlLayout {

    enum class TextDirection { Ltr, Rtl }

    /** Logical alignment. [Start] is the reading start: left in LTR, right in RTL. */
    enum class Align { Start, Center, End }

    /** Direction of [s] from its first strong character; no strong character means LTR. */
    fun resolveDirection(s: String): TextDirection =
        if (Kashida.isRtlText(s)) TextDirection.Rtl else TextDirection.Ltr

    /**
     * Minimal bidi reordering for measurement fallbacks. SkParagraph already shapes bidi, so
     * this must not run on the drawing path. A pure RTL string is returned as-is. When an RTL
     * line also carries an LTR island, top-level words are reversed so a left-to-right baseline
     * sees the same ink order as the eye (LTR islands move with the RTL runs between them).
     * Neutrals, brackets and numbers stay the shaper's job.
     */
    fun reorderLogicalToVisual(logical: String): String {
        if (logical.isEmpty() || resolveDirection(logical) != TextDirection.Rtl) return logical
        if (!hasLtrIsland(logical)) return logical
        return logical.split(' ').reversed().joinToString(" ")
    }

    /**
     * Default alignment for a run of [direction]. Always [Align.Start] — callers resolve Start
     * against the direction (right edge in RTL, left edge in LTR) rather than flipping to End.
     */
    fun alignFor(direction: TextDirection): Align = when (direction) {
        TextDirection.Ltr, TextDirection.Rtl -> Align.Start
    }

    private fun hasLtrIsland(s: String): Boolean = s.any { it.isLetter() && !Kashida.isArabicLetter(it) }
}
