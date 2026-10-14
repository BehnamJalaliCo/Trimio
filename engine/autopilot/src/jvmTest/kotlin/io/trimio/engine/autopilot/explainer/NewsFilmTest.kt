package io.trimio.engine.autopilot.explainer

import io.trimio.engine.autopilot.explainer.judges.FilmJury
import io.trimio.engine.motion.explainer.Beat
import io.trimio.engine.motion.explainer.BeatContent
import io.trimio.engine.motion.explainer.FilmGrammar
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewsFilmTest {
    private val fa = Narrations.of(Narrations.FA_NEWS, persian = true)
    private val en = Narrations.of(Narrations.EN_NEWS, persian = false)

    @Test
    fun storiesAreNewsAndConceptsAreDiagrams() {
        assertEquals(FilmGrammar.IllustratedNews, Grammars.choose(fa), "${Grammars.scores(fa)}")
        assertEquals(FilmGrammar.IllustratedNews, Grammars.choose(en), "${Grammars.scores(en)}")
        for ((key, pair) in Narrations.ALL) {
            val n = Narrations.of(pair.first, pair.second)
            assertEquals(FilmGrammar.DiagramChapters, Grammars.choose(n), "$key ${Grammars.scores(n)}")
        }
        // The brief's own word wins.
        assertEquals(FilmGrammar.IllustratedNews, Grammars.choose(Narrations.of(Narrations.FA_TECH, true, "یه ویدیوی خبری بساز")))
        assertEquals(FilmGrammar.DiagramChapters, Grammars.choose(Narrations.of(Narrations.FA_NEWS, true, "دیاگرام بساز")))
    }

    /** A short sentence is its whole headline: never «… پولشان را پس» without the verb that ends it. */
    @Test
    fun shortSentencesAreWholeHeadlines() {
        val n = Narrations.of(
            "صرافی بزرگ دیروز برداشت همه‌ی کاربران را متوقف کرد. هزاران نفر هنوز پولشان را پس نگرفته‌اند، و جلوی دفتر صرافی صف کشیده‌اند.",
            persian = true,
        )
        val writer = NewsWriter()
        val heads = writer.scenes(n).map { writer.headline(n, it) }
        heads.forEach { println("headline «${it.main}» / «${it.second}»") }
        assertEquals("صرافی بزرگ دیروز برداشت همه‌ی کاربران را متوقف کرد", heads[0].main)
        assertTrue(heads[1].main.endsWith("نگرفته‌اند"), heads[1].main)
        // «و» joins two nouns and stays; it never opens or closes a line.
        val joined = Narrations.of("کارشناسان می‌گویند طلا و دلار امن‌ترند.", persian = true)
        assertEquals("کارشناسان می‌گویند طلا و دلار امن‌ترند", writer.headline(joined, writer.scenes(joined).first()).main)
        assertTrue(heads.none { it.main.startsWith("و ") || it.main.endsWith(" و") }, "$heads")
        // Slower speech (the production proof's timing): a sentence split for length and joined back stays whole.
        var t = 0.3
        val slow = n.words.map { w ->
            val len = 0.22 + w.text.length * 0.045 + (if (w.text.endsWith('.') || w.text.endsWith('،')) 0.35 else 0.0)
            w.copy(range = io.trimio.core.model.time.TimeRange((t * 1000).toLong(), ((t + len - 0.06) * 1000).toLong())).also { t += len }
        }
        val m = Narration.of(slow, "", lexicon = Narrations.lexicon)
        val slowHeads = writer.scenes(m).map { writer.headline(m, it) }
        slowHeads.forEach { println("slow headline «${it.main}» / «${it.second}»") }
        assertTrue(slowHeads.none { h -> h.main.endsWith(" را") || h.main.endsWith(" پس") || h.main.endsWith("کاربران") }, "$slowHeads")
    }

    @Test
    fun aSceneIsAHeadlineAHeroAndOneDevice() {
        for (n in listOf(fa, en)) {
            val film = FilmCheck(n).repair(RulesDirector().film(n, 1))
            Beats.table(film).forEach(::println)
            assertEquals(FilmGrammar.IllustratedNews.id, film.art.grammar)
            assertTrue(film.art.accent.startsWith("#") && film.art.dark.startsWith("#"))
            assertEquals(emptyList(), FilmCheck(n).problems(film))
            for (ch in film.chapters) {
                assertTrue(ch.end - ch.start <= 6.5, "a scene of ${ch.end - ch.start}s")
                assertTrue(ch.beats.any { it.primitive == Primitive.Headline }, "headline in ${ch.title}")
                assertTrue(ch.beats.any { it.primitive == Primitive.Hero }, "hero in ${ch.title}: ${ch.beats.map { it.primitive }}")
                assertTrue(ch.beats.count { it.op == StageOp.Add && it.primitive in setOf(Primitive.Bubble, Primitive.Annotation, Primitive.Calendar, Primitive.Stat) } <= 2)
            }
            assertTrue(film.chapters.drop(1).zipWithNext().all { (a, b) -> a.transition != b.transition }, "transitions vary")
            val beats = film.chapters.flatMap { it.beats }
            assertTrue(beats.any { it.content.punch.isNotBlank() }, "a punchline on the dark box")
            assertTrue(beats.any { it.primitive == Primitive.Calendar && it.content.value == 3.0 }, "three business days count down")
            // Nothing still for more than two and a half seconds.
            film.chapters.forEach { ch -> assertTrue((listOf(ch.start) + ch.beats.map { it.at } + ch.end).sorted().zipWithNext().all { (a, b) -> b - a <= 2.5 }, "still in ${ch.title}") }
        }
    }

    @Test
    fun illustrationsShowWhoTheSentenceIsAbout() {
        val film = RulesDirector().film(fa, 1)
        val heroes = film.chapters.map { ch -> ch.beats.firstOrNull { it.primitive == Primitive.Hero }?.content?.illustration?.let(IllustrationRequest::parse) }
        println(heroes)
        assertTrue(heroes[0]!!.query.contains("gold"), "the gold shop's scene: ${heroes[0]}")
        assertTrue(heroes.any { it != null && it.subject.contains("man") && "smartphone" in it.objects }, "a worried user with a phone")
        // The sign carries the name only because it is said («نیلوفر»), never a company nobody named.
        assertTrue(heroes.filterNotNull().all { h -> h.sign.isEmpty() || h.sign.split(' ').all { w -> fa.keys.contains(Metaphors.bare(w)) } })
        val r = IllustrationRequest("safe", "standing", listOf("gold bars"), "میلی", "pencil sketch")
        assertEquals(r, IllustrationRequest.parse(r.encode()))
        // A queue in front of an office is its own picture, not the scene before it again.
        val q = Narrations.of("و جلوی دفتر صرافی صف کشیده‌اند.", persian = true)
        val queue = Illustrations.request(q, q.words.indices, "")!!
        assertEquals("group of people", queue.subject)
        assertTrue("queue" in queue.action && "currency exchange office" in queue.objects, "$queue")
    }

    @Test
    fun theNewsFilmPassesTheJuryAndTheJuryCatchesAStillScene() = runBlocking {
        val r = Screenwriter().write(fa, 2)
        println(r.panel!!.summary())
        r.panel!!.findings().take(8).forEach(::println)
        assertTrue(r.panel!!.min >= 9.5f, r.panel!!.summary())
        // A scene left still for four seconds, with a headline that says something else and a hero of the wrong thing.
        val good = FilmCheck(fa).repair(RulesDirector().film(fa, 2))
        val bad = good.copy(chapters = good.chapters.mapIndexed { i, ch ->
            if (i != 1) ch else ch.copy(end = ch.end, beats = listOf(
                Beat(ch.start + 0.1, ch.end, ch.beats.first().line, StageOp.Add, "h", Primitive.Headline, BeatContent(text = "هوای امروز")),
                Beat(ch.start + 0.2, ch.end, ch.beats.first().line, StageOp.Add, "p", Primitive.Hero, BeatContent(illustration = IllustrationRequest("mountain").encode())),
            ))
        })
        val panel = FilmJury().deliberate(FilmJury.Context(bad, fa))
        println(panel.summary())
        panel.findings().filter { "pace" in it }.forEach(::println)
        assertTrue(panel.of("pace")!!.score <= 8f, panel.summary())
        val fixed = FilmJury().loop(bad, { FilmJury.Context(it, fa) }, { null })
        println("after: ${fixed.panel.summary()}")
        assertTrue(fixed.panel.of("pace")!!.score > panel.of("pace")!!.score)
    }
}
