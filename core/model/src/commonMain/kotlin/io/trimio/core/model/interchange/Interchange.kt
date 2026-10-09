package io.trimio.core.model.interchange

import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.EditMap
import io.trimio.core.model.timeline.Timeline
import kotlin.math.roundToLong

/** The user's original file, as other editors need to relink it. */
data class SourceMedia(
    /** file:// URL (or content:// on Android, relinked by the user on import). */
    val url: String,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val hasVideo: Boolean,
)

/**
 * Hand-off to professional editors. The edit (cuts) and the captions travel; Trimio's motion
 * graphics stay a rendered layer (export the video and stack it, or re-render after changes).
 *  - [fcpxml]: Final Cut Pro and DaVinci Resolve (FCPXML 1.11) — cuts on the spine, captions as titles.
 *  - [xmeml]: Adobe Premiere Pro (FCP7 XML) — cuts on video and audio tracks.
 *  - [srt]: captions, line by line, for Premiere's caption track or any player.
 * All times are snapped to the timeline's frame grid so cuts land on frames.
 */
object Interchange {

    fun fcpxml(timeline: Timeline, source: SourceMedia, projectName: String): String {
        val fps = timeline.canvas.frameRate
        val edit = EditMap.of(timeline, source.durationMs)
        fun t(ms: Long) = "${frames(ms, fps)}/${fps}s"
        val captions = lines(timeline)
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<!DOCTYPE fcpxml>")
            appendLine("""<fcpxml version="1.11">""")
            appendLine("  <resources>")
            appendLine("""    <format id="r1" name="Trimio${timeline.canvas.widthPx}x${timeline.canvas.heightPx}p$fps" frameDuration="1/${fps}s" width="${timeline.canvas.widthPx}" height="${timeline.canvas.heightPx}"/>""")
            appendLine("""    <asset id="r2" name="${esc(source.name)}" start="0s" duration="${t(source.durationMs)}" hasVideo="${if (source.hasVideo) 1 else 0}" hasAudio="1" format="r1" audioSources="1" audioChannels="2" audioRate="48000">""")
            appendLine("""      <media-rep kind="original-media" src="${esc(source.url)}"/>""")
            appendLine("    </asset>")
            appendLine("""    <effect id="r3" name="Basic Title" uid=".../Titles.localized/Bumper:Opener.localized/Basic Title.localized/Basic Title.moti"/>""")
            appendLine("  </resources>")
            appendLine("  <library>")
            appendLine("""    <event name="Trimio">""")
            appendLine("""      <project name="${esc(projectName)}">""")
            appendLine("""        <sequence format="r1" duration="${t(timeline.durationMs)}" tcStart="0s" tcFormat="NDF" audioLayout="stereo" audioRate="48k">""")
            appendLine("          <spine>")
            var outCursor = 0L
            var styleIndex = 0
            for (segment in edit.kept) {
                val length = segment.durationMs
                appendLine("""            <asset-clip ref="r2" name="${esc(source.name)}" offset="${t(outCursor)}" start="${t(segment.startMs)}" duration="${t(length)}" format="r1" tcFormat="NDF">""")
                // Captions starting in this segment ride on lane 1, timed in the asset's (source) time.
                for ((range, text) in captions.filter { it.first.startMs >= outCursor && it.first.startMs < outCursor + length }) {
                    val localStart = segment.startMs + (range.startMs - outCursor)
                    val id = "ts${++styleIndex}"
                    appendLine("""              <title ref="r3" lane="1" name="${esc(text.take(24))}" offset="${t(localStart)}" duration="${t(range.durationMs)}" start="0s">""")
                    appendLine("""                <text><text-style ref="$id">${esc(text)}</text-style></text>""")
                    appendLine("""                <text-style-def id="$id"><text-style font="Vazirmatn" fontSize="72" fontFace="Bold" fontColor="1 1 1 1" alignment="center"/></text-style-def>""")
                    appendLine("              </title>")
                }
                appendLine("            </asset-clip>")
                outCursor += length
            }
            appendLine("          </spine>")
            appendLine("        </sequence>")
            appendLine("      </project>")
            appendLine("    </event>")
            appendLine("  </library>")
            appendLine("</fcpxml>")
        }
    }

    fun xmeml(timeline: Timeline, source: SourceMedia, projectName: String): String {
        val fps = timeline.canvas.frameRate
        val edit = EditMap.of(timeline, source.durationMs)
        fun f(ms: Long) = frames(ms, fps)
        val rate = "<rate><timebase>$fps</timebase><ntsc>FALSE</ntsc></rate>"
        fun clipItems(kind: String) = buildString {
            var out = 0L
            edit.kept.forEachIndexed { i, segment ->
                val start = f(out)
                val end = f(out + segment.durationMs)
                appendLine("""          <clipitem id="$kind-$i">""")
                appendLine("            <name>${esc(source.name)}</name>")
                appendLine("            <enabled>TRUE</enabled>")
                appendLine("            <duration>${f(source.durationMs)}</duration>")
                appendLine("            $rate")
                appendLine("            <start>$start</start><end>$end</end>")
                appendLine("            <in>${f(segment.startMs)}</in><out>${f(segment.startMs) + (end - start)}</out>")
                if (i == 0 && kind == "v") {
                    appendLine("""            <file id="file-1">""")
                    appendLine("              <name>${esc(source.name)}</name>")
                    appendLine("              <pathurl>${esc(source.url)}</pathurl>")
                    appendLine("              $rate")
                    appendLine("              <duration>${f(source.durationMs)}</duration>")
                    appendLine("              <media>${if (source.hasVideo) "<video><samplecharacteristics><width>${source.width}</width><height>${source.height}</height></samplecharacteristics></video>" else ""}<audio><channelcount>2</channelcount></audio></media>")
                    appendLine("            </file>")
                } else {
                    appendLine("""            <file id="file-1"/>""")
                }
                appendLine("          </clipitem>")
                out += segment.durationMs
            }
        }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<!DOCTYPE xmeml>")
            appendLine("""<xmeml version="5">""")
            appendLine("""  <sequence id="trimio-sequence">""")
            appendLine("    <name>${esc(projectName)}</name>")
            appendLine("    <duration>${f(timeline.durationMs)}</duration>")
            appendLine("    $rate")
            appendLine("    <media>")
            appendLine("      <video>")
            appendLine("        <format><samplecharacteristics><width>${timeline.canvas.widthPx}</width><height>${timeline.canvas.heightPx}</height>$rate</samplecharacteristics></format>")
            appendLine("        <track>")
            if (source.hasVideo) append(clipItems("v"))
            appendLine("        </track>")
            appendLine("      </video>")
            appendLine("      <audio>")
            appendLine("        <track>")
            append(clipItems(if (source.hasVideo) "a" else "v"))
            appendLine("        </track>")
            appendLine("      </audio>")
            appendLine("    </media>")
            appendLine("  </sequence>")
            appendLine("</xmeml>")
        }
    }

    /** SubRip captions, one cue per on-screen line, in output (edited) time. */
    fun srt(timeline: Timeline): String = buildString {
        lines(timeline).forEachIndexed { i, (range, text) ->
            appendLine(i + 1)
            appendLine("${clock(range.startMs)} --> ${clock(range.endMs)}")
            appendLine(text)
            appendLine()
        }
    }

    /** Caption groups as (range, text), in reading order. */
    private fun lines(timeline: Timeline): List<Pair<TimeRange, String>> =
        timeline.clipsOf<CaptionClip>().groupBy { it.group }.values
            .map { words -> words.sortedBy { it.range.startMs } }
            .sortedBy { it.first().range.startMs }
            .map { words -> TimeRange(words.first().range.startMs, words.maxOf { it.range.endMs }) to words.joinToString(" ") { it.text } }

    private fun frames(ms: Long, fps: Int): Long = (ms * fps / 1000.0).roundToLong()

    private fun clock(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val milli = ms % 1000
        return "${pad(h)}:${pad(m)}:${pad(s)},${milli.toString().padStart(3, '0')}"
    }

    private fun pad(v: Long) = v.toString().padStart(2, '0')

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
