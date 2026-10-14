package io.trimio.engine.autopilot.longform

import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.motion.score.Words
import kotlinx.serialization.Serializable
import kotlin.math.max

/** A chapter of a long video: where it starts (seconds, the source's clock) and its title. */
@Serializable
data class Chapter(val at: Float, val title: String)

/**
 * Chapters for a long video, by YouTube's rules (docs/research/YOUTUBE_LONGFORM.md): the first at 0:00, at least
 * three, each at least ten seconds, listed in the description as `M:SS title` (`H:MM:SS` past an hour). A chapter
 * starts at a line where the speaker paused and the words change: what is said in the minute after shares little
 * with the minute before. A video too short for three chapters gets none (YouTube would hide them anyway).
 */
object YouTubeChapters {
    /** The first word of each chapter (0 first), or none. */
    fun starts(words: List<Word>, lines: List<Lines.Line>, duration: Float): List<Int> {
        if (duration < MIN_VIDEO || lines.size < MIN_CHAPTERS) return emptyList()
        val length = (duration / TARGET_COUNT).coerceIn(MIN_LENGTH, MAX_LENGTH)
        val most = (duration / length).toInt().coerceAtLeast(MIN_CHAPTERS)
        val at = { k: Int -> words[lines[k].first].range.startMs / MS }
        val scored = (1 until lines.size).map { k -> k to score(words, lines, k, at(k)) }
            .filter { (k, _) -> at(k) >= MIN_LENGTH && duration - at(k) >= MIN_LENGTH }
            .sortedByDescending { it.second }
        val chosen = mutableListOf<Int>()
        for ((k, _) in scored) {
            if (chosen.size + 1 >= most) break
            if (chosen.all { c -> kotlin.math.abs(at(c) - at(k)) >= length * SPACING }) chosen += k
        }
        val starts = listOf(0) + chosen.sorted().map { lines[it].first }
        return if (starts.size >= MIN_CHAPTERS) starts else emptyList()
    }

    /** The description block: one `M:SS title` line per chapter, ready to paste. */
    fun description(chapters: List<Chapter>): String = chapters.joinToString("\n") { "${stamp(it.at)} ${it.title}" }

    /** "0:00", "12:05", "1:02:09": YouTube's own stamps (Latin digits, as it reads them). */
    fun stamp(seconds: Float): String {
        val s = seconds.toInt().coerceAtLeast(0)
        val h = s / HOUR
        val m = s % HOUR / MINUTE
        val sec = (s % MINUTE).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
    }

    /** A title when no model writes one: the chapter's opening words, up to [TITLE_WORDS], ending on a content word. */
    fun rulesTitle(words: List<Word>): String {
        val head = words.map { it.text }.filterNot { it.isBlank() || Words.isFiller(it) }.take(TITLE_WORDS)
        val end = head.indexOfLast { Words.isContent(it) }
        return (if (end >= 1) head.take(end + 1) else head).joinToString(" ")
    }

    /** A turn: the pause before line [k] and how much the words after it differ from the words before. */
    private fun score(words: List<Word>, lines: List<Lines.Line>, k: Int, at: Float): Float {
        val pause = (words[lines[k].first].range.startMs - words[lines[k - 1].last].range.endMs) / MS
        val before = bag(words, at - WINDOW, at)
        val after = bag(words, at, at + WINDOW)
        val union = (before + after).size
        val shift = if (union == 0) 0f else 1f - (before intersect after).size / union.toFloat()
        return shift + pause.coerceIn(0f, MAX_PAUSE) / MAX_PAUSE * PAUSE_WEIGHT
    }

    private fun bag(words: List<Word>, from: Float, to: Float): Set<String> =
        words.filter { it.range.startMs / MS in max(0f, from)..to }.map { it.text }.filter(Words::isContent).toSet()

    private const val MS = 1000f
    private const val MIN_VIDEO = 90f
    private const val MIN_CHAPTERS = 3
    private const val TARGET_COUNT = 8f
    private const val MIN_LENGTH = 40f
    private const val MAX_LENGTH = 300f
    private const val SPACING = 0.6f
    private const val WINDOW = 45f
    private const val MAX_PAUSE = 2f
    private const val PAUSE_WEIGHT = 0.5f
    private const val TITLE_WORDS = 5
    private const val HOUR = 3600
    private const val MINUTE = 60
}
