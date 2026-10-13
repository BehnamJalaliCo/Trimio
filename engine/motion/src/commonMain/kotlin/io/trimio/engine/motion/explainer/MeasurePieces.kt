package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.recipe.Kit

/** Primitives that measure by shape: a clock, a balance, a ladder with a climbing marker, a funnel. */
internal object MeasurePieces {
    /** A clock face beside its words: time sweeps an accent wedge as the value fills. */
    val Clock = Piece { s, h ->
        val d = 170f * h.u
        val label = h.say(s.text.ifEmpty { s.caption })
        val lf = label.takeIf { it.isNotEmpty() }?.let { h.fit(it, Voices.head, 46f * h.u, minOf(h.maxW, 860f * h.u) - d - 40f * h.u, 2) }
        val gap = 36f * h.u
        val w = d + (lf?.let { it.width + gap } ?: 0f)
        val ht = maxOf(d, lf?.height ?: 0f)
        val cx = if (h.rtl) w - d / 2f else d / 2f
        val cy = ht / 2f
        Laid(w, ht, listOf(Slot(cx - d / 2f, cy - d / 2f, d, d, d / 2f, h.card))) { t ->
            val sweep = t.value(s.value ?: 0.0) { DataPieces.share(it) }
            val minute = if (t.fills.any { it.to != null } || s.value != null) {
                t.value(s.value ?: 0.0) { DataPieces.share(it) * 360f }
            } else {
                Anim.tween(0f, 300f, t.enter, t.leave + 0.5f, Easing.Linear)
            }
            val hour = Anim.tween(60f, 90f, t.enter, t.leave + 0.5f, Easing.Linear)
            val face = listOf(
                h.dot(cx, cy, d, ColorAnim.of(h.card), stroke = Stroke(h.ink.fill(), 6f * h.u)),
                ShapeNode(
                    ShapeSpec.Ellipse((d * 0.42f).anim, (d * 0.42f).anim), stroke = Stroke(h.accent.copy(alpha = 0.55f).fill(), d * 0.42f), trimEnd = sweep,
                    transform = Transform(x = cx.anim, y = cy.anim, rotation = (-90f).anim), name = "clock-sweep",
                ),
            ) + (0 until 12).map { k ->
                val a = k * 30f
                val long = k % 3 == 0
                h.rect(cx, cy, 4f * h.u, if (long) 16f * h.u else 8f * h.u, 2f * h.u, h.ink.copy(alpha = if (long) 0.7f else 0.35f), "clock-tick").let { r ->
                    Group(listOf(r.withY(-d / 2f + 18f * h.u)), Transform(x = cx.anim, y = cy.anim, rotation = a.anim, anchorX = 0f, anchorY = 0f), name = "clock-mark")
                }
            } + listOf(
                hand(h, cx, cy, d * 0.24f, 7f * h.u, hour),
                hand(h, cx, cy, d * 0.36f, 5f * h.u, minute),
                h.dot(cx, cy, 14f * h.u, ColorAnim.of(h.accentBody)),
            )
            val faceGroup = h.pop(face, Box.around(cx, cy, d, d), t.enter, "clock")
            listOfNotNull(faceGroup, lf?.let { h.words(label, it, h.ink, if (h.rtl) w - d - gap - it.width / 2f else d + gap + it.width / 2f, cy, h.ground, t.enter + 0.15f, name = "clock-label") })
        }
    }

    private fun ShapeNode.withY(dy: Float) = ShapeNode(shape, fill, stroke, trimStart, trimEnd, shadow, transform.copy(x = 0f.anim, y = dy.anim), start, end, mask, blend, name)

    private fun hand(h: Hand, cx: Float, cy: Float, len: Float, width: Float, angle: Anim) = ShapeNode(
        ShapeSpec.Rect(width.anim, len.anim, (width / 2f).anim), fill = Fill.Solid(ColorAnim.of(h.ink)),
        transform = Transform(x = cx.anim, y = cy.anim, anchorY = 0.92f, rotation = angle), name = "clock-hand",
    )

    /** A balance: the beam tips toward the heavier side as the value fills (−1…1, + = the first item). */
    val Scale = Piece { s, h ->
        val texts = s.labels.map { h.say(it) }.ifEmpty { listOf(h.say(s.text), h.say(s.caption)) }.let { if (it.size < 2) it + listOf("") else it }.take(2)
        val beam = minOf(h.maxW, 600f * h.u)
        val pan = 190f * h.u
        val fits = h.fitAll(texts.map { it.ifEmpty { " " } }, Voices.label, 28f * h.u, pan, 2)
        val arm = beam / 2f - pan / 2f + 20f * h.u
        val w = beam + 40f * h.u
        val beamY = 80f * h.u
        val panY = beamY + 120f * h.u
        val lh = fits.maxOf { it.height }
        val ht = panY + 30f * h.u + 24f * h.u + lh + 70f * h.u
        val cx = w / 2f
        val sides = listOf(cx + h.dir * -arm, cx + h.dir * arm)
        Laid(w, ht, sides.map { x -> Slot(x - pan / 2f, panY - 12f * h.u, pan, 24f * h.u, 12f * h.u, h.accent) }) { t ->
            val sign = if (h.rtl) 1f else -1f
            val angle = t.value(s.value ?: 0.0) { v -> v.toFloat().coerceIn(-1f, 1f) * 11f * sign }
            val drop = t.value(s.value ?: 0.0) { v -> v.toFloat().coerceIn(-1f, 1f) * 11f * (kotlin.math.PI.toFloat() / 180f) * arm }
            val nodes = ArrayList<Node>()
            nodes += ShapeNode(
                ShapeSpec.Path("M60 0L120 100H0Z", 120f, 120f * h.u), fill = Fill.Solid(ColorAnim.of(h.pal.track)),
                transform = Transform(x = (cx - 60f * h.u).anim, y = (ht - 120f * h.u).anim, anchorX = 0f, anchorY = 0f), name = "scale-foot",
            )
            nodes += h.rect(cx, (beamY + ht - 40f * h.u) / 2f, 8f * h.u, ht - 40f * h.u - beamY, 4f * h.u, h.pal.track.copy(alpha = 1f), "scale-post")
            nodes += ShapeNode(
                ShapeSpec.Rect(beam.anim, (10f * h.u).anim, (5f * h.u).anim), fill = Fill.Solid(ColorAnim.of(h.ink)),
                transform = Transform(x = cx.anim, y = beamY.anim, rotation = angle), name = "scale-beam",
            )
            nodes += h.dot(cx, beamY, 26f * h.u, ColorAnim.of(h.accentBody))
            for ((k, x) in sides.withIndex()) {
                val dy = if (k == 0) drop else drop * (-1f).anim
                val at = t.revealAt(k)
                val pick = t.pickColor(k, h.ink, h.accent)
                val parts = listOfNotNull(
                    h.rect(x, (beamY + panY) / 2f, 3f * h.u, panY - beamY, 1.5f * h.u, h.ink.copy(alpha = 0.45f), "scale-string"),
                    h.rect(x, panY, pan, 22f * h.u, 11f * h.u, pick, name = "scale-pan"),
                    texts[k].takeIf { it.isNotBlank() }?.let { h.words(it, fits[k], h.ink, x, panY + 30f * h.u + fits[k].height / 2f, h.ground, at + 0.15f, Kit.Reveal.Fade, name = "scale-label") },
                )
                nodes += Group(listOf(h.pop(parts, Box.around(x, panY, pan, 60f * h.u), at, "scale-side")), Transform(anchorX = 0f, anchorY = 0f, y = dy), name = "scale-hang")
            }
            nodes
        }
    }

    /** Rungs from the bottom up; a marker climbs to the rung the value names. */
    val Ladder = Piece { s, h ->
        val texts = s.labels.map { h.say(it) }.ifEmpty { listOf(h.say(s.text)) }
        val n = texts.size
        val rw = minOf(h.maxW - 90f * h.u, 560f * h.u)
        val rh = 72f * h.u
        val gap = 16f * h.u
        val fits = h.fitAll(texts, Voices.body, 30f * h.u, rw - 60f * h.u, 1)
        val marker = 80f * h.u
        val w = rw + marker
        val ht = n * rh + (n - 1) * gap
        val rx = if (h.rtl) rw / 2f else marker + rw / 2f
        val ys = (0 until n).map { i -> ht - rh / 2f - i * (rh + gap) }
        Laid(w, ht, ys.map { y -> Slot(rx - rw / 2f, y - rh / 2f, rw, rh, rh / 2f, h.card) }) { t ->
            val nodes = ArrayList<Node>()
            for (i in 0 until n) {
                val y = ys[i]
                val at = t.revealAt(i)
                val pick = h.toggled(listOf(h.rect(rx, y, rw, rh, rh / 2f, h.accentBody), h.words(texts[i], fits[i], h.onAccent, rx, y, h.accentBody, 0f, Kit.Reveal.None, name = "rung-on")),
                    t.pickSteps(i), "rung-pick")
                nodes += h.rise(listOfNotNull(h.card(rx, y, rw, rh, rh / 2f), h.words(texts[i], fits[i], h.ink, rx, y, h.card, at + 0.1f, Kit.Reveal.Fade, name = "rung"), pick), Box(rx - rw / 2f,
                    y - rh / 2f, rx + rw / 2f, y + rh / 2f), at, "rung")
            }
            if (t.fills.any { it.to != null } || s.value != null) {
                val idx = { v: Double -> (if (v <= 1.0) v * (n - 1) else v - 1.0).toFloat().coerceIn(0f, (n - 1).toFloat()) }
                val y = t.value(s.value ?: 0.0) { v -> ht - rh / 2f - idx(v) * (rh + gap) }
                val mx = if (h.rtl) w - marker / 2f else marker / 2f
                val appear = (t.fills.firstOrNull()?.at ?: t.enter) - 0.1f
                val arrow = ShapeNode(
                    ShapeSpec.Path(if (h.rtl) "M18 4L6 12l12 8z" else "M6 4l12 8-12 8z", 24f, 40f * h.u), fill = Fill.Solid(ColorAnim.of(h.accent)),
                    transform = Transform(x = mx.anim, y = 0f.anim), name = "ladder-arrow",
                )
                nodes += Group(listOf(arrow), Transform(anchorX = 0f, anchorY = 0f, y = y, opacity = Anim.tween(0f, 1f, appear, appear + 0.3f)), start = appear, name = "ladder-marker")
            }
            nodes
        }
    }

    /** Layers narrowing down; a pick fills its layer with the accent. */
    val Funnel = Piece { s, h ->
        val texts = s.labels.map { h.say(it) }.ifEmpty { listOf(h.say(s.text)) }
        val n = texts.size
        val top = minOf(h.maxW, 760f * h.u)
        val bottom = top * 0.42f
        val lh = 84f * h.u
        val gap = 10f * h.u
        val widths = (0..n).map { i -> top + (bottom - top) * i / n.coerceAtLeast(1) }
        val fits = h.fitAll(texts, Voices.body, 30f * h.u, widths.last() - 30f * h.u + (top - bottom) * 0.3f, 1)
        val ht = n * lh + (n - 1) * gap
        Laid(top, ht, (0 until n).map { i -> Slot((top - widths[i + 1]) / 2f, i * (lh + gap), widths[i + 1], lh, 10f * h.u, h.card) }) { t ->
            (0 until n).map { i ->
                val y0 = i * (lh + gap)
                val a = widths[i]
                val b = widths[i + 1]
                val at = t.revealAt(i)
                val path = trapezoid(top, a, b, lh)
                fun layer(fill: ColorAnim, stroke: Stroke?) = ShapeNode(
                    ShapeSpec.Path(path, top, top), fill = Fill.Solid(fill), stroke = stroke,
                    transform = Transform(x = 0f.anim, y = y0.anim, anchorX = 0f, anchorY = 0f), name = "funnel-layer",
                )
                val pick = h.toggled(listOf(layer(ColorAnim.of(h.accentBody), null), h.words(texts[i], fits[i], h.onAccent, top / 2f, y0 + lh / 2f, h.accentBody, 0f, Kit.Reveal.None,
                    name = "funnel-on")), t.pickSteps(i), "funnel-pick")
                h.rise(
                    listOfNotNull(layer(ColorAnim.of(h.card), Stroke(h.pal.rim.fill(), 2f * h.u)), h.words(texts[i], fits[i], h.ink, top / 2f, y0 + lh / 2f, h.card, at + 0.1f, Kit.Reveal.Fade,
                        name = "funnel"), pick),
                    Box(0f, y0, top, y0 + lh), at, "funnel",
                )
            }
        }
    }

    /** A trapezoid [a] wide at the top and [b] at the bottom, centred in [w], [hgt] tall (px path). */
    private fun trapezoid(w: Float, a: Float, b: Float, hgt: Float): String {
        val r = 10f
        val x0 = (w - a) / 2f
        val x1 = (w + a) / 2f
        val x2 = (w + b) / 2f
        val x3 = (w - b) / 2f
        return "M${x0 + r} 0H${x1 - r}Q$x1 0 ${x1 - 2f} ${r}L${x2 + 1f} ${hgt - r}Q$x2 $hgt ${x2 - r} ${hgt}H${x3 + r}Q$x3 $hgt ${x3 - 1f} ${hgt - r}L${x0 + 2f} ${r}Q$x0 0 ${x0 + r} 0Z"
    }
}
