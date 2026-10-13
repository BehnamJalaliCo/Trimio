package io.trimio.engine.autopilot

/**
 * Words the word list does not know (or knows too rarely) but every Persian creator says: the
 * loanwords and terms of tech, AI, crypto, forex and social media («توکن», «ریپو», «پرامپت», «تتر»,
 * «ووچر», «کامنت»…) and product names as they are written in Persian («کلاد», «اوپس», «جمینای»).
 * They are protected: a spelling fix never changes or splits one («توکن» is never «تو کن», «اوپس»
 * never «او پس»), they count as correct on screen, and the recogniser's usual slips of a few of
 * them are put back as the term ([ALIASES]: «کلادکود» → «کلاد کد»).
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

    /** The recogniser's spellings of terms, as the term is written. */
    val ALIASES: Map<String, String> = mapOf(
        "کلادکود" to "کلاد کد", "کلادکد" to "کلاد کد", "کودکس" to "کدکس", "اوپنکود" to "اوپن‌کد", "اوپن‌کود" to "اوپن‌کد", "اوپنکد" to "اوپن‌کد",
        "ریپور" to "ریپو", "پرامپ" to "پرامپت",
    )

    /** Two heard words that are one term («چچی پیتیت» is ChatGPT). */
    val PAIRS: Map<Pair<String, String>, String> = mapOf(
        ("چچی" to "پیتیت") to "چت‌جی‌پی‌تی", ("چت" to "جی‌پی‌تی") to "چت‌جی‌پی‌تی", ("چت" to "جیپیتی") to "چت‌جی‌پی‌تی", ("کلاد" to "کود") to "کلاد کد",
    )

    /** The term two heard words stand for, or null. */
    fun pair(a: String, b: String): String? = PAIRS[plain(a) to plain(b)] ?: PAIRS.entries.firstOrNull { (k, _) -> plain(k.first) == plain(a) && plain(k.second) == plain(b) }?.value

    private val ALL: Set<String> = (TECH + CRYPTO + SOCIAL + PRODUCTS).map(::plain).toSet()

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

    /** The term a recognised spelling stands for (several words when the term is: «کلاد کد»), or null. */
    fun alias(word: String): String? = ALIASES[plain(word.trim { !it.isLetterOrDigit() && it != ZWNJ })]

    private const val ZWNJ = '‌'
    private const val MIN_STEM = 2

    /** Half-spaces and Arabic letter forms aside, as the word list's lookups are. */
    private fun plain(w: String) = PersianSpelling.normalize(w).replace(ZWNJ.toString(), "")
}
