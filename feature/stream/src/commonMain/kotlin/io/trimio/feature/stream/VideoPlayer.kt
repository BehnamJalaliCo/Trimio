package io.trimio.feature.stream

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * A finished video file played inside the app: its picture and its sound, kept in step. The
 * platform supplies the decoding (Android: Media3 ExoPlayer; desktop: the bundled ffmpeg with the
 * sound card as the clock); the result screen draws the transport over [Picture] ([VideoStage]).
 */
interface VideoPlayer : AutoCloseable {
    val state: StateFlow<PlaybackState>

    fun play()

    fun pause()

    /** Moves the playhead to [seconds]; fast calls (a drag) are folded into the latest. */
    fun seek(seconds: Float)

    /** The picture, fitted inside [modifier]'s bounds on black. */
    @Composable
    fun Picture(modifier: Modifier)
}

/** Where a [VideoPlayer] is. Times in seconds. */
data class PlaybackState(
    val position: Float = 0f,
    val duration: Float = 0f,
    val playing: Boolean = false,
    /** The file is open and its first picture can be shown. */
    val ready: Boolean = false,
    /** Played to its end (the next play starts over). */
    val ended: Boolean = false,
    /** Why the file cannot be played; null while it can. */
    val failed: String? = null,
) {
    val progress: Float get() = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
}

/** Opens [VideoPlayer]s on the platform; a platform without one leaves the result screen on its poster. */
fun interface VideoPlayers {
    /** A player on the file or platform URI [uri], loaded but not playing. */
    fun open(uri: String): VideoPlayer
}
