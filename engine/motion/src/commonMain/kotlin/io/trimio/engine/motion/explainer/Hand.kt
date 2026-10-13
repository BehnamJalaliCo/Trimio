package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.trimio.core.brand.BrandFonts
import io.trimio.core.model.text.Numerals
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Decoration
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.VectorNode
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.recipe.Fitted
import io.trimio.engine.motion.recipe.Icons
import io.trimio.engine.motion.recipe.Kit
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Move

/** The explainer's type roles, in the engine's voices (Persian is never tracked; see [Kit.voiceFor]). */
internal object Voices {
    val title = Look.Voice(BrandFonts.Role.Display, 700, lineHeight = 1.12f)
    val head = Look.Voice(BrandFonts.Role.Ui, 800, lineHeight = 1.24f)
    val body = Look.Voice(BrandFonts.Role.Ui, 700, lineHeight = 1.26f)
    val label = Look.Voice(BrandFonts.Role.Ui, 650, lineHeight = 1.24f)
    val kicker = Look.Voice(BrandFonts.Role.Ui, 600, lineHeight = 1.26f)
    val number = Look.Voice(BrandFonts.Role.Accent, 700, lineHeight = 1.05f)
    val big = Look.Voice(BrandFonts.Role.Display, 900, lineHeight = 1.14f)
}

/**
 * The stage's drawing hand for one chapter: its palette and accent, the reading direction, the
 * size unit, and the pieces every primitive is made of (words, counters, cards, glyphs, ticks,
 * entrances), all holding the contrast floor against what they sit on.
 */
internal class Hand(
    val text: TextLayoutEngine,
    val pal: ExplainerPalette,
    val accentIndex: Int,
    val rtl: Boolean,
    val u: Float,
    val icons: IconResolver,
    /** The widest and tallest an element may be (the stage). */
    val maxW: Float,
    val maxH: Float,
    val seed: Long,
) {
    val ground: Color = pal.background
    val ink: Color = pal.ink
    val muted: Color = pal.muted
    val card: Color = pal.card
    val accent: Color = pal.accent(accentIndex)
    val accentInk: Color = pal.accentText(accentIndex)
    val accentOnCard: Color = pal.accentText(accentIndex, pal.card)
    val accentBody: Color = pal.accentBody(accentIndex)
    val onAccent: Color = pal.onAccent(accentIndex)

    /** A second accent for two-tone moments (the rest of a row, the other side). */
    val second: Color = pal.accent(accentIndex + 1)

    /** The accent washed into the card: soft backs for icons and avatars. */
    val wash: Color = lerp(pal.card, accent, if (pal.dark) 0.22f else 0.16f)

    val dir: Float get() = if (rtl) -1f else 1f
    val radius: Float get() = 22f * u

    /** Persian digits (and the Persian percent sign) on a Persian film. */
    fun say(s: String): String = if (persian) Hand.persianDigits(s) else s

    /** Readable [c] on [on]. */
    fun readable(c: Color, on: Color): Color = ExplainerPalette.readable(c, on, if (ExplainerPalette.ratio(ink, on) >= ExplainerPalette.FLOOR) ink else Look.textOn(on))

    // ------------------------------------------------------------------ type

    /** The largest size ≤ [size] at which [s] fits [w] in at most [lines] lines. */
    fun fit(s: String, voice: Look.Voice, size: Float, w: Float, lines: Int = 2): Fitted {
        val v = Kit.voiceFor(voice, s)
        var px = size
        var last: Fitted? = null
        repeat(FIT_STEPS) {
            val type = v.at(px)
            val block = text.layout(s, type, w, TextAlign.Center, isRtl(s))
            val f = Fitted(type, block.width, block.height, block.lines.size)
            last = f
            if (block.width <= w + 0.5f && block.lines.size <= lines) return f
            px *= 0.93f
        }
        return last!!
    }

    /** [texts] at one shared size (the largest all of them fit at): a row whose labels change size reads as a mistake. */
    fun fitAll(texts: List<String>, voice: Look.Voice, size: Float, w: Float, lines: Int = 2): List<Fitted> {
        val first = texts.map { fit(it, voice, size, w, lines) }
        val common = first.minOfOrNull { it.type.size } ?: size
        return texts.map { fit(it, voice, common, w, lines) }
    }

    fun isRtl(s: String) = rtl || Kit.persian(s)

    /**
     * Words centred at [cx], [cy] in [color] (animatable), revealed by [reveal] from [at], held to
     * the contrast floor against [on].
     */
    fun words(
        s: String, f: Fitted, color: ColorAnim, cx: Float, cy: Float, on: Color, at: Float,
        reveal: Kit.Reveal = Kit.Reveal.Rise, align: TextAlign = TextAlign.Center, decorations: List<Decoration> = emptyList(), name: String = "words",
    ): TextNode {
        val node = TextNode(
            s, f.type, Fill.Solid(color), maxWidth = f.width + 2f, align = align, rtl = isRtl(s),
            animators = listOfNotNull(reveal.animator(at)), decorations = decorations, transform = Transform(x = cx.anim, y = cy.anim), name = name,
        )
        node.onBody = true
        node.ground = on
        return node
    }

    fun words(s: String, f: Fitted, color: Color, cx: Float, cy: Float, on: Color, at: Float, reveal: Kit.Reveal = Kit.Reveal.Rise, align: TextAlign = TextAlign.Center, name: String = "words") =
        words(s, f, ColorAnim.of(color), cx, cy, on, at, reveal, align, name = name)

    /** Words that appear one by one across [from]..[to] (a "build" of a sentence). */
    fun wordByWord(from: Float, to: Float, count: Int): TextAnimator = TextAnimator(
        TextUnit.Word, UnitState(dy = 0.35f, opacity = 0f), at = from, duration = 0.42f,
        stagger = if (count <= 1) 0f else ((to - from - 0.42f) / (count - 1)).coerceIn(0.08f, 0.6f), ease = Easing.ExpoOut,
    )

    /** A figure that rolls (Persian digits on a Persian film), centred at [cx], [cy]. */
    fun counter(value: Anim, decimals: Int, suffix: String, size: Float, color: Color, cx: Float, cy: Float, on: Color, at: Float, prefix: String = "", anchorX: Float = 0.5f): CounterNode {
        val node = CounterNode(
            value = value, decimals = decimals, prefix = prefix, suffix = say(suffix), persianDigits = rtl, grouping = true,
            type = Voices.number.at(size), fill = color.fill(),
            transform = Transform(x = cx.anim, y = cy.anim, anchorX = anchorX, opacity = Anim.tween(0f, 1f, at, at + 0.25f)), name = "figure",
        )
        node.onBody = true
        node.ground = on
        return node
    }

    /** Width of a figure's widest form (so a rolling number never pushes its neighbours). */
    fun figureWidth(v: Double, decimals: Int, suffix: String, size: Float): Float {
        val s = say(io.trimio.engine.motion.formatFixed(v.toFloat(), decimals, true) + suffix)
        return text.measureLine(s, text.style(Voices.number.at(size), rtl = false, s)).size.width.toFloat()
    }

    // ------------------------------------------------------------------ shapes

    fun rect(
        cx: Float, cy: Float, w: Float, h: Float, r: Float, color: ColorAnim?, stroke: Stroke? = null, shadow: Shadow? = null, name: String = "rect",
        width: Anim? = null, opacity: Anim = Anim.One, anchorX: Float = 0.5f,
    ) = ShapeNode(
        ShapeSpec.Rect(width ?: w.anim, h.anim, r.anim), fill = color?.let { Fill.Solid(it) }, stroke = stroke, shadow = shadow,
        transform = Transform(x = cx.anim, y = cy.anim, anchorX = anchorX, opacity = opacity), name = name,
    )

    fun rect(cx: Float, cy: Float, w: Float, h: Float, r: Float, color: Color, name: String = "rect") = rect(cx, cy, w, h, r, ColorAnim.of(color), name = name)

    /** A UI card: the palette's card, a hairline rim and a soft shadow. */
    fun card(cx: Float, cy: Float, w: Float, h: Float, r: Float = radius, fill: ColorAnim = ColorAnim.of(card), rim: Color = pal.rim, name: String = "card") =
        rect(cx, cy, w, h, r, fill, stroke = Stroke(rim.fill(), maxOf(1.5f, 2f * u)), shadow = shadow(), name = name)

    fun shadow() = Shadow(Color.Black.copy(alpha = if (pal.dark) 0.28f else 0.07f), 0f, 6f * u, 18f * u)

    fun dot(cx: Float, cy: Float, d: Float, color: ColorAnim, name: String = "dot", stroke: Stroke? = null) = ShapeNode(
        ShapeSpec.Ellipse(d.anim, d.anim), fill = Fill.Solid(color), stroke = stroke, transform = Transform(x = cx.anim, y = cy.anim), name = name,
    )

    /** A polyline in local px (smooth for hand-drawn strokes), drawn on over [from]..[to]. */
    fun stroke(points: List<Pair<Float, Float>>, color: ColorAnim, width: Float, from: Float, to: Float, smooth: Boolean = true, name: String = "stroke"): ShapeNode {
        val ox = points.minOf { it.first }
        val oy = points.minOf { it.second }
        return ShapeNode(
            ShapeSpec.Polyline(points.map { (x, y) -> x - ox to y - oy }, smooth), stroke = Stroke(Fill.Solid(color), width),
            trimEnd = Anim.tween(0f, 1f, from, to, Easing.Bezier(0.45f, 0.05f, 0.25f, 1f)),
            transform = Transform(x = ox.anim, y = oy.anim, anchorX = 0f, anchorY = 0f), name = name,
        )
    }

    /** A picture for [query] at [cx], [cy], [size] px, in [color] (pictures with their own colours keep them). */
    fun glyph(query: String, cx: Float, cy: Float, size: Float, color: ColorAnim, at: Float): Node = when (val g = Glyphs.resolve(query, icons)) {
        is Glyph.Picture -> VectorNode(
            g.icon, size * 0.92f, tint = color, assemble = Anim.tween(0f, 1f, at, at + 0.5f, Easing.Linear),
            transform = Transform(x = cx.anim, y = cy.anim), name = "picture",
        )
        is Glyph.Line -> ShapeNode(
            ShapeSpec.Path(g.path, Icons.VIEWPORT, size), stroke = Stroke(Fill.Solid(color), size * 0.085f),
            trimEnd = Anim.tween(0f, 1f, at, at + 0.55f, Easing.ExpoOut), transform = Transform(x = cx.anim, y = cy.anim), name = "glyph",
        )
    }

    /** A check mark [size] px drawn on from [at], in [color] (the words' colour on the accent by default). */
    fun check(cx: Float, cy: Float, size: Float, at: Float, color: Color = onAccent): ShapeNode = ShapeNode(
        ShapeSpec.Path(Icons.path("check")!!, Icons.VIEWPORT, size), stroke = Stroke(color.fill(), size * 0.15f),
        trimEnd = Anim.tween(0f, 1f, at, at + 0.32f, Easing.ExpoOut), transform = Transform(x = cx.anim, y = cy.anim), name = "check",
    )

    /** A round tick that pops in at [at] and draws its check. */
    fun tick(cx: Float, cy: Float, d: Float, at: Float, fill: Color = accentBody, mark: Color = onAccent, ring: Color = ground): Node {
        val check = check(cx, cy, d * 0.62f, at + 0.1f, mark)
        val ring = ShapeNode(
            ShapeSpec.Ellipse((d + 5f * u).anim, (d + 5f * u).anim), fill = ring.fill(), transform = Transform(x = cx.anim, y = cy.anim), name = "tick-ring",
        )
        return pop(listOf(ring, dot(cx, cy, d, ColorAnim.of(fill), "tick"), check), Box.around(cx, cy, d, d), at, "tick")
    }

    /** [children] (local px) springing up from small about [box], from [at]. */
    fun pop(children: List<Node>, box: Box, at: Float, name: String): Group = Kit.item(Move.Pop, box, at, dir, children, name)

    /** [children] rising a little into place about [box], from [at]. */
    fun rise(children: List<Node>, box: Box, at: Float, name: String): Group = Kit.item(Move.Rise, box, at, dir, children, name)

    /** A gentle accent pulse of scale at each of [times]. */
    fun pulse(times: List<Float>, amount: Float = 0.06f): Anim {
        if (times.isEmpty()) return Anim.One
        val keys = ArrayList<Anim.Key>()
        keys += Anim.Key(times.first() - 0.01f, 1f, Easing.Hold)
        for (t in times.sorted()) {
            val start = maxOf(t, keys.last().t)
            keys += Anim.Key(start, 1f, Easing.Hold)
            keys += Anim.Key(start + 0.22f, 1f + amount, Easing.ExpoOut)
            keys += Anim.Key(start + 0.7f, 1f, Easing.SineInOut)
        }
        return Anim.keys(keys)
    }

    /** [children] (local px) that fade in at [at] (a checked or picked state laid over the plain one). */
    fun layer(children: List<Node>, at: Float, name: String): Group =
        Group(children, Transform(anchorX = 0f, anchorY = 0f, opacity = Anim.tween(0f, 1f, at, at + Track.TURN, Easing.Standard)), start = at, name = name)

    /** [children] that fade in at each "on" step and out at each "off" step of [steps]. */
    fun toggled(children: List<Node>, steps: List<Pair<Float, Boolean>>, name: String): Group? {
        if (steps.isEmpty()) return null
        val keys = ArrayList<Anim.Key>()
        keys += Anim.Key(steps.first().first - 0.001f, 0f, Easing.Hold)
        for ((t, on) in steps) {
            val start = maxOf(t, keys.last().t + 0.001f)
            keys += Anim.Key(start, keys.last().v, Easing.Hold)
            keys += Anim.Key(start + Track.TURN, if (on) 1f else 0f, Easing.Standard)
        }
        return Group(children, Transform(anchorX = 0f, anchorY = 0f, opacity = Anim.keys(keys)), start = steps.first().first, name = name)
    }

    /** Splits items of [widths] into rows no wider than [maxW] (with [gap] between), in reading order. */
    fun wrap(widths: List<Float>, maxW: Float, gap: Float, maxPerRow: Int = Int.MAX_VALUE): List<List<Int>> {
        val rows = ArrayList<MutableList<Int>>()
        var used = 0f
        for ((i, w) in widths.withIndex()) {
            val row = rows.lastOrNull()
            if (row == null || row.size >= maxPerRow || used + gap + w > maxW) {
                rows += mutableListOf(i)
                used = w
            } else {
                row += i
                used += gap + w
            }
        }
        return rows
    }

    /** Reading-start x inside a span [left]..[right] at [inset]. */
    fun startX(left: Float, right: Float, inset: Float) = if (rtl) right - inset else left + inset

    /** Positions of [n] items laid along a span of [total] px in reading order (RTL: right to left), centred in [w]. */
    fun row(n: Int, item: Float, gap: Float, w: Float): List<Float> {
        val total = n * item + (n - 1).coerceAtLeast(0) * gap
        val left = (w - total) / 2f
        return List(n) { i -> val k = if (rtl) n - 1 - i else i; left + k * (item + gap) + item / 2f }
    }

    /** Persian digits are for Persian films (Arabic keeps its own). */
    var persian: Boolean = rtl

    companion object {
        private const val FIT_STEPS = 24

        /** Persian digits, with the Persian decimal point and percent sign next to digits only. */
        fun persianDigits(s: String): String {
            val p = Numerals.toPersian(s)
            val out = StringBuilder(p.length)
            for ((i, c) in p.withIndex()) {
                val before = p.getOrNull(i - 1)?.let { it in '۰'..'۹' } == true
                val after = p.getOrNull(i + 1)?.let { it in '۰'..'۹' } == true
                out.append(
                    when {
                        c == '.' && before && after -> '٫'
                        c == '%' && (before || after) -> '٪'
                        else -> c
                    },
                )
            }
            return out.toString()
        }
    }
}
