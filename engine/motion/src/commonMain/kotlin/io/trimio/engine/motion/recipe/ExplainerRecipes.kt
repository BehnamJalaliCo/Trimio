package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Explainer devices for product and tech pieces: the things an editor builds by hand for every
 * "here is how it works" reel — names that pop as they are said, a terminal, a system map drawing
 * itself, a before/after meter and the comment call to action.
 */
object ExplainerRecipes {

    private val Dark = Color(0xFF14110F)
    private val DarkRim = Color(0x33FFFFFF)

    /** The whole device lifts and fades out together at the end of its cue. */
    private fun group(cue: Cue, children: List<Node>, name: String): Group {
        val exitAt = cue.out - 0.28f
        return Group(
            children,
            transform = Transform(
                anchorX = 0f, anchorY = 0f,
                y = anim(0f, exitAt) { by(-26f, 0.28f, Easing.Exit) },
                opacity = anim(1f, exitAt) { by(0f, 0.28f, Easing.Exit) },
            ),
            start = cue.at - 0.6f, end = cue.out + 0.02f, name = name,
        )
    }

    private fun shadow() = Shadow(Color.Black.copy(alpha = 0.4f), 0f, 14f, 30f)

    /** A gentle repeating pulse between [at] and [until] (for things that ask to be tapped). */
    private fun pulse(at: Float, until: Float, period: Float, amount: Float): Anim {
        val keys = mutableListOf(Anim.Key(at, 1f, Easing.Hold))
        var t = at
        while (t + period <= until) {
            keys += Anim.Key(t + period * 0.35f, 1f + amount, Easing.SineInOut)
            keys += Anim.Key(t + period, 1f, Easing.SineInOut)
            t += period
        }
        return Anim.keys(keys)
    }

    /**
     * Names as pills, each popping in when it is said. The newest lights up in the accent, then
     * settles into glass as the next one arrives — the eye always knows which one is being said.
     */
    val Chips = object : Recipe("chips", Kind.Element) {
        override val preferredHeight = 0.24f
        override val minHold = 2f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val items = cue.items.ifEmpty { cue.text.split('|', '،', ',').map { it.trim() }.filter { it.isNotEmpty() } }.take(MAX_CHIPS)
            if (items.isEmpty()) return Built(emptyList())
            // Largest pill height at which the rows (at most two pills each) fit the slot.
            val voice = look.label.copy(weight = 700, tracking = 0f)
            var h = minOf(cue.height / 2.2f, cue.width * 0.17f)
            val gap: Float
            val fitted: List<Fitted>
            val widths: List<Float>
            val rows = mutableListOf<MutableList<Int>>()
            while (true) {
                val g = h * 0.28f
                val f = items.map { fit.fit(it, voice, h * 0.56f, cue.width * 0.8f, h, maxLines = 1) }
                val ws = f.map { it.width + h * 1.3f }
                rows.clear()
                var rowW = 0f
                for (i in items.indices) {
                    if (rows.isEmpty() || rows.last().size >= 2 || rowW + ws[i] + g > cue.width) { rows += mutableListOf<Int>(); rowW = 0f }
                    rows.last() += i
                    rowW += ws[i] + g
                }
                if (rows.size * h + (rows.size - 1) * g <= cue.height || h < cue.height * 0.12f) { gap = g; fitted = f; widths = ws; break }
                h *= 0.9f
            }
            val times = items.indices.map { i -> (cue.itemTimes.getOrNull(i) ?: (cue.at + i * 0.35f)) - Craft.LEAD }
            val totalH = rows.size * h + (rows.size - 1) * gap
            val nodes = mutableListOf<Node>()
            for ((r, row) in rows.withIndex()) {
                val rw = row.sumOf { widths[it].toDouble() }.toFloat() + gap * (row.size - 1)
                // Reading order: right to left for Persian pieces.
                var x = if (cue.rtl) cue.x + rw / 2f else cue.x - rw / 2f
                val y = cue.y - totalH / 2f + h / 2f + r * (h + gap)
                for (i in row) {
                    val w = widths[i]
                    val cx = if (cue.rtl) x - w / 2f else x + w / 2f
                    x = if (cue.rtl) x - w - gap else x + w + gap
                    val t0 = times[i]
                    val next = times.getOrNull(i + 1) ?: (t0 + 0.9f)
                    val lit = ColorAnim.keys(
                        Triple(t0, look.accent, Easing.Hold),
                        Triple(maxOf(next, t0 + 0.5f), look.accent, Easing.Hold),
                        Triple(maxOf(next, t0 + 0.5f) + 0.25f, Dark, Easing.Standard),
                    )
                    val ink = ColorAnim.keys(
                        Triple(t0, look.onAccent, Easing.Hold),
                        Triple(maxOf(next, t0 + 0.5f), look.onAccent, Easing.Hold),
                        Triple(maxOf(next, t0 + 0.5f) + 0.25f, Color(0xFFF2EDE4), Easing.Standard),
                    )
                    val pop = Transform(
                        x = cx.anim, y = y.anim,
                        scale = anim(0.4f, t0) { by(1f, 0.45f, Easing.Spring(0.5f, 2.4f)) },
                        opacity = Anim.tween(0f, 1f, t0, t0 + 0.08f),
                        rotation = Anim.tween(if (i % 2 == 0) -8f else 8f, 0f, t0, t0 + 0.5f, Easing.ExpoOut),
                    )
                    nodes += ShapeNode(
                        ShapeSpec.Rect(w.anim, h.anim, (h / 2f).anim), fill = Fill.Solid(lit), stroke = Stroke(DarkRim.fill(), 2f),
                        shadow = shadow(), transform = pop, name = "chip-$i",
                    )
                    nodes += ShapeNode(
                        ShapeSpec.Ellipse((h * 0.22f).anim, (h * 0.22f).anim), fill = Fill.Solid(ink),
                        transform = Transform(
                            x = (cx + (if (cue.rtl) 1f else -1f) * (w / 2f - h * 0.42f)).anim, y = y.anim,
                            scale = pop.scale, opacity = pop.opacity,
                        ),
                        name = "chip-dot-$i",
                    )
                    nodes += TextNode(
                        items[i], fitted[i].type, Fill.Solid(ink), rtl = false,
                        transform = Transform(
                            x = (cx + (if (cue.rtl) -1f else 1f) * h * 0.18f).anim, y = y.anim,
                            scale = pop.scale, opacity = pop.opacity,
                        ),
                        name = "chip-label-$i",
                    )
                }
            }
            return Built(listOf(group(cue, nodes, name)), sfx = times.map { Sfx(it + Craft.LEAD, SfxKind.Pop, 0.55f) })
        }
    }

    /** A terminal window: the command types itself, then a green tick answers. */
    val Terminal = object : Recipe("terminal", Kind.Element) {
        override val preferredHeight = 0.2f
        override val minHold = 2.2f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val command = cue.label ?: cue.text.ifBlank { "install" }
            val result = cue.items.firstOrNull() ?: "done"
            val w = cue.width * 0.92f
            val h = minOf(cue.height, w * 0.42f)
            val left = cue.x - w / 2f
            val top = cue.y - h / 2f
            val bar = h * 0.2f
            val mono = look.label.copy(weight = 600, tracking = 0.02f)
            val cf = fit.fit("$ $command", mono, h * 0.21f, w * 0.86f, h * 0.3f, maxLines = 1)
            val rf = fit.fit("✓ $result", mono, cf.type.size, w * 0.86f, h * 0.3f, maxLines = 1)
            val typing = (command.length * 0.045f).coerceIn(0.4f, 1.3f)
            val doneAt = at + 0.3f + typing + 0.25f
            val enter = Transform(
                x = cue.x.anim, y = cue.y.anim,
                scale = Anim.tween(0.9f, 1f, at, at + 0.55f, Easing.Land),
                opacity = Anim.tween(0f, 1f, at, at + 0.15f),
                rotationX = Anim.tween(25f, 0f, at, at + 0.6f, Easing.ExpoOut),
            )
            val nodes = mutableListOf<Node>(
                ShapeNode(
                    ShapeSpec.Rect(w.anim, h.anim, (h * 0.09f * look.roundness).anim), fill = Dark.fill(), stroke = Stroke(DarkRim.fill(), 2f),
                    shadow = shadow(), transform = enter, name = "term",
                ),
            )
            // Window controls, always on the left like a real terminal.
            listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840)).forEachIndexed { i, c ->
                nodes += ShapeNode(
                    ShapeSpec.Ellipse((bar * 0.42f).anim, (bar * 0.42f).anim), fill = c.fill(),
                    transform = Transform(x = (left + bar * (0.75f + i * 0.62f)).anim, y = (top + bar * 0.6f).anim, opacity = enter.opacity),
                    name = "term-dot",
                )
            }
            val lineX = left + w * 0.07f
            val typed = TextAnimator(TextUnit.Char, UnitState(opacity = 0f), at = at + 0.3f, duration = 0.01f, stagger = typing / command.length.coerceAtLeast(1), ease = Easing.Hold)
            nodes += TextNode(
                "$ $command", cf.type, Color(0xFFF2EDE4).fill(), rtl = false, animators = listOf(typed),
                transform = Transform(x = (lineX + cf.width / 2f).anim, y = (top + bar + h * 0.24f).anim, opacity = enter.opacity), name = "term-cmd",
            )
            nodes += ShapeNode(
                ShapeSpec.Rect((cf.type.size * 0.5f).anim, (cf.type.size * 0.95f).anim), fill = look.accent.fill(),
                transform = Transform(
                    x = Anim.tween(lineX + cf.type.size * 0.9f, lineX + cf.width + cf.type.size * 0.4f, at + 0.3f, at + 0.3f + typing, Easing.Linear),
                    y = (top + bar + h * 0.24f).anim,
                    opacity = Anim.keys((0 until 12).map { Anim.Key(at + it * 0.26f, if (it % 2 == 0) 1f else 0f, Easing.Hold) }),
                ),
                end = doneAt, name = "term-cursor",
            )
            nodes += TextNode(
                "✓ $result", rf.type, look.positive.fill(), rtl = false,
                animators = listOf(TextAnimator(TextUnit.All, UnitState(dx = -0.4f, opacity = 0f), at = doneAt, duration = 0.4f, ease = Easing.ExpoOut)),
                transform = Transform(x = (lineX + rf.width / 2f).anim, y = (top + bar + h * 0.55f).anim, opacity = enter.opacity), name = "term-result",
            )
            return Built(
                listOf(group(cue, nodes, name)),
                sfx = listOf(Sfx(at, SfxKind.Swish, 0.5f), Sfx(at + 0.35f, SfxKind.Tick, 0.4f), Sfx(doneAt, SfxKind.Pop, 0.7f)),
            )
        }
    }

    /**
     * A system map drawing itself: a hub, rings of modules wired to it and to each other, edges
     * tracing outward in waves, nodes landing as their edge arrives, a pulse when it is complete.
     */
    val Network = object : Recipe("network", Kind.Element) {
        override val preferredHeight = 0.42f
        override val minHold = 2.8f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val r = minOf(cue.width, cue.height) * 0.52f
            val labels = cue.items.ifEmpty { listOf("api", "ui", "db", "auth", "core", "docs", "tests", "utils", "build") }.take(MAX_NODES)
            val rnd = kotlin.random.Random(cue.seed)
            val inner = labels.size.coerceAtMost(5)
            val points = labels.mapIndexed { i, _ ->
                val ring = if (i < inner) 0.55f else 1f
                val count = if (i < inner) inner else labels.size - inner
                val k = if (i < inner) i else i - inner
                val a = (k + if (i < inner) 0f else 0.5f) / count * 2f * PI.toFloat() - PI.toFloat() / 2f + rnd.nextFloat() * 0.25f
                Pair(cue.x + cos(a) * r * ring, cue.y + sin(a) * r * ring)
            }
            val hub = Pair(cue.x, cue.y)
            val edges = mutableListOf<Triple<Pair<Float, Float>, Pair<Float, Float>, Float>>()
            points.forEachIndexed { i, p ->
                val parent = if (i < inner) hub else points[(i - inner) % inner]
                val wave = if (i < inner) 0f else 1f
                edges += Triple(parent, p, at + 0.25f + wave * 0.55f + (i % inner) * 0.08f)
            }
            // A few cross links make it read as a map, not a tree.
            for (i in inner until labels.size - 1 step 2) edges += Triple(points[i], points[i + 1], at + 1.15f + i * 0.04f)
            val landed = edges.maxOf { it.third } + 0.4f
            val nodes = mutableListOf<Node>()
            for ((a, b, t) in edges) {
                val len = sqrt((b.first - a.first) * (b.first - a.first) + (b.second - a.second) * (b.second - a.second))
                nodes += ShapeNode(
                    ShapeSpec.Polyline(listOf(0f to 0f, (b.first - a.first) to (b.second - a.second)), smooth = false),
                    stroke = Stroke(look.ink.copy(alpha = 0.35f).fill(), maxOf(2f, r * 0.012f)),
                    trimEnd = Anim.tween(0f, 1f, t, t + 0.35f + len / 4000f, Easing.ExpoOut),
                    transform = Transform(x = a.first.anim, y = a.second.anim, anchorX = 0f, anchorY = 0f),
                    name = "edge",
                )
            }
            val nodeR = r * 0.085f
            points.forEachIndexed { i, p ->
                val t = edges[i].third + 0.25f
                val main = i < inner
                val d = if (main) nodeR * 2.2f else nodeR * 1.5f
                nodes += ShapeNode(
                    ShapeSpec.Ellipse(d.anim, d.anim), fill = (if (main) look.ink else Dark).fill(), stroke = Stroke(look.ink.copy(alpha = 0.6f).fill(), 2.5f),
                    transform = Transform(x = p.first.anim, y = p.second.anim, scale = Anim.tween(0f, 1f, t, t + 0.4f, Easing.Spring(0.5f, 2.2f))),
                    name = "node",
                )
                // Labels read at phone size: up to twice the node radius (never under ~4% of the frame
                // width, even on a small slot), as wide as the ring allows; full ink and a shadow over footage.
                val lf = fit.fit(labels[i], look.label.copy(weight = 700), maxOf(nodeR * 2.1f, cue.width * 0.048f), r * 0.85f, maxOf(nodeR * 2.6f, cue.width * 0.06f), maxLines = 1)
                nodes += TextNode(
                    labels[i], lf.type, look.ink.copy(alpha = if (cue.overMedia) 1f else 0.85f).fill(), rtl = labels[i].any { it in '\u0600'..'\u06FF' },
                    shadow = Craft.shadow(cue, look, lf.type.size),
                    transform = Transform(x = p.first.anim, y = (p.second + d / 2f + lf.height * 0.75f).anim, opacity = Anim.tween(0f, 1f, t + 0.1f, t + 0.4f)),
                    name = "node-label",
                )
            }
            val hubD = nodeR * 3.2f
            nodes += ShapeNode(
                ShapeSpec.Ellipse(hubD.anim, hubD.anim), stroke = Stroke(look.accent.fill(), 4f),
                transform = Transform(
                    x = hub.first.anim, y = hub.second.anim,
                    scale = Anim.tween(1f, 3.2f, landed, landed + 0.9f, Easing.ExpoOut), opacity = Anim.tween(0.9f, 0f, landed, landed + 0.9f, Easing.ExpoOut),
                ),
                start = landed, end = landed + 1f, name = "hub-ring",
            )
            nodes += ShapeNode(
                ShapeSpec.Ellipse(hubD.anim, hubD.anim), fill = look.accent.fill(), shadow = Shadow(look.accent.copy(alpha = 0.5f), 0f, 0f, 30f),
                transform = Transform(x = hub.first.anim, y = hub.second.anim, scale = anim(0f, at) { by(1f, 0.5f, Easing.Spring(0.45f, 2.2f)) }),
                name = "hub",
            )
            nodes += ShapeNode(
                ShapeSpec.Path(Icons.path(cue.icon ?: "target")!!, Icons.VIEWPORT, hubD * 0.55f), stroke = Stroke(look.onAccent.fill(), hubD * 0.06f),
                trimEnd = Anim.tween(0f, 1f, at + 0.2f, at + 0.7f, Easing.ExpoOut), transform = Transform(x = hub.first.anim, y = hub.second.anim), name = "hub-icon",
            )
            val sfx = listOf(Sfx(at, SfxKind.Riser, 0.4f)) + edges.map { it.third }.distinctBy { (it * 4).toInt() }.map { Sfx(it, SfxKind.Tick, 0.25f) } +
                Sfx(landed, SfxKind.Shimmer, 0.6f)
            return Built(listOf(group(cue, nodes, name)), sfx = sfx)
        }
    }

    /**
     * Before and after as two bars: both fill, then "after" collapses to what is left while the
     * saving counts up beside it — the claim, made visible.
     */
    val Meter = object : Recipe("meter", Kind.Element) {
        override val preferredHeight = 0.5f
        override val minHold = 3f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val saved = (cue.value ?: 90f).coerceIn(0f, 100f)
            val labels = cue.items.ifEmpty { listOf("قبل", "بعد") }
            val w = cue.width * 0.92f
            // Vertical rhythm: the number, its caption, then the two bars.
            val numberH = cue.height * 0.34f
            val barH = cue.height * 0.095f
            val numberY = cue.y - cue.height * 0.26f
            val labelVoice = look.body.copy(weight = 700)
            val lf = labels.take(2).map { fit.fit(it, labelVoice, barH * 0.7f, w * 0.36f, barH * 1.2f, maxLines = 1) }
            val labelW = lf.maxOf { it.width } + barH * 0.6f
            val trackW = w - labelW
            val right = cue.x + w / 2f
            val trackRight = if (cue.rtl) right - labelW else right
            val y0 = cue.y + cue.height * 0.16f
            val y1 = y0 + barH * 1.9f
            val fillEnd = at + 0.7f
            val drop = fillEnd + 0.35f
            val settle = drop + 0.9f
            val radius = (barH / 2f * minOf(1f, look.roundness)).anim
            val nodes = mutableListOf<Node>()
            // Bars grow from the reading-start edge; in RTL that is the right end of the track.
            val anchorX = if (cue.rtl) 1f else 0f
            val edgeX = if (cue.rtl) trackRight else right - trackW
            fun bar(y: Float, color: Color, width: Anim, name: String) = ShapeNode(
                ShapeSpec.Rect(width, barH.anim, radius), fill = color.fill(),
                transform = Transform(x = edgeX.anim, y = y.anim, anchorX = anchorX), name = name,
            )
            for (y in listOf(y0, y1)) {
                nodes += ShapeNode(
                    ShapeSpec.Rect(trackW.anim, barH.anim, radius), fill = look.ink.copy(alpha = 0.08f).fill(),
                    transform = Transform(x = edgeX.anim, y = y.anim, anchorX = anchorX, opacity = Anim.tween(0f, 1f, at, at + 0.2f)),
                    name = "meter-track",
                )
            }
            nodes += bar(y0, look.muted, Anim.tween(0f, trackW, at + 0.1f, fillEnd, Easing.ExpoOut), "meter-before")
            nodes += bar(
                y1, look.accent,
                anim(0f, at + 0.2f) { to(trackW, fillEnd + 0.05f, Easing.ExpoOut); hold(drop); to(trackW * (100f - saved) / 100f, settle, Easing.ExpoInOut) },
                "meter-after",
            )
            lf.forEachIndexed { i, f ->
                val y = if (i == 0) y0 else y1
                val x = if (cue.rtl) right - f.width / 2f else cue.x - w / 2f + f.width / 2f
                nodes += TextNode(
                    labels[i], f.type, (if (i == 0) look.muted else look.ink).fill(), rtl = cue.rtl,
                    transform = Transform(x = x.anim, y = y.anim, opacity = Anim.tween(0f, 1f, at + 0.1f * i, at + 0.3f + 0.1f * i)), name = "meter-label",
                )
            }
            val big = fit.fit("−${saved.toInt()}٪", look.number, numberH, w * 0.8f, numberH, maxLines = 1)
            nodes += CounterNode(
                value = Anim.tween(0f, saved, drop, settle, Easing.ExpoInOut), decimals = 0, prefix = cue.prefix.ifEmpty { "−" },
                suffix = cue.suffix.ifEmpty { "٪" }, persianDigits = cue.rtl, type = big.type, fill = look.accent.fill(),
                transform = Transform(
                    x = cue.x.anim, y = numberY.anim,
                    scale = anim(0.8f, drop) { to(1f, drop + 0.4f, Easing.Land); hold(settle); by(1.08f, 0.08f, Easing.ExpoOut); by(1f, 0.3f, Easing.Land) },
                    opacity = Anim.tween(0f, 1f, drop, drop + 0.15f),
                ),
                name = "meter-number",
            )
            cue.label?.let { label ->
                val tf = fit.fit(label, look.body, numberH * 0.3f, w, cue.height * 0.1f, maxLines = 1)
                nodes += TextNode(
                    label, tf.type, look.ink.fill(), rtl = cue.rtl,
                    animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.3f), at = settle - 0.2f, duration = 0.6f, stagger = 0.06f, ease = Easing.ExpoOut, clipToLine = true)),
                    transform = Transform(x = cue.x.anim, y = (numberY + big.height / 2f + tf.height * 0.8f).anim), name = "meter-caption",
                )
            }
            return Built(
                listOf(group(cue, nodes, name)),
                camera = listOf(Craft.punch(settle, 0.03f)),
                sfx = listOf(Sfx(at, SfxKind.Swish, 0.5f), Sfx(drop, SfxKind.Riser, 0.4f), Sfx(settle, SfxKind.Boom, 0.8f)),
            )
        }
    }

    /**
     * The comment call to action: a comment field slides up, the keyword types itself in, the
     * send button lights, and an arrow keeps pointing at the comments until the end.
     */
    val Comment = object : Recipe("comment", Kind.Element) {
        override val preferredHeight = 0.2f
        override val minHold = 2.5f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val keyword = cue.text.ifBlank { cue.items.firstOrNull() ?: "" }
            val w = cue.width * 0.9f
            val h = minOf(cue.height * 0.46f, w * 0.24f)
            val y = cue.y + cue.height * 0.12f
            val left = cue.x - w / 2f
            val enter = Transform(
                x = cue.x.anim, y = (y).anim + Anim.tween(h * 1.2f, 0f, at, at + 0.55f, Easing.ExpoOut),
                opacity = Anim.tween(0f, 1f, at, at + 0.15f),
                scale = pulse(at + 1.6f, cue.out, 0.9f, 0.035f),
            )
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(
                ShapeSpec.Rect(w.anim, h.anim, (h / 2f).anim), fill = Color.White.fill(), shadow = Shadow(Color.Black.copy(alpha = 0.45f), 0f, 18f, 40f),
                transform = enter, name = "comment-field",
            )
            // Avatar on the reading-start side, send button on the other.
            val startX = if (cue.rtl) left + w - h * 0.55f else left + h * 0.55f
            val endX = if (cue.rtl) left + h * 0.55f else left + w - h * 0.55f
            nodes += ShapeNode(
                ShapeSpec.Ellipse((h * 0.7f).anim, (h * 0.7f).anim), fill = Fill.Linear(listOf(look.hot, look.accent), angle = 45f.anim),
                transform = Transform(x = startX.anim, y = enter.y, opacity = enter.opacity, scale = enter.scale), name = "comment-avatar",
            )
            val kf = fit.fit(keyword, look.headline, h * 0.72f, w * 0.55f, h * 0.95f, maxLines = 1)
            val typeAt = at + 0.45f
            val typing = (keyword.length * 0.12f).coerceIn(0.2f, 0.9f)
            val textX = if (cue.rtl) startX - h * 0.55f - kf.width / 2f else startX + h * 0.55f + kf.width / 2f
            nodes += TextNode(
                keyword, kf.type, Color(0xFF111111).fill(), rtl = cue.rtl,
                animators = listOf(
                    TextAnimator(TextUnit.Char, UnitState(opacity = 0f, scale = 1.4f), at = typeAt, duration = 0.12f, stagger = typing / keyword.length.coerceAtLeast(1), ease = Easing.ExpoOut),
                ),
                transform = Transform(x = textX.anim, y = enter.y, opacity = enter.opacity, scale = enter.scale), name = "comment-keyword",
            )
            val sendAt = typeAt + typing + 0.25f
            val send = ColorAnim.tween(Color(0xFFDADADA), look.hot, sendAt, sendAt + 0.2f)
            nodes += ShapeNode(
                ShapeSpec.Ellipse((h * 0.72f).anim, (h * 0.72f).anim), fill = Fill.Solid(send),
                transform = Transform(x = endX.anim, y = enter.y, opacity = enter.opacity, scale = anim(1f, sendAt) { by(1.25f, 0.1f, Easing.ExpoOut); by(1f, 0.4f, Easing.Land) }),
                name = "comment-send",
            )
            nodes += ShapeNode(
                ShapeSpec.Path(Icons.path("arrow-up")!!, Icons.VIEWPORT, h * 0.38f), stroke = Stroke(Color.White.fill(), h * 0.06f),
                transform = Transform(x = endX.anim, y = enter.y, opacity = enter.opacity), name = "comment-send-icon",
            )
            cue.label?.let { label ->
                val lf = fit.fit(label, look.headline, cue.height * 0.26f, cue.width, cue.height * 0.36f, maxLines = 1)
                nodes += TextNode(
                    label, lf.type, look.ink.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, lf.type.size),
                    animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.3f), at = at, duration = 0.6f, stagger = 0.07f, ease = Easing.ExpoOut, clipToLine = true)),
                    transform = Transform(x = cue.x.anim, y = (y - h * 0.5f - lf.height * 0.75f).anim), name = "comment-title",
                )
            }
            // An arrow below the field keeps pointing at the comments.
            val arrowY = y + h * 1.05f
            val bob = Anim.keys(List(((cue.out - sendAt) / 0.35f).toInt().coerceAtLeast(1) + 1) { k -> Anim.Key(sendAt + k * 0.35f, if (k % 2 == 0) 0f else h * 0.18f, Easing.SineInOut) })
            nodes += ShapeNode(
                ShapeSpec.Path(Icons.path("arrow-down")!!, Icons.VIEWPORT, h * 0.6f), stroke = Stroke(look.accent.fill(), h * 0.07f),
                trimEnd = Anim.tween(0f, 1f, sendAt, sendAt + 0.4f, Easing.ExpoOut),
                transform = Transform(x = cue.x.anim, y = arrowY.anim + bob), name = "comment-arrow",
            )
            return Built(
                listOf(group(cue, nodes, name)),
                sfx = listOf(Sfx(at, SfxKind.Swish, 0.5f), Sfx(typeAt, SfxKind.Tick, 0.4f), Sfx(sendAt, SfxKind.Pop, 0.8f)),
            )
        }
    }

    private const val MAX_CHIPS = 8
    private const val MAX_NODES = 11

    val all: List<Recipe> = listOf(Chips, Terminal, Network, Meter, Comment)
}
