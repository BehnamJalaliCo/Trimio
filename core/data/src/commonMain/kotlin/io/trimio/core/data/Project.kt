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
    /** Published file (content:// on Android, path on desktop); for productions, the finished MP4 in app storage. */
    val outputUri: String? = null,
    val error: String? = null,
    /** Set when the automatic engine made this project (phase 7); null for projects of the earlier pipeline. */
    val production: ProductionRecord? = null,
) {
    val isAudioOnly: Boolean get() = input is InputSource.AudioOnly

    companion object {
        const val WAVEFORM_STEP_MS = 50L
    }
}

/**
 * A project the automatic engine makes: the order it ran, how far the current run is, and what the
 * finished edit is (for the home list and the result screen). Fields only grow, with defaults, so
 * files written by older versions keep loading.
 */
@Serializable
data class ProductionRecord(
    /** The engine's order as it serialises itself (io.trimio.engine.production.ProductionRequest); replayed for retries and new versions. */
    val request: String,
    /** The key of the current run (the engine reports progress under it). */
    val key: String,
    /** Every run key this project has had: their saved words and reading go when the project is deleted. */
    val keys: List<String> = emptyList(),
    /** Every file its versions wrote (videos, posters), kept until the project is deleted. */
    val files: List<String> = emptyList(),
    /** 0..1 of the current run. */
    val progress: Float = 0f,
    /** The edit's poster frame (JPEG in app storage). */
    val poster: String? = null,
    /** The style the edit was made in (the creator's pick, or the director's when automatic). */
    val styleId: String? = null,
    val durationMs: Long = 0,
    val buildSeconds: Int = 0,
    /** 1 for the first edit, 2+ for variations and revisions. */
    val version: Int = 1,
    val onDevice: Boolean = true,
    val scenes: Int = 0,
    val words: Int = 0,
    val effects: Int = 0,
    /** One score (0–10) per judge, in panel order. */
    val judges: List<JudgeVerdict> = emptyList(),
    val findings: List<String> = emptyList(),
    /** The creator has opened the finished edit (a fresh one is marked on the home list). */
    val seen: Boolean = false,
    /**
     * The creator's own changes (the editor) in the current version, as the engine serialises
     * them (io.trimio.engine.production.EditOverrides); null while the version is the director's.
     */
    val overrides: String? = null,
    /** Changes made in the editor and not yet rendered into a version (kept so leaving the app loses nothing). */
    val draft: String? = null,
)

@Serializable
data class JudgeVerdict(val judge: String, val score: Float)
