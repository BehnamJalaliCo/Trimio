package io.trimio.engine.motion.score

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreGrammarTest {

    @Test
    fun gbnfIsPinnedToTheScoreEnums() {
        val gbnf = ScoreGrammar.gbnf
        assertTrue(gbnf.isNotBlank())
        val literals = listOf(
            "noir", "paper", "lumen",
            "9:16", "1:1", "4:5", "16:9",
            "push-in", "pull-out", "drift", "still",
            "cut", "whip", "zoom", "flash", "leak",
        )
        for (literal in literals) {
            assertTrue(literal in gbnf, "gbnf must pin $literal")
        }
    }

    @Test
    fun jsonSchemaIsBalancedAndCoversScenes() {
        val schema = ScoreGrammar.jsonSchema
        assertEquals(schema.count { it == '{' }, schema.count { it == '}' })
        assertEquals(schema.count { it == '[' }, schema.count { it == ']' })
        assertTrue("scenes" in schema, "the schema must describe scenes")
        assertTrue("draft-07" in schema)
    }

    @Test
    fun repairStripsFencesAndProse() {
        val raw = "```json\nSure, here is the score you asked for:\n{\"look\":\"noir\"}\nHope this helps!\n```"
        assertEquals("{\"look\":\"noir\"}", ScoreGrammar.repair(raw))
    }

    @Test
    fun repairRepairsCurlyQuotesPersianDigitsAndTrailingCommas() {
        val raw = """{"look": “noir”, "bpm": ۱۲۸, "scenes": [{"from": 0, "beats": [{"recipe": "slam", "text": "بیت‌کوین", "energy": ۰.۹,}]}],}"""
        val expected = """{"look": "noir", "bpm": 128, "scenes": [{"from": 0, "beats": [{"recipe": "slam", "text": "بیت‌کوین", "energy": 0.9}]}]}"""
        assertEquals(expected, ScoreGrammar.repair(raw))
    }

    /** The sample from [Score]'s own doc: valid scores must come back byte for byte. */
    @Test
    fun aFullScoreJsonPassesThroughRepairUnchanged() {
        val sample = """{"look":"noir","scenes":[{"from":0,"camera":"push-in","beats":[
  {"recipe":"slam","text":"بیت‌کوین","energy":0.9},
  {"recipe":"counter","at":3,"value":5,"suffix":"٪","label":"رشد امروز"}]}]}"""
        assertEquals(sample, ScoreGrammar.repair(sample))
    }
}
