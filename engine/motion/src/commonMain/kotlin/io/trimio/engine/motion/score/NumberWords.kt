package io.trimio.engine.motion.score

import io.trimio.core.model.text.Numerals

/**
 * Finds spoken numbers in a transcript — digits or words, Persian or English ("پنج درصد",
 * "بیست و سه هزار", "5.2%", "twenty one thousand", "هزار نفر", "50k") — so the compiler can animate
 * them as counters. Speech recognisers split and slur numbers («سی صد», «پنجا», «چلا هشت»), so the
 * common slips are read too; a lone «نه» or «یه» is the word "no" or "a", not a number.
 */
object NumberWords {

    data class Found(val start: Int, val count: Int, val value: Double, val percent: Boolean, val decimals: Int)

    private val units = mapOf(
        "صفر" to 0, "یک" to 1, "یه" to 1, "دو" to 2, "سه" to 3, "چهار" to 4, "پنج" to 5, "شش" to 6, "شیش" to 6, "هفت" to 7, "هشت" to 8, "نه" to 9,
        "ده" to 10, "یازده" to 11, "دوازده" to 12, "سیزده" to 13, "چهارده" to 14, "پانزده" to 15, "پونزده" to 15, "شانزده" to 16, "شونزده" to 16,
        "هفده" to 17, "هجده" to 18, "هیجده" to 18, "نوزده" to 19, "بیست" to 20, "سی" to 30, "چهل" to 40, "پنجاه" to 50, "شصت" to 60,
        "هفتاد" to 70, "هشتاد" to 80, "نود" to 90, "صد" to 100, "یکصد" to 100, "دویست" to 200, "سیصد" to 300, "چهارصد" to 400,
        "پانصد" to 500, "پونصد" to 500, "ششصد" to 600, "هفتصد" to 700, "هشتصد" to 800, "نهصد" to 900,
        // Recogniser slips: «پنجا» (پنجاه), «چل»/«چلا» (چهل، «چهل و» run together).
        "پنجا" to 50, "چل" to 40, "چلا" to 40, "شیصد" to 600,
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90, "hundred" to 100,
    )
    private val scales = mapOf("هزار" to 1_000L, "میلیون" to 1_000_000L, "میلیارد" to 1_000_000_000L, "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val joiners = setOf("و", "and")
    private val hundreds = setOf("hundred", "صد", "سد")
    private val percentWords = setOf("درصد", "درصدی", "percent", "%", "٪")
    private val halfWords = setOf("نیم")

    /** Everyday words that are also numbers: alone they mean "no", "a", "one of". */
    private val ambiguous = setOf("نه", "یه", "یک", "one")

    fun normalize(word: String): String = Numerals.toLatin(word)
        .lowercase()
        .replace('ي', 'ی').replace('ك', 'ک').replace("‌", "")
        .trim { it.isWhitespace() || it in ".,،؛:!?؟«»\"'()[]" }

    /** All numbers in [words] (in order, non-overlapping). */
    fun findAll(words: List<String>): List<Found> {
        val out = mutableListOf<Found>()
        var i = 0
        while (i < words.size) {
            val f = at(words, i)
            if (f != null) { out += f; i += f.count } else i++
        }
        return out
    }

    /** A number starting at word [i], or null. */
    fun at(words: List<String>, i: Int): Found? = digits(words, i) ?: spoken(words, i)

    private fun digits(words: List<String>, i: Int): Found? {
        val raw = Numerals.toLatin(words[i]).replace('٫', '.').replace('٬', ',')
        val percentInline = raw.contains('%') || raw.contains('٪')
        val cleaned = raw.trim { !it.isDigit() && it != '.' && it != '+' && it != '-' }.replace(",", "")
        var value = cleaned.toDoubleOrNull() ?: return null
        val decimals = cleaned.substringAfter('.', "").length
        // "50k", and "50 هزار" (digits with a spoken scale).
        if (raw.trimEnd('.', '،', ',').lowercase().endsWith('k')) value *= 1_000
        var count = 1
        scales[words.getOrNull(i + 1)?.let(::normalize)]?.let { value *= it; count++ }
        val next = words.getOrNull(i + count)?.let(::normalize)
        val percent = percentInline || next in percentWords
        return Found(i, if (!percentInline && next in percentWords) count + 1 else count, value, percent, decimals)
    }

    private fun spoken(words: List<String>, i: Int): Found? {
        var total = 0L
        var group = 0L
        var j = i
        var any = false
        var lastWasNumber = false
        var afterThirty = false
        while (j < words.size) {
            val w = normalize(words[j])
            val unit = units[w]
            val scale = scales[w]
            when {
                w in hundreds && any && group in 1..9 -> { group *= 100; lastWasNumber = true }
                // «سی صد» is سیصد split in two, not thirty hundreds.
                w in hundreds && afterThirty -> { group += SPLIT_THREE_HUNDRED; lastWasNumber = true }
                unit != null -> { group += unit; any = true; lastWasNumber = true }
                // A scale can open a number: «هزار نفر» is a thousand people.
                scale != null -> { total += maxOf(group, 1) * scale; group = 0; any = true; lastWasNumber = true }
                w in joiners && any && lastWasNumber && words.getOrNull(j + 1)?.let { normalize(it) in units } == true -> lastWasNumber = false
                else -> break
            }
            afterThirty = w == "سی"
            j++
        }
        if (!any) return null
        var value = (total + group).toDouble()
        var decimals = 0
        // "پنج و نیم" → 5.5
        if (j + 1 < words.size && normalize(words[j]) in joiners && normalize(words[j + 1]) in halfWords) { value += 0.5; decimals = 1; j += 2 }
        val percent = words.getOrNull(j)?.let { normalize(it) in percentWords } == true
        if (percent) j++
        if (j - i == 1 && normalize(words[i]) in ambiguous) return null
        return Found(i, j - i, value, percent, decimals)
    }

    private const val SPLIT_THREE_HUNDRED = 270
}
