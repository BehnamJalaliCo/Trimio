package io.trimio.engine.autopilot.explainer

import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.score.NumberWords

/**
 * The idea a sentence carries, as a designer would draw it — never the words themselves: "like an
 * intern" is a profile card with a tag, "light repetitive work" a row of task tiles ticked one by
 * one, "usage drains slowly" a meter at 100 → 98 → 95, "thinks first, finds the best path"
 * branching paths with the best one lit, "deep complex problems" a tangle, "strongest" bars with
 * the tallest badged, "only for the hardest part" a row of blocks in two colours.
 *
 * [pictures] are the primitives that draw the idea, best first, with how often a seed takes each
 * (variety without losing the fit).
 */
enum class Idea(val pictures: List<Pair<Primitive, Float>>) {
    Persona(listOf(Primitive.Profile to 1f)),
    Routine(listOf(Primitive.TaskTiles to 0.75f, Primitive.Checklist to 0.25f)),
    Drain(listOf(Primitive.Meter to 0.75f, Primitive.Ring to 0.25f)),
    Grow(listOf(Primitive.Bars to 0.6f, Primitive.Meter to 0.2f, Primitive.Stack to 0.2f)),
    Uses(listOf(Primitive.IconTiles to 0.7f, Primitive.Grid to 0.3f)),
    Path(listOf(Primitive.Branches to 0.85f, Primitive.Flow to 0.15f)),
    Think(listOf(Primitive.Thinking to 0.7f, Primitive.Branches to 0.3f)),
    Tangle(listOf(Primitive.Tangle to 1f)),
    Rank(listOf(Primitive.Bars to 0.65f, Primitive.Ladder to 0.35f)),
    Portion(listOf(Primitive.Blocks to 0.75f, Primitive.Dots to 0.25f)),
    Slow(listOf(Primitive.Clock to 1f)),
    Fast(listOf(Primitive.Clock to 1f)),
    /** Less and less: bars that fall, a meter that drains. */
    Decline(listOf(Primitive.Bars to 0.5f, Primitive.Meter to 0.5f)),
    Compare(listOf(Primitive.Versus to 0.6f, Primitive.Scale to 0.4f)),
    Change(listOf(Primitive.BeforeAfter to 1f)),
    Steps(listOf(Primitive.Steps to 0.5f, Primitive.Flow to 0.3f, Primitive.Timeline to 0.2f)),
    Cycle(listOf(Primitive.Cycle to 1f)),
    Filter(listOf(Primitive.Funnel to 1f)),
    Balance(listOf(Primitive.Scale to 1f)),
    Accumulate(listOf(Primitive.Stack to 0.7f, Primitive.Bars to 0.3f)),
    Levels(listOf(Primitive.Ladder to 0.7f, Primitive.Steps to 0.3f)),
    Question(listOf(Primitive.BigWord to 0.6f, Primitive.Thinking to 0.4f)),
    Talk(listOf(Primitive.Chat to 1f)),
    Warning(listOf(Primitive.Badge to 0.6f, Primitive.Mark to 0.4f)),
    Goal(listOf(Primitive.Icon to 0.6f, Primitive.Badge to 0.4f)),
    Money(listOf(Primitive.Stack to 0.75f, Primitive.Icon to 0.25f)),
    Many(listOf(Primitive.Dots to 1f)),
    Place(listOf(Primitive.Map to 1f)),
    History(listOf(Primitive.Timeline to 1f)),
    Quote(listOf(Primitive.Quote to 1f)),
    Insight(listOf(Primitive.Icon to 0.6f, Primitive.Note to 0.4f)),
    Success(listOf(Primitive.Mark to 0.6f, Primitive.Badge to 0.4f)),
    Connect(listOf(Primitive.Flow to 0.6f, Primitive.Grid to 0.4f)),
    /** Qualities said one after another («باهوش، خلاق، استدلال قوی»): tiles that build in as each is said. */
    Traits(listOf(Primitive.IconTiles to 0.7f, Primitive.Chips to 0.3f)),
    /** Numbers said: a stat, a ring for a percent, dots for "n of m" (chosen from the figure itself). */
    Figure(listOf(Primitive.Stat to 1f)),
}

/** An idea found in a clause: where its cue word is (a word index) and how strongly it is said. */
data class Found(val idea: Idea, val at: Int, val strength: Float)

/**
 * Finds the ideas in a clause from the words themselves (Persian and English cue stems, said or
 * colloquial) and, when a model read the line, from its English gist. Patterns are word stems:
 * "x*" matches a word starting with x, "*x" one ending with x, anything else the whole word;
 * a pattern of several words matches them in a row and counts for more.
 */
object Metaphors {

    private class Cue(val idea: Idea, val weight: Float, val words: List<String>)

    private val CUES: List<Cue> = buildList {
        fun cue(idea: Idea, weight: Float, vararg patterns: String) = patterns.forEach { add(Cue(idea, weight, it.split(' '))) }
        cue(
            Idea.Persona, 1.2f, "مثل یه", "مثل یک", "like a", "like an", "کارآموز*", "کاراموز*", "intern*", "دستیار*", "assistant*", "تازهکار*", "تازهوارد*",
            "beginner*", "junior", "senior", "متخصص*", "expert*", "استاد*", "mentor*", "مربی*", "coach*", "کارمند*", "employee*", "مشاور*", "advisor*",
        )
        cue(Idea.Routine, 1.2f, "تکراری*", "روتین*", "routine*", "repetitive", "chores", "busywork", "کار سبک", "کارهای سبک", "کارای سبک", "simple tasks", "small tasks")
        cue(Idea.Routine, 0.7f, "سبک", "ساده", "light", "simple", "easy", "آسون", "آسان")
        cue(
            Idea.Drain, 1.2f, "محدودیت*", "سهمیه*", "quota*", "usage", "drain*", "runs out", "run out", "battery", "باتری*", "بودجه*", "budget*", "credits",
            "تموم میشه", "تمام میشود", "کم میشه", "کم میشود", "کمتر میشه", "مصرف*", "limit", "limits",
        )
        cue(Idea.Grow, 1.1f, "رشد*", "افزایش*", "grow*", "increase*", "rising", "rise", "بالا میره", "بیشتر میشه", "بیشتر و بیشتر", "more and more", "compound*", "مرکب")
        cue(
            Idea.Uses, 1.3f, "کارهای عمومی", "کارای عمومی", "کارهای روزمره", "روزمره*", "everyday", "most tasks", "most things", "اکثر کارها", "اکثر کارهای",
            "general purpose", "all-round", "همه کار", "همهکاره", "کاربرد*", "use cases", "uses",
        )
        cue(
            Idea.Path, 1.6f, "بهترین راه", "بهترین مسیر", "راه درست", "best way", "best path", "right path", "best route", "plan*", "نقشه راه", "strategy",
            "استراتژی*", "مسیر*", "path", "paths", "route",
        )
        cue(Idea.Path, 0.8f, "راه", "تصمیم*", "decide*", "decision*", "انتخاب میکنه", "choose*")
        cue(Idea.Think, 0.9f, "فکر*", "think*", "thought", "reason*", "استدلال*", "تحلیل*", "analy*", "ذهن*", "mind")
        cue(Idea.Tangle, 1.1f, "پیچیده*", "complex*", "complicated", "messy", "tangled", "tricky", "عمیق*", "deep", "گیر", "stuck", "سردرگم*", "confus*", "گره*", "knot*")
        cue(Idea.Tangle, 0.8f, "مسئله*", "مسائل*", "مساله*", "problem*", "مشکل*", "سخت", "hard")
        cue(
            Idea.Rank, 1.3f, "قویترین", "بهترین مدل", "برترین*", "قدرتمندترین", "strongest", "most powerful", "the best", "top tier", "سریعترین", "fastest",
            "رتبه اول", "number one", "first place",
        )
        cue(Idea.Rank, 0.6f, "*ترین")
        cue(
            Idea.Portion, 1.5f, "فقط برای", "فقط واسه", "فقط تو", "only for", "just for", "only use", "only the", "بخشی از", "قسمتی از", "part of", "portion",
            "some of", "بقیه*", "the rest", "بیشترش", "بیشتر کار", "most of the work", "سهم*", "share of",
        )
        cue(Idea.Portion, 0.7f, "قسمت*", "بخش*", "part", "parts", "فقط", "only")
        cue(Idea.Slow, 1.2f, "دیرتر", "دیر", "کند*", "کندتر", "slower", "slow*", "طول میکشه", "takes time", "takes longer", "صبر*", "wait*", "longer")
        cue(Idea.Fast, 1.1f, "سریع*", "فوری*", "فورا", "در لحظه", "fast*", "quick*", "instant*", "seconds", "سرعت*", "speed*", "زود")
        cue(Idea.Compare, 1.1f, "در مقابل", "برعکس*", "مقایسه*", "versus", "vs", "compared", "unlike", "instead", "به جای", "بجای")
        cue(Idea.Compare, 0.8f, "فرق*", "تفاوت*", "difference*", "differ*")
        cue(Idea.Change, 1.1f, "قبلا", "قبلاً", "used to", "before and after", "تبدیل*", "turns into", "turned into", "عوض*", "changed", "transform*")
        cue(Idea.Steps, 1.1f, "مرحله*", "گام*", "قدم*", "step", "steps", "process", "فرایند*", "فرآیند*", "مراحل*")
        cue(Idea.Steps, 0.8f, "اول", "دوم", "سوم", "بعدش", "سپس", "در نهایت", "first", "second", "third", "then", "finally", "next")
        cue(Idea.Cycle, 1.2f, "هر روز", "هرروز", "هر شب", "هر هفته", "هر صبح", "عادت*", "چرخه*", "every day", "every night", "every morning", "daily", "habit*", "cycle*", "loop*", "again and again", "repeat*")
        cue(Idea.Filter, 1.2f, "از بین", "فیلتر*", "filter*", "narrow*", "funnel", "only a few", "out of")
        cue(Idea.Balance, 1.1f, "تعادل*", "توازن*", "balance*", "trade-off", "tradeoff", "هزینه و فایده", "سود و ضرر", "pros and cons")
        cue(Idea.Decline, 1.1f, "کمتری", "کمتر میشه", "کاهش*", "افت*", "less and less", "decreas*", "declin*", "shrink*", "loses", "falls", "drops", "lower")
        cue(Idea.Accumulate, 1.1f, "جمع میشه", "روی هم", "پسانداز*", "انباشت*", "stack*", "pile*", "adds up", "add up", "build up", "builds up", "لایه*", "layer*", "savings")
        cue(Idea.Levels, 1.1f, "سطح*", "سطوح", "رده*", "level*", "tier*", "پله*", "نردبان*", "ladder", "ارتقا*", "upgrade*", "نسخه*", "version*")
        cue(Idea.Talk, 1.0f, "بپرسی*", "بپرسید", "سوال کن*", "chat*", "ask*", "conversation", "پیام*", "message*", "گفتگو*", "گفتوگو*", "dialog*")
        cue(Idea.Warning, 1.2f, "مراقب*", "خطر*", "اشتباه*", "نکنید", "هرگز", "careful", "danger*", "mistake*", "never", "avoid*", "warning", "ریسک*", "risk*", "حواستون")
        cue(Idea.Goal, 1.0f, "هدف*", "goal*", "target*", "aim", "مقصد*", "رسیدن به", "reach")
        cue(Idea.Money, 1.0f, "پول*", "هزینه*", "قیمت*", "تومن*", "تومان*", "دلار*", "money", "cost*", "price*", "dollar*", "pay*", "پرداخت*", "سود*", "profit*", "درآمد*", "income")
        cue(Idea.Many, 1.0f, "میلیونها", "هزاران", "صدها", "خیلیها", "مردم", "people", "millions", "thousands", "hundreds", "everyone", "users", "کاربرا*", "کاربران*")
        cue(Idea.Place, 1.0f, "کشور*", "جهان*", "دنیا*", "country", "countries", "world*", "map", "منطقه*", "region*", "شهر*", "city", "cities", "spread*", "across", "همه جا", "everywhere", "پخش*")
        cue(Idea.History, 1.1f, "قرن*", "تاریخ*", "دوران*", "century", "centuries", "ago", "history", "era", "ancient", "سال پیش", "years ago", "قدیم*", "قبل از میلاد")
        cue(Idea.Quote, 1.0f, "میگه", "گفته", "گفت", "said", "says", "according to", "به گفته", "quote*")
        cue(Idea.Insight, 1.0f, "ایده*", "نکته*", "راز*", "idea*", "tip*", "secret*", "trick*", "insight*", "کلید*", "key")
        cue(Idea.Success, 1.0f, "خیلی خوب", "خوب جواب", "حل میکنه", "حل میشه", "موفق*", "solved", "solves", "success*", "works well", "نتیجه*", "result*", "درست میشه")
        cue(Idea.Connect, 1.0f, "وصل*", "ارتباط*", "شبکه*", "connect*", "network*", "link*", "یکپارچه*", "integrat*")
        cue(Idea.Traits, 1.0f, "باهوش*", "خلاق*", "قدرت*", "دقیق*", "smart*", "creative*", "powerful", "precise", "accurate", "clever", "intelligen*", "هوش*")
    }

    /** Traits said in a row: each one's picture query, so tiles can carry an icon. */
    val TRAIT_VISUALS: Map<String, String> = mapOf(
        "باهوش" to "light bulb", "هوش" to "light bulb", "smart" to "light bulb", "intelligen" to "light bulb", "clever" to "light bulb",
        "خلاق" to "sparkles", "creative" to "sparkles", "استدلال" to "puzzle piece", "reason" to "puzzle piece", "قدرت" to "flexed biceps",
        "powerful" to "flexed biceps", "دقیق" to "direct hit", "precise" to "direct hit", "accurate" to "direct hit", "سریع" to "high voltage", "fast" to "high voltage",
    )

    /** Every idea [keys] (normalised words, [first] the index of the first) says, strongest cue per idea. */
    fun find(keys: List<String>, first: Int = 0, gist: String = ""): List<Found> {
        val words = keys.map(::bare)
        val out = mutableMapOf<Idea, Found>()
        for (cue in CUES) {
            val at = matchAt(words, cue.words) ?: continue
            val strength = cue.weight * (1f + MULTI * (cue.words.size - 1))
            val prev = out[cue.idea]
            if (prev == null || prev.strength < strength) out[cue.idea] = Found(cue.idea, first + at, strength)
        }
        // The model's English gist of the line: the same cues, a little weaker, timed to the clause's start.
        if (gist.isNotBlank()) {
            val g = gist.lowercase().split(Regex("[^a-z0-9'-]+")).filter { it.isNotEmpty() }
            for (cue in CUES) {
                if (matchAt(g, cue.words) == null) continue
                val strength = GIST * cue.weight * (1f + MULTI * (cue.words.size - 1))
                if ((out[cue.idea]?.strength ?: 0f) < strength) out[cue.idea] = Found(cue.idea, first, strength)
            }
        }
        return out.values.sortedByDescending { it.strength }
    }

    /** Where [pattern] starts in [words], or null. */
    private fun matchAt(words: List<String>, pattern: List<String>): Int? {
        for (i in 0..words.size - pattern.size) {
            if (pattern.indices.all { j -> fits(words[i + j], pattern[j]) }) return i
        }
        return null
    }

    private fun fits(word: String, p: String): Boolean = when {
        word.isEmpty() -> false
        p.startsWith('*') -> word.endsWith(p.drop(1)) && word.length > p.length + 1
        p.endsWith('*') -> word.startsWith(p.dropLast(1))
        else -> word == p
    }

    /** A word as the cues read it: normalised, no half-space, no punctuation, «ي» → «ی». */
    fun bare(w: String): String = NumberWords.normalize(w).replace("‌", "").replace("ٔ", "").replace("أ", "ا")

    /** Whether [word] is said as a quality ([TRAIT_VISUALS]): its picture query, or null. */
    fun trait(word: String): String? {
        val b = bare(word)
        return TRAIT_VISUALS.entries.firstOrNull { (k, _) -> b.startsWith(k) }?.value
    }

    /** The other pictures of the idea [p] draws, for a seed that wants a change ([Idea.pictures]). */
    fun alternatives(p: Primitive): List<Primitive> =
        Idea.entries.filter { i -> i.pictures.any { it.first == p } }.flatMap { i -> i.pictures.map { it.first } }.distinct().filter { it != p }

    /** Ideas whose picture [p] can be. */
    fun ideasOf(p: Primitive): List<Idea> = Idea.entries.filter { i -> i.pictures.any { it.first == p } }

    private const val MULTI = 0.3f
    private const val GIST = 0.8f
}
