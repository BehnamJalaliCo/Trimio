package io.trimio.engine.autopilot.explainer

import io.trimio.engine.autopilot.explainer.judges.FilmJury
import io.trimio.engine.motion.explainer.Beat
import io.trimio.engine.motion.explainer.BeatContent
import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class FilmJuryTest {
    private val n = Narrations.of(Narrations.FA_TECH, persian = true)
    private val jury = FilmJury()
    private val good: ExplainerFilm = FilmCheck(n).repair(RulesDirector().film(n, 1))

    private fun panel(f: ExplainerFilm, narration: Narration = n, others: List<io.trimio.engine.motion.explainer.ArtDirection> = emptyList()) =
        jury.deliberate(FilmJury.Context(f, narration, FilmCheck(narration), others))

    private fun ExplainerFilm.beats(change: (Int, Beat) -> Beat) = copy(chapters = chapters.map { c -> c.copy(beats = c.beats.mapIndexed(change)) })

    private fun catches(judge: String, film: ExplainerFilm, narration: Narration = n, others: List<io.trimio.engine.motion.explainer.ArtDirection> = emptyList()) {
        val p = panel(film, narration, others)
        println("$judge: ${p.summary()}")
        p.findings().filter { it.trim().startsWith(judge) }.take(4).forEach { println("  $it") }
        val v = p.of(judge)!!
        assertTrue(v.score < 10f, "$judge should catch it: ${p.summary()}")
        // The loop sends it back and the judge's own fixes bring it up.
        val outcome = runBlocking { jury.loop(film, { FilmJury.Context(it, narration, FilmCheck(narration), others) }, { null }) }
        println("  after the loop: ${outcome.panel.summary()}")
        assertTrue(outcome.panel.of(judge)!!.score > v.score || outcome.panel.unanimous, "$judge fixed: ${outcome.panel.summary()}")
    }

    @Test
    fun theRulesFilmPasses() {
        val p = panel(good)
        println(p.summary())
        assertTrue(p.min >= 9.5f, p.summary())
    }

    @Test
    fun catchesAPictureThatDoesNotFit() {
        // The tangle of «مسائل عمیق و پیچیده» drawn as a map with nothing of the sentence in it.
        val wrong = good.beats { _, b -> if (b.primitive == Primitive.Tangle) b.copy(primitive = Primitive.Map, content = BeatContent(text = "کوه")) else b }
        catches("metaphor", wrong)
    }

    @Test
    fun catchesAHardCutAndAStageThatNeverBuilds() {
        val cut = good.copy(chapters = good.chapters.map { c ->
            var k = 0
            c.copy(beats = c.beats.filter { it.op in FilmCheck.CREATES }.flatMap { b ->
                k++
                listOfNotNull(
                    Beat(b.at - 0.05, b.at, b.line, StageOp.Clear, "e${k - 1}").takeIf { k > 1 },
                    b.copy(op = StageOp.Add, element = "e$k", primitive = b.primitive ?: Primitive.Kicker),
                )
            })
        })
        catches("continuity", cut)
    }

    @Test
    fun catchesTheSameDeviceThreeTimesRunning() {
        var k = 0
        val same = good.beats { _, b -> if (b.op in FilmCheck.CREATES && k++ in 2..5) b.copy(primitive = Primitive.Chips) else b }
        catches("variety", same)
        // And the same look as a film already made of this narration.
        catches("variety", good, others = listOf(good.art.copy(seed = 99)))
    }

    @Test
    fun catchesABeatTooShortToRead() {
        val rushed = good.copy(chapters = good.chapters.mapIndexed { i, c ->
            if (i != 1) c else c.copy(beats = c.beats.mapIndexed { j, b -> if (j == 1) b.copy(at = c.beats[0].at + 0.05) else b })
        })
        catches("timing", FilmCheck(n).repair(rushed))
    }

    @Test
    fun catchesEnglishOnAPersianFilm() {
        val english = good.beats { i, b -> if (i == 0 && b.op == StageOp.Add) b.copy(content = b.content.copy(text = "best model ever")) else b }
        catches("spelling", english)
    }

    @Test
    fun catchesCaptionsTheBriefDidNotWant() {
        val quiet = Narrations.of(Narrations.FA_TECH, persian = true, prompt = "بدون زیرنویس، تم تیره")
        catches("brief", good, quiet)
    }

    @Test
    fun screenwriterKeepsTheBestPanel() = runBlocking {
        val r = Screenwriter().write(n, 7)
        r.report.takeLast(8).forEach(::println)
        assertTrue(r.panel!!.min >= 9.5f, r.panel!!.summary())
    }
}
