package io.trimio.engine.motion

import io.trimio.engine.motion.recipe.Cue

/** What each family is shown with on the bench, in Persian and English (crypto and trading first). */
object Samples {
    fun fill(family: String, c: Cue, rtl: Boolean): Cue = if (rtl) FA[family]?.invoke(c) ?: faDefault(c) else EN[family]?.invoke(c) ?: enDefault(c)

    private val FA: Map<String, (Cue) -> Cue> = mapOf(
        "ranking" to { c -> c.copy(label = "محبوب‌ترین کوین‌ها", items = listOf("بیت‌کوین", "اتریوم", "سولانا", "تتر"), points = listOf(98f, 91f, 87f, 80f)) },
        "timeline" to { c -> c.copy(items = listOf("۲۰۰۹: تولد بیت‌کوین", "۲۰۱۷: اولین رکورد", "۲۰۲۱: ورود نهادها", "۲۰۲۴: ای‌تی‌اف")) },
        "steps" to { c -> c.copy(items = listOf("حساب باز کن", "احراز هویت", "واریز تتر", "اولین خرید")) },
        "versus" to { c -> c.copy(items = listOf("اسپات", "فیوچرز"), points = listOf(62f, 38f), suffix = "٪") },
        "pros-cons" to { c -> c.copy(items = listOf("سود بالا", "بازار ۲۴ ساعته", "-ریسک لیکویید شدن", "-استرس زیاد")) },
        "before-after" to { c -> c.copy(items = listOf("معامله با احساس", "معامله با استراتژی"), from = 12f, value = 64f, suffix = "٪") },
        "stat-stack" to { c -> c.copy(items = listOf("وین ریت", "سود ماهانه", "معامله"), points = listOf(68f, 23f, 140f), suffix = "") },
        "ring" to { c -> c.copy(value = 72f, label = "وین ریت این ماه") },
        "social-proof" to { c -> c.copy(points = listOf(12500f, 840f, 2300f), value = 4.8f, label = "۳۲۰۰ نظر") },
        "price-tag" to { c -> c.copy(value = 49f, from = 99f, prefix = "$", label = "دوره ترید") },
        "quote" to { c -> c.copy(text = "بازار به صبور‌ها پول می‌ده، نه به عجول‌ها", label = "یک تریدر قدیمی", emphasis = setOf(3)) },
        "chapter" to { c -> c.copy(text = "مدیریت ریسک", value = 2f) },
        "bubble" to { c -> c.copy(text = "سلام، این استراتژی روی فارکس هم جواب میده؟", label = "سارا", value = 214f) },
        "poll" to { c -> c.copy(text = "الان وقت خرید بیت‌کوینه؟", items = listOf("آره، می‌خرم", "نه، صبر می‌کنم"), points = listOf(68f, 32f)) },
        "callout" to { c -> c.copy(text = "۱۰ سال تجربه ترید") },
        "lower-third" to { c -> c.copy(text = "علی رضایی", label = "تریدر ارز دیجیتال") },
        "notification-stack" to { c -> c.copy(label = "صرافی", items = listOf("سفارش خرید BTC انجام شد", "قیمت ETH به هدف رسید", "واریز ۵۰۰ تتر تأیید شد")) },
        "receipt" to { c -> c.copy(label = "رسید معامله", items = listOf("خرید بیت‌کوین: 0.25", "کارمزد: 1.6", "شبکه: TRC20"), value = 1250f, prefix = "$") },
        "phone" to { c -> c.copy(
            label = "دارایی من", value = 12480f, items = listOf("بیت‌کوین", "اتریوم", "تتر"), points = listOf(2.4f, -1.2f, 0.1f), text = "سفارش شما انجام شد",
        ) },
        "burst" to { c -> c.copy(text = "سود!", icon = "fire") },
        "map-pin" to { c -> c.copy(text = "دبی", label = "امارات") },
        "candles" to { c -> c.copy(label = "BTC/USDT", value = 64250f) },
        "tape" to { c -> c.copy(items = listOf("BTC", "ETH", "SOL", "XRP", "BNB"), points = listOf(2.4f, -1.1f, 5.6f, -0.4f, 1.2f)) },
        "wallet" to { c -> c.copy(label = "تتر · شبکه TRC20", text = "TQ7f3aB9xLmN2pK8vR4sD1c21e", value = 2500f, prefix = "$") },
        "order" to { c -> c.copy(label = "BTC/USDT", items = listOf("لانگ"), value = 64250f, points = listOf(62300f, 68100f, 0.25f)) },
    )

    private fun faDefault(c: Cue): Cue = c.copy(text = "بیت‌کوین", label = "نمونه", items = listOf("یک", "دو", "سه"), value = 42f, points = listOf(3f, 5f, 4f, 7f))

    private val EN: Map<String, (Cue) -> Cue> = mapOf(
        "ranking" to { c -> c.copy(label = "Most loved coins", items = listOf("Bitcoin", "Ethereum", "Solana", "Tether"), points = listOf(98f, 91f, 87f, 80f)) },
        "timeline" to { c -> c.copy(items = listOf("2009: Bitcoin is born", "2017: First record", "2021: Institutions in", "2024: Spot ETF")) },
        "steps" to { c -> c.copy(items = listOf("Open an account", "Verify your ID", "Deposit USDT", "First trade")) },
        "versus" to { c -> c.copy(items = listOf("Spot", "Futures"), points = listOf(62f, 38f), suffix = "%") },
        "pros-cons" to { c -> c.copy(items = listOf("High returns", "Open 24/7", "-Liquidation risk", "-Stressful")) },
        "before-after" to { c -> c.copy(items = listOf("Trading on emotion", "Trading a plan"), from = 12f, value = 64f, suffix = "%") },
        "stat-stack" to { c -> c.copy(items = listOf("Win rate", "Monthly return", "Trades"), points = listOf(68f, 23f, 140f)) },
        "ring" to { c -> c.copy(value = 72f, label = "Win rate this month") },
        "social-proof" to { c -> c.copy(points = listOf(12500f, 840f, 2300f), value = 4.8f, label = "3,200 reviews") },
        "price-tag" to { c -> c.copy(value = 49f, from = 99f, prefix = "$", label = "Trading course") },
        "quote" to { c -> c.copy(text = "The market pays the patient, not the hasty", label = "An old trader", emphasis = setOf(4)) },
        "chapter" to { c -> c.copy(text = "Risk management", value = 2f) },
        "bubble" to { c -> c.copy(text = "Hey, does this strategy work on forex too?", label = "Sarah", value = 214f) },
        "poll" to { c -> c.copy(text = "Is it time to buy Bitcoin?", items = listOf("Yes, buying", "No, waiting"), points = listOf(68f, 32f)) },
        "callout" to { c -> c.copy(text = "10 years trading") },
        "lower-third" to { c -> c.copy(text = "Sam Rivera", label = "Crypto trader") },
        "notification-stack" to { c -> c.copy(label = "Exchange", items = listOf("BTC buy order filled", "ETH hit your target", "500 USDT deposit confirmed")) },
        "receipt" to { c -> c.copy(label = "Trade receipt", items = listOf("Bitcoin: 0.25", "Fee: 1.6", "Network: TRC20"), value = 1250f, prefix = "$") },
        "phone" to { c -> c.copy(label = "My portfolio", value = 12480f, items = listOf("Bitcoin", "Ethereum", "Tether"), points = listOf(2.4f, -1.2f, 0.1f), text = "Your order was filled") },
        "burst" to { c -> c.copy(text = "PROFIT!", icon = "fire") },
        "map-pin" to { c -> c.copy(text = "Dubai", label = "UAE") },
        "candles" to { c -> c.copy(label = "BTC/USDT", value = 64250f) },
        "tape" to { c -> c.copy(items = listOf("BTC", "ETH", "SOL", "XRP", "BNB"), points = listOf(2.4f, -1.1f, 5.6f, -0.4f, 1.2f)) },
        "wallet" to { c -> c.copy(label = "USDT · TRC20", text = "TQ7f3aB9xLmN2pK8vR4sD1c21e", value = 2500f, prefix = "$") },
        "order" to { c -> c.copy(label = "BTC/USDT", items = listOf("long"), value = 64250f, points = listOf(62300f, 68100f, 0.25f)) },
    )

    private fun enDefault(c: Cue): Cue = c.copy(text = "Bitcoin", label = "Sample", items = listOf("One", "Two", "Three"), value = 42f, points = listOf(3f, 5f, 4f, 7f))
}
