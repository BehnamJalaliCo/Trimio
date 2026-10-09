package io.trimio.engine.asr

/**
 * Cleans recogniser output into standard Persian orthography, the way an editor would before
 * captions go on screen:
 *  - Arabic letter variants → Persian (ي → ی, ك → ک, ى → ی, ۀ kept, ة → ه)
 *  - diacritics (tashkeel) and kashida (tatweel) removed
 *  - verb prefix «می/نمی» and plural/possessive suffixes («ها، های، هایی، تر، ترین») joined with ZWNJ
 *  - Persian punctuation (، ؛ ؟) and no space before punctuation
 *
 * Works per word as well as on whole strings, so it is safe to apply to streamed words.
 */
object PersianNormalizer {
    const val ZWNJ = '\u200C'

    private val letterMap = mapOf(
        'ي' to 'ی', 'ى' to 'ی', 'ك' to 'ک', 'ة' to 'ه',
        '٠' to '۰', '١' to '۱', '٢' to '۲', '٣' to '۳', '٤' to '۴',
        '٥' to '۵', '٦' to '۶', '٧' to '۷', '٨' to '۸', '٩' to '۹',
    )

    private fun isDiacritic(c: Char) = c in '\u064B'..'\u0652' || c == '\u0670' || c == '\u0640' // tatweel

    /** Character-level cleanup only; never changes word boundaries. */
    fun normalizeChars(text: String): String = buildString(text.length) {
        for (c in text) {
            if (isDiacritic(c)) continue
            append(letterMap[c] ?: c)
        }
    }

    private val prefixes = setOf("می", "نمی")
    private val suffixes = setOf("ها", "های", "هایی", "هایم", "هایت", "هایش", "هایمان", "هایتان", "هایشان", "تر", "ترین")

    /**
     * Joins the space-separated words of a sentence where Persian writes a half-space.
     * Returns the merged words; [mergedFrom] reports which input indices each output covers, so word
     * timings can be merged with them.
     */
    fun joinAffixes(words: List<String>): List<IntRange> {
        val groups = mutableListOf<IntRange>()
        var i = 0
        while (i < words.size) {
            var end = i
            // «می خواهم» → «می\u200Cخواهم»
            if (words[i] in prefixes && i + 1 < words.size && isPersianWord(words[i + 1])) end = i + 1
            // «کتاب ها» → «کتاب\u200Cها», «بزرگ تر» → «بزرگ\u200Cتر»
            while (end + 1 < words.size && stripPunct(words[end + 1]) in suffixes && isPersianWord(words[end])) end++
            groups += i..end
            i = end + 1
        }
        return groups
    }

    fun joinWithZwnj(parts: List<String>): String = parts.joinToString(ZWNJ.toString())

    /** Persian punctuation and spacing for a full caption line. */
    fun punctuation(text: String): String {
        var t = text.replace(',', '،').replace(';', '؛')
        // A question mark after Persian text becomes the Persian question mark.
        t = Regex("([\\u0600-\\u06FF])\\s*\\?").replace(t) { it.groupValues[1] + "؟" }
        t = Regex("\\s+([،؛؟!.:])").replace(t) { it.groupValues[1] }
        return t.replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun stripPunct(w: String) = w.trimEnd('،', '؛', '؟', '!', '.', ':', ',', '?')

    private fun isPersianWord(w: String) = w.any { it in '\u0600'..'ۿ' }
}
