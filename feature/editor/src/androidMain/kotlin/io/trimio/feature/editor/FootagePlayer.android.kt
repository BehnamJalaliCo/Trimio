package io.trimio.feature.editor

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.timeline.EditMap
import io.trimio.core.model.timeline.Timeline
import io.trimio.engine.render.PreviewClock
import kotlin.math.abs

/**
 * ExoPlayer is the master clock while playing: its source position is mapped through the edit's
 * cuts to output time, and when playback enters a cut it jumps to the next kept segment, so the
 * preview plays exactly the edited result. While paused, the clock (scrubbing) drives the player.
 */
@OptIn(UnstableApi::class)
@Composable
actual fun FootagePlayer(input: InputSource, timeline: Timeline, clock: PreviewClock, modifier: Modifier): Boolean {
    val context = LocalContext.current
    val edit = remember(timeline, input.durationMs) { EditMap.of(timeline, input.durationMs) }
    val player = remember(input.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(input.uri.value))
            prepare()
        }
    }
    DisposableEffect(player) {
        clock.drivenExternally = true
        onDispose {
            clock.drivenExternally = false
            player.release()
        }
    }
    LaunchedEffect(player, edit) {
        while (true) {
            withFrameNanos { }
            val position = player.currentPosition
            if (clock.playing) {
                if (!player.isPlaying) player.play()
                val out = edit.toOutput(position)
                when {
                    out != null -> clock.seekTo(out)
                    else -> {
                        val next = edit.kept.firstOrNull { it.startMs > position }
                        if (next != null) player.seekTo(next.startMs) else {
                            player.seekTo(edit.kept.firstOrNull()?.startMs ?: 0)
                            clock.seekTo(0)
                        }
                    }
                }
            } else {
                if (player.isPlaying) player.pause()
                val wanted = edit.toSource(clock.positionMs)
                if (abs(position - wanted) > 40) player.seekTo(wanted)
            }
        }
    }
    if (input !is InputSource.Video) return false
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
            }
        },
        modifier = modifier,
    )
    return true
}
