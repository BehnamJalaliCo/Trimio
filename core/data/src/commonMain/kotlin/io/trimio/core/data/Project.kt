package io.trimio.core.data

import io.trimio.core.model.input.InputSource
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.timeline.Timeline
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.pipeline.DirectorBackend
import kotlinx.serialization.Serializable

@Serializable
enum class ProjectStatus { Draft, Building, Ready, Failed }

/**
 * One edit the user made: what went in, what they asked for and everything the pipeline decided.
 * The [timeline] is the source of truth for re-editing and re-exporting without re-running speech
 * recognition or the Director.
 */
@Serializable
data class Project(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val input: InputSource,
    val prompt: String,
    val styleId: String? = null,
    val director: DirectorBackend = DirectorBackend.OnDevice,
    val status: ProjectStatus = ProjectStatus.Draft,
    val transcript: Transcript? = null,
    val timeline: Timeline? = null,
    val style: StyleSpec? = null,
    /** Speech loudness 0..1 every [WAVEFORM_STEP_MS] of *source* time, for the editor's waveform track. */
    val waveform: List<Float> = emptyList(),
    /** Published file (content:// on Android, path on desktop). */
    val outputUri: String? = null,
    val error: String? = null,
) {
    val isAudioOnly: Boolean get() = input is InputSource.AudioOnly

    companion object {
        const val WAVEFORM_STEP_MS = 50L
    }
}
