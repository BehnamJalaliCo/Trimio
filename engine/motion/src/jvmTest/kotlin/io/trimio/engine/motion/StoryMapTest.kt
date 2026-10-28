package io.trimio.engine.motion

import io.trimio.engine.motion.score.ScoreGrammar
import io.trimio.engine.motion.score.StoryBeat
import io.trimio.engine.motion.score.StoryMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The story map the model writes: the schema names its four kinds, and the model's slips come out clean. */
class StoryMapTest {

    @Test
    fun theStorySchemaNamesAllFourKinds() {
        for (kind in StoryMap.KINDS) {
            assertTrue(kind in ScoreGrammar.storyJsonSchema, "the JSON schema must pin «$kind»")
            assertTrue(kind in ScoreGrammar.storyGbnf, "the GBNF must pin «$kind»")
        }
    }

    @Test
    fun aModelStoryMapRepairsAndParses() {
        // Fenced, curly-quoted, Persian digits, trailing commas: the repair does its work first.
        val raw = """
            ```json
            {"beats":[{"kind":"payoff","from":۲,"until":۵,"claim":"۵۰٪ کمتر","rationale":"the promise", "energy":0.9,}],
             "objects":["the voucher"], "banned":["no countdown"],}
            ```
        """.trimIndent()
        val map = StoryMap.parse(raw)
        assertEquals(1, map.beats.size)
        val beat = map.beats.single()
        assertEquals("payoff", beat.kind)
        assertEquals(2, beat.from, "Persian digits are repaired to Latin")
        assertEquals(5, beat.until)
        assertEquals("the promise", beat.rationale)
        assertEquals("no countdown", map.banned.single(), "the banned list survives the repair")
    }

    @Test
    fun sanitizeOrdersBeatsAndClampsThemToTheTranscript() {
        val map = StoryMap(
            beats = listOf(
                StoryBeat(kind = "payoff", from = 6, until = 99, claim = "the payoff"),
                StoryBeat(kind = "nonsense", from = 0, until = 2, claim = "the open"),
                StoryBeat(kind = "callback", from = 40, until = 50, claim = "the echo"),
            ),
        )
        val clean = StoryMap.sanitize(map, n = 12)
        assertEquals(2, clean.beats.size, "a beat outside the transcript goes")
        assertEquals(listOf("setup", "payoff"), clean.beats.map { it.kind }, "unknown kinds fall back to setup; beats run in story order")
        assertEquals(2, clean.beats[0].until, "a beat keeps its own end inside the transcript")
        assertEquals(11, clean.beats[1].until, "a beat that trails runs to the transcript's end")
    }

    @Test
    fun theTopBeatsPathCapsTheBeatCount() {
        assertTrue("\"maxItems\": 3" in ScoreGrammar.topBeatsJsonSchema(3), "the model may write at most three beats")
        assertTrue("{0,2}" in ScoreGrammar.topBeatsGbnf(3), "constrained decoding caps it too")
        assertTrue("\"maxItems\": 8" in ScoreGrammar.topBeatsJsonSchema(8), "the cap follows the caller")
    }
}
