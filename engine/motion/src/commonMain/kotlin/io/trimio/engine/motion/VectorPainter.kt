package io.trimio.engine.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import io.trimio.engine.motion.visual.Paint
import io.trimio.engine.motion.visual.VectorIcon
import io.trimio.engine.motion.visual.VectorLayer

/** Draws [VectorNode]s: solid and gradient layers, strokes, and the layer-by-layer assemble. */
internal class VectorPainter {
    private class Prepared(val path: Path, val gradientPath: Path?, val brush: Brush?, val matrix: M3?)

    private val cache = HashMap<VectorIcon, List<Prepared?>>()

    private fun prepare(icon: VectorIcon): List<Prepared?> = cache.getOrPut(icon) {
        icon.layers.map { layer -> runCatching { prepare(layer) }.getOrNull() }
    }

    private fun prepare(layer: VectorLayer): Prepared {
        val path = PathParser().parsePathString(layer.d).toPath().apply { if (layer.evenOdd) fillType = PathFillType.EvenOdd }
        val g = layer.paint as? Paint.Gradient ?: return Prepared(path, null, null, null)
        // The gradient lives in its own space: draw there, with the path mapped back into it.
        val m = g.matrix
        val toIcon = M3(floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f))
        val inverse = toIcon.inverse() ?: return Prepared(path, null, null, null)
        val local = Path().apply {
            addPath(path)
            transform(inverse.toCompose())
            if (layer.evenOdd) fillType = PathFillType.EvenOdd
        }
        val stops = g.stops.map { it.first.coerceIn(0f, 1f) to it.second }.toTypedArray()
        val c = g.coords
        val brush = if (g.linear) {
            Brush.linearGradient(*stops, start = Offset(c[0], c[1]), end = Offset(c[2], c[3]))
        } else {
            Brush.radialGradient(*stops, center = Offset(c[0], c[1]), radius = c[2].coerceAtLeast(0.001f))
        }
        return Prepared(path, local, brush, toIcon)
    }

    fun draw(scope: DrawScope, node: VectorNode, t: Float, alpha: Float) = with(scope) {
        val icon = node.icon
        val k = node.width / icon.width
        val tint = node.tint.at(t)
        val assemble = node.assemble.at(t).coerceIn(0f, 1f)
        val prepared = prepare(icon)
        val n = icon.layers.size
        val cx = icon.width / 2f
        val cy = icon.height / 2f
        withTransform({ scale(k, k, Offset.Zero) }) {
            for ((i, layer) in icon.layers.withIndex()) {
                val p = prepared[i]
                // Layer i pops in over its own slice of the assemble.
                val start = if (n <= 1) 0f else i.toFloat() / n * ASSEMBLE_SPREAD
                val local = ((assemble - start) / (1f - ASSEMBLE_SPREAD + 1e-3f)).coerceIn(0f, 1f)
                if (p == null || local <= 0f) continue
                val pop = Easing.BackOut(1.6f).at(local)
                val a = alpha * layer.opacity * Easing.ExpoOut.at(local).coerceIn(0f, 1f)
                val s = 0.6f + 0.4f * pop
                val m = if (local >= 1f) M3.IDENTITY else M3.translate(cx, cy) * M3.scale(s, s) * M3.translate(-cx, -cy)
                withM(m) { drawLayer(layer, p, tint, a) }
            }
        }
    }

    private fun DrawScope.drawLayer(layer: VectorLayer, p: Prepared, tint: androidx.compose.ui.graphics.Color, alpha: Float) {
        val style = if (layer.strokeWidth > 0f) Stroke(layer.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round) else Fill
        when (val paint = layer.paint) {
            is Paint.Solid -> drawPath(p.path, paint.color, alpha = alpha, style = style)
            Paint.Current -> drawPath(p.path, tint, alpha = alpha, style = style)
            is Paint.Gradient -> {
                val brush = p.brush
                val local = p.gradientPath
                val m = p.matrix
                if (brush != null && local != null && m != null) {
                    withM(m) { drawPath(local, brush, alpha = alpha, style = style) }
                } else {
                    drawPath(p.path, paint.stops.lastOrNull()?.second ?: tint, alpha = alpha, style = style)
                }
            }
        }
    }

    private inline fun DrawScope.withM(m: M3, block: DrawScope.() -> Unit) {
        if (m.isIdentity) block() else withTransform({ transform(m.toCompose()) }, block)
    }

    private companion object {
        /** How much of the assemble is spent staggering layers (the rest lets the last one land). */
        const val ASSEMBLE_SPREAD = 0.6f
    }
}
