package io.trimio.engine.asr

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Word

/** A second recogniser (Qwen3-ASR through llama.cpp's audio input): the text it hears in a stretch of audio, or null. */
fun interface SecondEar {
    suspend fun hear(audio: PcmAudio, language: Language): String?
}

/** Where the second ear heard something else: the word's index, what it heard instead, and how sure whisper was. */
data class Alternative(val index: Int, val heard: String, val confidence: Float)

/**
 * A second opinion on the words whisper was unsure of (ROVER-style, at word level): the second
 * recogniser's text is aligned to whisper's words by edit distance over normalised tokens, and every
 * low-confidence word it hears differently becomes an [Alternative]. Nothing is replaced here: the
 * proofreader decides (it accepts a change only with the lexicon, the glossary or the brief behind it).
 */
object SecondOpinion {
    /** Whisper's word probability below which a second opinion is asked for. */
    const val UNSURE = 0.6f

    /** Spans (ms, padded by [padMs], merged when close) around the unsure words: what to send to the second ear. */
    fun spans(words: List<Word>, threshold: Float = UNSURE, padMs: Long = 600): List<LongRange> {
        val out = ArrayList<LongRange>()
        for (w in words.filter { it.confidence < threshold }) {
            val r = (w.range.startMs - padMs).coerceAtLeast(0)..(w.range.endMs + padMs)
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last) out[out.lastIndex] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    /** Alternatives for [words] given the second recogniser's [heard] text over the same stretch. */
    fun candidates(words: List<Word>, heard: String, threshold: Float = UNSURE): List<Alternative> {
        val a = words.map { AsrScore.tokens(it.text).joinToString("") }
        val rawB = heard.split(' ', '\n').filter { it.isNotBlank() }
        val b = rawB.map { AsrScore.tokens(it).joinToString("") }
        val n = a.size
        val m = b.size
        val cost = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) cost[i][0] = i
        for (j in 0..m) cost[0][j] = j
        for (i in 1..n) for (j in 1..m) cost[i][j] = minOf(cost[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1, cost[i - 1][j] + 1, cost[i][j - 1] + 1)
        val out = ArrayList<Alternative>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val same = a[i - 1] == b[j - 1]
            when {
                cost[i][j] == cost[i - 1][j - 1] + if (same) 0 else 1 -> {
                    if (!same && words[i - 1].confidence < threshold) out += Alternative(i - 1, rawB[j - 1], words[i - 1].confidence)
                    i--
                    j--
                }
                cost[i][j] == cost[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
        return out.reversed()
    }
}
