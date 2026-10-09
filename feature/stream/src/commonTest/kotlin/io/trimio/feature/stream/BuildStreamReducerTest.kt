package io.trimio.feature.stream

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.core.pipeline.JobStatus
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineEvent
import io.trimio.core.pipeline.PipelineState
import io.trimio.core.pipeline.StageId
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildStreamReducerTest {

    private fun word(i: Int) = PipelineEvent.Live(
        StageId.Transcription,
        LiveSignal.WordRecognized(i, Word("w$i", TimeRange(i * 100L, i * 100L + 80), language = Language.English)),
    )

    private fun progress(overall: Float) = PipelineEvent.StateChanged(
        PipelineState("j", JobStatus.Running, emptyList(), overall, StageId.Render, etaMs = null),
    )

    @Test
    fun overallNeverMovesBackwards() {
        val state = listOf(progress(0.4f), progress(0.35f), progress(0.5f))
            .fold(BuildStreamState(), BuildStreamReducer::reduce)
        assertEquals(0.5f, state.overall)
    }

    @Test
    fun wordsKeepARollingWindowAndReceiveEmphasis() {
        var state = (0 until BuildStreamReducer.MAX_WORDS + 5).map(::word).fold(BuildStreamState(), BuildStreamReducer::reduce)
        assertEquals(BuildStreamReducer.MAX_WORDS, state.words.size)
        assertEquals(5, state.words.first().index)

        state = BuildStreamReducer.reduce(state, PipelineEvent.Live(StageId.Analysis, LiveSignal.EmphasisFound(10, 0.9f)))
        assertEquals(0.9f, state.words.first { it.index == 10 }.emphasis)
    }

    @Test
    fun framesAndPlanAccumulate() {
        val state = listOf(
            PipelineEvent.Live(StageId.Direction, LiveSignal.PlanStep("الف", "a")),
            PipelineEvent.Live(StageId.Direction, LiveSignal.PlanStep("ب", "b")),
            PipelineEvent.Live(StageId.Render, LiveSignal.FrameRendered(120, 600)),
        ).fold(BuildStreamState(), BuildStreamReducer::reduce)
        assertEquals(listOf("a", "b"), state.plan.map { it.en })
        assertEquals(120 to 600, state.framesRendered to state.framesTotal)
    }
}
