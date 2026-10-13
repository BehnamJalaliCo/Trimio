package io.trimio.engine.autopilot

/**
 * The on-screen word check, independent of what was heard: a Persian word may be shown only when
 * the word list knows it (as written, with its half-spaces, or as a known stem with its endings),
 * it is a protected term ([Loanwords]), or it is a name — one the understanding or the brief names,
 * or one the speech itself introduces as a name («مدل فیبل», «اسمش سانته»). A word the recogniser
 * misheard is none of these («کاراموز» is written «کارآموز»; «تذکیر», «پیتیت» are no words), so it
 * never reaches a graphic or a caption as heard.
 */
internal class ScreenWords(private val lexicon: PersianSpelling, names: Collection<String> = emptyList()) {
    private val repairs = PersianRepairs(lexicon)
    private val names: Set<String> = names.flatMap { it.split(' ') }.map { plain(core(it)) }.filter { it.isNotEmpty() }.toSet()

    /** Whether [token] (punctuation aside) may be shown as it is spelled. */
    fun shows(token: String): Boolean {
        val c = core(token)
        if (c.isEmpty() || !persian(c)) return true
        if (plain(c) in names) return true
        return (known(c) && repairs.written(c) == null) || probableName(c)
    }

    /**
     * An unknown word no repair reaches and no listed word is one slip from («رایتینگ», «آیلتس»):
     * a name or a loanword the list lacks, shown as said. A slip of a word has a word next to it
     * («تذکیر» … «تذکر», «پیتیت» … «پیتی») and is not shown.
     */
    private fun probableName(c: String): Boolean {
        val p = plain(c)
        if (p.length < NAME_LENGTH || repairs.repair(c, null) != null || lexicon.slip(p) != null) return false
        return !lexicon.near(p)
    }

    /** The Persian words of [text] that may not be shown as spelled. */
    fun unknown(text: String): List<String> = text.split(' ').filter { !shows(it) }.map(::core).distinct()

    /** A better spelling of [token] the word list is sure of (a written variant, a repaired slip), or null. */
    fun respelled(token: String, next: String? = null): String? {
        val c = core(token)
        if (c.isEmpty() || !persian(c) || plain(c) in names) return null
        if (lexicon.count(c) == 0) Loanwords.alias(c)?.let { return token.replace(c, it) }
        val fixed = repairs.written(c) ?: if (known(c)) null else repairs.repair(c, next?.let(::core))
        return fixed?.let { token.replace(c, it) }
    }

    private fun known(c: String): Boolean {
        if (lexicon.count(c) > 0 || repairs.known(c)) return true
        // The half-spaces as the list writes them, or not at all («می‌کنه», «میکنه»).
        val joined = c.replace(ZWNJ.toString(), "")
        if (lexicon.count(joined) > 0 || repairs.known(joined)) return true
        // Half-spaced parts, each a word or a verb prefix («می‌رسونه», «نقطه‌به‌نقطه», «سی‌تایی»); a degree only of a stem
        // written in it (never «قبیل‌ترین»).
        val parts = c.split(ZWNJ).filter { it.isNotEmpty() }
        val degree = parts.size > 1 && parts.last() in PersianRepairs.GRADES && !repairs.known(parts.dropLast(1).joinToString("") + parts.last())
        return parts.size > 1 && !degree && parts.withIndex().all { (i, p) -> lexicon.count(p) > 0 || repairs.known(p) || (i == 0 && p in PREFIXES) }
    }

    companion object {
        private const val ZWNJ = '‌'
        private val PREFIXES = setOf("می", "نمی", "بی")

        /** Words that introduce a name: «مدل X», «اسمش X», «اپ X», «ربات X». */
        private val NAMING = setOf(
            "مدل", "مودل", "اسمش", "اسم", "اپ", "اپلیکیشن", "برنامه", "ربات", "بات", "سایت", "شرکت", "کانال", "پیج", "صرافی", "پلتفرم", "نسخه",
            "ابزار", "سرویس", "پروژه", "آقای", "خانم", "برند", "کوین", "توکن",
        )
        private const val REPEATED = 3

        /**
         * Unknown words the speech itself gives as names: right after a naming word («مدل فیبل»),
         * or said [REPEATED] times or more the same way; with [known] telling words the list has.
         */
        fun namesIn(texts: List<String>, known: (String) -> Boolean): Set<String> {
            val cores = texts.map(::core)
            val counts = cores.groupingBy { plain(it) }.eachCount()
            return cores.indices.mapNotNull { i ->
                val c = cores[i]
                val named = i > 0 && plain(cores[i - 1]) in NAMING
                c.takeIf { persian(it) && !known(it) && it.length >= MIN_NAME && (named || (counts[plain(it)] ?: 0) >= REPEATED) }
            }.toSet()
        }

        private const val MIN_NAME = 3
        private const val NAME_LENGTH = 5

        fun core(w: String) = w.trim { !it.isLetterOrDigit() && it != ZWNJ }.trim(ZWNJ)
        fun plain(w: String) = PersianSpelling.normalize(w).replace(ZWNJ.toString(), "")
        fun persian(w: String) = w.any { it in 'ؠ'..'ۿ' && it.isLetter() }
    }
}
