package io.trimio.core.data

import io.trimio.core.pipeline.Artifacts
import io.trimio.core.pipeline.JobSpec
import io.trimio.core.pipeline.JobStatus
import io.trimio.core.pipeline.PipelineEvent
import io.trimio.core.pipeline.PipelineOrchestrator
import io.trimio.core.pipeline.PipelineState
import io.trimio.core.pipeline.StandardArtifacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs one heavy job at a time (a full build or a re-export) outside any screen, so it survives
 * navigation and the Android foreground service can mirror its progress in a notification.
 * Results are written back to the [ProjectRepository] when the job finishes.
 */
class JobRunner(
    private val scope: CoroutineScope,
    private val projects: ProjectRepository,
    /** All nine stages: speech, direction, assets, render, export. */
    private val buildPipeline: () -> PipelineOrchestrator,
    /** Render + Export only, for edits and new formats of a finished project. */
    private val exportPipeline: () -> PipelineOrchestrator,
) {
    enum class Kind { Build, Export }

    class Run(
        val projectId: String,
        val kind: Kind,
        /** Every event of the run, replayed to late subscribers (a screen opened mid-build). */
        val events: SharedFlow<PipelineEvent>,
        val state: StateFlow<PipelineState?>,
    )

    private val _active = MutableStateFlow<Run?>(null)
    val active: StateFlow<Run?> = _active.asStateFlow()
    private var job: Job? = null

    fun build(project: Project): Run {
        val spec = JobSpec(
            id = project.id,
            input = project.input,
            prompt = project.prompt,
            styleId = project.styleId,
            director = project.director,
            seed = project.createdAt,
        )
        return launch(project.id, Kind.Build, spec, Artifacts(), buildPipeline())
    }

    fun export(project: Project): Run? {
        val timeline = project.timeline ?: return null
        val style = project.style ?: return null
        val artifacts = Artifacts().apply {
            set(StandardArtifacts.Timeline, timeline)
            set(StandardArtifacts.Style, style)
        }
        val spec = JobSpec(id = "${project.id}-export-${Clock.now()}", input = project.input, prompt = project.prompt, styleId = timeline.styleId)
        return launch(project.id, Kind.Export, spec, artifacts, exportPipeline())
    }

    fun cancel() {
        job?.cancel()
        job = null
        val run = _active.value ?: return
        _active.value = null
        scope.launch { projects.update(run.projectId) { if (it.status == ProjectStatus.Building) it.copy(status = ProjectStatus.Draft) else it } }
    }

    private fun launch(projectId: String, kind: Kind, spec: JobSpec, artifacts: Artifacts, pipeline: PipelineOrchestrator): Run {
        job?.cancel()
        val events = MutableSharedFlow<PipelineEvent>(replay = REPLAY)
        val state = MutableStateFlow<PipelineState?>(null)
        val run = Run(projectId, kind, events, state.asStateFlow())
        _active.value = run
        job = scope.launch {
            if (kind == Kind.Build) projects.update(projectId) { it.copy(status = ProjectStatus.Building, error = null) }
            pipeline.run(spec, artifacts).collect { event ->
                events.emit(event)
                if (event is PipelineEvent.StateChanged) {
                    state.value = event.state
                    when (event.state.status) {
                        JobStatus.Completed -> finish(projectId, kind, artifacts)
                        JobStatus.Failed -> projects.update(projectId) {
                            it.copy(status = if (kind == Kind.Build) ProjectStatus.Failed else it.status, error = event.state.error)
                        }
                        else -> Unit
                    }
                }
            }
        }
        return run
    }

    private suspend fun finish(projectId: String, kind: Kind, artifacts: Artifacts) {
        projects.update(projectId) { p ->
            p.copy(
                status = ProjectStatus.Ready,
                transcript = if (kind == Kind.Build) artifacts[StandardArtifacts.Transcript] ?: p.transcript else p.transcript,
                timeline = artifacts[StandardArtifacts.Timeline] ?: p.timeline,
                style = artifacts[StandardArtifacts.Style] ?: p.style,
                outputUri = artifacts[StandardArtifacts.Output] ?: p.outputUri,
                waveform = artifacts[StandardArtifacts.AudioFeatures]?.let { f ->
                    (0 until (p.input.durationMs / Project.WAVEFORM_STEP_MS).toInt()).map { i -> f.energy01(i * Project.WAVEFORM_STEP_MS) }
                } ?: p.waveform,
                error = null,
            )
        }
    }

    private companion object {
        /** Enough for every progress tick and recognised word of a long build. */
        const val REPLAY = 20_000
    }
}
