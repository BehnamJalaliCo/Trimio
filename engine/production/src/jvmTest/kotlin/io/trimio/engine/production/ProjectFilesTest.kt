package io.trimio.engine.production

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.EditPlan
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hand-off files on a cut with everything in it: a cold open (two stretches), the restart's
 * transition gap, three body segments and a running end hold. Pure text: no media needed.
 */
class ProjectFilesTest {
    // Source 0–20 s. Cold open: 12–13 and 13.5–14 (1.5 s), a 0.25 s gap, then the body 1–5, 6–9, 10–12, then 0.8 s of picture after the last word.
    private val plan = EditPlan(listOf(EditPlan.Segment(1f, 5f, 0f), EditPlan.Segment(6f, 9f, 4f), EditPlan.Segment(10f, 12f, 7f)))
        .withColdOpen(listOf(12f..13f, 13.5f..14f), gap = 0.25f)
        .withOutro(0.8f, sourceDuration = 20f)
    private val source = SourceFacts("talk & co.mp4", "file:///videos/talk%20%26%20co.mp4", 20f, 1080, 1920, 30)
    private val words = listOf("سلام" to 1.2f, "دوستان" to 1.8f, "امروز" to 6.2f, "رشد" to 10.5f, "کردیم." to 12.2f, "واقعاً" to 13.6f)
    private val transcript = Transcript(Language.Persian, words.map { (w, t) -> Word(w, TimeRange((t * 1000).toLong(), (t * 1000 + 400).toLong()), language = Language.Persian) })

    private fun timeline(fps: Int) = ProjectFiles.Timeline(
        "تست & <پروژه>", fps, 1080, 1920, ProjectFiles.frames(plan.duration, fps), ProjectFiles.events(plan, fps), source,
        ProjectFiles.captions(plan, transcript, 4),
    )

    @Test
    fun eventsFollowTheCutOnTheFrameGrid() {
        for (fps in listOf(24, 30, 60)) {
            val events = ProjectFiles.events(plan, fps)
            assertEquals(6, events.size, "two cold-open stretches, three body segments, the end hold at $fps fps")
            events.zipWithNext().forEach { (a, b) -> assertTrue(b.record >= a.recordEnd, "no overlap at $fps fps") }
            // The restart's gap stays empty: the body starts a quarter second after the cold open.
            assertEquals(ProjectFiles.frames(1.75f, fps), events[2].record)
            assertEquals(ProjectFiles.frames(1f, fps), events[2].source)
            // The hold runs the source on from where the last cut ended.
            assertEquals(ProjectFiles.frames(12f, fps), events.last().source)
            assertTrue(events.last().recordEnd <= ProjectFiles.frames(plan.duration, fps))
        }
    }

    @Test
    fun fcpxmlSpineIsContiguousWithGapsAndLayers() {
        val fps = 24
        val xml = ProjectFiles.fcpxml(timeline(fps), graphics = "file:///out/g.mov", mix = "file:///out/mix.wav")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("تست & <پروژه>", (doc.getElementsByTagName("project").item(0) as Element).getAttribute("name"))
        val spine = doc.getElementsByTagName("spine").item(0) as Element
        val items = (0 until spine.childNodes.length).mapNotNull { spine.childNodes.item(it) as? Element }
        assertEquals(listOf("asset-clip", "asset-clip", "gap", "asset-clip", "asset-clip", "asset-clip", "asset-clip"), items.map { it.tagName })
        var cursor = 0L
        for (e in items) {
            assertEquals(cursor, frames(e.getAttribute("offset")))
            cursor += frames(e.getAttribute("duration"))
        }
        assertEquals(frames((doc.getElementsByTagName("sequence").item(0) as Element).getAttribute("duration")), cursor)
        // The layers hang off the first clip at the sequence start (its own start, in source time).
        val first = items.first()
        val lanes = (0 until first.childNodes.length).mapNotNull { first.childNodes.item(it) as? Element }
        assertEquals(listOf("1", "-1"), lanes.map { it.getAttribute("lane") })
        assertEquals(first.getAttribute("start"), lanes.first().getAttribute("offset"))
        assertEquals("0", lanes.last().getAttribute("enabled"), "the mastered mix waits switched off beside the source sound")
    }

    @Test
    fun edlAndSrtAreWellFormed() {
        val fps = 30
        val edl = ProjectFiles.edl(timeline(fps), "g.mov").lines()
        assertTrue(edl[0].startsWith("TITLE: "))
        val events = edl.filter { it.matches(Regex("\\d{3} {2}AX {7}AA/V {2}C {8}(\\d\\d:\\d\\d:\\d\\d:\\d\\d ?){4}")) }
        assertEquals(6, events.size)
        assertEquals("003  AX       AA/V  C        00:00:01:00 00:00:05:00 00:00:01:23 00:00:05:23", events[2].trimEnd())
        assertEquals("00:01:00:00", ProjectFiles.tc(60L * 30, 30))

        val srt = ProjectFiles.srt(ProjectFiles.captions(plan, transcript, 4))
        val cues = srt.trim().split("\n\n").map { it.lines() }
        assertEquals("1", cues.first()[0])
        // The cold open's words come first, on the output clock.
        assertTrue(cues.first()[2].contains("کردیم"), srt)
        cues.zipWithNext().forEach { (a, b) -> assertTrue(a[1].substringAfter("--> ") <= b[1].substringBefore(" -->"), "cues never overlap: $srt") }
        assertTrue(words.all { (w, _) -> srt.contains(w) })
    }

    private fun frames(rational: String): Long = if (rational == "0s") 0 else rational.removeSuffix("s").substringBefore('/').toLong()
}
