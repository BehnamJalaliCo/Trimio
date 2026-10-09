package io.trimio.engine.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.timeline.BackgroundClip
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.ElementClip
import io.trimio.core.model.timeline.Timeline

/**
 * Renderer tuning per platform.
 * [shaderScale] < 1 renders full-screen shader backgrounds at reduced resolution and upscales them:
 * invisible on soft backdrops, and essential on CPU rasterisers (desktop/web) where a 1080p aurora
 * costs ~1 s per frame. GPU platforms keep 1.
 */
data class RenderOptions(val shaderScale: Float = 1f)

/** Inputs that change per frame. */
class FrameContext(
    /** Output time of the frame. */
    val timeMs: Long,
    /** Soundtrack features in output time, for audio-reactive visuals; null when silent. */
    val audio: AudioFeatures? = null,
    /** Draws the source footage frame into the given rect (cover-fit is the caller's job), or null for audio-only. */
    val footage: (DrawScope.(Rect) -> Unit)? = null,
    /**
     * Footage exists but is composited underneath by the platform (Media3 on Android). The renderer
     * then draws only the overlay layers and leaves camera motion to [FrameRenderer.cameraZoom].
     */
    val externalFootage: Boolean = false,
)

/**
 * Draws one frame of a [Timeline] in a [StyleSpec]. Pure function of time: preview, export and
 * golden tests all call this, so what the user previews is exactly what gets exported.
 *
 * Layer order: background → footage (with camera punch) → elements → captions → overlay.
 */
class FrameRenderer(
    val timeline: Timeline,
    val style: StyleSpec,
    textMeasurer: TextMeasurer,
    fontFamily: FontFamily,
    options: RenderOptions = RenderOptions(),
) {
    private val palette = ResolvedPalette(style.palette)
    private val backgrounds = BackgroundLayer(style, palette, options)
    private val captions = CaptionLayer(timeline.clipsOf<CaptionClip>(), style, palette, textMeasurer, fontFamily)
    private val elements = ElementLayer(timeline.clipsOf<ElementClip>(), style, palette, textMeasurer, fontFamily)
    private val overlay = OverlayLayer(style.overlay, options)
    private val camera = CameraMotion(timeline, style)
    private val backgroundClips = timeline.clipsOf<BackgroundClip>()

    fun DrawScope.drawFrame(frame: FrameContext) {
        val t = frame.timeMs
        val energy = frame.audio?.energy01(t) ?: 0f
        val canvas = Rect(0f, 0f, size.width, size.height)

        val background = backgroundClips.lastOrNull { t in it.range }
        val hasFootage = frame.footage != null || frame.externalFootage
        // Opaque footage hides the style backdrop, so it is only drawn without footage, or when a
        // clip explicitly asks for a different backdrop (e.g. a picture-in-picture layout).
        val explicit = background?.preset?.takeIf { it != BackgroundClip.STYLE_PRESET }
        if (!hasFootage || explicit != null) {
            with(backgrounds) { draw(explicit ?: style.background.preset, t, energy, frame.audio) }
        }

        frame.footage?.let { drawFootage ->
            val zoom = camera.zoomAt(t)
            withTransform({ scale(zoom, zoom, canvas.center) }) { drawFootage(canvas) }
        }

        with(elements) { draw(t) }
        with(captions) { draw(t) }
        with(overlay) { draw(t) }
    }

    /** Footage zoom at [timeMs] (punch-in on emphasis), for platforms that composite footage themselves. */
    fun cameraZoom(timeMs: Long): Float = camera.zoomAt(timeMs)

    /** Convenience for callers outside a DrawScope receiver. */
    fun render(scope: DrawScope, frame: FrameContext) = with(scope) { drawFrame(frame) }

    companion object {
        /** Short edge of the canvas; all style sizes are fractions of it. */
        fun unit(size: Size): Float = minOf(size.width, size.height)
    }
}
