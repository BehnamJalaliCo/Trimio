package io.trimio.feature.stream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.core.pipeline.JobSpec
import io.trimio.core.pipeline.PipelineOrchestrator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BuildStreamViewModel(
    private val orchestrator: PipelineOrchestrator,
    val job: JobSpec,
) : ViewModel() {

    private val _state = MutableStateFlow(BuildStreamState())
    val state: StateFlow<BuildStreamState> = _state.asStateFlow()

    private var running: Job? = null

    init {
        start()
    }

    fun start() {
        running?.cancel()
        _state.value = BuildStreamState()
        running = viewModelScope.launch {
            orchestrator.run(job).collect { event ->
                _state.update { BuildStreamReducer.reduce(it, event) }
            }
        }
    }

    /** Stops work; finished stages stay checkpointed so [start] resumes. */
    fun cancel() {
        running?.cancel()
        running = null
    }
}
