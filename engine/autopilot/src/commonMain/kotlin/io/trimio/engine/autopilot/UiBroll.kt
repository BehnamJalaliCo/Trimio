package io.trimio.engine.autopilot

/**
 * Interface B-roll for tech and product talk (the `ui-*` recipes): a line that names an app, a
 * site, code, a dashboard or notifications can cut to a simulated, brand-free interface instead
 * of the speaker again. This is only the mapping from what is said to which interface shows it;
 * the user's own screenshots are the `asset-*` recipes' business.
 */
object UiBroll {

    /** The `ui-*` recipe a line's words call for, or null when it names no interface. */
    fun recipeFor(words: List<String>): String? {
        val keys = words.map { Phrases.key(it).replace("‌", "").replace(" ", "").lowercase() }
        for (k in keys) {
            CONCEPTS[k]?.let { return it }
            CONCEPTS.entries.firstOrNull { (c, _) -> c.length >= MIN_STEM && k.startsWith(c) && k.removePrefix(c) in SUFFIXES }?.let { return it.value }
        }
        return null
    }

    /** Readings a matching line may trade for its interface (it had no graphic of its own to lose). */
    val TRADES = setOf("headline", "none", "object", "terminal")

    private val SUFFIXES = setOf("", "ها", "های", "ش", "مون", "تون", "رو", "و", "ی", "s")
    private const val MIN_STEM = 3

    private val CONCEPTS: Map<String, String> = HashMap<String, String>().apply {
        fun add(recipe: String, vararg keys: String) = keys.forEach { put(it.replace("‌", ""), recipe) }
        add("ui-browser", "سایت", "وبسایت", "وب‌سایت", "مرورگر", "لندینگ", "website", "site", "browser", "landing", "webpage")
        add("ui-feed", "اپ", "اپلیکیشن", "اپلیکیشنی", "فید", "app", "apps", "application", "feed")
        add("ui-code", "کد", "کدنویسی", "برنامه‌نویسی", "برنامه‌نویس", "code", "coding", "programming", "developer", "api")
        add("ui-dashboard", "داشبورد", "آنالیتیکس", "پنل", "dashboard", "analytics", "panel", "metrics")
        add("ui-chat", "نوتیفیکیشن", "نوتیف", "اعلان", "notification", "notifications", "notif")
    }
}
