package io.trimio.engine.autopilot

import io.trimio.core.model.text.Numerals
import io.trimio.engine.motion.score.NumberWords

/**
 * Numbers with their meaning, read from speech the way an editor hears them: «سیصد و پنجاه تتر»
 * is money (a Tether amount), «چهل و هشت ساعت مونده» a deadline, «هزار نفر اول … پر شد» a quota
 * that filled, «پنجاه هزار بازدید و پنج هزار کامنت» two social stats. [NumberWords] finds the
 * number; this reads the unit after it and the cues around it, so the planner can pick the
 * graphic that says it best (a voucher, a countdown, a progress bar, a stats board) instead of a
 * bare counter. Word positions are indices into the list given.
 */
object Quantities {

    enum class Kind { Money, Duration, People, Capacity, Views, Comments, Likes, Followers, Percent, Plain }

    /** What a unit word means; [display] is how the screen spells it, [brand] the currency's mark name. */
    data class Unit(val kind: Kind, val display: String, val brand: String? = null)

    data class Quantity(
        /** First word of the number. */
        val at: Int,
        /** Words the number itself spans. */
        val count: Int,
        val value: Double,
        val decimals: Int,
        val kind: Kind,
        /** The unit word's index, or -1. */
        val unitAt: Int = -1,
        /** The unit for the screen («تتر», «ساعت», «بازدید»), or "". */
        val unit: String = "",
        /** Canonical currency brand ("Tether"), when the unit is a coin. */
        val brand: String? = null,
        /** A deadline: time left («مونده», «تا پایان»), not time spent. */
        val remaining: Boolean = false,
        /** "The first N" («هزار نفر اول»). */
        val first: Boolean = false,
        /** New room opened («دو هزار تا دیگه ظرفیت باز شده»). */
        val opened: Boolean = false,
    ) {
        val last: Int get() = maxOf(at + count - 1, unitAt)
        val social: Boolean get() = kind in SOCIAL

        fun shifted(by: Int) = copy(at = at + by, unitAt = if (unitAt >= 0) unitAt + by else -1)
    }

    /** One line's numbers and the cues around them. */
    data class Sense(
        val quantities: List<Quantity>,
        /** Where the line says something filled up («پر شد», «تکمیل», "sold out"), as word indices. */
        val filled: IntRange? = null,
        /** The line talks about receiving or winning it (a voucher, a gift, starting capital). */
        val gift: Boolean = false,
        /** The line quotes a price (a ticker, not a voucher). */
        val price: Boolean = false,
    ) {
        val money: Quantity? get() = quantities.firstOrNull { it.kind == Kind.Money && it.value > 0 }
        val social: List<Quantity> get() = quantities.filter { it.social }
        val deadline: Quantity? get() = quantities.firstOrNull { it.kind == Kind.Duration && it.remaining }

        /** A quota: people or seats that filled, or a percent of capacity. */
        val quota: Quantity? get() = quantities.firstOrNull { q ->
            (filled != null && q.kind in setOf(Kind.People, Kind.Capacity)) || (q.kind == Kind.Percent && quantities.any { it.kind == Kind.Capacity })
        } ?: quantities.firstOrNull { it.kind == Kind.Percent && filled != null }

        /** Money given or won (a voucher), not a price. */
        val voucher: Quantity? get() = money?.takeIf { gift || (it.brand != null && !price) }

        /**
         * The richest show the numbers clearly call for, or null: an amount given → voucher, two
         * social numbers → stats, time left → countdown, a quota that filled → progress.
         */
        fun richShow(): String? = when {
            voucher != null -> "voucher"
            social.size >= 2 -> "stats"
            deadline != null -> "countdown"
            quota != null -> "progress"
            else -> null
        }

        /** Whether the line holds what [show] needs (a model's pick is checked against this). */
        fun supports(show: String): Boolean = when (show) {
            "voucher" -> money != null
            "stats" -> quantities.count { it.value >= 1 } >= 2
            "countdown" -> quantities.any { it.kind == Kind.Duration }
            "progress" -> quota != null || (filled != null && quantities.isNotEmpty())
            else -> true
        }

        /** How strongly the line pays the piece off (the hook): a gift beats stats beats a deadline beats a percent. */
        fun weight(): Double {
            val base = when {
                voucher != null -> GIFT_WEIGHT
                social.size >= 2 -> STATS_WEIGHT
                deadline != null -> DEADLINE_WEIGHT
                else -> 0.0
            }
            val plain = quantities.maxOfOrNull { (if (it.kind == Kind.Percent) 100.0 else 0.0) + it.value.coerceAtMost(99.0) } ?: -1.0
            return maxOf(base, plain)
        }
    }

    /** Every number in [words] with its unit and role. */
    fun read(words: List<String>): List<Quantity> {
        val norm = words.map(NumberWords::normalize)
        return NumberWords.findAll(words).map { f ->
            val end = f.start + f.count
            // The unit follows the number, past a counter word («دو هزار تا دیگه ظرفیت»).
            var j = end
            var skipped = 0
            while (j < norm.size && norm[j] in COUNTERS && skipped < MAX_SKIP) { j++; skipped++ }
            val inline = currencyInline(words[f.start])
            val unit = inline ?: norm.getOrNull(j)?.let(::unitOf)
            val unitAt = if (inline == null && unit != null) j else -1
            val kind = when {
                f.percent -> Kind.Percent
                unit != null -> unit.kind
                else -> Kind.Plain
            }
            val after = (maxOf(end, unitAt + 1) until minOf(norm.size, maxOf(end, unitAt + 1) + LOOK_AHEAD)).map { norm[it] }
            Quantity(
                at = f.start, count = f.count, value = f.value, decimals = f.decimals, kind = kind, unitAt = unitAt,
                unit = unit?.display.orEmpty(), brand = unit?.brand,
                remaining = kind == Kind.Duration && remainingIn(after),
                first = after.firstOrNull() in FIRST,
                opened = kind == Kind.Capacity && (norm.subList(end, minOf(norm.size, end + LOOK_AHEAD)).any { it in OPENED }),
            )
        }
    }

    /** The line's numbers with its cues; [offset] shifts word positions to transcript indices. */
    fun sense(words: List<String>, offset: Int = 0): Sense {
        val norm = words.map(NumberWords::normalize)
        val filled = norm.indices.firstNotNullOfOrNull { i -> filledAt(norm, i) }
        return Sense(
            quantities = read(words).map { it.shifted(offset) },
            filled = filled?.let { (it.first + offset)..(it.last + offset) },
            gift = norm.any { w -> GIFT.any { w.startsWith(it) } },
            price = norm.any { it in PRICE },
        )
    }

    /**
     * Every line's sense, read across line breaks: speech pauses split «دو هزار | تا دیگه ظرفیت»,
     * «ساعتم بیشتر | نمونده» and «پنجاه هزار بازدید | خورد و پنج هزار کامنت», so units and cues are
     * read on the whole transcript and each number belongs to the line it starts in. Social numbers
     * said in the next line join this line's (one stats board, not two counters); a gift said in
     * the next line («سیصد و پنجاه تتر سرمایه | اولیه …») still makes this one a gift.
     */
    fun senses(texts: List<String>, lines: List<Lines.Line>): List<Sense> {
        val norm = texts.map(NumberWords::normalize)
        val all = read(texts)
        val fills = norm.indices.mapNotNull { i -> filledAt(norm, i) }
        fun own(line: Lines.Line) = all.filter { it.at in line.range }
        return lines.mapIndexed { k, line ->
            val next = lines.getOrNull(k + 1)
            val mine = own(line)
            val joined = if (mine.any { it.social } && next != null) mine + own(next).filter { it.social } else mine
            val window = line.first..(next?.last ?: line.last)
            Sense(
                quantities = joined,
                filled = fills.firstOrNull { it.first in line.range },
                gift = window.any { i -> GIFT.any { norm[i].startsWith(it) } },
                price = line.range.any { norm[it] in PRICE },
            )
        }
    }

    private fun filledAt(norm: List<String>, i: Int): IntRange? = when {
        norm[i] in FILLED_ONE -> i..i
        norm[i] in FILLED_HEAD && norm.getOrNull(i + 1)?.trimEnd('.', '،', ',') in FILLED_TAIL -> i..i + 1
        else -> null
    }

    /** What a unit word means, tolerant of Persian suffixes («تتری», «ساعته», «بازدیدها»). */
    fun unitOf(word: String): Unit? {
        val w = NumberWords.normalize(word).trimEnd('.', '،', ',')
        if (w.isEmpty()) return null
        UNITS[w]?.let { return it }
        return UNITS.entries.firstOrNull { (key, _) -> key.length >= 3 && w.startsWith(key) && w.removePrefix(key) in SUFFIXES }?.value
    }

    /** The canonical brand of a coin named in [word] («تتر» → Tether), or null. */
    fun coinOf(word: String): String? = unitOf(word)?.takeIf { it.kind == Kind.Money }?.brand

    /** [value] as the screen shows it: no grouping, Persian digits on a Persian piece. */
    fun digits(value: Double, persian: Boolean): String {
        val whole = value == kotlin.math.floor(value)
        val s = if (whole) value.toLong().toString() else (kotlin.math.round(value * 10.0) / 10.0).toString()
        return if (persian) Numerals.toPersian(s) else s
    }

    private fun currencyInline(word: String): Unit? = when {
        '$' in word -> Unit(Kind.Money, "$")
        '€' in word -> Unit(Kind.Money, "€")
        else -> null
    }

    private fun remainingIn(after: List<String>): Boolean =
        after.any { it in REMAINING } || after.firstOrNull() in SOON ||
            after.zipWithNext().any { (a, b) -> a == "تا" && b in ENDING }

    private val SOCIAL = setOf(Kind.Views, Kind.Comments, Kind.Likes, Kind.Followers)

    private val UNITS: Map<String, Unit> = buildMap {
        fun put(kind: Kind, display: String, brand: String?, vararg keys: String) = keys.forEach { put(it, Unit(kind, display, brand)) }
        // Coins (the brand gets its mark on a voucher) and money.
        put(Kind.Money, "تتر", "Tether", "تتر")
        put(Kind.Money, "USDT", "Tether", "usdt", "tether")
        put(Kind.Money, "بیت‌کوین", "Bitcoin", "بیتکوین")
        put(Kind.Money, "BTC", "Bitcoin", "btc", "bitcoin", "bitcoins")
        put(Kind.Money, "اتریوم", "Ethereum", "اتریوم", "اتریم")
        put(Kind.Money, "ETH", "Ethereum", "eth", "ethereum")
        put(Kind.Money, "سولانا", "Solana", "سولانا")
        put(Kind.Money, "SOL", "Solana", "solana")
        put(Kind.Money, "ترون", "TRON", "ترون")
        put(Kind.Money, "TRX", "TRON", "trx", "tron")
        put(Kind.Money, "دوج‌کوین", "Dogecoin", "دوجکوین")
        put(Kind.Money, "DOGE", "Dogecoin", "doge", "dogecoin")
        put(Kind.Money, "BNB", "BNB", "bnb")
        put(Kind.Money, "XRP", "XRP", "xrp", "ریپل")
        put(Kind.Money, "دلار", null, "دلار")
        put(Kind.Money, "$", null, "usd", "dollar", "dollars", "bucks")
        put(Kind.Money, "تومان", null, "تومان", "تومن")
        put(Kind.Money, "ریال", null, "ریال")
        put(Kind.Money, "یورو", null, "یورو")
        put(Kind.Money, "€", null, "euro", "euros", "eur")
        // Time.
        put(Kind.Duration, "ساعت", null, "ساعت", "سات", "ساعات")
        put(Kind.Duration, "دقیقه", null, "دقیقه", "دیقه")
        put(Kind.Duration, "ثانیه", null, "ثانیه")
        put(Kind.Duration, "روز", null, "روز")
        put(Kind.Duration, "هفته", null, "هفته")
        put(Kind.Duration, "hours", null, "hour", "hours", "hrs")
        put(Kind.Duration, "minutes", null, "minute", "minutes", "mins", "min")
        put(Kind.Duration, "days", null, "day", "days")
        put(Kind.Duration, "weeks", null, "week", "weeks")
        // People and room.
        put(Kind.People, "نفر", null, "نفر", "نفرات")
        put(Kind.People, "کاربر", null, "کاربر", "کاربران")
        put(Kind.People, "people", null, "people", "users", "persons", "person", "user")
        put(Kind.Capacity, "ظرفیت", null, "ظرفیت")
        put(Kind.Capacity, "spots", null, "spots", "seats", "places", "slots")
        // Social.
        put(Kind.Views, "بازدید", null, "بازدید", "بازید", "ویو", "ویوو")
        put(Kind.Views, "views", null, "view", "views")
        put(Kind.Comments, "کامنت", null, "کامنت")
        put(Kind.Comments, "comments", null, "comment", "comments")
        put(Kind.Likes, "لایک", null, "لایک")
        put(Kind.Likes, "likes", null, "like", "likes")
        put(Kind.Followers, "فالوور", null, "فالوور", "فالور", "فالوئر", "فالو")
        put(Kind.Followers, "followers", null, "follower", "followers", "subscribers", "subs")
    }

    /** Persian endings a unit word takes in speech («تتری», «ساعتم», «کامنتا», «بازدیدها»). */
    private val SUFFIXES = setOf("", "ی", "ه", "و", "ا", "م", "ها", "های", "ای", "رو", "ش", "تون", "مون", "شون", "یی", "s")

    /** Words between a number and its unit («دو هزار تا دیگه ظرفیت»). */
    private val COUNTERS = setOf("تا", "تای", "عدد", "دونه", "دیگه", "دیگر", "more", "other", "extra")
    private const val MAX_SKIP = 2
    private const val LOOK_AHEAD = 5

    private val REMAINING = setOf(
        "مونده", "نمونده", "مانده", "نمانده", "باقی", "باقیه", "باقیمونده", "باقیمانده", "فرصت", "left", "remaining", "remain", "remains",
    )
    private val SOON = setOf("دیگه", "دیگر", "more")
    private val ENDING = setOf("پایان", "آخر", "اتمام", "تموم", "تمام", "end", "close")
    private val FIRST = setOf("اول", "اولی", "first")
    private val OPENED = setOf("باز", "اضافه", "جدید", "دیگه", "دیگر", "new", "more", "opened", "added", "extra")
    private val FILLED_ONE = setOf("تکمیل", "تکمیله", "پرشد", "full", "filled", "soldout")
    private val FILLED_HEAD = setOf("پر", "تموم", "تمام", "sold")
    private val FILLED_TAIL = setOf("شد", "شده", "شدن", "میشه", "out")
    private val GIFT = listOf(
        "ووچر", "وچر", "voucher", "هدیه", "جایزه", "بونوس", "bonus", "رایگان", "free", "gift", "reward", "سرمایه", "اعتبار", "credit", "دریافت", "بگیر",
    )
    private val PRICE = setOf("قیمت", "قیمتش", "price", "ارزش", "نرخ")

    private const val GIFT_WEIGHT = 400.0
    private const val STATS_WEIGHT = 250.0
    private const val DEADLINE_WEIGHT = 150.0
}
