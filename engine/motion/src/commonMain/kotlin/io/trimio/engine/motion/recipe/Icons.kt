package io.trimio.engine.motion.recipe

/**
 * Line icons for motion (24-unit grid, drawn as strokes so they can draw on). Names are loose on
 * purpose: small models say "up", "arrow-up" or "rise" and all of them land here.
 */
object Icons {
    private val paths = mapOf(
        "arrow-up" to "M12 20V4M5 11l7-7 7 7",
        "arrow-down" to "M12 4v16M5 13l7 7 7-7",
        "arrow-left" to "M20 12H4M11 5l-7 7 7 7",
        "arrow-right" to "M4 12h16M13 5l7 7-7 7",
        "trend-up" to "M3 17l6-6 4 4 8-8M15 7h6v6",
        "trend-down" to "M3 7l6 6 4-4 8 8M15 17h6v-6",
        "check" to "M4 12.5l5 5L20 6.5",
        "close" to "M6 6l12 12M18 6L6 18",
        "bolt" to "M13 2L4 14h7l-1 8 9-12h-7l1-8z",
        "fire" to "M12 22c4 0 7-3 7-7 0-5-4-7-5-11-2 3-2 5-1 7-2-1-3-3-3-5-3 3-5 6-5 9 0 4 3 7 7 7z",
        "star" to "M12 3l2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9L12 3z",
        "bell" to "M6 16V11a6 6 0 0 1 12 0v5l2 2H4l2-2zM10 20a2 2 0 0 0 4 0",
        "play" to "M7 4l13 8-13 8V4z",
        "target" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM12 12h.01",
        "chart" to "M4 20V4M4 20h16M8 16v-5M12 16V8M16 16v-3",
        "coin" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM9 7.5h4.5a2.25 2.25 0 0 1 0 4.5H9zM9 12h5a2.25 2.25 0 0 1 0 4.5H9zM9 7.5v9M11 6v1.5M11 16.5V18",
        "cash-coin" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM15 9.2c-.5-1-1.6-1.7-3-1.7-1.8 0-3 .9-3 2.2 0 3 6 1.6 6 4.6 0 1.3-1.3 2.2-3 2.2-1.5 0-2.6-.7-3.1-1.8M12 6v1.5M12 16.5V18",
        "lock" to "M6 11h12v10H6V11zM8 11V8a4 4 0 0 1 8 0v3",
        "clock" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2",
        "heart" to "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z",
        "warning" to "M12 3l10 18H2L12 3zM12 10v5M12 18h.01",
        "rocket" to "M5 15c-1 1-2 4-2 6 2 0 5-1 6-2M9 15l-3-3c1-4 5-9 12-9 0 7-5 11-9 12zM14 10a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
        "eye" to "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        "spark" to "M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M18 6l-2.5 2.5M8.5 15.5L6 18",
        // Things of products and screens (the B-roll scenes compose these).
        "calendar" to "M4 6h16v14H4zM4 10h16M8 3v4M16 3v4",
        "phone" to "M7 2h10a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zM11 18h2",
        "browser" to "M3 5h18v14H3zM3 9h18M6 7h.01M8.5 7h.01",
        "code" to "M8 8l-4 4 4 4M16 8l4 4-4 4M13.5 5l-3 14",
        "mail" to "M3 6h18v12H3zM3 7l9 6 9-6",
        "cart" to "M3 4h2l2.4 11h10.6l2-8H6.2M9 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM17 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
        "user" to "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0",
        "cloud" to "M7 18a5 5 0 0 1-.6-9.97A6 6 0 0 1 18 9a4.5 4.5 0 0 1-.5 9z",
        "search" to "M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13zM15.5 15.5L21 21",
        "sliders" to "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M14 4v4M8 10v4M16 16v4",
        "wifi" to "M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01",
        "card" to "M3 6h18v12H3zM3 10h18M7 15h4",
        "chat" to "M4 5h16v11H9l-5 4V5z",
        "pointer" to "M6 3l12 7-5.5 1.5L10 17 6 3z",
        "image" to "M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9h.01",
        "home" to "M4 11l8-7 8 7M6 9.5V20h12V9.5",
        "send" to "M21 3L10 14M21 3l-7 18-4-7-7-4 18-7z",
        "shield" to "M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6l8-3z",
        "gift" to "M4 10h16v10H4zM3 7h18v3H3zM12 7v13M12 7c-1-3-5-4-5-1.5S12 7 12 7zM12 7c1-3 5-4 5-1.5S12 7 12 7z",
        "download" to "M12 4v11M7 10l5 5 5-5M5 20h14",
        "dashboard" to "M4 4h7v7H4zM13 4h7v4h-7zM13 10h7v10h-7zM4 13h7v7H4z",
    )

    private val aliases = mapOf(
        "up" to "arrow-up", "rise" to "trend-up", "growth" to "trend-up", "pump" to "trend-up", "bull" to "trend-up",
        "down" to "arrow-down", "fall" to "trend-down", "drop" to "trend-down", "dump" to "trend-down", "bear" to "trend-down",
        "ok" to "check", "done" to "check", "yes" to "check", "no" to "close", "x" to "close",
        "fast" to "bolt", "energy" to "bolt", "hot" to "fire", "trending" to "fire", "favorite" to "star",
        "alert" to "warning", "danger" to "warning", "notify" to "bell", "signal" to "bell", "goal" to "target", "tp" to "target",
        "bitcoin" to "coin", "btc" to "coin", "crypto" to "coin", "money" to "coin", "price" to "coin",
        "secure" to "lock", "time" to "clock", "love" to "heart", "launch" to "rocket", "moon" to "rocket", "watch" to "eye", "new" to "spark",
        "next" to "arrow-left", "previous" to "arrow-right",
        "date" to "calendar", "schedule" to "calendar", "event" to "calendar", "deadline-date" to "calendar",
        "mobile" to "phone", "app" to "phone", "smartphone" to "phone", "website" to "browser", "web" to "browser", "site" to "browser", "page" to "browser",
        "coding" to "code", "developer" to "code", "email" to "mail", "inbox" to "mail", "shop" to "cart", "buy" to "cart", "store" to "cart",
        "profile" to "user", "account" to "user", "person" to "user", "sync" to "cloud", "upload" to "cloud", "find" to "search",
        "settings" to "sliders", "internet" to "wifi", "online" to "wifi", "payment" to "card", "pay" to "card", "credit-card" to "card",
        "message" to "chat", "comment" to "chat", "dm" to "chat", "click" to "pointer", "tap" to "pointer", "cursor" to "pointer",
        "photo" to "image", "picture" to "image", "security" to "shield", "safe" to "shield", "protect" to "shield",
        "reward" to "gift", "bonus" to "gift", "voucher" to "gift", "install" to "download", "analytics" to "dashboard", "stats" to "dashboard",
    )

    const val VIEWPORT = 24f

    fun path(name: String?): String? {
        val key = name?.trim()?.lowercase() ?: return null
        return paths[key] ?: paths[aliases[key]]
    }

    val names: Set<String> get() = paths.keys
}
