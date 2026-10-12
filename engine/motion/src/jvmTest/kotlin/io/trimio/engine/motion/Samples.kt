package io.trimio.engine.motion

import io.trimio.engine.motion.recipe.Cue

/** What each family is shown with on the bench, in Persian and English (crypto and trading first). */
object Samples {
    fun fill(family: String, c: Cue, rtl: Boolean): Cue = if (rtl) fa(family, c) else en(family, c)

    private fun fa(family: String, c: Cue): Cue = when (family) {
        "ranking" -> c.copy(label = "بهترین صرافی‌ها", items = listOf("بایننس", "کوکوین", "بای‌بیت", "اوکی‌ایکس"), points = listOf(98f, 91f, 87f, 80f))
        "timeline" -> c.copy(items = listOf("۲۰۰۹: تولد بیت‌کوین", "۲۰۱۷: اولین رکورد", "۲۰۲۱: ورود نهادها", "۲۰۲۴: ای‌تی‌اف"))
        "steps" -> c.copy(items = listOf("حساب باز کن", "احراز هویت", "واریز تتر", "اولین خرید"))
        "versus" -> c.copy(items = listOf("اسپات", "فیوچرز"), points = listOf(62f, 38f), suffix = "٪")
        "pros-cons" -> c.copy(items = listOf("سود بالا", "بازار ۲۴ ساعته", "-ریسک لیکویید شدن", "-استرس زیاد"))
        "before-after" -> c.copy(items = listOf("معامله با احساس", "معامله با استراتژی"), from = 12f, value = 64f, suffix = "٪")
        "stat-stack" -> c.copy(items = listOf("وین ریت", "سود ماهانه", "معامله"), points = listOf(68f, 23f, 140f), suffix = "")
        "ring" -> c.copy(value = 72f, label = "وین ریت این ماه")
        "social-proof" -> c.copy(points = listOf(12500f, 840f, 2300f), value = 4.8f, label = "۳۲۰۰ نظر")
        "price-tag" -> c.copy(value = 49f, from = 99f, prefix = "$", label = "دوره ترید")
        "quote" -> c.copy(text = "بازار به صبور‌ها پول می‌ده، نه به عجول‌ها", label = "وارن بافت", emphasis = setOf(3))
        "chapter" -> c.copy(text = "مدیریت ریسک", value = 2f)
        "bubble" -> c.copy(text = "سلام، این استراتژی روی فارکس هم جواب میده؟", label = "سارا", value = 214f)
        "poll" -> c.copy(text = "الان وقت خرید بیت‌کوینه؟", items = listOf("آره، می‌خرم", "نه، صبر می‌کنم"), points = listOf(68f, 32f))
        "callout" -> c.copy(text = "۱۰ سال تجربه ترید")
        "lower-third" -> c.copy(text = "علی رضایی", label = "تریدر ارز دیجیتال")
        "notification-stack" -> c.copy(label = "صرافی", items = listOf("سفارش خرید BTC انجام شد", "قیمت ETH به هدف رسید", "واریز ۵۰۰ تتر تأیید شد"))
        "receipt" -> c.copy(label = "رسید معامله", items = listOf("خرید بیت‌کوین: 0.25", "کارمزد: 1.6", "شبکه: TRC20"), value = 1250f, prefix = "$")
        "phone" -> c.copy(
            label = "دارایی من", value = 12480f, items = listOf("بیت‌کوین", "اتریوم", "تتر"), points = listOf(2.4f, -1.2f, 0.1f), text = "سفارش شما انجام شد",
        )
        "burst" -> c.copy(text = "سود!", icon = "fire")
        "map-pin" -> c.copy(text = "دبی", label = "امارات")
        "candles" -> c.copy(label = "BTC/USDT", value = 64250f)
        "tape" -> c.copy(items = listOf("BTC", "ETH", "SOL", "XRP", "BNB"), points = listOf(2.4f, -1.1f, 5.6f, -0.4f, 1.2f))
        "wallet" -> c.copy(label = "تتر · شبکه TRC20", text = "TQ7f3aB9xLmN2pK8vR4sD1c21e", value = 2500f, prefix = "$")
        "order" -> c.copy(label = "BTC/USDT", items = listOf("لانگ"), value = 64250f, points = listOf(62300f, 68100f, 0.25f))
        else -> c.copy(text = "بیت‌کوین", label = "نمونه", items = listOf("یک", "دو", "سه"), value = 42f, points = listOf(3f, 5f, 4f, 7f))
    }

    private fun en(family: String, c: Cue): Cue = when (family) {
        "ranking" -> c.copy(label = "Top exchanges", items = listOf("Binance", "KuCoin", "Bybit", "OKX"), points = listOf(98f, 91f, 87f, 80f))
        "timeline" -> c.copy(items = listOf("2009: Bitcoin is born", "2017: First record", "2021: Institutions in", "2024: Spot ETF"))
        "steps" -> c.copy(items = listOf("Open an account", "Verify your ID", "Deposit USDT", "First trade"))
        "versus" -> c.copy(items = listOf("Spot", "Futures"), points = listOf(62f, 38f), suffix = "%")
        "pros-cons" -> c.copy(items = listOf("High returns", "Open 24/7", "-Liquidation risk", "-Stressful"))
        "before-after" -> c.copy(items = listOf("Trading on emotion", "Trading a plan"), from = 12f, value = 64f, suffix = "%")
        "stat-stack" -> c.copy(items = listOf("Win rate", "Monthly return", "Trades"), points = listOf(68f, 23f, 140f))
        "ring" -> c.copy(value = 72f, label = "Win rate this month")
        "social-proof" -> c.copy(points = listOf(12500f, 840f, 2300f), value = 4.8f, label = "3,200 reviews")
        "price-tag" -> c.copy(value = 49f, from = 99f, prefix = "$", label = "Trading course")
        "quote" -> c.copy(text = "The market pays the patient, not the hasty", label = "Warren Buffett", emphasis = setOf(3))
        "chapter" -> c.copy(text = "Risk management", value = 2f)
        "bubble" -> c.copy(text = "Hey, does this strategy work on forex too?", label = "Sarah", value = 214f)
        "poll" -> c.copy(text = "Is it time to buy Bitcoin?", items = listOf("Yes, buying", "No, waiting"), points = listOf(68f, 32f))
        "callout" -> c.copy(text = "10 years trading")
        "lower-third" -> c.copy(text = "Alex Morgan", label = "Crypto trader")
        "notification-stack" -> c.copy(label = "Exchange", items = listOf("BTC buy order filled", "ETH hit your target", "500 USDT deposit confirmed"))
        "receipt" -> c.copy(label = "Trade receipt", items = listOf("Bitcoin: 0.25", "Fee: 1.6", "Network: TRC20"), value = 1250f, prefix = "$")
        "phone" -> c.copy(label = "My portfolio", value = 12480f, items = listOf("Bitcoin", "Ethereum", "Tether"), points = listOf(2.4f, -1.2f, 0.1f), text = "Your order was filled")
        "burst" -> c.copy(text = "PROFIT!", icon = "fire")
        "map-pin" -> c.copy(text = "Dubai", label = "UAE")
        "candles" -> c.copy(label = "BTC/USDT", value = 64250f)
        "tape" -> c.copy(items = listOf("BTC", "ETH", "SOL", "XRP", "BNB"), points = listOf(2.4f, -1.1f, 5.6f, -0.4f, 1.2f))
        "wallet" -> c.copy(label = "USDT · TRC20", text = "TQ7f3aB9xLmN2pK8vR4sD1c21e", value = 2500f, prefix = "$")
        "order" -> c.copy(label = "BTC/USDT", items = listOf("long"), value = 64250f, points = listOf(62300f, 68100f, 0.25f))
        else -> c.copy(text = "Bitcoin", label = "Sample", items = listOf("One", "Two", "Three"), value = 42f, points = listOf(3f, 5f, 4f, 7f))
    }
}
