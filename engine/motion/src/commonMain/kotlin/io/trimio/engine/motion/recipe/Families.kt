package io.trimio.engine.motion.recipe

/**
 * The premium families ([Family]): every one in several layouts and choreographies, set in the
 * look's own type, and the names a director (or a small model) may call them by.
 */
object Families {
    val all: List<Recipe> = FigureFamilies.all + ListFamilies.all + CompareFamilies.all + StatFamilies.all + TalkFamilies.all + ThingFamilies.all + TradeFamilies.all

    /** Loose names → family names. */
    val aliases: Map<String, String> = mapOf(
        "leaderboard" to "ranking", "rank" to "ranking", "top" to "ranking", "top-list" to "ranking", "podium" to "ranking", "top-3" to "ranking",
        "history" to "timeline", "roadmap" to "timeline", "milestones" to "timeline", "journey" to "timeline",
        "how-to" to "steps", "step" to "steps", "process" to "steps", "instructions" to "steps", "recipe-steps" to "steps",
        "vs" to "versus", "comparison" to "versus", "split" to "versus", "split-screen" to "versus", "face-off" to "versus",
        "pros" to "pros-cons", "cons" to "pros-cons", "pros-and-cons" to "pros-cons", "good-bad" to "pros-cons",
        "before" to "before-after", "after" to "before-after", "transformation" to "before-after", "wipe" to "before-after",
        "stat-stack" to "stat-stack", "kpi-stack" to "stat-stack", "figures-stack" to "stat-stack",
        "gauge" to "ring", "donut" to "ring", "progress-ring" to "ring", "percentage" to "ring", "dial" to "ring",
        "likes" to "social-proof", "followers" to "social-proof", "rating" to "social-proof", "reviews" to "social-proof", "stars" to "social-proof",
        "quote-card" to "quote", "testimonial" to "quote", "kinetic-quote" to "quote", "said" to "quote",
        "section" to "chapter", "part" to "chapter", "title-card" to "chapter", "chapter-card" to "chapter",
        "chat-bubble" to "bubble", "reply" to "bubble", "comment-card" to "bubble", "text-message" to "bubble",
        "question" to "poll", "vote" to "poll", "survey" to "poll", "ask-box" to "poll",
        "arrow" to "callout", "pointer" to "callout", "annotation" to "callout", "spotlight" to "callout",
        "name-tag" to "lower-third", "nametag" to "lower-third",
        "lock-screen" to "notification-stack", "push-stack" to "notification-stack", "notification-pile" to "notification-stack",
        "invoice" to "receipt", "bill" to "receipt", "payment" to "receipt", "paid" to "receipt", "ticket-stub" to "receipt",
        "mockup" to "phone", "app-mockup" to "phone", "phone-ui" to "phone", "mobile-app" to "phone",
        "confetti" to "burst", "celebrate" to "burst", "sticker-burst" to "burst", "reaction" to "burst", "wow" to "burst",
        "pin" to "map-pin", "location" to "map-pin", "place" to "map-pin", "where" to "map-pin", "city" to "map-pin",
        "candlestick" to "candles", "candle" to "candles", "ohlc" to "candles", "price-chart" to "candles", "kline" to "candles",
        "ticker-tape" to "tape", "marquee" to "tape", "crawl" to "tape", "market-tape" to "tape",
        "crypto-wallet" to "wallet", "address-card" to "wallet", "wallet-card" to "wallet", "deposit-address" to "wallet",
        "trade" to "order", "position" to "order", "order-card" to "order", "buy" to "order", "sell" to "order", "long" to "order", "short" to "order",
        "order-book" to "order", "entry" to "order", "signal" to "order",
        "sale" to "price-tag", "discount" to "price-tag", "tag" to "price-tag", "price-card" to "price-tag", "offer" to "price-tag",
    )
}
