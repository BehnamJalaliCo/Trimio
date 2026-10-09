package io.trimio.core.model.input

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Platform-neutral reference to a media file (content:// on Android, file URL on iOS, blob URL on web). */
@Serializable
@JvmInline
value class MediaUri(val value: String)

/** What the user gave us to edit. */
@Serializable
sealed interface InputSource {
    val uri: MediaUri
    val durationMs: Long

    /** A video with its own audio track. Motion graphics are composited over the footage. */
    @Serializable
    @SerialName("video")
    data class Video(
        override val uri: MediaUri,
        override val durationMs: Long,
        val format: VideoFormat,
    ) : InputSource

    /**
     * Audio only (podcast, voice note, music). There is no footage, so the selected style
     * generates the whole picture: animated background, typography, elements and audio-reactive visuals.
     */
    @Serializable
    @SerialName("audio")
    data class AudioOnly(
        override val uri: MediaUri,
        override val durationMs: Long,
        val canvas: CanvasSpec,
    ) : InputSource
}

@Serializable
data class VideoFormat(
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val rotationDegrees: Int = 0,
    val isHdr: Boolean = false,
) {
    /** Size as displayed, after applying container rotation. */
    val displayWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val displayHeight: Int get() = if (rotationDegrees % 180 == 0) height else width

    fun closestAspect(): AspectRatio = AspectRatio.closestTo(displayWidth, displayHeight)
}
