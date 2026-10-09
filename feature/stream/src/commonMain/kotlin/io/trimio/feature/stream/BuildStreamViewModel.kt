package io.trimio.feature.stream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.JobRunner
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.ProjectStatus
import io.trimio.core.pipeline.JobStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Mirrors the [JobRunner]'s build of one project. The run lives in the runner, not here: leaving
 * the screen does not stop it, and coming back replays every event so the stream is complete.
 */
class BuildStreamViewModel(
    private val runner: JobRunner,
    private val projects: ProjectRepository,
    val projectId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(BuildStreamState())
    val state: StateFlow<BuildStreamState> = _state.asStateFlow()

    val project: StateFlow<Project?> = projects.projects.map { list -> list.firstOrNull { it.id == projectId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, projects.get(projectId))

    private var following: Job? = null

    init {
        val run = runner.active.value
        when {
            run != null && run.projectId == projectId && run.kind == JobRunner.Kind.Build -> follow(run)
            projects.get(projectId)?.status == ProjectStatus.Ready -> _state.value = BuildStreamState(status = JobStatus.Completed, overall = 1f)
            else -> start()
        }
    }

    fun start() {
        viewModelScope.launch {
            val p = project.filterNotNull().first()
            _state.value = BuildStreamState()
            follow(runner.build(p))
        }
    }

    private fun follow(run: JobRunner.Run) {
        following?.cancel()
        _state.value = BuildStreamState()
        following = viewModelScope.launch {
            run.events.collect { event -> _state.value = BuildStreamReducer.reduce(_state.value, event) }
        }
    }

    /** Stops the build; finished stages stay checkpointed so [start] resumes. */
    fun cancel() {
        following?.cancel()
        runner.cancel()
        _state.value = _state.value.copy(status = JobStatus.Cancelled)
    }
}
