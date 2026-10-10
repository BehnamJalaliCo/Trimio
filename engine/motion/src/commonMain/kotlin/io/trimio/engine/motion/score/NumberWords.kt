package io.trimio.engine.motion.score

import io.trimio.core.model.text.Numerals

/**
 * Finds spoken numbers in a transcript — digits or words, Persian or English ("پنج درصد",
 * "بیست و سه هزار", "5.2%", "twenty one thousand", "هزار نفر", "50k") — so the compiler can animate
 * them as counters. Speech recognisers split and slur numbers («سی صد», «پنجا», «چلا هشت»), so the
 * common slips are read too; a lone «نه» or «یه» is the word "no" or "a", not a number.
 */
object NumberWords {

    /**
     * A number in the words. [year] marks a calendar year («۱۴۰۱», «هزار و چهارصد و یک», 2024, «سال نود و
     * هفت»): written without a thousands separator and never counted up like an amount.
     */
    data class Found(val start: Int, val count: Int, val value: Double, val percent: Boolean, val decimals: Int, val year: Boolean = false)

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
    private val percentWords = setOf("درصد", "درصدی", "درصده", "درصدش", "درصدشو", "درصدو", "درصدرو", "percent", "percentage", "%", "٪")
    private val yearWords = setOf("سال", "سالِ", "در", "تو", "توی", "از", "تا", "in", "since", "by", "from", "until", "year")
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
    fun at(words: List<String>, i: Int): Found? = (digits(words, i) ?: spoken(words, i))?.let { it.copy(year = year(words, it)) }

    /**
     * A calendar year: a whole number of the Persian calendar's range said in Persian (1300–1499), or of
     * this and the last century (1900–2099); or two digits right after «سال» («سال نود و هفت»). An amount
     * («۱۵۰۰ تومن», "2,000 people") is never a year: the word after it is a unit, not a year's context.
     */
    private fun year(words: List<String>, f: Found): Boolean {
        if (f.percent || f.decimals > 0 || f.value != kotlin.math.floor(f.value)) return false
        val v = f.value.toLong()
        val before = words.getOrNull(f.start - 1)?.let(::normalize)
        val after = words.getOrNull(f.start + f.count)?.let(::normalize).orEmpty()
        val raw = words[f.start]
        val persian = raw.any { it in '\u0600'..'\u06FF' } || (before != null && before.any { it in '\u0600'..'\u06FF' })
        val grouped = ',' in Numerals.toLatin(raw) || '٬' in raw
        return when {
            grouped -> false
            v in 1900..2099 -> after !in AMOUNT_UNITS
            v in 1300..1499 -> persian && after !in AMOUNT_UNITS
            v in 10..99 -> before == "سال" && f.count >= 1 && after !in AMOUNT_UNITS
            else -> false
        }
    }

    /** Words after a number that make it an amount, not a year. */
    private val AMOUNT_UNITS = setOf(
        "تومن", "تومان", "ریال", "دلار", "تتر", "نفر", "تا", "متر", "کیلو", "گرم", "واحد", "هزار", "میلیون", "میلیارد", "سال", "ساله",
        "people", "dollars", "units", "users", "times", "steps", "kilos", "years", "km", "\$",
    )

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
        val percentAfter = !percentInline && percentAt(words, i + count) > 0
        val percent = percentInline || percentAfter
        return Found(i, if (percentAfter) count + percentAt(words, i + count) else count, value, percent, decimals)
    }

    private fun spoken(words: List<String>, i: Int): Found? {
        var total = 0L
        var group = 0L
        var j = i
        var any = false
        var lastWasNumber = false
        var afterThirty = false
        // The smallest part added so far: «چهل و دو» then «و چهل» starts a new number (a list, «۴۲ و ۴۳»).
        var smallest = Long.MAX_VALUE
        while (j < words.size) {
            val w = normalize(words[j])
            val unit = units[w]
            val scale = scales[w]
            val nextUnit = words.getOrNull(j + 1)?.let(::normalize)?.let { n -> units[n]?.toLong() ?: HUNDRED.takeIf { n in hundreds } }
            when {
                w in hundreds && any && group in 1..9 -> { group *= 100; smallest = group; lastWasNumber = true }
                // «سی صد» is سیصد split in two, not thirty hundreds.
                w in hundreds && afterThirty -> { group += SPLIT_THREE_HUNDRED; smallest = HUNDRED; lastWasNumber = true }
                // A lone «سد» (the recogniser's «صد») is a hundred only when a number follows: «سد و بیست و پنج».
                w in hundreds && !any && opensNumber(words, j) -> { group = HUNDRED; smallest = HUNDRED; any = true; lastWasNumber = true }
                unit != null && unit < smallest -> { group += unit; smallest = unit.toLong(); any = true; lastWasNumber = true }
                // A scale can open a number: «هزار نفر» is a thousand people.
                scale != null -> { total += maxOf(group, 1) * scale; group = 0; smallest = scale; any = true; lastWasNumber = true }
                w in joiners && any && lastWasNumber && nextUnit != null && nextUnit < smallest -> lastWasNumber = false
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
        val percent = percentAt(words, j) > 0
        j += percentAt(words, j)
        if (j - i == 1 && normalize(words[i]) in ambiguous) return null
        return Found(i, j - i, value, percent, decimals)
    }

    /** Words a percent sign takes at [j]: «درصد», "percent", "per cent" (2), or 0. */
    private fun percentAt(words: List<String>, j: Int): Int {
        val w = words.getOrNull(j)?.let(::normalize) ?: return 0
        return when {
            w in percentWords -> 1
            w == "per" && words.getOrNull(j + 1)?.let(::normalize) == "cent" -> 2
            else -> 0
        }
    }

    /** «سد» at [j] opens a number when «و» and a number, or a scale, follow it. */
    private fun opensNumber(words: List<String>, j: Int): Boolean {
        val next = words.getOrNull(j + 1)?.let(::normalize)
        val after = words.getOrNull(j + 2)?.let(::normalize)
        return next in scales || (next in joiners && (after in units || after in scales))
    }

    private const val HUNDRED = 100L
    private const val SPLIT_THREE_HUNDRED = 270
}
