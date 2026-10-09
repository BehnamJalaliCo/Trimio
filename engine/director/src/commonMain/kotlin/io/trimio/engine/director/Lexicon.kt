package io.trimio.engine.director

/**
 * Bilingual (Persian + English) word lists the rules engine reasons with. Every entry and every
 * lookup goes through [Lexicon.norm], so spelling variants (ي/ی, ك/ک, ZWNJ, diacritics, case,
 * punctuation) all match the same key.
 */
object Lexicon {

    /** Canonical matching key for a word or phrase. */
    fun norm(text: String): String = buildString(text.length) {
        for (ch in text.lowercase()) {
            when (ch) {
                'ي', 'ى' -> append('ی') // Arabic yeh / alef maksura → Persian yeh
                'ك' -> append('ک') // Arabic kaf → Persian keheh
                'ة' -> append('ه') // teh marbuta → heh
                'أ', 'إ', 'آ' -> append('ا') // hamza/madda forms → alef
                '\u200C', '\u200D', '\u200F', '\u200E', 'ـ' -> Unit // ZWNJ/ZWJ/marks/tatweel
                in '\u064B'..'\u065F', '\u0670' -> Unit // harakat
                else -> if (ch.isLetterOrDigit() || ch == '%' || ch == '$') append(ch)
            }
        }
    }

    private fun set(vararg words: String) = words.map(::norm).toSet()

    enum class Topic(val tags: List<String>) {
        Crypto(listOf("crypto", "کریپتو", "finance", "مالی")),
        Forex(listOf("forex", "فارکس", "finance", "مالی")),
        Trading(listOf("finance", "مالی", "crypto")),
        Business(listOf("finance", "startup", "premium")),
        Tech(listOf("tech", "cinematic")),
        Education(listOf("education", "tutorial", "آموزشی")),
        Motivation(listOf("motivation", "انگیزشی", "energetic")),
        Ad(listOf("ad", "تبلیغاتی", "bold")),
    }

    val topics: Map<Topic, Set<String>> = mapOf(
        Topic.Crypto to set(
            "بیتکوین", "کریپتو", "اتریوم", "سولانا", "تتر", "آلتکوین", "توکن", "بلاکچین", "رمزارز", "میمکوین",
            "btc", "eth", "bitcoin", "ethereum", "crypto", "solana", "usdt", "altcoin", "token", "nft", "blockchain", "memecoin", "bnb", "xrp",
        ),
        Topic.Forex to set(
            "فارکس", "یورو", "طلا", "انس", "پیپ", "لات", "اسپرد", "لوریج", "بروکر", "جفتارز",
            "forex", "eurusd", "gbpusd", "xauusd", "gold", "pip", "pips", "lot", "spread", "leverage", "broker",
        ),
        Topic.Trading to set(
            "سیگنال", "ترید", "تریدر", "تارگت", "استاپ", "استاپلاس", "حدضرر", "حمایت", "مقاومت", "لانگ", "شورت", "پوزیشن", "چارت", "کندل", "بورس", "سهام",
            "signal", "trade", "trading", "trader", "target", "stoploss", "support", "resistance", "long", "short", "position", "chart", "candle", "stocks",
        ),
        Topic.Business to set("کسبوکار", "بیزینس", "استارتاپ", "فروش", "مشتری", "برند", "درآمد", "business", "startup", "sales", "customer", "brand", "revenue", "marketing"),
        Topic.Tech to set("هوشمصنوعی", "تکنولوژی", "اپلیکیشن", "برنامهنویسی", "گوشی", "ai", "tech", "technology", "app", "software", "coding", "iphone", "android"),
        Topic.Education to set("آموزش", "یادبگیریم", "درس", "نکته", "ترفند", "قدمبهقدم", "tutorial", "learn", "lesson", "tip", "tips", "howto", "guide", "step"),
        Topic.Motivation to set("انگیزه", "موفقیت", "هدف", "رویا", "تلاش", "motivation", "success", "goal", "dream", "hustle", "mindset"),
        Topic.Ad to set("تخفیف", "خرید", "پیشنهاد", "فروشویژه", "رایگان", "sale", "discount", "offer", "free", "deal", "promo"),
    )

    val rise = set("رشد", "صعود", "بالا", "افزایش", "سود", "پامپ", "صعودی", "ریباند", "سبز", "up", "rise", "rising", "growth", "gain", "gains", "profit", "bullish", "pump", "bounce", "breakout", "green", "moon")
    val fall = set("ریزش", "سقوط", "کاهش", "ضرر", "دامپ", "نزولی", "اصلاح", "قرمز", "down", "fall", "falling", "drop", "crash", "loss", "bearish", "dump", "correction", "red")
    val signal = set("سیگنال", "signal", "هشدارخرید", "الرت", "alert")
    val coin = set("بیتکوین", "btc", "bitcoin", "کوین", "coin", "اتریوم", "eth", "ethereum", "رمزارز")
    val gold = set("طلا", "gold", "xauusd", "انس")
    val warning = set("هشدار", "ریسک", "خطر", "مراقب", "احتیاط", "warning", "risk", "danger", "careful", "caution")
    val success = set("موفق", "موفقیت", "تایید", "رسید", "خورد", "انجامشد", "success", "successful", "done", "completed", "confirmed", "hit", "reached")
    val star = set("بهترین", "عالی", "فوقالعاده", "ویژه", "طلایی", "شاهکار", "best", "top", "amazing", "awesome", "special", "golden", "incredible")
    val love = set("عشق", "قلب", "عاشق", "love", "heart", "favorite")
    val cta = set(
        "فالو", "لایک", "کامنت", "سابسکرایب", "اشتراک", "لینک", "بیو", "پیج", "کانال", "عضو", "ذخیره", "دانلود", "ثبتنام", "دایرکت", "شیر",
        "follow", "like", "comment", "subscribe", "share", "link", "bio", "page", "channel", "join", "save", "download", "register", "signup", "dm",
    )

    val percent = set("درصد", "%", "percent", "٪")
    val dollar = set("دلار", "$", "dollar", "dollars", "usd")
    val toman = set("تومان", "تومن", "toman")

    val stopwords = set(
        "و", "در", "به", "از", "که", "را", "رو", "با", "این", "آن", "اون", "یه", "هم", "برای", "تا", "بر", "یا", "اما", "ولی", "اگر", "اگه", "هر",
        "من", "تو", "ما", "شما", "او", "اونها", "آنها", "است", "هست", "بود", "شد", "میشه", "نمی", "می", "دیگه", "چی", "چه", "کنه", "کرد", "کنیم", "کنید", "داره", "دارم",
        "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "is", "are", "was", "were", "be", "been", "it", "its", "this",
        "that", "i", "you", "we", "they", "he", "she", "my", "your", "our", "so", "as", "by", "from", "just", "do", "does", "did", "have", "has",
    )

    // --- Prompt vocabulary ---------------------------------------------------------------------

    val energetic = set("پرانرژی", "انرژی", "هیجانی", "هیجان", "سریع", "تند", "وایرال", "ریلز", "تیکتاک", "شورتس", "جذاب", "پرهیجان", "بولد", "داینامیک", "پویا",
        "energetic", "energy", "hype", "fast", "viral", "reels", "tiktok", "shorts", "catchy", "punchy", "bold", "dynamic", "exciting")
    val calm = set("آرام", "آروم", "ملایم", "مینیمال", "لوکس", "شیک", "رسمی", "سینمایی", "آهسته",
        "calm", "minimal", "slow", "soft", "luxury", "elegant", "formal", "cinematic", "subtle", "chill")
    val noMusic = listOf("بدون موزیک", "بدون آهنگ", "بدون موسیقی", "موزیک نذار", "آهنگ نذار", "no music", "without music", "no background music")
    val noSfx = listOf("بدون افکت صوتی", "بدون صدای افکت", "بدون sfx", "no sfx", "no sound effects", "without sound effects")
    val keepPauses = listOf("کات نزن", "بدون کات", "سکوت ها رو نگه", "سکوتها را نگه", "don't cut", "do not cut", "no cuts", "keep pauses", "keep silences")
    val keepFillers = listOf("اممم ها رو نگه", "fillers stay", "keep fillers", "keep ums")

    /** Style names and nicknames people actually type, mapped to style ids. */
    val styleAliases: Map<String, String> = buildMap {
        fun alias(id: String, vararg names: String) = names.forEach { put(norm(it), id) }
        alias("liquid-glass", "لیکوئید", "لیکویید", "liquid", "liquidglass", "شیشه", "شیشهای", "اپل")
        alias("glassmorphism", "گلس", "glass", "گلسمورفیسم", "glassmorphism")
        alias("neobrutalism", "نئوبروتال", "نئوبروتالیسم", "neobrutal", "neobrutalism", "neubrutalism", "بروتال", "brutal")
        alias("brutalism", "بروتالیسم", "brutalism")
        alias("kinetic-typography", "کینتیک", "kinetic", "تایپوگرافی", "typography", "تایپو")
        alias("aurora-ui", "آئورورا", "اورورا", "aurora")
        alias("minimalism", "مینیمالیسم", "minimalism")
        alias("y2k", "y2k", "وایتوکی")
        alias("bento-grid", "بنتو", "bento")
        alias("chrome-liquid-metal", "کروم", "chrome", "متال", "metal")
        alias("grain-noise", "گرین", "grain", "نویز", "noise")
        alias("3d-immersive", "سهبعدی", "3d")
        alias("dark-mode-ui", "دارک", "dark", "darkmode")
    }
}
