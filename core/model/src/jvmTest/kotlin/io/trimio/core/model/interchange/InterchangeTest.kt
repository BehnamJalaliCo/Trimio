package io.trimio.core.model.interchange

import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.CutClip
import io.trimio.core.model.timeline.CutReason
import io.trimio.core.model.timeline.Timeline
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterchangeTest {
    // 10 s source, 2 s cut out in the middle → an 8 s edit with two kept segments.
    private val timeline = Timeline(
        styleId = "liquid-glass", seed = 1, canvas = CanvasSpec(frameRate = 30), durationMs = 8_000,
        clips = listOf(
            CutClip(TimeRange(4_000, 6_000), CutReason.Silence),
            CaptionClip(TimeRange(500, 900), text = "سلام", language = Language.Persian, preset = "pop", wordIndex = 0, group = 0),
            CaptionClip(TimeRange(900, 1_400), text = "دوستان & <همه>", language = Language.Persian, preset = "pop", wordIndex = 1, group = 0),
            CaptionClip(TimeRange(4_500, 5_000), text = "رشد", language = Language.Persian, preset = "pop", wordIndex = 2, group = 1),
        ),
    )
    private val source = SourceMedia("file:///videos/clip%201.mp4", "clip 1.mp4", 10_000, 1080, 1920, hasVideo = true)

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())

    @Test
    fun fcpxmlHasOneSpineClipPerKeptSegmentAndCaptionTitles() {
        val doc = parse(Interchange.fcpxml(timeline, source, "تست"))
        val clips = doc.getElementsByTagName("asset-clip")
        assertEquals(2, clips.length)
        val second = clips.item(1) as Element
        assertEquals("120/30s", second.getAttribute("offset"), "second segment starts at 4 s of output")
        assertEquals("180/30s", second.getAttribute("start"), "and at 6 s of the source")
        val titles = doc.getElementsByTagName("title")
        assertEquals(2, titles.length)
        // The second caption line (output 4.5 s) sits at source 6.5 s inside the second clip.
        assertEquals("195/30s", (titles.item(1) as Element).getAttribute("offset"))
        assertTrue(doc.getElementsByTagName("text-style").item(0).textContent.contains("&"))
    }

    @Test
    fun xmemlCutsVideoAndAudioOnTheSameFrames() {
        val doc = parse(Interchange.xmeml(timeline, source, "تست"))
        val items = doc.getElementsByTagName("clipitem")
        assertEquals(4, items.length, "two segments × video and audio")
        val v2 = items.item(1) as Element
        assertEquals("120", v2.getElementsByTagName("start").item(0).textContent)
        assertEquals("180", v2.getElementsByTagName("in").item(0).textContent)
        assertEquals("file:///videos/clip%201.mp4", doc.getElementsByTagName("pathurl").item(0).textContent)
    }

    @Test
    fun srtHasOneCuePerLine() {
        val srt = Interchange.srt(timeline)
        assertEquals(
            "1\n00:00:00,500 --> 00:00:01,400\nسلام دوستان & <همه>\n\n2\n00:00:04,500 --> 00:00:05,000\nرشد\n\n",
            srt,
        )
    }
}
