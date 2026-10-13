package io.trimio.engine.autopilot

/**
 * Words the word list does not know (or knows too rarely) but every Persian creator says: the
 * loanwords and terms of tech, AI, crypto, forex and social media («توکن», «ریپو», «پرامپت», «تتر»,
 * «ووچر», «کامنت»…) and product names as they are written in Persian («کلاد», «اوپس», «جمینای»).
 * They are protected: a spelling fix never changes or splits one («توکن» is never «تو کن», «اوپس»
 * never «او پس»), they count as correct on screen, and the recogniser's usual slips of a few of
 * them are put back as the term ([alias]: «کلادکود» → «کلاد کد»).
 */
internal object Loanwords {

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
    fun pair(a: String, b: String): String? = ALL_WRITTEN.firstOrNull { plain(it) == plain(a) + plain(b) && ZWNJ in it }

    private val ALL_WRITTEN: Set<String> = TECH + CRYPTO + SOCIAL + PRODUCTS
    private val ALL: Set<String> = ALL_WRITTEN.map(::plain).toSet()

    /** Endings a protected term may carry and stay itself («توکن‌ها», «ریپوتو», «کامنتاتون»). */
    private val ENDINGS = listOf(
        "هاتون", "هاشون", "هامون", "های", "ها", "هایی", "ای", "اتون", "اشون", "امون", "ات", "اش", "ام", "تون", "شون", "مون", "تو", "شو", "مو",
        "رو", "ی", "و", "ت", "ش", "م", "ه", "ا", "یه", "ست", "تر", "ترین",
    )

    /** [word] (punctuation aside) is a protected term, or one with a clitic or plural ending. */
    fun protected(word: String): Boolean {
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
    fun alias(word: String): String? {
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
        return options.firstNotNullOfOrNull { o -> ALL_WRITTEN.firstOrNull { plain(it) == o && it.length >= MIN_ALIAS - 1 } }
    }

    private const val MIN_ALIAS = 4

    private const val ZWNJ = '‌'
    private const val MIN_STEM = 2

    /** Half-spaces and Arabic letter forms aside, as the word list's lookups are. */
    private fun plain(w: String) = PersianSpelling.normalize(w).replace(ZWNJ.toString(), "")
}
