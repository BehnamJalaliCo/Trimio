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
        "lock" to "M6 11h12v10H6V11zM8 11V8a4 4 0 0 1 8 0v3",
        "clock" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2",
        "heart" to "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z",
        "warning" to "M12 3l10 18H2L12 3zM12 10v5M12 18h.01",
        "rocket" to "M5 15c-1 1-2 4-2 6 2 0 5-1 6-2M9 15l-3-3c1-4 5-9 12-9 0 7-5 11-9 12zM14 10a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
        "eye" to "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        "spark" to "M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M18 6l-2.5 2.5M8.5 15.5L6 18",
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
    )

    const val VIEWPORT = 24f

    fun path(name: String?): String? {
        val key = name?.trim()?.lowercase() ?: return null
        return paths[key] ?: paths[aliases[key]]
    }

    val names: Set<String> get() = paths.keys
}
