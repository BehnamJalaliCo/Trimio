package io.trimio.engine.autopilot.explainer

import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenwriterTest {

    private fun film(key: String, seed: Long = 1, prompt: String = ""): Pair<Narration, ExplainerFilm> {
        val (script, persian) = Narrations.ALL.toMap().getValue(key)
        val n = Narrations.of(script, persian, prompt)
        return n to FilmCheck(n).repair(RulesDirector().film(n, seed))
    }

    private fun made(f: ExplainerFilm) = f.chapters.flatMap { c -> c.beats.filter { it.op in FilmCheck.CREATES }.mapNotNull { it.primitive } }

    @Test
    fun printsEveryFilm() {
        for ((key, _) in Narrations.ALL) {
            val (_, f) = film(key)
            println("=== $key")
            Beats.table(f).forEach(::println)
        }
    }

    @Test
    fun chaptersFollowTheSubjects() {
        val (_, tech) = film("fa-tech")
        val titles = tech.chapters.map { it.title }
        println("fa-tech chapters: $titles")
        assertTrue(listOf("Haiku", "Sonnet", "Opus", "Fable").all { name -> titles.any { it.contains(name) } }, "a chapter per model: $titles")
        val (_, howto) = film("en-howto")
        assertTrue(howto.chapters.size >= 3, "steps make chapters: ${howto.chapters.map { it.title }}")
        val (_, health) = film("fa-health")
        assertTrue(health.chapters.size >= 3, "«اول، دوم، سوم» make chapters: ${health.chapters.map { it.title }}")
        for ((key, _) in Narrations.ALL) {
            val (n, f) = film(key)
            assertEquals(0.0, f.chapters.first().start, key)
            assertEquals(n.duration, f.chapters.last().end, 0.01, key)
            f.chapters.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start, 0.01, "$key: chapters end to end") }
        }
    }

    @Test
    fun metaphorsFitTheSentences() {
        val (_, f) = film("fa-tech")
        val beats = f.chapters.flatMap { it.beats }
        fun pictured(word: String, vararg pictures: Primitive) =
            assertTrue(beats.any { b -> word in b.line && (b.primitive in pictures) }, "«$word» → ${pictures.toList()}: ${beats.filter { word in it.line }.map { "${it.op} ${it.primitive}" }}")
        pictured("کارآموز", Primitive.Profile)
        pictured("تکراری", Primitive.TaskTiles, Primitive.Checklist)
        pictured("محدودیت", Primitive.Meter, Primitive.Ring)
        pictured("روزمره", Primitive.IconTiles, Primitive.Grid)
        pictured("بهترین", Primitive.Branches, Primitive.Flow)
        pictured("پیچیده", Primitive.Tangle)
        pictured("قوی‌ترین", Primitive.Bars, Primitive.Ladder)
        pictured("فقط", Primitive.Blocks, Primitive.Dots)
        // The meter drains on «کم میشه» / «زودتر تموم میشه», after it is put on stage.
        assertTrue(beats.any { it.op == StageOp.Fill }, "a meter that drains")
        // Tiles are ticked; the best path lit.
        assertTrue(beats.any { it.op == StageOp.Check }, "something is ticked")
        val (_, coffee) = film("en-howto")
        assertTrue(made(coffee).any { it in setOf(Primitive.Steps, Primitive.Flow, Primitive.Timeline, Primitive.Stat) }, "a how-to has steps or figures: ${made(coffee)}")
        val (_, money) = film("en-finance")
        assertTrue(made(money).any { it in setOf(Primitive.Bars, Primitive.Stack, Primitive.Meter, Primitive.Ring) }, "savings grow: ${made(money)}")
        val (_, history) = film("en-history")
        assertTrue(made(history).any { it in setOf(Primitive.Timeline, Primitive.Stat, Primitive.Dots) }, "history has dates and counts: ${made(history)}")
    }

    @Test
    fun stagesBuildInsteadOfCutting() {
        for ((key, _) in Narrations.ALL) {
            val (n, f) = film(key)
            assertTrue(FilmCheck(n).problems(f).none { it.kind in setOf(FilmCheck.Kind.Reference, FilmCheck.Kind.Time, FilmCheck.Kind.Crowded, FilmCheck.Kind.Op) }, "$key: ${FilmCheck(n).problems(f)}")
            val reuse = f.chapters.flatMap { it.beats }.count { it.op !in setOf(StageOp.Add, StageOp.Clear) }
            assertTrue(reuse > 0, "$key: elements are worked on")
            // Every sentence gets a picture.
            val lines = f.chapters.flatMap { c -> c.beats.map { it.line } }.toSet()
            val bare = n.clauses.filter { it.text !in lines }
            assertTrue(bare.size * 5 <= n.clauses.size, "$key: sentences without a beat: ${bare.map { it.text }}")
        }
    }

    @Test
    fun seedsGiveDifferentFilms() {
        val seeds = (1L..8L)
        val films = seeds.map { film("fa-tech", it).second }
        val palettes = films.map { it.art.palette }.distinct()
        val layouts = films.map { it.art.layout }.distinct()
        val looks = films.map { it.art.copy(seed = 0) }.distinct()
        val primitives = films.flatMap(::made).distinct()
        println("8 seeds: ${palettes.size} palettes $palettes, ${layouts.size} layouts, ${looks.size} looks, ${primitives.size} pictures $primitives")
        val perFilm = films.map { made(it).distinct().size }
        println("pictures per film: $perFilm")
        assertTrue(palettes.size >= 4, "palettes $palettes")
        assertTrue(layouts.size >= 3, "layouts $layouts")
        assertTrue(looks.size >= 6, "looks")
        assertTrue(primitives.size >= 10, "pictures across seeds $primitives")
        assertTrue(perFilm.all { it >= 7 }, "pictures per film $perFilm")
        // Picture choices themselves move with the seed.
        val sequences = films.map { made(it) }.distinct()
        assertTrue(sequences.size >= 4, "${sequences.size} distinct picture sequences")
        // No device three times in a row.
        films.forEach { f -> assertTrue(made(f).windowed(3).none { w -> w.all { it == w[0] } }, "a device three times running: ${made(f)}") }
    }

    @Test
    fun captionsAreShortChunksOnTheWords() {
        for ((key, _) in Narrations.ALL) {
            val (n, f) = film(key)
            assertTrue(f.captions.isNotEmpty(), key)
            f.captions.forEach { c ->
                val count = c.text.split(' ').size
                assertTrue(count in 2..5, "$key: «${c.text}»")
                assertTrue(c.end > c.start, key)
            }
            assertEquals(n.words.size, f.captions.sumOf { it.text.split(' ').size }, "$key: every word captioned once")
            f.captions.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start + 1e-6, "$key: chunks in order") }
        }
        val (_, quiet) = film("fa-health", prompt = "بدون زیرنویس")
        assertTrue(quiet.captions.isEmpty() && quiet.art.captionStyle == ArtDirector.NO_CAPTIONS, "no captions when the brief says so")
    }

    @Test
    fun persianFilmsWritePersian() {
        for (key in listOf("fa-tech", "fa-health", "fa-finance")) {
            val (n, f) = film(key)
            val texts = f.chapters.flatMap { c -> listOf(c.title) + c.beats.flatMap { b -> listOf(b.content.text, b.content.caption) + b.content.items.map { it.label } } }
            val latin = texts.flatMap { it.split(' ') }.map(FilmCheck::core).filter { FilmCheck.latin(it) }
            assertTrue(latin.all { it.lowercase() in n.spokenLatin }, "$key: only names said in Latin: $latin")
            assertTrue(texts.none { s -> s.any { it in '0'..'9' } }, "$key: Persian digits")
        }
    }

    @Test
    fun screenwriterPassesTheJury() = runBlocking {
        for ((key, pair) in Narrations.ALL) {
            val n = Narrations.of(pair.first, pair.second)
            val r = Screenwriter().write(n, 3)
            println("$key: ${r.panel?.summary()}")
            r.panel!!.findings().take(6).forEach { println("  $it") }
            assertTrue(r.panel!!.min >= 8f, "$key: ${r.panel!!.summary()}")
        }
    }
}
