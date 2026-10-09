package io.trimio.engine.asr

import io.trimio.core.model.text.Language
import io.trimio.core.model.text.ScriptDetector
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word

/**
 * Turns recogniser tokens into caption-ready words:
 * 1. byte-level BPE tokens are concatenated per word (a leading space starts a new word), and only
 *    then decoded as UTF-8, so Persian letters split across tokens come out intact;
 * 2. punctuation-only tokens attach to the previous word;
 * 3. Persian words are normalised and affixes joined with ZWNJ, merging their timings.
 */
object WordAssembler {

    fun assemble(tokens: List<RecognizedToken>, fallback: Language): List<Word> {
        val raw = mutableListOf<RawWord>()
        for (t in tokens) {
            if (t.bytes.isEmpty()) continue
            val startsWord = t.bytes[0] == SPACE
            val bytes = if (startsWord) t.bytes.copyOfRange(1, t.bytes.size) else t.bytes
            val current = raw.lastOrNull()
            if (startsWord || current == null) {
                raw += RawWord(bytes.toMutableList(), t.startMs, t.endMs, mutableListOf(t.probability))
            } else {
                current.bytes += bytes.toList()
                current.endMs = maxOf(current.endMs, t.endMs)
                current.probabilities += t.probability
            }
        }

        // Decode and drop empties; a word that is only punctuation is glued to the previous one.
        val decoded = mutableListOf<RawWord>()
        for (w in raw) {
            w.text = PersianNormalizer.normalizeChars(w.bytes.toByteArray().decodeToString().trim())
            if (w.text.isEmpty()) continue
            val prev = decoded.lastOrNull()
            if (prev != null && w.text.all { it in PUNCTUATION }) {
                prev.text += w.text
                prev.endMs = maxOf(prev.endMs, w.endMs)
            } else {
                decoded += w
            }
        }

        val groups = PersianNormalizer.joinAffixes(decoded.map { it.text })
        return groups.map { range ->
            val parts = decoded.subList(range.first, range.last + 1)
            val text = if (parts.size == 1) parts[0].text else PersianNormalizer.joinWithZwnj(parts.map { it.text })
            val start = parts.first().startMs
            val end = maxOf(parts.last().endMs, start + MIN_WORD_MS)
            val probs = parts.flatMap { it.probabilities }
            val language = ScriptDetector.detect(text, fallback)
            Word(
                text = if (language == Language.Persian) PersianNormalizer.punctuation(text) else text,
                range = TimeRange(start, end),
                confidence = probs.average().toFloat().coerceIn(0f, 1f),
                language = language,
            )
        }.let(::enforceMonotonic)
    }

    /** Recogniser timestamps can overlap or go backwards at segment edges; make them strictly ordered. */
    private fun enforceMonotonic(words: List<Word>): List<Word> {
        var cursor = 0L
        return words.map { w ->
            val start = maxOf(w.range.startMs, cursor)
            val end = maxOf(w.range.endMs, start + MIN_WORD_MS)
            cursor = end
            w.copy(range = TimeRange(start, end))
        }
    }

    private class RawWord(
        val bytes: MutableList<Byte>,
        val startMs: Long,
        var endMs: Long,
        val probabilities: MutableList<Float>,
        var text: String = "",
    )

    private const val SPACE: Byte = 0x20
    private const val MIN_WORD_MS = 60L
    private val PUNCTUATION = setOf('.', ',', '!', '?', ':', ';', '،', '؛', '؟', '…', '"', '«', '»', ')', '(')
}
