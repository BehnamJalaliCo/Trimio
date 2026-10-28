package io.trimio.engine.motion

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.pow

/** The light behind a piece of text: its darkest, typical and brightest parts (relative luminance, linear 0..1). */
internal data class Tone(val dark: Float, val mid: Float, val light: Float)

/**
 * What the footage looks like behind any frame-space rectangle, read from the very frame the
 * renderer has just drawn (no extra decoding, so it follows the picture as it moves), with the
 * compiler's scrims over it. Text guards ask it how bright their background is.
 */
internal class Backdrop {
    private var image: ImageBitmap? = null
    private var gridW = 0
    private var gridH = 0
    private var grid = FloatArray(0)
    private var row = IntArray(0)

    /** Frame space → the media box, and how the image covers that box. */
    private var toBox: M3? = null
    private var offsetX = 0f
    private var offsetY = 0f
    private var scale = 1f
    private val scrims = mutableListOf<Scrim>()

    private class Scrim(val fromBottom: Boolean, val top: Float, val height: Float, val alpha: Float)

    /** A new frame: forget the last frame's footage and scrims. */
    fun reset() {
        toBox = null
        scrims.clear()
    }

    /** Footage drawn into [box] (local) placed in the frame by [screen], covering it as [fit] says. */
    fun footage(frame: ImageBitmap, screen: M3, box: Size, fit: Fit) {
        val inverse = screen.inverse() ?: return
        val iw = frame.width.toFloat()
        val ih = frame.height.toFloat()
        scale = if (fit == Fit.Cover) maxOf(box.width / iw, box.height / ih) else minOf(box.width / iw, box.height / ih)
        offsetX = (box.width - iw * scale) / 2f
        offsetY = (box.height - ih * scale) / 2f
        toBox = inverse
        if (frame !== image) index(frame)
    }

    /** A scrim (dark ramp from the top or bottom edge) drawn over the footage in a frame [frameHeight] tall. */
    fun scrim(fromBottom: Boolean, coverage: Float, alpha: Float, frameHeight: Float) {
        if (alpha <= 0f) return
        val h = frameHeight * coverage
        scrims += Scrim(fromBottom, if (fromBottom) frameHeight - h else 0f, h, alpha)
    }

    /** The tone behind [rect] (frame space), or null when no footage is behind it. */
    fun tone(rect: Rect): Tone? {
        val m = toBox ?: return null
        val samples = ArrayList<Float>(SAMPLES_X * SAMPLES_Y)
        for (j in 0 until SAMPLES_Y) for (i in 0 until SAMPLES_X) {
            val x = rect.left + rect.width * (i + 0.5f) / SAMPLES_X
            val y = rect.top + rect.height * (j + 0.5f) / SAMPLES_Y
            val local = m.map(x, y)
            val gx = (((local.x - offsetX) / scale) / STEP).toInt()
            val gy = (((local.y - offsetY) / scale) / STEP).toInt()
            if (gx !in 0 until gridW || gy !in 0 until gridH) continue
            samples += darkened(grid[gy * gridW + gx], y)
        }
        if (samples.isEmpty()) return null
        samples.sort()
        fun at(q: Float) = samples[((samples.size - 1) * q).toInt()]
        return Tone(at(0.1f), at(0.5f), at(0.9f))
    }

    /** Luminance under the scrims at frame height [y] (scrims are black ramps blended in sRGB). */
    private fun darkened(linear: Float, y: Float): Float {
        var v = Contrast.encode(linear)
        for (s in scrims) {
            val p = ((y - s.top) / s.height).coerceIn(0f, 1f).let { if (s.fromBottom) 1f - it else it }
            if (y < s.top || y > s.top + s.height) continue
            // Ramp: full at the edge, 0.55 at the middle, clear at the far end.
            val a = s.alpha * if (p < 0.5f) 1f - 0.9f * p else 0.55f * (1f - (p - 0.5f) * 2f)
            v *= 1f - a
        }
        return Contrast.decode(v)
    }

    /** A coarse luminance grid of [frame] (every [STEP] px), kept until the frame changes. */
    private fun index(frame: ImageBitmap) {
        image = frame
        gridW = (frame.width + STEP - 1) / STEP
        gridH = (frame.height + STEP - 1) / STEP
        if (grid.size != gridW * gridH) grid = FloatArray(gridW * gridH)
        if (row.size != frame.width) row = IntArray(frame.width)
        for (gy in 0 until gridH) {
            frame.readPixels(row, startX = 0, startY = minOf(gy * STEP, frame.height - 1), width = frame.width, height = 1)
            for (gx in 0 until gridW) {
                val c = row[minOf(gx * STEP, frame.width - 1)]
                grid[gy * gridW + gx] = Contrast.luminance((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f)
            }
        }
    }

    private companion object {
        const val STEP = 6
        const val SAMPLES_X = 14
        const val SAMPLES_Y = 6
    }
}

/** WCAG-style contrast arithmetic, and how much guard a piece of ink needs. */
internal object Contrast {
    fun decode(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    fun encode(l: Float) = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f

    fun luminance(r: Float, g: Float, b: Float) = 0.2126f * decode(r) + 0.7152f * decode(g) + 0.0722f * decode(b)
    fun luminance(c: Color) = luminance(c.red, c.green, c.blue)

    fun ratio(a: Float, b: Float) = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

    /**
     * The guard tone (opposite the ink) and its strength 0..1: the least that lifts the contrast
     * between [ink] and the worst part of [tone] (the brightest for light ink, the darkest for
     * dark ink) to the guard's floor, with a margin for what antialiasing and motion eat.
     */
    fun guard(ink: Color, tone: Tone, guard: Guard): Pair<Color, Float> {
        val li = luminance(ink)
        val dark = luminance(guard.dark)
        val light = luminance(guard.light)
        val lightInk = ratio(li, dark) >= ratio(li, light)
        val against = if (lightInk) guard.dark else guard.light
        val bg = if (lightInk) tone.light else tone.dark
        val gl = if (lightInk) dark else light
        val target = guard.floor * MARGIN
        if (ratio(li, bg) >= target) return against to 0f
        // The picture mixes in linear light (the F16 composite): the guard lands on that same curve.
        fun reached(s: Float) = ratio(li, bg + (gl - bg) * s) >= target
        if (!reached(1f)) return against to 1f
        var lo = 0f
        var hi = 1f
        repeat(BISECT) { val m = (lo + hi) / 2f; if (reached(m)) hi = m else lo = m }
        return against to hi
    }

    private const val MARGIN = 1.15f
    private const val BISECT = 14
}
