package io.trimio.engine.motion

import kotlin.math.abs

/**
 * App-side kashida justification for Persian/Arabic lines. SkParagraph shapes and measures but
 * does not justify, so a line that falls short of its box is stretched by inserting tatweel
 * (U+0640) at cursive joins. Every insertion is re-measured with the caller's measurer — the same
 * engine that will draw the line — so the stretch is observed, not estimated. The quality
 * ceilings follow W3C alreq conservatively: at most one tatweel per word, never across ZWNJ,
 * never at a line edge, and the openings are visited in a spatially even order.
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

    /** Hard ceiling on insertions per line, independent of how many words offer a join. */
    private const val MAX_ROUNDS = 32

    /** Advance model when no measurer is at hand: a tatweel is half an em. */
    private const val TATWEEL_UNITS = 0.5f

    /** Advance model when no measurer is at hand: a space is a third of an em. */
    private const val SPACE_UNITS = 0.3f

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
     * Stretches [line] toward [targetWidth] by inserting tatweel into eligible joins and
     * re-measuring every candidate with [measure] — the layout engine's own measurer, so the
     * width change is the one that will be drawn. The loop stops at [targetWidth] or at the
     * quality ceilings (one tatweel per word, [MAX_ROUNDS] total). Returns [line] unchanged when
     * it is not RTL or is already as wide as [targetWidth]. Deterministic — the same input and
     * measurer always yield the same line.
     */
    fun justifyToWidth(line: String, targetWidth: Float, measure: (String) -> Float): String {
        if (!isRtlText(line)) return line
        if (measure(line) >= targetWidth) return line
        return stretchTo(line, targetWidth, measure)
    }

    /**
     * Ratio form of [justifyToWidth]: [targetWidthRatio] is desired/current width (> 1 stretches).
     * Pass [measure] to re-measure with the engine that will draw the line; when it is null a
     * conservative advance model stands in (letters one em, tatweel half). Returns [line]
     * unchanged when not RTL or when [targetWidthRatio] is at most 1.02.
     */
    fun justifyWithKashida(
        line: String,
        targetWidthRatio: Float,
        measure: ((String) -> Float)? = null,
    ): String {
        if (targetWidthRatio <= RATIO_EPSILON) return line
        if (!isRtlText(line)) return line
        val meter = measure ?: ::estimateWidth
        val current = meter(line)
        if (current <= 0f) return line
        return stretchTo(line, current * targetWidthRatio, meter)
    }

    /**
     * Indices at which tatweel may be inserted (StringBuilder insert points) under the same
     * eligibility rules as [justifyToWidth], except the one-per-word policy — every legal join
     * is listed. Empty when the line is not RTL. For tests and telemetry.
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

    /**
     * The insert→measure→adjust loop: open the joins in a spatially even order, one tatweel at a
     * time, and re-measure after each until [measure] sees [targetWidth] or the ceilings are met.
     * Positions are tracked in the original string so earlier insertions never invalidate later
     * ones.
     */
    private fun stretchTo(line: String, targetWidth: Float, measure: (String) -> Float): String {
        val order = evenVisitOrder(preferredSlots(line))
        if (order.isEmpty()) return line
        val sb = StringBuilder(line)
        val inserted = mutableListOf<Int>()
        var i = 0
        while (i < order.size && inserted.size < MAX_ROUNDS && measure(sb.toString()) < targetWidth) {
            val orig = order[i]
            var at = orig
            for (done in inserted) if (done < orig) at++
            sb.insert(at, TATWEEL)
            inserted += orig
            i++
        }
        return sb.toString()
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

    /**
     * Visit order whose every prefix is spatially even: the middle slot first, then the medians of
     * each half. An early stop therefore still spreads the stretch across the line instead of
     * pouring it into the first words.
     */
    private fun evenVisitOrder(slots: List<Int>): List<Int> {
        if (slots.size <= 2) return slots
        val mid = slots.size / 2
        return listOf(slots[mid]) +
            evenVisitOrder(slots.subList(0, mid)) +
            evenVisitOrder(slots.subList(mid + 1, slots.size))
    }

    /** Advance model when no measurer is at hand: letters one unit, tatweel half, spaces a third. */
    private fun estimateWidth(s: String): Float {
        var units = 0f
        for (c in s) {
            units += when {
                c == TATWEEL -> TATWEEL_UNITS
                c.isWhitespace() -> SPACE_UNITS
                else -> 1f
            }
        }
        return units
    }
}
