package io.trimio.core.model.text

/**
 * Canonical matching keys for Persian/English words and phrases. Spelling variants map to one
 * form for matching only (the original text is always what is shown):
 *  - Arabic letter forms → Persian: ي ى ئ → ی, ك → ک, ؤ → و, ة ۀ → ه, أ إ آ ٱ → ا;
 *  - digits ۰–۹ and ٠–٩ → 0–9;
 *  - diacritics (harakat, superscript alef, hamza marks), tatweel, ZWJ, LRM/RLM and BOM dropped;
 *  - case folded.
 *
 * [of] is the compact key of a whole word or phrase (spaces and half-spaces removed: «می‌روم»,
 * «می روم» and «میروم» are one key). [phrase], [words] and [terms] keep word boundaries, for
 * BM25-style search: a half-space (ZWNJ) word is indexed both joined («میروم») and split
 * («می», «روم»), and a prefix or suffix written apart («می روم», «کتاب ها») also yields the
 * joined word, so the three spellings people type all meet.
 */
object TextKey {
    const val ZWNJ = '‌'

    /** The compact key: folded letters and digits only (plus `%` and `$`), no spaces. */
    fun of(text: String): String = buildString(text.length) {
        for (raw in text.lowercase()) {
            val ch = fold(raw) ?: continue
            if (ch.isLetterOrDigit() || ch == '%' || ch == '$') append(ch)
        }
    }

    /**
     * [ch] as it is matched, or null when it is dropped (marks and invisible controls). Spaces,
     * punctuation and ZWNJ pass through: callers decide what separates words.
     */
    fun fold(ch: Char): Char? = when (ch) {
        'ي', 'ى', 'ئ' -> 'ی'
        'ك' -> 'ک'
        'ؤ' -> 'و'
        'ة', 'ۀ', 'ە' -> 'ه'
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        in '۰'..'۹' -> '0' + (ch - '۰')
        in '٠'..'٩' -> '0' + (ch - '٠')
        '‍', '‎', '‏', '﻿', 'ـ' -> null
        in 'ً'..'ٟ', 'ٰ' -> null
        ' ', ' ', ' ', '\t', '\n', '\r' -> ' '
        else -> ch
    }

    /**
     * Folded, lower-case text with word boundaries kept: punctuation becomes a space, runs of
     * spaces collapse to one, repeated half-spaces collapse to one, and a half-space next to a
     * space (or at a word's edge) is dropped.
     */
    fun phrase(text: String): String {
        val out = StringBuilder(text.length)
        for (raw in text.lowercase()) {
            val ch = fold(raw) ?: continue
            when {
                ch == ZWNJ -> if (out.isNotEmpty() && out.last() != ' ' && out.last() != ZWNJ) out.append(ZWNJ)
                ch.isLetterOrDigit() || ch == '%' || ch == '$' -> out.append(ch)
                else -> {
                    while (out.isNotEmpty() && out.last() == ZWNJ) out.setLength(out.length - 1)
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                }
            }
        }
        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == ZWNJ)) out.setLength(out.length - 1)
        return out.toString()
    }

    /** The words of [text] ([phrase] split at spaces), each joined (half-spaces removed). */
    fun words(text: String): List<String> = phrase(text).split(' ').filter { it.isNotEmpty() }.map { it.replace(ZWNJ.toString(), "") }

    /**
     * Search terms of [text], in order: every word joined; the parts of a half-space word
     * («می‌روم» → «میروم», «می», «روم»); and the joined word of a verb prefix or a suffix written
     * apart («می روم» → «میروم», «کتاب ها» → «کتابها»). Duplicates are kept: term frequency counts.
     */
    fun terms(text: String): List<String> {
        val raw = phrase(text).split(' ').filter { it.isNotEmpty() }
        val joined = raw.map { it.replace(ZWNJ.toString(), "") }
        val out = ArrayList<String>(raw.size * 2)
        for (i in raw.indices) {
            out += joined[i]
            if (ZWNJ in raw[i]) raw[i].split(ZWNJ).filter { it.isNotEmpty() }.forEach { out += it }
            val next = joined.getOrNull(i + 1) ?: continue
            if (affixed(joined[i], next)) out += joined[i] + next
        }
        return out
    }

    /** Verb prefixes Persian joins with a half-space. */
    private val PREFIXES = setOf("می", "نمی", "بی")

    /** Suffixes Persian joins with a half-space (plurals, comparatives, possessives). */
    private val SUFFIXES = setOf(
        "ها", "های", "هایی", "هایم", "هایت", "هایش", "هایمان", "هایتان", "هایشان", "تر", "ترین", "ای", "اش", "ام", "ات",
    )

    private fun persian(w: String) = w.isNotEmpty() && w.all { it in '؀'..'ۿ' }

    /** Two Persian words of which one is a prefix or suffix Persian joins with a half-space. */
    private fun affixed(first: String, second: String): Boolean {
        val joins = first in PREFIXES || second in SUFFIXES
        return joins && persian(first) && persian(second)
    }
}
