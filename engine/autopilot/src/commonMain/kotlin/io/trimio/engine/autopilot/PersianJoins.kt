package io.trimio.engine.autopilot

import kotlin.math.ln
import kotlin.math.min

/**
 * Where Persian words begin and end, as the word list ([PersianSpelling]) writes them. The recogniser
 * loses half-spaces («میدیم», «هفتهم»), turns them into spaces («نور گیر», «خیلی هاتون», «نا امید»),
 * runs short words together («کهدیگه», «ازدست») and splits prefixes off; these rules put the
 * boundaries back without touching a word the list knows as written («همینجا» stays when the list
 * writes it so, «تنها» never becomes «تن‌ها», «ولی عصر» is "but in the evening" unless a street precedes).
 */
internal class PersianJoins(private val lex: PersianSpelling) {

    private fun count(w: String) = lex.count(w)

    /**
     * A half-space put back inside one word, at a morpheme the list separates:
     * - the verb prefix «می/نمی» («میدیم» → «می‌دیم»), when the word is a verb (its «ب» form is common);
     * - plural «ها/های» and comparative «تر/ترین» («طبیعیتر» → «طبیعی‌تر»), as often as the list writes them;
     * - a clitic after a final «ه» («هفتهم» → «هفته‌م», «آشپزخونهاش» → «آشپزخونه‌اش»);
     * - «همین/همون/هیچ» before a word («هیچوقت» → «هیچ‌وقت»), when the list writes that form at all.
     */
    fun halfSpace(w: String): String? {
        if (ZWNJ in w) return null
        return verb(w) ?: suffixed(w) ?: afterHe(w) ?: determined(w) ?: variant(w)
    }

    /** «پسانداز» → «پس‌انداز», «چایخوری» → «چای‌خوری»: the list writes these letters with a half-space, often enough. */
    private fun variant(w: String): String? = lex.sounding(w).filter { ZWNJ in it && it.replace(ZWNJ_S, "") == w }
        .maxByOrNull { count(it) }?.takeIf { count(it) >= PLACE && count(it) * VARIANT_RATIO >= count(w) }

    private fun verb(w: String): String? {
        val prefix = VERB_PREFIXES.firstOrNull { w.startsWith(it) && w.length - it.length >= 2 } ?: return null
        val rest = w.substring(prefix.length)
        val zw = prefix + ZWNJ + rest
        val listed = count(zw) > 0 || (count(w) == 0 && count(rest) >= KNOWN_STEM)
        // «میدیم» is a verb (the list knows «نمیدیم»); «میدان», «میدون», «میوه» are not (no «نمیدان»).
        val other = if (prefix == "می") "نمی" else "می"
        val conjugated = rest.length >= 3 && count(other + rest) + count(other + ZWNJ + rest) >= KNOWN_STEM
        return zw.takeIf { listed || conjugated }
    }

    private fun suffixed(w: String): String? {
        val suffix = SUFFIXES.firstOrNull { w.endsWith(it) && w.length - it.length >= 2 } ?: return null
        val stem = w.dropLast(suffix.length)
        val zw = stem + ZWNJ + suffix
        // «تنها» stays: the list almost never writes «تن‌ها»; a known stem with an unknown ending takes the half-space.
        val listed = count(zw) > 0 && count(zw) * PLURAL_RATIO >= count(w)
        val unknown = count(w) == 0 && count(stem) >= KNOWN_STEM && (suffix in PLURALS || stem.endsWith('ه'))
        val afterHe = suffix in PLURALS && stem.endsWith('ه') && count(stem) >= KNOWN
        return zw.takeIf { listed || unknown || afterHe }
    }

    private fun afterHe(w: String): String? = HE_CLITICS.firstNotNullOfOrNull { c ->
        val stem = w.dropLast(c.length)
        val zw = stem + ZWNJ + c
        val rare = count(w) == 0 || count(zw) * PLURAL_RATIO >= count(w)
        zw.takeIf { w.endsWith(c) && stem.endsWith('ه') && stem.length >= 3 && count(stem) >= KNOWN && rare }
    }

    private fun determined(w: String): String? = DETERMINERS.firstNotNullOfOrNull { d ->
        val rest = w.removePrefix(d)
        val zw = d + ZWNJ + rest
        zw.takeIf { rest != w && rest.length >= 2 && count(zw) > 0 && count(zw) * DETERMINER_RATIO >= count(w) }
    }

    /** «بیترتیب» → «بی‌ترتیب»: «بی» before a common word the list does not know joined, with its half-space. */
    fun prefixed(core: String): String? {
        val rest = core.removePrefix("بی")
        return ("بی" + ZWNJ + rest).takeIf { rest != core && rest.length >= 3 && count(core) == 0 && count(rest) >= PREFIXED_STEM }
    }

    /**
     * «کهدیگه» → «که دیگه», «ازدست» → «از دست», «همهچیذم» → «همه چیزم»: a short common word run into the
     * next. A rare or unknown word splits when chance alone puts the two words side by side twice as often
     * as the list has the run-together word («درجا», «تومن» stay), the second half a word or one sound-alike
     * from one; the likeliest split wins.
     */
    fun unmerge(core: String): String? {
        val plain = core.length >= MIN_MERGED && ZWNJ !in core
        if (!plain || count(core) >= KNOWN || lex.decomposes(core)) return null
        return (2..core.length - 2).mapNotNull { k ->
            val head = core.substring(0, k)
            val rest = core.substring(k)
            val right = if (count(rest) >= KNOWN) rest else lex.sounding(rest).filter { count(it) >= KNOWN }.maxByOrNull { count(it) }
            val likely = right != null && count(head).toDouble() * count(right) / lex.total >= MERGE_ODDS * count(core)
            right?.takeIf { head.length <= MAX_HEAD && count(head) >= MERGE_HEAD && likely }
                ?.let { ln(count(head).toDouble()) + ln(count(it).toDouble()) to "$head $it" }
        }.maxByOrNull { it.first }?.second
    }

    /**
     * Two recognised words that are one: the verb prefix, a clitic or plural («خیلی هاتون»), a split prefix
     * («نا امید»), a place name after a street («خیابون ولی عصره»), or a compound the list knows far better
     * joined («برنامه ریزی», «تخم مرغ‌ها»), its clitic kept.
     */
    fun join(a: String, b: String, prev: String?): String? = when {
        a in VERB_PREFIXES -> (a + ZWNJ + b).takeIf { b.length >= 2 && (count(it) > 0 || count(a + b) > 0) }
        a in SPLIT_PREFIXES && splitPrefix(a, b) != null -> splitPrefix(a, b)
        prev in STREETS && place(a, b) != null -> place(a, b)
        // «سی نه» (thirty-nine said without «و») is two numbers, not «سینه».
        a in NUMBERS && b in NUMBERS -> null
        a.length < 2 || b.length < 2 -> letter(a, b)
        b in BOUND -> bound(a, b)
        b == "تایی" && a in NUMBERS -> a + ZWNJ + b
        else -> compound(a, b)
    }

    /** «آیلتس م» → «آیلتسم», «پایه ی» → «پایه‌ی»: a one-letter clitic heard as a word. */
    private fun letter(a: String, b: String): String? {
        if (b !in LETTER_CLITICS || a.length < 2 || count(a) < PLACE) return null
        return if (a.endsWith('ه')) a + ZWNJ + b else a + b
    }

    /** A plural or clitic: as the list writes it, with a half-space after «ه»; «تموم شده ها» (the particle) stays. */
    private fun bound(a: String, b: String): String? {
        if (count(a) < PLACE) return null
        val joined = a + b
        val zw = a + ZWNJ + b
        if (b in PLURALS) {
            // «کتاب ها» joins (the list writes «کتاب‌ها» far more than chance); «شده ها» is the particle «ها».
            val expected = count(a).toDouble() * count(b) / lex.total
            val plural = maxOf(count(zw), count(joined)) >= expected || count(a) < RARE_NOUN
            if (!plural) return null
        }
        return if (a.endsWith('ه') || count(zw) >= count(joined)) zw else joined
    }

    private fun compound(a: String, b: String): String? {
        val fa = count(a).toDouble()
        val fb = count(b).toDouble()
        sounded(a, b)?.let { return it }
        if (fa >= FUNCTION || fb >= FUNCTION) return null
        // «برنامه ریزی», «سعادت آباده», «تخم مرغ‌ها»: the list's compound for the two halves (the second with its clitic).
        return endings(b).firstNotNullOfOrNull { (stem, rest) ->
            val j = listOf(a + ZWNJ + stem, a + stem).filter { count(it) >= PLACE }.maxByOrNull { count(it) }
            val expected = fa * maxOf(count(stem), 1) / lex.total
            j?.takeIf { count(it) >= COMPOUND_ODDS * expected }?.let { it + glue(it, rest) + rest }
        }
    }

    /** [b] as stem and ending: whole, then each clitic it may end in («آباده» → «آباد» + «ه», «مرغ‌ها» → «مرغ» + «‌ها»). */
    private fun endings(b: String): List<Pair<String, String>> = listOf(b to "") + PersianSpelling.CLITICS.mapNotNull { c ->
        val stem = b.removeSuffix(c).trimEnd(ZWNJ)
        (stem to b.substring(stem.length)).takeIf { b.endsWith(c) && stem.length >= 2 }
    }

    /** «سی سد» → «سیصد»: a known word whose two halves were heard as other words. */
    private fun sounded(a: String, b: String): String? {
        val j = lex.sounding(a + b).filter { count(it) >= KNOWN_STEM }.maxByOrNull { count(it) } ?: return null
        val fa = count(a).toDouble()
        val fb = count(b).toDouble()
        // How much likelier the joined word is than the two words meeting by chance.
        val ratio = count(j) * lex.total / (fa * fb + 1)
        val compound = j == a + ZWNJ + b && count(j) >= COMPOUND_SHARE * min(fa, fb)
        val respelled = j.replace(ZWNJ_S, "") != a + b
        val rare = min(fa, fb) < RARE
        return j.takeIf { ratio >= JOIN_RATIO && (compound || respelled || rare) }
    }

    private fun glue(word: String, rest: String): String {
        val clitic = rest.isNotEmpty() && rest[0] != ZWNJ
        return if (clitic && word.endsWith('ه') && rest != "ه") ZWNJ_S else ""
    }

    /**
     * «نا امید» → «ناامید», «بی ترتیب» → «بی‌ترتیب»: a prefix split off its word, joined as the list writes
     * it. «هم» joins only into a word more common than its second half («هم کار» stays: "work too").
     */
    private fun splitPrefix(a: String, b: String): String? {
        val best = listOf(a + b, a + ZWNJ + b).maxBy { count(it) }
        if (count(best) >= KNOWN_STEM) return best.takeIf { a != "هم" || count(it) >= count(b) }
        return (a + ZWNJ + b).takeIf { a == "بی" && count(b) >= KNOWN }
    }

    /** «ولی عصره» after «خیابون»: the place name the list knows («ولیعصر»), its clitic kept. */
    private fun place(a: String, b: String): String? = endings(b).firstNotNullOfOrNull { (stem, rest) ->
        listOf(a + stem, a + ZWNJ + stem).filter { count(it) >= PLACE }.maxByOrNull { count(it) }?.let { it + glue(it, rest) + rest }
    }

    /** «قدم به قدم» → «قدم‌به‌قدم», «نقطه به نقطه» → «نقطه‌به‌نقطه»: a word, «به», the same word. */
    fun repeated(a: String, middle: String?, b: String?): String? =
        (a + ZWNJ + "به" + ZWNJ + a).takeIf { middle == "به" && b == a && a.length >= 2 && count(a) >= KNOWN }

    /** Misspellings so common the list counts them, always put right («بزار» → «بذار», «تعین» → «تعیین»). */
    fun misspelling(w: String): String? = MISSPELLINGS[w.replace(ZWNJ_S, "")]

    companion object {
        private const val ZWNJ = '\u200C'
        private const val ZWNJ_S = "\u200C"

        private const val KNOWN = 300
        private const val KNOWN_STEM = 100
        private const val PLACE = 50
        private const val RARE = 50.0
        private const val RARE_NOUN = 5000
        private const val FUNCTION = 1_000_000.0
        private const val PREFIXED_STEM = 5000
        private const val PLURAL_RATIO = 50
        private const val DETERMINER_RATIO = 100
        private const val VARIANT_RATIO = 10
        private const val JOIN_RATIO = 1000.0
        private const val COMPOUND_ODDS = 100.0
        private const val COMPOUND_SHARE = 0.3
        private const val MERGE_HEAD = 100_000
        private const val MERGE_ODDS = 2.0
        private const val MAX_HEAD = 4
        private const val MIN_MERGED = 4

        private val VERB_PREFIXES = listOf("نمی", "می")
        private val PLURALS = setOf("هایی", "های", "ها")
        private val SUFFIXES = listOf("هایی", "های", "ها", "ترین", "تر")
        private val HE_CLITICS = listOf("هاتون", "هاشون", "هامون", "مون", "تون", "شون", "اش", "ات", "ام", "ای", "ست", "م", "ت", "ش")
        private val DETERMINERS = listOf("همین", "همون", "هیچ")
        private val BOUND = setOf(
            "ها", "های", "هایی", "هاتون", "هاشون", "هامون", "هاش", "هات", "هام", "تر", "ترین", "تون", "شون", "مون", "ای", "اش", "ام",
        )
        private val LETTER_CLITICS = setOf("م", "ت", "ش", "ی")
        private val SPLIT_PREFIXES = setOf("نا", "بی", "هم")
        private val STREETS = setOf("خیابون", "خیابان", "میدون", "میدان", "بلوار", "اتوبان", "بزرگراه", "کوچه", "محله", "شهرک")
        private val NUMBERS = setOf("یه", "یک", "دو", "سه", "چهار", "پنج", "شش", "شیش", "هفت", "هشت", "نه", "ده", "بیست", "سی", "چهل", "پنجاه", "صد")

        /** «بزار» for «بذار» (the verb گذاشتن) in all its forms, and a few more the list is full of. */
        private val MISSPELLINGS: Map<String, String> = buildMap {
            for (pre in listOf("ب", "می", "نمی", "ن")) for (end in listOf("", "م", "ی", "ه", "یم", "ید", "ین", "ن")) {
                if (pre.isNotEmpty() || end.isNotEmpty()) put(pre + "زار" + end, pre + "ذار" + end)
            }
            put("تعین", "تعیین")
            put("بیتکوین", "بیت‌کوین")
        }
    }
}
