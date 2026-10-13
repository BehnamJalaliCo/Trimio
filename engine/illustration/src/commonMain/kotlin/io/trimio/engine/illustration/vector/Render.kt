package io.trimio.engine.illustration.vector

import io.trimio.engine.illustration.Box
import io.trimio.engine.illustration.Colors
import io.trimio.engine.illustration.Raster
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A drawing painted flat: [image] (the plain colours, transparent outside), and per pixel the index of the
 * shape on top ([regions], -1 for none) so the styler knows where one painted area meets another.
 * [placement] maps drawing units to pixels.
 */
class FlatRender(val image: Raster, val regions: IntArray, val shapes: List<Shape>, val placement: Affine)

object Render {
    /**
     * Paints [drawing] into [width]×[height], fitted inside the frame with [margin] (a fraction of the
     * shorter side) on every side, centred horizontally and resting at [anchorY] (0 top … 1 bottom).
     */
    fun flat(drawing: Drawing, width: Int, height: Int, margin: Float = 0.04f, anchorY: Float = 0.5f, box: Box? = drawing.bounds): FlatRender {
        val bounds = box ?: return FlatRender(Raster(width, height), IntArray(width * height) { -1 }, drawing.shapes, Affine.Identity)
        val pad = min(width, height) * margin
        val s = min((width - 2 * pad) / bounds.width, (height - 2 * pad) / bounds.height)
        val dx = (width - bounds.width * s) / 2 - bounds.left * s
        val dy = pad + (height - 2 * pad - bounds.height * s) * anchorY - bounds.top * s
        return paint(drawing, width, height, Affine(s, 0f, 0f, s, dx, dy))
    }

    /** Paints [drawing] under the exact transform [m]. */
    fun paint(drawing: Drawing, width: Int, height: Int, m: Affine): FlatRender {
        val image = Raster(width, height)
        val regions = IntArray(width * height) { -1 }
        val raster = Rasterizer(width, height)
        val cov = FloatArray(width * height)
        drawing.shapes.forEachIndexed { index, shape ->
            val b = shape.bounds ?: return@forEachIndexed
            val pad = shape.strokeWidth * m.scale + 2
            val x0 = max(0, floor(min(m.x(b.left, b.top), m.x(b.right, b.bottom)) - pad).toInt())
            val x1 = min(width, ceil(max(m.x(b.left, b.top), m.x(b.right, b.bottom)) + pad).toInt())
            val y0 = max(0, floor(min(m.y(b.left, b.top), m.y(b.right, b.bottom)) - pad).toInt())
            val y1 = min(height, ceil(max(m.y(b.left, b.top), m.y(b.right, b.bottom)) + pad).toInt())
            if (x1 <= x0 || y1 <= y0) return@forEachIndexed
            shape.fill?.let { fill ->
                raster.fill(shape.lines, shape.evenOdd, m, cov)
                composite(image, regions, cov, fill, index, x0, x1, y0, y1)
            }
            shape.stroke?.let { stroke ->
                raster.stroke(shape.lines, shape.strokeWidth * m.scale, m, cov)
                composite(image, regions, cov, stroke, index, x0, x1, y0, y1)
            }
        }
        return FlatRender(image, regions, drawing.shapes, m)
    }

    @Suppress("LongParameterList")
    private fun composite(image: Raster, regions: IntArray, cov: FloatArray, color: Int, index: Int, x0: Int, x1: Int, y0: Int, y1: Int) {
        val w = image.width
        val alpha = Colors.a(color) / OPAQUE
        // The rasterizer may touch whole rows; clear the full rows of the box afterwards.
        for (y in y0 until y1) {
            val base = y * w
            for (x in 0 until w) {
                val c = cov[base + x]
                if (c <= 0f) continue
                cov[base + x] = 0f
                image.argb[base + x] = Colors.over(color, image.argb[base + x], c)
                if (c * alpha >= REGION_COVERAGE) regions[base + x] = index
            }
        }
    }

    private const val OPAQUE = 255f
    private const val REGION_COVERAGE = 0.5f
}
