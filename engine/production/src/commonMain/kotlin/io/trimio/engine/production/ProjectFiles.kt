package io.trimio.engine.production

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Words
import kotlin.math.roundToLong

/**
 * A finished edit as another editor's timeline: the creator's source cut as the engine cut it, the
 * motion graphics as one rendered layer on top (they are Trimio's own; no other editor could
 * rebuild them), the mastered mix beside the source sound, and the captions.
 *  - [fcpxml]: Final Cut Pro, DaVinci Resolve and Premiere (through its FCPXML import), FCPXML 1.10.
 *  - [edl]: CMX 3600, the cut list every editor reads (source events only; the layer starts at 0).
 *  - [srt]: every spoken line, on the edit's clock (YouTube, players, caption tracks).
 * Every time is snapped to the export's frame grid, so cuts land on frames.
 */
object ProjectFiles {

    /** A caption on the output clock, seconds. */
    data class Caption(val start: Float, val end: Float, val text: String)

    /** A stretch of the source (frames at the timeline's rate) placed at [record] on the output. */
    data class Event(val source: Long, val record: Long, val length: Long) {
        val sourceEnd: Long get() = source + length
        val recordEnd: Long get() = record + length
    }

    /** What the files describe. */
    data class Timeline(
        val name: String,
        val fps: Int,
        val width: Int,
        val height: Int,
        /** Frames of the whole edit (graphics included). */
        val frames: Long,
        val events: List<Event>,
        /** The creator's file; its picture is cut when [SourceFacts.width] is known (0 for a voice recording). */
        val source: SourceFacts,
        val captions: List<Caption>,
    ) {
        val hasVideo: Boolean get() = source.width > 0
    }

    /** The cut ([EditPlan.played], then a running end hold) on a [fps] frame grid; overlaps from rounding are trimmed. */
    fun events(plan: EditPlan, fps: Int): List<Event> {
        val segments = plan.played + listOfNotNull(plan.outro?.takeIf { it.running > 0f }?.let { EditPlan.Segment(it.sourceStart, it.sourceStart + it.running, plan.bodyEnd) })
        val out = mutableListOf<Event>()
        for (s in segments) {
            val record = maxOf(frames(s.outStart, fps), out.lastOrNull()?.recordEnd ?: 0L)
            val length = frames(s.outEnd, fps) - record
            if (length > 0) out += Event(frames(s.sourceStart, fps) + (record - frames(s.outStart, fps)), record, length)
        }
        return out
    }

    /**
     * Every spoken line as a caption: the cold open's words, then the body's, in lines of at most
     * [maxWords] (the captions the edit itself would show, without hiding any under a headline).
     */
    fun captions(plan: EditPlan, transcript: io.trimio.core.model.transcript.Transcript, maxWords: Int): List<Caption> {
        fun said(words: List<Word>) = words.filterNot { it.text.isBlank() || Words.isFiller(it.text) }
        val n = maxWords.coerceIn(1, MAX_WORDS)
        val lines = Words.captionLines(said(plan.preludeWords(transcript)), n) + Words.captionLines(said(plan.remap(transcript).words), n)
        return lines.filter { it.isNotEmpty() }.map { line ->
            Caption(line.first().range.startMs / MS, line.last().range.endMs / MS, line.joinToString(" ") { it.text.trim() })
        }
    }

    /** SubRip: one cue per line, never overlapping the next. */
    fun srt(captions: List<Caption>): String = buildString {
        captions.forEachIndexed { i, c ->
            val end = minOf(c.end, captions.getOrNull(i + 1)?.start ?: Float.MAX_VALUE).coerceAtLeast(c.start + MIN_CUE)
            append(i + 1).append('\n')
            append(clock(c.start)).append(" --> ").append(clock(end)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    /**
     * FCPXML 1.10. [graphics] (the motion layer with alpha), [mix] (the mastered sound) and
     * [reference] (the finished video, for phones that cannot write alpha) are URLs; each that is
     * given rides above the cut: graphics and reference on lane 1 (the reference switched off), the
     * mix on lane -1 switched off, ready to swap for the source sound.
     */
    fun fcpxml(t: Timeline, graphics: String? = null, mix: String? = null, reference: String? = null): String {
        val fps = t.fps
        fun r(frames: Long) = if (frames == 0L) "0s" else "$frames/${fps}s"
        val sourceFrames = maxOf(frames(t.source.durationSeconds, fps), t.events.maxOfOrNull { it.sourceEnd } ?: 0L)
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<!DOCTYPE fcpxml>")
            appendLine("""<fcpxml version="1.10">""")
            appendLine("  <resources>")
            appendLine("""    <format id="r1" name="Trimio ${t.width}x${t.height} $fps" frameDuration="1/${fps}s" width="${t.width}" height="${t.height}" colorSpace="1-1-1 (Rec. 709)"/>""")
            if (t.hasVideo) appendLine("""    <format id="r2" frameDuration="1/${t.source.fps.takeIf { it > 0 } ?: fps}s" width="${t.source.width}" height="${t.source.height}"/>""")
            val video = if (t.hasVideo) """ hasVideo="1" format="r2" videoSources="1"""" else ""
            appendLine("""    <asset id="r3" name="${esc(t.source.name)}" start="0s" duration="${r(sourceFrames)}"$video hasAudio="1" audioSources="1" audioChannels="2" audioRate="48000">""")
            appendLine("""      <media-rep kind="original-media" src="${esc(t.source.url ?: relative(t.source.name))}"/>""")
            appendLine("    </asset>")
            (graphics ?: reference)?.let { layer ->
                appendLine("""    <asset id="r4" name="${esc(layer.substringAfterLast('/'))}" start="0s" duration="${r(t.frames)}" hasVideo="1" format="r1" videoSources="1">""")
                appendLine("""      <media-rep kind="original-media" src="${esc(layer)}"/>""")
                appendLine("    </asset>")
            }
            mix?.let {
                appendLine("""    <asset id="r5" name="${esc(it.substringAfterLast('/'))}" start="0s" duration="${r(t.frames)}" hasAudio="1" audioSources="1" audioChannels="1" audioRate="48000">""")
                appendLine("""      <media-rep kind="original-media" src="${esc(it)}"/>""")
                appendLine("    </asset>")
            }
            appendLine("  </resources>")
            appendLine("  <library>")
            appendLine("""    <event name="Trimio">""")
            appendLine("""      <project name="${esc(t.name)}">""")
            appendLine("""        <sequence format="r1" duration="${r(t.frames)}" tcStart="0s" tcFormat="NDF" audioLayout="stereo" audioRate="48k">""")
            appendLine("          <spine>")
            spine(t, graphics, reference, mix, ::r).forEach { appendLine(it) }
            appendLine("          </spine>")
            appendLine("        </sequence>")
            appendLine("      </project>")
            appendLine("    </event>")
            appendLine("  </library>")
            appendLine("</fcpxml>")
        }
    }

    /**
     * The spine: gaps where nothing of the source plays (a transition into the restart), the
     * source's events in order. The layers connect to the first element at the sequence's start.
     */
    private fun spine(t: Timeline, graphics: String?, reference: String?, mix: String?, r: (Long) -> String): List<String> {
        val lines = mutableListOf<String>()
        var cursor = 0L
        val layers = buildList {
            if (graphics != null || reference != null) {
                val off = if (graphics == null) """ enabled="0"""" else ""
                add("""<asset-clip ref="r4" lane="1" offset="%s" name="${if (graphics != null) "Trimio graphics" else "Trimio edit (reference)"}" start="0s" duration="${r(t.frames)}"$off/>""")
            }
            if (mix != null) add("""<asset-clip ref="r5" lane="-1" offset="%s" name="Trimio mix" start="0s" duration="${r(t.frames)}" enabled="0"/>""")
        }
        fun open(tag: String, attrs: String, start: Long) {
            val first = lines.isEmpty()
            lines += "            <$tag $attrs${if (first && layers.isNotEmpty()) "" else "/"}>"
            if (first && layers.isNotEmpty()) {
                layers.forEach { lines += "              " + it.replace("%s", r(start)) }
                lines += "            </$tag>"
            }
        }
        for (e in t.events) {
            if (e.record > cursor) open("gap", """name="Gap" offset="${r(cursor)}" start="0s" duration="${r(e.record - cursor)}"""", 0L)
            open("asset-clip", """ref="r3" name="${esc(t.source.name)}" offset="${r(e.record)}" start="${r(e.source)}" duration="${r(e.length)}" tcFormat="NDF"""", e.source)
            cursor = e.recordEnd
        }
        if (t.frames > cursor) open("gap", """name="Gap" offset="${r(cursor)}" start="0s" duration="${r(t.frames - cursor)}"""", 0L)
        return lines
    }

    /** CMX 3600: one event per stretch of the source (picture and both sound channels), record time from 00:00:00:00. */
    fun edl(t: Timeline, graphicsName: String? = null): String = buildString {
        val fps = t.fps
        appendLine("TITLE: ${t.name.uppercase().take(TITLE)}")
        appendLine("FCM: NON-DROP FRAME")
        appendLine()
        val channels = if (t.hasVideo) "AA/V" else "AA  "
        t.events.forEachIndexed { i, e ->
            val n = (i + 1).toString().padStart(EVENT_DIGITS, '0')
            appendLine("$n  AX       $channels  C        ${tc(e.source, fps)} ${tc(e.sourceEnd, fps)} ${tc(e.record, fps)} ${tc(e.recordEnd, fps)}")
            appendLine("* FROM CLIP NAME: ${t.source.name}")
        }
        graphicsName?.let { appendLine("* GRAPHICS LAYER: $it ON V2 FROM ${tc(0L, fps)}") }
    }

    /** "HH:MM:SS:FF" at [fps], non-drop. */
    fun tc(frames: Long, fps: Int): String {
        val f = frames % fps
        val s = frames / fps
        return "${pad(s / HOUR)}:${pad(s / MINUTE % MINUTE)}:${pad(s % MINUTE)}:${pad(f)}"
    }

    fun frames(seconds: Float, fps: Int): Long = (seconds.toDouble() * fps).roundToLong()

    private fun clock(seconds: Float): String {
        val ms = (seconds.toDouble() * MS).roundToLong().coerceAtLeast(0)
        val s = ms / MS.toLong()
        return "${pad(s / HOUR)}:${pad(s / MINUTE % MINUTE)}:${pad(s % MINUTE)},${(ms % MS.toLong()).toString().padStart(3, '0')}"
    }

    /** A file next to the project file, for editors that relink by name. */
    private fun relative(name: String) = "./" + name.replace(" ", "%20")

    private fun pad(v: Long) = v.toString().padStart(2, '0')

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val MS = 1000f
    private const val MINUTE = 60L
    private const val HOUR = 3600L
    private const val MIN_CUE = 0.2f
    private const val MAX_WORDS = 8
    private const val TITLE = 60
    private const val EVENT_DIGITS = 3
}
