package io.trimio.engine.autopilot

import io.trimio.engine.autopilot.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.math.ln

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
class PersianSpelling(
    private val counts: Map<String, Int>,
    /** Everyday English (files/spelling/en_words.tsv, lowercase → count): a brief's Latin word in it is no name. */
    val english: Map<String, Int> = emptyMap(),
) {

    internal val total: Double = counts.values.sumOf { it.toDouble() }
    private val bySound: Map<String, List<String>> = counts.keys.groupBy(::soundKey)
    private val joins = PersianJoins(this)

    /** Common words ending in [suffix] («تر», «ترین»), with the stem before it (half-spaces dropped): what [PersianRepairs] grades against. */
    internal fun graded(suffix: String): List<Pair<String, String>> = gradedBy.getOrPut(suffix) {
        counts.entries.mapNotNull { (w, c) ->
            val bare = w.replace(ZWNJ_S, "")
            (bare.dropLast(suffix.length) to w).takeIf { c >= KNOWN_IN_DOC && bare.endsWith(suffix) && bare.length - suffix.length >= 2 }
        }
    }

    private val gradedBy = mutableMapOf<String, List<Pair<String, String>>>()

    /** One recogniser slip of an unknown [core] put right ([fix] with no other evidence), for a stem under its endings. */
    internal fun slip(core: String): String? = if (count(core) > 0) null else fix(core, emptySet(), soundOnly = false)

    /**
     * Whether a listed word ([KNOWN_IN_DOC] or more) is one sound slip from [core]: a letter more,
     * a letter less or one letter swapped. An unknown word with none near is likelier a name or a
     * loanword than a slip of a word.
     */
    internal fun near(core: String): Boolean {
        val k = soundKey(core)
        fun hit(v: String) = v.length >= 2 && bySound[v].orEmpty().any { count(it) >= KNOWN_IN_DOC && it.replace(ZWNJ_S, "") != core.replace(ZWNJ_S, "") }
        if (k.indices.any { hit(k.removeRange(it, it + 1)) }) return true
        return (0..k.length).any { i -> ALPHABET.any { ch -> hit(k.substring(0, i) + ch + k.substring(i)) || (i < k.length && hit(k.substring(0, i) + ch + k.substring(i + 1))) } }
    }

    /** Listed words that sound like [w] (sound-alike letters merged, half-spaces ignored). */
    internal fun sounding(w: String): List<String> = bySound[soundKey(w)].orEmpty()

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
        val settled = settled(parts)
        val out = mutableMapOf<Int, String>()
        var i = 0
        while (i < parts.size) {
            val w = parts[i]
            val prev = parts.getOrNull(i - 1)?.core
            val next = parts.getOrNull(i + 1)
            val span = span(parts, i)
            if (span != null) {
                span.forEachIndexed { k, t -> out[i + k] = t }
                i += span.size
                continue
            }
            val fixed = if (persian(w.core) && !w.voweled) word(w, prev, next, parts.getOrNull(i + 2), heard, doc, settled) else null
            if (fixed != null) out[i] = w.head + fixed + w.tail
            i++
        }
        return out
    }

    /**
     * Words from [i] that are put right together, one text per word ("" for a word joined into the
     * one before), or null: one term heard as two («چچی پیتیت», «صرف جوی»), a two-word term heard
     * with a slip («حوشه مسئولی» → «هوش مصنوعی»), or words that are one ([joined]).
     */
    private fun span(parts: List<Parts>, i: Int): List<String>? {
        val w = parts[i]
        val next = parts.getOrNull(i + 1) ?: return null
        val pair = Loanwords.pair(w.core, next.core) ?: next.takeIf { joinable(w, it) }?.let { repairs.compound(w.core, it.core) }
        if (pair != null) return listOf(w.head + pair + next.tail, "")
        val term = next.takeIf { joinable(w, it) }?.let { repairs.phrase(w.core, it.core) }
        if (term != null) return listOf(w.head + term.first + w.tail, next.head + term.second + next.tail)
        val joined = joined(w, next, parts.getOrNull(i + 2), parts.getOrNull(i - 1)?.core) ?: return null
        val last = i + joined.second - 1
        return listOf(w.head + joined.first + parts[last].tail) + List(joined.second - 1) { "" }
    }

    /**
     * One heard word put right, or null. A term the recogniser often mishears is written as the
     * term ([Loanwords.alias]); a protected term is never touched ([Loanwords.protected]: «توکن» is
     * never «تو کن»). A known word is split in two only when it is unknown and said once.
     */
    private fun word(w: Parts, prev: String?, next: Parts?, third: Parts?, heard: Map<String, Int>, doc: Set<String>, settled: Set<String>): String? {
        if (count(w.core) == 0) Loanwords.alias(w.core)?.let { return it }
        if (Loanwords.protected(w.core)) return null
        // A word heard twice the same way is taken as meant (a name, a brand) unless a sound-alike fixes it.
        val soundOnly = (heard[w.core] ?: 0) > 1 && count(w.core) == 0
        val around = Around(prev, next?.core, third?.core)
        val split = { joins.unmerge(w.core)?.takeIf { count(w.core) == 0 && (heard[w.core] ?: 0) < 2 } }
        return confusion(w.core, prev, next?.core, doc, settled) ?: joins.misspelling(w.core) ?: numeral(w.core, around)
            ?: joins.halfSpace(w.core) ?: joins.prefixed(w.core) ?: fix(w.core, doc, soundOnly)?.let { counted(it, prev) }
            ?: repairs.written(w.core) ?: split() ?: repairs.repair(w.core, next?.core) ?: halfSpaced(w.core)
    }

    private val repairs by lazy { PersianRepairs(this) }

    /** A word already half-spaced whose first part lost its own half-space: «سختترین‌ها» → «سخت‌ترین‌ها». */
    private fun halfSpaced(core: String): String? {
        val parts = core.split(ZWNJ)
        if (parts.size < 2) return null
        val first = joins.halfSpace(parts[0]) ?: return null
        return (listOf(first) + parts.drop(1)).joinToString(ZWNJ_S)
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

    /**
     * Whether the director's [fixed] spelling is a step back from what was [heard]: only a half-space taken
     * out («می‌گه» → «میگه»), the end of the word cut off («دایرکتتو» → «دایرکتت»), or letters deleted into a
     * word no more common than the heard one («ووچر» → «وچر»). A doubled last letter of a rare word
     * («زمینن» → «زمین») may still go.
     */
    fun worse(heard: String, fixed: String): Boolean {
        val a = normalize(heard.trim { !it.isLetterOrDigit() })
        val b = normalize(fixed.trim { !it.isLetterOrDigit() })
        val la = a.replace(ZWNJ_S, "")
        val lb = b.replace(ZWNJ_S, "")
        if (a == b || lb.isEmpty()) return false
        if (la == lb) return b.count { it == ZWNJ } < a.count { it == ZWNJ }
        if (lb.length >= la.length || !subsequence(lb, la)) return false
        val doubled = la.length - lb.length == 1 && la.last() == lb.last() && count(b) >= maxOf(KNOWN, RATIO * count(a))
        return if (la.startsWith(lb)) !doubled else count(b) <= count(a)
    }

    // ------------------------------------------------------------------ rules

    /** A [Confusion] that its neighbours give away; [settled] holds heard words already given away elsewhere. */
    private fun confusion(core: String, prev: String?, next: String?, doc: Set<String>, settled: Set<String>): String? {
        val c = CONFUSIONS.firstOrNull { it.heard == core } ?: return null
        val after = next?.replace(ZWNJ_S, "")
        val onTopic = c.topic.count { t -> t in doc || doc.any { t in stems(it) } } >= TOPIC_WORDS
        val fires = when {
            prev != null && prev in c.notAfter -> false
            c.after.isEmpty() && c.afterOnTopic.isEmpty() -> if (c.topic.isEmpty()) count(core) == 0 else onTopic
            after != null && after in c.after -> true
            // One sense per talk: once a next word gave this one away, its other occurrences follow.
            c.sticky && core in settled -> true
            after == null || after !in c.afterOnTopic -> false
            else -> core in settled || onTopic
        }
        return c.meant.takeIf { fires }
    }

    /** Heard words some occurrence of which its next word settles («پوست گذاشتم»): one sense per talk. */
    private fun settled(parts: List<Parts>): Set<String> = parts.indices.mapNotNull { i ->
        val after = parts.getOrNull(i + 1)?.core?.replace(ZWNJ_S, "") ?: return@mapNotNull null
        parts[i].core.takeIf { core -> CONFUSIONS.any { it.heard == core && after in it.after } }
    }.toSet()

    private fun fix(core: String, doc: Set<String>, soundOnly: Boolean): String? {
        val own = count(core)
        if (own >= FREQUENT || core.length < 3) return null
        val decomposable = decomposes(core)
        if (decomposable && (own == 0 || soundOnly)) return null
        val ranked = candidates(core, doc, soundOnly)
        val best = ranked.firstOrNull() ?: return silentHe(core).takeIf { own == 0 }
        val second = ranked.getOrNull(1)?.first ?: Double.NEGATIVE_INFINITY
        if (best.first - second < ln(MARGIN)) return null
        // A rare but listed word gives way only to a word a hundred (or, if it parses, a thousand) times as common.
        // Heard elsewhere in the talk («تورم … طورم»), a hundred times is enough even for a word that parses.
        val ratio = if (decomposable && !inDoc(best.second, doc, core)) RATIO_PARSED else RATIO
        return best.second.takeIf { own == 0 || count(it) >= ratio * own }
    }

    /**
     * A stem whose silent final «ه» went unwritten before a clitic: «روزمرت» → «روزمره‌ت», «پروژت» → «پروژه‌ت».
     * Only an unknown stem that is a common word once «ه» is put back.
     */
    private fun silentHe(core: String): String? = HE_CLITICS.firstNotNullOfOrNull { c ->
        val stem = core.removeSuffix(c)
        val known = bySound[soundKey(stem) + "ه"].orEmpty().filter { it.endsWith('ه') && count(it) >= KNOWN }.maxByOrNull { count(it) }
        known?.takeIf { stem != core && stem.length >= MIN_EDIT_LENGTH && count(stem) == 0 }?.let { it + ZWNJ + c }
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
            val enough = if (seen || c == SOUND) KNOWN_IN_DOC else KNOWN
            if (!allowed(c, core.length, seen, listed) || count(w) < enough) return@mapNotNull null
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
        // A last «د» heard «ت» («شاگرت»), «ژ» heard «ج» («پاساج»): a five-letter word is long enough.
        cost == SUBSTITUTE -> length >= LONG_WORD - 1
        cost != INSERT -> false
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

    /** The words around one: the one before, after, and the one after that. */
    private class Around(val prev: String?, val next: String?, val after: String?)

    /** A number word the recogniser misspelled, read as one: «سی سد»'s «سد», «چلا هشت», «پنجا هزار», «شست درصد». */
    private fun numeral(core: String, around: Around): String? = numberSlip(core, around) ?: around.next?.let { number(core, it) }

    /**
     * «سد هزار» → «صد هزار», «شست درصد» → «شصت درصد», «دوازه ساعت» → «دوازده ساعت»: a number heard as
     * another word, put right where a number is said (a number, a scale, «و» and a number, or a measure
     * after it). A misspelling that is no word at all («دویس», «چارصد») is always put right.
     */
    private fun numberSlip(core: String, around: Around): String? {
        val meant = NUMBER_SLIPS[core] ?: return null
        val next = around.next
        // «هفتصد ازار تومنه»: a scale heard as another word after a number is that scale.
        val scaled = meant in SCALES && around.prev in NUMBER_WORDS
        val counting = scaled || next in NUMBER_WORDS || next in SCALES || (next == "و" && around.after in NUMBER_WORDS)
        val measured = (next in MEASURES || next?.removeSuffix("ه") in MEASURES) && core != "هشتا"
        return meant.takeIf { count(core) == 0 || counting || measured }
    }

    /** Spoken tens: «چلا هشت» → «چهل و هشت», «پنجا هزار» → «پنجاه هزار». */
    private fun number(core: String, next: String?): String? =
        // «هفتادوپنج» → «هفتاد و پنج».
        TENS.entries.firstNotNullOfOrNull { (t, full) -> core.removePrefix(t + "و").takeIf { core.startsWith(t + "و") && it in UNITS }?.let { "$full و $it" } }
            ?: next?.let { tens(core, it) }

    private fun tens(core: String, next: String): String? {
        // «سی سه» → «سی و سه»: the «و» between tens and units, not heard.
        if (core in TENS.values && next in UNITS && core != next) return "$core و"
        if (next in UNITS) {
            TENS.entries.firstOrNull { (t, _) -> core == t + "و" || core == t + "ا" }?.let { return it.value + " و" }
        }
        val full = TENS[core] ?: return null
        return full.takeIf { it != core && (next in UNITS || next in SCALES || next == "و" || next in MEASURES || next == "تا") }
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
    internal fun decomposes(w: String): Boolean {
        if (ZWNJ in w) return w.split(ZWNJ).all { count(it) > 0 || it in CLITICS || it in VERB_PREFIXES }
        if (stems(w).any { count(it) >= KNOWN_STEM * 2 }) return true
        return STEM_PREFIXES.any { p -> w.startsWith(p) && w.length - p.length >= 3 && count(w.substring(p.length)) >= KNOWN_STEM * 2 }
    }

    private fun subsequence(short: String, long: String): Boolean {
        var k = 0
        for (ch in long) if (k < short.length && short[k] == ch) k++
        return k == short.length
    }

    private fun stems(w: String) = CLITICS.filter { w.endsWith(it) && w.length - it.length >= 3 }.map { w.dropLast(it.length) }

    /** A word split into leading punctuation, normalised letters and trailing punctuation; [voweled] words are deliberate (Arabic, poetry). */
    private data class Parts(val head: String, val core: String, val tail: String, val voweled: Boolean)

    /** Words heard apart that are one, and how many: «قدم به قدم» (three) or a pair ([PersianJoins.join]). */
    private fun joined(w: Parts, next: Parts?, third: Parts?, prev: String?): Pair<String, Int>? {
        if (next == null || !joinable(w, next)) return null
        if (third != null && joinable(next, third)) joins.repeated(w.core, next.core, third.core)?.let { return it to 3 }
        return joins.join(w.core, next.core, prev)?.let { it to 2 }
    }

    /** Two Persian words with nothing but a space between them (a protected term is never joined to its neighbour). */
    private fun joinable(a: Parts, b: Parts) = a.tail.isEmpty() && b.head.isEmpty() && listOf(a, b).all { persian(it.core) && !it.voweled && !Loanwords.protected(it.core) }

    private fun split(word: String): Parts {
        val head = word.takeWhile { !it.isLetterOrDigit() }
        val rest = word.substring(head.length)
        val core = rest.trimEnd { !it.isLetterOrDigit() }
        return Parts(head, normalize(core), rest.substring(core.length), rest.any { it in '\u064B'..'\u0652' || it == '\u0670' })
    }

    /**
     * A recogniser mishearing that only the neighbouring words give away: «پوست گذاشتم» is «پست گذاشتم».
     * Next words in [after] settle it; next words in [afterOnTopic] settle it when the talk is about [topic]
     * (two topic words in the transcript or the brief) or another occurrence was settled; never right after
     * [notAfter] («بازار رو ترک کنی» is leaving the market). With neither, [heard] is no word and is always fixed.
     */
    private class Confusion(
        val heard: String,
        val meant: String,
        val after: Set<String> = emptySet(),
        val afterOnTopic: Set<String> = emptySet(),
        val topic: Set<String> = emptySet(),
        val notAfter: Set<String> = emptySet(),
        /** Once one occurrence is given away by its next word, every occurrence is the meant word. */
        val sticky: Boolean = false,
    )

    companion object {
        /** Loaded once per process (the list is 100k words); also lets [Proofreader.align] check the director. */
        var shared: PersianSpelling? = null
            private set

        @OptIn(ExperimentalResourceApi::class)
        suspend fun load(): PersianSpelling = shared ?: parse(
            Res.readBytes("files/spelling/fa_words.tsv").decodeToString(),
            Res.readBytes("files/spelling/en_words.tsv").decodeToString(),
        ).also { shared = it }

        /** "word<TAB>count" lines: the Persian list and, optionally, the English one. */
        fun parse(tsv: String, englishTsv: String = ""): PersianSpelling = PersianSpelling(counts(tsv, LIST_SIZE), counts(englishTsv, ENGLISH_SIZE))

        private fun counts(tsv: String, size: Int): Map<String, Int> {
            val counts = HashMap<String, Int>(size)
            for (line in tsv.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab > 0) counts[line.substring(0, tab)] = line.substring(tab + 1).trim().toIntOrNull() ?: continue
            }
            return counts
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
        private const val MIN_EDIT_LENGTH = 4
        private const val LONG_WORD = 6
        private const val LIST_SIZE = 131_072
        private const val ENGLISH_SIZE = 32_768

        /** One letter per sound class (the ones [soundKey] maps to). */
        private const val ALPHABET = "ابپتجچخدرزژسشعفقکگلمنوهی"
        private const val WEAK_LETTERS = "عه"
        private val NEAR: Map<Char, List<Char>> = mapOf(
            'د' to listOf('ه', 'ت'), 'ه' to listOf('د'), 'ت' to listOf('د'), 'ب' to listOf('و'), 'و' to listOf('ب'), 'ج' to listOf('ژ'), 'ژ' to listOf('ج'),
        )

        private val VERB_PREFIXES = listOf("نمی", "می")
        private val STEM_PREFIXES = listOf("نمی", "می", "ب", "ن")
        internal val CLITICS = listOf(
            "تان", "ا", "شان", "مان", "یت", "یش", "یم", "و", "رو", "ت", "تو", "ش", "شو", "م", "مو", "مون", "تون", "شون", "ه", "ی",
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
        /** Forms of a verb from its past and present stems, without half-spaces («کردم», «بکنی», «میذارم», «بذار»). */
        private fun verb(past: String, presents: List<String>): Set<String> = buildSet {
            for (neg in listOf("", "ن")) for (pre in listOf("", "می", "نمی")) for (end in listOf("م", "ی", "", "یم", "ید", "ین", "ن", "ه")) {
                if (end != "ه" && (neg.isEmpty() || pre.isEmpty())) add(neg + pre + past + end)
            }
            for (present in presents) for (pre in listOf("", "ب", "می", "نمی", "ن")) for (end in listOf("م", "ی", "ه", "یم", "ید", "ین", "ن", "")) {
                if (pre.isNotEmpty() || end.isNotEmpty()) add(pre + present + end)
            }
        }

        private val DO = verb("کرد", listOf("کن"))
        private val PUT = verb("گذاشت", listOf("ذار", "گذار", "زار"))
        private val SOCIAL = setOf(
            "پیج", "کامنت", "دایرکت", "فالو", "فالوور", "لایک", "استوری", "ریلز", "اینستاگرام", "اینستا", "کلیپ", "ویدیو", "ویدئو",
            "یوتیوب", "تلگرام", "کانال", "هشتگ", "کپشن",
        )
        private val CRYPTO = setOf(
            "تتر", "ارز", "کریپتو", "بیت‌کوین", "بیتکوین", "صرافی", "تریدر", "ترید", "معامله", "فیوچرز", "اتریوم", "توکن", "ووچر", "بایننس", "والت",
        )
        private val TECH = setOf(
            "کد", "کود", "ریپو", "ریپور", "پروژه", "مدل", "مودل", "کلاد", "توکن", "پرامپت", "ربات", "برنامه", "سایت", "اپ", "هوش", "تسک", "سرور", "گیت",
        )
        private const val TOPIC_WORDS = 2
        private val HE_CLITICS = listOf("ت", "م", "ش", "تو", "مو", "شو", "تون", "مون", "شون")

        /** Mishearings seen in real runs; conservative on purpose («لایو»/«لایک» are both real and both follow «کردن»). */
        private val CONFUSIONS = listOf(
            Confusion("پوست", "پست", after = PUT, afterOnTopic = DO, topic = SOCIAL),
            Confusion("ترک", "ترید", afterOnTopic = DO, topic = CRYPTO, notAfter = setOf("رو", "را", "از")),
            Confusion("کارورد", "کاربر"),
            // «تیه» (a wilderness) is never done or made: «تیه می‌کنه» is «تهیه می‌کنه».
            Confusion("تیه", "تهیه", after = DO),
            // Lineage is never installed: «نسب بکنه» is «نصب بکنه», and then «بعد از نسب» too.
            Confusion("نسب", "نصب", after = DO + setOf("شد", "شده", "شدن", "میشه", "بشه"), sticky = true),
            // In a talk about code, the fertiliser is the code: «کلمه کود رو بفرست».
            Confusion("کود", "کد", topic = TECH),
        )

        /** Number words whisper writes as another word, or as none. */
        private val NUMBER_SLIPS = mapOf(
            "سد" to "صد", "شست" to "شصت", "ازار" to "هزار", "دویس" to "دویست", "هشتا" to "هشتاد", "چارصد" to "چهارصد", "پونزه" to "پونزده", "دوازه" to "دوازده",
        )
        /** Words that measure a number said before them. */
        private val MEASURES = setOf(
            "ساعت", "دقیقه", "ثانیه", "روز", "هفته", "ماه", "سال", "نفر", "متر", "کیلو", "گرم", "درصد", "تومن", "تومان", "دلار", "واحد", "برابر",
            "تتر", "تا", "بار", "عدد", "درصدش", "درصده", "درصدی", "ست",
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
