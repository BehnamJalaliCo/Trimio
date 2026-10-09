package io.trimio.core.model.transcript

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import kotlinx.serialization.Serializable

/** One spoken word with its aligned timing on the source media. */
@Serializable
data class Word(
    val text: String,
    val range: TimeRange,
    /** Recogniser confidence, 0..1. Low-confidence words are highlighted in the transcript editor. */
    val confidence: Float = 1f,
    val language: Language,
    /** Prosodic emphasis from pitch/energy analysis, 0..1. Filled by the analysis stage. */
    val emphasis: Float = 0f,
) {
    init {
        require(confidence in 0f..1f) { "confidence out of range: $confidence" }
        require(emphasis in 0f..1f) { "emphasis out of range: $emphasis" }
    }

    val endsSentence: Boolean get() = text.isNotEmpty() && text.last() in SENTENCE_END

    companion object {
        private val SENTENCE_END = setOf('.', '!', '?', '؟', '…')
    }
}

@Serializable
data class Transcript(
    /** Dominant language of the recording; individual words may differ. */
    val language: Language,
    val words: List<Word>,
) {
    init {
        require(words.zipWithNext().all { (a, b) -> a.range.startMs <= b.range.startMs }) {
            "words must be ordered by start time"
        }
    }

    val text: String get() = words.joinToString(" ") { it.text }

    /**
     * Splits words into caption-sized lines. A line breaks at sentence punctuation, at a pause
     * longer than [pauseMs], or when it reaches [maxWords].
     */
    fun lines(pauseMs: Long = 450, maxWords: Int = 4): List<List<Word>> {
        require(maxWords > 0)
        val result = mutableListOf<List<Word>>()
        var current = mutableListOf<Word>()
        for ((index, word) in words.withIndex()) {
            current += word
            val next = words.getOrNull(index + 1)
            val pauseBreak = next != null && next.range.startMs - word.range.endMs > pauseMs
            if (word.endsSentence || pauseBreak || current.size >= maxWords) {
                result += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    /** Silent gaps longer than [minGapMs] between words — candidates for jump cuts. */
    fun silences(minGapMs: Long, totalMs: Long): List<TimeRange> {
        val gaps = mutableListOf<TimeRange>()
        var cursor = 0L
        for (word in words) {
            if (word.range.startMs - cursor >= minGapMs) gaps += TimeRange(cursor, word.range.startMs)
            cursor = maxOf(cursor, word.range.endMs)
        }
        if (totalMs - cursor >= minGapMs) gaps += TimeRange(cursor, totalMs)
        return gaps
    }
}
