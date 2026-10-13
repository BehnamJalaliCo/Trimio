package io.trimio.engine.autopilot.explainer

import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.JsonSchemaGrammar
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.StopReason
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmDirectorTest {

    /** A model that answers with whatever [reply] writes for the request it gets, and remembers the requests. */
    private class FakeModel(override val isLocal: Boolean, val reply: (GenerationRequest) -> String) : LanguageModel {
        override val id = if (isLocal) "fake-local" else "fake-cloud"
        val asked = mutableListOf<GenerationRequest>()
        override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
            asked += request
            val text = reply(request)
            onText(text)
            return Generation(text, StopReason.EndTurn, id)
        }
    }

    private val n = Narrations.of(Narrations.FA_TECH, persian = true)

    /** Clause numbers the user message lists ("c12 [...]"). */
    private fun clauses(request: GenerationRequest): List<Int> =
        Regex("(?m)^c(\\d+) \\[").findAll(request.messages.last().text).map { it.groupValues[1].toInt() }.toList()

    /** A beat as a model writes it, landing on the first word of its clause. */
    private fun beat(k: Int, primitive: String, op: String, element: String, text: String = "", items: String = "[]", value: Double = -1.0, picks: String = "[]") =
        """{"clause":$k,"metaphor":"m","primitive":"$primitive","op":"$op","element":"$element","cue":"${n.texts[n.clauses[k].first]}","text":"$text","caption":"",""" +
            """"items":$items,"visuals":[],"values":[],"value":$value,"unit":"","picks":$picks,"visual":""}"""

    private fun scene(ks: List<Int>, title: String): String {
        val beats = ks.mapIndexed { j, k ->
            if (j == 0) beat(k, "task-tiles", "add", "tiles", "کارهای سبک", items = """["","",""]""") else beat(k, "none", "check", "tiles", picks = "[0,1,2]")
        }
        return """{"title":"$title","beats":[${beats.joinToString(",")}]}"""
    }

    @Test
    fun localModelWritesChapterByChapter() = runBlocking {
        val plans = Chapters.find(n)
        val model = FakeModel(isLocal = true) { r ->
            when {
                r.system == DirectorPrompts.OUTLINE -> """{"chapters":[${plans.joinToString(",") { """{"first":${it.clauses.first},"title":"${it.title}"}""" }}]}"""
                else -> scene(clauses(r), "عنوان")
            }
        }
        val log = mutableListOf<String>()
        val film = FilmCheck(n).repair(LlmDirector(model).direct(n, 5) { log += it })
        log.forEach(::println)
        Beats.table(film).forEach(::println)
        // One outline call, then one per chapter, each constrained by a schema the grammar compiles.
        assertEquals(1 + plans.size, model.asked.size)
        model.asked.forEach { r -> assertTrue(JsonSchemaGrammar.compile(r.schema!!).contains("root ::=")) }
        assertEquals(plans.size, film.chapters.size)
        assertTrue(film.chapters.all { it.title == "عنوان" })
        // Elements stay inside their chapter; checks land after the tiles are added.
        val first = film.chapters.first().beats
        assertTrue(first.first().op == StageOp.Add && first.first().primitive == Primitive.TaskTiles)
        assertTrue(first.first().element.startsWith("c1-"))
        assertTrue(film.chapters.flatMap { it.beats }.any { it.op == StageOp.Check })
        // Each beat sits on its cue word.
        val c0 = n.clauses[plans[1].clauses.first]
        assertTrue(film.chapters[1].beats.first().at in c0.start - 0.2..c0.end)
        assertEquals(emptyList(), FilmCheck(n).problems(film))
        // The prompt teaches the metaphor language and the screen's language.
        val prompt = model.asked.last()
        assertTrue("intern" in prompt.system && "tangle" in prompt.system && "Persian" in prompt.messages.last().text)
    }

    @Test
    fun cloudModelWritesTheWholeFilmInOneCall() = runBlocking {
        val plans = Chapters.find(n)
        val model = FakeModel(isLocal = false) { _ ->
            val chapters = plans.mapIndexed { i, p ->
                val ks = p.clauses.toList()
                val beats = ks.map { k -> beat(k, if (k == ks.first()) "meter" else "none", if (k == ks.first()) "add" else "fill", "m", "محدودیت مصرف", value = 1.0 - 0.1 * (k - ks.first())) }
                """{"title":"فصل ${i + 1}","first":${p.clauses.first},"last":${p.clauses.last},"beats":[${beats.joinToString(",")}]}"""
            }
            """{"chapters":[${chapters.joinToString(",")}]}"""
        }
        val film = FilmCheck(n).repair(LlmDirector(model).direct(n, 2))
        assertEquals(1, model.asked.size, "one call for a short narration")
        assertEquals(plans.size, film.chapters.size)
        assertTrue(film.chapters.flatMap { it.beats }.count { it.op == StageOp.Fill } >= plans.size)
        assertEquals(emptyList(), FilmCheck(n).problems(film))
    }

    @Test
    fun aBrokenModelLeavesTheFilmToTheRules() = runBlocking {
        val model = FakeModel(isLocal = true) { "I cannot write JSON today" }
        val log = mutableListOf<String>()
        val result = Screenwriter(LlmDirector(model), askAgain = false).write(n, 1) { log += it }
        assertTrue(log.any { "rules write" in it }, log.joinToString("\n"))
        val rules = FilmCheck(n).repair(RulesDirector().film(n, 1))
        assertEquals(rules.chapters.size, result.film.chapters.size)
        assertTrue(result.film.chapters.flatMap { it.beats }.isNotEmpty())
    }

    @Test
    fun aCarelessModelIsRepaired() = runBlocking {
        val plans = Chapters.find(n)
        // English words on a Persian film, a brand nobody said, a check on nothing, an op that does not suit its picture.
        val model = FakeModel(isLocal = true) { r ->
            if (r.system == DirectorPrompts.OUTLINE) {
                """{"chapters":[${plans.joinToString(",") { """{"first":${it.clauses.first},"title":"Chapter"}""" }}]}"""
            } else {
                val ks = clauses(r)
                """{"title":"Model Google","beats":[${beat(ks.first(), "none", "check", "ghost")},${beat(ks.first(), "chips", "add", "c", "OpenAI is great", items = """["writing","کدنویسی"]""")},""" +
                    """${beat(ks.last(), "none", "fill", "c", value = 0.4)}]}"""
            }
        }
        val result = Screenwriter(LlmDirector(model), askAgain = false).write(n, 1)
        val texts = result.film.chapters.flatMap { c -> listOf(c.title) + c.beats.flatMap { b -> listOf(b.content.text) + b.content.items.map { it.label } } }
        println(texts)
        assertTrue(texts.none { t -> t.contains("Google") || t.contains("OpenAI") || t.contains("writing") || t.contains("Chapter") })
        assertEquals(emptyList(), FilmCheck(n).problems(result.film))
    }
}
