package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.NumberWords

/**
 * Whether a text on screen reads on its own. A title, a pop or a stamp is a noun phrase («کارهای
 * پیچیده», «محدودیت مصرف»), a key claim, a number with its unit or a name — never a piece cut out
 * of a sentence: not one that ends on a modal, a quantifier, a light verb's noun without its verb,
 * an ezafe or an object marker waiting for what follows («باید استفاده», «زیاد استفاده», «درد اکثر
 * کارای», «پروژه تهیه», «دیرتر جوابتونو»), not one that starts mid-phrase on a connector, an adverb or
 * a verb («همینجوری سخت», «وارد مسئله», «بکنید و …»), and no lone word that only points («اکثرشو»,
 * «تازه»). A quote is a whole sentence as said: it starts where a sentence starts and ends where it ends.
 */
internal object Fragments {

    /** Treatments whose text is a title: it must read on its own. */
    val TITLES = setOf("slam", "mask-rise", "type-on", "blur-in", "flip", "spread", "stack", "glitch", "stamp", "headline", "callout", "chapter", "burst")

    /** Why [text] does not read on its own, or null when it does. Latin text and figures are judged elsewhere. */
    fun fault(text: String): String? {
        val words = text.split(' ').map(::bare).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.none { persian(it) }) return null
        if (words.any { w -> w.any { it.isDigit() } }) return null
        val first = words.first()
        val last = words.last()
        return when {
            words.size == 1 -> lone(first)
            first in OPENERS || first in ADVERBS || first in QUANTIFIERS || first in MODALS -> "starts mid-phrase on «$first»"
            first in COMPLEMENTS -> "starts on «$first» without its verb"
            Phrases.verb(key(first)) -> "starts on the verb «$first»"
            last in CLOSERS || last in ADVERBS || last in QUANTIFIERS || last in MODALS -> "ends on «$last»"
            last in COMPLEMENTS -> "ends on «$last» without its verb"
            (Phrases.verb(key(last)) || last in LIGHT) && !whole(words) -> "ends on the verb «$last»"
            dangling(last) || pointer(last) -> "ends on «$last», waiting for what follows"
            words.any { clause(it) || it in PREDICATES } -> "says a clause, not a phrase («${words.first { clause(it) || it in PREDICATES }}»)"
            // «کانال‌ها تبلیغات»: a bare plural then another noun — two nouns side by side, no phrase between them.
            words.size == 2 && plural(first) && Phrases.nounish(last) -> "two nouns side by side («$first» «$last»)"
            else -> null
        }
    }

    /** Whether [w] can carry a phrase: not a connector, adverb, quantifier, modal, light verb or half of a compound verb. */
    fun content(w: String): Boolean {
        val b = bare(w)
        return b !in OPENERS && b !in CLOSERS && b !in ADVERBS && b !in QUANTIFIERS && b !in MODALS && b !in COMPLEMENTS && b !in LIGHT
    }

    /**
     * Whether the words [range] of [texts] are a whole sentence as said, fit to quote: it starts after
     * a sentence end (or at the first word) and its last word ends one, and it opens on no connector or verb.
     */
    fun quotable(texts: List<String>, range: IntRange): Boolean {
        if (range.isEmpty()) return false
        val starts = range.first == 0 || texts.getOrNull(range.first - 1)?.trimEnd()?.lastOrNull()?.let { it in SENTENCE_END } == true
        val ends = texts.getOrNull(range.last)?.trimEnd()?.lastOrNull()?.let { it in SENTENCE_END } == true
        val first = bare(texts[range.first])
        return starts && ends && first !in OPENERS && first !in COMPLEMENTS && !Phrases.verb(key(first))
    }

    /** A short compound verb said whole: «عجله کنید», «ثبت‌نام کنید», «فالو کن» (a noun, then its light verb). */
    private fun whole(words: List<String>): Boolean = words.size in 2..3 && words.last() in LIGHT && words[words.size - 2].let { it in COMPLEMENTS || Phrases.nounish(it) }

    /** A lone word stands only when it names something: a common noun as written, or a term. */
    private fun lone(w: String): String? = when {
        w in ADVERBS || w in QUANTIFIERS || w in MODALS || w in OPENERS || w in CLOSERS || w in PRONOUNS -> "«$w» alone says nothing"
        Phrases.verb(key(w)) || w in LIGHT || w in COMPLEMENTS -> "«$w» alone is half a verb"
        Loanwords.protected(w) -> null
        // «سخت‌ترین» alone: the hardest what?
        w.replace("\u200C", "").endsWith("ترین") || w.endsWith("\u200Cتر") -> "«$w» alone: a degree of what?"
        !Phrases.nounish(w) -> "«$w» alone says nothing"
        dangling(w) || CLITIC_ENDS.any { w.endsWith(it) && w.length - it.length >= 2 } || clitic(w) -> "«$w» points at something not shown"
        clause(w) || copula(w) || w in PREDICATES -> "«$w» alone is a clause"
        pointer(w) -> "«$w» points at something not shown"
        // «بالاتر»: a comparative of a common word, alone.
        graded(w) -> "«$w» alone: a degree of what?"
        else -> null
    }

    /** [w] waits for the word after it (an ezafe, an object marker). */
    fun waits(w: String): Boolean = dangling(bare(w))

    /** An ezafe or an object marker at the end: «کارای», «قابلیتهای», «جوابتونو» wait for a noun or a verb. */
    private fun dangling(w: String): Boolean {
        val plain = w.replace("‌", "")
        // «کارای»: a colloquial plural with its ezafe (never «حرفه‌ای», whose «ای» follows a half-space).
        val spoken = w.endsWith("ای") && !w.endsWith("\u200Cای") && w.length >= 4 && Phrases.nounish(w.dropLast(2))
        val ezafe = spoken || EZAFE.any { plain.endsWith(it) && plain.length - it.length >= 2 }
        val marked = OBJECT.any { plain.endsWith(it) && plain.length - it.length >= 2 } || objectMarked(plain)
        return ezafe || marked || w in setOf("رو", "را")
    }

    /** The word list, when it is loaded (some checks need it; without it they are skipped). */
    private val lex: PersianSpelling? get() = PersianSpelling.shared

    private fun count(w: String) = lex?.count(w) ?: 0

    /** «کانالو», «شرایطو»: a common word with the spoken object marker «و» (the word itself rare as written). */
    private fun objectMarked(plain: String): Boolean =
        plain.length >= MIN_MARKED && plain.endsWith('و') && count(plain.dropLast(1)) >= COMMON && count(plain) < COMMON

    /** A one-letter possessive or «too» on a common word, the whole rarer as written: «دوستانم», «اونم» — pointing back at something. */
    private fun clitic(w: String): Boolean {
        val plain = w.replace("\u200C", "")
        return plain.length >= MIN_MARKED && plain.last() in "مت" && count(plain.dropLast(1)) >= COMMON && count(plain) < count(plain.dropLast(1)) / RARER
    }

    /** A copula on a word: «ورودیمونه» (our entry is), «چجوریه» (how is it) — a clause, not a title. */
    private fun clause(w: String): Boolean = COPULAS.any { w.replace("\u200C", "").endsWith(it) && w.length - it.length >= 1 }

    /** «خوبه», «سخته»: a common word with the spoken copula «ه», the whole far rarer as written (never «خونه»). */
    private fun copula(w: String): Boolean {
        val plain = w.replace("\u200C", "")
        return plain.length >= MIN_MARKED - 1 && plain.endsWith('ه') && count(plain.dropLast(1)) >= COMMON && count(plain) < count(plain.dropLast(1)) / RARER
    }

    /** «سودشون», «واقعیتشون»: a possessive on a common word — whose? */
    private fun pointer(w: String): Boolean {
        val plain = w.replace("\u200C", "")
        return POSSESSIVES.any { p -> plain.endsWith(p) && plain.length - p.length >= 2 && count(plain.dropLast(p.length)) >= COMMON && count(plain) < COMMON }
    }

    private val POSSESSIVES = listOf("شون", "تون", "مون")

    /** Colloquial predicates, an adjective with its copula: «خوبه» (it is good) is a clause, not a title. */
    private val PREDICATES = setOf(
        "خوبه", "بده", "عالیه", "بهتره", "بدتره", "سخته", "راحته", "آسونه", "مهمه", "کافیه", "لازمه", "ممکنه", "درسته", "معلومه", "واضحه",
        "خوبن", "بدن", "عالین", "رایگانه", "ارزونه", "گرونه", "امنه", "مطمئنه",
    )

    /** A bare plural, no ezafe: «کانال‌ها», «تبلیغات‌ها». */
    private fun plural(w: String): Boolean = w.endsWith("\u200Cها") || (w.endsWith("ها") && count(w.dropLast(2)) >= COMMON)

    /** A comparative of a common word: «بالاتر», «سخت‌تر» (never «دختر», «دفتر»: their stems are no common word). */
    private fun graded(w: String): Boolean {
        val plain = w.replace("\u200C", "")
        return plain.endsWith("تر") && plain.length >= MIN_MARKED && count(plain.dropLast(2)) >= COMMON
    }

    private const val COMMON = 1000
    private const val RARER = 20
    private const val MIN_MARKED = 4
    /** Pronouns, colloquial forms too: alone they point at what is not shown. */
    private val PRONOUNS = setOf("اون", "این", "اونم", "اینم", "اونا", "اینا", "ما", "من", "شما", "اونها", "اینها", "خودم", "خودمون", "خودشون")
    private val COPULAS = listOf("مونه", "تونه", "شونه", "جوریه", "طوریه", "چیه", "کیه", "کجاست")

    private fun bare(w: String) = w.trim { !it.isLetterOrDigit() && it != '‌' }.trim('‌')

    private fun key(w: String) = NumberWords.normalize(w).replace("‌", "")

    private fun persian(w: String) = w.any { it in '؀'..'ۿ' }

    private val SENTENCE_END = setOf('.', '!', '?', '؟', '…')

    /** Words that join a phrase to what came before: a text never starts on one. */
    private val OPENERS = setOf(
        "و", "که", "یا", "تا", "ولی", "اما", "چون", "پس", "را", "رو", "هم", "از", "به", "با", "در", "برای", "بعد", "قبل", "اگه", "اگر", "مثل",
        "تو", "توی", "روی", "بین", "واسه", "یعنی", "وقتی", "شاید", "حتی", "فقط", "دیگه", "مورد", "مثلا", "مثلاً", "چجوری", "چطوری", "چطور", "چرا",
        "کی", "کجا", "چی", "چه",
    )

    /** Words that wait for what follows: a text never ends on one. */
    private val CLOSERS = OPENERS + setOf("این", "اون", "آن", "یه", "یک", "هر", "چند", "همه", "کل", "همین", "همون", "خود", "خودش", "خودت", "مورد", "درباره")

    private val ADVERBS = setOf(
        "همینجوری", "همین‌جوری", "اینجوری", "اونجوری", "خیلی", "تقریبا", "تقریباً", "حتما", "حتماً", "واقعا", "واقعاً", "اصلا", "اصلاً", "کلا", "دقیقا",
        "تازه", "الان", "حالا", "هنوز", "همیشه", "زودتر", "دیرتر", "سریعتر", "سریع‌تر", "راحت", "آروم", "آسون", "بسیار", "کاملا", "کاملاً", "معمولا",
        "اولش", "آخرش", "بعدش", "قبلش", "اینجا", "اونجا", "همونجا", "اینجاست", "اونجاست", "دوباره", "باز",
    )

    private val QUANTIFIERS = setOf("زیاد", "کم", "اکثر", "بیشتر", "کمتر", "بیشتری", "اکثرشو", "اکثرش", "بیشترش", "همش", "همه‌ش", "کلی", "یکم", "یه‌کم", "چندتا", "خیلیا")

    private val MODALS = setOf("باید", "نباید", "میشه", "می‌شه", "نمیشه", "نمی‌شه", "میتونه", "می‌تونه", "میتونی", "می‌تونی", "میخوای", "می‌خوای", "بخوای")

    /** Nouns of compound verbs: «استفاده» (کردن), «فکر» (کردن), «وارد» (شدن) — half a verb without it. */
    private val COMPLEMENTS = setOf(
        "استفاده", "فکر", "انجام", "شروع", "پیدا", "درست", "دریافت", "ارسال", "برداشت", "تهیه", "نصب", "ثبت", "وارد", "تموم", "تمام", "کمک", "صحبت",
        "عوض", "پر", "خالی", "باز", "بسته", "گیر", "پیاده", "سوار", "حساب", "چک", "کامنت", "فالو", "لایک", "دنبال", "ادامه", "تغییر",
    )

    /** Light verbs and the copula, colloquial forms included. */
    private val LIGHT = setOf(
        "است", "هست", "هستش", "هستن", "نیست", "بود", "شد", "شده", "شدن", "کرد", "کرده", "کن", "کنید", "کنی", "بکن", "بکنید", "بکنه", "بشه",
        "میکنه", "می‌کنه", "میده", "می‌ده", "داره", "دارن", "میشه", "می‌شه", "باشید", "باشه", "بده", "بدی", "میدی", "می‌دی", "میخوره", "می‌خوره",
    )

    /** A plural or noun with its ezafe, colloquial or written. */
    private val EZAFE = listOf("های", "هایی")

    /** The object marker on the word, colloquial: «جوابتونو», «کارشو». */
    private val OBJECT = listOf("تونو", "شونو", "مونو")

    /** Clitics that point at something said before: «اکثرشو», «مسائلیه». */
    private val CLITIC_ENDS = listOf("شو", "تو", "مو", "یه", "شون")
}
