package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.CaptionsScoreUnused
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The contact sheet: six questions a loop can assert against, graded from the score and the words alone. */
class ContactSheetTest {

    @Test
    fun aMadeEditPassesEveryCriterion() {
        val score = Score(
            look = "noir",
            captions = captionScore(maxWords = 4),
            scenes = listOf(
                SceneScore(from = 0, camera = "push-in", beats = listOf(BeatScore(recipe = "mask-rise", at = 0, hold = 2f, text = "امروز رفتم"))),
                SceneScore(from = 4, camera = "drift", beats = listOf(BeatScore(recipe = "meter", at = 4, hold = 3f, label = "پنجاه درصد"))),
                SceneScore(from = 8, beats = listOf(BeatScore(recipe = "voucher", at = 8, until = 12, hold = 4f, label = "سیصد و پنجاه تتر"))),
                SceneScore(from = 13, beats = listOf(BeatScore(recipe = "flip", at = 13, hold = 2f, text = "همان اول گفتم"))),
            ),
        )
        val sheet = ContactSheet.grade(score, timed(talk), Understanding(mood = "confident"))
        assertTrue(sheet.min >= ContactSheet.Sheet.PASS, sheet.summary())
        assertTrue(sheet.findings.isEmpty(), sheet.findings.joinToString())
    }

    @Test
    fun anEmptyEditFailsTheHook() {
        val sheet = ContactSheet.grade(Score(), timed(talk))
        assertEquals(0f, sheet.hook.score, "nothing lands — no hook at all")
        assertTrue(sheet.total < ContactSheet.Sheet.PASS, sheet.summary())
        assertTrue(sheet.findings.any { "hook" in it }, sheet.findings.joinToString())
    }

    @Test
    fun aFlashingGraphicCostsTheMotionGrade() {
        val score = Score(
            scenes = listOf(
                SceneScore(from = 0, beats = listOf(BeatScore(recipe = "slam", at = 0, hold = 0.5f, text = "امروز"))),
                SceneScore(from = 4, beats = listOf(BeatScore(recipe = "meter", at = 4, hold = 3f, label = "پنجاه درصد"))),
            ),
        )
        val sheet = ContactSheet.grade(score, timed(talk))
        assertTrue(sheet.motion.score < 10f, "a flash is a motion flaw: ${sheet.motion.note}")
    }

    // ---------------------------------------------------------------- fixtures

    private val talk = listOf(
        "امروز رفتم صرافی دیدم.",
        "پنجاه درصد تخفیف داشت.",
        "سیصد و پنجاه تتر دادند.",
        "همان اول گفتم مواظب باشید.",
    )

    private fun timed(sentences: List<String>): List<Word> {
        var t = 300L
        return sentences.flatMap { s ->
            s.split(' ').map { w -> Word(w, TimeRange(t, t + 420), language = Language.Persian).also { t += 500 } }.also { t += 1000 }
        }
    }
}
