package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.core.brand.BrandFonts
import io.trimio.core.model.text.Numerals
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Contrast
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Mask
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.style.StyleBook

/**
 * The choreographies a family plays: one entrance and its matching exit for the whole graphic,
 * and a smaller echo of it for each item inside (rows, chips, cards).
 */
enum class Move {
    /** Rises a little from below and settles; leaves lifting. */
    Rise,
    /** Springs up from small; leaves shrinking. */
    Pop,
    /** Slides in from the reading start; leaves toward the reading end. */
    Slide,
    /** Revealed by a wipe in reading order; wiped away the same way. */
    Wipe,
    /** Flips up around its middle in 3D; flips away. */
    Flip,
    /** Comes into focus from a soft blur; goes out of focus. */
    Focus,
    /** Drops from above and lands with a bounce; falls away. */
    Drop,
    /** Unfolds from its top edge like a printed slip; folds back up. */
    Unfold,
}

/** Type treatments a family sets its words in, each spoken in the look's own voices. */
enum class Typo {
    /** The look's headline voice, big; labels as a small eyebrow. */
    Poster,
    /** The body voice made heavy, figures in the number voice: magazine-like. */
    Editorial,
    /** The interface voice throughout, figures in the accent face: a data screen. */
    Data,
}

/**
 * A premium family: one device ([Kind.Element]) in [layouts] layouts × the [moves] it plays, so
 * [variants] treatments ([Variety] picks one per beat from the edit's seed and what the beat
 * says, weighted by the look). The type treatment follows the look and the treatment. Families
 * build through [Kit] so every one keeps the same floors: text at 4.5:1 on what it sits on,
 * everything inside its slot, no distortion over words, Persian set right to left.
 */
abstract class Family(name: String, val moves: List<Move>, final override val layouts: Int = 3) : Recipe(name, Kind.Element) {
    final override val variants: Int get() = layouts * moves.size

    /** How much the look wants [layout] (zero rules it out); 1 by default. */
    open fun layoutWeight(look: Look, cue: Cue, layout: Int): Float = 1f

    override fun variantWeights(look: Look, cue: Cue): List<Float> =
        List(variants) { v -> layoutWeight(look, cue, v % layouts) * Kit.moveWeight(look, moves[v / layouts]) }

    final override fun build(cue: Cue, look: Look, fit: Fitter): Built = compose(cue, look, fit, Kit.plan(this, cue, look))

    abstract fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built
}

/** The shared hand of the families: treatments, surfaces, readable colours, type and choreography. */
object Kit {
    /** A beat's treatment, decoded: which layout, which move, which type, and the reading direction. */
    class Plan(val layout: Int, val move: Move, val typo: Typo, val at: Float, val rtl: Boolean) {
        /** +1 when reading runs left to right, −1 right to left. */
        val dir: Float get() = if (rtl) -1f else 1f
    }

    fun plan(f: Family, cue: Cue, look: Look): Plan {
        val v = cue.variant.mod(f.variants.coerceAtLeast(1))
        return Plan(v % f.layouts, f.moves[v / f.layouts], typo(look, cue, f.name), cue.at - Craft.LEAD, cue.rtl)
    }

    // ------------------------------------------------------------------ choice

    /** How much the look's motion grammar likes [move] (its text entrances stand for its taste). */
    fun moveWeight(look: Look, move: Move): Float {
        val e = StyleBook.of(look.name)?.motion?.entrances.orEmpty()
        fun w(vararg keys: String) = if (e.isEmpty()) 1f else keys.maxOf { e[it] ?: UNLISTED }
        val base = when (move) {
            Move.Rise -> w("mask-rise", "stack")
            Move.Pop -> w("slam", "spread")
            Move.Slide -> w("stack", "glitch", "slam")
            Move.Wipe -> w("mask-rise", "type-on")
            Move.Flip -> w("flip")
            Move.Focus -> w("blur-in")
            Move.Drop -> w("slam", "spread")
            Move.Unfold -> w("type-on", "mask-rise")
        }
        // Calm languages do not bounce; sharp Swiss ones neither.
        val calm = look.settle == Easing.SineInOut
        val bounce = move == Move.Pop || move == Move.Drop
        return base * if (bounce && (calm || look.roundness == 0f && look.settle == Easing.QuintOut)) CALM_BOUNCE else 1f
    }

    /** The type treatment: the look's own first (its headline role decides), the others with a variety seed. */
    fun typo(look: Look, cue: Cue, family: String): Typo {
        val order = when (look.headline.role) {
            BrandFonts.Role.Ui -> listOf(Typo.Data, Typo.Poster, Typo.Editorial)
            BrandFonts.Role.Expressive -> listOf(Typo.Editorial, Typo.Poster, Typo.Data)
            else -> listOf(Typo.Poster, Typo.Editorial, Typo.Data)
        }
        if (cue.variety == 0) return order[0]
        val u = Variety.unit(cue.variety, family.hashCode(), cue.variant)
        return when {
            u < TYPO_OWN -> order[0]
            u < TYPO_SECOND -> order[1]
            else -> order[2]
        }
    }

    // ------------------------------------------------------------------ colour

    fun ratio(a: Color, b: Color): Float = Contrast.ratio(Contrast.luminance(a), Contrast.luminance(b))

    /** [top] composited over [under] (alpha honoured). */
    fun over(top: Color, under: Color) = Color(
        top.red * top.alpha + under.red * (1f - top.alpha), top.green * top.alpha + under.green * (1f - top.alpha),
        top.blue * top.alpha + under.blue * (1f - top.alpha),
    )

    fun light(c: Color) = Contrast.luminance(c) > 0.4f

    /** Text on [ground]: the look's ink when it reads there at 4.5:1, else near-black or bone. */
    fun inkOn(look: Look, ground: Color): Color = if (ratio(look.ink, ground) >= FLOOR) look.ink else Look.textOn(ground)

    /**
     * A coloured text ([c]: up green, accent, heat) made readable on [ground]: itself when it
     * clears 4.5:1, else moved toward the readable ink just as far as it must.
     */
    fun tint(look: Look, c: Color, ground: Color): Color {
        if (ratio(c, ground) >= FLOOR) return c
        val target = inkOn(look, ground)
        var lo = 0f
        var hi = 1f
        repeat(BISECT) {
            val m = (lo + hi) / 2f
            if (ratio(androidx.compose.ui.graphics.lerp(c, target, m), ground) >= FLOOR + 0.05f) hi = m else lo = m
        }
        return androidx.compose.ui.graphics.lerp(c, target, hi)
    }

    /** Secondary text on [ground]: the look's muted grey when it still reads, else the ink. */
    fun quiet(look: Look, ground: Color): Color = if (ratio(look.muted, ground) >= FLOOR) look.muted else inkOn(look, ground)

    // ------------------------------------------------------------------ surfaces

    /** What a body is painted with and the colour words on it are read against. */
    class Surface(val fill: Fill, val ground: Color, val stroke: Stroke?, val shadow: Shadow?, val glass: Boolean)

    enum class Tone { Card, Accent, Ink, Hot, Raised }

    /**
     * A body in the look's material: over footage a solid body (it never depends on the picture);
     * on a stage the look's card (frosted glass where the look has a shader stage); [Tone.Accent]
     * the emphasis colour, [Tone.Ink] the inverse plate, [Tone.Hot] the heat, [Tone.Raised] a
     * card one step lighter than the card (rows inside a card).
     */
    fun surface(look: Look, cue: Cue, tone: Tone = Tone.Card): Surface {
        val lightCanvas = light(look.canvas)
        val shadow = if (cue.overMedia || look.roundness > 0f) Shadow(Color.Black.copy(alpha = if (lightCanvas) 0.16f else 0.34f), 0f, 16f, 36f) else null
        // Hard offset shadow for sharp light looks: the neo-brutal print.
        val hard = Shadow(look.ink.copy(alpha = 0.9f), 8f, 8f, 0.1f).takeIf { lightCanvas && look.roundness == 0f && !cue.overMedia }
        return when (tone) {
            Tone.Accent -> Surface(look.accent.fill(), look.accent, null, shadow, false)
            Tone.Hot -> Surface(look.hot.fill(), look.hot, null, shadow, false)
            Tone.Ink -> Surface(look.ink.fill(), look.ink, null, shadow, false)
            Tone.Raised -> {
                val base = body(look, cue)
                val raised = androidx.compose.ui.graphics.lerp(base, look.ink, if (lightCanvas) 0.05f else 0.08f)
                Surface(raised.fill(), raised, null, null, false)
            }
            Tone.Card -> {
                val ground = body(look, cue)
                val glass = !cue.overMedia && Stages.shader(look) != null
                val fill = if (cue.overMedia || lightCanvas) ground.fill() else Fill.Linear(listOf(look.card, look.card.copy(alpha = look.card.alpha * 0.55f)), angle = 70f.anim)
                Surface(fill, ground, Stroke(look.cardRim.fill(), 2f), hard ?: shadow, glass)
            }
        }
    }

    /** An opaque card (piles and stacks, where a card must hide the one under it). */
    fun solid(look: Look, cue: Cue): Surface {
        val s = surface(look, cue)
        val ground = if (light(look.canvas)) s.ground else androidx.compose.ui.graphics.lerp(look.canvas, look.ink, 0.1f)
        return Surface(ground.fill(), ground, s.stroke, s.shadow ?: Shadow(Color.Black.copy(alpha = 0.3f), 0f, 12f, 28f), false)
    }

    /** The colour of the look's card as words see it. */
    fun body(look: Look, cue: Cue): Color = when {
        cue.overMedia -> if (light(look.canvas)) over(look.card.copy(alpha = 1f), look.canvas) else androidx.compose.ui.graphics.lerp(look.canvas, look.ink, 0.06f)
        light(look.canvas) -> over(look.card, look.canvas)
        else -> over(look.card, look.canvas)
    }

    /** A panel of [s] centred at [cx], [cy] with the frosted glass under it when the look has one. */
    fun panel(look: Look, cue: Cue, s: Surface, cx: Float, cy: Float, w: Float, h: Float, radius: Float, name: String): List<Node> {
        val r = radius * look.roundness
        val node = ShapeNode(ShapeSpec.Rect(w.anim, h.anim, r.anim), fill = s.fill, stroke = s.stroke, shadow = s.shadow, transform = Transform(x = cx.anim, y = cy.anim), name = name)
        val glass = if (s.glass) Stages.glass(look, cue, cx - w / 2f, cy - h / 2f, w, h, r, Anim.One) else null
        return listOfNotNull(glass, node)
    }

    fun rect(cx: Float, cy: Float, w: Float, h: Float, color: Color, radius: Float = 0f, name: String = "rect") =
        ShapeNode(ShapeSpec.Rect(w.anim, h.anim, radius.anim), fill = color.fill(), transform = Transform(x = cx.anim, y = cy.anim), name = name)

    fun dot(cx: Float, cy: Float, d: Float, color: Color, name: String = "dot") =
        ShapeNode(ShapeSpec.Ellipse(d.anim, d.anim), fill = color.fill(), transform = Transform(x = cx.anim, y = cy.anim), name = name)

    fun icon(name: String, cx: Float, cy: Float, size: Float, color: Color, at: Float, width: Float = size * 0.09f): ShapeNode {
        val path = Icons.path(name) ?: Icons.path("spark")!!
        return ShapeNode(
            ShapeSpec.Path(path, Icons.VIEWPORT, size), stroke = Stroke(color.fill(), width),
            trimEnd = Anim.tween(0f, 1f, at, at + DRAW, Easing.ExpoOut), transform = Transform(x = cx.anim, y = cy.anim), name = "icon-$name",
        )
    }

    /** A straight rule from ([x0], [y0]) to ([x1], [y1]) that draws on from [at]. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Color, width: Float, at: Float, duration: Float = DRAW, name: String = "line"): ShapeNode {
        val ox = minOf(x0, x1)
        val oy = minOf(y0, y1)
        return ShapeNode(
            ShapeSpec.Polyline(listOf(x0 - ox to y0 - oy, x1 - ox to y1 - oy), smooth = false), stroke = Stroke(color.fill(), width),
            trimEnd = Anim.tween(0f, 1f, at, at + duration, Easing.ExpoInOut),
            transform = Transform(x = ox.anim, y = oy.anim, anchorX = 0f, anchorY = 0f), name = name,
        )
    }

    /** A polyline through frame-space [points] that draws on from [at]. */
    fun path(points: List<Pair<Float, Float>>, color: Color, width: Float, at: Float, duration: Float, smooth: Boolean = false, name: String = "path"): ShapeNode {
        val ox = points.minOf { it.first }
        val oy = points.minOf { it.second }
        return ShapeNode(
            ShapeSpec.Polyline(points.map { (x, y) -> x - ox to y - oy }, smooth), stroke = Stroke(color.fill(), width),
            trimEnd = Anim.tween(0f, 1f, at, at + duration, Easing.ExpoInOut),
            transform = Transform(x = ox.anim, y = oy.anim, anchorX = 0f, anchorY = 0f), name = name,
        )
    }

    // ------------------------------------------------------------------ type

    /** The voice for a family's title in [typo]. */
    fun titleVoice(look: Look, typo: Typo): Look.Voice = when (typo) {
        Typo.Poster -> look.headline
        Typo.Editorial -> look.body.copy(weight = maxOf(look.body.weight, HEAVY), lineHeight = 1.22f)
        Typo.Data -> Look.Voice(BrandFonts.Role.Ui, maxOf(look.caption.weight, HEAVY), lineHeight = 1.2f)
    }

    /** Small labels: the look's label voice (tracked caps in Latin), the body voice when editorial. */
    fun labelVoice(look: Look, typo: Typo): Look.Voice = when (typo) {
        Typo.Editorial -> look.body.copy(weight = maxOf(look.body.weight, LABEL), tracking = 0f)
        Typo.Data -> Look.Voice(BrandFonts.Role.Ui, LABEL, lineHeight = 1.2f, tracking = 0.04f)
        Typo.Poster -> look.label.copy(weight = maxOf(look.label.weight, LABEL))
    }

    fun numberVoice(look: Look, typo: Typo): Look.Voice = when (typo) {
        Typo.Data -> look.number.copy(role = BrandFonts.Role.Accent)
        else -> look.number
    }

    /** Persian is never tracked (letter spacing breaks its joins). */
    fun voiceFor(v: Look.Voice, text: String): Look.Voice = if (persian(text)) v.copy(tracking = 0f) else v

    fun persian(s: String) = s.any { it in '؀'..'ۿ' }

    /** Fits [text] in [voice] (tracking dropped for Persian) at most [size] into [w] × [h]. */
    fun fit(fit: Fitter, text: String, voice: Look.Voice, size: Float, w: Float, h: Float, lines: Int = 1): Fitted =
        fit.fit(text, voiceFor(voice, text), size, w, h, lines)

    /**
     * [texts] fitted at one shared size (the largest every one of them fits at): a list whose rows
     * change size from row to row reads as a mistake.
     */
    fun uniform(fit: Fitter, texts: List<String>, voice: Look.Voice, size: Float, w: Float, h: Float, lines: Int = 1): List<Fitted> {
        val first = texts.map { fit(fit, it, voice, size, w, h, lines) }
        val common = first.minOfOrNull { it.type.size } ?: size
        return texts.map { fit(fit, it, voice, common, w, h, lines) }
    }

    /**
     * Words that sit on a body: [color] at [x], [y] (centre), revealed by [reveal] from [at], held
     * against [ground] (the contrast floor), never guarded over footage (the body is their ground).
     */
    fun words(
        text: String, f: Fitted, color: Color, x: Float, y: Float, at: Float, ground: Color?, rtl: Boolean,
        reveal: Reveal = Reveal.Rise, align: TextAlign = TextAlign.Center, name: String = "words",
    ): TextNode {
        val node = TextNode(
            text, f.type, color.fill(), maxWidth = f.width + 2f, align = align, rtl = rtl || persian(text),
            animators = listOfNotNull(reveal.animator(at)), transform = Transform(x = x.anim, y = y.anim), name = name,
        )
        if (ground != null) {
            node.onBody = true
            node.ground = ground
        }
        return node
    }

    /** How a piece of text arrives inside a family (the family's own move plays around it). */
    enum class Reveal {
        Rise, Fade, Type, Pop, None;

        fun animator(at: Float): TextAnimator? = when (this) {
            Rise -> TextAnimator(TextUnit.Word, UnitState(dy = 1.25f), at = at, duration = 0.55f, stagger = 0.05f, ease = Easing.ExpoOut, clipToLine = true)
            Fade -> TextAnimator(TextUnit.Word, UnitState(dy = 0.25f, opacity = 0f), at = at, duration = 0.45f, stagger = 0.04f, ease = Easing.ExpoOut)
            Type -> TextAnimator(TextUnit.Char, UnitState(opacity = 0f, scale = 1.2f), at = at, duration = 0.08f, stagger = 0.025f, ease = Easing.QuintOut)
            Pop -> TextAnimator(TextUnit.Word, UnitState(scale = 0.4f, opacity = 0f), at = at, duration = 0.42f, stagger = 0.05f, ease = Easing.Spring(0.5f, 2.4f))
            None -> null
        }
    }

    /** A rolling figure on a body (Persian digits on a Persian piece). */
    fun number(
        value: Float, from: Float, decimals: Int, prefix: String, suffix: String, voice: Look.Voice, size: Float,
        color: Color, x: Float, y: Float, at: Float, settle: Float, ground: Color?, persianDigits: Boolean, name: String = "figure",
    ): CounterNode {
        val node = CounterNode(
            value = Anim.tween(from, value, at, at + settle, Easing.ExpoOut), decimals = decimals, prefix = prefix, suffix = suffix,
            persianDigits = persianDigits, type = voice.at(size), fill = color.fill(),
            transform = Transform(x = x.anim, y = y.anim, opacity = Anim.tween(0f, 1f, at, at + 0.15f)), name = name,
        )
        if (ground != null) {
            node.onBody = true
            node.ground = ground
        }
        return node
    }

    /** A figure as text, in Persian digits on a Persian piece (۱۲٫۵ ٪). */
    fun digits(s: String, rtl: Boolean): String = if (rtl) Numerals.toPersian(s).replace('.', '٫') else s

    fun format(v: Float, decimals: Int, rtl: Boolean): String = digits(io.trimio.engine.motion.formatFixed(v, decimals, true), rtl).let { if (rtl) it.replace(',', '٬') else it }

    /** Persian or English, by the piece's language. */
    fun say(rtl: Boolean, fa: String, en: String) = if (rtl) fa else en

    // ------------------------------------------------------------------ choreography

    /**
     * [children] (in frame px) as one graphic that enters with [move] from [at] and leaves with its
     * pair at [Cue.out], turning and scaling about the middle of [box] (or its top edge for
     * [Move.Unfold]). Gone exactly at the cue's end.
     */
    fun stage(cue: Cue, look: Look, move: Move, box: Box, children: List<Node>, name: String, at: Float): Group {
        val pad = maxOf(MIN_PAD, minOf(box.width, box.height) * PAD_SHARE)
        val b = Box(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        val enter = Craft.pace(cue.energy, ENTER_CALM, ENTER_HOT)
        val exitAt = cue.out - EXIT
        val d = cue.dir
        val m = motion(move, b, box, at, enter, exitAt, d, look)
        val inner = Group(children, Transform(x = (-b.left).anim, y = (-b.top).anim, anchorX = 0f, anchorY = 0f), name = "$name-body")
        return Group(
            listOf(inner), transform = m.first, width = b.width, height = b.height,
            start = at - 0.05f, end = cue.out + 0.02f, mask = m.second, name = name,
        )
    }

    private val Cue.dir: Float get() = if (rtl) -1f else 1f

    /** The outer transform (and wipe mask) of [move] for a padded box [b] around [box]. */
    private fun motion(move: Move, b: Box, box: Box, at: Float, enter: Float, exitAt: Float, d: Float, look: Look): Pair<Transform, Mask?> {
        val fade = anim(0f, at) { by(1f, FADE_IN); hold(exitAt); by(0f, EXIT, Easing.Exit) }
        val cx = b.centerX
        val cy = b.centerY
        val base = Transform(x = cx.anim, y = cy.anim, anchorX = 0.5f, anchorY = 0.5f, opacity = fade)
        val lift = box.height * 0.08f
        val travel = minOf(box.width * 0.16f, 140f)
        return when (move) {
            Move.Rise -> base.copy(
                y = cy.anim + anim(lift, at) { by(0f, enter, Easing.ExpoOut); hold(exitAt); by(-lift * 0.6f, EXIT, Easing.Exit) },
                blur = anim(0f, exitAt) { by(3f, EXIT, Easing.Exit) },
            ) to null
            Move.Pop -> base.copy(scale = anim(0.72f, at) { by(1f, enter * 0.9f, Easing.Spring(0.52f, 2.1f)); hold(exitAt); by(0.86f, EXIT, Easing.Exit) }) to null
            Move.Slide -> base.copy(
                x = cx.anim + anim(-d * travel, at) { by(0f, enter, look.settle.takeIf { it != Easing.SineInOut } ?: Easing.ExpoOut); hold(exitAt); by(d * travel * 0.8f, EXIT, Easing.Exit) },
            ) to null
            Move.Wipe -> base.copy(opacity = anim(1f, exitAt) { by(0f, EXIT, Easing.Exit) }) to wipe(b, at, enter, exitAt, d)
            Move.Flip -> base.copy(
                rotationX = anim(-72f, at) { by(0f, enter * 1.1f, Easing.Spring(0.64f, 1.6f)); hold(exitAt); by(55f, EXIT, Easing.Exit) },
                perspective = 1600f,
            ) to null
            Move.Focus -> base.copy(
                blur = anim(18f, at) { by(0f, enter * 1.2f, Easing.ExpoOut); hold(exitAt); by(12f, EXIT, Easing.Exit) },
                scale = anim(1.07f, at) { by(1f, enter * 1.2f, Easing.ExpoOut) },
            ) to null
            Move.Drop -> base.copy(
                y = cy.anim + anim(-lift * 2.6f, at) { by(0f, enter, Easing.Spring(0.45f, 2.2f)); hold(exitAt); by(lift * 1.5f, EXIT, Easing.Exit) },
            ) to null
            Move.Unfold -> base.copy(
                y = (box.top).anim, anchorY = (box.top - b.top) / b.height,
                scaleY = anim(0.04f, at) { by(1f, enter, Easing.ExpoOut); hold(exitAt); by(0.04f, EXIT, Easing.Exit) },
            ) to null
        }
    }

    /** A rectangle mask that grows in reading order over [enter], and shrinks the same way at [exitAt]. */
    private fun wipe(b: Box, at: Float, enter: Float, exitAt: Float, d: Float): Mask {
        val w = b.width
        val width = anim(0f, at) { by(w, enter, Easing.ExpoInOut); hold(exitAt); by(0f, EXIT, Easing.Exit) }
        // Anchored at the reading start: LTR grows rightward from the left edge, RTL leftward from the right.
        val node = ShapeNode(
            ShapeSpec.Rect(width, b.height.anim), fill = Color.White.fill(),
            transform = Transform(x = (if (d > 0f) 0f else w).anim, y = 0f.anim, anchorX = if (d > 0f) 0f else 1f, anchorY = 0f),
            name = "wipe",
        )
        return Mask(node)
    }

    /**
     * An item inside a family (a row, a card, a chip) entering at [at] with the small echo of
     * [move], about its own [box].
     */
    fun item(move: Move, box: Box, at: Float, dir: Float, children: List<Node>, name: String): Group {
        val pad = maxOf(MIN_PAD * 0.5f, minOf(box.width, box.height) * PAD_SHARE)
        val b = Box(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        val cx = b.centerX
        val cy = b.centerY
        val fade = Anim.tween(0f, 1f, at, at + FADE_IN)
        val t = Transform(x = cx.anim, y = cy.anim, opacity = fade)
        val small = minOf(box.height * 0.5f, 60f)
        val tr = when (move) {
            Move.Rise, Move.Wipe, Move.Unfold -> t.copy(y = cy.anim + Anim.tween(small, 0f, at, at + ITEM, Easing.ExpoOut))
            Move.Pop -> t.copy(scale = Anim.tween(0.5f, 1f, at, at + ITEM, Easing.Spring(0.5f, 2.3f)))
            Move.Slide -> t.copy(x = cx.anim + Anim.tween(-dir * small * 1.6f, 0f, at, at + ITEM, Easing.ExpoOut))
            Move.Flip -> t.copy(rotationX = Anim.tween(-80f, 0f, at, at + ITEM * 1.2f, Easing.Spring(0.62f, 1.8f)))
            Move.Focus -> t.copy(blur = Anim.tween(10f, 0f, at, at + ITEM, Easing.ExpoOut))
            Move.Drop -> t.copy(y = cy.anim + Anim.tween(-small * 1.4f, 0f, at, at + ITEM, Easing.Spring(0.45f, 2.2f)))
        }
        val inner = Group(children, Transform(x = (-b.left).anim, y = (-b.top).anim, anchorX = 0f, anchorY = 0f), name = "$name-body")
        return Group(listOf(inner), transform = tr, width = b.width, height = b.height, start = at - 0.02f, name = name)
    }

    /**
     * [children] (in frame px) turned, scaled and faded about the pivot ([px], [py]) by [transform]
     * (its x and y are offsets from the pivot). Its bounds cover [extent] (everything it draws, in
     * frame px) on both sides of the pivot, so a fade or a motion-blurred move never clips it.
     */
    fun pivot(children: List<Node>, px: Float, py: Float, extent: Box, transform: Transform, name: String, start: Float = 0f, end: Float = Float.POSITIVE_INFINITY): Group {
        val hw = maxOf(px - extent.left, extent.right - px) + PIVOT_PAD
        val hh = maxOf(py - extent.top, extent.bottom - py) + PIVOT_PAD
        val inner = Group(children, Transform(x = (hw - px).anim, y = (hh - py).anim, anchorX = 0f, anchorY = 0f), name = "$name-body")
        return Group(
            listOf(inner), transform = transform.copy(x = px.anim + transform.x, y = py.anim + transform.y, anchorX = 0.5f, anchorY = 0.5f),
            width = hw * 2f, height = hh * 2f, start = start, end = end, name = name,
        )
    }

    /** The whole family as [Built]: its stage group, sound and where it lands. */
    fun built(
        cue: Cue, look: Look, p: Plan, box: Box, children: List<Node>, name: String,
        sfx: List<Sfx> = emptyList(), camera: List<CameraMove> = emptyList(), overlays: List<Node> = emptyList(),
    ): Built {
        val entrance = when (p.move) {
            Move.Pop, Move.Drop -> Sfx(p.at, SfxKind.Pop, 0.6f)
            Move.Slide, Move.Wipe -> Sfx(p.at - 0.05f, SfxKind.Swish, 0.55f)
            Move.Focus -> Sfx(p.at - 0.15f, SfxKind.Shimmer, 0.45f)
            else -> Sfx(p.at, SfxKind.Whoosh, 0.4f)
        }
        return Built(listOf(stage(cue, look, p.move, box, children, name, p.at)), camera, listOf(entrance) + sfx, overlays, box)
    }

    /**
     * A box of [w] × [h] (shrunk to fit) inside the cue's slot, centred on it; families size
     * themselves from it so nothing ever leaves the slot (and the slot is inside the safe area).
     */
    fun box(cue: Cue, w: Float, h: Float): Box {
        val k = minOf(1f, cue.width / w.coerceAtLeast(1f), cue.height / h.coerceAtLeast(1f))
        return Box.around(cue.x, cue.y, w * k, h * k)
    }

    /** Items of a beat: its items, else its text split on "|" or new lines. */
    fun items(cue: Cue, max: Int): List<String> =
        cue.items.ifEmpty { cue.text.split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() } }.map { it.trim() }.filter { it.isNotEmpty() }.take(max)

    /** When item [i] lands: when it is said, else on an even stagger after [at]. */
    fun itemAt(cue: Cue, i: Int, at: Float, stagger: Float): Float =
        maxOf(cue.itemTimes.getOrNull(i)?.minus(Craft.LEAD) ?: (at + i * stagger), at + i * MIN_STAGGER)

    /** Reading-start x of a box (left edge for LTR, right edge for RTL) inset by [inset]. */
    fun startX(b: Box, rtl: Boolean, inset: Float) = if (rtl) b.right - inset else b.left + inset

    fun endX(b: Box, rtl: Boolean, inset: Float) = if (rtl) b.left + inset else b.right - inset

    /** A text's centre x when its reading start sits at [edge]. */
    fun fromStart(edge: Float, width: Float, rtl: Boolean) = if (rtl) edge - width / 2f else edge + width / 2f

    fun fromEnd(edge: Float, width: Float, rtl: Boolean) = if (rtl) edge + width / 2f else edge - width / 2f

    const val FLOOR = 4.5f
    private const val BISECT = 16
    private const val UNLISTED = 0.55f
    private const val CALM_BOUNCE = 0.2f
    private const val TYPO_OWN = 0.5f
    private const val TYPO_SECOND = 0.8f
    private const val HEAVY = 700
    private const val LABEL = 600
    const val DRAW = 0.5f
    private const val MIN_PAD = 60f
    private const val PIVOT_PAD = 80f
    private const val PAD_SHARE = 0.15f
    private const val ENTER_CALM = 0.8f
    private const val ENTER_HOT = 0.5f
    const val EXIT = 0.3f
    private const val FADE_IN = 0.18f
    private const val ITEM = 0.5f
    private const val MIN_STAGGER = 0.12f
}
