package io.trimio.engine.motion

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A caption said over the speaker ends at the cut to a full-frame stage: it never runs on under
 * the stage's graphic (the benchmark's caption overlapped the countdown by 36 px at that cut).
 */
class CaptionStageTest {
    private val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList())))

    @Test
    fun captionsEndWhereAStageBegins() {
        // Continuous speech: no pause gives the caption a natural end before the cut.
        val words = (0 until 24).map { i -> Word("کلمه$i", TimeRange(300L + i * 300L, 300L + i * 300L + 280L), language = Language.Persian) }
        val score = Score(
            auto = false,
            scenes = listOf(
                SceneScore(from = 0),
                SceneScore(from = 10, bg = "grid", beats = listOf(BeatScore("countdown", at = 10, value = 48f, suffix = "ساعت", hold = 3.4f, place = "center"))),
                SceneScore(from = 20),
            ),
        )
        val input = Compiler.Input(score, Transcript(Language.Persian, words), footage = "main", subject = Subject(0.3f, 0.3f, 0.7f, 0.62f))
        val out = Compiler(probe.renderer.text).compile(input)
        val stage = out.beats.first { it.recipe == "countdown" }
        val stageStart = words[10].range.startMs / 1000f - 0.2f
        val before = out.beats.filter { it.recipe == "pop-captions" && it.at < stageStart }
        assertTrue(before.isNotEmpty(), "captions over the speaker before the stage")
        for (c in before) assertTrue(c.out <= stage.at, "caption «${c.text}» ${c.at}–${c.out} runs into the stage at ${stage.at}")
    }
}
