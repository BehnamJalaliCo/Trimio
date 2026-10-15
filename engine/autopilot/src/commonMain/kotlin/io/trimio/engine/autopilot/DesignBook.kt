package io.trimio.engine.autopilot

import io.trimio.engine.autopilot.resources.Res
import io.trimio.engine.motion.score.NumberWords
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An editor's memory: lines as creators said them and what a senior motion editor put on screen for each — the show,
 * its title and items, and whether it took the frame (the speaker kept in a corner card). A new line recalls the nearest
 * moments, by what it does (a figure, a price, a share of a whole, names, a question, an ask, a contrast, someone's
 * words) and by its words, and the director sees them as examples: a small model on a phone adapts a good example far
 * better than it invents from a list of names. The book grows with every edit an editor directs or a creator rates well.
 */
object DesignBook {

    @Serializable
    data class Moment(
        val said: String,
        val show: String,
        val title: String = "",
        val items: List<String> = emptyList(),
        val role: String = "claim",
        /** The graphic took the frame, the speaker in a corner card. */
        val stage: Boolean = false,
        val lang: String = "fa",
    )

    @Serializable
    private data class Book(val note: String = "", val moments: List<Moment> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** The moments shipped with the app. */
    suspend fun load(): List<Moment> = runCatching { parse(Res.readBytes("files/design/moments.json").decodeToString()) }.getOrDefault(emptyList())

    fun parse(text: String): List<Moment> = json.decodeFromString(Book.serializer(), text).moments

    /** What a line does, as far as its words tell: the traits that decide how an editor shows it. */
    enum class Trait { Figure, Money, Percent, Share, Names, Question, Ask, Contrast, Speech, Time, Steps, Site, Free, Self }

    fun traits(words: List<String>): Set<Trait> {
        val norm = words.map(::key)
        val numbers = NumberWords.findAll(words)
        val out = mutableSetOf<Trait>()
        if (numbers.isNotEmpty()) out += Trait.Figure
        if (numbers.any { it.percent }) out += Trait.Percent
        if (numbers.size >= 2) out += Trait.Share
        if (norm.any { w -> MONEY.any { w.startsWith(it) } } && numbers.isNotEmpty()) out += Trait.Money
        if (words.count { w -> w.any { it in 'A'..'Z' || it in 'a'..'z' } } >= 1 && words.any { w -> w.any { it in '؀'..'ۿ' } }) out += Trait.Names
        if (words.any { it.endsWith('?') || it.endsWith('؟') } || norm.any { it in QUESTIONS }) out += Trait.Question
        if (norm.any { w -> ASKS.any { w.startsWith(it) } }) out += Trait.Ask
        if (norm.any { it in CONTRASTS }) out += Trait.Contrast
        if (norm.any { it in SPEECH }) out += Trait.Speech
        if (norm.any { it in TIME } && numbers.isNotEmpty()) out += Trait.Time
        if (norm.count { it in ORDINALS } >= 2) out += Trait.Steps
        if (norm.any { it.contains(".com") || it.contains(".ir") || it == "سایت" || it == "سایتمون" }) out += Trait.Site
        if (norm.any { w -> FREE.any { w.startsWith(it) } }) out += Trait.Free
        if (norm.any { it in SELF }) out += Trait.Self
        return out
    }

    /**
     * The [k] moments nearest to [line]: shared traits weigh most (they decide the device), then shared words (they
     * carry the field). A moment whose show needs what the line lacks (a figure, money) is not recalled.
     */
    fun recall(line: List<String>, moments: List<Moment>, k: Int = 2, latin: Boolean = false): List<Moment> {
        val mine = traits(line)
        val stems = line.map(::stem).filter { it.length >= MIN_STEM }.toSet()
        return moments.filter { (it.lang == "en") == latin }.map { m ->
            val words = m.said.split(' ')
            val theirs = traits(words)
            val shared = (mine intersect theirs).size
            val odd = (mine union theirs).size - shared
            val overlap = words.map(::stem).filter { it.length >= MIN_STEM }.toSet().let { s -> if (s.isEmpty() || stems.isEmpty()) 0f else (s intersect stems).size.toFloat() / minOf(s.size, stems.size) }
            m to (shared * TRAIT_WEIGHT - odd * MISMATCH_WEIGHT + overlap * WORD_WEIGHT)
        }.filter { (m, score) -> score > 0f && fits(m, mine) }.sortedByDescending { it.second }.take(k).map { it.first }
    }

    /** Whether the line holds what the moment's show needs. */
    private fun fits(m: Moment, traits: Set<Trait>): Boolean = when (m.show) {
        "counter", "ring", "chart", "meter", "stats", "candles", "progress", "countdown" -> Trait.Figure in traits
        "price-tag", "voucher" -> Trait.Money in traits
        "comment" -> Trait.Ask in traits
        "message" -> Trait.Speech in traits || Trait.Question in traits
        else -> true
    }

    /** The line of a prompt that shows [moments] to the director for one line. */
    fun examples(moments: List<Moment>): String = moments.joinToString("\n") { m ->
        buildString {
            append("   an editor showed a line like «").append(m.said).append("» as: show=").append(m.show)
            if (m.title.isNotBlank()) append(", title=«").append(m.title).append('»')
            if (m.items.isNotEmpty()) append(", items=").append(m.items.joinToString(" | "))
        }
    }

    private fun key(w: String) = NumberWords.normalize(w).trim('،', ',', '.', '!', '؟', '?', '«', '»')

    /** A rough stem: the word without its common Persian endings («سیگنال‌ها» → «سیگنال»). */
    private fun stem(w: String): String {
        var s = key(w)
        for (e in ENDINGS) if (s.length - e.length >= MIN_STEM && s.endsWith(e)) { s = s.dropLast(e.length); break }
        return s
    }

    private val ENDINGS = listOf("هایی", "های", "ها", "مون", "تون", "شون", "یم", "ید", "ند", "ش", "م", "ی", "ه")
    private val MONEY = listOf("تومن", "تومان", "دلار", "دولار", "تتر", "ریال", "یورو", "$", "usdt", "dollar", "€")
    private val QUESTIONS = setOf("چرا", "چطور", "چطوری", "چجوری", "کدوم", "کدام", "آیا", "چی", "چیه", "why", "how", "which", "what")
    private val ASKS = listOf("کامنت", "فالو", "دایرکت", "سیو", "لایک", "comment", "follow", "subscribe", "dm")
    private val CONTRASTS = setOf("ولی", "اما", "برعکس", "یا", "but", "versus", "vs", "or")
    private val SPEECH = setOf("گفت", "میگه", "میگفت", "پرسید", "نوشته", "نوشت", "میگن", "بپرس", "said", "asked", "says", "wrote")
    private val TIME = setOf("ساعت", "روز", "دقیقه", "ثانیه", "هفته", "ماه", "جمعه", "hours", "days", "minutes")
    private val ORDINALS = setOf("اول", "دوم", "سوم", "چهارم", "بعد", "first", "second", "third", "then")
    private val FREE = listOf("رایگان", "مجانی", "free")
    private val SELF = setOf("من", "هستم", "اسمم", "i'm", "my")
    private const val MIN_STEM = 2
    private const val TRAIT_WEIGHT = 3f
    private const val MISMATCH_WEIGHT = 1f
    private const val WORD_WEIGHT = 4f
}
