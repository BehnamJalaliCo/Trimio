package io.trimio.core.model.input

import kotlinx.serialization.Serializable

/** What a probe found in the user's file. */
@Serializable
data class MediaInfo(
    val durationMs: Long,
    /** Null for audio-only files. */
    val video: VideoFormat?,
    /** Null for silent videos — the pipeline then skips speech stages. */
    val audio: AudioTrackInfo?,
    val mimeType: String? = null,
) {
    val hasVideo: Boolean get() = video != null
    val hasAudio: Boolean get() = audio != null
}

@Serializable
data class AudioTrackInfo(
    val sampleRate: Int,
    val channels: Int,
    val codec: String? = null,
)
