package io.trimio.engine.autopilot.explainer

import io.trimio.engine.autopilot.Phrases
import io.trimio.engine.motion.explainer.Item

/**
 * Short phrases for the screen, taken from what was said: the noun phrase around a cue
 * («کار سبک و تکراری», «محدودیت مصرف», "usage limit"), or the few words a cue starts («دیرتر جواب»),
 * never a whole sentence. And the example sets a director may draw when the narration names a kind
 * of thing without listing it ("most everyday tasks" → writing, code, analysis, chat).
 */
internal object Said {

    /** The noun phrase around word [at] of [c], at most [max] words, inside its clause and its comma. */
    fun around(n: Narration, c: Clause, at: Int, max: Int = MAX_PHRASE): String {
        val t = n.texts
        fun noun(i: Int) = i in c.words && Phrases.nounish(t[i]) && Metaphors.bare(t[i]) !in ADVERBS
        fun connector(i: Int) = i in c.words && Metaphors.bare(t[i]) in CONNECTORS
        fun breaks(i: Int) = t[i].trimEnd().lastOrNull()?.let { it in PUNCTUATION } == true
        var start = if (!noun(at) && noun(at + 1)) at + 1 else at
        var end = start
        while (end - start + 1 < max && !breaks(end)) {
            end = when {
                noun(end + 1) -> end + 1
                connector(end + 1) && noun(end + 2) && end - start + 3 <= max -> end + 2
                else -> break
            }
        }
        while (end - start + 1 < max) {
            start = when {
                noun(start - 1) && !breaks(start - 1) -> start - 1
                connector(start - 1) && noun(start - 2) && !breaks(start - 2) && end - start + 3 <= max -> start - 2
                else -> break
            }
        }
        return clean(t.subList(start, end + 1))
    }

    /** Up to [max] words from [at] on (function words left out), stopping at the clause's punctuation. */
    fun from(n: Narration, c: Clause, at: Int, max: Int = MAX_WINDOW): String {
        val out = mutableListOf<String>()
        var i = at
        while (i <= c.last && out.size < max) {
            val w = n.texts[i]
            if (Metaphors.bare(w) !in FUNCTION) out += w
            if (w.trimEnd().lastOrNull()?.let { it in PUNCTUATION } == true) break
            i++
        }
        return clean(out)
    }

    /** The clause's own strongest phrase ([Phrases.title]), at most [max] words; "" when it has none. */
    fun title(n: Narration, c: Clause, max: Int = MAX_PHRASE): String =
        Phrases.title(n.words, io.trimio.engine.autopilot.Lines.Line(c.first, c.last)).split(' ').take(max).joinToString(" ")

    private fun clean(words: List<String>): String =
        words.map { Phrases.clean(it).trim('«', '»', '"', '(', ')') }.filter { it.isNotBlank() }.dropWhile { Metaphors.bare(it) in CONNECTORS }
            .dropLastWhile { Metaphors.bare(it) in CONNECTORS || Metaphors.bare(it) in FUNCTION }.joinToString(" ")

    /** The picture query for a thing said ([Concepts.visualOf], and the everyday tech words it lacks), or null. */
    fun visualOf(word: String): String? = io.trimio.engine.autopilot.Concepts.visualOf(word) ?: Metaphors.bare(word).let { b ->
        THINGS.entries.firstOrNull { (k, _) -> b == k || (k.length >= MIN_STEM && b.startsWith(k) && b.length - k.length <= 2) }?.value
    }

    /** A role a persona card can show («کارآموز», "assistant"), as said; null when [word] is not one. */
    fun role(word: String): Boolean = Metaphors.bare(word).let { b -> ROLES.any { b == it || (it.length >= MIN_STEM && b.startsWith(it) && b.length - it.length <= 2) } }

    /** Example sets: what a kind of work or life is made of, by field, in the film's language. */
    fun examples(domain: String, persian: Boolean): List<Item> {
        val set = when (domain) {
            "finance", "crypto", "business" -> MONEY
            "health", "medical", "fitness", "food", "lifestyle" -> HEALTH
            "education", "news" -> STUDY
            else -> WORK
        }
        return set.map { (fa, en, visual) -> Item(if (persian) fa else en, visual = visual) }
    }

    private val WORK = listOf(Triple("نوشتن", "Writing", "pencil"), Triple("کدنویسی", "Code", "laptop"), Triple("تحلیل", "Analysis", "bar chart"), Triple("گفتگو", "Chat", "speech balloon"))
    private val MONEY = listOf(Triple("درآمد", "Income", "money bag"), Triple("خرج", "Spending", "receipt"), Triple("پس‌انداز", "Savings", "piggy bank"), Triple("سرمایه", "Investing", "chart increasing"))
    private val HEALTH = listOf(Triple("خواب", "Sleep", "bed"), Triple("تغذیه", "Food", "green salad"), Triple("ورزش", "Exercise", "person running"), Triple("آب", "Water", "droplet"))
    private val STUDY = listOf(Triple("مطالعه", "Reading", "books"), Triple("تمرین", "Practice", "pencil"), Triple("آزمون", "Tests", "memo"), Triple("مرور", "Review", "repeat button"))

    private val THINGS = mapOf(
        "server" to "desktop computer", "سرور" to "desktop computer", "app" to "mobile phone", "apps" to "mobile phone", "اپ" to "mobile phone",
        "اپلیکیشن" to "mobile phone", "cache" to "card file box", "کش" to "card file box", "database" to "file cabinet", "دیتابیس" to "file cabinet",
        "cloud" to "cloud", "ابر" to "cloud", "internet" to "globe with meridians", "اینترنت" to "globe with meridians", "email" to "e-mail", "ایمیل" to "e-mail",
        "password" to "key", "رمز" to "key", "security" to "locked", "امنیت" to "locked", "bug" to "bug", "باگ" to "bug", "notebook" to "notebook",
        "دفترچه" to "notebook", "chef" to "cook", "سرآشپز" to "cook", "request" to "outbox tray", "درخواست" to "outbox tray", "press" to "printer",
        "books" to "books", "book" to "closed book", "ideas" to "light bulb", "مغز" to "brain", "انرژی" to "high voltage", "نور" to "light bulb",
        "کافئین" to "hot beverage", "تورم" to "chart decreasing", "inflation" to "chart decreasing", "risk" to "warning", "ریسک" to "warning",
        "water" to "droplet", "beans" to "beans", "coffee" to "hot beverage", "هوش" to "robot", "model" to "robot",
    )
    private val ROLES = setOf(
        "کارآموز", "کاراموز", "دستیار", "کارمند", "مربی", "معلم", "استاد", "دکتر", "پزشک", "متخصص", "مشاور", "مدیر", "بچه", "دوست", "آشپز", "سرآشپز",
        "راننده", "نگهبان", "دانشجو", "شاگرد", "intern", "assistant", "employee", "coach", "teacher", "professor", "doctor", "expert", "advisor",
        "manager", "kid", "child", "friend", "chef", "cook", "driver", "guard", "beginner", "junior", "senior", "mentor", "student", "librarian",
    )
    private const val MIN_STEM = 3

    /** Words that describe how (fast, slow, very): never part of a label. */
    val ADVERBS = setOf("زود", "زودتر", "دیر", "دیرتر", "آروم", "اروم", "آرام", "سریع", "سریعتر", "کند", "کندتر", "خیلی", "کم", "کمتر", "بیشتر", "very", "slowly", "quickly", "faster")

    /** Words that frame a sentence ("suppose", «فرض کن», «یعنی»): never a label. */
    val FILLERS = setOf("فرض", "یعنی", "مثلا", "مثلاً", "suppose", "imagine", "say", "example")

    val CONNECTORS = setOf("و", "یا", "and", "or", "of")
    val FUNCTION = setOf(
        "رو", "را", "که", "به", "از", "با", "در", "تو", "توی", "هم", "یه", "یک", "این", "اون", "خیلی", "the", "a", "an", "to", "of", "is", "are", "it", "that", "very",
        "your", "you", "و",
    )
    private const val PUNCTUATION = ".،,!?؟:؛;…"
    private const val MAX_PHRASE = 4
    private const val MAX_WINDOW = 3
}
