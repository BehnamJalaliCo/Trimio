package io.trimio.engine.production

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The [ProductionRunner] the app holds for its lifetime ([scope] is the app scope, so a run
 * outlives the screen that started it). The running request is saved in [checkpoints]: after the
 * system kills the process, [resume] restarts it, and the saved words and reading skip whisper and
 * the model.
 */
class AppProductionRunner(
    private val editor: AutoEditor,
    private val scope: CoroutineScope,
    private val checkpoints: Checkpoints = Checkpoints.None,
) : ProductionRunner {
    private val state = MutableStateFlow<ProductionState?>(null)
    override val active: StateFlow<ProductionState?> = state
    private var job: Job? = null

    override fun start(request: ProductionRequest) = launch(request, editor.produce(request))

    override fun vary(edit: Edit, seed: Long) = launch(edit.request.copy(seed = seed), editor.vary(edit, seed))

    override fun revise(edit: Edit, feedback: String) = launch(edit.request.copy(seed = edit.request.seed + 1), editor.revise(edit, feedback))

    override fun cancel() {
        job?.cancel()
        job = null
        checkpoints.setPending(null)
        state.update { s -> s?.takeIf { !it.finished }?.copy(status = ProductionStatus.Cancelled) ?: s }
    }

    /** Restarts the run the process was killed in, if any; true when one was restarted. */
    fun resume(): Boolean {
        if (job?.isActive == true) return false
        val request = checkpoints.pending() ?: return false
        start(request)
        return true
    }

    private fun launch(request: ProductionRequest, flow: Flow<ProductionState>) {
        job?.cancel()
        checkpoints.setPending(request)
        state.value = ProductionState(request.key)
        job = scope.launch {
            flow.collect { s ->
                state.value = s
                if (s.finished) checkpoints.setPending(null)
            }
        }
    }
}
