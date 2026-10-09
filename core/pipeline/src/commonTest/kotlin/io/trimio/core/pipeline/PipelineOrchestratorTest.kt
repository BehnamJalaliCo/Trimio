package io.trimio.core.pipeline

import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.pipeline.demo.DemoPipeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineOrchestratorTest {

    private val job = JobSpec(
        id = "job-1",
        input = InputSource.AudioOnly(MediaUri("file://voice.m4a"), 42_000, CanvasSpec()),
        prompt = "پرانرژی و مینیمال",
    )

    private class RecordingStage(
        override val id: StageId,
        private val log: MutableList<StageId>,
        private val fail: Boolean = false,
    ) : RestorableStage {
        override suspend fun run(context: StageContext) {
            log += id
            context.progress(0.5f)
            delay(10)
            if (fail) error("boom in $id")
            context.progress(1f)
        }

        override suspend fun restore(job: JobSpec, artifacts: Artifacts) = true
    }

    private fun states(events: List<PipelineEvent>) = events.filterIsInstance<PipelineEvent.StateChanged>().map { it.state }

    @Test
    fun weightsCoverHundredPercent() {
        assertEquals(100, StageId.TOTAL_WEIGHT)
    }

    @Test
    fun weightedProgressCountsRunningStageByFraction() {
        val stages = listOf(
            StageProgress(StageId.Ingest, StageStatus.Done, 1f),
            StageProgress(StageId.Transcription, StageStatus.Running, 0.5f),
            StageProgress(StageId.Render, StageStatus.Pending, 0f),
        )
        // (2 + 25 * 0.5) / (2 + 25 + 35)
        assertEquals(14.5f / 62f, WeightedProgress.overall(stages), 1e-5f)
    }

    @Test
    fun etaIgnoresRestoredWork() {
        assertEquals<Long?>(null, WeightedProgress.etaMs(elapsedMs = 1_000, overall = 0.51f, restoredShare = 0.5f))
        assertEquals(9_000L, WeightedProgress.etaMs(elapsedMs = 1_000, overall = 0.1f, restoredShare = 0f))
    }

    @Test
    fun runsStagesInOrderAndFinishesAtHundredPercent() = runTest {
        val log = mutableListOf<StageId>()
        val orchestrator = PipelineOrchestrator(StageId.entries.map { RecordingStage(it, log) })

        val states = states(orchestrator.run(job).toList())

        assertEquals(StageId.entries.toList(), log.toList())
        assertEquals(JobStatus.Completed, states.last().status)
        assertEquals(100, states.last().percent)
        val overall = states.map { it.overall }
        assertEquals(overall.sorted(), overall, "progress must never go backwards")
    }

    @Test
    fun failureStopsThePipelineAndReportsTheError() = runTest {
        val log = mutableListOf<StageId>()
        val stages = StageId.entries.map { RecordingStage(it, log, fail = it == StageId.Direction) }

        val last = states(PipelineOrchestrator(stages).run(job).toList()).last()

        assertEquals(JobStatus.Failed, last.status)
        assertEquals("boom in Direction", last.error)
        assertEquals(StageId.Direction, log.last())
        assertEquals(StageStatus.Failed, last.stages.first { it.id == StageId.Direction }.status)
    }

    @Test
    fun resumesFromCheckpointWithoutRerunningFinishedStages() = runTest {
        val checkpoints = InMemoryCheckpointStore()
        val firstLog = mutableListOf<StageId>()
        val failing = StageId.entries.map { RecordingStage(it, firstLog, fail = it == StageId.Render) }
        PipelineOrchestrator(failing, checkpoints).run(job).toList()

        val secondLog = mutableListOf<StageId>()
        val states = states(PipelineOrchestrator(StageId.entries.map { RecordingStage(it, secondLog) }, checkpoints).run(job).toList())

        assertEquals(listOf(StageId.Render, StageId.Export), secondLog)
        assertTrue(states.first().stages.take(7).all { it.status == StageStatus.Restored })
        assertEquals(JobStatus.Completed, states.last().status)
        assertTrue(checkpoints.completedStages(job.id).isEmpty(), "checkpoints are cleared after success")
    }

    @Test
    fun demoPipelineStreamsWordsPlanAndFrames() = runTest {
        val events = PipelineOrchestrator(DemoPipeline.stages(speed = 10f)).run(job).toList()
        val signals = events.filterIsInstance<PipelineEvent.Live>().map { it.signal }

        assertEquals(DemoPipeline.sampleSentence.size, signals.count { it is LiveSignal.WordRecognized })
        assertTrue(signals.any { it is LiveSignal.PlanStep })
        assertTrue(signals.filterIsInstance<LiveSignal.FrameRendered>().last().let { it.frameIndex == it.totalFrames })
        assertEquals(JobStatus.Completed, states(events).last().status)
    }

    @Test
    fun liveSignalsAreTaggedWithTheirStage() = runTest {
        val stages = PipelineOrchestrator(DemoPipeline.stages(speed = 10f)).run(job)
            .filterIsInstance<PipelineEvent.Live>()
            .map { it.stage to it.signal }
            .toList()
        assertTrue(stages.filter { it.second is LiveSignal.WordRecognized }.all { it.first == StageId.Transcription })
    }
}
