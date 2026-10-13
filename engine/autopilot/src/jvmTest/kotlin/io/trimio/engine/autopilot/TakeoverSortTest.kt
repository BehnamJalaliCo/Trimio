package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The owner's Windows 1.0.14 crash, «jury: Comparison method violates its general contract!», on
 * a 155 s Persian talk: the planner sorted its full-frame candidates by a weight that drew a new
 * random number on every comparison, and on 32 lines or more TimSort caught it. The jury plans
 * variations, so it died there. This plans that talk (its recognised words only, no audio or
 * video) for every seed and variation the app would try, with the rules' reading.
 */
class TakeoverSortTest {
    @Serializable
    private data class W(val w: String, val s: Float, val e: Float)

    private fun words(name: String): List<Word> {
        val text = javaClass.getResource("/real/$name.json")!!.readText()
        return Json.decodeFromString(ListSerializer(W.serializer()), text)
            .map { Word(it.w, TimeRange((it.s * 1000).toLong(), (it.e * 1000).toLong()), language = Language.Persian) }
    }

    @Test
    fun everyVariationOfTheOwnersTalkIsPlanned() {
        val prompt = "ویدیوی آموزشی ترید کریپتو با معرفی ربات تلگرام"
        for (name in listOf("owner-155s", "joined-157s")) {
            val words = words(name)
            val lines = Lines.split(words)
            assertTrue(lines.size >= MIN_LINES, "$name: ${lines.size} lines (TimSort only checks its contract on 32+ items)")
            val u = RulesUnderstander.understand(Transcript(Language.Persian, words), prompt, lines)
            for (seed in 1L..6L) for (k in 0L..VARIATIONS) {
                val s = if (k == 0L) seed else seed * 1000 + k
                val plan = Planner(Taste(), prompt).plan(words, lines, u, s)
                assertTrue(plan.score.scenes.isNotEmpty(), "$name seed $s")
            }
        }
    }

    private companion object {
        const val MIN_LINES = 32
        const val VARIATIONS = 3L
    }
}
