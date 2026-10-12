package io.trimio.shared.player

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import io.trimio.feature.stream.PlaybackState
import io.trimio.feature.stream.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The phone's in-app player for a finished MP4: Media3 ExoPlayer (hardware decoding, audio focus,
 * the sound as the clock) drawing into a TextureView, so the picture clips to the result screen's
 * rounded frame and moves into full screen with the same player. Made and used on the main thread.
 */
class ExoVideoPlayer(context: Context, uri: String) : VideoPlayer {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val aspect = MutableStateFlow<Float?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setMediaItem(MediaItem.fromUri(uriOf(uri)))
        addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        aspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.update { it.copy(failed = error.errorCodeName, playing = false) }
                }
            },
        )
        prepare()
    }

    init {
        // The playhead while playing (the player has no position callback).
        scope.launch {
            while (isActive) {
                if (player.isPlaying) sync()
                delay(TICK_MS)
            }
        }
    }

    private fun sync() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / MS } ?: 0f
        val ended = player.playbackState == Player.STATE_ENDED
        _state.update {
            it.copy(
                position = if (ended) duration else player.currentPosition / MS,
                duration = duration,
                playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
                ready = player.playbackState == Player.STATE_READY || ended,
                ended = ended,
            )
        }
    }

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
        sync()
    }

    override fun pause() {
        player.pause()
        sync()
    }

    override fun seek(seconds: Float) {
        player.seekTo((seconds * MS).toLong())
        sync()
    }

    @Composable
    override fun Picture(modifier: Modifier) {
        val ratio by aspect.collectAsState()
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            val view = remember { mutableListOf<TextureView>() }
            AndroidView(
                factory = { c -> TextureView(c).also { player.setVideoTextureView(it); view += it } },
                modifier = ratio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
            )
            DisposableEffect(Unit) {
                onDispose { view.forEach(player::clearVideoTextureView) }
            }
        }
    }

    override fun close() {
        scope.cancel()
        player.release()
    }

    private companion object {
        const val MS = 1000f
        const val TICK_MS = 100L

        /** A path (the app's renders) or a content/file URI. */
        fun uriOf(uri: String): Uri = if ("://" in uri || uri.startsWith("content:")) Uri.parse(uri) else Uri.fromFile(File(uri))
    }
}
