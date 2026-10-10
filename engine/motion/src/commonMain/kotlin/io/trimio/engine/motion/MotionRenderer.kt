package io.trimio.engine.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Footage and stills for [MediaNode]s, by source id, at a time in the source. */
fun interface MediaSource {
    fun frame(source: String, time: Float): ImageBitmap?

    companion object {
        val None = MediaSource { _, _ -> null }
    }
}

/**
 * Draws a [Composition] at any time into a [DrawScope] — the same path for on-screen preview,
 * desktop rendering and Android export. The [measurer] must use a density of 1 (sp == px).
 *
 * Quality features: per-unit text animation that keeps Persian shaping, alpha masks, 3D layers in
 * perspective, multi-sample blur, and film motion blur (temporal supersampling of moving layers).
 */
class MotionRenderer(
    val composition: Composition,
    measurer: TextMeasurer,
    fonts: MotionFonts,
    private val media: MediaSource = MediaSource.None,
) {
    internal val text = TextLayoutEngine(measurer, fonts)
    private val blocks = HashMap<TextNode, TextBlock>()
    private val frame = Rect(0f, 0f, composition.width.toFloat(), composition.height.toFloat())
    private val shutter = composition.shutterAngle / 360f / composition.fps

    fun block(node: TextNode): TextBlock = blocks.getOrPut(node) { text.layout(node.text, node.type, node.maxWidth, node.align, node.rtl) }

    fun render(scope: DrawScope, t: Float) = with(scope) {
        drawRect(composition.background)
        val cam = composition.camera
        val cx = composition.width / 2f
        val cy = composition.height / 2f
        val m = M3.translate(cx, cy) * M3.rotate(cam.rotation.at(t)) * M3.scale(cam.zoom.at(t), cam.zoom.at(t)) * M3.translate(-cx + cam.x.at(t), -cy + cam.y.at(t))
        withM(m) { drawNode(composition.root, t, motionBlur = shutter > 0f) }
    }

    // ---------------------------------------------------------------- nodes

    /** Local bounds of a node at [t], before its transform. */
    fun bounds(node: Node, t: Float): Size = when (node) {
        is Group -> Size(node.width ?: frame.width, node.height ?: frame.height)
        is TextNode -> block(node).let { Size(it.width, it.height) }
        is CounterNode -> counterLayout(node, t).size.let { Size(it.width.toFloat(), it.height.toFloat()) }
        is ShapeNode -> shapeSize(node.shape, t)
        is MediaNode -> Size(node.width, node.height)
        is EffectNode -> frame.size
    }

    internal fun matrixOf(node: Node, t: Float): M3 {
        val tr = node.transform
        val size = bounds(node, t)
        val s = tr.scale.at(t)
        return M3.translate(tr.x.at(t), tr.y.at(t)) *
            M3.rotate3d(tr.rotationX.at(t), tr.rotationY.at(t), tr.perspective) *
            M3.rotate(tr.rotation.at(t)) *
            M3.skewX(tr.skewX.at(t)) *
            M3.scale(s * tr.scaleX.at(t), s * tr.scaleY.at(t)) *
            M3.translate(-tr.anchorX * size.width, -tr.anchorY * size.height)
    }

    private fun DrawScope.drawNode(node: Node, t: Float, motionBlur: Boolean) {
        if (!node.isAlive(t)) return
        if (motionBlur && isMoving(node, t)) {
            val k = composition.shutterSamples.coerceAtLeast(2)
            val times = List(k) { i -> t + (i / (k - 1f) - 0.5f) * shutter }
            val area = times.fold(Rect.Zero) { acc, ti -> union(acc, matrixOf(node, ti).mapBounds(paddedBounds(node, ti))) }
            if (area.isEmpty) return
            accumulate(area, times.map { ti -> { drawNodeOnce(node, ti, motionBlur = false) } })
        } else {
            drawNodeOnce(node, t, motionBlur)
        }
    }

    private fun DrawScope.drawNodeOnce(node: Node, t: Float, motionBlur: Boolean) {
        val tr = node.transform
        val opacity = tr.opacity.at(t).coerceIn(0f, 1f)
        if (opacity <= 0.002f) return
        val blur = tr.blur.at(t)
        val local = Rect(Offset.Zero, bounds(node, t))
        withM(matrixOf(node, t)) {
            val needsLayer = node.mask != null || node.blend != BlendMode.SrcOver || (opacity < 1f && node is Group) || blur > 0.5f
            if (!needsLayer) {
                drawContent(node, t, opacity, motionBlur)
                return@withM
            }
            val area = padded(node, local)
            layer(area, alpha = if (blur > 0.5f || node is Group || node.mask != null) opacity else 1f, blend = node.blend) {
                val contentAlpha = if (blur > 0.5f || node is Group || node.mask != null) 1f else opacity
                val draw: DrawScope.() -> Unit = { drawContent(node, t, contentAlpha, motionBlur) }
                if (blur > 0.5f) blurred(area, blur, draw) else draw()
                node.mask?.let { mask ->
                    layer(area, alpha = 1f, blend = if (mask.invert) BlendMode.DstOut else BlendMode.DstIn) { drawNode(mask.node, t, motionBlur = false) }
                }
            }
        }
    }

    private fun DrawScope.drawContent(node: Node, t: Float, alpha: Float, motionBlur: Boolean) {
        when (node) {
            is Group -> node.children.forEach { drawNode(it, t, motionBlur) }
            is TextNode -> drawTextNode(node, t, alpha)
            is CounterNode -> drawCounter(node, t, alpha)
            is ShapeNode -> drawShape(node, t, alpha)
            is MediaNode -> drawMedia(node, t, alpha)
            is EffectNode -> drawEffect(node.effect, t, alpha, frame.size)
        }
    }

    /** True when the node visibly moves within one shutter interval (worth motion blur). */
    private fun isMoving(node: Node, t: Float): Boolean {
        if (node is EffectNode || node is MediaNode) return false
        val a = t - shutter / 2f
        val b = t + shutter / 2f
        if (!node.isAlive(a) || !node.isAlive(b)) return false
        val r = Rect(Offset.Zero, bounds(node, t))
        val pa = matrixOf(node, a).mapBounds(r)
        val pb = matrixOf(node, b).mapBounds(r)
        val moved = abs(pa.left - pb.left) + abs(pa.top - pb.top) + abs(pa.right - pb.right) + abs(pa.bottom - pb.bottom)
        return moved > MOTION_THRESHOLD_PX * 2 || (node is TextNode && unitsMoving(node, a, b))
    }

    private fun unitsMoving(node: TextNode, a: Float, b: Float): Boolean {
        val block = block(node)
        val size = node.type.size
        return node.animators.any { anim ->
            val units = unitCount(block, anim.unit)
            (0 until units).any { i -> differs(anim.stateAt(a, i, units), anim.stateAt(b, i, units), size) }
        }
    }

    private fun differs(sa: UnitState, sb: UnitState, size: Float): Boolean {
        val travel = (abs(sa.dx - sb.dx) + abs(sa.dy - sb.dy) + abs(sa.scale * sa.scaleY - sb.scale * sb.scaleY)) * size
        return travel > MOTION_THRESHOLD_PX || abs(sa.rotation - sb.rotation) > 1.5f || abs(sa.rotationX - sb.rotationX) > 3f
    }

    private fun paddedBounds(node: Node, t: Float): Rect = padded(node, Rect(Offset.Zero, bounds(node, t)))

    /** Room around a node for what draws outside its box: moving letters, shadows, blur. */
    private fun padded(node: Node, r: Rect): Rect {
        val pad = when (node) {
            is TextNode -> node.type.size * 2.2f + (node.shadow?.blur ?: 0f) * 2
            is CounterNode -> node.type.size * 0.8f
            is ShapeNode -> (node.stroke?.width ?: 0f) * 2 + (node.shadow?.let { it.blur * 2 + abs(it.dx) + abs(it.dy) } ?: 0f) + 4f
            else -> 0f
        } + node.transform.blur.let { if (it is Anim.Const && it.v == 0f) 0f else 160f }
        return r.inflate(pad)
    }

    // ---------------------------------------------------------------- text

    private fun unitCount(block: TextBlock, unit: TextUnit) = when (unit) {
        TextUnit.Char -> block.charCount
        TextUnit.Word -> block.wordCount
        TextUnit.Line -> block.lines.size
        TextUnit.All -> 1
    }

    private fun DrawScope.drawTextNode(node: TextNode, t: Float, alpha: Float) {
        val block = block(node)
        val size = node.type.size
        val base = fillBrush(node.fill, t, Size(block.width, block.height))
        val baseColor = (node.fill as? Fill.Solid)?.color?.at(t)
        // Which unit level drives clipping: the finest unit any animator uses.
        val unitLevel = node.animators.minOfOrNull { it.unit.ordinal }?.let { TextUnit.entries[it] } ?: TextUnit.All
        val maskLine = node.animators.any { it.clipToLine }
        for (line in block.lines) {
            val lineState = stateFor(node, block, TextUnit.Line, line.index, t) + stateFor(node, block, TextUnit.All, 0, t)
            val units: List<Pair<UnitBox, UnitState>> = when (unitLevel) {
                TextUnit.Char -> line.chars.map { it to stateFor(node, block, TextUnit.Char, it.index, t) + stateFor(node, block, TextUnit.Word, wordAt(line, it.center), t) }
                TextUnit.Word -> line.words.map { it to stateFor(node, block, TextUnit.Word, it.index, t) }
                else -> emptyList()
            }
            // Units at rest draw as one piece: no clip seams once the words have landed.
            val pieces = if (units.all { it.second.isRest }) listOf(wholeLine(line, size) to UnitState.REST) else units
            for ((u, own) in pieces) {
                val state = own + lineState
                val a = alpha * state.opacity.coerceIn(0f, 1f)
                if (a <= 0.002f) continue
                val clip = Rect(line.x + u.clipLeft, line.top - size * 3, line.x + u.clipRight, line.top + line.height + size * 3)
                val outer = if (maskLine) Rect(clip.left, line.top - size * 0.08f, clip.right, line.top + line.height + size * 0.1f) else clip
                // Clips cost a coverage mask each in software rendering: only units that move apart need them.
                val whole = u.index < 0
                clipIf(!whole || maskLine, outer) {
                    val pivot = Offset(line.x + u.center, line.top + line.height / 2f)
                    val m = M3.translate(pivot.x + state.dx * size, pivot.y + state.dy * size) *
                        M3.rotate3d(state.rotationX, 0f, size * 6f) *
                        M3.rotate(state.rotation) *
                        M3.scale(state.scale * state.scaleX, state.scale * state.scaleY) *
                        M3.translate(-pivot.x, -pivot.y)
                    withM(m) {
                        val drawUnit: DrawScope.() -> Unit = {
                            clipIf(!whole, clip) { drawLineWithDecorations(node, block, line, t, a, base, baseColor) }
                        }
                        val blur = state.blur * size
                        if (blur > 0.5f) blurred(clip.inflate(blur * 2), blur, drawUnit) else drawUnit()
                    }
                }
            }
        }
        // Loops around words draw over everything, unclipped.
        for (d in node.decorations.filterIsInstance<Decoration.Circle>()) drawCircleMark(d, block, t, alpha)
    }

    private fun wholeLine(line: TextLine, size: Float) = UnitBox(-1, 0f, line.width, -size * 4, line.width + size * 4)

    private fun wordAt(line: TextLine, x: Float): Int = line.words.firstOrNull { x >= it.clipLeft && x < it.clipRight }?.index ?: -1

    private fun stateFor(node: TextNode, block: TextBlock, unit: TextUnit, index: Int, t: Float): UnitState {
        if (index < 0) return UnitState.REST
        var s = UnitState.REST
        val count = unitCount(block, unit)
        for (anim in node.animators) if (anim.unit == unit) s += anim.stateAt(t, index, count)
        return s
    }

    /** One line of text with the blocks, underlines and ink that belong to it. */
    private fun DrawScope.drawLineWithDecorations(node: TextNode, block: TextBlock, line: TextLine, t: Float, alpha: Float, base: Brush, baseColor: Color?) {
        val size = node.type.size
        val origin = Offset(line.x, line.glyphTop)
        val blocks = node.decorations.filterIsInstance<Decoration.Block>().mapNotNull { d -> blockRect(d, line, t, size, node.rtl)?.let { d to it } }
        for ((d, r) in blocks) {
            val m = if (d.skew != 0f) M3.translate(r.center.x, r.center.y) * M3.skewX(d.skew) * M3.translate(-r.center.x, -r.center.y) else M3.IDENTITY
            withM(m) { drawRoundRect(d.color, r.topLeft, r.size, androidx.compose.ui.geometry.CornerRadius(d.radius * size), alpha = alpha) }
        }
        // Karaoke ink: each inked word's slice of the line, with its current colour.
        val inks = node.decorations.filterIsInstance<Decoration.Ink>().filter { t >= it.at }.flatMap { d ->
            val p = ((t - d.at) / d.duration).coerceIn(0f, 1f)
            val color = androidx.compose.ui.graphics.lerp(baseColor ?: d.color, d.color, p)
            line.words.filter { it.index in d.words }.map { Rect(line.x + it.clipLeft, line.top - size * 3, line.x + it.clipRight, line.top + line.height + size * 3) to color }
        }
        val shadow = node.shadow?.let { androidx.compose.ui.graphics.Shadow(it.color.copy(alpha = it.color.alpha * alpha), Offset(it.dx, it.dy), it.blur) }
        // Fill everywhere except blocks and inked words; then inked words; then text on blocks.
        clipOutRects(blocks.map { it.second } + inks.map { it.first }) {
            drawText(line.layout, brush = base, topLeft = origin, alpha = alpha, shadow = shadow)
        }
        for ((r, color) in inks) {
            clipOutRects(blocks.map { it.second }) {
                clipRect(r.left, r.top, r.right, r.bottom) { drawText(line.layout, color = color, topLeft = origin, alpha = alpha, shadow = shadow) }
            }
        }
        for ((d, r) in blocks) clipRect(r.left, r.top, r.right, r.bottom) { drawText(line.layout, color = d.textColor, topLeft = origin, alpha = alpha) }
        node.stroke?.let { s ->
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(s.width)
            drawText(line.layout, brush = fillBrush(s.fill, t, Size(block.width, block.height)), topLeft = origin, alpha = alpha, drawStyle = stroke)
        }
        for (d in node.decorations.filterIsInstance<Decoration.Underline>()) {
            val r = wipeRect(d.words, d.at, d.duration, d.ease, line, t, node.rtl, 0f) ?: continue
            val y = line.baseline + d.offset * size
            drawRoundRect(d.color, Offset(r.left, y), Size(r.width, d.thickness * size), androidx.compose.ui.geometry.CornerRadius(d.thickness * size / 2), alpha = alpha)
        }
    }

    private inline fun DrawScope.clipIf(condition: Boolean, r: Rect, block: DrawScope.() -> Unit) {
        if (condition) clipRect(r.left, r.top, r.right, r.bottom) { block() } else block()
    }

    private fun DrawScope.clipOutRects(rects: List<Rect>, block: DrawScope.() -> Unit) {
        if (rects.isEmpty()) return block()
        val r = rects.first()
        clipRect(r.left, r.top, r.right, r.bottom, ClipOp.Difference) { clipOutRects(rects.drop(1), block) }
    }

    private fun blockRect(d: Decoration.Block, line: TextLine, t: Float, size: Float, rtl: Boolean): Rect? {
        val r = wipeRect(d.words, d.at, d.duration, d.ease, line, t, rtl, d.padX * size) ?: return null
        // Snug to the measured ink: from the ascenders to the tails, with a little air.
        return Rect(r.left, line.inkTop - (BLOCK_AIR + d.padTop) * size, r.right, line.inkBottom + (BLOCK_AIR + d.padBottom) * size)
    }

    /**
     * The part of [words] on [line] revealed by a wipe in reading order. Multi-line ranges wipe
     * line after line within the same duration.
     */
    private fun wipeRect(words: IntRange, at: Float, duration: Float, ease: Easing, line: TextLine, t: Float, rtl: Boolean, pad: Float): Rect? {
        if (t < at) return null
        val mine = line.words.filter { it.index in words }
        if (mine.isEmpty()) return null
        val left = mine.minOf { it.left } - pad
        val right = mine.maxOf { it.right } + pad
        val p = ease.at(((t - at) / duration).coerceIn(0f, 1f))
        if (p <= 0f) return null
        val w = (right - left) * p
        return if (rtl) Rect(line.x + right - w, line.top, line.x + right, line.top + line.height) else Rect(line.x + left, line.top, line.x + left + w, line.top + line.height)
    }

    private fun DrawScope.drawCircleMark(d: Decoration.Circle, block: TextBlock, t: Float, alpha: Float) {
        if (t < d.at) return
        val boxes = block.lines.flatMap { l -> l.words.filter { it.index in d.words }.map { Rect(l.x + it.left, l.top, l.x + it.right, l.top + l.height) } }
        if (boxes.isEmpty()) return
        val r = boxes.reduce { a, b -> union(a, b) }.inflate(block.fontSize * 0.18f)
        val p = Easing.ExpoOut.at(((t - d.at) / d.duration).coerceIn(0f, 1f))
        val path = androidx.compose.ui.graphics.Path()
        val turns = 1.12f
        val steps = (64 * p).toInt().coerceAtLeast(2)
        for (i in 0..steps) {
            val a = (i / 64f) * turns * 2f * PI.toFloat() - 0.6f
            val wobble = 1f + 0.035f * sin(a * 3.1f) + 0.02f * (i / 64f)
            val x = r.center.x + cos(a) * r.width / 2f * wobble
            val y = r.center.y + sin(a) * r.height / 2f * wobble * 1.08f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            d.thickness * block.fontSize, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        drawPath(path, d.color, alpha = alpha, style = stroke)
    }

    // ---------------------------------------------------------------- counter

    internal fun counterText(node: CounterNode, t: Float): String {
        val v = node.value.at(t)
        val fixed = formatFixed(v, node.decimals, node.grouping)
        val s = node.prefix + fixed + node.suffix
        return if (node.persianDigits) io.trimio.core.model.text.Numerals.toPersian(s).replace('.', '٫').replace(',', '٬') else s
    }

    private fun counterLayout(node: CounterNode, t: Float) = counterText(node, t).let { text.measureLine(it, text.style(node.type, rtl = false, it)) }

    private fun DrawScope.drawCounter(node: CounterNode, t: Float, alpha: Float) {
        val layout = counterLayout(node, t)
        val shadow = node.shadow?.let { androidx.compose.ui.graphics.Shadow(it.color.copy(alpha = it.color.alpha * alpha), Offset(it.dx, it.dy), it.blur) }
        drawText(layout, brush = fillBrush(node.fill, t, Size(layout.size.width.toFloat(), layout.size.height.toFloat())), alpha = alpha, shadow = shadow)
    }

    // ---------------------------------------------------------------- shapes

    private fun shapeSize(shape: ShapeSpec, t: Float): Size = when (shape) {
        is ShapeSpec.Rect -> Size(shape.width.at(t), shape.height.at(t))
        is ShapeSpec.Ellipse -> Size(shape.width.at(t), shape.height.at(t))
        is ShapeSpec.Path -> Size(shape.size, shape.size)
        is ShapeSpec.Polyline -> Size(shape.points.maxOf { it.first }, shape.points.maxOf { it.second })
    }

    private val pathCache = HashMap<ShapeSpec.Path, List<androidx.compose.ui.graphics.Path>>()

    /** Each sub-path of an icon on its own, so a draw-on traces every stroke at once. */
    private fun contours(shape: ShapeSpec.Path) = pathCache.getOrPut(shape) {
        val k = shape.size / shape.viewport
        Regex("[Mm][^Mm]*").findAll(shape.data).map { it.value }.toList().map { part ->
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(if (part[0] == 'm') "M" + part.substring(1) else part).toPath().apply {
                transform(androidx.compose.ui.graphics.Matrix().apply { scale(k, k) })
            }
        }
    }

    private fun outline(shape: ShapeSpec, t: Float): androidx.compose.ui.graphics.Path = when (shape) {
        is ShapeSpec.Rect -> androidx.compose.ui.graphics.Path().apply {
            val r = shape.radius.at(t)
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, shape.width.at(t), shape.height.at(t), r, r))
        }
        is ShapeSpec.Ellipse -> androidx.compose.ui.graphics.Path().apply { addOval(Rect(0f, 0f, shape.width.at(t), shape.height.at(t))) }
        is ShapeSpec.Path -> contours(shape).let { parts -> androidx.compose.ui.graphics.Path().apply { parts.forEach { addPath(it) } } }
        is ShapeSpec.Polyline -> polylinePath(shape)
    }

    private fun polylinePath(shape: ShapeSpec.Polyline) = androidx.compose.ui.graphics.Path().apply {
        val p = shape.points
        moveTo(p[0].first, p[0].second)
        if (!shape.smooth || p.size < 3) {
            for (i in 1 until p.size) lineTo(p[i].first, p[i].second)
        } else {
            // Catmull-Rom through the points, as cubic Béziers.
            for (i in 0 until p.size - 1) {
                val p0 = p[maxOf(i - 1, 0)]
                val p1 = p[i]
                val p2 = p[i + 1]
                val p3 = p[minOf(i + 2, p.size - 1)]
                cubicTo(
                    p1.first + (p2.first - p0.first) / 6f, p1.second + (p2.second - p0.second) / 6f,
                    p2.first - (p3.first - p1.first) / 6f, p2.second - (p3.second - p1.second) / 6f,
                    p2.first, p2.second,
                )
            }
        }
    }

    private fun DrawScope.drawShape(node: ShapeNode, t: Float, alpha: Float) {
        val path = outline(node.shape, t)
        val size = shapeSize(node.shape, t)
        node.shadow?.let { s ->
            val r = Rect(Offset.Zero, size).inflate(s.blur * 2).translate(s.dx, s.dy)
            blurred(r, s.blur / 2f) {
                translate(s.dx, s.dy) { node.fill?.let { drawPath(path, s.color, alpha = alpha) } }
            }
        }
        node.fill?.let { drawPath(path, fillBrush(it, t, size), alpha = alpha) }
        node.stroke?.let { st ->
            val a = node.trimStart.at(t).coerceIn(0f, 1f)
            val b = node.trimEnd.at(t).coerceIn(0f, 1f)
            if (b - a <= 0.0005f) return@let
            val visible = when {
                a <= 0f && b >= 1f -> path
                node.shape is ShapeSpec.Path -> androidx.compose.ui.graphics.Path().apply { contours(node.shape).forEach { addPath(trimmed(it, a, b)) } }
                else -> trimmed(path, a, b)
            }
            drawPath(
                visible, fillBrush(st.fill, t, size), alpha = alpha,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    st.width,
                    cap = if (st.round) androidx.compose.ui.graphics.StrokeCap.Round else androidx.compose.ui.graphics.StrokeCap.Butt,
                    join = if (st.round) androidx.compose.ui.graphics.StrokeJoin.Round else androidx.compose.ui.graphics.StrokeJoin.Miter,
                ),
            )
        }
    }

    private fun trimmed(path: androidx.compose.ui.graphics.Path, a: Float, b: Float): androidx.compose.ui.graphics.Path {
        val measure = androidx.compose.ui.graphics.PathMeasure()
        measure.setPath(path, false)
        val length = measure.length
        return androidx.compose.ui.graphics.Path().also { measure.getSegment(a * length, b * length, it, true) }
    }

    // ---------------------------------------------------------------- media

    private fun DrawScope.drawMedia(node: MediaNode, t: Float, alpha: Float) {
        val image = media.frame(node.source, node.sourceOffset + (t - node.start).coerceAtLeast(0f) * node.speed)
        val box = Size(node.width, node.height)
        val radius = node.radius.at(t)
        val clip = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, box.width, box.height, radius, radius)) }
        clipPath(clip) {
            if (image == null) {
                drawRect(Brush.linearGradient(listOf(Color(0xFF2A2420), Color(0xFF0E0C0B)), Offset.Zero, Offset(box.width, box.height)), size = box, alpha = alpha)
                return@clipPath
            }
            val iw = image.width.toFloat()
            val ih = image.height.toFloat()
            val k = if (node.fit == Fit.Cover) maxOf(box.width / iw, box.height / ih) else minOf(box.width / iw, box.height / ih)
            val dw = iw * k
            val dh = ih * k
            drawImage(
                image,
                dstOffset = androidx.compose.ui.unit.IntOffset(((box.width - dw) / 2).toInt(), ((box.height - dh) / 2).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()),
                alpha = alpha,
                colorFilter = gradeFilter(node.grade, t),
            )
        }
    }

    private fun gradeFilter(g: Grade, t: Float): androidx.compose.ui.graphics.ColorFilter? {
        val exposure = g.exposure.at(t)
        val contrast = g.contrast.at(t)
        val saturation = g.saturation.at(t)
        val temp = g.temperature.at(t)
        val neutralTone = exposure == 0f && temp == 0f
        if (neutralTone && contrast == 1f && saturation == 1f) return null
        val gain = kotlin.math.exp(exposure * 0.6931472f)
        val m = androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(saturation) }
        val off = 128f * (1f - contrast)
        val c = androidx.compose.ui.graphics.ColorMatrix(
            floatArrayOf(
                contrast * gain * (1 + 0.08f * temp), 0f, 0f, 0f, off,
                0f, contrast * gain, 0f, 0f, off,
                0f, 0f, contrast * gain * (1 - 0.1f * temp), 0f, off,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        m.timesAssign(c)
        return androidx.compose.ui.graphics.ColorFilter.colorMatrix(m)
    }

    // ---------------------------------------------------------------- paint helpers

    internal fun fillBrush(fill: Fill, t: Float, size: Size): Brush = when (fill) {
        is Fill.Solid -> androidx.compose.ui.graphics.SolidColor(fill.color.at(t))
        is Fill.Linear -> {
            val a = fill.angle.at(t) * PI.toFloat() / 180f
            val c = Offset(size.width / 2, size.height / 2)
            val half = (abs(cos(a)) * size.width + abs(sin(a)) * size.height) / 2f
            val d = Offset(cos(a) * half, sin(a) * half)
            val stops = fill.stops
            if (stops != null) Brush.linearGradient(*stops.zip(fill.colors).toTypedArray(), start = c - d, end = c + d)
            else Brush.linearGradient(fill.colors, start = c - d, end = c + d)
        }
        is Fill.Radial -> Brush.radialGradient(
            fill.colors,
            center = Offset(size.width * fill.cx.at(t), size.height * fill.cy.at(t)),
            radius = (sqrt(size.width * size.width + size.height * size.height) * fill.radius.at(t)).coerceAtLeast(1f),
        )
    }

    private inline fun DrawScope.withM(m: M3, block: DrawScope.() -> Unit) {
        if (m.isIdentity) block() else withTransform({ transform(m.toCompose()) }, block)
    }

    private inline fun DrawScope.layer(bounds: Rect, alpha: Float, blend: BlendMode, block: DrawScope.() -> Unit) {
        drawIntoCanvas { it.saveLayer(bounds, Paint().apply { this.alpha = alpha; blendMode = blend }) }
        block()
        drawIntoCanvas { it.restore() }
    }

    /**
     * Averages several renderings: each is added (Plus) at 1/n into a clear layer, which is the
     * exact mean of the premultiplied samples — used for motion blur and soft blur alike.
     */
    private fun DrawScope.accumulate(bounds: Rect, samples: List<DrawScope.() -> Unit>) {
        val n = samples.size
        layer(bounds, 1f, BlendMode.SrcOver) {
            for (s in samples) layer(bounds, 1f / n, BlendMode.Plus) { s() }
        }
    }

    /** A soft blur of [radius] px: samples on a Vogel spiral, averaged. */
    private fun DrawScope.blurred(bounds: Rect, radius: Float, block: DrawScope.() -> Unit) {
        val n = (6 + radius / 1.5f).toInt().coerceIn(6, MAX_BLUR_SAMPLES)
        val golden = 2.3999631f
        accumulate(
            bounds.inflate(radius),
            List(n) { i ->
                {
                    val r = radius * sqrt((i + 0.5f) / n)
                    translate(cos(i * golden) * r, sin(i * golden) * r) { block() }
                }
            },
        )
    }

    private fun union(a: Rect, b: Rect) = if (a.isEmpty) b else Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    private companion object {
        const val MOTION_THRESHOLD_PX = 1.5f
        const val MAX_BLUR_SAMPLES = 28
        const val BLOCK_AIR = 0.06f
    }
}


/** Fixed-point formatting without platform formatters (common code), with optional grouping. */
internal fun formatFixed(v: Float, decimals: Int, grouping: Boolean): String {
    val negative = v < 0
    var p = 1L
    repeat(decimals) { p *= 10 }
    val scaled = kotlin.math.round(abs(v.toDouble()) * p).toLong()
    val whole = (scaled / p).toString()
    val frac = (scaled % p).toString().padStart(decimals, '0')
    val grouped = if (grouping) whole.reversed().chunked(3).joinToString(",").reversed() else whole
    return (if (negative) "-" else "") + grouped + if (decimals > 0) ".$frac" else ""
}
