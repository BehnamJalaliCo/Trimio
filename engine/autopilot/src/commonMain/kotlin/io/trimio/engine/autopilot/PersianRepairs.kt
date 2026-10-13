package io.trimio.engine.autopilot

/**
 * The recogniser's slips the one-letter rules of [PersianSpelling] cannot reach, each put right
 * only when the word as heard is unknown and the repair lands on a common word:
 *
 * - a written variant: «آ» heard «ا» in a word the list knows far better with it («کاراموز» → «کارآموز»);
 * - a long vowel heard that is not written («مودل» → «مدل», «دوشوار» → «دشوار»);
 * - two weak letters lost in a long word («فوقلاده» → «فوق‌العاده»);
 * - a comparative or superlative whose stem was misheard: no «‌تر/‌ترین» of the heard stem was ever
 *   written, but one of a near stem is common («قبیل‌ترین» → «قوی‌ترین», «دیگتر» → «دیرتر»);
 * - the next word's first letter heard twice («ریپور رو» → «ریپو رو»);
 * - a stem misheard under its endings: the stem repaired, the endings kept («دوشوارترین‌ها» → «دشوارترین‌ها»);
 * - a two-word term heard as near words, one of them no word at all («حوشه مسئولی» → «هوش مصنوعی»).
 */
internal class PersianRepairs(private val lex: PersianSpelling) {

    private fun count(w: String) = lex.count(w)

    /** A known word for the unknown [core] (followed by [next]), or null. */
    fun repair(core: String, next: String?): String? {
        if (core.length < MIN_LENGTH || known(core)) return null
        return whole(core, next) ?: underEndings(core)
    }

    /** [core] is a word as it stands: listed, protected, or a listed stem with endings. */
    fun known(core: String): Boolean = count(core) > 0 || Loanwords.protected(core) || stemmed(core) != null

    private fun whole(core: String, next: String?): String? =
        written(core) ?: echoed(core, next) ?: lostVowel(core) ?: lostWeak(core) ?: graded(core)

    /** «آ» for «ا»: the same letters, the list writes it with the madda. */
    fun written(core: String): String? {
        val options = core.indices.filter { core[it] == 'ا' }.map { core.substring(0, it) + 'آ' + core.substring(it + 1) }
        val best = options.maxByOrNull { count(it) } ?: return null
        return best.takeIf { count(it) >= KNOWN && count(it) >= VARIANT_RATIO * count(core) }
    }

    /** «ریپور رو»: the word, then the next word's first letter heard on its end. */
    private fun echoed(core: String, next: String?): String? {
        val first = next?.firstOrNull() ?: return null
        val cut = core.dropLast(1)
        return cut.takeIf { core.last() == first && cut.length >= MIN_STEM && count(core) == 0 && (count(cut) >= KNOWN_IN_DOC || Loanwords.protected(cut)) }
    }

    /** A long vowel the recogniser wrote that the word does not have. */
    private fun lostVowel(core: String): String? {
        if (count(core) > 0) return null
        val options = (1 until core.length).filter { core[it] in LONG_VOWELS }.map { core.removeRange(it, it + 1) }
        // A written long vowel is usually heard right: only a very common word without it is taken.
        return best(options.filter { it.length >= MIN_STEM && count(it) >= COMMON })
    }

    /** Two weak letters («ا», «ع», «و», «ی», «ه») lost in a long word, written back as the list writes it. */
    private fun lostWeak(core: String): String? {
        if (count(core) > 0 || core.length < LONG_WORD) return null
        val key = PersianSpelling.soundKey(core)
        val found = mutableSetOf<String>()
        for (i in 1..key.length) for (a in WEAK) {
            val once = key.substring(0, i) + a + key.substring(i)
            for (j in i + 1..once.length) for (b in WEAK) found += lex.sounding(once.substring(0, j) + b + once.substring(j))
        }
        return best(found.toList())
    }

    /** The most common of [options] when it is common and clearly ahead of the next. */
    private fun best(options: List<String>): String? {
        val ranked = options.distinct().filter { count(it) >= KNOWN }.sortedByDescending { count(it) }
        val top = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.let { count(it) } ?: 0
        return top.takeIf { count(it) >= MARGIN * second }
    }

    /**
     * «قبیل‌ترین»: a comparative or superlative nobody writes of the heard stem, where a near stem
     * (two slips at most, one for a short stem, its first letter kept) is common in that degree.
     */
    private fun graded(core: String): String? {
        val bare = core.replace(ZWNJ_S, "")
        val suffix = GRADES.firstOrNull { bare.endsWith(it) && bare.length - it.length >= MIN_STEM } ?: return null
        val stem = bare.dropLast(suffix.length)
        if (forms(stem, suffix).any { count(it) > 0 }) return null
        val key = PersianSpelling.soundKey(stem)
        val reach = if (stem.length <= SHORT_STEM) 1 else 2
        // Each near stem once, in the form the list writes best (the half-space form when it is common enough).
        val near = lex.graded(suffix).filter { (s, _) -> s != stem && s.first() == stem.first() && distance(PersianSpelling.soundKey(s), key) <= reach }
            .groupBy({ it.first }, { it.second }).map { (_, words) -> words.sortedByDescending { w -> count(w) * (if (ZWNJ in w) HALF_SPACE_FAVOUR else 1) }.first() to words.sumOf { count(it) } }
            .sortedByDescending { it.second }
        val top = near.firstOrNull() ?: return null
        val second = near.getOrNull(1)?.second ?: 0
        return top.first.takeIf { top.second >= KNOWN && top.second >= MARGIN * second }
    }

    private fun forms(stem: String, suffix: String) = listOf(stem + suffix, stem + ZWNJ + suffix)

    /**
     * The stem under a chain of endings (at most [MAX_ENDINGS]) when the stem is listed: «مصرفتونو» →
     * «مصرف». A one-letter ending («ت», «ه», «ی») needs a common stem and comes once at most (so «پیتیت»
     * is not «پیت» + «ی» + «ت», nor «حوشه» «حوش» + «ه»); a degree («تر», «ترین») needs a stem that is
     * written in that degree («قبیل‌ترین» is not «قبیل» + «ترین»).
     */
    fun stemmed(core: String): String? {
        var w = core.trimEnd(ZWNJ)
        var single = false
        var left = MAX_ENDINGS
        while (left-- > 0) {
            val e = ending(w, single) ?: break
            single = single || e.length == 1
            w = w.dropLast(e.length).trimEnd(ZWNJ)
            // A one-letter ending on a less common stem stands when no word sounds like the whole («آیلتسم»), not when one does («حوشه», «هوشه»).
            val enough = if (single && homophone(core)) KNOWN_ONE else KNOWN_STEM
            // A verb's prefix counts too: «میرسید» is «می» + «رسید».
            val verb = VERB_PREFIXES.any { w.startsWith(it) && count(w.removePrefix(it).trimStart(ZWNJ)) >= enough }
            if (count(w) >= enough || Loanwords.protected(w) || verb) return w
        }
        return null
    }

    /** Another word the list has that sounds exactly like [core]. */
    private fun homophone(core: String) = lex.sounding(core).any { it.replace(ZWNJ_S, "") != core.replace(ZWNJ_S, "") && count(it) >= KNOWN_IN_DOC }

    /** The ending [w] may lose: never a second one-letter ending, never a degree its stem is not written in. */
    private fun ending(w: String, single: Boolean): String? {
        val e = ENDINGS.firstOrNull { w.endsWith(it) && w.length - it.length >= MIN_STEM } ?: return null
        val stem = w.dropLast(e.length).trimEnd(ZWNJ)
        val ungraded = e in GRADES && GRADES.none { g -> forms(stem, g).any { count(it) > 0 } }
        return e.takeIf { !ungraded && !(e.length == 1 && single) }
    }

    /**
     * A misheard stem repaired under its endings, the endings kept as heard. Endings of two letters
     * or more may chain («ترین» + «ها»); a one-letter ending is taken only first and alone (a name
     * like «سانته» is never cut down to a common «سن»).
     */
    private fun underEndings(core: String): String? {
        var w = core.trimEnd(ZWNJ)
        for (round in 0 until MAX_ENDINGS) {
            val e = ENDINGS.firstOrNull { w.endsWith(it) && w.length - it.length >= MIN_STEM } ?: return null
            if (e.length == 1 && round > 0) return null
            w = w.dropLast(e.length).trimEnd(ZWNJ)
            val tail = core.substring(w.length).trimStart(ZWNJ)
            val fixed = if (w.length >= MIN_REPAIRED) (whole(w, null) ?: lex.slip(w))?.takeIf { it != w } else null
            if (fixed != null) return fixed + glue(fixed, tail) + tail
            if (e.length == 1) return null
        }
        return null
    }

    private fun glue(stem: String, tail: String) = if (stem.endsWith('ه') || tail.startsWith("ها") || tail.startsWith("تر")) ZWNJ_S else ""

    /**
     * Two heard words that are a common two-word term ([PHRASES]) said with a slip, one of them no
     * word at all («حوشه مسئولی» → «هوش مصنوعی»): within a slip for every three sounds of the term.
     */
    fun phrase(a: String, b: String): Pair<String, String>? {
        val words = a.isNotEmpty() && b.isNotEmpty()
        if (!words || (known(a) && known(b))) return null
        val start = PersianSpelling.soundKey(a).first()
        val heard = PersianSpelling.soundKey(a) + " " + PersianSpelling.soundKey(b)
        return PHRASES.filter { (x, _) -> PersianSpelling.soundKey(x).first() == start }
            .map { p -> p to distance(heard, PersianSpelling.soundKey(p.first) + " " + PersianSpelling.soundKey(p.second)) }
            .filter { (p, d) -> d in 1..(p.first.length + p.second.length + 1) / SOUNDS_PER_SLIP }
            .minByOrNull { it.second }?.first
    }

    /**
     * Two heard words that are one compound whose silent «ه» and final «ی» went unheard («صرف جوی»
     * → «صرفه‌جویی»): the compound common, and far likelier than the two words meeting by chance.
     */
    fun compound(a: String, b: String): String? {
        // Two halves the list already joins as heard («نا امید» → «ناامید») are [PersianJoins]' to join.
        val plainJoin = maxOf(count(a + b), count(a + ZWNJ + b))
        if (a.length < MIN_STEM || b.length < MIN_STEM || plainJoin >= KNOWN) return null
        val heads = listOf(a + "ه", a)
        val tails = listOf(b + "ی", b)
        val options = heads.flatMap { h -> tails.flatMap { t -> listOf(h + ZWNJ + t, h + t) } }.filter { it.replace(ZWNJ_S, "") != a + b }
        val best = options.maxByOrNull { count(it) } ?: return null
        val chance = count(a).toDouble() * count(b) / lex.total
        return best.takeIf { count(it) >= KNOWN && count(it) >= COMPOUND_ODDS * chance }
    }

    companion object {
        private const val COMPOUND_ODDS = 100.0
        private const val COMMON = 2000
        private const val ZWNJ = '‌'
        private const val ZWNJ_S = "‌"
        private const val MIN_LENGTH = 3
        private const val MIN_STEM = 2
        /** A stem shorter than this is not repaired under endings: too many words are one slip from it. */
        private const val MIN_REPAIRED = 4
        private const val SHORT_STEM = 3
        private const val LONG_WORD = 6
        private const val KNOWN = 300
        private const val KNOWN_STEM = 50
        /** A stem under a one-letter ending must be this common: a rare stem plus a letter is likelier a slip. */
        private const val KNOWN_ONE = 1000
        private const val KNOWN_IN_DOC = 20
        private const val VARIANT_RATIO = 5
        private const val MARGIN = 3
        private const val MAX_ENDINGS = 3
        private const val SOUNDS_PER_SLIP = 3
        private const val HALF_SPACE_FAVOUR = 4
        private const val LONG_VOWELS = "ویا"
        private const val WEAK = "اعویه"
        val GRADES = listOf("ترین", "تر")
        private val VERB_PREFIXES = listOf("نمی", "می")

        /** Endings a stem carries in speech: plurals, degrees, clitics, the object marker («و», «رو»). */
        private val ENDINGS = listOf(
            "هاتون", "هاشون", "هامون", "هایی", "های", "ها", "ترین", "تر", "تون", "شون", "مون", "اتون", "اشون", "امون", "اش", "ات", "ام", "ای",
            "رو", "تو", "شو", "مو", "یه", "ست", "و", "ی", "ت", "ش", "م", "ه", "ا",
        )

        /** Two-word terms a talk is full of, as they are written. */
        private val PHRASES = listOf(
            "هوش" to "مصنوعی", "ارز" to "دیجیتال", "کیف" to "پول", "شبکه" to "اجتماعی", "سرمایه" to "اولیه", "یادگیری" to "ماشین",
            "زبان" to "برنامه‌نویسی", "بازار" to "سرمایه", "سود" to "روزانه", "حد" to "ضرر", "حد" to "سود", "تحلیل" to "تکنیکال",
            "مدل" to "زبانی", "نقطه" to "ورود", "فضای" to "مجازی", "کسب" to "درآمد", "تولید" to "محتوا",
        )

        fun distance(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
