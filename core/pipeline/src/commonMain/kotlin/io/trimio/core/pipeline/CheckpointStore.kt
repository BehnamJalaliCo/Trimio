package io.trimio.core.pipeline

/**
 * Remembers which stages of a job finished, so a job interrupted by the system (low memory,
 * thermal kill, user closing the app) resumes instead of starting again.
 *
 * Stages are responsible for persisting their own outputs (files, transcript JSON) and for
 * re-populating [Artifacts] in [RestorableStage.restore].
 */
interface CheckpointStore {
    suspend fun completedStages(jobId: String): Set<StageId>
    suspend fun markCompleted(jobId: String, stage: StageId)
    suspend fun clear(jobId: String)
}

class InMemoryCheckpointStore : CheckpointStore {
    private val completed = mutableMapOf<String, MutableSet<StageId>>()

    override suspend fun completedStages(jobId: String): Set<StageId> = completed[jobId].orEmpty().toSet()

    override suspend fun markCompleted(jobId: String, stage: StageId) {
        completed.getOrPut(jobId) { mutableSetOf() } += stage
    }

    override suspend fun clear(jobId: String) {
        completed.remove(jobId)
    }
}

/** A stage whose output can be reloaded from disk when resuming. */
interface RestorableStage : PipelineStage {
    /** Re-populate artifacts from persisted output. Return false if the output is gone and the stage must re-run. */
    suspend fun restore(job: JobSpec, artifacts: Artifacts): Boolean
}
