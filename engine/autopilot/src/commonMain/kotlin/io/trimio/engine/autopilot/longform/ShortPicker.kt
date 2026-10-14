package io.trimio.engine.autopilot.longform

import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.motion.score.NumberWords

/**
 * Shorts from a long video: the [COUNT] strongest stretches of [MIN_SECONDS]–[MAX_SECONDS] that start and end on
 * a sentence, never overlapping. Strong means said with weight (prosody), carrying figures, and asking or
 * promising something; a stretch that starts mid-thought (a pronoun, «و») scores lower.
 */
object ShortPicker {
    fun pick(words: List<Word>, lines: List<Lines.Line>, chapters: List<Chapter>): List<ShortClip> {
        if (words.isEmpty()) return emptyList()
        val texts = words.map { it.text }
        val starts = lines.map { it.first }.filter { it == 0 || words[it - 1].endsSentence }
        val candidates = starts.mapNotNull { s ->
            val from = words[s].range.startMs / MS
            val end = (s + 1 until words.size).lastOrNull { j ->
                val t = words[j].range.endMs / MS - from
                t in MIN_SECONDS..MAX_SECONDS && words[j].endsSentence
            } ?: return@mapNotNull null
            Triple(s, end, strength(words, texts, s, end))
        }.sortedByDescending { it.third }
        val chosen = mutableListOf<Triple<Int, Int, Float>>()
        for (c in candidates) {
            if (chosen.size >= COUNT) break
            if (chosen.none { o -> c.first <= o.second && o.first <= c.second }) chosen += c
        }
        return chosen.sortedBy { it.first }.map { (s, e, _) ->
            val from = words[s].range.startMs / MS
            val chapter = chapters.lastOrNull { it.at <= from }?.title
            ShortClip(from, words[e].range.endMs / MS, chapter ?: YouTubeChapters.rulesTitle(words.subList(s, e + 1)))
        }
    }

    private fun strength(words: List<Word>, texts: List<String>, s: Int, e: Int): Float {
        val span = s..e
        val weight = span.sumOf { words[it].emphasis.toDouble() }.toFloat() / (e - s + 1)
        val figures = NumberWords.findAll(texts.subList(s, e + 1)).size.coerceAtMost(MAX_FIGURES) * FIGURE
        val ask = if (span.any { texts[it].endsWith('؟') || texts[it].endsWith('?') }) ASK else 0f
        val midThought = if (texts[s] in OPENERS) MID_THOUGHT else 0f
        return weight + figures + ask - midThought
    }

    private const val MS = 1000f
    private const val COUNT = 3
    private const val MIN_SECONDS = 30f
    private const val MAX_SECONDS = 60f
    private const val MAX_FIGURES = 3
    private const val FIGURE = 0.15f
    private const val ASK = 0.2f
    private const val MID_THOUGHT = 0.3f
    private val OPENERS = setOf("و", "ولی", "اما", "این", "اون", "آن", "که", "پس", "and", "but", "so", "this", "that", "it")
}
