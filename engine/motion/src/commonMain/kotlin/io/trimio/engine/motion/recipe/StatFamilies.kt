package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Figures with a shape: a stat stack (stacked, tiled, or one hero with two seconds), a progress
 * ring (a full ring, a ring beside its words, a gauge with a needle), social proof (reaction
 * counters, a star rating, a crowd of faces) and a price tag (a hanging tag, a sale burst, a
 * card with the old price struck through).
 */
object StatFamilies {

    private fun free(look: Look, cue: Cue): Color? = look.canvas.takeIf { !cue.overMedia }

    private fun inkFree(look: Look, cue: Cue) = free(look, cue)?.let { Kit.inkOn(look, it) } ?: look.ink

    private fun figure(cue: Cue, v: Float) = cue.prefix + Kit.format(v, cue.decimals, false) + cue.suffix

    // ------------------------------------------------------------------ stat stack

    /** Two to four figures ([Cue.points]) with their labels ([Cue.items]). */
    val StatStack = object : Family("stat-stack", listOf(Move.Rise, Move.Flip, Move.Slide)) {
        override val minHold = 3.2f
        override val preferredHeight = 0.34f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val values = cue.points?.take(MAX).orEmpty().ifEmpty { listOfNotNull(cue.value) }.ifEmpty { return Built(emptyList()) }
            val labels = values.indices.map { cue.items.getOrNull(it) ?: "" }
            return when (if (values.size < 2) 0 else p.layout) {
                1 -> tiles(cue, look, fit, p, values, labels)
                2 -> hero(cue, look, fit, p, values, labels)
                else -> stack(cue, look, fit, p, values, labels)
            }
        }

        private fun stack(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, values: List<Float>, labels: List<String>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val rowH = b.height / values.size
            val nodes = mutableListOf<Node>()
            // One column for the figures, as wide as the widest of them.
            val numW = values.maxOf { Kit.fit(fit, figure(cue, it), Kit.numberVoice(look, p.typo), rowH * 0.62f, b.width * 0.5f, rowH * 0.8f).width }
            for ((i, v) in values.withIndex()) {
                val top = b.top + i * rowH
                val row = Box(b.left, top, b.right, top + rowH)
                val t0 = p.at + 0.1f + i * 0.3f
                val nf = Kit.fit(fit, figure(cue, v), Kit.numberVoice(look, p.typo), rowH * 0.62f, b.width * 0.5f, rowH * 0.8f)
                val color = ground?.let { Kit.tint(look, if (i == 0) look.accent else look.ink, it) } ?: if (i == 0) look.accent else look.ink
                val parts = mutableListOf<Node>(
                    Kit.number(
                        v, 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size, color,
                        Kit.fromStart(Kit.startX(row, p.rtl, 0f), nf.width, p.rtl), row.centerY, t0, 1f, ground, p.rtl,
                    ),
                )
                if (i > 0) parts += Kit.line(row.left, row.top, row.right, row.top, inkFree(look, cue).copy(alpha = 0.25f), 2f, t0 - 0.1f, name = "stat-rule")
                labels[i].takeIf { it.isNotBlank() }?.let { l ->
                    val lf = Kit.fit(fit, l, Kit.titleVoice(look, p.typo), rowH * 0.26f, b.width - numW - rowH * 0.4f, rowH * 0.7f, lines = 2)
                    parts += Kit.words(
                        l, lf, inkFree(look, cue), Kit.fromStart(Kit.startX(row, p.rtl, numW + rowH * 0.3f), lf.width, p.rtl), row.centerY, t0 + 0.15f, ground,
                        p.rtl, name = "stat-label",
                    )
                }
                nodes += Kit.item(p.move, row, t0, p.dir, parts, "stat-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, values.indices.map { Sfx(p.at + 0.1f + it * 0.3f + 1f, SfxKind.Tick, 0.4f) })
        }

        private fun tiles(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, values: List<Float>, labels: List<String>): Built {
            val cols = if (values.size <= 3) values.size else 2
            val rows = (values.size + cols - 1) / cols
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * (if (cols == 3) 0.34f else 0.42f) * rows))
            val gap = b.width * 0.035f
            val w = (b.width - gap * (cols - 1)) / cols
            val h = (b.height - gap * (rows - 1)) / rows
            val nodes = mutableListOf<Node>()
            for ((i, v) in values.withIndex()) {
                val c = i % cols
                val r = i / cols
                val left = if (p.rtl) b.right - (c + 1) * w - c * gap else b.left + c * (w + gap)
                val tile = Box(left, b.top + r * (h + gap), left + w, b.top + r * (h + gap) + h)
                val s = Kit.surface(look, cue, if (i == 0) Kit.Tone.Accent else Kit.Tone.Card)
                val ink = Kit.inkOn(look, s.ground)
                val t0 = p.at + 0.1f + i * 0.2f
                val parts = Kit.panel(look, cue, s, tile.centerX, tile.centerY, tile.width, tile.height, h * 0.12f, "stat-tile").toMutableList()
                val nf = Kit.fit(fit, figure(cue, v), Kit.numberVoice(look, p.typo), h * 0.36f, w * 0.84f, h * 0.45f)
                val has = labels[i].isNotBlank()
                parts += Kit.number(
                    v, 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size, ink, tile.centerX,
                    tile.centerY - if (has) h * 0.1f else 0f, t0 + 0.1f, 1f, s.ground, p.rtl,
                )
                if (has) {
                    val lf = Kit.fit(fit, labels[i], Kit.titleVoice(look, p.typo).copy(weight = 700), h * 0.13f, w * 0.84f, h * 0.3f, lines = 2)
                    parts += Kit.words(labels[i], lf, ink, tile.centerX, tile.centerY + h * 0.24f, t0 + 0.2f, s.ground, p.rtl, Kit.Reveal.Fade, name = "stat-label")
                }
                nodes += Kit.item(p.move, tile, t0, p.dir, parts, "stat-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, values.indices.map { Sfx(p.at + 0.1f + it * 0.2f, SfxKind.Pop, 0.4f) })
        }

        /** The first figure big on the reading start; the others small and stacked beside it. */
        private fun hero(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, values: List<Float>, labels: List<String>): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.5f))
            val ground = free(look, cue)
            val split = b.width * 0.58f
            val heroBox = if (p.rtl) Box(b.right - split, b.top, b.right, b.bottom) else Box(b.left, b.top, b.left + split, b.bottom)
            val nodes = mutableListOf<Node>()
            val v0 = values[0]
            val nf = Kit.fit(fit, figure(cue, v0), Kit.numberVoice(look, p.typo), b.height * 0.5f, split * 0.95f, b.height * 0.6f)
            val accent = ground?.let { Kit.tint(look, look.accent, it) } ?: look.accent
            nodes += Kit.number(
                v0, 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size, accent, heroBox.centerX,
                b.centerY - b.height * 0.08f, p.at + 0.1f, 1.2f, ground, p.rtl,
            )
            labels[0].takeIf { it.isNotBlank() }?.let {
                val lf = Kit.fit(fit, it, Kit.titleVoice(look, p.typo), b.height * 0.11f, split * 0.9f, b.height * 0.25f, lines = 2)
                nodes += Kit.words(it, lf, inkFree(look, cue), heroBox.centerX, b.centerY + nf.height * 0.42f + lf.height * 0.3f, p.at + 0.3f, ground, p.rtl, name = "stat-label")
            }
            val dividerX = if (p.rtl) heroBox.left else heroBox.right
            nodes += Kit.line(dividerX, b.top + b.height * 0.1f, dividerX, b.bottom - b.height * 0.1f, inkFree(look, cue).copy(alpha = 0.3f), 2f, p.at + 0.2f, name = "stat-divider")
            val rest = values.drop(1)
            val sideW = b.width - split - b.width * 0.06f
            val rowH = b.height / rest.size
            for ((k, v) in rest.withIndex()) {
                val i = k + 1
                val cx = if (p.rtl) b.left + sideW / 2f else b.right - sideW / 2f
                val cy = b.top + rowH * (k + 0.5f)
                val t0 = p.at + 0.4f + k * 0.25f
                val sf = Kit.fit(fit, figure(cue, v), Kit.numberVoice(look, p.typo), rowH * 0.42f, sideW, rowH * 0.5f)
                val figure = Kit.number(v, 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), sf.type.size, inkFree(look, cue), cx, cy - rowH * 0.1f, t0, 0.9f, ground, p.rtl)
                val parts = mutableListOf<Node>(figure)
                labels[i].takeIf { it.isNotBlank() }?.let {
                    val lf = Kit.fit(fit, it, Kit.titleVoice(look, p.typo).copy(weight = 700), rowH * 0.2f, sideW, rowH * 0.3f)
                    parts += Kit.words(it, lf, ground?.let { g -> Kit.quiet(look, g) } ?: look.ink, cx, cy + sf.height * 0.45f, t0 + 0.1f, ground, p.rtl, Kit.Reveal.Fade, name = "stat-label")
                }
                nodes += Kit.item(p.move, Box(cx - sideW / 2f, cy - rowH / 2f, cx + sideW / 2f, cy + rowH / 2f), t0, p.dir, parts, "stat-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.1f, SfxKind.Riser, 0.4f), Sfx(p.at + 1.3f, SfxKind.Pop, 0.6f)))
        }
    }

    // ------------------------------------------------------------------ progress ring

    /** Points on an arc from [start] degrees sweeping [sweep] degrees (0° at three o'clock, clockwise). */
    private fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, steps: Int = 72): List<Pair<Float, Float>> =
        (0..steps).map { i ->
            val a = (start + sweep * i / steps) * PI.toFloat() / 180f
            cx + r * cos(a) to cy + r * sin(a)
        }

    /** A percentage ([Cue.value], 0–100) as a ring filling: alone, beside its words, or as a gauge. */
    val Ring = object : Family("ring", listOf(Move.Pop, Move.Focus, Move.Rise)) {
        override val minHold = 2.8f
        override val preferredHeight = 0.3f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val v = (cue.value ?: return Built(emptyList())).coerceIn(0f, 100f)
            return when (p.layout) {
                1 -> beside(cue, look, fit, p, v)
                2 -> gauge(cue, look, fit, p, v)
                else -> ring(cue, look, fit, p, v)
            }
        }

        /** Track and fill of a ring of diameter [d] at [cx], [cy], filling to [v]% from twelve o'clock. */
        private fun ringNodes(look: Look, cx: Float, cy: Float, d: Float, v: Float, at: Float, fillTime: Float, stroke: Float): List<Node> {
            val track = ShapeNode(ShapeSpec.Ellipse(d.anim, d.anim), stroke = Stroke(look.ink.copy(alpha = 0.14f).fill(), stroke), transform = Transform(x = cx.anim, y = cy.anim), name = "ring-track")
            val pts = arc(d / 2f, d / 2f, d / 2f, -90f, 360f * v / 100f)
            val fillNode = ShapeNode(
                ShapeSpec.Polyline(pts, smooth = false), stroke = Stroke(look.accent.fill(), stroke),
                trimEnd = Anim.tween(0f, 1f, at, at + fillTime, Easing.ExpoOut), transform = Transform(x = (cx - d / 2f).anim, y = (cy - d / 2f).anim, anchorX = 0f, anchorY = 0f), name = "ring-fill",
            )
            val end = pts.last()
            val tip = ShapeNode(
                ShapeSpec.Ellipse((stroke * 1.6f).anim, (stroke * 1.6f).anim), fill = look.accent.fill(),
                transform = Transform(
                    x = (cx - d / 2f + end.first).anim, y = (cy - d / 2f + end.second).anim,
                    scale = Anim.tween(0f, 1f, at + fillTime * 0.8f, at + fillTime + 0.3f, Easing.Spring(0.45f, 2.4f)),
                ),
                name = "ring-tip",
            )
            return listOf(track, fillNode, tip)
        }

        private fun ring(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, v: Float): Built {
            val label = cue.label ?: cue.text.takeIf { it.isNotBlank() }
            val d = minOf(cue.height * (if (label != null) 0.74f else 0.92f), cue.width * 0.6f)
            val b = Kit.box(cue, cue.width, d + (if (label != null) d * 0.3f else 0f))
            val ground = free(look, cue)
            val cy = b.top + d / 2f
            val fillTime = Craft.pace(cue.energy, 1.5f, 1f)
            val nodes = ringNodes(look, b.centerX, cy, d * 0.9f, v, p.at + 0.15f, fillTime, d * 0.08f).toMutableList()
            val sample = Kit.format(v, cue.decimals, false) + "%"
            val nf = Kit.fit(fit, sample, Kit.numberVoice(look, p.typo), d * 0.26f, d * 0.6f, d * 0.4f)
            val pct = if (p.rtl) "٪" else "%"
            nodes += Kit.number(v, 0f, cue.decimals, "", pct, Kit.numberVoice(look, p.typo), nf.type.size, inkFree(look, cue), b.centerX, cy, p.at + 0.15f, fillTime, ground, p.rtl)
            label?.let {
                val lf = Kit.fit(fit, it, Kit.titleVoice(look, p.typo), d * 0.12f, b.width, d * 0.25f)
                nodes += Kit.words(it, lf, inkFree(look, cue), b.centerX, b.bottom - lf.height / 2f, p.at + 0.4f, ground, p.rtl, name = "ring-label")
            }
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.15f, SfxKind.Riser, 0.4f), Sfx(p.at + 0.15f + fillTime, SfxKind.Pop, 0.6f)))
        }

        private fun beside(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, v: Float): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.4f))
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val d = b.height * 0.72f
            val cx = Kit.startX(b, p.rtl, b.height * 0.14f + d / 2f)
            val fillTime = Craft.pace(cue.energy, 1.4f, 0.9f)
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.2f, "ring-card").toMutableList()
            nodes += ringNodes(look, cx, b.centerY, d, v, p.at + 0.2f, fillTime, d * 0.11f)
            val small = Kit.fit(fit, Kit.format(v, cue.decimals, false) + "%", Kit.numberVoice(look, p.typo), d * 0.26f, d * 0.62f, d * 0.4f)
            nodes += Kit.number(v, 0f, cue.decimals, "", if (p.rtl) "٪" else "%", Kit.numberVoice(look, p.typo), small.type.size, ink, cx, b.centerY, p.at + 0.2f, fillTime, s.ground, p.rtl)
            val text = cue.label ?: cue.text.ifBlank { Kit.say(p.rtl, "تکمیل شد", "complete") }
            val room = b.width - d - b.height * 0.5f
            val tf = Kit.fit(fit, text, Kit.titleVoice(look, p.typo), b.height * 0.2f, room, b.height * 0.7f, lines = 2)
            nodes += Kit.words(text, tf, ink, Kit.fromStart(cx - p.dir * (-d / 2f - b.height * 0.18f), tf.width, p.rtl), b.centerY, p.at + 0.35f, s.ground, p.rtl, name = "ring-label")
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.2f, SfxKind.Riser, 0.4f), Sfx(p.at + 0.2f + fillTime, SfxKind.Pop, 0.6f)))
        }

        /** A half-circle gauge in three zones; a needle swings to the value with a little overshoot. */
        private fun gauge(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, v: Float): Built {
            val label = cue.label ?: cue.text.takeIf { it.isNotBlank() }
            val r = minOf(cue.width * 0.36f, cue.height * 0.52f)
            val b = Kit.box(cue, r * 2.3f, r * 1.55f + (if (label != null) r * 0.3f else 0f))
            val cx = b.centerX
            val cy = b.top + r * 1.1f
            val ground = free(look, cue)
            val stroke = r * 0.16f
            val nodes = mutableListOf<Node>()
            // Zones read in the piece's direction: on a Persian piece the scale runs right to left.
            val colors = listOf(look.negative, look.hot, look.positive)
            for ((k, c) in colors.withIndex()) {
                val from = if (p.rtl) -k * 60f else 180f + k * 60f
                val pts = arc(r, r, r, if (p.rtl) from - 58f else from + 1f, 58f, 24)
                nodes += ShapeNode(
                    ShapeSpec.Polyline(pts, smooth = false), stroke = Stroke(c.fill(), stroke, round = false), trimEnd = Anim.tween(
                        0f, 1f, p.at + 0.1f + k * 0.12f, p.at + 0.5f + k * 0.12f, Easing.ExpoOut,
                    ),
                    transform = Transform(x = (cx - r).anim, y = (cy - r).anim, anchorX = 0f, anchorY = 0f), name = "gauge-zone",
                )
            }
            // Rotation 0 points up; the scale runs left to right (right to left on a Persian piece).
            val angle = if (p.rtl) 90f - 180f * v / 100f else -90f + 180f * v / 100f
            val needleLen = r * 0.82f
            val swing = p.at + 0.5f
            val needle = ShapeNode(
                ShapeSpec.Rect((r * 0.05f).anim, needleLen.anim, (r * 0.025f).anim), fill = inkFree(look, cue).fill(),
                transform = Transform(
                    x = cx.anim, y = cy.anim, anchorX = 0.5f, anchorY = 1f,
                    rotation = Anim.tween(if (p.rtl) 90f else -90f, angle, swing, swing + 1.1f, Easing.Spring(0.42f, 1.4f)),
                ),
                name = "gauge-needle",
            )
            nodes += needle
            nodes += Kit.dot(cx, cy, r * 0.16f, inkFree(look, cue), "gauge-hub")
            val nf = Kit.fit(fit, Kit.format(v, cue.decimals, false) + "%", Kit.numberVoice(look, p.typo), r * 0.3f, r * 1.2f, r * 0.4f)
            val ny = cy + r * 0.12f + nf.height / 2f
            nodes += Kit.number(v, 0f, cue.decimals, "", if (p.rtl) "٪" else "%", Kit.numberVoice(look, p.typo), nf.type.size, inkFree(look, cue), cx, ny, swing, 1.1f, ground, p.rtl)
            label?.let {
                val lf = Kit.fit(fit, it, Kit.titleVoice(look, p.typo), r * 0.16f, b.width, r * 0.3f)
                nodes += Kit.words(it, lf, ground?.let { g -> Kit.quiet(look, g) } ?: look.ink, cx, ny + nf.height / 2f + lf.height * 0.7f, p.at + 0.4f, ground, p.rtl, name = "gauge-label")
            }
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(swing, SfxKind.Whoosh, 0.5f), Sfx(swing + 0.6f, SfxKind.Tick, 0.5f)))
        }
    }

    // ------------------------------------------------------------------ social proof

    /**
     * Proof in numbers: reaction counters (likes, comments, shares: [Cue.points] with [Cue.items]),
     * a star rating ([Cue.value] of 5, [Cue.label] the reviews), or a crowd of faces with a count.
     */
    val SocialProof = object : Family("social-proof", listOf(Move.Pop, Move.Rise, Move.Slide)) {
        override val minHold = 2.8f
        override val preferredHeight = 0.2f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built = when (p.layout) {
            1 -> rating(cue, look, fit, p)
            2 -> crowd(cue, look, fit, p)
            else -> reactions(cue, look, fit, p)
        }

        private fun reactions(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val values = cue.points?.take(3).orEmpty().ifEmpty { listOf(cue.value ?: DEFAULT_LIKES) }
            val icons = listOf("heart", "chat", "send")
            val n = values.size
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.2f))
            val gap = b.width * 0.03f
            val w = (b.width - gap * (n - 1)) / n
            val nodes = mutableListOf<Node>()
            for ((i, v) in values.withIndex()) {
                val left = if (p.rtl) b.right - (i + 1) * w - i * gap else b.left + i * (w + gap)
                val pill = Box(left, b.top, left + w, b.bottom)
                val s = Kit.surface(look, cue, if (i == 0) Kit.Tone.Accent else Kit.Tone.Card)
                val ink = Kit.inkOn(look, s.ground)
                val t0 = p.at + 0.1f + i * 0.18f
                val ic = pill.height * 0.42f
                val icx = Kit.startX(pill, p.rtl, pill.height * 0.3f + ic / 2f)
                val parts = Kit.panel(look, cue, s, pill.centerX, pill.centerY, pill.width, pill.height, pill.height / 2f, "proof-pill").toMutableList()
                parts += Kit.icon(cue.items.getOrNull(i)?.let { Icons.path(it)?.let { _ -> it } } ?: icons[i % icons.size], icx, pill.centerY, ic, ink, t0 + 0.1f, ic * 0.1f)
                val k = compact(v, p.rtl)
                val nf = Kit.fit(fit, k.first, Kit.numberVoice(look, p.typo), pill.height * 0.4f, pill.width - ic - pill.height * 0.75f, pill.height * 0.6f)
                parts += Kit.number(
                    k.second, 0f, k.third, "", k.fourth, Kit.numberVoice(look, p.typo), nf.type.size, ink,
                    Kit.fromStart(icx - p.dir * (-ic / 2f - pill.height * 0.15f), nf.width, p.rtl), pill.centerY, t0 + 0.15f, 1.1f, s.ground, p.rtl,
                )
                nodes += Kit.item(p.move, pill, t0, p.dir, parts, "proof-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, values.indices.map { Sfx(p.at + 0.1f + it * 0.18f, SfxKind.Pop, 0.5f) })
        }

        /** 12500 → «۱۲٫۵ هزار» / "12.5K": big counts read in thousands and millions. */
        private fun compact(v: Float, rtl: Boolean): Count {
            val (scale, unit) = when {
                v >= MILLION -> MILLION to Kit.say(rtl, " میلیون", "M")
                v >= THOUSAND -> THOUSAND to Kit.say(rtl, " هزار", "K")
                else -> 1f to ""
            }
            val d = if (scale > 1f && v < scale * 100) 1 else 0
            return Count(Kit.format(v / scale, d, false) + unit, v / scale, d, unit)
        }

        private fun rating(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val score = (cue.value ?: FIVE).coerceIn(0f, FIVE)
            val b = Kit.box(cue, cue.width * 0.86f, minOf(cue.height, cue.width * 0.32f))
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.18f, "rating-card").toMutableList()
            val star = b.height * 0.3f
            val starsW = star * 5.6f
            val y = b.top + b.height * 0.36f
            val x0 = b.centerX - starsW / 2f + star / 2f
            for (i in 0 until 5) {
                // Stars fill in reading order.
                val k = if (p.rtl) 4 - i else i
                val cx = x0 + k * star * 1.15f
                val t0 = p.at + 0.25f + i * 0.12f
                val full = score >= i + 1 - 0.25f
                nodes += ShapeNode(
                    ShapeSpec.Path(Icons.path("star")!!, Icons.VIEWPORT, star), stroke = Stroke(look.ink.copy(alpha = 0.3f).fill(), star * 0.06f),
                    transform = Transform(x = cx.anim, y = y.anim), name = "star-empty",
                )
                if (full) {
                    nodes += ShapeNode(
                        ShapeSpec.Path(Icons.path("star")!!, Icons.VIEWPORT, star), fill = look.hot.fill(), stroke = Stroke(look.hot.fill(), star * 0.06f),
                        transform = Transform(
                            x = cx.anim, y = y.anim, scale = Anim.tween(0f, 1f, t0, t0 + 0.45f, Easing.Spring(0.4f, 2.6f)),
                            rotation = Anim.tween(-60f, 0f, t0, t0 + 0.5f, Easing.ExpoOut),
                        ),
                        name = "star",
                    )
                }
            }
            val sf = Kit.fit(fit, Kit.format(score, 1, false), Kit.numberVoice(look, p.typo), b.height * 0.24f, b.width * 0.3f, b.height * 0.3f)
            val label = cue.label ?: Kit.say(p.rtl, "امتیاز کاربران", "user rating")
            val lf = Kit.fit(fit, label, Kit.labelVoice(look, p.typo), b.height * 0.13f, b.width * 0.6f, b.height * 0.2f)
            val rowY = b.top + b.height * 0.74f
            val total = sf.width + lf.width + b.height * 0.2f
            val start = if (p.rtl) b.centerX + total / 2f else b.centerX - total / 2f
            nodes += Kit.number(score, 0f, 1, "", "", Kit.numberVoice(look, p.typo), sf.type.size, ink, Kit.fromStart(start, sf.width, p.rtl), rowY, p.at + 0.25f, 0.9f, s.ground, p.rtl)
            nodes += Kit.words(
                label, lf, Kit.quiet(look, s.ground), Kit.fromEnd(if (p.rtl) b.centerX - total / 2f else b.centerX + total / 2f, lf.width, p.rtl), rowY,
                p.at + 0.4f, s.ground, p.rtl, Kit.Reveal.Fade, name = "rating-label",
            )
            return Kit.built(cue, look, p, b, nodes, name, (0 until 5).map { Sfx(p.at + 0.25f + it * 0.12f, SfxKind.Tick, 0.35f) })
        }

        /** Overlapping faces (initial discs) then a «+۱۲ هزار» count and the label. */
        private fun crowd(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val count = cue.points?.firstOrNull() ?: cue.value?.takeIf { it > FIVE } ?: DEFAULT_LIKES
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.22f))
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height / 2f, "crowd-pill").toMutableList()
            val d = b.height * 0.66f
            val faces = 4
            val palette = listOf(look.accent, look.hot, look.positive, androidx.compose.ui.graphics.lerp(look.accent, look.hot, 0.5f))
            for (i in 0 until faces) {
                val cx = Kit.startX(b, p.rtl, b.height * 0.2f + d / 2f + i * d * 0.62f)
                val t0 = p.at + 0.1f + i * 0.1f
                nodes += ShapeNode(
                    ShapeSpec.Ellipse(d.anim, d.anim), fill = palette[i].fill(), stroke = Stroke(s.ground.fill(), d * 0.08f),
                    transform = Transform(x = cx.anim, y = b.centerY.anim, scale = Anim.tween(0f, 1f, t0, t0 + 0.4f, Easing.Spring(0.5f, 2.3f))), name = "face",
                )
                nodes += Kit.icon("user", cx, b.centerY, d * 0.56f, Look.textOn(palette[i]), t0 + 0.1f, d * 0.05f)
            }
            val k = compact(count, p.rtl)
            val after = b.height * 0.2f + d + (faces - 1) * d * 0.62f + b.height * 0.2f
            val nf = Kit.fit(fit, "+" + k.first, Kit.numberVoice(look, p.typo), b.height * 0.36f, b.width * 0.36f, b.height * 0.5f)
            nodes += Kit.number(
                k.second, 0f, k.third, "+", k.fourth, Kit.numberVoice(look, p.typo), nf.type.size, Kit.tint(look, look.accent, s.ground),
                Kit.fromStart(Kit.startX(b, p.rtl, after), nf.width, p.rtl), b.centerY, p.at + 0.4f, 1.1f, s.ground, p.rtl,
            )
            val label = cue.label ?: Kit.say(p.rtl, "نفر به ما پیوستند", "people joined")
            val lf = Kit.fit(fit, label, Kit.titleVoice(look, p.typo), b.height * 0.24f, b.width - after - nf.width - b.height * 0.5f, b.height * 0.6f)
            nodes += Kit.words(label, lf, ink, Kit.fromStart(Kit.startX(b, p.rtl, after + nf.width + b.height * 0.2f), lf.width, p.rtl), b.centerY, p.at + 0.5f, s.ground, p.rtl, name = "crowd-label")
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.1f, SfxKind.Pop, 0.5f), Sfx(p.at + 1.5f, SfxKind.Tick, 0.4f)))
        }
    }

    // ------------------------------------------------------------------ price tag

    /**
     * A price ([Cue.value] with [Cue.prefix]/[Cue.suffix]; [Cue.from] the old price, [Cue.label]
     * what it is): a tag swinging on its string, a sale burst, or a card with the old price struck.
     */
    val PriceTag = object : Family("price-tag", listOf(Move.Drop, Move.Pop, Move.Rise)) {
        override val minHold = 2.6f
        override val preferredHeight = 0.24f

        // On a frame of its own the price is the picture: the burst fills it, a wide card would sit small in the middle.
        override fun layoutWeight(look: Look, cue: Cue, layout: Int): Float = if (cue.overMedia) 1f else STAGE_LAYOUTS[layout]

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val v = cue.value ?: return Built(emptyList())
            return when (p.layout) {
                1 -> burst(cue, look, fit, p, v)
                2 -> card(cue, look, fit, p, v)
                else -> tag(cue, look, fit, p, v)
            }
        }

        private fun priceSize(cue: Cue, v: Float) = cue.prefix + Kit.format(v, cue.decimals, false) + cue.suffix

        /** A swing tag: hole, string to the top of the slot, the price and what it is; it sways to rest. */
        private fun tag(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, v: Float): Built {
            val h = minOf(cue.height * 0.78f, cue.width * 0.32f)
            val w = h * 1.9f
            val b = Kit.box(cue, w, h / 0.78f)
            val s = Kit.surface(look, cue, Kit.Tone.Accent)
            val ink = Kit.inkOn(look, s.ground)
            val top = b.bottom - h
            // The point (and the hole) on the reading start: mirrored on a Persian piece.
            val body = ShapeNode(
                ShapeSpec.Path(TAG, 100f, w), fill = s.fill, shadow = s.shadow,
                transform = Transform(x = b.centerX.anim, y = (top + h / 2f).anim, scaleY = (h / w).anim, scaleX = (if (p.rtl) -1f else 1f).anim), name = "tag-body",
            )
            val holeX = Kit.startX(Box(b.centerX - w / 2f, top, b.centerX + w / 2f, top + h), p.rtl, h * 0.34f)
            val hole = Kit.dot(holeX, top + h / 2f, h * 0.14f, look.canvas, "tag-hole")
            // The string runs from a pin above the hole, so the tag hangs from it.
            val pivotX = holeX + p.dir * h * 0.1f
            val pivotY = b.top
            val string = Kit.line(pivotX, pivotY, holeX, top + h / 2f, Kit.inkOn(look, look.canvas), maxOf(3f, h * 0.025f), p.at, 0.3f, "tag-string")
            val text = priceSize(cue, v)
            val nf = Kit.fit(fit, text, Kit.numberVoice(look, p.typo), h * 0.42f, w * 0.66f, h * 0.5f)
            val tx = b.centerX + p.dir * h * 0.16f
            val nodes = mutableListOf<Node>(string, body, hole)
            nodes += Kit.number(
                v, cue.from ?: 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size, ink, tx,
                top + h * (if (cue.label != null) 0.42f else 0.5f), p.at + 0.2f, 0.9f, s.ground, p.rtl,
            )
            cue.label?.let {
                val lf = Kit.fit(fit, it, Kit.labelVoice(look, p.typo), h * 0.15f, w * 0.62f, h * 0.22f)
                nodes += Kit.words(it, lf, ink, tx, top + h * 0.76f, p.at + 0.35f, s.ground, p.rtl, Kit.Reveal.Fade, name = "tag-label")
            }
            // The tag sways about its string's pin and settles.
            val sway = Kit.pivot(nodes, pivotX, pivotY, b, Transform(rotation = anim(p.dir * 14f, p.at) { by(0f, 1.6f, Easing.Spring(0.25f, 1.1f)) }), "tag-sway")
            return Kit.built(cue, look, p, b, listOf(sway), name, listOf(Sfx(p.at + 0.2f, SfxKind.Click, 0.5f)))
        }

        /** A sale burst: a spiky star in the heat colour turning in, the price on it, the old price above. */
        private fun burst(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, v: Float): Built {
            val d = minOf(cue.height, cue.width * 0.5f)
            val b = Kit.box(cue, d, d)
            val s = Kit.surface(look, cue, Kit.Tone.Hot)
            val ink = Kit.inkOn(look, s.ground)
            val star = ShapeNode(
                ShapeSpec.Path(starburst(), 100f, d), fill = s.fill, shadow = s.shadow,
                transform = Transform(x = b.centerX.anim, y = b.centerY.anim, rotation = anim(-40f, p.at) { by(0f, 0.7f, Easing.ExpoOut) } + Anim.Noise(cue.seed, 0.2f, 4f.anim)),
                name = "sale-burst",
            )
            val nodes = mutableListOf<Node>(star)
            // A unit in words («تومان») goes under the figure: on one line with seven digits the price shrinks to a
            // small print on a big star.
            val unit = cue.suffix.trim().takeIf { u -> u.any { it.isLetter() } }
            val figure = if (unit != null) cue.prefix + Kit.format(v, cue.decimals, false) else priceSize(cue, v)
            val nf = Kit.fit(fit, figure, Kit.numberVoice(look, p.typo), d * 0.24f, d * 0.62f, d * 0.3f)
            val ny = b.centerY + d * if (unit != null) 0.02f else 0.06f
            nodes += Kit.number(
                v, cue.from ?: 0f, cue.decimals, cue.prefix, if (unit != null) "" else cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size, ink, b.centerX, ny,
                p.at + 0.25f, 0.8f, s.ground, p.rtl,
            )
            unit?.let {
                val uf = Kit.fit(fit, it, Kit.labelVoice(look, p.typo).copy(weight = 800), d * 0.1f, d * 0.4f, d * 0.13f)
                nodes += Kit.words(it, uf, ink, b.centerX, ny + nf.height / 2f + uf.height * 0.6f, p.at + 0.45f, s.ground, p.rtl, Kit.Reveal.Fade, name = "sale-unit")
            }
            // The old price, else what it is; never «فقط» on its own: a price said as a complaint is no bargain.
            val top = cue.from?.let { Kit.digits(priceSize(cue, it), p.rtl) } ?: cue.label.orEmpty()
            if (top.isNotBlank()) {
                val tf = Kit.fit(fit, top, Kit.labelVoice(look, p.typo).copy(weight = 800), d * 0.12f, d * 0.5f, d * 0.16f)
                val ty = b.centerY - d * 0.17f
                nodes += Kit.words(top, tf, ink, b.centerX, ty, p.at + 0.3f, s.ground, p.rtl, Kit.Reveal.Fade, name = "sale-top")
                if (cue.from != null) nodes += Kit.line(b.centerX - tf.width * 0.6f, ty, b.centerX + tf.width * 0.6f, ty, ink, d * 0.014f, p.at + 0.6f, 0.25f, "strike")
            }
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.2f, SfxKind.Hit, 0.6f)), listOf(Craft.punch(p.at + 0.2f, 0.025f)))
        }

        /** Product, old price struck through, the new price big, and the saving as a chip. */
        private fun card(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, v: Float): Built {
            val b = Kit.box(cue, cue.width * 0.8f, minOf(cue.height, cue.width * 0.4f))
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.14f, "price-card").toMutableList()
            val pad = b.height * 0.14f
            // What the price is for, only as said: never a «قیمت ویژه» nobody spoke of.
            val label = cue.label ?: cue.text.ifBlank { null }
            val lf = label?.let { Kit.fit(fit, it, Kit.titleVoice(look, p.typo), b.height * 0.15f, b.width * 0.8f, b.height * 0.22f) }
            if (label != null && lf != null) {
                nodes += Kit.words(label, lf, ink, Kit.fromStart(Kit.startX(b, p.rtl, pad), lf.width, p.rtl), b.top + pad + lf.height / 2f, p.at + 0.1f, s.ground, p.rtl, name = "price-label")
            }
            val lh = lf?.height ?: 0f
            val nf = Kit.fit(fit, priceSize(cue, v), Kit.numberVoice(look, p.typo), b.height * if (lf == null) 0.5f else 0.36f, b.width * 0.8f, b.height * if (lf == null) 0.6f else 0.42f)
            // The price alone is the card: centred, as big as the card allows.
            val alone = lf == null && cue.from == null
            val ny = if (alone) b.centerY else b.bottom - pad - nf.height / 2f
            nodes += Kit.number(
                v, cue.from ?: v * 1.4f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size,
                Kit.tint(look, look.accent, s.ground), if (alone) b.centerX else Kit.fromStart(Kit.startX(b, p.rtl, pad), nf.width, p.rtl), ny, p.at + 0.3f, 1f, s.ground, p.rtl,
            )
            cue.from?.takeIf { it > v }?.let { old ->
                val of = Kit.fit(fit, priceSize(cue, old), Kit.numberVoice(look, p.typo), b.height * 0.16f, b.width * 0.3f, b.height * 0.2f)
                val ox = Kit.fromEnd(Kit.endX(b, p.rtl, pad), of.width, p.rtl)
                val oy = b.top + pad + lh + of.height
                nodes += Kit.words(Kit.digits(priceSize(cue, old), p.rtl), of, Kit.quiet(look, s.ground), ox, oy, p.at + 0.2f, s.ground, p.rtl, Kit.Reveal.Fade, name = "old-price")
                nodes += Kit.line(ox - of.width * 0.55f, oy, ox + of.width * 0.55f, oy, look.hot, of.type.size * 0.1f, p.at + 0.7f, 0.25f, "strike")
                val off = ((1f - v / old) * 100f).toInt()
                val chip = if (p.rtl) "${Kit.digits("$off", true)}٪ تخفیف" else "−$off%"
                val hot = Kit.surface(look, cue, Kit.Tone.Hot)
                val cf = Kit.fit(fit, chip, Kit.labelVoice(look, p.typo).copy(weight = 800), b.height * 0.15f, b.width * 0.3f, b.height * 0.2f)
                val cx = Kit.fromEnd(Kit.endX(b, p.rtl, pad), cf.width + pad, p.rtl)
                nodes += Kit.rect(cx, ny, cf.width + pad, cf.height + pad * 0.5f, hot.ground, (cf.height + pad * 0.5f) / 2f * look.roundness, "save-chip")
                nodes += Kit.words(chip, cf, Kit.inkOn(look, hot.ground), cx, ny, p.at + 0.9f, hot.ground, p.rtl, Kit.Reveal.Pop, name = "save")
            }
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.3f, SfxKind.Riser, 0.3f), Sfx(p.at + 1.3f, SfxKind.Pop, 0.5f)))
        }

        /** A 24-point sale burst in a 100 box. */
        private fun starburst(): String {
            val sb = StringBuilder()
            val n = SPIKES * 2
            for (i in 0 until n) {
                val a = i * 2 * PI / n - PI / 2
                val r = if (i % 2 == 0) 50.0 else 42.0
                sb.append(if (i == 0) "M" else "L").append(fmt(50 + r * cos(a))).append(' ').append(fmt(50 + r * sin(a)))
            }
            return sb.append('Z').toString()
        }

        private fun fmt(d: Double) = (kotlin.math.round(d * 100) / 100).toString()
    }

    /** A count as read: its sample text, the figure, its decimals and its unit (K / هزار). */
    private class Count(val first: String, val second: Float, val third: Int, val fourth: String)

    /** A swing tag pointing left (mirrored for right-to-left), filling a 100 box. */
    private const val TAG = "M0 44L20 0H94Q100 0 100 8V92Q100 100 94 100H20L0 56Z"
    private const val SPIKES = 12

    /** A price tag's layouts on a stage of its own (tag, burst, card): mostly the burst. */
    private val STAGE_LAYOUTS = listOf(0.5f, 3f, 0.3f)
    private const val MAX = 4
    private const val FIVE = 5f
    private const val DEFAULT_LIKES = 1200f
    private const val THOUSAND = 1000f
    private const val MILLION = 1_000_000f

    val all: List<Recipe> = listOf(StatStack, Ring, SocialProof, PriceTag)
}
