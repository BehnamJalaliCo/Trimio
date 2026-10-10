package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/** Elements: numbers, data, icons and graphic devices, each with its own choreography. */
object ElementRecipes {

    /** Visible from just before the beat until the cue ends; exits fade and lift the whole group. */
    private fun group(cue: Cue, children: List<Node>, name: String, enter: Float = cue.at - Craft.LEAD): Group {
        val exitAt = cue.out - 0.28f
        return Group(
            children,
            transform = Transform(
                anchorX = 0f, anchorY = 0f,
                y = io.trimio.engine.motion.anim(0f, exitAt) { by(-28f, 0.28f, Easing.Exit) },
                opacity = io.trimio.engine.motion.anim(1f, exitAt) { by(0f, 0.28f, Easing.Exit) },
                blur = io.trimio.engine.motion.anim(0f, exitAt) { by(3f, 0.28f, Easing.Exit) },
            ),
            start = enter - 0.05f, end = cue.out + 0.02f, name = name,
        )
    }

    private fun card(cue: Cue, look: Look, w: Float, h: Float, at: Float, radius: Float = 34f) = ShapeNode(
        ShapeSpec.Rect(w.anim, h.anim, (radius * look.roundness).anim),
        fill = Fill.Linear(listOf(look.card, look.card.copy(alpha = look.card.alpha * 0.45f)), angle = 60f.anim),
        stroke = Stroke(look.cardRim.fill(), 2f),
        shadow = if (cue.overMedia || look.roundness > 0f) io.trimio.engine.motion.Shadow(Color.Black.copy(alpha = 0.35f), 0f, 18f, 40f) else null,
        transform = Transform(
            x = cue.x.anim, y = cue.y.anim,
            scale = Anim.tween(0.86f, 1f, at, at + 0.6f, Easing.Land),
            opacity = Anim.tween(0f, 1f, at, at + 0.2f),
        ),
        name = "card",
    )

    private fun signColor(look: Look, cue: Cue): Color {
        val v = cue.value ?: 0f
        val from = cue.from ?: 0f
        return if (v >= from) look.positive else look.negative
    }

    private fun counterNode(cue: Cue, look: Look, size: Float, at: Float, settle: Float, x: Float, y: Float, color: Color, persian: Boolean = cue.rtl): CounterNode {
        val to = cue.value ?: 0f
        val from = cue.from ?: 0f
        return CounterNode(
            value = Anim.tween(from, to, at, at + settle, Easing.ExpoOut),
            decimals = cue.decimals, prefix = cue.prefix, suffix = cue.suffix, persianDigits = persian,
            type = look.number.at(size), fill = color.fill(), shadow = Craft.shadow(cue, look, size),
            transform = Transform(
                x = x.anim, y = y.anim,
                scale = io.trimio.engine.motion.anim(0.82f, at) { by(1f, 0.5f, Easing.Land); hold(at + settle); by(1.07f, 0.08f, Easing.ExpoOut); by(1f, 0.3f, Easing.Land) },
                opacity = Anim.tween(0f, 1f, at, at + 0.15f),
            ),
            name = "counter",
        )
    }

    /** A big number counts to its value, decelerating, then lands with a pulse. */
    val Counter = object : Recipe("counter", Kind.Element) {
        override val minHold = 2.2f
        override val preferredHeight = 0.24f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val settle = Craft.pace(cue.energy, 1.4f, 0.8f)
            val sample = cue.prefix + io.trimio.engine.motion.formatFixed(cue.value ?: 0f, cue.decimals, true) + cue.suffix
            val f = fit.fit(sample, look.number, cue.height * 0.62f, cue.width * 0.9f, cue.height * 0.7f, maxLines = 1)
            val label = cue.label ?: cue.text.takeIf { it.isNotBlank() }
            val numberY = if (label != null) cue.y - cue.height * 0.12f else cue.y
            val nodes = mutableListOf<Node>(counterNode(cue, look, f.type.size, at, settle, cue.x, numberY, signColor(look, cue)))
            if (label != null) {
                val lf = fit.fit(label, look.body, f.type.size * 0.28f, cue.width, cue.height * 0.25f, maxLines = 1)
                nodes += TextNode(
                    label, lf.type, look.muted.fill(), rtl = cue.rtl,
                    animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 0.6f, opacity = 0f), at = at + 0.25f, duration = 0.6f, stagger = 0.06f, ease = Easing.ExpoOut)),
                    shadow = Craft.shadow(cue, look, lf.type.size),
                    transform = Transform(x = cue.x.anim, y = (numberY + f.height / 2f + lf.height * 0.75f).anim), name = "counter-label",
                )
            }
            return Built(
                listOf(group(cue, nodes, name)),
                camera = listOf(Craft.punch(at + settle, 0.02f + 0.03f * cue.energy)),
                sfx = listOf(Sfx(at, SfxKind.Riser, 0.5f), Sfx(at + settle, SfxKind.Pop, 0.8f)),
            )
        }
    }

    /** A price pill: symbol, change and direction, built from the centre out. */
    val Ticker = object : Recipe("ticker", Kind.Element) {
        override val minHold = 2.4f
        override val preferredHeight = 0.1f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val h = minOf(cue.height, cue.width * 0.2f)
            val symbol = (cue.label ?: cue.text).ifBlank { "BTC" }
            val sf = fit.fit(symbol, look.label.copy(weight = 800, tracking = 0.04f), h * 0.42f, cue.width * 0.4f, h * 0.6f, maxLines = 1)
            val value = cue.value ?: 0f
            val up = value >= (cue.from ?: 0f)
            val color = if (up) look.positive else look.negative
            val w = minOf(cue.width, sf.width + h * 3.6f)
            val left = cue.x - w / 2f
            val pill = ShapeNode(
                ShapeSpec.Rect(Anim.tween(h, w, at, at + 0.55f, Easing.ExpoOut), h.anim, (h / 2f).anim),
                fill = look.card.copy(alpha = maxOf(look.card.alpha, 0.85f)).let { if (look.canvas.luminance() > 0.5f) Color.White else Color(0xFF16130F) }.fill(),
                stroke = Stroke(look.cardRim.fill(), 2f),
                transform = Transform(x = cue.x.anim, y = cue.y.anim, opacity = Anim.tween(0f, 1f, at, at + 0.12f)),
                name = "pill",
            )
            val dot = ShapeNode(
                ShapeSpec.Ellipse((h * 0.62f).anim, (h * 0.62f).anim), fill = color.fill(),
                transform = Transform(x = (left + h * 0.52f).anim, y = cue.y.anim, scale = Anim.tween(0f, 1f, at + 0.2f, at + 0.6f, Easing.Spring(0.5f, 2.2f))),
                name = "dot",
            )
            val arrow = ShapeNode(
                ShapeSpec.Path(Icons.path(if (up) "arrow-up" else "arrow-down")!!, Icons.VIEWPORT, h * 0.4f),
                stroke = Stroke(look.onAccent.fill(), h * 0.05f), trimEnd = Anim.tween(0f, 1f, at + 0.35f, at + 0.75f, Easing.ExpoOut),
                transform = Transform(x = (left + h * 0.52f).anim, y = cue.y.anim), name = "arrow",
            )
            val sym = TextNode(
                symbol, sf.type, (if (look.canvas.luminance() > 0.5f) look.ink else Color(0xFFF2EDE4)).fill(), rtl = false,
                transform = Transform(x = (left + h * 1.05f + sf.width / 2f).anim, y = cue.y.anim, opacity = Anim.tween(0f, 1f, at + 0.25f, at + 0.5f)), name = "symbol",
            )
            val change = counterNode(
                cue.copy(prefix = cue.prefix.ifEmpty { if (up) "+" else "" }, suffix = cue.suffix.ifEmpty { "%" }, decimals = if (cue.decimals == 0) 1 else cue.decimals),
                look, h * 0.42f, at + 0.3f, 0.9f, cue.x + w / 2f - h * 1.1f, cue.y, color, persian = false,
            )
            return Built(listOf(group(cue, listOf(pill, dot, arrow, sym, change), name)), sfx = listOf(Sfx(at, SfxKind.Swish, 0.6f), Sfx(at + 1.2f, SfxKind.Tick, 0.5f)))
        }
    }

    /** A line chart drawing itself on a glass card, ending in a live dot and the final value. */
    val Chart = object : Recipe("chart", Kind.Element) {
        override val minHold = 3.2f
        override val preferredHeight = 0.26f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val w = cue.width
            val h = cue.height
            val pts = cue.points?.takeIf { it.size >= 2 } ?: listOf(3f, 4.2f, 3.8f, 5.6f, 5.1f, 7.4f, 7f, 9.6f)
            val lo = pts.min()
            val hi = pts.max()
            val pad = h * 0.2f
            val cw = w - pad * 2
            val ch = h - pad * 2.1f
            val xy = pts.mapIndexed { i, v -> cw * i / (pts.size - 1) to ch * (1f - (v - lo) / (hi - lo).coerceAtLeast(1e-6f)) }
            val up = pts.last() >= pts.first()
            val color = if (up) look.positive else look.negative
            val draw = Craft.pace(cue.energy, 1.6f, 1.0f)
            val ox = cue.x - cw / 2f
            val oy = cue.y - h / 2f + pad * 1.3f
            val line = ShapeNode(
                ShapeSpec.Polyline(xy), stroke = Stroke(color.fill(), h * 0.025f), trimEnd = Anim.tween(0f, 1f, at + 0.25f, at + 0.25f + draw, Easing.Move),
                transform = Transform(x = ox.anim, y = oy.anim, anchorX = 0f, anchorY = 0f), name = "line",
            )
            val grid = (1..3).map { i ->
                ShapeNode(
                    ShapeSpec.Rect(cw.anim, 1.5f.anim), fill = look.cardRim.fill(),
                    transform = Transform(x = cue.x.anim, y = (oy + ch * i / 3.5f).anim, scaleX = Anim.tween(0f, 1f, at + 0.1f * i, at + 0.5f + 0.1f * i, Easing.ExpoOut)),
                    name = "grid",
                )
            }
            val end = xy.last()
            val landing = at + 0.25f + draw
            val dot = ShapeNode(
                ShapeSpec.Ellipse((h * 0.06f).anim, (h * 0.06f).anim), fill = color.fill(),
                transform = Transform(x = (ox + end.first).anim, y = (oy + end.second).anim, scale = Anim.tween(0f, 1f, landing - 0.05f, landing + 0.35f, Easing.Spring(0.45f, 2.4f))),
                name = "dot",
            )
            val ring = ShapeNode(
                ShapeSpec.Ellipse((h * 0.06f).anim, (h * 0.06f).anim), stroke = Stroke(color.fill(), 3f),
                transform = Transform(
                    x = (ox + end.first).anim, y = (oy + end.second).anim,
                    scale = Anim.tween(1f, 4f, landing, landing + 0.7f, Easing.ExpoOut), opacity = Anim.tween(1f, 0f, landing, landing + 0.7f, Easing.ExpoOut),
                ),
                start = landing, end = landing + 0.75f, name = "ring",
            )
            val nodes = mutableListOf<Node>(card(cue, look, w, h, at)) + grid + listOf(line, ring, dot)
            val title = cue.label ?: cue.text.takeIf { it.isNotBlank() }
            val extra = mutableListOf<Node>()
            if (title != null) {
                val tf = fit.fit(title, look.body, h * 0.09f, w * 0.55f, h * 0.14f, maxLines = 1)
                extra += TextNode(
                    title, tf.type, look.ink.fill(), rtl = cue.rtl,
                    transform = Transform(x = (cue.x + w / 2f - pad - tf.width / 2f).anim, y = (cue.y - h / 2f + pad * 0.75f).anim, opacity = Anim.tween(0f, 1f, at + 0.2f, at + 0.5f)),
                    name = "title",
                )
            }
            if (cue.value != null) {
                extra += counterNode(cue, look, h * 0.13f, at + 0.25f, draw, cue.x - w / 2f + pad + h * 0.25f, cue.y - h / 2f + pad * 0.75f, color, persian = cue.rtl)
            }
            return Built(listOf(group(cue, nodes + extra, name)), sfx = listOf(Sfx(at + 0.25f, SfxKind.Riser, 0.5f), Sfx(landing, SfxKind.Pop, 0.7f)))
        }
    }

    /** Bars grow from the baseline one after another. */
    val Bars = object : Recipe("bars", Kind.Element) {
        override val minHold = 2.6f
        override val preferredHeight = 0.26f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val values = cue.points?.takeIf { it.isNotEmpty() } ?: listOf(3f, 5f, 4f, 8f)
            val max = values.max().coerceAtLeast(1e-6f)
            val n = values.size
            val gap = cue.width * 0.04f
            val bw = (cue.width - gap * (n - 1)) / n
            val base = cue.y + cue.height / 2f
            val nodes = values.mapIndexed { i, v ->
                val h = cue.height * 0.9f * v / max
                val t0 = at + i * 0.12f
                val highlight = i == values.indexOf(values.max())
                ShapeNode(
                    ShapeSpec.Rect(bw.anim, Anim.tween(0f, h, t0, t0 + 0.7f, Easing.Spring(0.6f, 1.6f)), (bw * 0.12f * look.roundness).anim),
                    fill = (if (highlight) look.accent else look.ink.copy(alpha = 0.85f)).fill(),
                    transform = Transform(x = (cue.x - cue.width / 2f + bw / 2f + i * (bw + gap)).anim, y = base.anim, anchorY = 1f),
                    name = "bar-$i",
                )
            }
            return Built(listOf(group(cue, nodes, name)), sfx = values.indices.map { Sfx(at + it * 0.12f, SfxKind.Tick, 0.4f) })
        }
    }

    /** An icon on a disc: the disc pops, the icon draws itself, a ring bursts outward. */
    val IconPop = object : Recipe("icon", Kind.Element) {
        override val minHold = 1.3f
        override val preferredHeight = 0.14f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val d = minOf(cue.width, cue.height)
            val path = Icons.path(cue.icon) ?: Icons.path("spark")!!
            val disc = ShapeNode(
                ShapeSpec.Ellipse(d.anim, d.anim), fill = look.accent.fill(),
                transform = Transform(
                    x = cue.x.anim, y = cue.y.anim,
                    scale = Anim.tween(0f, 1f, at, at + 0.55f, Easing.Spring(0.45f, 2.2f)), rotation = Anim.tween(-25f, 0f, at, at + 0.6f, Easing.ExpoOut),
                ),
                name = "disc",
            )
            val glyph = ShapeNode(
                ShapeSpec.Path(path, Icons.VIEWPORT, d * 0.52f), stroke = Stroke(look.onAccent.fill(), d * 0.055f),
                trimEnd = Anim.tween(0f, 1f, at + 0.15f, at + 0.6f, Easing.ExpoOut),
                transform = Transform(x = cue.x.anim, y = cue.y.anim, scale = Anim.tween(0.6f, 1f, at + 0.1f, at + 0.6f, Easing.Land)), name = "glyph",
            )
            val ring = ShapeNode(
                ShapeSpec.Ellipse(d.anim, d.anim), stroke = Stroke(look.accent.fill(), d * 0.03f),
                transform = Transform(
                    x = cue.x.anim, y = cue.y.anim,
                    scale = Anim.tween(1f, 1.9f, at + 0.12f, at + 0.75f, Easing.ExpoOut), opacity = Anim.tween(0.9f, 0f, at + 0.12f, at + 0.75f, Easing.ExpoOut),
                ),
                start = at + 0.1f, end = at + 0.8f, name = "ring",
            )
            return Built(listOf(group(cue, listOf(ring, disc, glyph), name)), sfx = listOf(Sfx(at + 0.1f, SfxKind.Pop, 0.8f)))
        }
    }

    /** Name and role: a bar grows, the name rises behind its mask, the role follows. */
    val LowerThird = object : Recipe("lower-third", Kind.Element) {
        override val minHold = 2.6f
        override val preferredHeight = 0.12f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val name = cue.text.ifBlank { cue.label ?: "" }
            val nf = fit.fit(name, look.headline, cue.height * 0.42f, cue.width * 0.9f, cue.height * 0.5f, maxLines = 1)
            val role = cue.label?.takeIf { cue.text.isNotBlank() }
            val edge = if (cue.rtl) cue.x + cue.width / 2f else cue.x - cue.width / 2f
            val dir = if (cue.rtl) -1f else 1f
            val barW = cue.height * 0.07f
            val bar = ShapeNode(
                ShapeSpec.Rect(barW.anim, Anim.tween(0f, cue.height * 0.9f, at, at + 0.45f, Easing.ExpoOut), (barW / 2f).anim), fill = look.accent.fill(),
                transform = Transform(x = edge.anim, y = cue.y.anim), name = "bar",
            )
            val nameNode = TextNode(
                name, nf.type, look.ink.fill(), rtl = cue.rtl,
                animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.35f), at = at + 0.15f, duration = 0.6f, stagger = 0.06f, ease = Easing.ExpoOut, clipToLine = true)),
                shadow = Craft.shadow(cue, look, nf.type.size),
                transform = Transform(x = (edge + dir * (barW * 2.5f + nf.width / 2f)).anim, y = (cue.y - cue.height * 0.14f).anim), name = "name",
            )
            val nodes = mutableListOf<Node>(bar, nameNode)
            if (role != null) {
                val rf = fit.fit(role, look.body, nf.type.size * 0.42f, cue.width * 0.9f, cue.height * 0.3f, maxLines = 1)
                nodes += TextNode(
                    role, rf.type, look.muted.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, rf.type.size),
                    transform = Transform(
                        x = (edge + dir * (barW * 2.5f + rf.width / 2f)).anim + Anim.tween(-dir * 30f, 0f, at + 0.4f, at + 0.9f, Easing.ExpoOut),
                        y = (cue.y + cue.height * 0.26f).anim,
                        opacity = Anim.tween(0f, 1f, at + 0.4f, at + 0.8f),
                    ),
                    name = "role",
                )
            }
            return Built(listOf(group(cue, nodes, this.name)), sfx = listOf(Sfx(at, SfxKind.Swish, 0.5f)))
        }
    }

    /** A rubber stamp: slams in rotated, the frame shakes. */
    val Stamp = object : Recipe("stamp", Kind.Element) {
        override val minHold = 1.5f
        override val preferredHeight = 0.14f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val text = cue.text.ifBlank { cue.label ?: "" }
            val f = fit.fit(text, look.headline, cue.height * 0.45f, cue.width * 0.8f, cue.height * 0.6f, maxLines = 1)
            val w = f.width + f.type.size * 0.9f
            val h = f.height + f.type.size * 0.35f
            val land = at + 0.16f
            val t = Transform(
                x = cue.x.anim, y = cue.y.anim,
                scale = Anim.tween(2.6f, 1f, at, land, Easing.ExpoIn), rotation = Anim.tween(-24f, -7f, at, land, Easing.ExpoIn), opacity = Anim.tween(0f, 1f, at, at + 0.08f),
            )
            val frame = ShapeNode(
                ShapeSpec.Rect(w.anim, h.anim, (h * 0.18f * look.roundness).anim),
                stroke = Stroke(look.hot.fill(), f.type.size * 0.09f, round = false), transform = t, name = "stamp-frame",
            )
            val label = TextNode(text, f.type, look.hot.fill(), rtl = cue.rtl, transform = t, name = "stamp-text")
            return Built(
                listOf(group(cue, listOf(frame, label), name)),
                camera = listOf(Craft.punch(land, 0.05f), Craft.shake(land, 12f, cue.seed)),
                sfx = listOf(Sfx(land, SfxKind.Boom, 1f)),
            )
        }
    }

    /** A checklist: items separated by "|" tick in one after another. */
    val Checklist = object : Recipe("list", Kind.Element) {
        override val minHold = 2.5f
        override val preferredHeight = 0.3f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val items = cue.text.split('|', '\n', '،').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_ITEMS)
            if (items.isEmpty()) return Built(emptyList())
            val rowH = cue.height / items.size
            val nodes = items.flatMapIndexed { i, item ->
                val t0 = cue.wordTimes?.getOrNull(i)?.minus(Craft.LEAD) ?: (at + i * Craft.pace(cue.energy, 0.5f, 0.3f))
                val f = fit.fit(item, look.body, rowH * 0.42f, cue.width * 0.82f, rowH * 0.7f, maxLines = 1)
                val y = cue.y - cue.height / 2f + rowH * (i + 0.5f)
                val iconX = if (cue.rtl) cue.x + cue.width / 2f - rowH * 0.3f else cue.x - cue.width / 2f + rowH * 0.3f
                val textX = if (cue.rtl) iconX - rowH * 0.55f - f.width / 2f else iconX + rowH * 0.55f + f.width / 2f
                listOf(
                    ShapeNode(
                        ShapeSpec.Ellipse((rowH * 0.5f).anim, (rowH * 0.5f).anim), fill = look.accent.fill(),
                        transform = Transform(x = iconX.anim, y = y.anim, scale = Anim.tween(0f, 1f, t0, t0 + 0.45f, Easing.Spring(0.5f, 2.2f))), name = "tick-disc",
                    ),
                    ShapeNode(
                        ShapeSpec.Path(Icons.path("check")!!, Icons.VIEWPORT, rowH * 0.3f), stroke = Stroke(look.onAccent.fill(), rowH * 0.045f),
                        trimEnd = Anim.tween(0f, 1f, t0 + 0.12f, t0 + 0.45f, Easing.ExpoOut), transform = Transform(x = iconX.anim, y = y.anim), name = "tick",
                    ),
                    TextNode(
                        item, f.type, look.ink.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, f.type.size),
                        animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.35f), at = t0 + 0.05f, duration = 0.55f, stagger = 0.05f, ease = Easing.ExpoOut, clipToLine = true)),
                        transform = Transform(x = textX.anim, y = y.anim), name = "item-$i",
                    ),
                )
            }
            return Built(listOf(group(cue, nodes, name)), sfx = items.indices.map { Sfx(at + it * 0.4f, SfxKind.Click, 0.5f) })
        }
    }

    private const val MAX_ITEMS = 6

    val all: List<Recipe> = listOf(Counter, Ticker, Chart, Bars, IconPop, LowerThird, Stamp, Checklist)
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
