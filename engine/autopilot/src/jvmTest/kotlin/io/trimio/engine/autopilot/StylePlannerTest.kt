package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.style.StyleBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 28 styles reach the director the way the app's briefs do (a style word or name, Persian or
 * English; a plain look word; or nothing, when the domain and mood decide), and they change the
 * planner's choices through their language, not through code per style.
 */
class StylePlannerTest {
    private val sentence = "امروز می‌خوام سه تا نکته مهم درباره این محصول بگم. اول کیفیت ساخت که واقعا عالیه. دوم قیمتش که از همه رقبا ارزون‌تره. " +
        "سوم اینکه پنجاه درصد تخفیف داره فقط تا آخر هفته. اینو از دست ندید چون دیگه تکرار نمی‌شه. کلمه تخفیف رو کامنت کن تا لینکش رو بفرستم. " +
        "راستش من خودم شش ماهه ازش استفاده می‌کنم و هر روز بیشتر دوستش دارم. نظرتون رو برام بنویسید."

    private val words: List<Word> = run {
        var t = 300L
        sentence.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
            val word = Word(w, TimeRange(t, t + 320), language = Language.Persian, emphasis = if (w.length > 5) 0.5f + 0.05f * (i % 6) else 0.2f)
            t += if (w.endsWith('.')) 700 else 380
            word
        }
    }
    private val transcript = Transcript(Language.Persian, words)
    private val lines = Lines.split(words)

    private fun plan(brief: String, seed: Long, domain: String? = null, mood: String? = null): Planner.Plan {
        val u = RulesUnderstander.understand(transcript, brief).let { u -> u.copy(domain = domain ?: u.domain, mood = mood ?: u.mood) }
        return Planner(brief = brief).plan(words, lines, u, seed)
    }

    @Test
    fun theBriefsStyleWordsPickTheStyle() {
        assertEquals("retro-vhs", plan("یه ریلز با حس رترو و نوستالژی بساز", 1).score.look)
        assertEquals("neon-cyber", plan("make it neon cyberpunk", 2).score.look)
        assertEquals("education-chalk", plan("سبک تخته گچی برای کلاس", 3).score.look)
        assertEquals("news-broadcast", plan("سبک خبری و جدی", 4).score.look)
        // The picker's style (an earlier pack id) asks for its language.
        assertEquals("luxury-gold", plan("style: chrome-liquid-metal", 5).score.look)
        // Every style by its own names, in both languages, whatever the seed.
        for (s in StyleBook.all) for ((i, name) in listOf(s.nameFa, s.nameEn).withIndex()) {
            assertEquals(s.id, plan("سبک: $name", 7L + i).score.look, "«$name» asks for ${s.id}")
        }
    }

    @Test
    fun aPlainLookWordIsAnsweredByAnyStyleOfThatTone() {
        val dark = (1L..12L).map { plan("یه ریلز تیره و جدی", it, domain = "finance").score.look }.toSet()
        assertTrue(dark.all { StyleBook.of(it)!!.answers("noir") }, "a dark brief stays dark: $dark")
        assertTrue(dark.size >= 2, "more than one dark style answers it: $dark")
    }

    @Test
    fun domainAndMoodPickAmongFittingStyles() {
        for ((domain, mood) in listOf("food" to "playful", "crypto" to "energetic", "beauty" to "calm", "education" to "inspiring", "real-estate" to "luxury")) {
            val picked = (1L..24L).map { plan("یه ریلز جذاب بساز", it, domain, mood).score.look }
            assertTrue(picked.all { (StyleBook.of(it)!!.fit.domains[domain] ?: 0f) >= 0.5f }, "$domain: every pick fits ($picked)")
            assertTrue(picked.toSet().size >= 2, "$domain: the seed varies the style (${picked.toSet()})")
        }
    }

    @Test
    fun theLanguageTiltsThePlannersChoices() {
        fun recipes(brief: String) = (1L..16L).flatMap { seed -> plan(brief, seed).score.scenes.flatMap { sc -> sc.beats.map { it.recipe } } }
        fun share(all: List<String>, vararg r: String) = all.count { it in r } / all.size.coerceAtLeast(1).toFloat()
        val calm = recipes("سبک: آرامش و سلامت")
        val loud = recipes("سبک: اسپرت پرانرژی")
        assertTrue(share(calm, "blur-in") > share(loud, "blur-in"), "calm wellness eases in more")
        assertTrue(share(loud, "slam", "stack") > share(calm, "slam", "stack"), "sports energy slams more")
        val words = { brief: String -> (1L..16L).map { plan(brief, it).score.captions.maxWords }.average() }
        assertTrue(words("سبک: استریت هایپ") < words("سبک: استودیو پادکست"), "street captions are shorter than a podcast's")
        val accents = (1L..16L).map { plan("سبک: نئون سایبر", it).score.accent }.toSet()
        assertTrue(accents.all { it in StyleBook.of("neon-cyber")!!.accents }, "accents come from the style's own set: $accents")
    }

    @Test
    fun briefWordsAgreeWithThePlainLookReading() {
        // A style word must not contradict the rules' look reading: «لوکس» is calm luxury (lumen), so a style it names answers lumen.
        for (s in StyleBook.all) for (w in s.words + s.nameFa + s.nameEn) {
            val tone = RulesUnderstander.brief(w).look
            assertTrue(tone == "auto" || s.answers(tone), "${s.id}: «$w» reads as $tone, which the style does not answer")
        }
    }
}
