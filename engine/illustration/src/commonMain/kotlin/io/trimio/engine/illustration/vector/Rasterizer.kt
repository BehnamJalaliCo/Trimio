package io.trimio.engine.illustration.vector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Scanline coverage of flattened polygons, anti-aliased: [SUB] sub-scanlines per pixel row with exact
 * horizontal span coverage. Non-zero or even-odd winding. Strokes become filled outlines (a quad per
 * segment and a round cap/join per vertex, all wound the same way so non-zero unites them).
 */
class Rasterizer(val width: Int, val height: Int) {
    private class Edge(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val dir: Int) {
        val slope: Float = (x1 - x0) / (y1 - y0)
        fun xAt(y: Float): Float = x0 + (y - y0) * slope
    }

    /** Coverage 0..1 per pixel of [lines] (closed implicitly) under [m]; the box it touched is [touched]. */
    fun fill(lines: List<Polyline>, evenOdd: Boolean, m: Affine, out: FloatArray = FloatArray(width * height)): FloatArray {
        val edges = ArrayList<Edge>()
        for (l in lines) addEdges(l.transformed(m).points, edges)
        coverage(edges, evenOdd, out)
        return out
    }

    /** Coverage of the outline of [lines] stroked [strokeWidth] wide (already in pixels) under [m]. */
    fun stroke(lines: List<Polyline>, strokeWidth: Float, m: Affine, out: FloatArray = FloatArray(width * height)): FloatArray {
        val hw = max(strokeWidth / 2f, MIN_HALF_WIDTH)
        val edges = ArrayList<Edge>()
        for (l in lines) {
            val p = l.transformed(m).points
            val n = p.size / 2
            val count = if (l.closed) n else n - 1
            for (i in 0 until count) {
                val j = (i + 1) % n
                addEdges(quad(p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1], hw), edges)
            }
            for (i in 0 until n) addEdges(disc(p[i * 2], p[i * 2 + 1], hw), edges)
        }
        coverage(edges, false, out)
        return out
    }

    private fun addEdges(p: FloatArray, edges: MutableList<Edge>) {
        val n = p.size / 2
        if (n < 2) return
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = p[i * 2]
            val y0 = p[i * 2 + 1]
            val x1 = p[j * 2]
            val y1 = p[j * 2 + 1]
            if (y0 == y1) continue
            edges += if (y0 < y1) Edge(x0, y0, x1, y1, 1) else Edge(x1, y1, x0, y0, -1)
        }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun coverage(edgesIn: List<Edge>, evenOdd: Boolean, out: FloatArray) {
        if (edgesIn.isEmpty()) return
        val edges = edgesIn.sortedBy { it.y0 }
        val yStart = max(0, floor(edges.first().y0).toInt())
        val yEnd = min(height, ceil(edges.maxOf { it.y1 }).toInt())
        val row = FloatArray(width + 2)
        val active = ArrayList<Edge>()
        val xs = FloatArray(edges.size)
        val dirs = IntArray(edges.size)
        val order = ArrayList<Int>()
        var next = 0
        for (y in yStart until yEnd) {
            row.fill(0f)
            var touched = false
            for (s in 0 until SUB) {
                val sy = y + (s + 0.5f) / SUB
                while (next < edges.size && edges[next].y0 <= sy) active += edges[next++]
                active.removeAll { it.y1 <= sy }
                var k = 0
                for (e in active) {
                    if (e.y0 <= sy && e.y1 > sy) {
                        xs[k] = e.xAt(sy); dirs[k] = e.dir; k++
                    }
                }
                if (k < 2) continue
                order.clear()
                for (i in 0 until k) order += i
                order.sortBy { xs[it] }
                var winding = 0
                for (oi in 0 until k - 1) {
                    val i = order[oi]
                    winding += if (evenOdd) 1 else dirs[i]
                    val inside = if (evenOdd) winding % 2 != 0 else winding != 0
                    if (inside) {
                        span(row, xs[i], xs[order[oi + 1]])
                        touched = true
                    }
                }
            }
            if (!touched) continue
            val base = y * width
            for (x in 0 until width) {
                val c = row[x] / SUB
                if (c > 0f) out[base + x] = min(1f, out[base + x] + c)
            }
        }
    }

    /** Adds the horizontal coverage of [a, b) to one sub-scanline of [row]. */
    private fun span(row: FloatArray, a: Float, b: Float) {
        val x0 = a.coerceIn(0f, width.toFloat())
        val x1 = b.coerceIn(0f, width.toFloat())
        if (x1 <= x0) return
        val i0 = x0.toInt()
        val i1 = min(x1.toInt(), width - 1)
        if (i0 == i1) {
            row[i0] += x1 - x0
            return
        }
        row[i0] += (i0 + 1) - x0
        for (i in i0 + 1 until i1) row[i] += 1f
        if (i1 < width) row[i1] += x1 - i1
    }

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, hw: Float): FloatArray {
        val len = hypot(x1 - x0, y1 - y0)
        if (len < EPS) return FloatArray(0)
        val nx = -(y1 - y0) / len * hw
        val ny = (x1 - x0) / len * hw
        val p = floatArrayOf(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny)
        return if (area(p) < 0) reversed(p) else p
    }

    private fun disc(cx: Float, cy: Float, r: Float): FloatArray {
        val n = (r * DISC_DETAIL).toInt().coerceIn(MIN_DISC, MAX_DISC)
        val p = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = 2 * PI.toFloat() * i / n
            p[i * 2] = cx + r * cos(t)
            p[i * 2 + 1] = cy + r * sin(t)
        }
        return if (area(p) < 0) reversed(p) else p
    }

    private fun area(p: FloatArray): Float {
        var a = 0f
        val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }
        return a
    }

    private fun reversed(p: FloatArray): FloatArray {
        val n = p.size / 2
        return FloatArray(p.size) { k -> p[(n - 1 - k / 2) * 2 + k % 2] }
    }

    companion object {
        const val SUB = 5
        private const val EPS = 1e-4f
        private const val MIN_HALF_WIDTH = 0.35f
        private const val DISC_DETAIL = 2.5f
        private const val MIN_DISC = 6
        private const val MAX_DISC = 24

        /** True when [a] and [b] are within [eps] (tests). */
        fun near(a: Float, b: Float, eps: Float = 1e-3f): Boolean = abs(a - b) <= eps
    }
}
