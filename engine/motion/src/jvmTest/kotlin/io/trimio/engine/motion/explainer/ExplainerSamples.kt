package io.trimio.engine.motion.explainer

/** Generic sample content for every primitive, in Persian and English. */
object ExplainerSamples {
    private val wordsFa = listOf("نوشتن", "کدنویسی", "تحلیل", "گفتگو", "برنامه‌ریزی", "یادگیری", "خلاصه", "ترجمه")
    private val wordsEn = listOf("Writing", "Code", "Analysis", "Chat", "Planning", "Learning", "Summary", "Translation")
    private val visuals = listOf("pencil", "code", "analysis", "chat", "calendar", "book", "doc", "globe")
    private val phrasesFa = listOf(
        "یه سؤال ساده", "کارهای سبک و تکراری", "اول با خودش فکر می‌کنه", "بهترین راه رو پیدا می‌کنه", "خیلی آروم کم می‌شه",
        "فقط برای سخت‌ترین بخش", "هر کدوم یه کاری بلده", "۱۲ کار در روز",
    )
    private val phrasesEn = listOf(
        "A simple question", "Light, repetitive tasks", "It thinks first", "It finds the best path", "Drains very slowly",
        "Only for the hardest part", "Each one has a job", "12 tasks a day",
    )

    fun word(lang: String, i: Int) = (if (lang == "fa") wordsFa else wordsEn)[i.mod(wordsFa.size)]

    fun phrase(lang: String, i: Int) = (if (lang == "fa") phrasesFa else phrasesEn)[i.mod(phrasesFa.size)]

    fun items(lang: String, n: Int, from: Int = 0): List<Item> = (from until from + n).map { i -> Item(word(lang, i), (i + 1.5) * 10.0, visuals[i.mod(visuals.size)]) }

    fun content(p: Primitive, lang: String): BeatContent {
        val items = items(lang, 3)
        return when (p) {
            Primitive.Heading, Primitive.Kicker, Primitive.Note, Primitive.Quote, Primitive.Tangle, Primitive.Thinking, Primitive.Clock ->
                BeatContent(text = phrase(lang, p.ordinal), caption = phrase(lang, p.ordinal + 1))
            Primitive.BigWord, Primitive.Badge -> BeatContent(text = phrase(lang, 5), visual = "trophy")
            Primitive.Stat -> BeatContent(text = phrase(lang, 6), value = 42.0, unit = "%")
            Primitive.Meter, Primitive.Ring -> BeatContent(text = phrase(lang, 4), value = 0.9)
            Primitive.Icon -> BeatContent(text = word(lang, 2), visual = "analysis")
            Primitive.Profile -> BeatContent(text = word(lang, 0), caption = phrase(lang, 0), visual = "intern")
            Primitive.Branches, Primitive.Map -> BeatContent(text = word(lang, 1), items = items)
            Primitive.Blocks, Primitive.Dots, Primitive.TaskTiles -> BeatContent(text = phrase(lang, 1), value = 8.0)
            Primitive.Scale -> BeatContent(items = items.take(2), value = 0.5)
            Primitive.Mark -> BeatContent(text = phrase(lang, 3))
            else -> BeatContent(items = items, caption = phrase(lang, 7))
        }
    }
}
