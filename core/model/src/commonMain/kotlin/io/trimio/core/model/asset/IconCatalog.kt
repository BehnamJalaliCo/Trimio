package io.trimio.core.model.asset

/**
 * Vector icons the renderer can draw (`icon/<id>`), with the words that call for them in Persian
 * and English. The renderer draws every id listed here; the Director and the asset matcher choose
 * among them. All icons are original procedural drawings (no third-party artwork, no licence terms).
 */
object IconCatalog {

    data class Icon(val id: String, val nameFa: String, val nameEn: String, val keywords: List<String>)

    val all: List<Icon> = listOf(
        Icon("coin", "سکه\u0654 بیت\u200Cکوین", "Bitcoin coin", listOf("بیت\u200Cکوین", "بیتکوین", "کوین", "سکه", "رمزارز", "کریپتو", "bitcoin", "btc", "coin", "crypto")),
        Icon("eth", "اتریوم", "Ethereum", listOf("اتریوم", "اتر", "ethereum", "eth", "ether")),
        Icon("dollar", "دلار", "Dollar", listOf("دلار", "پول", "درآمد", "سود", "قیمت", "dollar", "money", "cash", "income", "price", "usd")),
        Icon("gold", "شمش طلا", "Gold bar", listOf("طلا", "انس", "شمش", "سکه\u200Cطلا", "gold", "xau", "bullion")),
        Icon("check", "تیک", "Check", listOf("درست", "تایید", "موفق", "رسید", "انجام", "check", "done", "success", "correct", "yes")),
        Icon("cross", "ضربدر", "Cross", listOf("غلط", "اشتباه", "نه", "ممنوع", "wrong", "no", "mistake", "never", "avoid")),
        Icon("star", "ستاره", "Star", listOf("بهترین", "عالی", "ستاره", "ویژه", "best", "star", "top", "amazing", "special")),
        Icon("bolt", "صاعقه", "Lightning", listOf("سریع", "انرژی", "برق", "فوری", "fast", "energy", "power", "instant", "quick")),
        Icon("heart", "قلب", "Heart", listOf("عشق", "قلب", "دوست", "لایک", "love", "heart", "like", "favorite")),
        Icon("rocket", "موشک", "Rocket", listOf("موشک", "پرواز", "رشد", "ماه", "انفجار", "rocket", "moon", "launch", "skyrocket", "growth")),
        Icon("fire", "آتش", "Fire", listOf("آتش", "داغ", "ترند", "وایرال", "fire", "hot", "trending", "viral", "lit")),
        Icon("trophy", "جام", "Trophy", listOf("جام", "برنده", "قهرمان", "پیروزی", "trophy", "win", "winner", "champion", "victory")),
        Icon("target", "هدف", "Target", listOf("هدف", "تارگت", "دقیق", "target", "goal", "aim", "precise")),
        Icon("bell", "زنگ", "Bell", listOf("زنگ", "اعلان", "نوتیفیکیشن", "یادآوری", "bell", "notification", "alert", "reminder")),
        Icon("lock", "قفل", "Lock", listOf("امن", "امنیت", "قفل", "رمز", "secure", "security", "lock", "safe", "private")),
        Icon("clock", "ساعت", "Clock", listOf("زمان", "ساعت", "وقت", "دقیقه", "امروز", "time", "clock", "minute", "hour", "today")),
        Icon("bulb", "لامپ", "Idea", listOf("ایده", "نکته", "ترفند", "خلاقیت", "idea", "tip", "trick", "insight", "creative")),
        Icon("crown", "تاج", "Crown", listOf("پادشاه", "تاج", "برتر", "vip", "king", "queen", "crown", "premium")),
        Icon("gift", "هدیه", "Gift", listOf("هدیه", "جایزه", "رایگان", "تخفیف", "gift", "free", "bonus", "prize", "discount")),
        Icon("warning", "هشدار", "Warning", listOf("هشدار", "ریسک", "خطر", "مراقب", "warning", "risk", "danger", "careful", "caution")),
    )

    val ids: List<String> = all.map { it.id }

    fun byId(id: String): Icon? = all.firstOrNull { it.id == id }
}
