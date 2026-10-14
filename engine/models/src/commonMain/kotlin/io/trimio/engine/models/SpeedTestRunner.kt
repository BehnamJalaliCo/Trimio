package io.trimio.engine.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The speed test as the app runs it, not a screen: it goes on when the creator leaves the app (on Android a foreground
 * service holds it, its percentage in the notification) and only closing the app ends it ([cancel]). Screens show
 * [state]; null [test] where the platform has none.
 */
class SpeedTestRunner(private val test: SpeedTest?, private val scope: CoroutineScope) {
    data class State(val running: Boolean = false, val done: Boolean = false, val progress: Float = 0f, val rows: List<SpeedRow> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    val available: Boolean get() = test != null

    /** Starts the test once: a second call while it runs does nothing. */
    fun start() {
        val test = test ?: return
        if (_state.value.running) return
        _state.value = State(running = true)
        job = scope.launch {
            runCatching {
                test.run(
                    onRow = { row -> _state.update { it.copy(rows = it.rows + row) } },
                    onProgress = { p -> _state.update { it.copy(progress = maxOf(it.progress, p)) } },
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(rows = it.rows + SpeedRow(SpeedRow.Kind.Note, "خطا", e.message ?: "")) }
            }
            _state.update { it.copy(running = false, done = true, progress = 1f) }
        }
    }

    /** Ends a running test (the creator closed the app). */
    fun cancel() {
        job?.cancel()
        job = null
        _state.update { if (it.running) State() else it }
    }

    /** The ways every build uses now (the last test's choices). */
    fun current(): List<SpeedRow> = test?.current().orEmpty()
}
