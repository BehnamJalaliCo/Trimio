package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Score
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
            assertNotNull(dm, "a DM step after the keyword: ${summary(beats)}")
            assertTrue(beats.last { it.recipe == "comment" }.let { c -> words[c.at!!].range.startMs > words.last().range.startMs - 8000 }, "the ask owns the end")
        }
    }

    @Test
    fun noEmptyStretchesOverTheSpeaker() {
        for (p in plans) {
            val gaps = quietStretches(p.score)
            assertTrue(gaps.isEmpty(), "seed ${p.choices.seed}: stretches over 2.6 s with nothing new: $gaps\n${summary(p.score.scenes.flatMap { it.beats })}")
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
        val dense = Planner(Taste(density = 1.5f)).plan(words, lines, u, 2L).score.scenes.sumOf { it.beats.size }
        val sparse = Planner(Taste(density = 0.5f)).plan(words, lines, u, 2L).score.scenes.sumOf { it.beats.size }
        assertTrue(sparse < dense, "density respected: sparse $sparse, dense $dense")
    }

    // ---------------------------------------------------------------- helpers

    private fun start(i: Int) = words[i].range.startMs / 1000f

    private fun beatStart(b: BeatScore, sceneFrom: Int, sceneLast: Int): Float = b.time ?: b.at?.let(::start)
        ?: b.text?.split(' ')?.firstOrNull()?.let { w -> (sceneFrom..sceneLast).firstOrNull { NumberWords.normalize(words[it].text).startsWith(NumberWords.normalize(w)) } }?.let(::start)
        ?: start(sceneFrom)

    private fun timeline(score: Score): List<Pair<Float, String>> = score.scenes.withIndex().flatMap { (k, sc) ->
        val last = (score.scenes.getOrNull(k + 1)?.from ?: words.size) - 1
        sc.beats.map { beatStart(it, sc.from ?: 0, last) to it.recipe }
    }.sortedBy { it.first }

    /**
     * Footage stretches longer than 2.6 s with no beat landing and no cut; the hook, a call to action
     * or names landing one by one keep their own time while they are up.
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
                    b.recipe == "comment" || b.time != null -> a + (b.hold ?: 0f)
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
    }
}
