package io.trimio.engine.director

import io.trimio.core.model.text.Numerals

/**
 * Reads spoken numbers out of transcript words, in Persian and English, written as digits
 * ("۵", "12.5", "40%") or as words ("بیست و پنج", "two hundred"), so the Director can animate the
 * exact figure the speaker says with a counter.
 */
object NumberWords {

    data class Match(
        val value: Double,
        /** Number of transcript words the number (and its unit, if any) spans. */
        val length: Int,
        val unit: Unit?,
    )

    enum class Unit { Percent, Dollar, Toman }

    private val persian = mapOf(
        "صفر" to 0, "یک" to 1, "یه" to 1, "دو" to 2, "سه" to 3, "چهار" to 4, "پنج" to 5, "شش" to 6, "شیش" to 6, "هفت" to 7, "هشت" to 8, "نه" to 9,
        "ده" to 10, "یازده" to 11, "دوازده" to 12, "سیزده" to 13, "چهارده" to 14, "پانزده" to 15, "پونزده" to 15, "شانزده" to 16, "شونزده" to 16,
        "هفده" to 17, "هیفده" to 17, "هجده" to 18, "هیجده" to 18, "نوزده" to 19, "بیست" to 20, "سی" to 30, "چهل" to 40, "پنجاه" to 50,
        "شصت" to 60, "هفتاد" to 70, "هشتاد" to 80, "نود" to 90, "صد" to 100, "یکصد" to 100, "دویست" to 200, "سیصد" to 300, "چهارصد" to 400,
        "پانصد" to 500, "پونصد" to 500, "ششصد" to 600, "هفتصد" to 700, "هشتصد" to 800, "نهصد" to 900,
    )
    private val english = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90, "hundred" to 100,
    )
    private val scales = mapOf("هزار" to 1_000.0, "میلیون" to 1e6, "میلیارد" to 1e9, "thousand" to 1_000.0, "million" to 1e6, "billion" to 1e9)
    private val connectors = setOf("و", "and")

    /** Words that are also everyday words ("نه" = no, "یک" = a): only numbers when a unit or scale follows. */
    private val ambiguous = setOf("نه", "یک", "یه", "سی", "one")

    private val digits = Regex("^[-+]?\\d+([.,٫]\\d+)?%?$")

    /** The number starting at [start], or null. [words] are raw transcript texts. */
    fun at(words: List<String>, start: Int): Match? {
        val first = clean(words.getOrNull(start) ?: return null)
        if (first.isEmpty()) return null

        if (digits.matches(first)) {
            var value = first.trimEnd('%').replace(',', '.').replace('٫', '.').toDoubleOrNull() ?: return null
            var length = 1
            clean(words.getOrNull(start + 1).orEmpty()).let { s -> scales[s]?.let { value *= it; length++ } }
            val unit = if (first.endsWith('%')) Unit.Percent else unitOf(words.getOrNull(start + length))
            return Match(value, length + if (unit != null && !first.endsWith('%')) 1 else 0, unit)
        }

        var total = 0.0
        var group = 0.0
        var i = start
        var consumed = 0
        var sawScale = false
        while (i < words.size) {
            val w = clean(words[i])
            val small = persian[w] ?: english[w]
            when {
                small == 100 && group in 1.0..9.0 -> group *= 100 // "two hundred"
                small != null -> group += small
                scales[w] != null -> { // "هزار و پانصد" starts with its scale
                    total += maxOf(group, 1.0) * scales.getValue(w)
                    group = 0.0
                    sawScale = true
                }
                w in connectors && consumed > 0 && i + 1 < words.size &&
                    clean(words[i + 1]).let { persian[it] ?: english[it] } != null -> Unit
                else -> break
            }
            consumed = i - start + 1
            i++
        }
        if (consumed == 0) return null
        // A trailing connector is not part of the number.
        while (consumed > 0 && clean(words[start + consumed - 1]) in connectors) consumed--
        val value = total + group
        val unit = unitOf(words.getOrNull(start + consumed))
        if (consumed == 1 && !sawScale && unit == null && clean(words[start]) in ambiguous) return null
        return Match(value, consumed + if (unit != null) 1 else 0, unit)
    }

    private fun unitOf(word: String?): Unit? {
        val w = Lexicon.norm(word ?: return null)
        return when (w) {
            in Lexicon.percent -> Unit.Percent
            in Lexicon.dollar -> Unit.Dollar
            in Lexicon.toman -> Unit.Toman
            else -> null
        }
    }

    private fun clean(word: String): String = Lexicon.norm(Numerals.toLatin(word)).let { n ->
        // norm keeps '%' and digits; restore the decimal point it strips.
        val raw = Numerals.toLatin(word).trim().trimEnd('.', '،', ',', '!', '?', '؟', ':')
        if (raw.isNotEmpty() && raw.first().isDigit() && digits.matches(raw.replace("٪", "%"))) raw.replace("٪", "%") else n
    }
}
