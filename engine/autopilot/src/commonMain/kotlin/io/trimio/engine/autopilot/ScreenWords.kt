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
        return known(c) && repairs.written(c) == null
    }

    /** The Persian words of [text] that may not be shown as spelled. */
    fun unknown(text: String): List<String> = text.split(' ').filter { !shows(it) }.map(::core).distinct()

    /** A better spelling of [token] the word list is sure of (a written variant, a repaired slip), or null. */
    fun respelled(token: String, next: String? = null): String? {
        val c = core(token)
        if (c.isEmpty() || !persian(c) || plain(c) in names) return null
        Loanwords.alias(c)?.let { return token.replace(c, it) }
        val fixed = repairs.written(c) ?: if (known(c)) null else repairs.repair(c, next?.let(::core))
        return fixed?.let { token.replace(c, it) }
    }

    private fun known(c: String): Boolean {
        if (lexicon.count(c) > 0 || repairs.known(c)) return true
        // The half-spaces as the list writes them, or not at all («می‌کنه», «میکنه»).
        val joined = c.replace(ZWNJ.toString(), "")
        if (lexicon.count(joined) > 0 || repairs.known(joined)) return true
        // A verb prefix before a known form: «می‌رسونه».
        val parts = c.split(ZWNJ).filter { it.isNotEmpty() }
        return parts.size == 2 && parts[0] in PREFIXES && (lexicon.count(parts[1]) > 0 || repairs.known(parts[1]))
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

        fun core(w: String) = w.trim { !it.isLetterOrDigit() && it != ZWNJ }.trim(ZWNJ)
        fun plain(w: String) = PersianSpelling.normalize(w).replace(ZWNJ.toString(), "")
        fun persian(w: String) = w.any { it in 'ؠ'..'ۿ' && it.isLetter() }
    }
}
