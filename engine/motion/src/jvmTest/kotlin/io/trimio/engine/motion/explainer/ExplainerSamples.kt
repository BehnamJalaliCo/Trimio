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

    /** One chapter per primitive: every op lands on it, beside a heading and under a mark. */
    fun everyOp(lang: String, palette: String, layout: String = "header-top", mark: String = "dashes", captions: String = "band-pill"): ExplainerFilm {
        val all = Primitive.entries
        val span = 9.0
        val chapters = all.mapIndexed { k, p ->
            val t0 = k * span
            val next = all[(k + 1) % all.size]
            val third = all[(k + 2) % all.size]
            fun b(at: Double, op: StageOp, el: String, prim: Primitive? = null, c: BeatContent = BeatContent()) = Beat(t0 + at, t0 + at + 0.9, "line", op, el, prim, c)
            Chapter(
                "${word(lang, k)} $k", t0, t0 + span, k,
                listOf(
                    b(0.2, StageOp.Add, "lead", Primitive.Kicker, BeatContent(text = phrase(lang, 0))),
                    b(0.4, StageOp.Add, "x", p, content(p, lang)),
                    b(1.4, StageOp.Build, "x", c = BeatContent(items = items(lang, 4, 2), text = phrase(lang, 1))),
                    b(2.4, StageOp.Check, "x", c = BeatContent(picks = listOf(0, 1))),
                    b(3.2, StageOp.Fill, "x", c = BeatContent(value = 0.4)),
                    b(4.0, StageOp.Highlight, "x", c = BeatContent(picks = listOf(1))),
                    b(4.4, StageOp.Add, "m", Primitive.Mark),
                    b(4.8, StageOp.Dim, "x"),
                    b(5.3, StageOp.Highlight, "x"),
                    b(5.9, StageOp.Morph, "x", next, content(next, lang)),
                    b(6.9, StageOp.Replace, "x", third, content(third, lang)),
                    b(7.6, StageOp.Clear, "lead"),
                    b(8.0, StageOp.Clear, "x"),
                ),
            )
        }
        val captionChunks = (0 until all.size * 3).map { i -> CaptionChunk(phrase(lang, i), i * 3.0, i * 3.0 + 2.6) }
        return ExplainerFilm(ArtDirection(palette, layout, mark, captions, 7L), chapters, captionChunks, lang)
    }

}
