package io.trimio.engine.perception.face

import io.trimio.engine.perception.Box
import io.trimio.engine.perception.Frame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A coarse "how busy is the picture here" map: per cell, the luma contrast and edge energy,
 * scaled so the frame's busiest cells are near 1. Text over a busy cell is hard to read and
 * hides what the viewer is looking at. Model-free: talking-head footage is simple enough that
 * contrast and edges find the person, props and any text already on screen.
 */
class BusyMap(val cols: Int, val rows: Int, val values: FloatArray) {
    operator fun get(col: Int, row: Int): Float = values[row * cols + col]

    /** Mean busyness under [box]. */
    fun mean(box: Box): Float {
        val c0 = (box.x * cols).toInt().coerceIn(0, cols - 1)
        val c1 = (box.right * cols).roundToInt().coerceIn(c0 + 1, cols)
        val r0 = (box.y * rows).toInt().coerceIn(0, rows - 1)
        val r1 = (box.bottom * rows).roundToInt().coerceIn(r0 + 1, rows)
        var sum = 0f
        for (r in r0 until r1) for (c in c0 until c1) sum += this[c, r]
        return sum / ((r1 - r0) * (c1 - c0))
    }

    /** The element-wise maximum with [other] (same grid): busy in any of the frames. */
    fun max(other: BusyMap): BusyMap = BusyMap(cols, rows, FloatArray(values.size) { max(values[it], other.values[it]) })

    companion object {
        /** Cells across the shorter side. */
        const val SHORT_CELLS = 18

        fun of(frame: Frame, shortCells: Int = SHORT_CELLS): BusyMap {
            val portrait = frame.height >= frame.width
            val cols = if (portrait) shortCells else (shortCells * frame.width.toFloat() / frame.height).roundToInt()
            val rows = if (portrait) (shortCells * frame.height.toFloat() / frame.width).roundToInt() else shortCells
            val raw = FloatArray(cols * rows)
            for (r in 0 until rows) for (c in 0 until cols) raw[r * cols + c] = cell(frame, c, r, cols, rows)
            val ref = raw.sortedArray()[(raw.size * TOP_SHARE).toInt().coerceAtMost(raw.size - 1)].coerceAtLeast(MIN_REF)
            return BusyMap(cols, rows, FloatArray(raw.size) { min(1f, raw[it] / ref) })
        }

        private fun cell(frame: Frame, c: Int, r: Int, cols: Int, rows: Int): Float {
            val x0 = c * frame.width / cols
            val x1 = max(x0 + 2, (c + 1) * frame.width / cols).coerceAtMost(frame.width)
            val y0 = r * frame.height / rows
            val y1 = max(y0 + 2, (r + 1) * frame.height / rows).coerceAtMost(frame.height)
            val step = max(1, (x1 - x0) / SAMPLES)
            var n = 0
            var sum = 0f
            var sq = 0f
            var edges = 0f
            var y = y0
            while (y < y1 - 1) {
                var x = x0
                while (x < x1 - 1) {
                    val l = frame.luma(x, y)
                    sum += l
                    sq += l * l
                    edges += abs(frame.luma(x + 1, y) - l) + abs(frame.luma(x, y + 1) - l)
                    n++
                    x += step
                }
                y += step
            }
            if (n == 0) return 0f
            val mean = sum / n
            val std = sqrt(max(0f, sq / n - mean * mean))
            return std + edges / n
        }

        private const val SAMPLES = 8
        private const val TOP_SHARE = 0.95f
        private const val MIN_REF = 8f
    }
}

/**
 * Faces without the face model: the largest skin-toned region in the upper part of the frame
 * (YCbCr skin range), else the usual talking-head position. Rough, but it keeps text off the
 * speaker when the add-on is not installed.
 */
object SkinFaces {
    /** Where a talking head's face usually is in an upright frame (centre, a little above the middle). */
    val Prior = Box(0.3f, 0.25f, 0.4f, 0.3f)

    fun guess(frame: Frame, cells: Int = 48): Face {
        val cols = cells
        val rows = (cells * frame.height.toFloat() / frame.width).roundToInt().coerceAtLeast(1)
        val skin = BooleanArray(cols * rows) { i -> isSkin(frame, i % cols, i / cols, cols, rows) }
        val best = largestRegion(skin, cols, rows, maxRow = (rows * UPPER).toInt())
        val box = best?.let { b -> Box.of(b[0].toFloat() / cols, b[1].toFloat() / rows, (b[2] + 1f) / cols, (b[3] + 1f) / rows) }
            ?.takeIf { it.area in MIN_AREA..MAX_AREA && it.h / it.w * frame.height / frame.width in MIN_ASPECT..MAX_ASPECT }
        return Face(box ?: Prior, emptyList(), if (box == null) 0f else GUESS)
    }

    private fun isSkin(frame: Frame, c: Int, r: Int, cols: Int, rows: Int): Boolean {
        val x = ((c + 0.5f) * frame.width / cols).toInt().coerceIn(0, frame.width - 1)
        val y = ((r + 0.5f) * frame.height / rows).toInt().coerceIn(0, frame.height - 1)
        val red = frame.r(x, y)
        val green = frame.g(x, y)
        val blue = frame.b(x, y)
        val cb = 128 - 0.168736f * red - 0.331264f * green + 0.5f * blue
        val cr = 128 + 0.5f * red - 0.418688f * green - 0.081312f * blue
        return cb in CB_MIN..CB_MAX && cr in CR_MIN..CR_MAX && red > SKIN_MIN_RED
    }

    /** The bounding cells (c0, r0, c1, r1) of the largest 4-connected true region starting above [maxRow]. */
    private fun largestRegion(mask: BooleanArray, cols: Int, rows: Int, maxRow: Int): List<Int>? {
        val seen = BooleanArray(mask.size)
        var best: List<Int>? = null
        var bestSize = 0
        for (start in mask.indices) {
            if (!mask[start] || seen[start] || start / cols > maxRow) continue
            val stack = ArrayDeque<Int>().apply { add(start) }
            seen[start] = true
            var size = 0
            var c0 = cols
            var r0 = rows
            var c1 = 0
            var r1 = 0
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                size++
                val c = i % cols
                val r = i / cols
                c0 = min(c0, c)
                c1 = max(c1, c)
                r0 = min(r0, r)
                r1 = max(r1, r)
                neighbours(i, cols, rows).filter { mask[it] && !seen[it] }.forEach { n ->
                    seen[n] = true
                    stack.add(n)
                }
            }
            if (size > bestSize) {
                bestSize = size
                best = listOf(c0, r0, c1, r1)
            }
        }
        return best
    }

    private fun neighbours(i: Int, cols: Int, rows: Int): List<Int> {
        val c = i % cols
        val r = i / cols
        return listOfNotNull(
            (i - 1).takeIf { c > 0 },
            (i + 1).takeIf { c < cols - 1 },
            (i - cols).takeIf { r > 0 },
            (i + cols).takeIf { r < rows - 1 },
        )
    }

    private const val UPPER = 0.7f
    private const val MIN_AREA = 0.004f
    private const val MAX_AREA = 0.25f
    private const val MIN_ASPECT = 0.6f
    private const val MAX_ASPECT = 2.2f
    private const val GUESS = 0.3f
    private const val CB_MIN = 77f
    private const val CB_MAX = 127f
    private const val CR_MIN = 133f
    private const val CR_MAX = 173f
    private const val SKIN_MIN_RED = 60
}
