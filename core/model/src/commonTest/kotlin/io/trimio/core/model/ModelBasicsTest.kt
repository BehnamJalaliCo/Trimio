package io.trimio.core.model

import io.trimio.core.model.input.AspectRatio
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.Resolution
import io.trimio.core.model.input.VideoFormat
import io.trimio.core.model.style.DesignStyle
import io.trimio.core.model.style.StyleFamily
import io.trimio.core.model.text.Language
import io.trimio.core.model.text.Numerals
import io.trimio.core.model.text.ScriptDetector
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelBasicsTest {

    @Test
    fun allTwentyEightStylesHaveUniqueIdsAndEveryFamilyIsUsed() {
        assertEquals(28, DesignStyle.entries.size)
        assertEquals(28, DesignStyle.entries.map { it.id }.toSet().size)
        assertEquals(StyleFamily.entries.toSet(), DesignStyle.entries.map { it.family }.toSet())
        assertEquals(DesignStyle.LiquidGlass, DesignStyle.fromId("liquid-glass"))
    }

    @Test
    fun timeRangeRejectsNegativeDurationAndDetectsOverlap() {
        assertFailsWith<IllegalArgumentException> { TimeRange(500, 100) }
        assertTrue(TimeRange(0, 100).overlaps(TimeRange(50, 150)))
        assertFalse(TimeRange(0, 100).overlaps(TimeRange(100, 200)))
        assertTrue(99L in TimeRange(0, 100))
        assertFalse(100L in TimeRange(0, 100))
    }

    @Test
    fun canvasSizesAreEvenAndMatchAspect() {
        val portrait = CanvasSpec(AspectRatio.Portrait9x16, Resolution.FullHd)
        assertEquals(1080, portrait.widthPx)
        assertEquals(1920, portrait.heightPx)

        val landscape4k = CanvasSpec(AspectRatio.Landscape16x9, Resolution.Uhd4k)
        assertEquals(3840, landscape4k.widthPx)
        assertEquals(2160, landscape4k.heightPx)

        val fourByFive = CanvasSpec(AspectRatio.Portrait4x5, Resolution.FullHd)
        assertEquals(1080, fourByFive.widthPx)
        assertEquals(1350, fourByFive.heightPx)
    }

    @Test
    fun rotatedPhoneVideoIsDetectedAsPortrait() {
        val format = VideoFormat(width = 1920, height = 1080, frameRate = 30f, rotationDegrees = 90)
        assertEquals(AspectRatio.Portrait9x16, format.closestAspect())
    }

    @Test
    fun scriptDetectorHandlesMixedPersianEnglish() {
        assertEquals(Language.Persian, ScriptDetector.detect("سیگنال"))
        assertEquals(Language.English, ScriptDetector.detect("BTC"))
        assertEquals(Language.Persian, ScriptDetector.detect("می\u200Cخواهم")) // with ZWNJ
        assertEquals(Language.English, ScriptDetector.detect("123", fallback = Language.English))
    }

    @Test
    fun numeralsConvertBothWays() {
        assertEquals("۲۵٪ سود", Numerals.toPersian("25٪ سود"))
        assertEquals("2025", Numerals.toLatin("۲۰۲۵"))
        assertEquals("42", Numerals.toLatin("٤٢"))
    }

    private fun w(text: String, start: Long, end: Long) = Word(text, TimeRange(start, end), language = ScriptDetector.detect(text))

    @Test
    fun transcriptLinesBreakOnSentencePauseAndLength() {
        val transcript = Transcript(
            Language.Persian,
            listOf(
                w("امروز", 0, 300), w("بیت\u200Cکوین", 320, 700), w("رشد", 720, 900), w("کرد.", 920, 1200),
                w("این", 2000, 2200), w("یعنی", 2220, 2400),
                w("یک", 3500, 3600), w("دو", 3620, 3700), w("سه", 3720, 3800), w("چهار", 3820, 3900), w("پنج", 3920, 4000),
            ),
        )
        val lines = transcript.lines(pauseMs = 450, maxWords = 4).map { line -> line.joinToString(" ") { it.text } }
        assertEquals(listOf("امروز بیت\u200Cکوین رشد کرد.", "این یعنی", "یک دو سه چهار", "پنج"), lines)
    }

    @Test
    fun transcriptFindsSilencesIncludingLeadingAndTrailing() {
        val transcript = Transcript(Language.English, listOf(w("hello", 800, 1200), w("world", 1250, 1600)))
        assertEquals(listOf(TimeRange(0, 800), TimeRange(1600, 3000)), transcript.silences(minGapMs = 500, totalMs = 3000))
    }
}
