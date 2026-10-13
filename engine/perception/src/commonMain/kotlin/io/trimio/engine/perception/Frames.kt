package io.trimio.engine.perception

import kotlin.math.max
import kotlin.math.min

/** One decoded video frame: packed 8-bit RGB, row-major, upright. [time] is its source time in seconds. */
class Frame(val width: Int, val height: Int, val rgb: ByteArray, val time: Float = 0f) {
    init {
        require(rgb.size == width * height * CHANNELS) { "frame ${width}x$height needs ${width * height * CHANNELS} bytes, got ${rgb.size}" }
    }

    fun r(x: Int, y: Int): Int = rgb[(y * width + x) * CHANNELS].toInt() and BYTE
    fun g(x: Int, y: Int): Int = rgb[(y * width + x) * CHANNELS + 1].toInt() and BYTE
    fun b(x: Int, y: Int): Int = rgb[(y * width + x) * CHANNELS + 2].toInt() and BYTE

    /** Luma (BT.601) of one pixel, 0..255. */
    fun luma(x: Int, y: Int): Float = LR * r(x, y) + LG * g(x, y) + LB * b(x, y)

    /** A bilinear resize to [w]×[h] (the whole frame, aspect not kept). */
    fun resized(w: Int, h: Int): Frame {
        if (w == width && h == height) return this
        val out = ByteArray(w * h * CHANNELS)
        val sx = width.toFloat() / w
        val sy = height.toFloat() / h
        for (y in 0 until h) {
            val fy = ((y + HALF) * sy - HALF).coerceIn(0f, (height - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, height - 1)
            val wy = fy - y0
            for (x in 0 until w) {
                val fx = ((x + HALF) * sx - HALF).coerceIn(0f, (width - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, width - 1)
                val wx = fx - x0
                for (c in 0 until CHANNELS) {
                    val top = at(x0, y0, c) * (1 - wx) + at(x1, y0, c) * wx
                    val bottom = at(x0, y1, c) * (1 - wx) + at(x1, y1, c) * wx
                    out[(y * w + x) * CHANNELS + c] = (top * (1 - wy) + bottom * wy + HALF).toInt().coerceIn(0, BYTE).toByte()
                }
            }
        }
        return Frame(w, h, out, time)
    }

    private fun at(x: Int, y: Int, c: Int): Float = (rgb[(y * width + x) * CHANNELS + c].toInt() and BYTE).toFloat()

    companion object {
        const val CHANNELS = 3
        private const val BYTE = 0xFF
        private const val HALF = 0.5f
        private const val LR = 0.299f
        private const val LG = 0.587f
        private const val LB = 0.114f

        fun solid(width: Int, height: Int, r: Int, g: Int, b: Int, time: Float = 0f): Frame {
            val px = ByteArray(width * height * CHANNELS)
            for (i in 0 until width * height) {
                px[i * CHANNELS] = r.toByte()
                px[i * CHANNELS + 1] = g.toByte()
                px[i * CHANNELS + 2] = b.toByte()
            }
            return Frame(width, height, px, time)
        }
    }
}

/** An axis-aligned box in frame fractions (0..1 on both axes, origin top-left). */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val area: Float get() = max(0f, w) * max(0f, h)
    val centerX: Float get() = x + w / 2f
    val centerY: Float get() = y + h / 2f

    fun intersection(o: Box): Float {
        val iw = min(right, o.right) - max(x, o.x)
        val ih = min(bottom, o.bottom) - max(y, o.y)
        return if (iw <= 0f || ih <= 0f) 0f else iw * ih
    }

    fun intersects(o: Box): Boolean = intersection(o) > 0f

    fun iou(o: Box): Float {
        val i = intersection(o)
        val u = area + o.area - i
        return if (u <= 0f) 0f else i / u
    }

    /** Grown by [dx]/[dy] on each side, clipped to the frame. */
    fun padded(dx: Float, dy: Float = dx): Box = of(x - dx, y - dy, right + dx, bottom + dy)

    fun union(o: Box): Box = of(min(x, o.x), min(y, o.y), max(right, o.right), max(bottom, o.bottom))

    companion object {
        val Full = Box(0f, 0f, 1f, 1f)

        /** From corners, clipped to the frame. */
        fun of(x0: Float, y0: Float, x1: Float, y1: Float): Box {
            val a = x0.coerceIn(0f, 1f)
            val b = y0.coerceIn(0f, 1f)
            return Box(a, b, x1.coerceIn(0f, 1f) - a, y1.coerceIn(0f, 1f) - b)
        }
    }
}

/** Greedy non-maximum suppression: keeps the best-scored boxes, dropping those overlapping a kept one by more than [iou]. */
fun <T> suppress(items: List<T>, iou: Float, box: (T) -> Box, score: (T) -> Float): List<T> {
    val kept = ArrayList<T>()
    for (item in items.sortedByDescending(score)) {
        if (kept.none { box(it).iou(box(item)) > iou }) kept += item
    }
    return kept
}
