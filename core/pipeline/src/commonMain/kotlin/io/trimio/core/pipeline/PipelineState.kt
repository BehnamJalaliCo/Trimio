package io.trimio.core.pipeline

enum class StageStatus { Pending, Running, Done, Restored, Failed }

data class StageProgress(val id: StageId, val status: StageStatus, val fraction: Float) {
    val isFinished: Boolean get() = status == StageStatus.Done || status == StageStatus.Restored
}

enum class JobStatus { Running, Completed, Failed, Cancelled }

data class PipelineState(
    val jobId: String,
    val status: JobStatus,
    val stages: List<StageProgress>,
    /** Overall progress 0..1, weighted by [StageId.weight]. */
    val overall: Float,
    val current: StageId?,
    val etaMs: Long?,
    val error: String? = null,
) {
    val percent: Int get() = (overall * 100).toInt().coerceIn(0, 100)
}

sealed interface PipelineEvent {
    data class StateChanged(val state: PipelineState) : PipelineEvent
    data class Live(val stage: StageId, val signal: LiveSignal) : PipelineEvent
}

object WeightedProgress {
    /** Finished stages count in full, the running stage by its fraction, the rest as zero. */
    fun overall(stages: List<StageProgress>): Float {
        val total = stages.sumOf { it.id.weight }.takeIf { it > 0 } ?: return 0f
        val done = stages.sumOf { stage ->
            when {
                stage.isFinished -> stage.id.weight.toDouble()
                stage.status == StageStatus.Running -> stage.id.weight * stage.fraction.toDouble()
                else -> 0.0
            }
        }
        return (done / total).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Linear extrapolation from time spent on work done in *this* run.
     * Restored stages are excluded so a resumed job does not report an optimistic ETA.
     * Returns null until there is enough signal to be meaningful.
     */
    fun etaMs(elapsedMs: Long, overall: Float, restoredShare: Float): Long? {
        val progressedThisRun = overall - restoredShare
        if (progressedThisRun < 0.03f || elapsedMs <= 0) return null
        val remaining = 1f - overall
        return (elapsedMs * remaining / progressedThisRun).toLong()
    }
}
