package io.trimio.engine.motion

import kotlin.math.abs

/**
 * App-side kashida justification for Persian/Arabic lines. SkParagraph shapes and measures but
 * does not justify, so a line that falls short of its box is stretched by inserting tatweel
 * (U+0640) at cursive joins. This is a pragmatic approximation of W3C alreq: real joining is
 * done by the shaper — this only places raw tatweel at positions that already form a join.
 * The result must be re-measured; width change is approximate.
 */
object Kashida {

    /** Tatweel (kashida). */
    private const val TATWEEL = '\u0640'

    /** Zero-width non-joiner: breaks the cursive run, so compounds like «می‌رود» stay unsplit. */
    private const val ZWNJ = '\u200C'

    /** Words shorter than this never take a tatweel — they read as stretched noise. */
    private const val MIN_WORD_LETTERS = 3

    /** Below this the stretch is noise; leave the line alone. */
    private const val RATIO_EPSILON = 1.02f

    /** Line cap: one tatweel per ~12 characters, always at least one. */
    private const val LINE_CAP_DIVISOR = 12

    /** A tatweel advances roughly half an em; used to estimate how many inserts the ratio wants. */
    private const val TATWEEL_LETTER_WIDTH = 0.5f

    /**
     * Arabic letters that join only to the preceding letter (alef, dal, reh, waw and kin):
     * they end a cursive run and cannot open a join forward.
     */
    private val RIGHT_JOINING = setOf(
        'ا', 'آ', 'أ', 'إ', 'ؤ', 'ة', 'د', 'ذ', 'ر', 'ز', 'و', 'ژ', 'ى', 'ۀ',
    )

    /** Arabic letters that join on neither side. */
    private val NON_JOINING = setOf('ء')

    /**
     * True when the first strong character is Arabic-script (U+0600–U+06FF, U+0750–U+077F,
     * U+08A0–U+08FF). Digits, punctuation and marks are weak/neutral and do not decide.
     */
    fun isRtlText(s: String): Boolean {
        for (c in s) {
            if (isArabicLetter(c)) return true
            if (c.isLetter()) return false
        }
        return false
    }

    /**
     * Inserts tatweel into eligible joins of [line] so it stretches toward [targetWidthRatio]
     * (desired/current width; > 1 means stretch). Conservative on purpose: at most one tatweel
     * per word, words under [MIN_WORD_LETTERS] letters and ZWNJ compounds skipped, never at a
     * line edge or beside a space, total capped at max(1, length/12). Deterministic — the same
     * input always yields the same positions. Returns [line] unchanged when not RTL or when
     * [targetWidthRatio] is at most 1.02.
     */
    fun justifyWithKashida(line: String, targetWidthRatio: Float): String {
        if (targetWidthRatio <= RATIO_EPSILON) return line
        if (!isRtlText(line)) return line
        val slots = preferredSlots(line)
        if (slots.isEmpty()) return line
        val count = insertionCount(line, targetWidthRatio).coerceAtMost(slots.size)
        if (count <= 0) return line
        // Insert back to front so earlier indices stay valid.
        val sb = StringBuilder(line)
        for (i in pickEvenly(slots, count).sortedDescending()) sb.insert(i, TATWEEL)
        return sb.toString()
    }

    /**
     * Indices at which tatweel may be inserted (StringBuilder insert points) under the same
     * eligibility rules as [justifyWithKashida], except the one-per-word policy — every legal
     * join is listed. Empty when the line is not RTL. For tests and telemetry.
     */
    fun stretchableSpans(line: String): List<Int> {
        if (!isRtlText(line)) return emptyList()
        return words(line).flatMap { wordSpans(line, it) }
    }

    /** Internal so [RtlLayout] can share the Unicode block test without duplicating ranges. */
    internal fun isArabicLetter(c: Char): Boolean {
        val code = c.code
        return (code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0x08A0..0x08FF) && c.isLetter()
    }

    private fun joinsForward(c: Char): Boolean =
        isArabicLetter(c) && c !in RIGHT_JOINING && c !in NON_JOINING

    private fun joinsBackward(c: Char): Boolean =
        isArabicLetter(c) && c !in NON_JOINING

    /** Whitespace-separated words as (start, endExclusive) ranges in [line]. */
    private fun words(line: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < line.length) {
            if (line[i].isWhitespace()) {
                i++
                continue
            }
            val start = i
            while (i < line.length && !line[i].isWhitespace()) i++
            result += start to i
        }
        return result
    }

    /**
     * Insertion indices inside one word. Skips ZWNJ compounds (never split «می‌رود»), words that
     * already carry tatweel (one per word total), short words, and anything but a dual→(dual|right)
     * cursive join — those sit strictly inside the word, so never at a line edge or beside a space.
     */
    private fun wordSpans(line: String, word: Pair<Int, Int>): List<Int> {
        val (start, end) = word
        val text = line.substring(start, end)
        if (text.contains(ZWNJ) || text.contains(TATWEEL)) return emptyList()
        if (text.count { isArabicLetter(it) } < MIN_WORD_LETTERS) return emptyList()
        val spans = mutableListOf<Int>()
        for (i in 0 until text.length - 1) {
            if (joinsForward(text[i]) && joinsBackward(text[i + 1])) spans += start + i + 1
        }
        return spans
    }

    /** One preferred span per eligible word: the join closest to the word's middle. */
    private fun preferredSlots(line: String): List<Int> = words(line).mapNotNull { word ->
        val spans = wordSpans(line, word)
        if (spans.isEmpty()) null
        else spans.minByOrNull { abs(it - (word.first + (word.second - word.first) / 2)) }
    }

    private fun insertionCount(line: String, ratio: Float): Int {
        val cap = maxOf(1, line.length / LINE_CAP_DIVISOR)
        val letters = line.count { isArabicLetter(it) }
        val demand = ((ratio - 1f) * letters / TATWEEL_LETTER_WIDTH).toInt().coerceAtLeast(1)
        return minOf(demand, cap)
    }

    /** Deterministic even spread across [slots], preferring the middle when count is one. */
    private fun pickEvenly(slots: List<Int>, count: Int): List<Int> {
        if (count >= slots.size) return slots
        return (0 until count).map { i -> slots[(i * slots.size + slots.size / 2) / count] }
    }
}
