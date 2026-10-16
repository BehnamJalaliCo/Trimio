package io.trimio.engine.motion.art

import io.trimio.engine.motion.M3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Lottie (Bodymovin) animation read into the engine's own model, drawn by [LottiePainter] into
 * any Compose DrawScope — the same code on Android and on the desktop, so an animation looks the
 * same in the app's preview, a phone export and a Windows export.
 *
 * The reader takes what open animation sets are made of (shape, null, solid and precomposed
 * layers; parenting; time remapping and stretch; alpha and luma track mattes; masks; groups,
 * paths, rectangles, ellipses, solid and gradient fills and strokes, dashes, trim paths) and
 * refuses the rest — text and image layers, 3D, expressions, layer effects, repeaters — with a
 * reason, rather than drawing something wrong ([Unsupported]). Merge paths draw their shapes
 * unmerged, as the reference web player does.
 */
internal class LottieAnimation(
    val width: Float,
    val height: Float,
    val frameRate: Float,
    val inPoint: Float,
    val outPoint: Float,
    val root: LComp,
    val assets: Map<String, LComp>,
    /** Seconds where it rests between building in and leaving: the stretch an edit lengthens to fit what is said. */
    val hold: ClosedFloatingPointRange<Float>? = null,
) {
    /** Seconds of one play. */
    val duration: Float get() = ((outPoint - inPoint) / frameRate).coerceAtLeast(1f / frameRate)

    /** A template's text slots, in reading order (the root's layers top to bottom, then each precomposition's). */
    val texts: List<LContent.Text> by lazy {
        (listOf(root) + assets.values).flatMap { c -> c.layers.reversed().mapNotNull { it.content as? LContent.Text } }
    }

    /** Every colour the animation can show (their first values), for deciding whether it is colourful. */
    val colours: List<FloatArray> by lazy { (listOf(root) + assets.values).flatMap { c -> c.layers.flatMap(LLayer::colours) } }
}

internal class Unsupported(reason: String) : RuntimeException(reason)

/** Refuses a document the engine will not draw faithfully, saying why. */
internal fun refuse(reason: String): Nothing = throw Unsupported(reason)

/** A list of layers (the animation or a precomposition), indexed by their `ind`. */
internal class LComp(val layers: List<LLayer>) {
    val byIndex: Map<Int, LLayer> = layers.mapNotNull { l -> l.ind?.let { it to l } }.toMap()
}

internal class LMatte(
    /** 0 none, 1 alpha, 2 alpha inverted, 3 luma, 4 luma inverted. */
    val mode: Int,
    /** This layer is another layer's matte and is not drawn by itself. */
    val isSource: Boolean,
    /** The matte layer's index (newer documents); otherwise the layer above. */
    val source: Int?,
)

internal class LMask(val mode: Char, val inverted: Boolean, val path: Value<ShapeData>, val opacity: Value<FloatArray>)

internal sealed interface LContent {
    class Shapes(val items: List<LShape>) : LContent
    class Solid(val color: FloatArray, val width: Float, val height: Float) : LContent
    class Precomp(val ref: String, val width: Float, val height: Float, val timeRemap: Value<FloatArray>?) : LContent

    /** A template's text slot: drawn by the engine's own typesetter with the edit's words, never the designer's. */
    class Text(val doc: LText) : LContent {
        /** Its place in reading order among the document's text layers. */
        var slot: Int = 0
    }
    data object Empty : LContent
}

/**
 * A text layer of a template as After Effects exported it: its first document (what the designer
 * typed, at what size, colour and justification, as point text or in a box) and its range-selector
 * animators. The animators move whole words — letters of Persian are joined, so a word is the
 * smallest piece that may move apart.
 */
internal class LText(
    val text: String,
    val size: Float,
    val font: String,
    val fill: FloatArray,
    val stroke: FloatArray?,
    val strokeWidth: Float,
    /** 0 left, 1 right, 2 centre (After Effects' numbering). */
    val justify: Int,
    /** Thousandths of an em. */
    val tracking: Float,
    val lineHeight: Float,
    val boxPosition: FloatArray?,
    val boxSize: FloatArray?,
    val animators: List<LTextAnimator>,
) {
    /** Word [index] of [count] at [frame]: every animator's share of it, its properties scaled by that share. */
    fun wordState(frame: Float, index: Int, count: Int): io.trimio.engine.motion.UnitState {
        var s = io.trimio.engine.motion.UnitState.REST
        for (a in animators) s += a.state(frame, index, count, size)
        return s
    }
}

/** One range selector (start, end, offset in % of the text) and the properties it applies to what it covers. */
internal class LTextAnimator(
    val start: Value<FloatArray>,
    val end: Value<FloatArray>,
    val offset: Value<FloatArray>,
    val amount: Value<FloatArray>,
    val opacity: Value<FloatArray>?,
    val position: Value<FloatArray>?,
    val scale: Value<FloatArray>?,
    val rotation: Value<FloatArray>?,
) {
    fun state(frame: Float, index: Int, count: Int, size: Float): io.trimio.engine.motion.UnitState {
        val o = offset.at(frame)[0]
        val a = start.at(frame)[0] + o
        val b = end.at(frame)[0] + o
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        val n = count.coerceAtLeast(1)
        val from = index * 100f / n
        val to = (index + 1) * 100f / n
        val share = ((minOf(hi, to) - maxOf(lo, from)) / (to - from)).coerceIn(0f, 1f) * (amount.at(frame)[0] / 100f)
        if (share <= 0f) return io.trimio.engine.motion.UnitState.REST
        val p = position?.at(frame)
        val em = size.coerceAtLeast(1f)
        return io.trimio.engine.motion.UnitState(
            dx = (p?.getOrNull(0) ?: 0f) * share / em,
            dy = (p?.getOrNull(1) ?: 0f) * share / em,
            scale = scale?.at(frame)?.get(0)?.let { 1f + (it / 100f - 1f) * share } ?: 1f,
            rotation = (rotation?.at(frame)?.get(0) ?: 0f) * share,
            opacity = opacity?.at(frame)?.get(0)?.let { 1f + (it / 100f - 1f) * share } ?: 1f,
        )
    }
}

internal class LLayer(
    val ind: Int?,
    val parent: Int?,
    val inPoint: Float,
    val outPoint: Float,
    val startTime: Float,
    val stretch: Float,
    val transform: LTransform,
    val matte: LMatte,
    val masks: List<LMask>,
    val content: LContent,
    /** Lottie blend mode number (0 normal). */
    val blend: Int,
    val hidden: Boolean,
) {
    fun colours(): List<FloatArray> = when (val c = content) {
        is LContent.Shapes -> c.items.flatMap(LShape::colours)
        is LContent.Solid -> listOf(c.color)
        else -> emptyList()
    }
}

/** A layer's or a shape group's transform: anchor, position (or split x/y), scale %, rotation °, opacity %, skew. */
internal class LTransform(
    val anchor: Value<FloatArray>,
    val position: Value<FloatArray>?,
    val positionX: Value<FloatArray>?,
    val positionY: Value<FloatArray>?,
    val scale: Value<FloatArray>,
    val rotation: Value<FloatArray>,
    val opacity: Value<FloatArray>,
    val skew: Value<FloatArray>,
    val skewAxis: Value<FloatArray>,
) {
    fun opacity(frame: Float): Float = (opacity.at(frame)[0] / 100f).coerceIn(0f, 1f)

    fun matrix(frame: Float): M3 {
        val a = anchor.at(frame)
        val p = position?.at(frame) ?: floatArrayOf(positionX?.at(frame)?.get(0) ?: 0f, positionY?.at(frame)?.get(0) ?: 0f)
        val s = scale.at(frame)
        val r = rotation.at(frame)[0]
        val sk = skew.at(frame)[0]
        var m = M3.translate(p[0], p.getOrElse(1) { 0f })
        if (r != 0f) m *= M3.rotate(r)
        if (sk != 0f) m *= skewMatrix(sk, skewAxis.at(frame)[0])
        val sx = s[0] / 100f
        val sy = s.getOrElse(1) { s[0] } / 100f
        if (sx != 1f || sy != 1f) m *= M3.scale(sx, sy)
        val ax = a.getOrElse(0) { 0f }
        val ay = a.getOrElse(1) { 0f }
        return if (ax == 0f && ay == 0f) m else m * M3.translate(-ax, -ay)
    }

    /** After Effects' skew along an axis: rotate to the axis, shear, rotate back (as lottie-android composes it). */
    private fun skewMatrix(skew: Float, axis: Float): M3 {
        val shear = M3(floatArrayOf(1f, 0f, 0f, kotlin.math.tan(skew * DEG), 1f, 0f, 0f, 0f, 1f))
        return M3.rotate(90f - axis) * shear * M3.rotate(axis - 90f)
    }

    private companion object {
        const val DEG = (kotlin.math.PI / 180.0).toFloat()
    }
}

internal sealed interface LShape {
    fun colours(): List<FloatArray> = emptyList()

    class Group(val items: List<LShape>, val transform: LTransform?) : LShape {
        override fun colours() = items.flatMap { it.colours() }
    }
    class Path(val shape: Value<ShapeData>) : LShape
    class Rect(val position: Value<FloatArray>, val size: Value<FloatArray>, val roundness: Value<FloatArray>) : LShape
    class Ellipse(val position: Value<FloatArray>, val size: Value<FloatArray>) : LShape

    class Fill(val color: Value<FloatArray>, val opacity: Value<FloatArray>, val evenOdd: Boolean) : LShape {
        override fun colours() = listOf(color.at(0f))
    }

    /** A stroke's line: width, cap (1 butt, 2 round, 3 square), join (1 miter, 2 round, 3 bevel), dashes. */
    class Line(val width: Value<FloatArray>, val cap: Int, val join: Int, val miter: Float, val dashes: List<Value<FloatArray>>, val dashOffset: Value<FloatArray>?)

    class Stroke(val color: Value<FloatArray>, val opacity: Value<FloatArray>, val line: Line) : LShape {
        override fun colours() = listOf(color.at(0f))
    }

    /** Linear (1) or radial (2) gradient from [start] to [end]; [colors] holds [stops] offset-rgb quads, then offset-alpha pairs. */
    class Gradient(val kind: Int, val start: Value<FloatArray>, val end: Value<FloatArray>, val stops: Int, val colors: Value<FloatArray>)

    class GradientFill(val gradient: Gradient, val opacity: Value<FloatArray>, val evenOdd: Boolean) : LShape {
        override fun colours() = gradientColours(gradient)
    }

    class GradientStroke(val gradient: Gradient, val opacity: Value<FloatArray>, val line: Line) : LShape {
        override fun colours() = gradientColours(gradient)
    }

    /** Trim paths: [start] and [end] in %, [offset] in degrees (a turn is the whole path). */
    class Trim(val start: Value<FloatArray>, val end: Value<FloatArray>, val offset: Value<FloatArray>) : LShape
}

private fun gradientColours(g: LShape.Gradient): List<FloatArray> {
    val v = g.colors.at(0f)
    return (0 until g.stops).mapNotNull { k -> if (k * 4 + 3 < v.size) floatArrayOf(v[k * 4 + 1], v[k * 4 + 2], v[k * 4 + 3], 1f) else null }
}

/** Reads Lottie JSON into [LottieAnimation]; [read] throws [Unsupported] (or a parse error) for what it will not draw. */
internal object LottieReader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * [texts] on: a template — its text layers become slots ([LContent.Text], numbered in reading
     * order) that the engine fills with the edit's own words; otherwise a document with text is refused.
     */
    fun read(text: String, texts: Boolean = false): LottieAnimation {
        val o = json.parseToJsonElement(text).jsonObject
        if (o.int("ddd") == 1) refuse("3D layers")
        val assets = o["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
        assets.firstOrNull { "layers" !in it }?.let { refuse("image or font assets") }
        if (!texts && (o["chars"] != null || o["fonts"]?.jsonObject?.get("list")?.jsonArray?.isNotEmpty() == true)) refuse("text")
        val comps = assets.associate { a -> a.getValue("id").jsonPrimitive.content to LComp(a.getValue("layers").jsonArray.map { layer(it.jsonObject, texts) }) }
        val animation = LottieAnimation(
            width = o.float("w") ?: refuse("no width"),
            height = o.float("h") ?: refuse("no height"),
            frameRate = o.float("fr")?.takeIf { it > 0f } ?: DEFAULT_RATE,
            inPoint = o.float("ip") ?: 0f,
            outPoint = o.float("op") ?: refuse("no out point"),
            root = LComp(o.getValue("layers").jsonArray.map { layer(it.jsonObject, texts) }),
            assets = comps,
            hold = if (texts) hold(o) else null,
        )
        // Every precomposition a layer names must exist.
        (listOf(animation.root) + comps.values).flatMap { it.layers }.map { it.content }.filterIsInstance<LContent.Precomp>()
            .firstOrNull { it.ref !in comps }?.let { refuse("missing precomposition ${it.ref}") }
        animation.texts.forEachIndexed { i, t -> t.slot = i }
        return animation
    }

    private fun layer(o: JsonObject, texts: Boolean): LLayer {
        val type = o.int("ty") ?: 0
        if (o.int("ddd") == 1) refuse("3D layer")
        if (o["ef"]?.jsonArray?.isNotEmpty() == true) refuse("layer effects")
        val content = when (type) {
            0 -> LContent.Precomp(o.getValue("refId").jsonPrimitive.content, o.float("w") ?: 0f, o.float("h") ?: 0f, o["tm"]?.let { floats(it, ZERO) })
            1 -> LContent.Solid(hex(o["sc"]?.jsonPrimitive?.contentOrNull ?: "#000000"), o.float("sw") ?: 0f, o.float("sh") ?: 0f)
            3 -> LContent.Empty
            4 -> LContent.Shapes(shapes(o["shapes"]?.jsonArray.orEmpty()))
            2 -> refuse("image layer")
            5 -> if (texts) LContent.Text(textLayer(o)) else refuse("text layer")
            else -> LContent.Empty
        }
        return LLayer(
            ind = o.int("ind"), parent = o.int("parent"),
            inPoint = o.float("ip") ?: 0f, outPoint = o.float("op") ?: Float.MAX_VALUE, startTime = o.float("st") ?: 0f,
            stretch = o.float("sr")?.takeIf { it != 0f } ?: 1f,
            transform = transform(o["ks"]?.jsonObject ?: JsonObject(emptyMap())),
            matte = LMatte(o.int("tt") ?: 0, o.int("td") == 1, o.int("tp")),
            masks = o["masksProperties"]?.jsonArray.orEmpty().map { mask(it.jsonObject) },
            content = content,
            blend = o.int("bm") ?: 0,
            hidden = o.bool("hd"),
        )
    }

    /**
     * Where a template rests: a marker named for it (hold, loop, pause, stay), else the longest
     * stillness between keyframes when it is long enough to be the designer's rest; seconds.
     */
    private fun hold(o: JsonObject): ClosedFloatingPointRange<Float>? {
        val rate = o.float("fr")?.takeIf { it > 0f } ?: DEFAULT_RATE
        val ip = o.float("ip") ?: 0f
        val op = o.float("op") ?: return null
        o["markers"]?.jsonArray.orEmpty().map { it.jsonObject }.firstOrNull { m ->
            REST.containsMatchIn(m["cm"]?.jsonPrimitive?.contentOrNull.orEmpty()) && (m.float("dr") ?: 0f) > 0f
        }?.let { m -> val t = m.float("tm") ?: 0f; return ((t - ip) / rate)..((t + (m.float("dr") ?: 0f) - ip) / rate) }
        val times = HashSet<Float>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    (e["k"] as? JsonArray)?.forEach { k -> (k as? JsonObject)?.float("t")?.let(times::add) }
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(o.getValue("layers"))
        o["assets"]?.let(::walk)
        val keys = (times.filter { it > ip && it < op } + ip + op).distinct().sorted()
        val gap = keys.zipWithNext().maxByOrNull { (a, b) -> b - a } ?: return null
        if (gap.second - gap.first < (op - ip) * MIN_REST) return null
        return ((gap.first - ip) / rate)..((gap.second - ip) / rate)
    }

    private fun textLayer(o: JsonObject): LText {
        val t = o["t"]?.jsonObject ?: refuse("text layer without text")
        val d = t["d"]?.jsonObject?.get("k")?.jsonArray?.firstOrNull()?.jsonObject?.get("s")?.jsonObject ?: refuse("text layer without a document")
        fun arr(key: String) = d[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.floatOrNull }?.toFloatArray()
        val animators = t["a"]?.jsonArray.orEmpty().map { it.jsonObject }.map { a ->
            val sel = a["s"]?.jsonObject ?: JsonObject(emptyMap())
            if (sel["x"] != null) refuse("expression selector")
            val props = a["a"]?.jsonObject ?: JsonObject(emptyMap())
            LTextAnimator(
                start = floats(sel["s"], ZERO), end = floats(sel["e"], HUNDRED), offset = floats(sel["o"], ZERO), amount = floats(sel["a"], HUNDRED),
                opacity = props["o"]?.let { floats(it, HUNDRED) }, position = props["p"]?.let { floats(it, ORIGIN) },
                scale = props["s"]?.let { floats(it, floatArrayOf(100f, 100f)) }, rotation = props["r"]?.let { floats(it, ZERO) },
            )
        }
        val size = d.float("s") ?: DEFAULT_TEXT
        return LText(
            text = d["t"]?.jsonPrimitive?.contentOrNull.orEmpty().replace('\r', '\n'),
            size = size,
            font = d["f"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            fill = arr("fc") ?: floatArrayOf(1f, 1f, 1f),
            stroke = arr("sc"),
            strokeWidth = d.float("sw") ?: 0f,
            justify = d.int("j") ?: 0,
            tracking = d.float("tr") ?: 0f,
            lineHeight = d.float("lh") ?: size * DEFAULT_LEADING,
            boxPosition = arr("ps"),
            boxSize = arr("sz"),
            animators = animators,
        )
    }

    private fun mask(o: JsonObject): LMask {
        o["x"]?.let { x -> if (floats(x, ZERO).at(0f)[0] != 0f) refuse("mask expansion") }
        return LMask(o["mode"]?.jsonPrimitive?.contentOrNull?.firstOrNull() ?: 'a', o.bool("inv"), shape(o.getValue("pt")), floats(o["o"], HUNDRED))
    }

    private fun transform(o: JsonObject): LTransform {
        if (o["rx"] != null || o["ry"] != null || o["or"] != null) {
            val tilted = listOf("rx", "ry").any { k -> o[k]?.let { floats(it, ZERO).at(0f)[0] != 0f } == true }
            if (tilted) refuse("3D rotation")
        }
        val p = o["p"]?.jsonObject
        val split = p?.bool("s") == true
        return LTransform(
            anchor = floats(o["a"], ORIGIN),
            position = if (split) null else floats(p, ORIGIN, spatial = true),
            positionX = if (split) floats(p?.get("x"), ZERO) else null,
            positionY = if (split) floats(p?.get("y"), ZERO) else null,
            scale = floats(o["s"], floatArrayOf(100f, 100f)),
            rotation = floats(o["r"] ?: o["rz"], ZERO),
            opacity = floats(o["o"], HUNDRED),
            skew = floats(o["sk"], ZERO),
            skewAxis = floats(o["sa"], ZERO),
        )
    }

    private fun shapes(items: List<JsonElement>): List<LShape> = items.mapNotNull { e ->
        val o = e.jsonObject
        if (o.bool("hd")) return@mapNotNull null
        when (val ty = o["ty"]?.jsonPrimitive?.contentOrNull) {
            "gr" -> {
                val its = o["it"]?.jsonArray.orEmpty()
                val tr = its.firstOrNull { it.jsonObject["ty"]?.jsonPrimitive?.contentOrNull == "tr" }?.jsonObject
                LShape.Group(shapes(its.filter { it.jsonObject["ty"]?.jsonPrimitive?.contentOrNull != "tr" }), tr?.let(::transform))
            }
            "sh" -> LShape.Path(shape(o.getValue("ks")))
            "rc" -> LShape.Rect(floats(o["p"], ORIGIN), floats(o["s"], ORIGIN), floats(o["r"], ZERO))
            "el" -> LShape.Ellipse(floats(o["p"], ORIGIN), floats(o["s"], ORIGIN))
            "fl" -> LShape.Fill(floats(o["c"], BLACK), floats(o["o"], HUNDRED), o.int("r") == 2)
            "st" -> LShape.Stroke(floats(o["c"], BLACK), floats(o["o"], HUNDRED), line(o))
            "gf" -> LShape.GradientFill(gradient(o), floats(o["o"], HUNDRED), o.int("r") == 2)
            "gs" -> LShape.GradientStroke(gradient(o), floats(o["o"], HUNDRED), line(o))
            "tm" -> LShape.Trim(floats(o["s"], ZERO), floats(o["e"], HUNDRED), floats(o["o"], ZERO))
            // Merge paths: drawn unmerged, as the reference web player draws them.
            "mm", "tr" -> null
            else -> refuse("shape item $ty")
        }
    }

    private fun line(o: JsonObject): LShape.Line {
        val dashes = o["d"]?.jsonArray.orEmpty().map { it.jsonObject }
        return LShape.Line(
            width = floats(o["w"], ONE),
            cap = o.int("lc") ?: 2,
            join = o.int("lj") ?: 2,
            miter = o.float("ml") ?: 4f,
            dashes = dashes.filter { it["n"]?.jsonPrimitive?.contentOrNull != "o" }.map { floats(it["v"], ZERO) },
            dashOffset = dashes.firstOrNull { it["n"]?.jsonPrimitive?.contentOrNull == "o" }?.let { floats(it["v"], ZERO) },
        )
    }

    private fun gradient(o: JsonObject): LShape.Gradient {
        val g = o.getValue("g").jsonObject
        return LShape.Gradient(o.int("t") ?: 1, floats(o["s"], ORIGIN), floats(o["e"], ORIGIN), g.int("p") ?: 0, floats(g["k"], ZERO))
    }

    // ------------------------------------------------------------------ values

    private fun floats(e: JsonElement?, default: FloatArray, spatial: Boolean = false): Value<FloatArray> {
        val o = e as? JsonObject ?: return Value.Static(default)
        if ((o["x"] as? JsonPrimitive)?.isString == true) refuse("expression")
        val k = o["k"] ?: return Value.Static(default)
        if (!keyframed(k)) return Value.Static(numbers(k))
        val keys = k.jsonArray.map { kf -> key(kf.jsonObject) { numbers(it) } }
        if (spatial) Lerp.prepareArcs(keys)
        return Value.Keyed(keys, Lerp::floats)
    }

    private fun shape(e: JsonElement): Value<ShapeData> {
        val o = e.jsonObject
        if ((o["x"] as? JsonPrimitive)?.isString == true) refuse("expression")
        val k = o.getValue("k")
        if (!keyframed(k)) return Value.Static(shapeData(k))
        return Value.Keyed(k.jsonArray.map { kf -> key(kf.jsonObject, ::shapeData) }, Lerp::shape)
    }

    private fun keyframed(k: JsonElement) = k is JsonArray && k.firstOrNull().let { it is JsonObject && "t" in it }

    private fun <T> key(o: JsonObject, value: (JsonElement) -> T): Value.Key<T> {
        val out = o["o"]?.jsonObject
        val inn = o["i"]?.jsonObject
        return Value.Key(
            t = o.float("t") ?: 0f,
            startOrNull = o["s"]?.let(value),
            end = o["e"]?.let(value),
            hold = o.int("h") == 1,
            ox = numbers(out?.get("x")), oy = numbers(out?.get("y")),
            ix = numbers(inn?.get("x")), iy = numbers(inn?.get("y")),
            to = o["to"]?.let(::numbers), ti = o["ti"]?.let(::numbers),
        )
    }

    private fun shapeData(e: JsonElement): ShapeData {
        val o = (if (e is JsonArray) e.first() else e).jsonObject
        fun pts(k: String) = o[k]?.jsonArray.orEmpty().flatMap { p -> p.jsonArray.take(2).map { it.jsonPrimitive.floatOrNull ?: 0f } }.toFloatArray()
        val v = pts("v")
        val i = pts("i").takeIf { it.size == v.size } ?: FloatArray(v.size)
        val out = pts("o").takeIf { it.size == v.size } ?: FloatArray(v.size)
        return ShapeData(o.bool("c"), v, i, out)
    }

    private fun numbers(e: JsonElement?): FloatArray = when (e) {
        null -> FloatArray(0)
        is JsonPrimitive -> floatArrayOf(e.floatOrNull ?: 0f)
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }.toFloatArray()
        else -> FloatArray(0)
    }

    private fun hex(s: String): FloatArray {
        val h = s.removePrefix("#").take(6).padEnd(6, '0')
        val rgb = h.toIntOrNull(16) ?: 0
        return floatArrayOf((rgb shr 16 and 0xFF) / 255f, (rgb shr 8 and 0xFF) / 255f, (rgb and 0xFF) / 255f, 1f)
    }

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.floatOrNull?.toInt() ?: it.booleanOrNull?.let { b -> if (b) 1 else 0 } }
    private fun JsonObject.float(k: String): Float? = (this[k] as? JsonPrimitive)?.floatOrNull
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.intOrNull == 1) } ?: false

    private const val DEFAULT_RATE = 30f
    private const val DEFAULT_TEXT = 48f
    private const val MIN_REST = 0.15f
    private val REST = Regex("(?i)hold|loop|pause|stay|rest")
    private const val DEFAULT_LEADING = 1.2f
    private val ZERO = floatArrayOf(0f)
    private val ONE = floatArrayOf(1f)
    private val HUNDRED = floatArrayOf(100f)
    private val ORIGIN = floatArrayOf(0f, 0f)
    private val BLACK = floatArrayOf(0f, 0f, 0f, 1f)
}
