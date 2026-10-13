package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.style.StyleLanguage

/**
 * The premium families ([io.trimio.engine.motion.recipe.Families]) as the planner's shows. The
 * planner decides what a line shows (a figure, a list, a headline) and when; this picks, per seed,
 * the treatment the moment can carry instead of the plain one: a percent as a ring, a price as a
 * tag, social numbers as proof counters, steps as numbered steps, a ranking as a leaderboard, a
 * dated list as a timeline, a crypto chart as candles, a trade as an order card, coins as a ticker
 * tape, a place as a map pin, a question as a poll, a short emphatic line as a kinetic quote. It
 * never invents content: a family is chosen only when the line holds what it needs, and the beat
 * keeps the planner's timing, place and words.
 */
internal class Shows(
    private val rtl: Boolean,
    private val domain: String,
    private val mood: String,
    private val rng: Rng,
    private val entities: List<Entity>,
) {
    /** Families already used: a treatment is not repeated while others are left. */
    private val used = mutableListOf<String>()

    /**
     * The beat [b] (the planner's primary beat for line [line], read as [read]) in a richer family
     * when the line allows one and the seed takes it; else [b] itself.
     */
    fun vary(b: BeatScore, texts: List<String>, line: Lines.Line, read: LineRead, sense: Quantities.Sense, takeover: Boolean): BeatScore {
        if (!enabled) return b
        val norm = line.range.map { NumberWords.normalize(texts[it]).trimEnd('.', '،', ',', '!', '?', '؟') }
        val options = options(b, texts, line, norm, read, sense, takeover)
        val fresh = options.filter { (beat, _) -> beat.recipe !in used.takeLast(RECENT) }.ifEmpty { options }
        val pick = fresh.firstOrNull { (_, p) -> rng.chance(p) }?.first ?: return b
        used += pick.recipe
        return pick
    }

    /** What the line could also be, each with how often the seed takes it. */
    private fun options(b: BeatScore, texts: List<String>, line: Lines.Line, norm: List<String>, read: LineRead, sense: Quantities.Sense, takeover: Boolean): List<Pair<BeatScore, Float>> {
        val crypto = domain in TRADING
        val out = mutableListOf<Pair<BeatScore, Float>>()
        when (b.recipe) {
            "counter" -> figure(b, norm, sense, crypto, out)
            "list" -> list(b, norm, read, out)
            "chart" -> if (crypto) out += b.copy(recipe = "candles", label = b.label?.takeIf { it.isNotBlank() } ?: pair(texts, line)) to 0.6f
            "stamp" -> out += b.copy(recipe = "burst", icon = if (mood == "playful") "heart" else "fire") to 0.35f
            "logos" -> tickers(b.items)?.takeIf { crypto && it.size >= 2 }?.let { out += b.copy(recipe = "tape", items = it) to 0.4f }
            "headline", "mask-rise", "slam", "stack", "flip", "spread", "blur-in", "type-on", "glitch" -> headline(b, texts, line, norm, read, takeover, out)
        }
        return out
    }

    /** What a figure could be instead: an order, social proof, a ring, a price tag, a stack of stats. */
    private fun figure(b: BeatScore, norm: List<String>, sense: Quantities.Sense, crypto: Boolean, out: MutableList<Pair<BeatScore, Float>>) {
        fun has(words: Set<String>) = norm.any { it in words }
        val q = sense.quantities.firstOrNull { b.value != null && kotlin.math.abs(it.value - b.value!!) < 0.01 }
        if (crypto && has(SIDES) && sense.money != null) out += order(b, norm, sense) to 0.6f
        if (sense.social.isNotEmpty()) out += b.copy(recipe = "social-proof", points = sense.social.map { it.value.toFloat() }) to 0.5f
        if (q?.kind == Quantities.Kind.Percent && (b.value ?: 0f) in 1f..100f) out += b.copy(recipe = "ring", prefix = null, suffix = null) to 0.5f
        if (q?.kind == Quantities.Kind.Money && (sense.price || has(PRICE))) out += b.copy(recipe = "price-tag") to 0.5f
        val many = sense.quantities.filter { !it.year && it.value >= 1 }
        if (many.size in 2..4) out += b.copy(recipe = "stat-stack", points = many.map { it.value.toFloat() }, items = many.map { it.unit }, value = null, prefix = null, suffix = null) to 0.35f
    }

    /** What a list could be instead: steps, a ranking, a timeline, a versus. */
    private fun list(b: BeatScore, norm: List<String>, read: LineRead, out: MutableList<Pair<BeatScore, Float>>) {
        fun has(words: Set<String>) = norm.any { it in words }
        val items = b.text?.split(" | ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (items.size < 2) return
        if (read.role == "steps" || has(ORDINALS)) out += b.copy(recipe = "steps", items = items, text = null) to 0.6f
        if (has(RANKING)) out += b.copy(recipe = "ranking", items = items, text = null) to 0.6f
        if (items.count { it.any(Char::isDigit) } >= 2) out += b.copy(recipe = "timeline", items = items, text = null) to 0.6f
        if (read.role == "comparison" && items.size == 2) out += b.copy(recipe = "versus", items = items, text = null) to 0.6f
    }

    /** What a headline line could be instead: a trade, a wallet, a place, a step card, a poll, a kinetic quote. */
    private fun headline(b: BeatScore, texts: List<String>, line: Lines.Line, norm: List<String>, read: LineRead, takeover: Boolean, out: MutableList<Pair<BeatScore, Float>>) {
        fun has(words: Set<String>) = norm.any { it in words }
        val title = b.text ?: return
        val crypto = domain in TRADING
        if (crypto && has(WALLET)) out += b.copy(recipe = "wallet", label = title, text = null) to 0.5f
        // A place by the name as written for this piece (a Latin name on a Persian piece is not shown).
        entities.firstOrNull { it.at in line.range && it.kind == "place" && it.name.any { c -> c in '\u0600'..'\u06FF' } == rtl }?.let { place ->
            out += b.copy(recipe = "map-pin", text = place.name, label = null, emphasis = emptyList()) to 0.5f
        }
        Enumerations.step(texts, line)?.let { r ->
            val n = NumberWords.findAll(texts.subList(r.first, r.last + 1)).firstOrNull()?.value?.toFloat() ?: ordinal(norm)
            if (n != null) out += b.copy(recipe = "chapter", value = n, emphasis = emptyList()) to 0.5f
        }
        if (read.role == "question" && title.split(' ').size >= 2) out += b.copy(recipe = "poll", items = emptyList()) to 0.35f
        val words = line.range.count()
        val loud = (b.energy ?: 0.5f) >= LOUD
        val quotable = read.role in QUOTABLE && words in QUOTE_WORDS
        if (!takeover && quotable && loud) {
            out += b.copy(recipe = "quote", text = line.range.joinToString(" ") { texts[it] }, at = line.first, until = line.last, label = null) to 0.2f
        }
        if (mood in PLAYFUL && loud && title.split(' ').size <= 2) out += b.copy(recipe = "burst", icon = "star") to 0.25f
    }

    /**
     * The beat for a family the understanding named for the line ([read].show), from what the line
     * holds (its items, its title as said, its numbers), or null when it lacks what the family needs.
     */
    fun direct(show: String, texts: List<String>, line: Lines.Line, read: LineRead, sense: Quantities.Sense, e: Float, place: String): BeatScore? {
        val title = read.title.takeIf { it.isNotBlank() }
        val items = read.items.filter { it.isNotBlank() }
        val q = sense.quantities.filter { !it.year }
        val base = BeatScore(recipe = show, at = line.first, until = line.last, energy = e, place = place)
        val made = when (show) {
            "ranking", "timeline", "steps", "pros-cons", "notification-stack" -> base.copy(items = items, label = title).takeIf { items.size >= 2 }
            "versus", "before-after" -> base.copy(items = items.take(2), points = q.take(2).map { it.value.toFloat() }.takeIf { it.size == 2 }).takeIf { items.size >= 2 }
            "tape" -> tickers(items)?.let { base.copy(items = it) }
            "quote" -> base.copy(text = line.range.joinToString(" ") { texts[it] }).takeIf { line.range.count() in QUOTE_WORDS }
            "chapter", "poll", "burst", "callout", "bubble" -> title?.let { base.copy(text = it, value = ordinal(line.range.map { i -> NumberWords.normalize(texts[i]) })) }
            "wallet" -> base.copy(label = title)
            "map-pin" -> title?.let { base.copy(text = it) }
            "ring" -> q.firstOrNull { it.kind == Quantities.Kind.Percent }?.let { base.copy(at = it.at, value = it.value.toFloat(), label = title) }
            "price-tag", "receipt" -> sense.money?.let { base.copy(at = it.at, value = it.value.toFloat(), suffix = it.unit.takeIf { u -> u.isNotBlank() }?.let { u -> " $u" }, label = title) }
            "stat-stack", "social-proof" -> base.copy(points = q.map { it.value.toFloat() }, items = q.map { it.unit }).takeIf { q.isNotEmpty() }
            "candles" -> base.copy(label = pair(texts, line), points = q.map { it.value.toFloat() }.takeIf { it.size >= 4 })
            "order" -> sense.money?.let { order(base, line.range.map { i -> NumberWords.normalize(texts[i]) }, sense) }
            "phone" -> base.copy(label = title, items = items.take(3))
            else -> null
        }
        made?.let { used += it.recipe }
        return made
    }

    /** A trade said with its side and price: an order card. */
    private fun order(b: BeatScore, norm: List<String>, sense: Quantities.Sense): BeatScore {
        val side = norm.firstOrNull { it in SIDES } ?: "buy"
        val money = sense.money!!
        return b.copy(recipe = "order", items = listOf(side), value = money.value.toFloat(), label = null, prefix = null, suffix = null)
    }

    /** "BTC/USDT" when the line names a coin, else the generic pair. */
    private fun pair(texts: List<String>, line: Lines.Line): String {
        val coin = line.range.firstNotNullOfOrNull { TICKERS[NumberWords.normalize(texts[it]).lowercase()] }
        return "${coin ?: "BTC"}/USDT"
    }

    private fun tickers(names: List<String>): List<String>? = names.map { TICKERS[it.lowercase()] ?: return null }

    private fun ordinal(norm: List<String>): Float? = norm.firstNotNullOfOrNull { ORDINAL_VALUES[it] }

    /** How the one strong word of each caption line is marked: the style's marks, the hand-drawn ones as rarer options. */
    fun captionMark(style: StyleLanguage): String? {
        if (!enabled) return null
        val marks = style.captions.marks.toList() + HAND.map { it to HAND_WEIGHT }
        return rng.weighted(marks.map { (m, w) -> m to w })
    }

    companion object {
        /** The families the understanding may name for a line (offered to the model with the planner's own shows). */
        val FAMILIES = listOf(
            "quote", "versus", "ranking", "timeline", "steps", "pros-cons", "before-after", "poll", "price-tag", "ring", "stat-stack",
            "social-proof", "candles", "order", "wallet", "tape", "map-pin", "chapter", "bubble", "burst", "receipt", "notification-stack", "phone", "callout",
        )

        /** The families offered to the understanding model by name (the ones a line's words clearly call for). */
        val OFFERED = listOf("quote", "versus", "ranking", "timeline", "steps", "pros-cons", "before-after", "poll", "price-tag", "ring", "candles", "order", "map-pin", "wallet")

        /** Families that show data: held long enough to be read, like the planner's own figures. */
        val DATA = setOf("ranking", "timeline", "steps", "versus", "pros-cons", "before-after", "stat-stack", "ring", "social-proof", "price-tag", "candles", "order", "tape", "poll", "receipt")

        /** Off for an A/B of the jury with every device in its signature form (tests only). */
        var enabled = true

        /** The edit's variety seed (never 0, which keeps every device in its signature form). */
        fun variety(seed: Long): Int = if (!enabled) 0 else (seed xor (seed ushr 32)).toInt().let { it * -0x61c88647 + 0x2545F491 }.let { if (it == 0) 1 else it }

        private const val RECENT = 2
        private const val LOUD = 0.6f
        private const val HAND_WEIGHT = 0.25f
        private val QUOTE_WORDS = 4..8
        private val HAND = listOf("scribble", "half", "double")
        private val TRADING = setOf("crypto", "finance")
        private val PLAYFUL = setOf("playful", "energetic")
        private val QUOTABLE = setOf("punchline", "insight", "hook", "claim", "warning", "benefit")
        private val SIDES = setOf("خرید", "فروش", "لانگ", "شورت", "buy", "sell", "long", "short")
        private val PRICE = setOf("قیمت", "قیمتش", "price", "costs", "cost", "تومن", "تومان", "دلار")
        private val WALLET = setOf("کیف", "ولت", "wallet", "آدرس", "address", "واریز", "deposit")
        private val RANKING = setOf("بهترین", "برترین", "رتبه", "رتبه‌بندی", "best", "top", "ranking", "رده")
        private val ORDINALS = setOf(
            "اول", "دوم", "سوم", "چهارم", "پنجم", "اولین", "دومین", "سومین", "first", "second", "third", "fourth", "step", "مرحله", "قدم",
        )
        private val ORDINAL_VALUES = mapOf(
            "اول" to 1f, "اولین" to 1f, "یک" to 1f, "دوم" to 2f, "دومین" to 2f, "سوم" to 3f, "سومین" to 3f, "چهارم" to 4f, "پنجم" to 5f,
            "first" to 1f, "second" to 2f, "third" to 3f, "fourth" to 4f, "fifth" to 5f, "one" to 1f, "two" to 2f, "three" to 3f,
        )
        private val TICKERS = mapOf(
            "bitcoin" to "BTC", "btc" to "BTC", "بیت‌کوین" to "BTC", "بیتکوین" to "BTC", "ethereum" to "ETH", "eth" to "ETH", "اتریوم" to "ETH",
            "tether" to "USDT", "usdt" to "USDT", "تتر" to "USDT", "solana" to "SOL", "sol" to "SOL", "سولانا" to "SOL", "ripple" to "XRP", "xrp" to "XRP", "ریپل" to "XRP",
            "bnb" to "BNB", "binance coin" to "BNB", "dogecoin" to "DOGE", "doge" to "DOGE", "دوج" to "DOGE", "cardano" to "ADA", "ada" to "ADA", "کاردانو" to "ADA",
            "ton" to "TON", "toncoin" to "TON", "تون" to "TON", "notcoin" to "NOT",
        )
    }
}
