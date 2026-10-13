package io.trimio.engine.autopilot

/**
 * Words the word list does not know (or knows too rarely) but every Persian creator says: the
 * loanwords and terms of tech, AI, crypto, forex and social media («توکن», «ریپو», «پرامپت», «تتر»,
 * «ووچر», «کامنت»…) and product names as they are written in Persian («کلاد», «اوپس», «جمینای»).
 * They are protected: a spelling fix never changes or splits one («توکن» is never «تو کن», «اوپس»
 * never «او پس»), they count as correct on screen, and the recogniser's usual slips of a few of
 * them are put back as the term ([alias]: «کلادکود» → «کلاد کد»).
 */
object Loanwords {

    private val TECH = setOf(
        "توکن", "توکنها", "ریپو", "ریپازیتوری", "پرامپت", "پرامت", "تسک", "کد", "کدها", "کدنویسی", "اپ", "اپلیکیشن", "وب‌سایت", "سایت", "لینک",
        "بات", "ربات", "چت‌بات", "چتبات", "دیتابیس", "دیتا", "سرور", "کلاینت", "فرانت", "بک‌اند", "بکند", "فرانت‌اند", "پایتون", "جاوااسکریپت",
        "اسکریپت", "ترمینال", "دیباگ", "باگ", "فیچر", "آپدیت", "دانلود", "آپلود", "پلاگین", "اکستنشن", "ایجنت", "اجنت",
        "کامیت", "گیت", "گیت‌هاب", "گیتهاب", "فریمورک", "لاگ", "کانفیگ", "دیپلوی", "API", "ای‌پی‌آی", "وایب", "کدبیس", "ورک‌فلو", "اتوماسیون",
        "تمپلیت", "داشبورد", "پنل", "اکانت", "لاگین", "پسورد", "ایمیل", "نوتیفیکیشن", "استارتاپ", "ادمین", "یوزر", "سابسکریپشن",
    )
    private val CRYPTO = setOf(
        "تتر", "ترید", "تریدر", "تریدینگ", "ووچر", "کمپین", "بیت‌کوین", "بیتکوین", "اتریوم", "آلت‌کوین", "آلتکوین", "صرافی", "والت", "فیوچرز",
        "اسپات", "لوریج", "مارجین", "لانگ", "شورت", "پوزیشن", "سیگنال", "چارت", "کندل", "اندیکاتور", "فارکس", "بروکر", "پیپ", "لات", "اسپرد",
        "استاپ", "استاپ‌لاس", "تارگت", "ایردراپ", "استیکینگ", "ماینینگ", "بلاکچین", "میم‌کوین", "پامپ", "دامپ", "هولد", "هاوینگ", "کوین",
        "توکنومیکس", "لیکوئید", "لیکویید", "ریفرال", "پرتفو", "پورتفولیو", "اسکالپ", "سولانا", "دوج‌کوین", "ریپل", "کاردانو", "تون‌کوین",
    )
    private val SOCIAL = setOf(
        "کامنت", "دایرکت", "فالو", "فالوور", "فالوئر", "آنفالو", "لایک", "استوری", "ریلز", "پیج", "پست", "کپشن", "هشتگ", "لایو",
        "تگ", "منشن", "شیر", "سیو", "ویو", "اینستاگرام", "اینستا", "تلگرام", "یوتیوب", "تیک‌تاک", "تیکتاک", "کانال", "ساب‌اسکرایب", "کلیپ",
        "ویدیو", "ویدئو", "پادکست", "اینفلوئنسر", "کانتنت", "ادیت", "تامبنیل", "واتساپ", "توییتر", "لینکدین", "دیسکورد",
    )

    /** Products and makers as they are written in Persian. */
    private val PRODUCTS = setOf(
        "کلاد", "کدکس", "اوپن‌کد", "جمینای", "جمنای", "چت‌جی‌پی‌تی", "جی‌پی‌تی", "دیپ‌سیک", "دیپسیک", "کوپایلت", "کرسر", "اوپس", "سانت",
        "سونت", "هایکو", "آنتروپیک", "اوپن‌ای‌آی", "بایننس", "نوبیتکس", "کوین‌بیس", "متاتریدر", "تریدینگ‌ویو", "اپل", "گوگل", "مایکروسافت",
        "آیفون", "اندروید", "سامسونگ", "انویدیا", "متا", "ایکس", "لاما", "میدجرنی", "کپ‌کات", "کپکات",
    )


    /** The term two heard words stand for when they are a term written as one: «چت جی‌پی‌تی» → «چت‌جی‌پی‌تی». */
    internal fun pair(a: String, b: String): String? = ALL_WRITTEN.firstOrNull { plain(it) == plain(a) + plain(b) && ZWNJ in it }

    private val ALL_WRITTEN: Set<String> = TECH + CRYPTO + SOCIAL + PRODUCTS
    private val ALL: Set<String> = ALL_WRITTEN.map(::plain).toSet()

    /** Endings a protected term may carry and stay itself («توکن‌ها», «ریپوتو», «کامنتاتون»). */
    private val ENDINGS = listOf(
        "هاتون", "هاشون", "هامون", "های", "ها", "هایی", "ای", "اتون", "اشون", "امون", "ات", "اش", "ام", "تون", "شون", "مون", "تو", "شو", "مو",
        "رو", "ی", "و", "ت", "ش", "م", "ه", "ا", "یه", "ست", "تر", "ترین",
    )

    /**
     * The recogniser's initial prompt: one well-punctuated Persian sentence naming the terms the
     * talk is likely to use — the brief's own names and terms first, then the glossary of each
     * field the brief touches (crypto and forex, tech and AI, social media; all three when it says
     * nothing) — within [PROMPT_BYTES] (whisper reads at most 224 prompt tokens; Persian takes about
     * one per two bytes). Conditioned on the terms, the recogniser writes «توکن», «ریپو», «نصب»
     * instead of «تو کن», «ریپور», «نسب».
     */
    fun prompt(brief: String): String {
        val b = brief.lowercase()
        val named = Regex("[A-Za-z][A-Za-z0-9.+-]*(?: [A-Z][A-Za-z0-9.+-]*)*").findAll(brief).map { it.value }.toList() +
            Regex("«([^»]{2,24})»").findAll(brief).map { it.groupValues[1] }.toList()
        val fields = FIELDS.filter { (cue, _) -> cue.any { it in b } }.map { it.second }.ifEmpty { FIELDS.map { it.second } }
        val terms = (named + interleave(fields)).distinct()
        val head = "در این ویدیو درباره‌ی "
        val tail = " صحبت می‌کنم."
        val out = mutableListOf<String>()
        for (t in terms) {
            val next = head + (out + t).joinToString("، ") + tail
            if (next.encodeToByteArray().size > PROMPT_BYTES) break
            out += t
        }
        return if (out.isEmpty()) "سلام دوستان، امروز می‌خواهم یک نکته‌ی مهم را بگویم." else head + out.joinToString("، ") + tail
    }

    /** One term of each field in turn, so every field the brief touches gets its share of the prompt. */
    private fun interleave(lists: List<List<String>>): List<String> =
        (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }

    /** The terms a talk in each field says most, most common first (the prompt keeps the head of each). */
    private val FIELDS: List<Pair<List<String>, List<String>>> = listOf(
        listOf("کریپتو", "ترید", "ارز", "بیت", "فارکس", "بورس", "صرافی", "crypto", "trad", "forex", "bitcoin") to listOf(
            "ترید", "تتر", "صرافی", "بیت‌کوین", "اتریوم", "ووچر", "کمپین", "سیگنال", "چارت", "فیوچرز", "لوریج", "والت", "آلت‌کوین", "پوزیشن", "استاپ‌لاس",
        ),
        listOf("هوش", "ربات", "کد", "برنامه", "اپ", "سایت", "مدل", "ai", "bot", "code", "app", "gpt") to listOf(
            "هوش مصنوعی", "مدل", "توکن", "ریپو", "پرامپت", "تسک", "کد", "نصب", "ربات", "اپلیکیشن", "کلاد", "چت‌جی‌پی‌تی", "جمینای", "ایجنت",
        ),
        listOf("تلگرام", "اینستا", "یوتیوب", "پیج", "کانال", "ریلز", "کامنت", "telegram", "instagram", "youtube") to listOf(
            "کامنت", "دایرکت", "فالو", "پیج", "پست", "استوری", "ریلز", "لایک", "کانال", "تلگرام", "اینستاگرام", "یوتیوب",
        ),
    )

    private const val PROMPT_BYTES = 420

    /** [word] (punctuation aside) is a protected term, or one with a clitic or plural ending. */
    internal fun protected(word: String): Boolean {
        val p = plain(word.trim { !it.isLetterOrDigit() && it != ZWNJ })
        if (p.isEmpty()) return false
        if (p in ALL) return true
        return ENDINGS.any { e -> p.endsWith(e) && p.length - e.length >= MIN_STEM && p.dropLast(e.length) in ALL }
    }

    /**
     * The term an unknown heard word stands for: the term written without its half-spaces
     * («اوپنکد» → «اوپن‌کد»), or with a long vowel the term does not have («کودکس» → «کدکس»,
     * «اوپنکود» → «اوپن‌کد»), or two terms run together («کلادکود» → «کلاد کد»); null otherwise.
     */
    internal fun alias(word: String): String? {
        val p = plain(word.trim { !it.isLetterOrDigit() && it != ZWNJ })
        if (p.length < MIN_ALIAS || protected(p)) return null
        return term(p) ?: (MIN_STEM + 1..p.length - MIN_STEM).firstNotNullOfOrNull { k ->
            val a = term(p.substring(0, k)) ?: return@firstNotNullOfOrNull null
            term(p.substring(k))?.let { "$a $it" }
        }
    }

    /** The written term [p] (half-spaces aside) is, or is with one long vowel too many. */
    private fun term(p: String): String? {
        if (p.length < MIN_STEM) return null
        val options = listOf(p) + (1 until p.length).filter { p[it] in "وای" }.map { p.removeRange(it, it + 1) }
        // Or the term's last letter unheard: «دایرک» is «دایرکت».
        return options.firstNotNullOfOrNull { o -> ALL_WRITTEN.firstOrNull { plain(it) == o } }
            ?: ALL_WRITTEN.firstOrNull { plain(it).length >= MIN_ALIAS + 1 && plain(it).dropLast(1) == p }
    }

    private const val MIN_ALIAS = 4

    private const val ZWNJ = '‌'
    private const val MIN_STEM = 2

    /** Half-spaces and Arabic letter forms aside, as the word list's lookups are. */
    private fun plain(w: String) = PersianSpelling.normalize(w).replace(ZWNJ.toString(), "")
}
