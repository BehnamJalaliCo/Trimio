package io.trimio.engine.retrieval

import io.trimio.core.model.text.TextKey

/**
 * Search terms of a Persian or English text, the same for documents and queries: [TextKey.terms]
 * (folded letters and digits, half-space words joined and split, separated prefixes and suffixes
 * joined), stop words dropped, and each word's light stem added beside it — conservatively, so a
 * stem never turns one word into another («ماهی» stays «ماهی»).
 */
object Terms {
    fun of(text: String): List<String> {
        val out = ArrayList<String>()
        for (t in TextKey.terms(text)) {
            if (t in STOP || t.length < 2 && t.none(Char::isDigit)) continue
            out += t
            val s = stem(t)
            if (s != t && s.length >= 2) out += s
        }
        return out
    }

    /** The word without a plural, comparative or possessive suffix (Persian) or a plural or -ing (English). */
    fun stem(w: String): String {
        if (w.any { it in '؀'..'ۿ' }) {
            for (suffix in FA_SUFFIXES) if (w.length - suffix.length >= MIN_FA_STEM && w.endsWith(suffix)) return w.dropLast(suffix.length)
            return w
        }
        return when {
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
            w.length > 4 && (w.endsWith("ses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes")) -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
            else -> w
        }
    }

    /** Longest first, so «هایی» goes before «ها». */
    private val FA_SUFFIXES = listOf("هایشان", "هایتان", "هایمان", "هایی", "هایم", "هایت", "هایش", "های", "ترین", "ها", "تر")
    private const val MIN_FA_STEM = 2

    private val STOP: Set<String> = (
        "و در به از که این آن را با برای تا یا هم اما اگر چون پس نیز یک می نمی هست است بود شد شده باشد کرد کند کنیم کنید " +
            "من تو او ما شما آنها ایشان خود همه هر چه چی چیزی خیلی رو ها های ای یه دیگه دیگر اینکه اون این ها " +
            "a an the of and or in on at to for with by from is are was were be been it its this that these those as " +
            "so but if then than too very can will just not no do does did have has had i you he she we they my your our"
        ).split(' ').filter { it.isNotEmpty() }.map(TextKey::of).toSet()
}
