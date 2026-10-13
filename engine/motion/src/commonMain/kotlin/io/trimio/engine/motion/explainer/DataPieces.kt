package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.recipe.Kit

/**
 * The quantity primitives: a meter that drains or fills with its percentage, bars by tier, a row
 * of blocks, a ring and a field of dots. Every value rolls; nothing jumps.
 */
internal object DataPieces {
    /** A value as a share: ≤ 1 is already one, otherwise a percentage. */
    fun share(v: Double): Float = (if (v <= 1.0) v else v / 100.0).toFloat().coerceIn(0f, 1f)

    /** A label line, a rolling percentage at the reading end, and the bar under them. */
    val Meter = Piece { s, h ->
        val w = minOf(h.maxW, 780f * h.u)
        val label = h.say(s.text.ifEmpty { s.labels.firstOrNull().orEmpty() })
        val numSize = 42f * h.u
        val raw = s.unit.isNotEmpty() && s.unit != "%" && (s.value ?: 0.0) > 1.0
        val fw = h.figureWidth(if (raw) (s.value ?: 0.0) * 10 else 100.0, 0, if (raw) s.unit else "%", numSize)
        val lf = label.takeIf { it.isNotEmpty() }?.let { h.fit(it, Voices.label, 30f * h.u, w - fw - 40f * h.u, 1) }
        val top = maxOf(numSize * 1.1f, lf?.height ?: 0f)
        val barH = 24f * h.u
        val barY = top + 22f * h.u + barH / 2f
        val cap = s.caption.takeIf { it.isNotEmpty() }?.let { h.say(it) }
        val cf = cap?.let { h.fit(it, Voices.body, 32f * h.u, w, 2) }
        val ht = barY + barH / 2f + (cf?.let { it.height + 26f * h.u } ?: 0f)
        Laid(w, ht, listOf(Slot(0f, barY - barH / 2f, w, barH, barH / 2f, h.accent))) { t ->
            val initial = s.value ?: 1.0
            val frac = t.value(initial, from = 0.0) { share(it) * w }
            val shown = t.value(initial, from = 0.0) { if (raw) it.toFloat() else share(it) * 100f }
            val numX = if (h.rtl) 0f else w
            listOfNotNull(
                lf?.let { h.words(label, it, h.ink, if (h.rtl) w - it.width / 2f else it.width / 2f, top / 2f, h.ground, t.enter, Kit.Reveal.Fade, name = "meter-label") },
                h.counter(shown, 0, if (raw) s.unit else "%", numSize, h.ink, numX, top / 2f, h.ground, t.enter, anchorX = if (h.rtl) 0f else 1f),
                h.rect(w / 2f, barY, w, barH, barH / 2f, h.pal.track, "meter-track"),
                h.rect(if (h.rtl) w else 0f, barY, w, barH, barH / 2f, ColorAnim.of(h.accent), width = frac, anchorX = if (h.rtl) 1f else 0f, name = "meter-fill"),
                cf?.let { h.words(cap, it, h.ink, w / 2f, barY + barH / 2f + 26f * h.u + it.height / 2f, h.ground, t.enter + 0.3f, name = "meter-caption") },
            )
        }
    }

    /** Bars by tier, growing one by one; a pick lifts a badge over its bar. */
    val Bars = Piece { s, h ->
        val items = s.items.ifEmpty { listOf(Item(s.text, s.value ?: 1.0)) }
        val n = items.size
        val values = items.mapIndexed { i, it -> it.value ?: (i + 1).toDouble() }
        val maxV = (values + listOfNotNull(s.value)).maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6)
        val bw = 88f * h.u
        val gap = 38f * h.u
        val maxH = minOf(320f * h.u, h.maxH * 0.42f)
        val texts = items.map { h.say(it.label) }
        val fits = h.fitAll(texts, Voices.label, 26f * h.u, bw + gap - 8f * h.u, 2)
        val badge = h.say(s.caption.ifEmpty { "" })
        val badgeH = 50f * h.u
        val top = badgeH + 18f * h.u
        val w = n * bw + (n - 1) * gap + 8f * h.u
        val labelH = fits.maxOfOrNull { it.height } ?: 0f
        val ht = top + maxH + 16f * h.u + labelH
        val xs = h.row(n, bw, gap, w)
        val base = top + maxH
        val colours = List(n) { i -> h.pal.accent(h.accentIndex - (n - 1 - i)) }
        val slots = xs.mapIndexed { i, x -> (values[i] / maxV).toFloat().let { f -> Slot(x - bw / 2f, base - maxH * f, bw, maxH * f, 14f * h.u, colours[i]) } }
        Laid(w, ht, slots) { t ->
            val nodes = ArrayList<Node>()
            nodes += h.rect(w / 2f, base + 1.5f * h.u, w, 3f * h.u, 1.5f * h.u, h.pal.track)
            for (i in 0 until n) {
                val at = t.revealAt(i)
                val height = barHeight(t, i, values, maxV, maxH, at)
                nodes += ShapeNode(
                    ShapeSpec.Rect(bw.anim, height, (14f * h.u).anim), fill = Fill.Solid(ColorAnim.of(colours[i])),
                    transform = Transform(x = xs[i].anim, y = base.anim, anchorY = 1f, opacity = Anim.tween(0f, 1f, at, at + 0.2f)), name = "bar",
                )
                nodes += h.words(texts[i], fits[i], h.ink, xs[i], base + 16f * h.u + fits[i].height / 2f, h.ground, at + 0.1f, Kit.Reveal.Fade, name = "bar-label")
                val steps = t.pickSteps(i)
                if (steps.isNotEmpty()) {
                    val str = badge.ifEmpty { h.say(io.trimio.engine.motion.formatFixed(values[i].toFloat(), TextPieces.decimalsOf(values[i]), true)) }
                    val f = h.fit(str, Voices.label, 22f * h.u, bw + gap * 2f, 1)
                    val bwid = f.width + 30f * h.u
                    val y = base - maxH * (values[i] / maxV).toFloat() - 14f * h.u - badgeH / 2f
                    h.toggled(
                        listOf(h.pop(listOf(h.rect(xs[i], y, bwid, badgeH, badgeH / 2f, h.accentBody), h.words(str, f, h.onAccent, xs[i], y, h.accentBody, 0f, Kit.Reveal.None, name = "badge")), Box.around(xs[i], y, bwid, badgeH), steps.first().first, "bar-badge")),
                        steps, "bar-pick",
                    )?.let { nodes += it }
                }
                t.checkedAt(i)?.let { c -> nodes += h.tick(xs[i], base - 30f * h.u, 38f * h.u, c, ring = colours[i]) }
            }
            nodes
        }
    }

    /** A bar's height: grows at its reveal, then follows fills (item values, or the value for picks). */
    private fun barHeight(t: Track, i: Int, values: List<Double>, maxV: Double, maxH: Float, at: Float): Anim {
        val keys = ArrayList<Anim.Key>()
        keys += Anim.Key(at, 0f, Easing.Hold)
        keys += Anim.Key(at + 0.75f, (values[i] / maxV).toFloat() * maxH, Easing.ExpoOut)
        for (f in t.fills) {
            val v = f.items.getOrNull(i)?.value ?: f.to?.takeIf { f.picks.isEmpty() || i in f.picks } ?: continue
            val last = keys.last()
            val t0 = maxOf(f.at, last.t)
            keys += Anim.Key(t0, last.v, Easing.Hold)
            keys += Anim.Key(t0 + f.dur, (v / maxV).toFloat().coerceIn(0f, 1.15f) * maxH, Easing.Bezier(0.3f, 0f, 0.2f, 1f))
        }
        return Anim.keys(keys)
    }

    /** Colour of cell [i] of [n]: the track, the accent once done, the second accent while picked. */
    private fun cellColor(h: Hand, t: Track, i: Int, n: Int): ColorAnim {
        val events = ArrayList<Pair<Float, Color>>()
        t.doneAt(i, n)?.let { events += it to h.accent }
        for ((at, on) in t.pickSteps(i)) events += at to if (on) h.second else (if (t.doneAt(i, n)?.let { it <= at } == true) h.accent else h.pal.track)
        return Track.steps(h.pal.track, events.sortedBy { it.first })
    }

    /** Cells (squares or dots) in rows; fills colour the first ones, picks the chosen ones. */
    private fun cells(s: Spec, h: Hand, round: Boolean): Laid {
        val n = when {
            s.items.isNotEmpty() -> s.items.size
            (s.value ?: 0.0) > 1.0 && !round -> s.value!!.toInt()
            else -> if (round) 40 else 10
        }.coerceIn(1, 120)
        val d = if (round) 30f * h.u else 46f * h.u
        val gap = if (round) 14f * h.u else 10f * h.u
        val perRow = if (round) 10 else 12
        val flow = Flow.of(n, d, d, gap, gap, minOf(h.maxW, 900f * h.u), perRow, h.rtl)
        val label = h.say(s.text)
        val lf = label.takeIf { it.isNotEmpty() }?.let { h.fit(it, Voices.label, 30f * h.u, minOf(h.maxW, 720f * h.u), 2) }
        val ht = flow.h + (lf?.let { it.height + 24f * h.u } ?: 0f)
        val w = maxOf(flow.w, lf?.width ?: 0f)
        val ox = (w - flow.w) / 2f
        val slots = flow.centres.map { (x, y) -> Slot(ox + x - d / 2f, y - d / 2f, d, d, if (round) d / 2f else 9f * h.u, h.pal.track) }
        return Laid(w, ht, slots) { t ->
            val nodes = ArrayList<Node>()
            for (i in 0 until n) {
                val (x, y) = flow.centres[i]
                val at = t.revealAt(i)
                val c = cellColor(h, t, i, n)
                val shape = if (round) h.dot(ox + x, y, d, c, "cell") else h.rect(ox + x, y, d, d, 9f * h.u, c, name = "cell")
                nodes += h.pop(listOf(shape), Box.around(ox + x, y, d, d), at, "cell")
            }
            lf?.let { nodes += h.words(label, it, h.muted, w / 2f, flow.h + 24f * h.u + it.height / 2f, h.ground, t.enter + 0.2f, Kit.Reveal.Fade, name = "cells-label") }
            nodes
        }
    }

    val Blocks = Piece { s, h -> cells(s, h, round = false) }

    val Dots = Piece { s, h -> cells(s, h, round = true) }

    /** A ring that closes to its share, the percentage rolling in its middle. */
    val Ring = Piece { s, h ->
        val d = 280f * h.u
        val sw = 28f * h.u
        val label = h.say(s.text.ifEmpty { s.caption })
        val lf = label.takeIf { it.isNotEmpty() }?.let { h.fit(it, Voices.label, 32f * h.u, minOf(h.maxW, 640f * h.u), 2) }
        val w = maxOf(d + sw, lf?.width ?: 0f)
        val ht = d + sw + (lf?.let { it.height + 26f * h.u } ?: 0f)
        val cy = (d + sw) / 2f
        Laid(w, ht, listOf(Slot(w / 2f - d / 2f, cy - d / 2f, d, d, d / 2f, h.accent))) { t ->
            val initial = s.value ?: 0.0
            val arc = t.value(initial, from = 0.0) { share(it) }
            val shown = t.value(initial, from = 0.0) { share(it) * 100f }
            listOfNotNull(
                ShapeNode(ShapeSpec.Ellipse(d.anim, d.anim), stroke = Stroke(h.pal.track.fill(), sw), transform = Transform(x = (w / 2f).anim, y = cy.anim), name = "ring-track"),
                ShapeNode(
                    ShapeSpec.Ellipse(d.anim, d.anim), stroke = Stroke(h.accent.fill(), sw), trimEnd = arc,
                    transform = Transform(x = (w / 2f).anim, y = cy.anim, rotation = (-90f).anim, scaleY = (if (h.rtl) -1f else 1f).anim), name = "ring",
                ),
                h.counter(shown, 0, "%", 66f * h.u, h.ink, w / 2f, cy, h.ground, t.enter),
                lf?.let { h.words(label, it, h.ink, w / 2f, d + sw + 26f * h.u + it.height / 2f, h.ground, t.enter + 0.2f, Kit.Reveal.Fade, name = "ring-label") },
            )
        }
    }
}
