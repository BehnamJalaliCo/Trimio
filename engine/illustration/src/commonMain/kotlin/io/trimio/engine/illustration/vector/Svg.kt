package io.trimio.engine.illustration.vector

import io.trimio.engine.illustration.Box
import io.trimio.engine.illustration.Colors

/**
 * One painted shape of a drawing, flattened, in the drawing's absolute coordinates. [fill] and
 * [stroke] are ARGB or null for none; [part] names the piece it came from ("hair/Hijab", "fluent:bank"),
 * so the styler can treat pieces differently (an accent object, a person).
 */
class Shape(
    val lines: List<Polyline>,
    val fill: Int?,
    val stroke: Int?,
    val strokeWidth: Float,
    val evenOdd: Boolean,
    val part: String = "",
) {
    val bounds: Box? by lazy { PathData.bounds(lines)?.let { Box(it[0], it[1], it[2], it[3]) } }

    fun transformed(m: Affine, part: String = this.part): Shape =
        Shape(lines.map { it.transformed(m) }, fill, stroke, strokeWidth * m.scale, evenOdd, part)
}

/** A vector picture: its shapes in paint order. */
class Drawing(val shapes: List<Shape>) {
    val bounds: Box? by lazy { shapes.mapNotNull { it.bounds }.reduceOrNull { a, b -> a.union(b) } }

    operator fun plus(o: Drawing): Drawing = Drawing(shapes + o.shapes)

    fun transformed(m: Affine, part: String? = null): Drawing = Drawing(shapes.map { it.transformed(m, part ?: it.part) })

    companion object {
        val Empty = Drawing(emptyList())
    }
}

/**
 * A small SVG reader for the library's drawings: `g`, `path`, `rect`, `circle`, `ellipse`, `line`,
 * `polyline`, `polygon` with `fill`, `stroke`, `stroke-width`, `fill-rule`, `opacity`/`fill-opacity`
 * and `transform`; gradients paint with the average of their stops. Text, filters, masks and clips are
 * ignored (the library has none that matter). [tolerance] is the flattening error in user units.
 */
object Svg {
    private class Style(val fill: Int?, val stroke: Int?, val strokeWidth: Float, val evenOdd: Boolean, val opacity: Float, val m: Affine)

    private class Tag(val name: String, val attrs: Map<String, String>, val closing: Boolean, val selfClosing: Boolean)

    fun parse(markup: String, tolerance: Float = DEFAULT_TOLERANCE, part: String = "", base: Affine = Affine.Identity): Drawing {
        val tags = tags(markup)
        val gradients = gradients(tags)
        val shapes = ArrayList<Shape>()
        val stack = ArrayDeque<Style>()
        stack.addLast(Style(Colors.BLACK, null, 1f, false, 1f, base))
        var skipDepth = 0
        for (t in tags) {
            when {
                t.closing -> if (skipDepth > 0) skipDepth-- else if (t.name in CONTAINERS && stack.size > 1) stack.removeLast()
                skipDepth > 0 || t.name in SKIPPED -> if (!t.selfClosing) skipDepth++
                t.name in CONTAINERS -> if (!t.selfClosing) stack.addLast(inherit(stack.last(), t.attrs, gradients))
                else -> shape(t, inherit(stack.last(), t.attrs, gradients), tolerance, part)?.let { shapes += it }
            }
        }
        return Drawing(shapes)
    }

    private fun shape(t: Tag, style: Style, tolerance: Float, part: String): Shape? {
        if (style.fill == null && style.stroke == null) return null
        val lines = geometry(t, tolerance / style.m.scale)
        return if (lines.isEmpty()) null else Shape(lines, style.fill, style.stroke, style.strokeWidth, style.evenOdd, part).transformed(style.m)
    }

    private fun inherit(parent: Style, a: Map<String, String>, gradients: Map<String, Int>): Style {
        val opacity = parent.opacity * (a["opacity"]?.toFloatOrNull() ?: 1f)
        fun paint(key: String, inherited: Int?): Int? {
            val v = a[key] ?: return inherited
            if (v == "none") return null
            val url = Regex("url\\(#([^)]+)\\)").find(v)?.groupValues?.get(1)
            return if (url != null) gradients[url] ?: inherited else Colors.parse(v) ?: inherited
        }
        val fillAlpha = (a["fill-opacity"]?.toFloatOrNull() ?: 1f) * opacity
        val fill = paint("fill", parent.fill)?.let { Colors.withAlpha(it, (Colors.a(it) * fillAlpha).toInt()) }
        val stroke = paint("stroke", parent.stroke)?.let { Colors.withAlpha(it, (Colors.a(it) * opacity).toInt()) }
        return Style(
            fill = fill,
            stroke = stroke,
            strokeWidth = a["stroke-width"]?.let { PathData.numbers(it).firstOrNull() } ?: parent.strokeWidth,
            evenOdd = a["fill-rule"]?.let { it == "evenodd" } ?: parent.evenOdd,
            opacity = opacity,
            m = parent.m * Affine.parse(a["transform"]),
        )
    }

    private fun geometry(t: Tag, tol: Float): List<Polyline> {
        val a = t.attrs
        fun n(k: String) = a[k]?.let { PathData.numbers(it).firstOrNull() } ?: 0f
        val d = when (t.name) {
            "path" -> a["d"].orEmpty()
            "rect" -> rect(n("x"), n("y"), n("width"), n("height"), a["rx"]?.let { n("rx") } ?: n("ry"), a["ry"]?.let { n("ry") } ?: n("rx"))
            "circle" -> ellipse(n("cx"), n("cy"), n("r"), n("r"))
            "ellipse" -> ellipse(n("cx"), n("cy"), n("rx"), n("ry"))
            "line" -> "M${n("x1")} ${n("y1")}L${n("x2")} ${n("y2")}"
            "polyline", "polygon" -> a["points"]?.let { "M" + it + if (t.name == "polygon") "Z" else "" }.orEmpty()
            else -> ""
        }
        return if (d.isBlank()) emptyList() else PathData(tol).parse(d)
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float, rxIn: Float, ryIn: Float): String {
        if (w <= 0f || h <= 0f) return ""
        val rx = rxIn.coerceIn(0f, w / 2)
        val ry = ryIn.coerceIn(0f, h / 2)
        if (rx == 0f || ry == 0f) return "M$x ${y}h${w}v${h}h${-w}Z"
        return "M${x + rx} ${y}h${w - 2 * rx}a$rx $ry 0 0 1 $rx ${ry}v${h - 2 * ry}a$rx $ry 0 0 1 ${-rx} ${ry}h${-(w - 2 * rx)}" +
            "a$rx $ry 0 0 1 ${-rx} ${-ry}v${-(h - 2 * ry)}a$rx $ry 0 0 1 $rx ${-ry}Z"
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): String {
        if (rx <= 0f || ry <= 0f) return ""
        return "M${cx - rx} ${cy}a$rx $ry 0 1 0 ${2 * rx} 0a$rx $ry 0 1 0 ${-2 * rx} 0Z"
    }

    /** Average colour of each gradient's stops, by id. */
    private fun gradients(tags: List<Tag>): Map<String, Int> {
        val out = HashMap<String, Int>()
        var current: String? = null
        val stops = ArrayList<Int>()
        for (t in tags) {
            val gradient = t.name.endsWith("gradient", ignoreCase = true)
            if (gradient && !t.closing) {
                current = t.attrs["id"]
                stops.clear()
            }
            if (gradient && (t.closing || t.selfClosing)) {
                current?.takeIf { stops.isNotEmpty() }?.let { id -> out[id] = average(stops) }
                current = null
            }
            if (!gradient && t.name == "stop" && current != null) stopColor(t)?.let { stops += it }
        }
        return out
    }

    private fun stopColor(t: Tag): Int? =
        Colors.parse(t.attrs["stop-color"] ?: Regex("stop-color:\\s*([^;]+)").find(t.attrs["style"].orEmpty())?.groupValues?.get(1))

    private fun average(cs: List<Int>): Int = Colors.argb(
        255, cs.sumOf { Colors.r(it) } / cs.size, cs.sumOf { Colors.g(it) } / cs.size, cs.sumOf { Colors.b(it) } / cs.size,
    )

    private fun tags(s: String): List<Tag> {
        val out = ArrayList<Tag>()
        var start = s.indexOf('<')
        while (start >= 0) {
            val end = s.indexOf('>', start)
            if (end < 0) break
            val body = s.substring(start + 1, end)
            if (!body.startsWith("!") && !body.startsWith("?")) out += tag(body)
            start = s.indexOf('<', end + 1)
        }
        return out
    }

    private fun tag(body: String): Tag {
        val inner = body.removePrefix("/").removeSuffix("/").trim()
        val name = inner.takeWhile { !it.isWhitespace() }.lowercase()
        val attrs = ATTR.findAll(inner.drop(name.length)).associate { it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
        return Tag(name, attrs, closing = body.startsWith("/"), selfClosing = body.endsWith("/"))
    }

    const val DEFAULT_TOLERANCE = 0.25f
    private val ATTR = Regex("([a-zA-Z:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val CONTAINERS = setOf("g", "svg", "a", "switch")
    private val SKIPPED = setOf("defs", "clippath", "mask", "filter", "text", "lineargradient", "radialgradient", "pattern", "title", "desc", "style", "symbol")
}
