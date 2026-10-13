package io.trimio.engine.autopilot.explainer

import io.trimio.engine.motion.explainer.ArtDirection
import io.trimio.engine.motion.explainer.Beat
import io.trimio.engine.motion.explainer.BeatContent
import io.trimio.engine.motion.explainer.Chapter
import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.explainer.Item
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilmCheckTest {
    private val n = Narrations.of(Narrations.FA_TECH, persian = true)
    private val check = FilmCheck(n)

    /** A film with one of every mistake a director makes. */
    private fun broken(): ExplainerFilm {
        val line = n.clauses[3].text
        val beats = listOf(
            Beat(9.0, 8.0, line, StageOp.Check, "tiles", content = BeatContent(picks = listOf(0))),
            Beat(10.0, 11.0, line, StageOp.Add, "chips", Primitive.Chips, BeatContent(items = listOf(Item("Google"), Item("نوشتن")))),
            Beat(10.5, 11.0, line, StageOp.Fill, "chips", content = BeatContent(value = 0.5)),
            Beat(11.0, 12.0, line, StageOp.Add, "meter", Primitive.Meter, BeatContent(text = "usage limit", value = 95.0, unit = "%")),
            Beat(11.2, 12.0, line, StageOp.Add, "a", Primitive.Kicker, BeatContent(text = "یک دو سه چهار پنج شش هفت هشت نه ده")),
            Beat(11.4, 12.0, line, StageOp.Add, "b", Primitive.Icon, BeatContent(text = "قیمت 98", visual = "Google logo")),
            Beat(11.6, 12.0, line, StageOp.Add, "c", Primitive.Badge, BeatContent(text = "تست")),
            Beat(11.8, 12.0, line, StageOp.Add, "chips", Primitive.Chips, BeatContent(items = listOf(Item("خلاق")))),
            Beat(30.0, 80.0, line, StageOp.Add, "late", Primitive.BigWord, BeatContent(text = "")),
        )
        return ExplainerFilm(
            ArtDirection("paper-warm", "header-top", "dashes", "band-pill", 1),
            listOf(Chapter("Haiku Model Number Two", 5.0, 40.0, 1, beats), Chapter("", 30.0, 90.0, 1, emptyList())),
            language = "en",
        )
    }

    @Test
    fun findsEveryKindOfMistake() {
        val problems = check.problems(broken())
        problems.forEach { println("${it.kind} ${it.at}: ${it.detail}") }
        val kinds = problems.map { it.kind }.toSet()
        for (k in listOf(
            FilmCheck.Kind.Time, FilmCheck.Kind.Reference, FilmCheck.Kind.Op, FilmCheck.Kind.Crowded, FilmCheck.Kind.Long, FilmCheck.Kind.Script,
            FilmCheck.Kind.Brand, FilmCheck.Kind.Heading, FilmCheck.Kind.Accent, FilmCheck.Kind.Empty, FilmCheck.Kind.Value,
        )) {
            assertTrue(k in kinds, "found $k: $kinds")
        }
    }

    @Test
    fun repairLeavesNothingWrong() {
        val log = mutableListOf<String>()
        val fixed = check.repair(broken()) { log += it }
        log.forEach(::println)
        Beats.table(fixed).forEach(::println)
        assertEquals(emptyList(), check.problems(fixed))
        assertEquals("fa", fixed.language)
        // The meter's 95 is read as 95%, names nobody said are gone, digits are Persian.
        val beats = fixed.chapters.flatMap { it.beats }
        assertEquals(0.95, beats.first { it.primitive == Primitive.Meter }.content.value)
        assertTrue(beats.none { b -> b.content.items.any { it.label == "Google" } || "Google" in b.content.visual.lowercase() })
        assertTrue(beats.any { "۹۸" in it.content.text })
        assertTrue(fixed.chapters.zipWithNext().all { (a, b) -> a.end == b.start && a.accent != b.accent })
        assertEquals(n.duration, fixed.chapters.last().end, 0.01)
    }

    @Test
    fun rulesAndRepairAgree() {
        for ((key, pair) in Narrations.ALL) {
            val narration = Narrations.of(pair.first, pair.second)
            val c = FilmCheck(narration)
            for (seed in 1L..4L) {
                val film = c.repair(RulesDirector().film(narration, seed))
                assertEquals(emptyList(), c.problems(film), "$key seed $seed")
                // Repair is stable: a sound film comes back unchanged.
                assertEquals(film, c.repair(film), "$key seed $seed")
            }
        }
    }
}
