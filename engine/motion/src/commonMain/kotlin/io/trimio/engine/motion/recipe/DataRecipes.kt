package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.trimio.core.model.text.Numerals
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
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
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.TypeSpec
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.formatFixed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * Data storytelling for promo, finance and social pieces: the devices an editor builds by hand for
 * every campaign reel — a deadline clock that makes the viewer feel the hours going, a row of
 * proof numbers counting up as they are said, a capacity bar that fills and gets stamped full, and
 * the reward itself as a ticket you want to hold. Every number lands with weight (overshoot, a
 * camera reaction, a sound), Persian pieces read right to left with Persian digits, and every
 * device carries its own body over footage so it never depends on the picture.
 */
object DataRecipes {

    /** Visible from just before the beat; the whole device lifts, softens and fades out by [Cue.out]. */
    private fun group(cue: Cue, children: List<Node>, name: String): Group {
        val exitAt = cue.out - EXIT
        return Group(
            children,
            transform = Transform(
                anchorX = 0f, anchorY = 0f,
                y = anim(0f, exitAt) { by(-28f, EXIT, Easing.Exit) },
                opacity = anim(1f, exitAt) { by(0f, EXIT, Easing.Exit) },
                blur = anim(0f, exitAt) { by(3f, EXIT, Easing.Exit) },
            ),
            start = cue.at - Craft.LEAD - 0.05f, end = cue.out + 0.02f, name = name,
        )
    }

    /**
     * The surface data sits on over footage: the look's canvas, smoked, with a rim and a drop
     * shadow, so white numbers never sit on a bright wall. On a graphic stage the data floats free.
     */
    private fun panel(look: Look, cx: Float, cy: Float, w: Float, h: Float, at: Float): ShapeNode = ShapeNode(
        ShapeSpec.Rect(w.anim, h.anim, (minOf(h * 0.2f, 44f) * look.roundness).anim),
        fill = Fill.Linear(listOf(look.canvas.copy(alpha = 0.86f), look.canvas.copy(alpha = 0.72f)), angle = 90f.anim),
        stroke = Stroke(look.cardRim.fill(), 2f),
        shadow = Shadow(Color.Black.copy(alpha = 0.32f), 0f, 18f, 40f),
        transform = Transform(
            x = cx.anim, y = cy.anim,
            scale = Anim.tween(0.92f, 1f, at, at + 0.6f, Easing.Land),
            opacity = Anim.tween(0f, 1f, at, at + 0.2f),
        ),
        name = "data-panel",
    )

    /** A repeating heartbeat offset (0 at rest) between [at] and [until]: urgency without movement. */
    private fun heartbeat(at: Float, until: Float, period: Float, amount: Float): Anim {
        if (until - at < period) return Anim.Zero
        val keys = mutableListOf(Anim.Key(at, 0f, Easing.Hold))
        var t = at
        while (t + period <= until) {
            keys += Anim.Key(t + period * 0.12f, amount, Easing.ExpoOut)
            keys += Anim.Key(t + period * 0.6f, 0f, Easing.SineInOut)
            keys += Anim.Key(t + period, 0f, Easing.Hold)
            t += period
        }
        return Anim.keys(keys)
    }

    /** The time in [t0]..[t1] at which a monotonic [ease] reaches [y]. */
    private fun timeOf(y: Float, t0: Float, t1: Float, ease: Easing): Float {
        var lo = 0f
        var hi = 1f
        repeat(BISECT) {
            val m = (lo + hi) / 2f
            if (ease.at(m) < y) lo = m else hi = m
        }
        return t0 + (t1 - t0) * hi
    }

    /** Samples a function of time into linear keys (paths the keyframe model cannot express, like orbits). */
    private fun sampled(t0: Float, t1: Float, step: Float, f: (Float) -> Float): Anim {
        val n = ((t1 - t0) / step).toInt().coerceIn(1, MAX_SAMPLES)
        return Anim.keys(List(n + 1) { i -> (t0 + (t1 - t0) * i / n).let { t -> Anim.Key(t, f(t), Easing.Linear) } })
    }

    /** Persian digits, the Persian thousands separator «٬», decimal «٫» and percent «٪» in RTL pieces. */
    private fun digits(s: String, rtl: Boolean) = if (rtl) sign(Numerals.toPersian(s).replace(',', '٬').replace('.', '٫'), true) else s

    /** Persian percent for Persian pieces, whatever the director wrote. */
    private fun sign(s: String, rtl: Boolean) = if (rtl) s.replace('%', '٪') else s

    /** Persian numbers group only from five digits: «۱۰۰۰» reads cleaner than any separator. */
    private fun grouped(v: Float, rtl: Boolean) = !rtl || abs(v) >= COMPACT

    private fun persian(s: String) = s.any { it in '؀'..'ۿ' }

    private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    /** [color] when it stands out from the canvas; else the hot colour, else ink (lime vanishes on paper). */
    private fun strong(look: Look, color: Color) = listOf(color, look.hot).firstOrNull { abs(luminance(it) - luminance(look.canvas)) > CONTRAST } ?: look.ink

    /** Words rise from behind their line, as in every label of the house style. */
    private fun rise(at: Float) = TextAnimator(TextUnit.Word, UnitState(dy = 1.3f), at = at, duration = 0.6f, stagger = 0.06f, ease = Easing.ExpoOut, clipToLine = true)

    /**
     * A light band sweeping across [target] (a number or a card): the glint that says "premium".
     * The band is masked by a copy of the target, so it only lights what is there.
     */
    private fun shine(mask: Node, x0: Float, x1: Float, y: Float, w: Float, h: Float, at: Float, strength: Float, duration: Float = 0.6f): Group {
        val band = ShapeNode(
            ShapeSpec.Rect(w.anim, h.anim),
            fill = Fill.Linear(listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent), angle = 0f.anim),
            transform = Transform(x = Anim.tween(x0, x1, at, at + duration, Easing.SineInOut), y = y.anim, rotation = 18f.anim),
            name = "shine-band",
        )
        return Group(listOf(band), mask = Mask(mask), start = at, end = at + duration + 0.02f, name = "shine")
    }

    // ------------------------------------------------------------------ countdown

    /**
     * Time running out: a clock face of sixty ticks charges up in the hot colour, then drains as
     * the hours roll down to what is left; the number lands with a hit, the ring keeps losing a
     * tick every second and the number beats like a pulse — with a clock tick — until it leaves.
     */
    val Countdown = object : Recipe("countdown", Kind.Element) {
        override val preferredHeight = 0.4f
        override val minHold = 2.8f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val v = (cue.value ?: DEFAULT_HOURS).coerceAtLeast(0f)
            val from = (cue.from ?: ceil(v * 1.6f + 1f)).coerceAtLeast(v)
            val label = cue.label ?: cue.text.takeIf { it.isNotBlank() }
            val band = if (label != null) cue.height * 0.15f else 0f
            // A takeover ring is the frame's hero (it may breathe past the slot a little); over footage it fits the band.
            val d = if (cue.overMedia) minOf(cue.width * 0.78f, cue.height - band * 1.25f) else minOf(cue.width * 0.74f, cue.height * 1.1f - band * 1.2f)
            val cx = cue.x
            val cy = cue.y - band * 0.6f
            val count = at + 0.4f
            val land = count + Craft.pace(cue.energy, 1.4f, 0.9f)
            val clock = Clock(at, count, land, if (from > 0f) v / from else 0f)
            val nodes = mutableListOf<Node>()
            if (cue.overMedia) {
                nodes += ShapeNode(
                    ShapeSpec.Ellipse((d * 1.08f).anim, (d * 1.08f).anim), fill = look.canvas.copy(alpha = 0.82f).fill(),
                    stroke = Stroke(look.cardRim.fill(), 2f), shadow = Shadow(Color.Black.copy(alpha = 0.35f), 0f, 16f, 36f),
                    transform = Transform(x = cx.anim, y = cy.anim, scale = Anim.tween(0.8f, 1f, at, at + 0.6f, Easing.Land), opacity = Anim.tween(0f, 1f, at, at + 0.15f)),
                    name = "countdown-body",
                )
            }
            nodes += ring(cue, look, d, cx, cy, clock)
            val beat = heartbeat(land + 0.55f, cue.out - EXIT - 0.1f, 1f, 0.045f)
            nodes += face(cue, look, fit, d, cx, cy, from, v, clock, beat)
            if (label != null) nodes += caption(cue, look, fit, label, cx, cy + d / 2f + band * 0.72f, band, at + 0.3f)
            val hot = cue.energy > HOT
            val pulses = (0 until 4).map { land + 0.55f + it }.filter { it < cue.out - EXIT - 0.2f }
            return Built(
                listOf(group(cue, nodes, name)),
                camera = listOf(Craft.punch(land, 0.025f + 0.03f * cue.energy)) + if (hot) listOf(Craft.shake(land, 9f, cue.seed)) else emptyList(),
                sfx = listOf(Sfx(at, SfxKind.Riser, 0.45f), Sfx(count, SfxKind.Tick, 0.4f), Sfx(land, if (hot) SfxKind.Boom else SfxKind.Hit, 0.85f)) +
                    pulses.map { Sfx(it, SfxKind.Tick, 0.32f) },
                overlays = if (hot) listOf(Craft.flash(land, look.hot, 0.12f)) else emptyList(),
            )
        }

        /** How full the ring is over time: charge, drain with the count, then a tick a second. */
        private inner class Clock(val at: Float, val count: Float, val land: Float, val share: Float) {
            fun frac(t: Float): Float = when {
                t < count -> ((t - at - 0.05f) / CHARGE).coerceIn(0f, 1f)
                t < land -> 1f - (1f - share) * Easing.ExpoOut.at((t - count) / (land - count))
                else -> (share - (t - land) / (TICKS * DRAIN)).coerceAtLeast(0f)
            }

            /** When tick [i] lights during the charge, and when the drain reaches it. */
            fun lit(i: Int) = at + 0.05f + CHARGE * i / TICKS
            fun dark(i: Int): Float {
                val p = i / TICKS.toFloat()
                return if (p >= share) timeOf((1f - p) / (1f - share).coerceAtLeast(1e-4f), count, land, Easing.ExpoOut) else land + (share - p) * TICKS * DRAIN
            }
        }

        private fun ring(cue: Cue, look: Look, d: Float, cx: Float, cy: Float, clock: Clock): Group {
            val tl = d * 0.075f
            val tw = maxOf(3f, d * 0.013f)
            val r = d / 2f - tl * 0.7f
            val dim = look.ink.copy(alpha = if (cue.overMedia) 0.2f else 0.13f)
            val children = mutableListOf<Node>()
            // A soft hot core breathes behind the number.
            children += ShapeNode(
                ShapeSpec.Ellipse((d * 0.86f).anim, (d * 0.86f).anim),
                fill = Fill.Radial(listOf(look.hot.copy(alpha = 0.26f), look.hot.copy(alpha = 0.06f), Color.Transparent)),
                transform = Transform(x = (d / 2f).anim, y = (d / 2f).anim, opacity = Anim.tween(0f, 1f, clock.at + 0.2f, clock.land, Easing.Standard)),
                name = "countdown-core",
            )
            children += ShapeNode(
                ShapeSpec.Ellipse((r * 2f - tl * 1.9f).anim, (r * 2f - tl * 1.9f).anim), stroke = Stroke(look.ink.copy(alpha = 0.12f).fill(), maxOf(1.5f, d * 0.004f)),
                transform = Transform(x = (d / 2f).anim, y = (d / 2f).anim), name = "countdown-track",
            )
            for (i in 0 until TICKS) {
                val a = 2f * PI.toFloat() * i / TICKS
                val major = i % 5 == 0
                val len = if (major) tl * 1.3f else tl
                val on = clock.lit(i)
                val off = clock.dark(i)
                val color = ColorAnim.keys(
                    Triple(on - 0.001f, dim, Easing.Hold), Triple(on + 0.06f, look.hot, Easing.Standard),
                    Triple(off, look.hot, Easing.Hold), Triple(off + 0.16f, dim, Easing.Standard),
                )
                children += ShapeNode(
                    ShapeSpec.Rect((if (major) tw * 1.25f else tw).anim, len.anim, (tw / 2f).anim), fill = Fill.Solid(color),
                    transform = Transform(x = (d / 2f + sin(a) * r).anim, y = (d / 2f - cos(a) * r).anim, rotation = (i * 360f / TICKS).anim),
                    name = "countdown-tick",
                )
            }
            // The leading edge of what is left: a glowing head that rides the drain.
            val angle = { t: Float -> 2f * PI.toFloat() * clock.frac(t) }
            val end = cue.out
            children += ShapeNode(
                ShapeSpec.Ellipse((tw * 2.6f).anim, (tw * 2.6f).anim), fill = look.hot.fill(), shadow = Shadow(look.hot.copy(alpha = 0.9f), 0f, 0f, tw * 4f),
                transform = Transform(
                    x = sampled(clock.at + 0.05f, end, SAMPLE) { t -> d / 2f + sin(angle(t)) * (r - tl * 0.95f) },
                    y = sampled(clock.at + 0.05f, end, SAMPLE) { t -> d / 2f - cos(angle(t)) * (r - tl * 0.95f) },
                    opacity = Anim.tween(0f, 1f, clock.at + 0.05f, clock.at + 0.15f),
                ),
                name = "countdown-head",
            )
            // The impact: a hot ring bursts as the number lands.
            children += ShapeNode(
                ShapeSpec.Ellipse((d * 0.84f).anim, (d * 0.84f).anim), stroke = Stroke(look.hot.fill(), maxOf(3f, d * 0.012f)),
                transform = Transform(
                    x = (d / 2f).anim, y = (d / 2f).anim,
                    scale = Anim.tween(0.9f, 1.32f, clock.land, clock.land + 0.75f, Easing.ExpoOut),
                    opacity = Anim.tween(0.9f, 0f, clock.land, clock.land + 0.75f, Easing.ExpoOut),
                ),
                start = clock.land, end = clock.land + 0.8f, name = "countdown-burst",
            )
            return Group(
                children, width = d, height = d,
                transform = Transform(
                    x = cx.anim, y = cy.anim,
                    scale = Anim.tween(0.82f, 1f, clock.at, clock.at + 0.7f, Easing.Land),
                    rotation = Anim.tween(-40f, 0f, clock.at, clock.at + 0.8f, Easing.ExpoOut),
                    opacity = Anim.tween(0f, 1f, clock.at, clock.at + 0.12f),
                ),
                name = "countdown-ring",
            )
        }

        /** The number rolling down to what is left, and its unit underneath. */
        private fun face(cue: Cue, look: Look, fit: Fitter, d: Float, cx: Float, cy: Float, from: Float, v: Float, clock: Clock, beat: Anim): List<Node> {
            val sample = digits(cue.prefix + formatFixed(maxOf(from, v), cue.decimals, grouped(maxOf(from, v), cue.rtl)), cue.rtl)
            val unit = cue.suffix.trim().takeIf { it.isNotEmpty() }
            val nf = fit.fit(sample, look.number, d * 0.4f, d * 0.6f, d * (if (unit != null) 0.36f else 0.46f), maxLines = 1)
            val uf = unit?.let { fit.fit(it, look.body.copy(weight = 800), nf.type.size * 0.27f, d * 0.5f, d * 0.14f, maxLines = 1) }
            val shift = uf?.let { it.height * 0.55f } ?: 0f
            val numberY = cy - shift
            val scale = anim(0.7f, clock.at + 0.1f) { by(1f, 0.55f, Easing.Land); hold(clock.land); by(1.12f, 0.07f, Easing.ExpoOut); by(1f, 0.4f, Easing.Land) } + beat
            val nodes = mutableListOf<Node>(
                CounterNode(
                    value = Anim.tween(from, v, clock.count, clock.land, Easing.ExpoOut), decimals = cue.decimals, prefix = sign(cue.prefix, cue.rtl),
                    grouping = grouped(maxOf(from, v), cue.rtl),
                    persianDigits = cue.rtl, type = nf.type, fill = look.ink.fill(), shadow = Craft.shadow(cue, look, nf.type.size),
                    transform = Transform(x = cx.anim, y = numberY.anim, scale = scale, opacity = Anim.tween(0f, 1f, clock.at + 0.1f, clock.at + 0.25f)),
                    name = "countdown-number",
                ),
            )
            if (unit != null && uf != null) {
                nodes += TextNode(
                    unit, uf.type, look.hot.fill(), rtl = persian(unit),
                    animators = listOf(TextAnimator(TextUnit.All, UnitState(dy = 0.5f, opacity = 0f), at = clock.count, duration = 0.5f, ease = Easing.ExpoOut)),
                    transform = Transform(x = cx.anim, y = (numberY + nf.height * 0.5f + uf.height * 0.45f).anim, scale = 1f.anim + beat * 0.5f.anim),
                    name = "countdown-unit",
                )
            }
            return nodes
        }

        /** What the clock counts to, with a blinking live dot on the reading-start side. */
        private fun caption(cue: Cue, look: Look, fit: Fitter, label: String, cx: Float, y: Float, band: Float, at: Float): List<Node> {
            val lf = fit.fit(label, look.body.copy(weight = 800), band * 0.5f, cue.width * 0.82f, band, maxLines = 1)
            val dot = lf.type.size * 0.4f
            val gap = lf.type.size * 0.45f
            val total = lf.width + gap + dot
            val dir = if (cue.rtl) 1f else -1f
            val dotX = cx + dir * (total / 2f - dot / 2f)
            val textX = cx - dir * (total / 2f - lf.width / 2f)
            val blink = Anim.keys(List(((cue.out - at) / BLINK).toInt().coerceAtLeast(1) + 1) { k -> Anim.Key(at + 0.2f + k * BLINK, if (k % 2 == 0) 1f else 0.25f, Easing.SineInOut) })
            // Over footage the caption rides a smoked pill, so a bright wall behind it never wins.
            val pill = if (cue.overMedia) {
                val ph = lf.height * 1.25f
                listOf(
                    ShapeNode(
                        ShapeSpec.Rect((total + ph * 0.9f).anim, ph.anim, (ph / 2f).anim), fill = look.canvas.copy(alpha = 0.82f).fill(), stroke = Stroke(look.cardRim.fill(), 2f),
                        transform = Transform(x = cx.anim, y = y.anim, scaleX = Anim.tween(0.6f, 1f, at, at + 0.5f, Easing.ExpoOut), opacity = Anim.tween(0f, 1f, at, at + 0.2f)),
                        name = "countdown-pill",
                    ),
                )
            } else {
                emptyList()
            }
            return pill + listOf(
                ShapeNode(
                    ShapeSpec.Ellipse(dot.anim, dot.anim), fill = look.hot.fill(), shadow = Shadow(look.hot.copy(alpha = 0.8f), 0f, 0f, dot),
                    transform = Transform(x = dotX.anim, y = y.anim, scale = Anim.tween(0f, 1f, at, at + 0.4f, Easing.Spring(0.5f, 2.2f)), opacity = blink),
                    name = "countdown-live",
                ),
                TextNode(
                    label, lf.type, look.ink.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, lf.type.size), animators = listOf(rise(at + 0.05f)),
                    transform = Transform(x = textX.anim, y = y.anim), name = "countdown-label",
                ),
            )
        }
    }

    // ------------------------------------------------------------------ stats

    /** A figure as shown: long values compacted (50,000 → 50 + «هزار» / "K"); RTL units sit beside the digits. */
    private class Figure(val value: Float, val decimals: Int, val prefix: String, val suffix: String, val text: String, val unit: String?)

    private fun figure(v: Float, cue: Cue, rtl: Boolean): Figure {
        if (cue.suffix.isNotEmpty() || abs(v) < COMPACT) {
            return Figure(v, cue.decimals, sign(cue.prefix, rtl), sign(cue.suffix, rtl), digits(cue.prefix + formatFixed(v, cue.decimals, grouped(v, rtl)) + cue.suffix, rtl), null)
        }
        val million = abs(v) >= MILLION
        val k = if (million) v / MILLION else v / THOUSAND
        val decimals = if (k == round(k)) 0 else 1
        val text = cue.prefix + formatFixed(k, decimals, true)
        // A Persian unit word is its own text: inside the counter's left-to-right run it would land on the wrong side.
        return if (rtl) Figure(k, decimals, sign(cue.prefix, true), "", digits(text, true), if (million) "میلیون" else "هزار")
        else Figure(k, decimals, cue.prefix, if (million) "M" else "K", text + if (million) "M" else "K", null)
    }

    /**
     * Proof in numbers: two or three figures counting up as each is said, thin dividers drawing
     * between them, an accent rule landing under each and a glint across it. When the slot is tall
     * enough to make the figures much bigger stacked (a full-frame stage), they stack as a table.
     */
    val Stats = object : Recipe("stats", Kind.Element) {
        override val preferredHeight = 0.34f
        override val minHold = 2.8f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val values = (cue.points?.takeIf { it.isNotEmpty() } ?: listOfNotNull(cue.value)).take(MAX_STATS)
            if (values.isEmpty()) return Built(emptyList())
            val n = values.size
            val labels = List(n) { cue.items.getOrNull(it)?.takeIf { l -> l.isNotBlank() } }
            val rtl = cue.rtl || labels.any { it != null && persian(it) }
            val figures = values.map { figure(it, cue, rtl) }
            val hasLabels = labels.any { it != null }
            // Room left for the figures under the board's caption, when it has one.
            val stage = !cue.overMedia
            val room = cue.height * (if (stage) 1.12f else 1f) * (if (cue.label.isNullOrBlank()) 1f else 0.82f)
            // Side by side, or stacked as rows: whichever lets the figures be clearly bigger.
            val rowPref = room * (if (hasLabels) 0.46f else 0.66f)
            val side = figures.minOf { sizeFor(it, look, fit, cue.width / n * 0.84f, rowPref) }
            val rowH = room / n
            // On a stage the rows run tighter (label beside, not under) and win whenever they are not smaller.
            val rowShare = if (stage) 0.78f else 0.66f
            val stackSize = figures.minOf { sizeFor(it, look, fit, cue.width * 0.6f, rowH * rowShare) }
            val stacked = n > 1 && stackSize > side * (if (stage) 1f else STACK_GAIN)
            val size = if (stacked) stackSize else side
            val stagger = Craft.pace(cue.energy, 0.45f, 0.28f)
            val times = List(n) { i -> maxOf(at, (cue.itemTimes.getOrNull(i) ?: (cue.at + i * stagger)) - Craft.LEAD) }
            val settle = Craft.pace(cue.energy, 1.2f, 0.75f)
            val board = Board(cue, look, fit, figures, labels, size, times, settle, rtl, cue.height * (if (stage) 1.12f else 1f))
            val nodes = if (stacked) board.rows() else board.columns()
            val lands = times.map { it + settle }
            return Built(
                listOf(group(cue, nodes, name)),
                camera = lands.map { Craft.punch(it, 0.012f + 0.018f * cue.energy) },
                sfx = listOf(Sfx(at, SfxKind.Riser, 0.35f)) + times.map { Sfx(it, SfxKind.Tick, 0.3f) } + lands.map { Sfx(it, SfxKind.Pop, 0.65f) } +
                    Sfx(lands.first() + 0.1f, SfxKind.Shimmer, 0.25f),
            )
        }

        /** The largest number size at which a figure (with its unit) fits [maxW]×[maxH]. */
        private fun sizeFor(f: Figure, look: Look, fit: Fitter, maxW: Float, maxH: Float): Float {
            val nf = fit.fit(f.text, look.number, maxH, maxW, maxH, maxLines = 1)
            val unit = f.unit ?: return nf.type.size
            val uf = fit.fit(unit, look.headline, nf.type.size * UNIT, maxW, maxH, maxLines = 1)
            val total = nf.width + uf.width + nf.type.size * UNIT_GAP
            return if (total > maxW) nf.type.size * maxW / total else nf.type.size
        }

        /** One set of figures at one size, laid out as columns or as rows. */
        private inner class Board(
            val cue: Cue, val look: Look, val fit: Fitter, val figures: List<Figure>, val labels: List<String?>,
            val size: Float, val times: List<Float>, val settle: Float, val rtl: Boolean,
            /** The height the board may use (a stage lets it breathe past the slot a little). */
            val height: Float,
        ) {
            val n = figures.size
            val type = look.number.at(size)
            val numberH = size * look.number.lineHeight
            val rule = maxOf(4f, size * 0.055f)
            val dir = if (rtl) -1f else 1f
            val accent = strong(look, look.accent)
            val widths = figures.map { fit.fit(it.text, look.number, size, 1e5f, 1e5f, maxLines = 1).width }
            val units = figures.map { f -> f.unit?.let { fit.fit(it, look.headline, size * UNIT, 1e5f, 1e5f, maxLines = 1) } }
            fun composite(i: Int) = widths[i] + (units[i]?.let { it.width + size * UNIT_GAP } ?: 0f)

            /** The board's caption («کمتر از ۱۲ ساعت»): it sits above the figures and rises first. */
            val title = cue.label?.takeIf { it.isNotBlank() }
            val head = title?.let { fit.fit(it, look.body.copy(weight = 800), maxOf(size * 0.34f, cue.width * 0.04f), cue.width * 0.9f, cue.height * 0.16f, maxLines = 1) }
            val headH = head?.let { it.height + size * 0.3f } ?: 0f
            fun headNodes(blockTop: Float): List<Node> {
                val f = head ?: return emptyList()
                return listOf(
                    TextNode(
                        title!!, f.type, look.ink.fill(), rtl = rtl, shadow = Craft.shadow(cue, look, f.type.size), animators = listOf(rise(times.first() - 0.1f)),
                        transform = Transform(x = cue.x.anim, y = (blockTop - headH + f.height / 2f).anim), name = "stats-title",
                    ),
                )
            }

            /** Side by side: each figure centred in its column, its label under its accent rule. */
            fun columns(): List<Node> {
                val colW = cue.width / n
                val lf = labels.map { l -> l?.let { fit.fit(it, look.body, maxOf(size * 0.36f, cue.width * 0.034f), colW * 0.9f, size * 0.9f, maxLines = 2) } }
                val labelH = lf.maxOf { it?.height ?: 0f }
                val gap = size * 0.2f
                val blockH = numberH + gap + rule + if (labelH > 0f) gap + labelH else 0f
                val centre = cue.y + headH / 2f
                val numberY = centre - blockH / 2f + numberH / 2f
                val ruleY = numberY + numberH / 2f + gap * 0.8f
                val labelY = ruleY + rule / 2f + gap + labelH / 2f
                val nodes = mutableListOf<Node>()
                if (cue.overMedia) nodes += panel(look, cue.x, cue.y, cue.width, blockH + headH + size * 0.8f, times.first())
                nodes += headNodes(centre - blockH / 2f)
                for (i in 1 until n) {
                    val t = times.first() + 0.1f + 0.08f * i
                    nodes += ShapeNode(
                        ShapeSpec.Rect(2f.anim, (blockH * 0.86f).anim, 1f.anim), fill = look.ink.copy(alpha = if (cue.overMedia) 0.3f else 0.2f).fill(),
                        transform = Transform(x = (cue.x - dir * (cue.width / 2f - colW * i)).anim, y = centre.anim, scaleY = Anim.tween(0f, 1f, t, t + 0.6f, Easing.ExpoOut)),
                        name = "stats-divider",
                    )
                }
                for (i in 0 until n) {
                    val x = cue.x - dir * (cue.width / 2f - colW * (i + 0.5f))
                    val land = times[i] + settle
                    nodes += figureNodes(i, x - composite(i) / 2f, numberY)
                    nodes += ShapeNode(
                        ShapeSpec.Rect((colW * 0.16f).anim, rule.anim, (rule / 2f).anim), fill = accent.fill(),
                        transform = Transform(x = x.anim, y = ruleY.anim, scaleX = Anim.tween(0f, 1f, land - 0.15f, land + 0.35f, Easing.ExpoOut)),
                        name = "stats-rule",
                    )
                    val label = labels[i]
                    val f = lf[i]
                    if (label != null && f != null) nodes += labelNode(label, f, x, labelY, times[i] + 0.2f, colW * 0.9f)
                }
                return nodes
            }

            /** Stacked as a table: figures aligned on the reading-start edge, labels in a second column. */
            fun rows(): List<Node> {
                val rowH = minOf((height - headH) / n, numberH * 1.4f)
                val numCol = figures.indices.maxOf { composite(it) }
                val colGap = size * 0.32f
                val lf = labels.map { l -> l?.let { fit.fit(it, look.body, size * 0.4f, cue.width - numCol - colGap, numberH, maxLines = 2) } }
                val total = numCol + colGap + (lf.maxOfOrNull { it?.width ?: 0f } ?: 0f)
                val start = cue.x - dir * total / 2f
                val top = cue.y - (rowH * n + headH) / 2f + headH
                val nodes = mutableListOf<Node>()
                if (cue.overMedia) nodes += panel(look, cue.x, cue.y, maxOf(total, head?.width ?: 0f) + size * 1.2f, rowH * n + headH + size * 0.4f, times.first())
                nodes += headNodes(top)
                for (i in 0 until n) {
                    val y = top + rowH * (i + 0.5f)
                    val land = times[i] + settle
                    val left = if (rtl) start - composite(i) else start
                    nodes += figureNodes(i, left, y)
                    // An accent bar on the reading-start edge lands with the number.
                    nodes += ShapeNode(
                        ShapeSpec.Rect(rule.anim, (numberH * 0.62f).anim, (rule / 2f).anim), fill = accent.fill(),
                        transform = Transform(x = (start - dir * size * 0.28f).anim, y = y.anim, scaleY = Anim.tween(0f, 1f, land - 0.15f, land + 0.35f, Easing.ExpoOut)),
                        name = "stats-rule",
                    )
                    val label = labels[i]
                    val f = lf[i]
                    if (label != null && f != null) nodes += labelNode(label, f, start + dir * (numCol + colGap + f.width / 2f), y + size * 0.08f, times[i] + 0.2f, cue.width - numCol - colGap)
                    if (i > 0) {
                        val t = times.first() + 0.1f + 0.08f * i
                        nodes += ShapeNode(
                            ShapeSpec.Rect((total + size * 0.28f).anim, 2f.anim), fill = look.ink.copy(alpha = if (cue.overMedia) 0.3f else 0.18f).fill(),
                            transform = Transform(
                                x = (start - dir * size * 0.28f).anim, y = (top + rowH * i).anim, anchorX = if (rtl) 1f else 0f,
                                scaleX = Anim.tween(0f, 1f, t, t + 0.7f, Easing.ExpoOut),
                            ),
                            name = "stats-divider",
                        )
                    }
                }
                return nodes
            }

            /**
             * A counting figure whose final composite starts at [left]: the number is pinned on its
             * reading-start edge (it grows away from the reader's eye), its unit joins as it lands.
             */
            fun figureNodes(i: Int, left: Float, y: Float): List<Node> {
                val f = figures[i]
                val t0 = times[i]
                val land = t0 + settle
                val edge = if (rtl) left + composite(i) else left
                fun number(name: String) = CounterNode(
                    value = Anim.tween(0f, f.value, t0, land, Easing.ExpoOut), decimals = f.decimals, prefix = f.prefix, suffix = f.suffix, grouping = grouped(f.value, rtl),
                    persianDigits = rtl, type = type, fill = look.ink.fill(), shadow = if (name == "stats-number") Craft.shadow(cue, look, size) else null,
                    transform = Transform(
                        x = edge.anim, y = y.anim + Anim.tween(size * 0.3f, 0f, t0, t0 + 0.6f, Easing.ExpoOut), anchorX = if (rtl) 1f else 0f,
                        scale = anim(0.88f, t0) { by(1f, 0.5f, Easing.Land); hold(land); by(1.06f, 0.08f, Easing.ExpoOut); by(1f, 0.32f, Easing.Land) },
                        opacity = Anim.tween(0f, 1f, t0, t0 + 0.15f),
                    ),
                    name = name,
                )
                val nodes = mutableListOf<Node>(number("stats-number"))
                val cx = left + composite(i) / 2f
                nodes += shine(number("stats-shine-mask"), cx - composite(i) * 0.7f - size, cx + composite(i) * 0.7f + size, y, size * 0.5f, numberH * 1.8f, land + 0.08f, 0.5f)
                val unit = f.unit
                val uf = units[i]
                if (unit != null && uf != null) {
                    nodes += TextNode(
                        unit, uf.type, accent.fill(), rtl = true, shadow = Craft.shadow(cue, look, uf.type.size),
                        animators = listOf(TextAnimator(TextUnit.All, UnitState(dx = 0.35f, opacity = 0f), at = land - 0.2f, duration = 0.45f, ease = Easing.ExpoOut)),
                        transform = Transform(x = (left + uf.width / 2f).anim, y = (y + numberH * 0.24f - uf.height * 0.3f).anim), name = "stats-unit",
                    )
                }
                return nodes
            }

            fun labelNode(label: String, f: Fitted, x: Float, y: Float, at: Float, maxWidth: Float) = TextNode(
                label, f.type, Craft.secondary(cue, look).fill(), maxWidth = maxWidth, rtl = persian(label),
                shadow = Craft.shadow(cue, look, f.type.size), animators = listOf(rise(at)),
                transform = Transform(x = x.anim, y = y.anim), name = "stats-label",
            )
        }
    }

    // ------------------------------------------------------------------ progress

    /**
     * Capacity filling up: a track draws in, the fill rushes to its value with a glowing head while
     * the percentage counts beside the label; at 100 it turns hot and a stamp slams across it.
     */
    val Progress = object : Recipe("progress", Kind.Element) {
        override val preferredHeight = 0.2f
        override val minHold = 2.6f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val target = (cue.value ?: 100f).coerceIn(0f, 100f)
            val start = (cue.from ?: 0f).coerceIn(0f, target)
            val full = target >= FULL
            val words = cue.words.size
            val label = cue.label ?: cue.text.takeIf { it.isNotBlank() && words > 2 }
            // On a full-frame stage the device owns the frame: sized by the width, the percentage as the hero.
            val stage = !cue.overMedia
            val w = cue.width * (if (stage) 0.96f else 0.94f)
            val bh = if (stage) cue.width * 0.11f else (cue.height * 0.2f).coerceIn(20f, 84f)
            val voice = look.body.copy(weight = 800)
            val unit = sign(cue.suffix.ifEmpty { "%" }, cue.rtl)
            val pct = digits(cue.prefix + formatFixed(target, cue.decimals, false), cue.rtl) + unit
            val cf = if (stage) fit.fit(pct, look.number, bh * 2.6f, w * 0.7f, bh * 2.8f, maxLines = 1) else fit.fit(pct, look.number, bh * 1.7f, w * 0.36f, bh * 2f, maxLines = 1)
            val lf = label?.let { if (stage) fit.fit(it, voice, bh * 0.8f, w, bh * 1.4f, maxLines = 1) else fit.fit(it, voice, bh * 0.95f, w - cf.width - bh, bh * 1.6f, maxLines = 1) }
            val labelH = lf?.height ?: 0f
            val headerH = if (stage) labelH + bh * 0.2f + cf.height else maxOf(cf.height, labelH)
            val gap = bh * (if (stage) 0.45f else 0.6f)
            val total = headerH + gap + bh
            val top = cue.y - total / 2f
            val headerY = top + headerH / 2f
            val barY = cue.y + total / 2f - bh / 2f
            val dir = if (cue.rtl) -1f else 1f
            // Stage: label on top, the percentage centred under it. Over footage: one header row, label and value at either end.
            val valueX = if (stage) cue.x else cue.x + dir * w / 2f
            val valueY = if (stage) top + labelH + bh * 0.2f + cf.height / 2f else headerY
            val valueAnchor = if (stage) 0.5f else if (cue.rtl) 0f else 1f
            val startX = cue.x - dir * w / 2f
            val t0 = at + 0.35f
            val t1 = t0 + Craft.pace(cue.energy, 1.7f, 1.05f) * maxOf(0.45f, (target - start) / 100f)
            val ease = if (full) Easing.Move else Easing.ExpoOut
            val land = t1 + 0.06f
            val nodes = mutableListOf<Node>()
            if (cue.overMedia) nodes += panel(look, cue.x, cue.y, cue.width, total + bh * 1.4f, at)
            nodes += bar(cue, look, Bar(startX, barY, w, bh, cue.rtl), start, target, t0, t1, ease, if (full) land else null)
            val heat = if (full) ColorAnim.tween(look.ink, look.hot, land, land + 0.12f) else look.ink.let { ColorAnim.of(it) }
            nodes += CounterNode(
                value = Anim.tween(start, target, t0, t1, ease), decimals = cue.decimals, prefix = sign(cue.prefix, cue.rtl), suffix = unit, grouping = false,
                persianDigits = cue.rtl, type = cf.type, fill = Fill.Solid(heat), shadow = Craft.shadow(cue, look, cf.type.size),
                transform = Transform(
                    x = valueX.anim, y = valueY.anim, anchorX = valueAnchor,
                    scale = anim(1f, land) { by(1.12f, 0.07f, Easing.ExpoOut); by(1f, 0.35f, Easing.Land) },
                    opacity = Anim.tween(0f, 1f, t0 - 0.1f, t0 + 0.1f),
                ),
                name = "progress-value",
            )
            if (label != null && lf != null) {
                nodes += TextNode(
                    label, lf.type, look.ink.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, lf.type.size), animators = listOf(rise(at + 0.1f)),
                    transform = Transform(
                        x = (if (stage) cue.x else startX + dir * lf.width / 2f).anim,
                        y = (if (stage) top + labelH / 2f else headerY + (headerH - lf.height) * 0.3f).anim,
                    ),
                    name = "progress-label",
                )
            }
            if (!full) {
                return Built(
                    listOf(group(cue, nodes, name)), camera = listOf(Craft.punch(land, 0.02f)),
                    sfx = listOf(Sfx(at, SfxKind.Swish, 0.45f), Sfx(t0, SfxKind.Riser, 0.4f), Sfx(land, SfxKind.Pop, 0.7f)),
                )
            }
            nodes += stamp(cue, look, fit, cue.x, barY + bh * 0.2f, bh, w, land)
            return Built(
                listOf(group(cue, nodes, name)),
                camera = listOf(Craft.punch(land + STAMP_DROP, 0.05f), Craft.shake(land + STAMP_DROP, 12f, cue.seed)),
                sfx = listOf(Sfx(at, SfxKind.Swish, 0.45f), Sfx(t0, SfxKind.Riser, 0.45f), Sfx(land, SfxKind.Pop, 0.6f), Sfx(land + STAMP_DROP, SfxKind.Boom, 1f)),
                overlays = if (cue.energy > HOT) listOf(Craft.flash(land + STAMP_DROP, Color.White, 0.14f)) else emptyList(),
            )
        }

        private inner class Bar(val startX: Float, val y: Float, val w: Float, val h: Float, val rtl: Boolean)

        private fun bar(cue: Cue, look: Look, b: Bar, start: Float, target: Float, t0: Float, t1: Float, ease: Easing, fullAt: Float?): List<Node> {
            val at = cue.at - Craft.LEAD
            val anchor = if (b.rtl) 1f else 0f
            val dir = if (b.rtl) -1f else 1f
            val radius = (b.h / 2f * minOf(1f, look.roundness + 0.25f)).anim
            val width = Anim.tween(b.w * start / 100f, b.w * target / 100f, t0, t1, ease)
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(
                ShapeSpec.Rect(b.w.anim, b.h.anim, radius), fill = look.ink.copy(alpha = if (cue.overMedia) 0.16f else 0.1f).fill(),
                transform = Transform(x = b.startX.anim, y = b.y.anim, anchorX = anchor, scaleX = Anim.tween(0f, 1f, at, at + 0.55f, Easing.ExpoOut)),
                name = "progress-track",
            )
            // Capacity marks every tenth: the eye reads "how much is left" without numbers.
            for (k in 1..9) {
                nodes += ShapeNode(
                    ShapeSpec.Rect(2f.anim, (b.h * 0.36f).anim, 1f.anim), fill = look.ink.copy(alpha = 0.18f).fill(),
                    transform = Transform(x = (b.startX + dir * b.w * k / 10f).anim, y = b.y.anim, opacity = Anim.tween(0f, 1f, at + 0.2f + k * 0.025f, at + 0.4f + k * 0.025f)),
                    name = "progress-mark",
                )
            }
            val accent = strong(look, look.accent)
            val color = if (fullAt != null) ColorAnim.tween(accent, look.hot, fullAt, fullAt + 0.12f) else ColorAnim.of(accent)
            nodes += ShapeNode(
                ShapeSpec.Rect(width, b.h.anim, radius), fill = Fill.Solid(color),
                transform = Transform(x = b.startX.anim, y = b.y.anim, anchorX = anchor), name = "progress-fill",
            )
            // A glowing head rides the leading edge while it moves (light needs a dark canvas to read as light).
            if (luminance(look.canvas) < 0.5f) nodes += ShapeNode(
                ShapeSpec.Ellipse((b.h * 2.4f).anim, (b.h * 2.4f).anim),
                fill = Fill.Radial(listOf(Color.White.copy(alpha = 0.55f), accent.copy(alpha = 0.25f), Color.Transparent), radius = 0.5f.anim),
                transform = Transform(
                    x = b.startX.anim + width * dir.anim, y = b.y.anim,
                    opacity = anim(0f, t0) { by(1f, 0.15f); hold(t1); by(0f, 0.35f, Easing.Standard) },
                ),
                start = t0, end = t1 + 0.4f, name = "progress-head",
            )
            if (fullAt != null) {
                val sweep = ShapeNode(ShapeSpec.Rect(b.w.anim, b.h.anim, radius), fill = Color.White.fill(), transform = Transform(x = b.startX.anim, y = b.y.anim, anchorX = anchor))
                nodes += shine(sweep, b.startX - dir * b.h * 2f, b.startX + dir * (b.w + b.h * 2f), b.y, b.h * 2.2f, b.h * 3f, fullAt + 0.02f, 0.7f, 0.45f)
            }
            return nodes
        }

        /** The verdict: a stamp slams across the bar, rotated, on a body so it reads over the fill. */
        private fun stamp(cue: Cue, look: Look, fit: Fitter, x: Float, y: Float, bh: Float, w: Float, at: Float): List<Node> {
            val word = cue.text.takeIf { it.isNotBlank() && cue.words.size <= 2 } ?: if (cue.rtl) "تکمیل" else "FULL"
            val f = fit.fit(word, look.headline, bh * 1.35f, w * 0.5f, bh * 2.2f, maxLines = 1)
            val sw = f.width + f.type.size * 0.95f
            val sh = f.height + f.type.size * 0.3f
            val drop = at + STAMP_DROP
            val t = Transform(
                x = x.anim, y = y.anim,
                scale = Anim.tween(2.6f, 1f, at, drop, Easing.ExpoIn), rotation = Anim.tween(-22f, -7f, at, drop, Easing.ExpoIn),
                opacity = Anim.tween(0f, 1f, at, at + 0.06f),
            )
            return listOf(
                ShapeNode(
                    ShapeSpec.Rect(sw.anim, sh.anim, (sh * 0.16f * look.roundness).anim), fill = look.canvas.copy(alpha = 0.92f).fill(),
                    stroke = Stroke(look.hot.fill(), f.type.size * 0.09f, round = false), shadow = Shadow(Color.Black.copy(alpha = 0.35f), 0f, 10f, 24f),
                    transform = t, name = "progress-stamp",
                ),
                TextNode(word, f.type, look.hot.fill(), rtl = persian(word), transform = t, name = "progress-stamp-text"),
            )
        }
    }

    // ------------------------------------------------------------------ voucher

    /**
     * The reward as an object: a ticket with a brand stub, a perforated tear line and punched
     * notches tilts up out of depth and settles; the currency's mark traces itself, the amount
     * counts up to its value, and a glint sweeps the card as it lands. It floats gently until it goes.
     */
    val Voucher = object : Recipe("voucher", Kind.Element) {
        override val preferredHeight = 0.3f
        override val minHold = 3f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            // A takeover ticket is taller (a bigger amount); over footage it stays a long strip for the top band.
            val ratio = if (cue.overMedia) RATIO else STAGE_RATIO
            val w = minOf(cue.width * 0.98f, cue.height * 0.98f * ratio)
            val h = w / ratio
            val mark = cue.marks.firstOrNull()
            val tint = mark?.color ?: look.accent
            val count = at + 0.45f
            val land = count + Craft.pace(cue.energy, 1.4f, 0.9f)
            val t = Ticket(w, h, w * (if (cue.overMedia) 0.32f else 0.28f), h * 0.085f, w * 0.16f, h * 0.3f, cue.rtl)
            val nodes = mutableListOf<Node>()
            // A halo in the currency's colour lifts the ticket off any background.
            nodes += ShapeNode(
                ShapeSpec.Rect((w * 1.4f).anim, (w * 1.4f).anim), fill = Fill.Radial(listOf(tint.copy(alpha = 0.3f), tint.copy(alpha = 0.08f), Color.Transparent), radius = 0.35f.anim),
                transform = Transform(x = cue.x.anim, y = cue.y.anim, scaleY = 0.6f.anim, opacity = Anim.tween(0f, 1f, at + 0.2f, land, Easing.Standard)), name = "voucher-halo",
            )
            val body = mutableListOf<Node>()
            body += surface(look, t, tint)
            body += stub(look, fit, t, tint, mark, at)
            body += amount(cue, look, fit, t, tint, count, land)
            body += barcode(look, t, cue.seed)
            val card = ShapeNode(ShapeSpec.Rect(w.anim, h.anim, (h * CORNER).anim), fill = Color.White.fill(), transform = Transform(x = (w / 2f).anim, y = (h / 2f).anim))
            body += shine(card, -w * 0.2f, w * 1.2f, h / 2f, w * 0.14f, h * 2.4f, land - 0.12f, 0.32f, 0.75f)
            val second = cue.out - EXIT - 1.1f
            if (second > land + 1.2f) body += shine(card, -w * 0.2f, w * 1.2f, h / 2f, w * 0.1f, h * 2.4f, second, 0.2f, 0.8f)
            // Punched notches on the tear line: real holes, cut out of everything on the card.
            val notches = Group(
                listOf(0f, h).map { y ->
                    ShapeNode(ShapeSpec.Ellipse((t.notch * 2f).anim, (t.notch * 2f).anim), fill = Color.White.fill(), transform = Transform(x = t.tear.anim, y = y.anim))
                },
            )
            val mx = w * 0.25f
            val my = h * 0.45f
            val shadow = ShapeNode(
                ShapeSpec.Rect((w * 0.92f).anim, (h * 0.86f).anim, (h * CORNER).anim), fill = Color.Black.copy(alpha = if (cue.overMedia) 0.55f else 0.45f).fill(),
                transform = Transform(x = (mx + w / 2f).anim, y = (my + h / 2f + h * 0.1f).anim, blur = 26f.anim + Anim.tween(10f, 0f, at, at + 0.8f)), name = "voucher-shadow",
            )
            val card3d = Group(body, Transform(anchorX = 0f, anchorY = 0f, x = mx.anim, y = my.anim), width = w, height = h, mask = Mask(notches, invert = true), name = "voucher-card")
            // Margins around the card keep its shadow inside the tilting layer's bounds.
            nodes += Group(
                listOf(shadow, card3d), width = w + mx * 2f, height = h + my * 2f,
                transform = tilt(cue, at, land, h, w),
                name = "voucher-ticket",
            )
            return Built(
                listOf(group(cue, nodes, name)),
                camera = listOf(Craft.punch(land, 0.03f + 0.02f * cue.energy)),
                sfx = listOf(
                    Sfx(at, SfxKind.Whoosh, 0.55f), Sfx(count, SfxKind.Riser, 0.4f),
                    Sfx(land, if (cue.energy > HOT) SfxKind.Boom else SfxKind.Pop, 0.8f), Sfx(land + 0.05f, SfxKind.Shimmer, 0.45f),
                ),
            )
        }

        /** Card geometry: size, stub width, notch radius, the tear line and the stub side. */
        private inner class Ticket(val w: Float, val h: Float, val stubW: Float, val notch: Float, val code: Float, val codeH: Float, val rtl: Boolean) {
            /** The stub sits on the reading-start side. */
            val tear: Float get() = if (rtl) w - stubW else stubW
            val mainCx: Float get() = if (rtl) (w - stubW) / 2f else stubW + (w - stubW) / 2f
            val stubCx: Float get() = if (rtl) w - stubW / 2f else stubW / 2f
        }

        /** Out of depth: tilted back and turned, it rises, settles with a spring, then floats. */
        private fun tilt(cue: Cue, at: Float, land: Float, h: Float, w: Float): Transform {
            val sway = anim(0f, land) { by(1f, 0.8f, Easing.SineInOut) }
            return Transform(
                x = cue.x.anim, y = cue.y.anim + Anim.tween(h * 0.45f, 0f, at, at + 0.75f, Easing.ExpoOut),
                scale = anim(0.74f, at) { by(1f, 0.8f, Easing.Land) },
                rotationX = Anim.tween(58f, 0f, at, at + 1f, Easing.Spring(0.6f, 1.5f)) + Anim.Noise(cue.seed, 0.45f, sway * 2.2f.anim),
                rotationY = Anim.tween(if (cue.rtl) 32f else -32f, 0f, at, at + 1.1f, Easing.ExpoOut) + Anim.Noise(cue.seed + 5, 0.35f, sway * 3.5f.anim),
                opacity = Anim.tween(0f, 1f, at, at + 0.15f),
                perspective = w * 2.4f,
            )
        }

        private fun surface(look: Look, t: Ticket, tint: Color): List<Node> {
            val r = (t.h * CORNER).anim
            val top = lerp(look.canvas, tint, 0.2f)
            val bottom = lerp(look.canvas, tint, 0.06f)
            return listOf(
                ShapeNode(
                    ShapeSpec.Rect(t.w.anim, t.h.anim, r), fill = lerp(look.canvas, look.ink, 0.22f).fill(),
                    transform = Transform(x = (t.w / 2f).anim, y = (t.h / 2f).anim), name = "voucher-rim",
                ),
                ShapeNode(
                    ShapeSpec.Rect((t.w - 3f).anim, (t.h - 3f).anim, r), fill = Fill.Linear(listOf(top, bottom), angle = 70f.anim),
                    transform = Transform(x = (t.w / 2f).anim, y = (t.h / 2f).anim), name = "voucher-body",
                ),
                // Stub in the currency's colour; its inner corners square against the tear line.
                ShapeNode(
                    ShapeSpec.Rect(t.stubW.anim, t.h.anim, r), fill = Fill.Linear(listOf(tint, lerp(tint, Color.Black, 0.3f)), angle = 90f.anim),
                    transform = Transform(x = t.stubCx.anim, y = (t.h / 2f).anim), name = "voucher-stub",
                ),
                ShapeNode(
                    ShapeSpec.Rect((t.h * CORNER * 1.2f).anim, t.h.anim), fill = Fill.Linear(listOf(tint, lerp(tint, Color.Black, 0.3f)), angle = 90f.anim),
                    transform = Transform(x = (t.tear + (if (t.rtl) 1f else -1f) * t.h * CORNER * 0.6f).anim, y = (t.h / 2f).anim), name = "voucher-stub-edge",
                ),
            ) + (0 until DASHES).map { k ->
                val span = t.h - t.notch * 3f
                ShapeNode(
                    ShapeSpec.Rect(maxOf(2.5f, t.h * 0.012f).anim, (span / DASHES * 0.5f).anim, 2f.anim), fill = look.ink.copy(alpha = 0.4f).fill(),
                    transform = Transform(x = t.tear.anim, y = (t.notch * 1.5f + span * (k + 0.5f) / DASHES).anim), name = "voucher-tear",
                )
            }
        }

        /** The currency's mark on the stub: traced, then filled; a coin when no mark is known. */
        private fun stub(look: Look, fit: Fitter, t: Ticket, tint: Color, mark: BrandMark?, at: Float): List<Node> {
            val glyph = if (luminance(tint) > LIGHT) Color(0xFF111111).copy(alpha = 0.9f) else Color.White
            val s = minOf(t.stubW * 0.5f, t.h * 0.42f)
            val y = t.h * 0.44f
            val nodes = mutableListOf<Node>()
            val path = mark?.path
            if (path != null) {
                nodes += ShapeNode(
                    ShapeSpec.Path(path, Icons.VIEWPORT, s), stroke = Stroke(glyph.fill(), maxOf(1.5f, s * 0.012f)),
                    trimEnd = Anim.tween(0f, 1f, at + 0.3f, at + 0.85f, Easing.ExpoOut),
                    transform = Transform(x = t.stubCx.anim, y = y.anim, opacity = anim(1f, at + 0.85f) { by(0f, 0.25f) }), end = at + 1.15f, name = "voucher-trace",
                )
                nodes += ShapeNode(
                    ShapeSpec.Path(path, Icons.VIEWPORT, s), fill = glyph.fill(),
                    transform = Transform(
                        x = t.stubCx.anim, y = y.anim, opacity = Anim.tween(0f, 1f, at + 0.6f, at + 0.9f),
                        scale = anim(1f, at + 0.6f) { by(1.1f, 0.1f, Easing.ExpoOut); by(1f, 0.4f, Easing.Land) },
                    ),
                    name = "voucher-mark",
                )
            } else {
                nodes += ShapeNode(
                    ShapeSpec.Path(Icons.path("coin")!!, Icons.VIEWPORT, s), stroke = Stroke(glyph.fill(), s * 0.06f),
                    trimEnd = Anim.tween(0f, 1f, at + 0.3f, at + 0.9f, Easing.ExpoOut), transform = Transform(x = t.stubCx.anim, y = y.anim), name = "voucher-coin",
                )
            }
            val title = mark?.takeIf { !it.isMonogram || path != null }?.title
            if (title != null) {
                val f = fit.fit(title.uppercase(), look.label.copy(weight = 700), t.h * 0.085f, t.stubW * 0.8f, t.h * 0.12f, maxLines = 1)
                nodes += TextNode(
                    title.uppercase(), f.type, glyph.copy(alpha = 0.85f).fill(), rtl = false,
                    transform = Transform(x = t.stubCx.anim, y = (y + s / 2f + t.h * 0.1f).anim, opacity = Anim.tween(0f, 1f, at + 0.7f, at + 1f)), name = "voucher-brand",
                )
            }
            return nodes
        }

        /** The label line and the amount with its unit, centred on the main part of the ticket. */
        private fun amount(cue: Cue, look: Look, fit: Fitter, t: Ticket, tint: Color, count: Float, land: Float): List<Node> {
            val v = cue.value ?: 0f
            val mainW = t.w - t.stubW
            val unit = cue.suffix.trim().takeIf { it.isNotEmpty() }
            val label = cue.label ?: cue.text.takeIf { it.isNotBlank() }
            val nf = fit.fit(digits(cue.prefix + formatFixed(v, cue.decimals, grouped(v, cue.rtl)), cue.rtl), look.number,
                t.h * (if (cue.overMedia) 0.38f else 0.46f), mainW * (if (unit == null) 0.8f else if (cue.overMedia) 0.56f else 0.6f), t.h * (if (cue.overMedia) 0.42f else 0.48f), maxLines = 1,
            )
            val uf = unit?.let { fit.fit(it, look.headline, nf.type.size * 0.4f, mainW * 0.3f, t.h * 0.2f, maxLines = 1) }
            val gap = nf.type.size * 0.16f
            val total = nf.width + (uf?.let { it.width + gap } ?: 0f)
            val left = t.mainCx - total / 2f
            val y = t.h * (if (label != null) 0.56f else 0.5f)
            // RTL reads "۳۵۰ تتر": the number on the right, its unit to its left. The number grows away from the unit.
            val numberEdge = if (cue.rtl) left + (uf?.let { it.width + gap } ?: 0f) else left + nf.width
            val unitX = if (cue.rtl) left + (uf?.width ?: 0f) / 2f else left + nf.width + gap + (uf?.width ?: 0f) / 2f
            val nodes = mutableListOf<Node>(
                CounterNode(
                    value = Anim.tween(0f, v, count, land, Easing.ExpoOut), decimals = cue.decimals, prefix = sign(cue.prefix, cue.rtl), persianDigits = cue.rtl,
                    grouping = grouped(v, cue.rtl),
                    type = nf.type, fill = look.ink.fill(),
                    transform = Transform(
                        x = numberEdge.anim, y = y.anim, anchorX = if (cue.rtl) 0f else 1f,
                        scale = anim(1f, land) { by(1.1f, 0.07f, Easing.ExpoOut); by(1f, 0.35f, Easing.Land) },
                        opacity = Anim.tween(0f, 1f, count - 0.05f, count + 0.1f),
                    ),
                    name = "voucher-amount",
                ),
            )
            if (unit != null && uf != null) {
                nodes += TextNode(
                    unit, uf.type, lerp(tint, look.ink, 0.35f).fill(), rtl = persian(unit),
                    animators = listOf(TextAnimator(TextUnit.All, UnitState(dx = if (cue.rtl) 0.4f else -0.4f, opacity = 0f), at = count + 0.15f, duration = 0.5f, ease = Easing.ExpoOut)),
                    transform = Transform(x = unitX.anim, y = (y + nf.height * 0.36f - uf.height * 0.62f).anim), name = "voucher-unit",
                )
            }
            if (label != null) {
                val lf = fit.fit(label, look.body.copy(weight = 700), t.h * 0.1f, mainW * 0.8f, t.h * 0.16f, maxLines = 1)
                nodes += TextNode(
                    label, lf.type, look.ink.copy(alpha = 0.72f).fill(), rtl = cue.rtl, animators = listOf(rise(count - 0.1f)),
                    transform = Transform(x = t.mainCx.anim, y = (y - nf.height * 0.5f - lf.height * 0.55f).anim), name = "voucher-label",
                )
            }
            return nodes
        }

        /** Fine print: a short barcode in the reading-end corner, the detail that makes it a ticket. */
        private fun barcode(look: Look, t: Ticket, seed: Int): List<Node> {
            val rnd = kotlin.random.Random(seed)
            val bars = List(BARS) { 1f + rnd.nextInt(3) }
            val unit = t.code / bars.sum() / 1.6f
            val right = if (t.rtl) t.h * 0.22f + t.code else t.w - t.h * 0.22f
            var x = right - t.code
            val y = t.h - t.h * 0.15f
            return bars.mapIndexed { i, b ->
                val bw = b * unit
                val node = ShapeNode(
                    ShapeSpec.Rect(bw.anim, (t.codeH * 0.42f).anim), fill = look.ink.copy(alpha = 0.3f).fill(),
                    transform = Transform(x = (x + bw / 2f).anim, y = y.anim), name = "voucher-code-$i",
                )
                x += bw + unit * 0.6f * (1 + i % 2)
                node
            }
        }
    }

    private const val EXIT = 0.28f
    private const val HOT = 0.8f
    private const val DEFAULT_HOURS = 48f
    private const val TICKS = 60
    private const val CHARGE = 0.35f
    /** Seconds per tick of the slow drain after the number lands. */
    private const val DRAIN = 0.8f
    private const val BLINK = 0.5f
    private const val SAMPLE = 1f / 30f
    private const val MAX_SAMPLES = 400
    private const val BISECT = 22
    private const val MAX_STATS = 3
    private const val COMPACT = 10_000f
    private const val THOUSAND = 1_000f
    private const val MILLION = 1_000_000f
    /** Unit word size and gap, as fractions of the number size. */
    private const val UNIT = 0.42f
    private const val UNIT_GAP = 0.16f
    /** Stack the figures only when that makes them this much bigger. */
    private const val STACK_GAIN = 1.15f
    private const val CONTRAST = 0.3f
    private const val FULL = 99.5f
    private const val STAMP_DROP = 0.16f
    private const val RATIO = 2.15f
    private const val STAGE_RATIO = 1.7f
    private const val CORNER = 0.09f
    private const val DASHES = 9
    private const val BARS = 18
    private const val LIGHT = 0.8f

    val all: List<Recipe> = listOf(Countdown, Stats, Progress, Voucher)
}
