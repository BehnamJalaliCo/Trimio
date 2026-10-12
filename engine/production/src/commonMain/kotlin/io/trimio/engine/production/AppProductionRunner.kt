package io.trimio.engine.production

import io.trimio.engine.production.diagnostics.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * The [ProductionRunner] the app holds for its lifetime ([scope] is the app scope, so a run
 * outlives the screen that started it). The running request is saved in [checkpoints]: after the
 * system kills the process, [resume] restarts it, and the saved words and reading skip whisper and
 * the model.
 *
 * A watchdog follows every run: when nothing moves for the device's limit
 * ([AutoEditor.stallLimitMs]; a model streaming text counts as moving) the state carries a
 * [Stall], and the creator keeps waiting ([keepWaiting]), goes on with a lighter director
 * ([lighter]) or cancels. [now] (ms) and [tickMs] are the watchdog's clock and pace (tests pass
 * virtual time).
 */
class AppProductionRunner(
    private val editor: AutoEditor,
    private val scope: CoroutineScope,
    private val checkpoints: Checkpoints = Checkpoints.None,
    private val now: () -> Long = monotonic(),
    private val tickMs: Long = TICK_MS,
) : ProductionRunner {
    private val state = MutableStateFlow<ProductionState?>(null)
    override val active: StateFlow<ProductionState?> = state
    private var job: Job? = null
    private var request: ProductionRequest? = null
    private var watchdog: Watchdog? = null

    override fun start(request: ProductionRequest) = launch(request, editor.produce(request))

    override fun vary(edit: Edit, seed: Long) = launch(edit.request.copy(seed = seed), editor.vary(edit, seed))

    override fun revise(edit: Edit, feedback: String) = launch(edit.request.copy(seed = edit.request.seed + 1), editor.revise(edit, feedback))

    // A render of the editor's changes is not resumed after process death: the changes live in the editor.
    override fun retouch(edit: Edit, overrides: EditOverrides) = launch(edit.request, editor.retouch(edit, overrides), resumable = false)

    override fun cancel() {
        state.value?.let { s -> s.stall?.let { editor.stalled(s.key, it.step, it.minutes, CANCELLED) } }
        job?.cancel()
        job = null
        checkpoints.setPending(null)
        state.update { s -> s?.takeIf { !it.finished }?.copy(status = ProductionStatus.Cancelled, stall = null) ?: s }
    }

    override fun keepWaiting() {
        val s = state.value ?: return
        val stall = s.stall ?: return
        editor.stalled(s.key, stall.step, stall.minutes, WAITING)
        watchdog?.keepWaiting()
        state.update { it?.copy(stall = null) }
    }

    override fun lighter(request: ProductionRequest?) {
        val s = state.value
        val asked = request ?: this.request ?: return
        s?.stall?.let { editor.stalled(s.key, it.step, it.minutes, LIGHTER) }
        // The model that ran (the report names it), else the one asked for.
        val current = s?.takeIf { it.key == asked.key }?.technical?.director ?: (asked.director as? DirectorChoice.Local)?.modelId
        scope.launch { redirect(editor.lighter(current), asked) }
    }

    override fun redirect(director: DirectorChoice, request: ProductionRequest?) {
        val asked = request ?: this.request ?: return
        // The same key: the saved words and reading carry over, the project follows the new attempt.
        start(asked.copy(director = director))
    }

    /** Restarts the run the process was killed in, if any; true when one was restarted. */
    fun resume(): Boolean {
        if (job?.isActive == true) return false
        val request = checkpoints.pending() ?: return false
        start(request)
        return true
    }

    private fun launch(request: ProductionRequest, flow: Flow<ProductionState>, resumable: Boolean = true) {
        job?.cancel()
        this.request = request.takeIf { resumable } ?: this.request
        checkpoints.setPending(request.takeIf { resumable })
        state.value = ProductionState(request.key)
        val dog = Watchdog(editor.stallLimitMs(), now).also { watchdog = it }
        job = scope.launch {
            val watch = launch { watch(dog) }
            flow.collect { s ->
                if (dog.saw(signature(s))) state.value = s else state.value = s.copy(stall = state.value?.stall?.takeIf { s.key == state.value?.key })
                if (s.finished) checkpoints.setPending(null)
            }
            watch.cancel()
        }
    }

    /** Every [tickMs]: a run silent past the limit gets a [Stall] (once, until it moves or the creator answers). */
    private suspend fun watch(dog: Watchdog) {
        while (true) {
            delay(tickMs)
            // A finished run, or one whose stall the creator has not answered yet, is not checked.
            val s = state.value?.takeIf { !it.finished && it.stall == null }
            val silent = dog.stalled()
            if (s == null || silent == null) continue
            val minutes = (silent / MINUTE).toInt().coerceAtLeast(1)
            state.update { it?.copy(stall = Stall(s.step, minutes)) }
            editor.stalled(s.key, s.step, minutes)
        }
    }

    companion object {
        const val TICK_MS = 15_000L
        private const val MINUTE = 60_000L
        private const val CANCELLED = "cancelled"
        private const val WAITING = "kept waiting"
        private const val LIGHTER = "lighter director"

        /** What counts as progress: the stage, the step, the bar (to a tenth of a percent), frames, a model streaming. */
        fun signature(s: ProductionState): Any = listOf(s.status, s.stage, s.step, (s.progress * PROGRESS_STEPS).toInt(), s.previews.size, s.heartbeat, s.panel != null)

        private const val PROGRESS_STEPS = 1000

        private fun monotonic(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
