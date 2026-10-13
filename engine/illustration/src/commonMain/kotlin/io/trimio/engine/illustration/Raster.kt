package io.trimio.engine.illustration

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A picture in memory: [argb] packed 0xAARRGGBB, straight (not premultiplied) alpha, row-major. Every
 * illustration source answers with one, so the stage turns it into its own bitmap type the same way
 * whatever made it ([toImageBitmap]).
 */
class Raster(val width: Int, val height: Int, val argb: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "empty raster ${width}x$height" }
        require(argb.size == width * height) { "raster ${width}x$height needs ${width * height} pixels, got ${argb.size}" }
    }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    operator fun set(x: Int, y: Int, c: Int) {
        argb[y * width + x] = c
    }

    fun alpha(x: Int, y: Int): Int = argb[y * width + x] ushr SHIFT_A

    fun copy(): Raster = Raster(width, height, argb.copyOf())

    /** The smallest box holding every pixel with alpha above [threshold], or null when all are clear. */
    fun opaqueBounds(threshold: Int = 8): Box? {
        var x0 = width
        var y0 = height
        var x1 = -1
        var y1 = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (argb[y * width + x] ushr SHIFT_A > threshold) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        return if (x1 < 0) null else Box(x0.toFloat(), y0.toFloat(), (x1 + 1).toFloat(), (y1 + 1).toFloat())
    }

    /** A bilinear resize to [w]×[h] in premultiplied space (edges do not darken). */
    fun resized(w: Int, h: Int): Raster {
        if (w == width && h == height) return copy()
        val out = IntArray(w * h)
        // Box-filter while shrinking a lot, so fine hatching does not alias.
        var src = this
        while (src.width > w * 2 && src.height > h * 2) src = src.halved()
        val fx = src.width.toFloat() / w
        val fy = src.height.toFloat() / h
        for (y in 0 until h) {
            val py = ((y + HALF) * fy - HALF).coerceIn(0f, (src.height - 1).toFloat())
            val y0 = py.toInt()
            val y1 = min(y0 + 1, src.height - 1)
            val wy = py - y0
            for (x in 0 until w) {
                val px = ((x + HALF) * fx - HALF).coerceIn(0f, (src.width - 1).toFloat())
                val x0 = px.toInt()
                val x1 = min(x0 + 1, src.width - 1)
                val wx = px - x0
                out[y * w + x] = Colors.mix4(src[x0, y0], src[x1, y0], src[x0, y1], src[x1, y1], wx, wy)
            }
        }
        return Raster(w, h, out)
    }

    /** Half size by 2×2 averaging (premultiplied), repeated by [resized] until close to the target. */
    private fun halved(): Raster {
        val w = max(1, width / 2)
        val h = max(1, height / 2)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val x2 = min(x * 2 + 1, width - 1)
                val y2 = min(y * 2 + 1, height - 1)
                out[y * w + x] = Colors.mix4(this[x * 2, y * 2], this[x2, y * 2], this[x * 2, y2], this[x2, y2], HALF, HALF)
            }
        }
        return Raster(w, h, out)
    }

    /** [box] (clamped to the picture) as its own raster. */
    fun cropped(box: Box): Raster {
        val x0 = box.left.toInt().coerceIn(0, width - 1)
        val y0 = box.top.toInt().coerceIn(0, height - 1)
        val x1 = box.right.roundToInt().coerceIn(x0 + 1, width)
        val y1 = box.bottom.roundToInt().coerceIn(y0 + 1, height)
        val w = x1 - x0
        val out = IntArray(w * (y1 - y0))
        for (y in y0 until y1) argb.copyInto(out, (y - y0) * w, y * width + x0, y * width + x1)
        return Raster(w, y1 - y0, out)
    }

    /** Draws [top] over this picture with its top-left corner at ([dx], [dy]) (source-over, straight alpha). */
    fun draw(top: Raster, dx: Int, dy: Int, opacity: Float = 1f) {
        for (y in max(0, dy) until min(height, dy + top.height)) {
            for (x in max(0, dx) until min(width, dx + top.width)) {
                val i = y * width + x
                argb[i] = Colors.over(top[x - dx, y - dy], argb[i], opacity)
            }
        }
    }

    companion object {
        const val SHIFT_A = 24
        private const val HALF = 0.5f

        fun filled(width: Int, height: Int, argb: Int): Raster = Raster(width, height, IntArray(width * height) { argb })
    }
}

/** An axis-aligned box in pixels (or user units): [left], [top] inclusive, [right], [bottom] exclusive. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun union(o: Box): Box = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    fun scaled(s: Float, dx: Float = 0f, dy: Float = 0f): Box = Box(left * s + dx, top * s + dy, right * s + dx, bottom * s + dy)

    /** The box grown by [m] on every side. */
    fun inflated(m: Float): Box = Box(left - m, top - m, right + m, bottom + m)

    companion object {
        val Empty = Box(0f, 0f, 0f, 0f)
    }
}
