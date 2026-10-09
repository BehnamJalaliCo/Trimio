package io.trimio.engine.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.style.CaptionMode
import io.trimio.core.model.timeline.Anchor
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

    /** With no footage the type is the picture: the style's audio-only layout applies. */
    private val audioOnlyCaptions by lazy {
        val c = style.captions
        val spec = style.copy(captions = c.copy(anchor = style.audioOnly.captionAnchor, size = c.size * style.audioOnly.captionScale))
        CaptionLayer(timeline.clipsOf<CaptionClip>(), spec, palette, textMeasurer, fontFamily)
    }
    private val visualizer = VisualizerLayer(style.audioOnly.visualizer, palette)
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

        if (!hasFootage) with(visualizer) { draw(t, frame.audio) }
        val captionAnchor = if (hasFootage) style.captions.anchor else style.audioOnly.captionAnchor
        val centred = style.captions.mode == CaptionMode.SingleWord || captionAnchor in CENTRE_ANCHORS
        // Keep elements clear of centred type, and of the visualiser ring around it.
        val elementsY = when {
            centred && !hasFootage && style.audioOnly.visualizer == "ring" -> 0.13f
            centred -> 0.22f
            else -> 0.4f
        }
        with(elements) { draw(t, middleY = elementsY) }
        with(if (hasFootage) captions else audioOnlyCaptions) { draw(t) }
        with(overlay) { draw(t) }
    }

    /** Footage zoom at [timeMs] (punch-in on emphasis), for platforms that composite footage themselves. */
    fun cameraZoom(timeMs: Long): Float = camera.zoomAt(timeMs)

    /** Convenience for callers outside a DrawScope receiver. */
    fun render(scope: DrawScope, frame: FrameContext) = with(scope) { drawFrame(frame) }

    companion object {
        private val CENTRE_ANCHORS = setOf(Anchor.CenterStart, Anchor.Center, Anchor.CenterEnd)

        /** Short edge of the canvas; all style sizes are fractions of it. */
        fun unit(size: Size): Float = minOf(size.width, size.height)
    }
}
