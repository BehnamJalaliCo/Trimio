package io.trimio.engine.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.geometry.Rect
import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.timeline.Timeline

/** Playback clock for previews: drives [TimelinePreview] and, on Android, the footage player. */
class PreviewClock(initialMs: Long = 0) {
    internal val position = mutableLongStateOf(initialMs)
    var playing: Boolean = true

    /** Set while a media player drives the clock (Android footage); the preview then stops advancing it itself. */
    var drivenExternally: Boolean = false
    val positionMs: Long get() = position.longValue
    fun seekTo(ms: Long) { position.longValue = ms }
}

/**
 * Live preview of an edit with the export renderer, so what you see is what you export.
 * Loops while [clock] is playing. Footage, when present, is drawn by [footage] beneath the overlay
 * (Android passes the current player frame; audio-only previews pass null).
 */
@Composable
fun TimelinePreview(
    timeline: Timeline,
    style: StyleSpec,
    modifier: Modifier = Modifier,
    clock: PreviewClock = remember { PreviewClock() },
    audio: AudioFeatures? = null,
    footage: (DrawScope.(Rect) -> Unit)? = null,
    /** Footage is drawn underneath by the platform player: draw overlays only. */
    externalFootage: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val fonts by produceState<FontFamily?>(null) { value = RenderFonts.vazirmatn() }
    val family = fonts ?: return
    val renderer = remember(timeline, style, family) { FrameRenderer(timeline, style, measurer, family) }

    LaunchedEffect(timeline) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                if (clock.playing && !clock.drivenExternally) {
                    val next = clock.positionMs + (now - last) / 1_000_000
                    clock.seekTo(if (next >= timeline.durationMs) 0 else next)
                }
                last = now
            }
        }
    }

    Canvas(modifier) {
        // Reading the clock here keeps recomposition out of the per-frame path: only the draw phase reruns.
        renderer.render(this, FrameContext(clock.position.longValue, audio, footage, externalFootage))
    }
}
