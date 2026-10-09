package io.trimio.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.time.TimeSource

/**
 * Runs [stages] in order for one job and streams [PipelineEvent]s:
 * weighted 0..1 progress, ETA, per-stage status and live content for the build screen.
 *
 * The flow is cold: collecting starts the job, cancelling the collector cancels it.
 */
class PipelineOrchestrator(
    private val stages: List<PipelineStage>,
    private val checkpoints: CheckpointStore = InMemoryCheckpointStore(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** Minimum change in a stage fraction worth emitting; avoids flooding the UI from per-frame reports. */
    private val progressGranularity: Float = 0.005f,
) {
    init {
        require(stages.isNotEmpty()) { "A pipeline needs at least one stage" }
        require(stages.map { it.id } == stages.map { it.id }.sorted()) { "Stages must follow StageId order" }
        require(stages.map { it.id }.toSet().size == stages.size) { "Duplicate stage" }
    }

    /**
     * @param artifacts the job's blackboard. Pass one to pre-seed inputs (e.g. an edited timeline
     *   for a re-export that runs only Render and Export) or to read results after completion.
     */
    fun run(job: JobSpec, artifacts: Artifacts = Artifacts()): Flow<PipelineEvent> = channelFlow {
        Run(job, this, artifacts).execute()
    }

    private inner class Run(private val job: JobSpec, private val scope: ProducerScope<PipelineEvent>, private val artifacts: Artifacts) {
        private val progress = stages.map { StageProgress(it.id, StageStatus.Pending, 0f) }.toMutableList()
        private val started = timeSource.markNow()
        private var restoredShare = 0f
        private var current: StageId? = null

        suspend fun execute() {
            restoreCheckpoints()
            publish(JobStatus.Running)

            for ((index, stage) in stages.withIndex()) {
                if (progress[index].isFinished) continue
                current = stage.id
                progress[index] = progress[index].copy(status = StageStatus.Running, fraction = 0f)
                publish(JobStatus.Running)

                try {
                    stage.run(ContextImpl(index, stage.id))
                } catch (e: CancellationException) {
                    // The collector is gone; nobody can receive a Cancelled state. Checkpoints stay for resume.
                    throw e
                } catch (e: Throwable) {
                    progress[index] = progress[index].copy(status = StageStatus.Failed)
                    publish(JobStatus.Failed, error = e.message ?: e::class.simpleName)
                    return
                }

                progress[index] = progress[index].copy(status = StageStatus.Done, fraction = 1f)
                checkpoints.markCompleted(job.id, stage.id)
                publish(JobStatus.Running)
            }

            current = null
            checkpoints.clear(job.id)
            publish(JobStatus.Completed)
        }

        private suspend fun restoreCheckpoints() {
            val done = checkpoints.completedStages(job.id)
            if (done.isEmpty()) return
            for ((index, stage) in stages.withIndex()) {
                if (stage.id !in done) break // only a contiguous prefix can be trusted
                val restored = (stage as? RestorableStage)?.restore(job, artifacts) ?: false
                if (!restored) break
                progress[index] = progress[index].copy(status = StageStatus.Restored, fraction = 1f)
            }
            restoredShare = WeightedProgress.overall(progress)
        }

        private suspend fun publish(status: JobStatus, error: String? = null) {
            val overall = if (status == JobStatus.Completed) 1f else WeightedProgress.overall(progress)
            val eta = when (status) {
                JobStatus.Running -> WeightedProgress.etaMs(started.elapsedNow().inWholeMilliseconds, overall, restoredShare)
                JobStatus.Completed -> 0L
                else -> null
            }
            scope.send(
                PipelineEvent.StateChanged(
                    PipelineState(
                        jobId = job.id,
                        status = status,
                        stages = progress.toList(),
                        overall = overall,
                        current = current,
                        etaMs = eta,
                        error = error,
                    ),
                ),
            )
        }

        private inner class ContextImpl(private val index: Int, private val stageId: StageId) : StageContext {
            override val job: JobSpec get() = this@Run.job
            override val artifacts: Artifacts get() = this@Run.artifacts
            private var lastPublished = 0f

            override fun progress(fraction: Float) {
                val clamped = fraction.coerceIn(0f, 1f)
                val previous = progress[index].fraction
                if (clamped <= previous) return
                progress[index] = progress[index].copy(fraction = clamped)
                if (clamped - lastPublished >= progressGranularity || clamped == 1f) {
                    lastPublished = clamped
                    // Non-suspending on purpose so engines can report from tight loops; a full
                    // buffer just drops this intermediate update.
                    scope.trySend(PipelineEvent.StateChanged(snapshot()))
                }
            }

            override suspend fun emit(signal: LiveSignal) {
                scope.send(PipelineEvent.Live(stageId, signal))
            }

            private fun snapshot(): PipelineState {
                val overall = WeightedProgress.overall(progress)
                return PipelineState(
                    jobId = job.id,
                    status = JobStatus.Running,
                    stages = progress.toList(),
                    overall = overall,
                    current = stageId,
                    etaMs = WeightedProgress.etaMs(started.elapsedNow().inWholeMilliseconds, overall, restoredShare),
                )
            }
        }
    }
}
