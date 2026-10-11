package io.trimio.engine.production

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.MotionRenderer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/** The platform's sound out, for the editor's player. Blocking work stays on its own thread. */
interface Speaker : AutoCloseable {
    /** Plays [audio] from [from] seconds until [pause], [close] or its end. */
    fun play(audio: PcmAudio, from: Float)

    fun pause()

    /** Seconds of the audio heard so far (the playhead) while playing; null when stopped or unknown. */
    val position: Float?

    override fun close() = pause()
}

/**
 * The editor's player for one production's edits: each frame drawn by the export's own renderer
 * ([MotionRenderer]) over the creator's footage, small and on demand, and the mastered mix through
 * the platform's [Speaker]. [show] swaps in a changed edit and keeps the footage open, so the
 * picture follows every change in the editor at once.
 */
class EditPlayback internal constructor(
    private val engines: suspend () -> Pair<TextMeasurer, MotionFonts>,
    private val shaders: Boolean,
    private val open: (Int, Int) -> MediaSource,
    private val speaker: Speaker?,
    edit: Edit,
) : AutoCloseable {
    private val lock = Mutex()
    private var current: Edit = edit
    private var renderer: MotionRenderer? = null
    private var media: MediaSource? = null
    private var mediaSize = 0 to 0
    private var closed = false

    val edit: Edit get() = current

    /** The edit being shown from now on (the same footage). */
    fun show(edit: Edit) {
        current = edit
    }

    /**
     * The frame at [t] seconds, [width] px wide, drawn as the export would draw it (no motion blur:
     * a still), into [into] when it is that size (a player alternates two buffers instead of
     * allocating a frame each time).
     */
    suspend fun frame(t: Float, width: Int, into: ImageBitmap? = null): ImageBitmap? = lock.withLock {
        if (closed) return@withLock null
        try {
            val c = current.production.compiled.composition
            val r = rendererFor(c)
            val k = width / c.width.toFloat()
            val height = (c.height * k).roundToInt().coerceAtLeast(1)
            val image = into?.takeIf { it.width == width && it.height == height } ?: ImageBitmap(width, height)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
                scale(k, k, pivot = Offset.Zero) { r.render(this, t.coerceIn(0f, c.duration)) }
            }
            image
        } finally {
            // Closed while this frame was drawn: the footage is let go now that nothing reads it.
            if (closed) release()
        }
    }

    private suspend fun rendererFor(c: Composition): MotionRenderer {
        renderer?.takeIf { it.composition.root === c.root }?.let { return it }
        val (measurer, fonts) = engines()
        val still = Composition(c.width, c.height, c.fps, c.duration, c.background, c.root, c.camera, shutterAngle = 0f, shutterSamples = 1)
        return MotionRenderer(still, measurer, fonts, mediaFor(c)).also {
            it.runtimeShaders = shaders
            renderer = it
        }
    }

    /** The footage at half the edit's size: sharp on a phone's player, light to decode. */
    private fun mediaFor(c: Composition): MediaSource {
        val size = c.width / MEDIA_DIVISOR to c.height / MEDIA_DIVISOR
        media?.takeIf { mediaSize == size }?.let { return it }
        (media as? AutoCloseable)?.close()
        mediaSize = size
        return open(size.first, size.second).also { media = it }
    }

    /** The mastered sound from [from] seconds (silent where the platform has no speaker). */
    fun play(from: Float) = speaker?.play(current.production.soundtrack.audio, from) ?: Unit

    fun pause() = speaker?.pause() ?: Unit

    /** The speaker's playhead while it plays; null when the caller keeps time itself. */
    val position: Float? get() = speaker?.position

    /** Stops the sound and lets the footage go (at once, or when the frame being drawn is done). */
    override fun close() {
        speaker?.close()
        closed = true
        if (lock.tryLock()) {
            try {
                release()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun release() {
        (media as? AutoCloseable)?.close()
        media = null
        renderer = null
    }

    private companion object {
        const val MEDIA_DIVISOR = 2
    }
}
