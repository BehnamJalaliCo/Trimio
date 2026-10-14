package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.StopReason
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A reading the system killed half-way goes on from its checkpoint: the whole-piece call and the groups
 * already read are not asked again (1.0.17 on a phone: killed for memory after the first groups, then
 * every call from the start).
 */
class UnderstandingDraftTest {
    private val words = (0 until 24).map { i -> Word("کلمه$i", TimeRange(i * 400L, i * 400L + 350L), language = Language.Persian) }
    private val transcript = Transcript(Language.Persian, words)
    private val lines = (0 until 8).map { Lines.Line(it * 3, it * 3 + 2) }

    /** Answers like a director, and counts what it was asked. */
    private class Director : LanguageModel {
        override val id = "test-director"
        override val isLocal = true
        val systems = mutableListOf<String>()

        override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
            systems += request.system
            val text = if (request.system == Understander.SYSTEM) {
                """{"title":"عنوان","topic":"test","domain":"general","mood":"calm"}"""
            } else {
                val n = Regex("LINES \\((\\d+)\\)").find(request.messages.last().text)!!.groupValues[1].toInt()
                """{"reads":[${List(n) { """{"show":"headline","title":"تیتر"}""" }.joinToString(",")}]}"""
            }
            return Generation(text, StopReason.EndTurn, id)
        }
    }

    @Test
    fun aKilledReadingResumesFromItsDraft() = runBlocking {
        val drafts = mutableListOf<UnderstandingDraft>()
        // The whole piece and two groups of three lines, then the process dies.
        assertFailsWith<IllegalStateException> {
            Understander(Director()).understand(transcript, "", lines, onDraft = { d -> drafts += d; check(d.reads.size < 6) { "killed" } })
        }
        val kept = drafts.last()
        assertEquals(6, kept.reads.size)

        val second = Director()
        val u = Understander(second).understand(transcript, "", lines, draft = kept)
        assertTrue(second.systems.none { it == Understander.SYSTEM }, "the whole-piece call is not made again")
        assertEquals(1, second.systems.size, "only the last group of two lines is read")
        assertEquals(8, u.lines.size)
        assertTrue(u.lines.all { it.show == "headline" })
    }
}
