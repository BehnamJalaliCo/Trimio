package io.trimio.engine.motion.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import io.trimio.engine.motion.M3

/**
 * Animated SVG icons (SMIL), as open animated icon sets publish them (Material Line Icons
 * `line-md`, MIT): strokes that draw themselves on (`stroke-dashoffset`), fills that fade in,
 * paths that morph, parts that rotate, move or scale, and black-and-white masks. Single-colour
 * drawings take the look's tint. Anything else (gradients, clip paths, symbols, motion paths,
 * timing tied to other animations) is refused rather than drawn wrong.
 */
internal class SvgDoc(val nodes: List<SNode>, val masks: Map<String, SNode>, val duration: Float, val loops: Boolean, val colourful: Boolean)

internal class SNode(val tag: String, val attrs: Map<String, String>, val children: List<SNode>, val anims: List<SAnim>) {
    val animated: Map<String, List<SAnim>> = anims.groupBy { it.attribute }
}

/** One `<animate>`, `<set>` or `<animateTransform>`. */
internal class SAnim(
    val attribute: String,
    /** "rotate", "translate" or "scale" for transforms; null otherwise. */
    val transformType: String?,
    val values: List<String>,
    val keyTimes: FloatArray?,
    val begin: Float,
    val dur: Float,
    /** Plays this many times (∞ for indefinite). */
    val repeat: Float,
    val freeze: Boolean,
    val discrete: Boolean,
    val additive: Boolean,
) {
    val end: Float get() = if (repeat.isInfinite()) Float.POSITIVE_INFINITY else begin + dur * repeat

    /** Value at [t], or null when the animation does not apply (not begun, or ended without freeze). */
    fun at(t: Float, base: String?): String? {
        if (t < begin || values.isEmpty()) return null
        if (t >= end && !freeze) return null
        val p = if (t >= end || dur <= 0f) 1f else ((t - begin) % dur) / dur
        return valueAt(p, base)
    }

    private fun valueAt(p: Float, base: String?): String {
        val vs = values.map { if (it == FROM_BASE) base.orEmpty() else it }
        if (vs.size == 1) return vs[0]
        val times = keyTimes?.takeIf { it.size == vs.size } ?: FloatArray(vs.size) { it / (vs.size - 1f) }
        var k = 1
        while (k < vs.size - 1 && times[k] < p) k++
        val span = times[k] - times[k - 1]
        val f = if (span <= 0f) 1f else ((p - times[k - 1]) / span).coerceIn(0f, 1f)
        if (discrete) return if (f >= 1f) vs[k] else vs[k - 1]
        return SvgNumbers.mix(vs[k - 1], vs[k], f)
    }

    companion object {
        const val FROM_BASE = "\u0000base"
    }
}

/** Interpolation of attribute strings that share their shape: every number moves, the rest stays. */
internal object SvgNumbers {
    private val number = Regex("""-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

    fun mix(a: String, b: String, f: Float): String {
        if (f <= 0f) return a
        if (f >= 1f) return b
        val na = number.findAll(a).toList()
        val nb = number.findAll(b).toList()
        if (na.size != nb.size || na.isEmpty()) return if (f < 0.5f) a else b
        val out = StringBuilder()
        var last = 0
        for ((i, m) in na.withIndex()) {
            out.append(a, last, m.range.first)
            val x = m.value.toFloat()
            val y = nb[i].value.toFloat()
            out.append(fmt(x + (y - x) * f))
            last = m.range.last + 1
        }
        out.append(a, last, a.length)
        return out.toString()
    }

    fun floats(s: String?): List<Float> = s?.let { number.findAll(it).map { m -> m.value.toFloat() }.toList() }.orEmpty()

    private fun fmt(v: Float): String {
        val r = kotlin.math.round(v * 1000f) / 1000f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }
}

internal object SvgMotionReader {
    private val token = Regex("""<(/?)([a-zA-Z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(/?)>""")
    private val attribute = Regex("""([a-zA-Z_:][\w:.-]*)\s*=\s*"([^"]*)"""")
    private val refused = setOf("animateMotion", "use", "symbol", "image", "text", "filter", "clipPath", "linearGradient", "radialGradient", "pattern", "foreignObject", "style")
    private val shapes = setOf("g", "path", "circle", "ellipse", "rect", "line", "polyline", "polygon")

    private class Raw(val tag: String, val attrs: Map<String, String>, val children: MutableList<Raw> = mutableListOf())

    fun read(body: String): SvgDoc {
        val root = Raw("root", emptyMap())
        val stack = ArrayDeque<Raw>().apply { addLast(root) }
        for (m in token.findAll(body)) {
            val (close, tag, rest, selfClose) = m.destructured
            if (tag in refused) throw Unsupported("svg <$tag>")
            if (close == "/") {
                if (stack.size > 1 && stack.last().tag == tag) stack.removeLast()
                continue
            }
            val attrs = attribute.findAll(rest).associate { it.groupValues[1] to it.groupValues[2] } + style(rest)
            if ("transform-origin" in attrs || attrs["clip-path"] != null || attrs["filter"] != null) throw Unsupported("svg attribute")
            val node = Raw(tag, attrs)
            stack.last().children += node
            if (selfClose != "/") stack.addLast(node)
        }
        val masks = mutableMapOf<String, SNode>()
        val nodes = convert(root.children, masks)
        val anims = (nodes + masks.values).flatMap(::allAnims)
        if (nodes.isEmpty()) throw Unsupported("empty svg")
        val finite = anims.filter { !it.end.isInfinite() }.maxOfOrNull { it.end } ?: 0f
        val loops = anims.any { it.end.isInfinite() }
        val cycle = anims.filter { it.end.isInfinite() }.maxOfOrNull { it.begin + it.dur } ?: 0f
        return SvgDoc(nodes, masks, maxOf(finite, cycle, MIN_DURATION), loops, nodes.any(::colourful))
    }

    private fun convert(raws: List<Raw>, masks: MutableMap<String, SNode>): List<SNode> = raws.mapNotNull { r ->
        when (r.tag) {
            "defs" -> {
                convert(r.children, masks)
                null
            }
            "mask" -> {
                val id = r.attrs["id"] ?: throw Unsupported("mask without id")
                masks[id] = SNode("g", emptyMap(), convert(r.children, masks), emptyList())
                null
            }
            "animate", "set", "animateTransform", "title", "desc", "metadata" -> null
            in shapes -> SNode(r.tag, r.attrs, convert(r.children, masks), r.children.mapNotNull(::anim))
            else -> throw Unsupported("svg <${r.tag}>")
        }
    }

    private fun anim(r: Raw): SAnim? {
        if (r.tag != "animate" && r.tag != "set" && r.tag != "animateTransform") return null
        val a = r.attrs
        val name = a["attributeName"] ?: return null
        val values = a["values"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOfNotNull(a["from"] ?: if (r.tag == "set") null else SAnim.FROM_BASE, a["to"]).takeIf { a["to"] != null }
            ?: throw Unsupported("svg animation without values")
        val begin = time(a["begin"] ?: "0s")
        val dur = if (r.tag == "set") a["dur"]?.let(::time) ?: 0f else time(a["dur"] ?: throw Unsupported("svg animation without dur"))
        val repeat = when (val rc = a["repeatCount"]) {
            null -> 1f
            "indefinite" -> Float.POSITIVE_INFINITY
            else -> rc.toFloatOrNull() ?: 1f
        }
        if (name == "transform" && r.tag != "animateTransform") throw Unsupported("svg transform animation")
        if (name !in ANIMATABLE) throw Unsupported("svg animation of $name")
        return SAnim(
            attribute = name, transformType = a["type"] ?: if (name == "transform") "translate" else null, values = values,
            keyTimes = a["keyTimes"]?.split(';')?.mapNotNull { it.trim().toFloatOrNull() }?.toFloatArray(),
            begin = begin, dur = dur, repeat = repeat, freeze = a["fill"] == "freeze" || (r.tag == "set" && a["dur"] == null),
            discrete = r.tag == "set" || a["calcMode"] == "discrete", additive = a["additive"] == "sum",
        )
    }

    private fun allAnims(n: SNode): List<SAnim> = n.anims + n.children.flatMap(::allAnims)

    /** A drawing in its own colours: some paint outside a mask is neither the tint nor none. */
    private fun colourful(n: SNode): Boolean {
        val own = listOf("fill", "stroke").any { k -> n.attrs[k]?.let { v -> v != "none" && v != "currentColor" && !v.startsWith("url(") } == true }
        return own || n.children.any(::colourful)
    }

    /** Seconds from "0.4s", "600ms" or "1"; timing tied to other animations is refused. */
    private fun time(s: String): Float {
        if (listOf("begin", "end", "click", ";").any { it in s }) throw Unsupported("svg timing $s")
        val v = s.trim()
        return when {
            v.endsWith("ms") -> v.removeSuffix("ms").toFloatOrNull()?.div(MS)
            v.endsWith("s") -> v.removeSuffix("s").toFloatOrNull()
            else -> v.toFloatOrNull()
        } ?: throw Unsupported("svg timing $s")
    }

    private fun style(rest: String): Map<String, String> {
        val s = Regex("""style="([^"]*)"""").find(rest)?.groupValues?.get(1) ?: return emptyMap()
        return s.split(';').mapNotNull { kv -> kv.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
    }

    private val ANIMATABLE = setOf(
        "d", "opacity", "fill-opacity", "stroke-opacity", "stroke-dashoffset", "stroke-dasharray", "stroke-width",
        "r", "cx", "cy", "rx", "ry", "x", "y", "width", "height", "x1", "y1", "x2", "y2", "points", "fill", "stroke", "transform",
    )
    private const val MS = 1000f
    private const val MIN_DURATION = 0.1f
}

/** A [SvgDoc] as [MotionArt], in a [width] × [height] icon box (24 for Material Line Icons). */
internal class SvgMotionArt(private val doc: SvgDoc, override val document: String, override val width: Float, override val height: Float) : MotionArt {
    override val duration: Float get() = doc.duration
    override val loops: Boolean get() = doc.loops
    override val colourful: Boolean get() = doc.colourful
    override val format: String get() = MotionArt.FORMAT_SVG

    override fun draw(scope: DrawScope, time: Float, paint: ArtPaint) = with(scope) {
        clipRect(-width * BLEED, -height * BLEED, width * (1f + BLEED), height * (1f + BLEED)) {
            val m = if (paint.mirror) M3.translate(width, 0f) * M3.scale(-1f, 1f) else M3.IDENTITY
            withM(m) { SvgPainter(doc, paint, time.coerceAtLeast(0f)).draw(this, doc.nodes) }
        }
    }

    private inline fun DrawScope.withM(m: M3, block: DrawScope.() -> Unit) {
        if (m.isIdentity) block() else withTransform({ transform(m.toCompose()) }, block)
    }

    private companion object {
        /** Round caps and strokes may reach a little past the icon box. */
        const val BLEED = 0.1f
    }
}

/** Draws the SVG tree at one time. */
internal class SvgPainter(private val doc: SvgDoc, private val paint: ArtPaint, private val t: Float) {
    private class Style(val values: Map<String, String>) {
        operator fun get(k: String) = values[k]
    }

    /** The pass drawing the halo ([ArtPaint.halo]) under the drawing: every paint in the halo tone, strokes wider. */
    private var haloPass = false

    fun draw(scope: DrawScope, nodes: List<SNode>) {
        if (paint.halo != null) {
            haloPass = true
            nodes.forEach { scope.node(it, Style(DEFAULTS), paint.alpha * HALO_ALPHA, mask = false) }
            haloPass = false
        }
        nodes.forEach { scope.node(it, Style(DEFAULTS), paint.alpha, mask = false) }
    }

    private fun value(n: SNode, k: String): String? {
        val base = n.attrs[k]
        val anims = n.animated[k] ?: return base
        var v = base
        for (a in anims) a.at(t, v)?.let { v = it }
        return v
    }

    private fun DrawScope.node(n: SNode, inherited: Style, alpha: Float, mask: Boolean) {
        val style = Style(inherited.values + INHERITED.mapNotNull { k -> value(n, k)?.let { k to it } })
        val opacity = (value(n, "opacity")?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f) * alpha
        if (opacity <= MIN_ALPHA) return
        val m = transform(n)
        val maskRef = n.attrs["mask"]?.substringAfter("#")?.substringBefore(")")?.let { doc.masks[it] }
        withM(m) {
            if (maskRef == null || mask) {
                content(n, style, opacity, mask)
                return@withM
            }
            layer(Paint()) {
                content(n, style, opacity, mask)
                layer(Paint().apply { blendMode = BlendMode.DstIn; colorFilter = LottiePainter.lumaToAlpha(inverted = false) }) {
                    node(maskRef, Style(DEFAULTS), 1f, mask = true)
                }
            }
        }
    }

    private fun DrawScope.content(n: SNode, style: Style, opacity: Float, mask: Boolean) {
        if (n.tag == "g") {
            n.children.forEach { node(it, style, opacity, mask) }
            return
        }
        val path = shape(n) ?: return
        if (style["fill-rule"] == "evenodd") path.fillType = PathFillType.EvenOdd
        val halo = if (haloPass && !mask) HALO_WIDTH else 0f
        colour(style["fill"], mask)?.let { c ->
            val a = opacity * (style["fill-opacity"]?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
            if (a > MIN_ALPHA) drawPath(path, c, alpha = a, style = Fill)
            if (a > MIN_ALPHA && halo > 0f) drawPath(path, c, alpha = a, style = Stroke(halo, join = StrokeJoin.Round))
        }
        colour(style["stroke"], mask)?.let { c ->
            val a = opacity * (style["stroke-opacity"]?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
            val w = style["stroke-width"]?.toFloatOrNull() ?: 1f
            if (a > MIN_ALPHA && w > 0f) drawPath(path, c, alpha = a, style = stroke(style, w + halo))
        }
    }

    private fun stroke(style: Style, width: Float): Stroke {
        val dash = SvgNumbers.floats(style["stroke-dasharray"]).filter { it >= 0f }
        val effect = if (dash.isEmpty() || dash.all { it == 0f }) null else {
            val even = if (dash.size % 2 == 0) dash else dash + dash
            PathEffect.dashPathEffect(even.map { it.coerceAtLeast(0.001f) }.toFloatArray(), style["stroke-dashoffset"]?.toFloatOrNull() ?: 0f)
        }
        return Stroke(
            width = width,
            cap = when (style["stroke-linecap"]) { "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt },
            join = when (style["stroke-linejoin"]) { "round" -> StrokeJoin.Round; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter },
            pathEffect = effect,
        )
    }

    private fun f(n: SNode, k: String) = value(n, k)?.removeSuffix("px")?.toFloatOrNull() ?: 0f

    private fun shape(n: SNode): Path? = when (n.tag) {
        "path" -> value(n, "d")?.takeIf { it.isNotBlank() }?.let { d -> runCatching { PathParser().parsePathString(d).toPath() }.getOrNull() }
        "circle" -> f(n, "r").takeIf { it > 0f }?.let { r -> Path().apply { addOval(Rect(Offset(f(n, "cx") - r, f(n, "cy") - r), Size(r * 2f, r * 2f))) } }
        "ellipse" -> Path().apply { addOval(Rect(Offset(f(n, "cx") - f(n, "rx"), f(n, "cy") - f(n, "ry")), Size(f(n, "rx") * 2f, f(n, "ry") * 2f))) }
        "rect" -> Path().apply {
            val r = (value(n, "rx") ?: value(n, "ry"))?.toFloatOrNull() ?: 0f
            addRoundRect(androidx.compose.ui.geometry.RoundRect(f(n, "x"), f(n, "y"), f(n, "x") + f(n, "width"), f(n, "y") + f(n, "height"), r, r))
        }
        "line" -> Path().apply {
            moveTo(f(n, "x1"), f(n, "y1"))
            lineTo(f(n, "x2"), f(n, "y2"))
        }
        "polyline", "polygon" -> SvgNumbers.floats(value(n, "points")).chunked(2).filter { it.size == 2 }.takeIf { it.isNotEmpty() }?.let { pts ->
            Path().apply {
                moveTo(pts[0][0], pts[0][1])
                pts.drop(1).forEach { lineTo(it[0], it[1]) }
                if (n.tag == "polygon") close()
            }
        }
        else -> null
    }

    /** The element's own transform, with an animated one replacing it (or adding to it, `additive="sum"`). */
    private fun transform(n: SNode): M3 {
        val static = n.attrs["transform"]?.let(::parseTransform) ?: M3.IDENTITY
        val anims = n.animated["transform"] ?: return static
        var m = static
        for (a in anims) {
            val v = a.at(t, null) ?: continue
            val step = typed(a.transformType ?: "translate", SvgNumbers.floats(v))
            m = if (a.additive) m * step else step
        }
        return m
    }

    private fun parseTransform(s: String): M3 = Regex("""(\w+)\s*\(([^)]*)\)""").findAll(s).fold(M3.IDENTITY) { acc, m ->
        val nums = SvgNumbers.floats(m.groupValues[2])
        acc * if (m.groupValues[1] == "matrix" && nums.size == 6) M3(floatArrayOf(nums[0], nums[2], nums[4], nums[1], nums[3], nums[5], 0f, 0f, 1f)) else typed(m.groupValues[1], nums)
    }

    private fun typed(type: String, v: List<Float>): M3 = when (type) {
        "rotate" -> {
            val cx = v.getOrElse(1) { 0f }
            val cy = v.getOrElse(2) { 0f }
            M3.translate(cx, cy) * M3.rotate(v.getOrElse(0) { 0f }) * M3.translate(-cx, -cy)
        }
        "scale" -> M3.scale(v.getOrElse(0) { 1f }, v.getOrElse(1) { v.getOrElse(0) { 1f } })
        "translate" -> M3.translate(v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
        "skewX" -> M3.skewX(v.getOrElse(0) { 0f })
        else -> M3.IDENTITY
    }

    /** A paint: the tint for `currentColor`, through the art's colouring; masks keep their own black and white. */
    private fun colour(v: String?, mask: Boolean): Color? {
        val halo = paint.halo
        if (haloPass && !mask && halo != null) return if (v == null || v == "none" || v == "transparent" || v.startsWith("url(")) null else halo
        val raw = when {
            v == null || v == "none" || v == "transparent" || v.startsWith("url(") -> return null
            v == "currentColor" -> if (mask) Color.White else return Colouring.map(paint.tint, Colouring.Own, doc.colourful, paint.tint)
            else -> parse(v) ?: return null
        }
        return if (mask) raw else Colouring.map(raw, paint.colouring, doc.colourful, paint.tint)
    }

    private fun parse(v: String): Color? {
        val h = v.trim().removePrefix("#")
        val full = when {
            !v.trim().startsWith("#") -> return NAMED[v.trim().lowercase()]
            h.length == 3 -> h.map { "$it$it" }.joinToString("")
            h.length == 6 -> h
            else -> return null
        }
        return full.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    }

    private inline fun DrawScope.layer(p: Paint, block: DrawScope.() -> Unit) {
        drawIntoCanvas { it.saveLayer(HUGE, p) }
        block()
        drawIntoCanvas { it.restore() }
    }

    private inline fun DrawScope.withM(m: M3, block: DrawScope.() -> Unit) {
        if (m.isIdentity) block() else withTransform({ transform(m.toCompose()) }, block)
    }

    private companion object {
        val HUGE = Rect(-1e5f, -1e5f, 1e5f, 1e5f)
        const val MIN_ALPHA = 0.002f

        /** How much wider (icon units, a 24 box) and how strong the halo under a drawing is. */
        const val HALO_WIDTH = 2.4f
        const val HALO_ALPHA = 0.55f
        val INHERITED = listOf(
            "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset",
            "fill-opacity", "stroke-opacity", "fill-rule",
        )
        val DEFAULTS = mapOf("fill" to "#000")
        val NAMED = mapOf("black" to Color.Black, "white" to Color.White, "red" to Color.Red)
    }
}
