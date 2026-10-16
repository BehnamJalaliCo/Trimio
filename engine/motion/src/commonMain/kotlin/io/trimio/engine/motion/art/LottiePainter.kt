package io.trimio.engine.motion.art

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import io.trimio.engine.motion.M3
import kotlin.math.hypot

/**
 * Draws one frame of a [LottieAnimation] with Compose graphics. Stateless between calls: every
 * path is built from the document at the frame asked, so frames can be drawn in any order and
 * from several threads.
 *
 * Shape groups follow After Effects' stack: a fill or stroke paints every path above it in its
 * group (paths of nested groups included, in the group's space); trim paths cut every path above
 * them, nested ones too; items lower in the list draw underneath.
 */
internal class LottiePainter(
    private val animation: LottieAnimation,
    private val paint: ArtPaint,
    private val colourful: Boolean,
    private val slots: List<TemplateSlot> = emptyList(),
) {

    fun draw(scope: DrawScope, frame: Float) = with(scope) {
        val w = animation.width
        val h = animation.height
        clipRect(0f, 0f, w, h) {
            val m = if (paint.mirror) M3.translate(w, 0f) * M3.scale(-1f, 1f) else M3.IDENTITY
            withM(m) { drawComp(animation.root, frame, paint.alpha.coerceIn(0f, 1f)) }
        }
    }

    // ------------------------------------------------------------------ layers

    private fun DrawScope.drawComp(comp: LComp, frame: Float, alpha: Float) {
        val worlds = HashMap<LLayer, M3>()
        for (idx in comp.layers.indices.reversed()) drawEntry(comp, idx, frame, alpha, worlds)
    }

    /** Layer [idx] of [comp], through its track matte when it has one. */
    private fun DrawScope.drawEntry(comp: LComp, idx: Int, frame: Float, alpha: Float, worlds: HashMap<LLayer, M3>) {
        val l = comp.layers[idx]
        if (l.hidden || l.matte.isSource || !l.live(frame)) return
        val matte = if (l.matte.mode == 0) null else l.matte.source?.let { comp.byIndex[it] } ?: comp.layers.getOrNull(idx - 1)?.takeIf { it.matte.isSource }
        if (matte == null) return drawLayer(comp, l, frame, alpha, worlds)
        layer(Paint()) {
            drawLayer(comp, l, frame, alpha, worlds)
            layer(mattePaint(l.matte.mode)) {
                if (matte.live(frame)) drawLayer(comp, matte, frame, 1f, worlds)
            }
        }
    }

    private fun LLayer.live(frame: Float) = frame >= inPoint && frame < outPoint

    private fun mattePaint(mode: Int) = Paint().apply {
        blendMode = if (mode == 2) BlendMode.DstOut else BlendMode.DstIn
        if (mode >= 3) colorFilter = lumaToAlpha(inverted = mode == 4)
    }

    /** The layer's matrix in its composition: its parents' chain, each at its own time. */
    private fun world(comp: LComp, l: LLayer, frame: Float, cache: HashMap<LLayer, M3>, depth: Int = 0): M3 = cache.getOrPut(l) {
        val own = l.transform.matrix(frame - l.startTime)
        val parent = l.parent?.let { comp.byIndex[it] }?.takeIf { depth < MAX_PARENTS && it !== l }
        if (parent == null) own else world(comp, parent, frame, cache, depth + 1) * own
    }

    private fun DrawScope.drawLayer(comp: LComp, l: LLayer, frame: Float, alpha: Float, worlds: HashMap<LLayer, M3>) {
        val local = frame - l.startTime
        val opacity = alpha * l.transform.opacity(local)
        if (opacity <= MIN_ALPHA) return
        withM(world(comp, l, frame, worlds)) {
            val own = l.masks.isNotEmpty() || l.blend != 0
            if (!own) {
                drawContent(l, local, opacity)
                return@withM
            }
            layer(Paint().apply { blendMode = blendOf(l.blend) }) {
                drawContent(l, local, opacity)
                if (l.masks.isNotEmpty()) applyMasks(l.masks, local)
            }
        }
    }

    private fun DrawScope.drawContent(l: LLayer, local: Float, opacity: Float) {
        when (val c = l.content) {
            is LContent.Shapes -> drawOps(collect(c.items, local, emptyList()).ops, local, opacity)
            is LContent.Solid -> drawRect(colour(c.color), Offset.Zero, Size(c.width, c.height), alpha = opacity)
            is LContent.Precomp -> {
                val inner = c.timeRemap?.let { it.at(local)[0] * animation.frameRate } ?: (local / l.stretch)
                val comp = animation.assets[c.ref] ?: return
                clipRect(0f, 0f, c.width, c.height) {
                    // A fading precomposition fades as one picture, not part by part.
                    if (opacity < OPAQUE) layer(Paint().apply { alpha = opacity }) { drawComp(comp, inner, 1f) } else drawComp(comp, inner, 1f)
                }
            }
            is LContent.Text -> paint.text?.let { drawer ->
                val slot = slots.getOrNull(c.slot) ?: return@let
                drawer.draw(this, slot, { i, n -> c.doc.wordState(local, i, n) }, opacity)
            }
            LContent.Empty -> Unit
        }
    }

    /** Masks in order: add, subtract, intersect or difference, each at its opacity, possibly inverted. */
    private fun DrawScope.applyMasks(masks: List<LMask>, local: Float) {
        layer(Paint().apply { blendMode = BlendMode.DstIn }) {
            val first = masks.firstOrNull { it.mode != 'n' } ?: return@layer drawRect(Color.White, HUGE.topLeft, HUGE.size)
            if (first.mode == 's' || first.mode == 'i') drawRect(Color.White, HUGE.topLeft, HUGE.size)
            for (mask in masks) {
                val shape = path(mask.path.at(local))
                val p = if (mask.inverted) Path().apply { op(Path().apply { addRect(HUGE) }, shape, PathOperation.Difference) } else shape
                val a = (mask.opacity.at(local)[0] / 100f).coerceIn(0f, 1f)
                when (mask.mode) {
                    's' -> drawPath(p, Color.White, alpha = a, blendMode = BlendMode.DstOut)
                    'i' -> layer(Paint().apply { blendMode = BlendMode.DstIn }) { drawPath(p, Color.White, alpha = a) }
                    'f' -> drawPath(p, Color.White, alpha = a, blendMode = BlendMode.Xor)
                    'n' -> Unit
                    else -> drawPath(p, Color.White, alpha = a)
                }
            }
        }
    }

    // ------------------------------------------------------------------ shapes

    /** What a shape group draws ([ops], in list order) and the paths it offers paints above it, in its parent's space. */
    private class Collected(val ops: List<Op>, val paths: List<Path>)

    private sealed interface Op {
        class Paint(val item: LShape, val paths: List<Path>) : Op
        class Sub(val matrix: M3, val opacity: Float, val ops: List<Op>) : Op
    }

    /** Walks a group's items in order; [trims] are the trim paths of enclosing groups that cut this group's paths. */
    private fun collect(items: List<LShape>, frame: Float, trims: List<LShape.Trim>): Collected {
        val ops = mutableListOf<Op>()
        val paths = mutableListOf<Path>()
        for ((j, item) in items.withIndex()) {
            val cutting by lazy { items.subList(j + 1, items.size).filterIsInstance<LShape.Trim>() + trims }
            when (item) {
                is LShape.Path, is LShape.Rect, is LShape.Ellipse -> paths += trimmed(contour(item, frame), cutting, frame)
                is LShape.Group -> {
                    val sub = collect(item.items, frame, cutting)
                    val m = item.transform?.matrix(frame) ?: M3.IDENTITY
                    ops += Op.Sub(m, item.transform?.opacity(frame) ?: 1f, sub.ops)
                    val compose = m.toCompose()
                    paths += sub.paths.map { p -> Path().apply { addPath(p); if (!m.isIdentity) transform(compose) } }
                }
                is LShape.Fill, is LShape.Stroke, is LShape.GradientFill, is LShape.GradientStroke -> ops += Op.Paint(item, paths.toList())
                is LShape.Trim -> Unit
            }
        }
        return Collected(ops, paths)
    }

    private fun DrawScope.drawOps(ops: List<Op>, frame: Float, alpha: Float) {
        for (op in ops.asReversed()) {
            when (op) {
                is Op.Sub -> if (op.opacity * alpha > MIN_ALPHA) withM(op.matrix) { drawOps(op.ops, frame, alpha * op.opacity) }
                is Op.Paint -> if (op.paths.isNotEmpty()) drawPaint(op.item, op.paths, frame, alpha)
            }
        }
    }

    private fun DrawScope.drawPaint(item: LShape, paths: List<Path>, frame: Float, alpha: Float) {
        val evenOdd = (item as? LShape.Fill)?.evenOdd ?: (item as? LShape.GradientFill)?.evenOdd ?: false
        val path = if (paths.size == 1 && !evenOdd) paths[0] else Path().apply {
            paths.forEach { addPath(it) }
            if (evenOdd) fillType = PathFillType.EvenOdd
        }
        when (item) {
            is LShape.Fill -> drawPath(path, colour(item.color.at(frame)), alpha = alpha * pct(item.opacity, frame), style = Fill)
            is LShape.Stroke -> drawPath(path, colour(item.color.at(frame)), alpha = alpha * pct(item.opacity, frame), style = stroke(item.line, frame))
            is LShape.GradientFill -> gradient(item.gradient, frame)?.let { drawPath(path, it, alpha = alpha * pct(item.opacity, frame), style = Fill) }
            is LShape.GradientStroke -> gradient(item.gradient, frame)?.let { drawPath(path, it, alpha = alpha * pct(item.opacity, frame), style = stroke(item.line, frame)) }
            else -> Unit
        }
    }

    private fun pct(v: Value<FloatArray>, frame: Float) = (v.at(frame)[0] / 100f).coerceIn(0f, 1f)

    private fun stroke(line: LShape.Line, frame: Float): Stroke {
        val intervals = line.dashes.map { it.at(frame)[0].coerceAtLeast(0.01f) }
        val effect = if (intervals.isEmpty()) null else {
            val even = if (intervals.size % 2 == 0) intervals else intervals + intervals
            PathEffect.dashPathEffect(even.toFloatArray(), line.dashOffset?.at(frame)?.get(0) ?: 0f)
        }
        return Stroke(
            width = line.width.at(frame)[0],
            miter = line.miter,
            cap = when (line.cap) { 1 -> StrokeCap.Butt; 3 -> StrokeCap.Square; else -> StrokeCap.Round },
            join = when (line.join) { 1 -> StrokeJoin.Miter; 3 -> StrokeJoin.Bevel; else -> StrokeJoin.Round },
            pathEffect = effect,
        )
    }

    private fun gradient(g: LShape.Gradient, frame: Float): Brush? {
        val v = g.colors.at(frame)
        val n = g.stops
        if (n < 2 || v.size < n * 4) return null
        val alphas = (n * 4 until v.size - 1 step 2).map { k -> v[k] to v[k + 1] }
        var last = -1f
        val stops = (0 until n).map { k ->
            val at = v[k * 4].coerceIn(last.coerceAtLeast(0f), 1f).also { last = it }
            val a = if (alphas.isEmpty()) 1f else alphaAt(alphas, at)
            at to colour(floatArrayOf(v[k * 4 + 1], v[k * 4 + 2], v[k * 4 + 3], a))
        }.toTypedArray()
        val s = g.start.at(frame)
        val e = g.end.at(frame)
        val start = Offset(s[0], s.getOrElse(1) { 0f })
        val end = Offset(e[0], e.getOrElse(1) { 0f })
        return if (g.kind == 2) {
            Brush.radialGradient(*stops, center = start, radius = hypot(end.x - start.x, end.y - start.y).coerceAtLeast(0.01f))
        } else {
            Brush.linearGradient(*stops, start = start, end = end)
        }
    }

    private fun alphaAt(stops: List<Pair<Float, Float>>, at: Float): Float {
        if (at <= stops.first().first) return stops.first().second
        if (at >= stops.last().first) return stops.last().second
        val k = stops.indexOfFirst { it.first >= at }
        val (p0, a0) = stops[k - 1]
        val (p1, a1) = stops[k]
        return if (p1 <= p0) a1 else a0 + (a1 - a0) * (at - p0) / (p1 - p0)
    }

    // ------------------------------------------------------------------ paths

    private fun contour(item: LShape, frame: Float): Path = when (item) {
        is LShape.Path -> path(item.shape.at(frame))
        is LShape.Rect -> Path().apply {
            val p = item.position.at(frame)
            val s = item.size.at(frame)
            val r = item.roundness.at(frame)[0].coerceAtMost(minOf(s[0], s.getOrElse(1) { s[0] }) / 2f)
            val left = p[0] - s[0] / 2f
            val top = p.getOrElse(1) { 0f } - s.getOrElse(1) { s[0] } / 2f
            addRoundRect(RoundRect(left, top, left + s[0], top + s.getOrElse(1) { s[0] }, CornerRadius(r, r)))
        }
        is LShape.Ellipse -> Path().apply {
            val p = item.position.at(frame)
            val s = item.size.at(frame)
            addOval(Rect(Offset(p[0] - s[0] / 2f, p.getOrElse(1) { 0f } - s.getOrElse(1) { s[0] } / 2f), Size(s[0], s.getOrElse(1) { s[0] })))
        }
        else -> Path()
    }

    private fun path(d: ShapeData): Path = Path().apply {
        val n = d.points
        if (n == 0) return@apply
        val v = d.v
        moveTo(v[0], v[1])
        for (k in 1 until n) segment(d, k - 1, k)
        if (d.closed) {
            segment(d, n - 1, 0)
            close()
        }
    }

    private fun Path.segment(d: ShapeData, a: Int, b: Int) {
        val v = d.v
        cubicTo(v[a * 2] + d.o[a * 2], v[a * 2 + 1] + d.o[a * 2 + 1], v[b * 2] + d.i[b * 2], v[b * 2 + 1] + d.i[b * 2 + 1], v[b * 2], v[b * 2 + 1])
    }

    /** [p] cut by every trim in [trims] (innermost first); each contour is trimmed on its own length. */
    private fun trimmed(p: Path, trims: List<LShape.Trim>, frame: Float): Path {
        var out = p
        for (t in trims) {
            val s = t.start.at(frame)[0] / 100f
            val e = t.end.at(frame)[0] / 100f
            val o = t.offset.at(frame)[0] / 360f
            out = trim(out, minOf(s, e) + o, maxOf(s, e) + o)
        }
        return out
    }

    private fun trim(p: Path, from: Float, to: Float): Path {
        if (to - from >= 1f - 1e-4f) return p
        if (to - from <= 1e-4f) return Path()
        val measure = PathMeasure()
        measure.setPath(p, false)
        val len = measure.length
        if (len <= 0f) return p
        val a = from - kotlin.math.floor(from)
        val b = a + (to - from)
        val out = Path()
        if (b <= 1f) {
            measure.getSegment(a * len, b * len, out, true)
        } else {
            measure.getSegment(a * len, len, out, true)
            measure.getSegment(0f, (b - 1f) * len, out, true)
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun colour(c: FloatArray): Color {
        val scale = if (c.take(3).any { it > 1f }) 255f else 1f
        fun ch(i: Int) = (c.getOrElse(i) { 0f } / scale).coerceIn(0f, 1f)
        val col = Color(ch(0), ch(1), ch(2), c.getOrElse(3) { 1f }.coerceIn(0f, 1f))
        return Colouring.map(col, paint.colouring, colourful, paint.tint)
    }

    private inline fun DrawScope.layer(p: Paint, block: DrawScope.() -> Unit) {
        drawIntoCanvas { it.saveLayer(HUGE, p) }
        block()
        drawIntoCanvas { it.restore() }
    }

    private inline fun DrawScope.withM(m: M3, block: DrawScope.() -> Unit) {
        if (m.isIdentity) block() else withTransform({ transform(m.toCompose()) }, block)
    }

    companion object {
        /** Layer bounds: the clip (the art's box) keeps every off-screen small. */
        private val HUGE = Rect(-1e5f, -1e5f, 1e5f, 1e5f)
        private const val MIN_ALPHA = 0.002f
        private const val OPAQUE = 0.999f
        private const val MAX_PARENTS = 32

        /** A colour filter that turns luminance into alpha (inverted: darkness into alpha), for luma mattes and SVG masks. */
        fun lumaToAlpha(inverted: Boolean): ColorFilter {
            val k = if (inverted) -1f else 1f
            return ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 0f, 255f,
                        0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, if (inverted) 255f else 0f,
                    ),
                ),
            )
        }

        fun blendOf(mode: Int): BlendMode = when (mode) {
            1 -> BlendMode.Multiply
            2 -> BlendMode.Screen
            3 -> BlendMode.Overlay
            4 -> BlendMode.Darken
            5 -> BlendMode.Lighten
            6 -> BlendMode.ColorDodge
            7 -> BlendMode.ColorBurn
            8 -> BlendMode.Hardlight
            9 -> BlendMode.Softlight
            10 -> BlendMode.Difference
            11 -> BlendMode.Exclusion
            12 -> BlendMode.Hue
            13 -> BlendMode.Saturation
            14 -> BlendMode.Color
            15 -> BlendMode.Luminosity
            16 -> BlendMode.Plus
            else -> BlendMode.SrcOver
        }
    }
}
