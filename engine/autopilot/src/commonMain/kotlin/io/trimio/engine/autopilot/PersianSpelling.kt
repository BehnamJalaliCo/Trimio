package io.trimio.engine.autopilot

import io.trimio.engine.autopilot.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.math.ln
import kotlin.math.min

/**
 * Offline Persian spelling for recognised speech, from a word-frequency list (files/spelling/fa_words.tsv).
 *
 * The recogniser writes what it hears, so its Persian mistakes are phonetic: letters that sound alike
 * («سبتنام» for «ثبت‌نام»), silent letters dropped («ساتم», «دایرک»), a doubled letter («سررافی»), a
 * compound split in two («سی سد») and missing half-spaces («میتونید»). A word is only changed when it is
 * unknown or rare and one such slip away from a word that is far more frequent; a frequent word is never
 * touched, because only context can tell «پوست» from «پست» — that is the director's job ([Proofreader.align]).
 *
 * Words elsewhere in the transcript or in the brief count as evidence ("document cache"): «دایرک» becomes
 * «دایرکت» because the speaker says «دایرکت» later, and «هشت ساتم» becomes «هشت ساعت هم» because «ساعت»
 * was heard (and after a number the «م» is «هم»).
 */
class PersianSpelling(private val counts: Map<String, Int>) {

    private val total: Double = counts.values.sumOf { it.toDouble() }
    private val bySound: Map<String, List<String>> = counts.keys.groupBy(::soundKey)

    /** How often [word] occurs in the list (0 when unknown); half-spaces and Arabic letter forms normalised. */
    fun count(word: String): Int = counts[normalize(word)] ?: 0

    /**
     * Word index → corrected spelling (trailing punctuation kept; timings never change). Two words that
     * are one compound («سی سد» → «سیصد») map the first index to the whole word and the second to ""; a
     * fix may also be several words («چلا» → «چهل و»). Apply with [withSpelling], which keeps every word one
     * word. [context] adds known-good words (the brief) to the evidence.
     */
    fun correct(words: List<String>, context: Collection<String> = emptyList()): Map<Int, String> {
        val parts = words.map(::split)
        val heard = parts.groupingBy { it.core }.eachCount()
        val doc = heard.keys + context.map(::normalize)
        val out = mutableMapOf<Int, String>()
        var i = 0
        while (i < parts.size) {
            val w = parts[i]
            val next = parts.getOrNull(i + 1)
            val joined = next?.takeIf { joinable(w, it) }?.let { join(w.core, it.core) }
            if (joined != null && next != null) {
                out[i] = w.head + joined + next.tail
                out[i + 1] = ""
                i += 2
                continue
            }
            if (persian(w.core) && !w.voweled) {
                // A word heard twice the same way is taken as meant (a name, a brand) unless a sound-alike fixes it.
                val soundOnly = (heard[w.core] ?: 0) > 1 && count(w.core) == 0
                val prev = parts.getOrNull(i - 1)?.core
                val fixed = number(w.core, next?.core) ?: halfSpace(w.core) ?: fix(w.core, doc, soundOnly)?.let { counted(it, prev) }
                if (fixed != null) out[i] = w.head + fixed + w.tail
            }
            i++
        }
        return out
    }

    /**
     * Whether the director's [fixed] spelling of a recognised word is a real word one or two slips away
     * from a [heard] word that is not one ("کارورد" → "کاربر"), so [Proofreader.align] may take it.
     */
    fun confirms(heard: String, fixed: String): Boolean {
        val a = normalize(heard)
        val b = normalize(fixed)
        val unknown = a.length >= MIN_EDIT_LENGTH && count(a) == 0 && !decomposes(a)
        if (!unknown || !persian(a + b) || count(b) < KNOWN) return false
        val ka = soundKey(a)
        val kb = soundKey(b)
        return ka.first() == kb.first() && distance(ka, kb) <= 2
    }

    // ------------------------------------------------------------------ rules

    private fun fix(core: String, doc: Set<String>, soundOnly: Boolean): String? {
        val own = count(core)
        if (own >= FREQUENT || core.length < 3) return null
        val decomposable = decomposes(core)
        if (decomposable && (own == 0 || soundOnly)) return null
        val ranked = candidates(core, doc, soundOnly)
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.first ?: Double.NEGATIVE_INFINITY
        if (best.first - second < ln(MARGIN)) return null
        // A rare but listed word gives way only to a word a hundred (or, if it parses, a thousand) times as common.
        val ratio = if (decomposable) RATIO_PARSED else RATIO
        return best.second.takeIf { own == 0 || count(it) >= ratio * own }
    }

    /** Spellings within one recogniser slip, scored by log frequency minus the slip's cost. */
    private fun candidates(core: String, doc: Set<String>, soundOnly: Boolean): List<Pair<Double, String>> {
        val key = soundKey(core)
        val listed = count(core) > 0
        val cost = mutableMapOf<String, Int>()
        bySound[key].orEmpty().filter { it.replace(ZWNJ_S, "") != core.replace(ZWNJ_S, "") }.forEach { cost[it] = SOUND }
        if (key.length >= 3 && !soundOnly) {
            for ((v, c) in variants(key)) {
                if (v.length < 3) continue
                bySound[v]?.forEach { w -> if (c < (cost[w] ?: Int.MAX_VALUE)) cost[w] = c }
            }
        }
        return cost.mapNotNull { (w, c) ->
            val seen = inDoc(w, doc, core)
            if (!allowed(c, core.length, seen, listed) || count(w) < (if (seen) KNOWN_IN_DOC else KNOWN)) return@mapNotNull null
            val penalty = (c / 10.0 - if (seen) 1.0 else 0.0) * ln(SLIP)
            ln(count(w).toDouble()) - penalty to w
        }.sortedByDescending { it.first }
    }

    /**
     * Sound-alikes, a dropped «ع»/«ه» and a doubled letter are always worth trying. An added letter or a
     * last «د»/«ه» swapped needs a long word (few neighbours) or a rare listed one («بازید»). A dropped last
     * letter or a swap inside the word needs the word heard elsewhere: «کارورد» would become «کاربرد», and
     * a wrong real word also hides the slip from the director, who would have written «کاربر».
     */
    private fun allowed(cost: Int, length: Int, seen: Boolean, listed: Boolean): Boolean = when {
        seen -> true
        cost == SOUND -> true
        length < MIN_EDIT_LENGTH -> false
        cost == WEAK || cost == DOUBLE -> true
        cost != INSERT && cost != SUBSTITUTE -> false
        else -> length >= LONG_WORD || listed && length >= LONG_WORD - 1
    }

    /** Keys one slip from [k]: a letter added (never before the first), a doubled letter dropped, a near letter swapped. */
    private fun variants(k: String): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        fun put(v: String, c: Int) {
            if (c < (out[v] ?: Int.MAX_VALUE)) out[v] = c
        }
        for (i in 1..k.length) {
            for (ch in ALPHABET) put(k.substring(0, i) + ch + k.substring(i), if (i == k.length) FINAL else if (ch in WEAK_LETTERS) WEAK else INSERT)
        }
        for (i in 1 until k.length) {
            if (k[i] == k[i - 1]) put(k.removeRange(i, i + 1), DOUBLE)
            NEAR[k[i]]?.forEach { ch -> put(k.substring(0, i) + ch + k.substring(i + 1), if (i == k.length - 1) SUBSTITUTE else SUBSTITUTE_MID) }
        }
        return out
    }

    private fun inDoc(w: String, doc: Set<String>, core: String) =
        (w != core && w in doc) || stems(w).any { it.length >= 4 && it != core && it in doc }

    /** «می/نمی» joined to the verb and plural «ها/های» to the noun with a half-space, as the list spells them. */
    private fun halfSpace(w: String): String? {
        if (ZWNJ in w) return null
        val prefix = VERB_PREFIXES.firstOrNull { w.startsWith(it) && w.length - it.length >= 2 }
        if (prefix != null) {
            val rest = w.substring(prefix.length)
            val zw = prefix + ZWNJ + rest
            return zw.takeIf { count(zw) > 0 || (count(w) == 0 && count(rest) >= KNOWN_STEM) }
        }
        val suffix = PLURALS.firstOrNull { w.endsWith(it) && w.length - it.length >= 2 } ?: return null
        val zw = w.dropLast(suffix.length) + ZWNJ + suffix
        // «تنها» stays: the list almost never writes «تن‌ها».
        return zw.takeIf { count(zw) > 0 && count(zw) * PLURAL_RATIO >= count(w) }
    }

    /** Two recognised words that are one: an affix, a clitic, or a compound the list knows far better joined. */
    private fun join(a: String, b: String): String? {
        if (a in VERB_PREFIXES) return (a + ZWNJ + b).takeIf { b.length >= 2 && (count(it) > 0 || count(a + b) > 0) }
        if (a.length < 2 || b.length < 2) return (a + ZWNJ + b).takeIf { b == "ی" && a.endsWith('ه') && count(a) >= KNOWN_STEM }
        val bound = b in BOUND && count(a) >= KNOWN_STEM
        if (bound && (b in CLITIC_WORDS || count(a + ZWNJ + b) > 0)) return a + ZWNJ + b
        val j = bySound[soundKey(a + b)].orEmpty().filter { count(it) >= KNOWN_STEM }.maxByOrNull { count(it) } ?: return null
        val fa = count(a).toDouble()
        val fb = count(b).toDouble()
        // How much likelier the joined word is than the two words meeting by chance.
        val ratio = count(j) * total / (fa * fb + 1)
        val compound = j == a + ZWNJ + b && count(j) >= COMPOUND_SHARE * min(fa, fb)
        val respelled = j.replace(ZWNJ_S, "") != a + b
        val rare = min(fa, fb) < RARE
        return j.takeIf { ratio >= JOIN_RATIO && (compound || respelled || rare) }
    }

    /** Spoken tens: «چلا هشت» → «چهل و هشت», «پنجا هزار» → «پنجاه هزار». */
    private fun number(core: String, next: String?): String? {
        next ?: return null
        if (next in UNITS) {
            TENS.entries.firstOrNull { (t, _) -> core == t + "و" || core == t + "ا" }?.let { return it.value + " و" }
        }
        val full = TENS[core] ?: return null
        return full.takeIf { it != core && (next in UNITS || next in SCALES || next == "و") }
    }

    /**
     * After a number a respelled «…م» is «… هم»: «هشت ساتم» was «هشت ساعت هم» (eight hours too), not
     * «ساعتم» (my hour). The list has no word pairs to weigh the two, but a counted noun takes «هم».
     */
    private fun counted(fixed: String, prev: String?): String {
        val stem = fixed.dropLast(1)
        val afterNumber = prev != null && (prev in NUMBER_WORDS || prev.all { it.isDigit() })
        return if (afterNumber && fixed.endsWith('م') && count(stem) >= KNOWN) "$stem هم" else fixed
    }

    /** A known stem with a colloquial clitic («کلیپت», «کمپینو», «دایرکتتو») or a verb prefix is a word, not a slip. */
    private fun decomposes(w: String): Boolean {
        if (ZWNJ in w) return w.split(ZWNJ).all { count(it) > 0 || it in CLITICS || it in VERB_PREFIXES }
        if (stems(w).any { count(it) >= KNOWN_STEM * 2 }) return true
        return STEM_PREFIXES.any { p -> w.startsWith(p) && w.length - p.length >= 3 && count(w.substring(p.length)) >= KNOWN_STEM * 2 }
    }

    private fun stems(w: String) = CLITICS.filter { w.endsWith(it) && w.length - it.length >= 3 }.map { w.dropLast(it.length) }

    /** A word split into leading punctuation, normalised letters and trailing punctuation; [voweled] words are deliberate (Arabic, poetry). */
    private data class Parts(val head: String, val core: String, val tail: String, val voweled: Boolean)

    /** Two Persian words with nothing but a space between them. */
    private fun joinable(a: Parts, b: Parts) = a.tail.isEmpty() && b.head.isEmpty() && listOf(a, b).all { persian(it.core) && !it.voweled }

    private fun split(word: String): Parts {
        val head = word.takeWhile { !it.isLetterOrDigit() }
        val rest = word.substring(head.length)
        val core = rest.trimEnd { !it.isLetterOrDigit() }
        return Parts(head, normalize(core), rest.substring(core.length), rest.any { it in '\u064B'..'\u0652' || it == '\u0670' })
    }

    companion object {
        /** Loaded once per process (the list is 100k words); also lets [Proofreader.align] check the director. */
        var shared: PersianSpelling? = null
            private set

        @OptIn(ExperimentalResourceApi::class)
        suspend fun load(): PersianSpelling =
            shared ?: parse(Res.readBytes("files/spelling/fa_words.tsv").decodeToString()).also { shared = it }

        /** "word<TAB>count" lines. */
        fun parse(tsv: String): PersianSpelling {
            val counts = HashMap<String, Int>(LIST_SIZE)
            for (line in tsv.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab > 0) counts[line.substring(0, tab)] = line.substring(tab + 1).trim().toIntOrNull() ?: continue
            }
            return PersianSpelling(counts)
        }

        private const val ZWNJ = '\u200C'
        private const val ZWNJ_S = "\u200C"

        // Slip costs in tenths of a slip; a slip costs a factor of SLIP in frequency.
        private const val SOUND = 0
        private const val WEAK = 3
        private const val DOUBLE = 8
        private const val INSERT = 10
        private const val FINAL = 11
        private const val SUBSTITUTE = 15
        private const val SUBSTITUTE_MID = 16
        private const val SLIP = 30.0
        private const val MARGIN = 3.0

        private const val FREQUENT = 400
        private const val KNOWN = 300
        private const val KNOWN_IN_DOC = 40
        private const val KNOWN_STEM = 100
        private const val RATIO = 100
        private const val RATIO_PARSED = 1000
        private const val PLURAL_RATIO = 50
        private const val JOIN_RATIO = 1000.0
        private const val COMPOUND_SHARE = 0.3
        private const val RARE = 50.0
        private const val MIN_EDIT_LENGTH = 4
        private const val LONG_WORD = 6
        private const val LIST_SIZE = 131_072

        /** One letter per sound class (the ones [soundKey] maps to). */
        private const val ALPHABET = "ابپتجچخدرزژسشعفقکگلمنوهی"
        private const val WEAK_LETTERS = "عه"
        private val NEAR: Map<Char, List<Char>> = mapOf('د' to listOf('ه'), 'ه' to listOf('د'), 'ب' to listOf('و'), 'و' to listOf('ب'))

        private val VERB_PREFIXES = listOf("نمی", "می")
        private val STEM_PREFIXES = listOf("نمی", "می", "ب", "ن")
        private val PLURALS = listOf("هایی", "های", "ها")
        private val BOUND = setOf("ها", "های", "هایی", "تر", "ترین", "تون", "شون", "مون")
        private val CLITIC_WORDS = setOf("ها", "های", "هایی", "تون", "شون", "مون")
        private val CLITICS = listOf(
            "تان", "شان", "مان", "یت", "یش", "یم", "و", "رو", "ت", "تو", "ش", "شو", "م", "مو", "مون", "تون", "شون", "ه", "ی",
            "ام", "ات", "اش", "ای", "یه", "ها", "های", "هاش", "هات", "هام", "هاتون", "هاشون", "هامون", "ست",
        )
        private val TENS = mapOf(
            "بیس" to "بیست", "بیست" to "بیست", "سی" to "سی", "چل" to "چهل", "چهل" to "چهل", "پنجا" to "پنجاه", "پنجاه" to "پنجاه",
            "شصت" to "شصت", "هفتاد" to "هفتاد", "هشتاد" to "هشتاد", "نود" to "نود",
        )
        private val UNITS = setOf("یک", "یه", "دو", "سه", "چهار", "پنج", "شش", "شیش", "هفت", "هشت", "نه")
        private val NUMBER_WORDS = UNITS + TENS.values + setOf(
            "ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "پونزده", "شانزده", "شونزده", "هفده", "هجده", "هیجده", "نوزده",
            "صد", "دویست", "سیصد", "چهارصد", "پانصد", "پونصد", "ششصد", "هفتصد", "هشتصد", "نهصد", "هزار", "میلیون", "میلیارد",
        )
        private val SCALES = setOf("صد", "هزار", "میلیون", "میلیارد", "تومن", "تومان", "دلار", "درصد", "سال")

        private val SOUND_CLASS: Map<Char, Char> = buildMap {
            "سصث".forEach { put(it, 'س') }
            "زذضظ".forEach { put(it, 'ز') }
            "تط".forEach { put(it, 'ت') }
            "هح".forEach { put(it, 'ه') }
            "قغ".forEach { put(it, 'ق') }
            "آأإا".forEach { put(it, 'ا') }
            "ؤو".forEach { put(it, 'و') }
            "ئی".forEach { put(it, 'ی') }
        }

        /** Letters that sound alike map to one; «ع» sounds like «ا» at the start, «آ» keeps its own sound there. */
        internal fun soundKey(w: String): String {
            val s = buildString { for (ch in w) if (ch != ZWNJ) append(SOUND_CLASS[ch] ?: ch) }
            return when {
                w.startsWith('آ') -> "آ" + s.drop(1)
                s.startsWith('ع') -> "ا" + s.drop(1)
                else -> s
            }
        }

        /** Persian letter forms (ی/ک), no diacritics or tatweel, single half-spaces — as the list is written. */
        internal fun normalize(w: String): String {
            val s = buildString(w.length) {
                for (ch in w) {
                    val repeated = ch == ZWNJ && (isEmpty() || last() == ZWNJ)
                    if (!mark(ch) && !repeated) append(LETTERS[ch] ?: ch)
                }
            }
            return s.trimEnd(ZWNJ)
        }

        /** Diacritics, tatweel, hamza above and the zero-width joiner: dropped for lookup. */
        private fun mark(ch: Char) = ch in '\u064B'..'\u0652' || ch in "\u0640\u0654\u0670\u200D"

        private val LETTERS = mapOf('ي' to 'ی', 'ى' to 'ی', 'ك' to 'ک', 'ة' to 'ه', 'ۀ' to 'ه')

        /** Persian letters (and half-spaces) only: Latin words, digits and symbols are never touched. */
        private fun persian(w: String) = w.isNotEmpty() && w.all { it == ZWNJ || (it in '\u0621'..'\u06FF' && it.isLetter()) }

        private fun distance(a: String, b: String): Int {
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
