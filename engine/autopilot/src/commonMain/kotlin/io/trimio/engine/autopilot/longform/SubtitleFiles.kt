package io.trimio.engine.autopilot.longform

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.Words

/**
 * The subtitles as files YouTube takes (Studio → Subtitles → upload): SubRip (`.srt`) and WebVTT (`.vtt`), cut into
 * the same lines the video shows ([Words.captionLines], at most [MAX_WORDS] words), on the source's clock (a
 * long-form edit never cuts, so the times are the upload's own). Hesitations and blanked words are left out.
 */
object SubtitleFiles {
    data class Cue(val from: Float, val to: Float, val text: String)

    fun cues(words: List<Word>): List<Cue> {
        val said = words.filterNot { it.text.isBlank() || Words.isFiller(it.text) }
        val lines = Words.captionLines(said, MAX_WORDS)
        return lines.mapIndexed { i, line ->
            val next = lines.getOrNull(i + 1)?.first()?.range?.startMs?.div(MS)
            val end = (line.last().range.endMs / MS + TAIL).let { if (next != null) minOf(it, next - GAP) else it }
            Cue(line.first().range.startMs / MS, end, line.joinToString(" ") { it.text })
        }
    }

    fun srt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n').append(time(c.from, ',')).append(" --> ").append(time(c.to, ',')).append('\n').append(c.text).append("\n\n")
        }
    }

    fun vtt(cues: List<Cue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { c -> append(time(c.from, '.')).append(" --> ").append(time(c.to, '.')).append('\n').append(c.text).append("\n\n") }
    }

    /** "00:01:02,345" (SRT) or "00:01:02.345" (VTT). */
    private fun time(seconds: Float, mark: Char): String {
        val ms = (seconds * MS).toLong().coerceAtLeast(0)
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "${two(ms / HOUR_MS)}:${two(ms % HOUR_MS / MINUTE_MS)}:${two(ms % MINUTE_MS / SECOND_MS)}$mark${(ms % SECOND_MS).toString().padStart(3, '0')}"
    }

    const val MAX_WORDS = 12
    private const val MS = 1000f
    private const val TAIL = 0.35f
    private const val GAP = 0.03f
    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000L
    private const val SECOND_MS = 1_000L
}
