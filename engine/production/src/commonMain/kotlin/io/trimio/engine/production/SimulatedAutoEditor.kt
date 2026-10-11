package io.trimio.engine.production

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A stand-in [AutoEditor] for platforms without engines (web, iOS previews) and for screen tests:
 * walks the stages with plausible progress and steps, then fails politely ("preview build"), so
 * every state a screen must draw appears. No previews, panel or file.
 */
class SimulatedAutoEditor(private val tick: Long = TICK_MS) : AutoEditor {
    override fun produce(request: ProductionRequest): Flow<ProductionState> = flow {
        var s = ProductionState(request.key)
        var done = 0f
        for ((stage, steps) in STEPS) {
            for ((i, step) in steps.withIndex()) {
                delay(tick)
                s = s.copy(stage = stage, step = step, progress = done + stage.weight * (i + 1) / steps.size)
                emit(s)
            }
            done += stage.weight
        }
        emit(s.copy(status = ProductionStatus.Failed, error = "preview build: no engines on this platform"))
    }

    override fun vary(edit: Edit, seed: Long): Flow<ProductionState> = produce(edit.request.copy(seed = seed))

    override fun revise(edit: Edit, feedback: String): Flow<ProductionState> = produce(edit.request.copy(seed = edit.request.seed + 1))

    private companion object {
        const val TICK_MS = 400L
        val STEPS = listOf(
            Stage.Prepare to listOf("prepare", "attachments"),
            Stage.Transcribe to listOf("transcribe"),
            Stage.Direct to listOf("listen", "understand", "research", "plan", "pictures", "compile", "jury", "sound"),
            Stage.Render to listOf("render"),
        )
    }
}
