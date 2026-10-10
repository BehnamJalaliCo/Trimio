package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Score
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A crypto-exchange promo (synthetic, the benchmark's corrected wording, evenly timed): money given,
 * social proof, a quota that filled, a deadline and a two-step call to action. The planner must read
 * what each number means and keep the speaker's stretches alive.
 */
class PlannerPromoTest {
    private val sentence = "یکی اومده تو دایرکت می‌گه بابا فلان صرافی بود، همون اولش می‌گفتی می‌رفتیم ثبت‌نام می‌کردیم، اسمش رو تو همون کلیپت به ما می‌گفتی، " +
        "ما می‌رفتیم ثبت‌نام می‌کردیم، دیگه نیازی نبود کامنت بذاریم و بریم دایرکت. نه عزیزم من اینجوری نیست، این کمپینو مخصوص پیج من فعال کردن برای هزار نفر اول. " +
        "الان هزار نفر اول توی دوازده ساعت دیروز تموم شد، دیروز یعنی من این رو پست کردم، یعنی پست گذاشتم، هزار نفر اول درجا پر شد، " +
        "یعنی توی کمتر از دوازده ساعت پنجاه هزار بازدید خورد و پنج هزار کامنت. و الان دو هزار تا دیگه ظرفیت باز شده، و چهل و هشت ساعت هم بیشتر نمونده از کمپینشون. " +
        "سیصد و پنجاه تتر سرمایه اولیه برای هر کاربر در نظر گرفتن، در قالب ووچر که می‌تونید همه‌تون دریافت بکنید. " +
        "کلمه تتر رو برام کامنت بکن، بعد برو دایرکتتو چک کن، همونجا همه توضیحاتو دادن برای دریافت سیصد و پنجاه تتر، " +
        "که خیلی راحت دریافت بکنی و ترید بکنی و سودتو برداشت بکنی. موفق باشید."

    private val words: List<Word> = run {
        var t = 300L
        sentence.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
            val stress = if (w.length > 5) 0.55f + 0.05f * (i % 5) else 0.2f
            val word = Word(w, TimeRange(t, t + 320), language = Language.Persian, emphasis = stress)
            t += if (w.endsWith('.')) 700 else 380
            word
        }
    }
    private val transcript = Transcript(Language.Persian, words)
    private val lines = Lines.split(words)
    private val u = RulesUnderstander.understand(transcript, "ریلز پرانرژی برای کمپین صرافی")
    private val seeds = 1L..6L
    private val plans = seeds.map { Planner().plan(words, lines, u, it) }

    @Test
    fun numbersAreReadWithTheirMeaning() {
        fun first(s: String) = NumberWords.findAll(s.split(' ')).first().value
        assertEquals(350.0, first("سیصد و پنجاه تتر"))
        assertEquals(350.0, first("سی سد و پنجا تتر"), "a split, misspelled three hundred and fifty")
        assertEquals(50_000.0, first("پنجاه هزار بازدید"))
        assertEquals(50_000.0, first("پنجا هزار بازید"))
        assertEquals(48.0, first("چهل و هشت ساعت"))
        assertEquals(48.0, first("چلا هشت ساتم"), "«چهل و» run together")
        assertEquals(2_000.0, first("دو هزار تا"))
        assertEquals(1_000.0, first("هزار نفر اول"))
        assertEquals(50_000.0, first("۵۰ هزار ویو"))
        assertNull(NumberWords.findAll("نه عزیزم".split(' ')).firstOrNull(), "«نه» alone is no")

        val voucher = Quantities.sense("سیصد و پنجاه تتر سرمایه اولیه".split(' '))
        assertEquals("voucher", voucher.richShow())
        assertEquals("Tether", voucher.voucher?.brand)
        assertEquals("تتر", voucher.voucher?.unit)
        val stats = Quantities.sense("پنجاه هزار بازدید خورد و پنج هزار کامنت".split(' '))
        assertEquals("stats", stats.richShow())
        assertEquals(listOf(50_000.0, 5_000.0), stats.social.map { it.value })
        val deadline = Quantities.sense("و چهل و هشت ساعت هم بیشتر نمونده از کمپینشون".split(' '))
        assertEquals("countdown", deadline.richShow())
        assertEquals(48.0, deadline.deadline?.value)
        assertNull(Quantities.sense("توی دوازده ساعت دیروز تموم شد".split(' ')).deadline, "time spent is not time left")
        val quota = Quantities.sense("هزار نفر اول درجا پر شد".split(' '))
        assertEquals("progress", quota.richShow())
        assertTrue(quota.quota!!.first)
        val opened = Quantities.read("دو هزار تا دیگه ظرفیت باز شده".split(' ')).single()
        assertEquals(Quantities.Kind.Capacity, opened.kind)
        assertTrue(opened.opened)
        assertEquals(Quantities.Kind.Money, Quantities.read("350 USDT".split(' ')).single().kind)
        assertEquals("Bitcoin", Quantities.coinOf("بیت‌کوین"))
        assertNull(Quantities.sense("قیمت بیت‌کوین ۶۸ هزار دلاره".split(' ')).voucher, "a price is not a voucher")
    }

    @Test
    fun rulesReadThePromo() {
        val shows = u.lines.map { it.show }
        assertTrue(listOf("voucher", "stats", "countdown", "progress").all { it in shows }, "rich shows from the numbers alone: $shows")
        assertEquals("تتر", u.cta?.keyword)
        assertEquals("comment", u.cta?.action)
        // The story's «کامنت بذاریم» and the stat «پنج هزار کامنت» are not the ask.
        val ctaLines = u.lines.withIndex().filter { it.value.show == "comment" }.map { it.index }
        assertTrue(ctaLines.all { k -> "کلمه" in words.textOf(lines[k].range) }, "only the real ask: ${ctaLines.map { words.textOf(lines[it].range) }}")
        assertEquals("crypto", u.domain)
        val hook = assertNotNull(u.hook)
        assertTrue("تتر" in words.textOf(lines[hook.line].range), "the money given is the payoff")
    }

    @Test
    fun everySeedShowsWhatTheNumbersMean() {
        for (p in plans) {
            val beats = p.score.scenes.flatMap { it.beats }
            val voucher = beats.firstOrNull { it.recipe == "voucher" && it.value == 350f }
            assertNotNull(voucher, "a 350 voucher: ${summary(beats)}")
            assertEquals("Tether", voucher.items.firstOrNull())
            assertEquals("تتر", voucher.suffix)
            assertTrue(beats.any { it.recipe == "countdown" && it.value == 48f && it.suffix == "ساعت" && it.label == "تا پایان کمپین" }, "48 hours left: ${summary(beats)}")
            assertTrue(beats.any { it.recipe == "stats" && it.points == listOf(50_000f, 5_000f) && it.items == listOf("بازدید", "کامنت") }, "views and comments: ${summary(beats)}")
            assertTrue(beats.any { it.recipe == "progress" && it.label == "۱۰۰۰ نفر اول" && it.value == 100f && it.text == "تکمیل" }, "the first 1000 filled: ${summary(beats)}")
            assertEquals(1, beats.count { it.recipe == "progress" }, "a quota said twice is shown once")
            assertEquals(1, beats.count { it.recipe == "voucher" && it.at != null }, "a voucher said twice is shown once (the hook may preview it)")
            val comment = beats.first { it.recipe == "comment" }
            assertEquals("تتر", comment.text)
            assertTrue(words[comment.at!!].text.startsWith("تتر"), "the field lands on the keyword")
            val dm = beats.firstOrNull { it.recipe == "stamp" && it.at != null && words[it.at!!].text.startsWith("دایرکت") && it.at!! > comment.at!! }
            assertNotNull(dm, "a DM step after the keyword: ${summary(beats)}\n${p.score.scenes.map { it.from }} ${lines.map { it.first }} ${p.notes}")
            assertTrue(beats.last { it.recipe == "comment" }.let { c -> words[c.at!!].range.startMs > words.last().range.startMs - 8000 }, "the ask owns the end")
        }
    }

    @Test
    fun noEmptyStretchesOverTheSpeaker() {
        for (p in plans) {
            val gaps = quietStretches(p.score)
            val scenes = p.score.scenes.joinToString("\n") { sc -> "scene ${sc.from} ${sc.time} ${sc.bg}: " + sc.beats.joinToString { "${it.recipe}@${it.at}/${it.hold}" } }
            assertTrue(gaps.isEmpty(), "seed ${p.choices.seed}: stretches over 2.6 s with nothing new: $gaps\n$scenes\n${p.notes}")
            val takeovers = p.score.scenes.withIndex().filter { it.value.bg != null }.map { it.index }
            assertTrue(takeovers.zipWithNext().none { (a, b) -> b == a + 1 }, "full-frame graphics never follow each other: $takeovers")
            val text = timeline(p.score).map { it.second }.filter { it in TEXT }
            assertTrue(text.windowed(3).none { it.toSet().size == 1 }, "a text treatment three times running: $text ${timeline(p.score)}\n${summary(p.score.scenes.flatMap { it.beats })}")
        }
    }

    @Test
    fun seedsDifferAndRepeat() {
        val encoded = plans.map { Score.encode(it.score) }
        assertTrue(encoded.toSet().size >= 5, "seeds should differ: ${encoded.toSet().size} distinct of ${encoded.size}")
        assertEquals(encoded[0], Score.encode(Planner().plan(words, lines, u, 1L).score), "same seed, same edit")
        val fills = plans.map { p -> timeline(p.score).joinToString { it.second } }
        assertTrue(fills.toSet().size >= 4, "the rhythm varies too: $fills")
    }

    @Test
    fun aSparseTasteFillsLess() {
        // Events: beats and cuts (a punch-in is a new scene).
        fun events(t: Taste) = Planner(t).plan(words, lines, u, 2L).score.scenes.let { sc -> sc.size + sc.sumOf { it.beats.size } }
        val dense = events(Taste(density = 1.5f))
        val sparse = events(Taste(density = 0.5f))
        assertTrue(sparse < dense, "density respected: sparse $sparse, dense $dense")
    }

    @Test
    fun moneyOpensOnItsVoucherMostOfTheTime() {
        val hooks = (1L..12L).map { Planner().plan(words, lines, u, it).score.scenes.first().beats.first { b -> b.time != null }.recipe }
        assertTrue(hooks.count { it == "voucher" } >= 6, "the voucher card is the default hook for money: $hooks")
        assertTrue(hooks.toSet().size >= 2, "counter or slam stay as seeded alternatives: $hooks")
    }

    @Test
    fun aColdOpenShowsTheFigureFirstAndOnce() {
        val open = 2.6f
        val shift = ((open + 0.25f) * 1000).toLong()
        val body = words.map { it.copy(range = TimeRange(it.range.startMs + shift, it.range.endMs + shift)) }
        val k = lines.indexOfFirst { l -> (l.first..l.last).any { words[it].text == "تتر" } }
        val opening = Planner.Opening(open, k, lines[k].first..lines[k].first + 3)
        for (seed in seeds) {
            val score = Planner().plan(body, lines, u, seed, opening = opening).score
            val first = score.scenes.first()
            assertEquals(0f, first.time, "the cold open is placed by time")
            assertTrue(first.from == null && first.bg == null, "over the speaker saying it")
            val voucher = first.beats.single()
            assertEquals("voucher", voucher.recipe)
            assertEquals(0f, voucher.time)
            assertTrue(voucher.hold!! > open, "past the restart")
            assertEquals(0, score.scenes[1].from, "the body starts at its first word")
            assertTrue(score.scenes[1].beats.none { it.time != null }, "no second hook at the body's start")
            val all = score.scenes.flatMap { it.beats }
            assertEquals(1, all.count { it.recipe == "voucher" }, "the figure is shown once: ${summary(all)}")
            assertTrue(all.none { it.recipe == "counter" && it.value == 350f }, "and gets no counter either")
        }
    }

    /**
     * The first automatic run on the benchmark video (the director model's own reading), when its
     * files are on this machine: the problems the supervisor found must not come back.
     */
    @Test
    fun theDirectorsReadingOfTheBenchmark() {
        val dir = File("/tmp/claude-0/-home-user-Trimio/7e03ed16-d9d4-5525-aa3f-2586cff127ac/scratchpad/v2/run")
        val heard = File(dir, "transcript.json").takeIf { it.isFile } ?: return
        val read = File(dir, "seed-1-understanding.json").takeIf { it.isFile } ?: return
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val raw = json.decodeFromString(ListSerializer(W.serializer()), heard.readText())
        val words = spelled(raw)
        assertEquals(164, words.size, "the run's spelling fixes")
        val lines = REAL_LINES.zipWithNext { a, b -> Lines.Line(a, b - 1) } + Lines.Line(REAL_LINES.last(), words.lastIndex)
        val u = Understander.sanitize(json.decodeFromString(Understanding.serializer(), read.readText()), words.size, lines.size)
        assertEquals("Tether", u.cta?.keyword, "the fixture: the model answered in English")
        for (seed in 1L..6L) {
            val plan = Planner().plan(words, lines, u, seed)
            val score = plan.score
            val beats = score.scenes.flatMap { it.beats }
            val sum = summary(beats, words)
            if (seed == 1L) println("benchmark seed 1: ${plan.notes}\n" + score.scenes.joinToString("\n") { sc -> "scene ${sc.from ?: sc.time} ${sc.bg ?: ""}" } + "\n$sum")
            sayTheSpokenKeyword(beats, words, sum)
            lastLongEnough(score, words, seed, sum)
            eachFigureOnce(beats, sum)
            popsCarryMeaning(beats, u, seed, sum)
            // 5. The big figures get the full frame; the progress bar stays over the speaker.
            val full = score.scenes.filter { it.bg != null }.flatMap { it.beats }.map { it.recipe }
            assertTrue("progress" !in full, "progress over footage: $full")
            assertTrue(full.count { it in setOf("voucher", "stats", "countdown") } >= 2, "the big figures take the frame: $full")
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 1. The keyword shown is the word said. */
    private fun sayTheSpokenKeyword(beats: List<BeatScore>, words: List<Word>, sum: String) {
        val comments = beats.filter { it.recipe == "comment" }
        assertTrue(comments.isNotEmpty() && comments.all { it.text == "تتر" }, "the comment shows «تتر»: $sum")
        assertEquals("تتر", words[comments.first().at!!].text, "and lands on it")
    }

    /** 2. Data graphics stay long enough to be read (as the compiler holds them); nothing flashes. */
    private fun lastLongEnough(score: Score, words: List<Word>, seed: Long, sum: String) {
        for ((k, sc) in score.scenes.withIndex()) {
            val end = score.scenes.getOrNull(k + 1)?.let { n -> n.time ?: (start(words, n.from!!) - 0.12f) } ?: (words.last().range.endMs / 1000f + 0.45f)
            for (b in sc.beats) {
                val a = b.time ?: b.at?.let { start(words, it) } ?: continue
                val life = minOf(a + (b.hold ?: 7f), end + 0.05f, a + 7f) - a
                val least = when {
                    b.recipe == "voucher" && b.time != null -> 4f
                    b.recipe in DATA -> 3.2f
                    else -> 1.5f
                }
                assertTrue(life >= least - 0.02f, "seed $seed: ${b.recipe} at ${b.at ?: b.time} lives ${life}s < $least\n$sum")
            }
        }
    }

    /** 3. Each figure once, in its richest form; no trend for views. */
    private fun eachFigureOnce(beats: List<BeatScore>, sum: String) {
        assertTrue(beats.none { it.recipe == "chart" }, "no chart for social stats: $sum")
        val stats = beats.single { it.recipe == "stats" }
        assertEquals(listOf(50_000f, 5_000f), stats.points, "views and comments on one board, across the pause")
        assertTrue(beats.any { it.recipe == "countdown" && it.value == 48f && it.label == "تا پایان کمپین" }, "48 hours left: $sum")
        assertEquals(1, beats.count { it.recipe == "voucher" && it.time == null }, "one voucher in the body: $sum")
        val shown = beats.filter { it.recipe in setOf("voucher", "countdown", "progress") }.mapNotNull { it.value } + stats.points!! + 1000f
        assertTrue(beats.none { it.recipe == "counter" && it.time == null && it.value in shown }, "no counter repeats a figure: $sum")
        assertEquals(1, beats.count { it.recipe == "progress" }, "the quota once: $sum")
    }

    /** 4. Pops carry meaning: a title, a noun phrase, or a name — never a lone weak word. */
    private fun popsCarryMeaning(beats: List<BeatScore>, u: Understanding, seed: Long, sum: String) {
        for (p in beats.filter { it.recipe in TEXT && it.time == null && it.hold == 1.6f }) {
            val t = p.text.orEmpty()
            assertTrue(' ' in t || u.entities.any { e -> p.at in e.at..e.until }, "seed $seed: a lone word popped: «$t»\n$sum")
            assertTrue(t !in setOf("کلیپت", "فعال", "دیروز", "دریافت"), "seed $seed: weak pop «$t»")
        }
    }

    @Serializable
    data class W(val w: String, val s: Float, val e: Float)

    /** The run's transcript after its spelling stage (the fixes its report lists), joined words included. */
    private fun spelled(raw: List<W>): List<Word> {
        val out = mutableListOf<Word>()
        var i = 0
        while (i < raw.size) {
            val joined = JOINS[raw[i].w to raw.getOrNull(i + 1)?.w]
            val (text, end) = if (joined != null) joined to raw[i + 1].e else (FIXES[raw[i].w] ?: raw[i].w) to raw[i].e
            out += Word(text, TimeRange((raw[i].s * 1000).toLong(), (end * 1000).toLong()), language = Language.Persian, emphasis = if (text.length > 4) 0.65f else 0.25f)
            i += if (joined != null) 2 else 1
        }
        return out
    }

    private fun start(words: List<Word>, i: Int) = words[i].range.startMs / 1000f

    private fun summary(beats: List<BeatScore>, words: List<Word>) = beats.joinToString("\n") { b ->
        "${b.recipe} at=${b.at}${b.at?.let { "(" + words[it].text + ")" } ?: ""} t=${b.time} hold=${b.hold} v=${b.value} label=${b.label} text=${b.text} items=${b.items} points=${b.points}"
    }

    private fun start(i: Int) = words[i].range.startMs / 1000f

    private fun beatStart(b: BeatScore, sceneFrom: Int, sceneLast: Int): Float = b.time ?: b.at?.let(::start)
        ?: b.text?.split(' ')?.firstOrNull()?.let { w -> (sceneFrom..sceneLast).firstOrNull { NumberWords.normalize(words[it].text).startsWith(NumberWords.normalize(w)) } }?.let(::start)
        ?: start(sceneFrom)

    private fun timeline(score: Score): List<Pair<Float, String>> = score.scenes.withIndex().flatMap { (k, sc) ->
        val last = (score.scenes.getOrNull(k + 1)?.from ?: words.size) - 1
        sc.beats.map { beatStart(it, sc.from ?: 0, last) to it.recipe }
    }.sortedBy { it.first }

    /**
     * Footage stretches longer than 2.6 s with no beat landing and no cut; the hook, a call to action,
     * a figure (counting, filling, ticking) or names landing one by one keep their own time while up.
     */
    private fun quietStretches(score: Score): List<String> {
        val out = mutableListOf<String>()
        for ((k, sc) in score.scenes.withIndex()) {
            if (sc.bg != null) continue
            val from = sc.from ?: 0
            val last = (score.scenes.getOrNull(k + 1)?.from ?: words.size) - 1
            val s = if (k == 0) 0f else start(from)
            val end = words[last].range.endMs / 1000f
            var t = s
            for (b in sc.beats.sortedBy { beatStart(it, from, last) }) {
                val a = beatStart(b, from, last)
                if (a - t > 2.6f) out += "${a - t}s at $t"
                val alive = when {
                    b.recipe in ALIVE || b.time != null -> a + (b.hold ?: 0f)
                    b.recipe == "logos" -> b.until?.let { words[it].range.endMs / 1000f } ?: a
                    else -> a
                }
                t = maxOf(t, a, alive)
            }
            if (end - t > 2.6f) out += "${end - t}s at $t (to the cut)"
        }
        return out
    }

    private fun summary(beats: List<BeatScore>) = beats.joinToString("\n") { b ->
        "${b.recipe} at=${b.at}${b.at?.let { "(" + words[it].text + ")" } ?: ""} v=${b.value} ${b.suffix ?: ""} label=${b.label} text=${b.text} items=${b.items} points=${b.points}"
    }

    private companion object {
        val TEXT = setOf("slam", "mask-rise", "type-on", "blur-in", "flip", "spread", "stack", "glitch")
        val ALIVE = setOf("comment", "countdown", "counter", "voucher", "stats", "progress")
        val DATA = setOf("counter", "voucher", "stats", "countdown", "progress", "chart", "meter")

        /** The run's 21 lines (its pause-aware split), by first word. */
        val REAL_LINES = listOf(0, 9, 15, 23, 36, 48, 52, 62, 69, 77, 87, 92, 96, 106, 109, 114, 121, 129, 140, 151, 158)
        val FIXES = mapOf(
            "دایرک" to "دایرکت", "سررافی" to "صرافی", "سبتنام" to "ثبت‌نام", "پنجا" to "پنجاه", "بازید" to "بازدید", "چلا" to "چهل", "ساتم" to "ساعتم",
            "میتونید" to "می‌تونید", "سرمایه" to "سرمایهٔ",
        )
        val JOINS = mapOf(("سی" to "سد") to "سیصد", ("همه" to "تون") to "همه‌تون")
    }
}
