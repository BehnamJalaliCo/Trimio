package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.recipe.Fitted
import io.trimio.engine.motion.recipe.Kit
import io.trimio.engine.motion.recipe.Move

/**
 * Ordered primitives: steps and a timeline (a line of stations, across or down), a grid of
 * tiles, a stack of layers built from the bottom, and a chat that fills bubble by bubble.
 */
internal object SequencePieces {
    private fun labels(s: Spec, h: Hand) = s.labels.map { h.say(it) }.ifEmpty { listOf(h.say(s.text.ifEmpty { "…" })) }

    /** Stations along a line: numbered circles (steps) or dots with dates (timeline). */
    private fun stations(s: Spec, h: Hand, numbered: Boolean): Laid {
        val texts = labels(s, h)
        val n = texts.size
        val maxW = minOf(h.maxW, 960f * h.u)
        val d = if (numbered) 76f * h.u else 28f * h.u
        val across = n * 170f * h.u <= maxW
        return if (across) across(s, h, texts, d, numbered, maxW) else down(h, texts, d, numbered)
    }

    private fun across(s: Spec, h: Hand, texts: List<String>, d: Float, numbered: Boolean, maxW: Float): Laid {
        val n = texts.size
        val cw = minOf(240f * h.u, (maxW - (n - 1) * 20f * h.u) / n)
        val fits = h.fitAll(texts, Voices.label, 27f * h.u, cw, 2)
        val values = s.items.map { it.value }
        val top = if (!numbered && values.any { it != null }) 44f * h.u else 0f
        val lineY = top + maxOf(d, 34f * h.u) / 2f
        val w = n * cw + (n - 1) * 20f * h.u
        val ht = lineY + d / 2f + 18f * h.u + (fits.maxOfOrNull { it.height } ?: 0f)
        val xs = h.row(n, cw, 20f * h.u, w)
        val slots = xs.map { x -> Slot(x - d / 2f, lineY - d / 2f, d, d, d / 2f, h.card) }
        return Laid(w, ht, slots) { t ->
            val nodes = ArrayList<Node>()
            val first = xs.first()
            val last = xs.last()
            nodes += h.rect((first + last) / 2f, lineY, kotlin.math.abs(last - first), 5f * h.u, 2.5f * h.u, h.pal.track)
            if (!numbered) nodes += progress(h, t, first, last, lineY, n)
            for (i in 1 until n) {
                val done = t.doneAt(i, n) ?: continue
                val a = xs[i - 1]
                val b = xs[i]
                val len = kotlin.math.abs(b - a)
                nodes += h.rect(a, lineY, len, 5f * h.u, 2.5f * h.u, ColorAnim.of(h.accent), width = Anim.tween(0f, len, done - 0.3f, done, io.trimio.engine.motion.Easing.ExpoOut),
                    anchorX = if (h.rtl) 1f else 0f)
            }
            for (i in 0 until n) {
                val at = t.revealAt(i)
                val x = xs[i]
                val label = h.words(texts[i], fits[i], h.ink, x, lineY + d / 2f + 18f * h.u + fits[i].height / 2f, h.ground, at + 0.1f, Kit.Reveal.Fade, name = "station")
                val value = values.getOrNull(i)?.takeIf { top > 0f }?.let { v ->
                    val str = h.say(io.trimio.engine.motion.formatFixed(v.toFloat(), TextPieces.decimalsOf(v), false))
                    h.words(str, h.fit(str, Voices.label, 26f * h.u, cw, 1), h.muted, x, top / 2f - 4f * h.u, h.ground, at + 0.15f, Kit.Reveal.Fade, name = "station-value")
                }
                nodes += h.pop(listOfNotNull(station(h, t, i, n, x, lineY, d, numbered), label, value), Box.around(x, lineY, d, d), at, "station")
            }
            nodes
        }
    }

    private fun down(h: Hand, texts: List<String>, d: Float, numbered: Boolean): Laid {
        val n = texts.size
        val tw = minOf(h.maxW, 760f * h.u) - d - 40f * h.u
        val fits = h.fitAll(texts, Voices.body, 32f * h.u, tw, 2)
        val rowH = maxOf(d + 30f * h.u, (fits.maxOfOrNull { it.height } ?: 0f) + 26f * h.u)
        val w = d + 30f * h.u + (fits.maxOfOrNull { it.width } ?: 0f)
        val ht = rowH * n
        val cx = if (h.rtl) w - d / 2f else d / 2f
        val slots = (0 until n).map { i -> Slot(cx - d / 2f, i * rowH + rowH / 2f - d / 2f, d, d, d / 2f, h.card) }
        return Laid(w, ht, slots) { t ->
            val nodes = ArrayList<Node>()
            nodes += h.rect(cx, ht / 2f, 5f * h.u, ht - rowH, 2.5f * h.u, h.pal.track)
            for (i in 0 until n) {
                val at = t.revealAt(i)
                val y = i * rowH + rowH / 2f
                val tx = if (h.rtl) w - d - 30f * h.u - fits[i].width / 2f else d + 30f * h.u + fits[i].width / 2f
                val label = h.words(texts[i], fits[i], h.ink, tx, y, h.ground, at + 0.1f, Kit.Reveal.Fade, align = TextAlign.Start, name = "station")
                val done = t.doneAt(i, n)
                val link = if (i > 0 && done != null) {
                    h.rect(cx, y - rowH, 5f * h.u, rowH, 2.5f * h.u, ColorAnim.of(h.accent), opacity = Anim.tween(0f, 1f, done - 0.3f, done))
                } else {
                    null
                }
                nodes += listOfNotNull(link)
                nodes += h.rise(listOf(station(h, t, i, n, cx, y, d, numbered), label), Box(0f, y - rowH / 2f, w, y + rowH / 2f), at, "station")
            }
            nodes
        }
    }

    /** One station: a numbered circle or a dot; done fills it with the accent, a pick rings it. */
    private fun station(h: Hand, t: Track, i: Int, n: Int, x: Float, y: Float, d: Float, numbered: Boolean): Node {
        val parts = ArrayList<Node>()
        parts += h.dot(x, y, d, ColorAnim.of(h.card), stroke = Stroke(h.ink.copy(alpha = 0.28f).fill(), 2.5f * h.u))
        if (numbered) {
            val str = h.say("${i + 1}")
            parts += h.words(str, h.fit(str, Voices.number, d * 0.42f, d, 1), h.ink, x, y, h.card, t.revealAt(i) + 0.1f, Kit.Reveal.Fade, name = "step-number")
        }
        h.toggled(listOf(h.dot(x, y, d + 14f * h.u, ColorAnim.of(h.wash)), h.dot(x, y, d, ColorAnim.of(h.card), stroke = Stroke(h.accent.fill(), 4f * h.u))), t.pickSteps(i),
            "station-pick")?.let { parts += it }
        t.doneAt(i, n)?.let { at ->
            parts += h.layer(listOfNotNull(h.dot(x, y, d, ColorAnim.of(h.accentBody)), if (numbered) h.check(x, y, d * 0.5f, at + 0.05f) else null), at, "station-done")
        }
        return io.trimio.engine.motion.Group(parts, name = "station-body")
    }

    /** The timeline's line filling with the accent as its value moves (0..1 of the way). */
    private fun progress(h: Hand, t: Track, first: Float, last: Float, y: Float, n: Int): List<Node> {
        if (t.fills.none { it.to != null }) return emptyList()
        val len = kotlin.math.abs(last - first)
        val width = t.value(0.0) { v -> (if (v <= 1.0) v else v / (n - 1).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) * len }
        return listOf(h.rect(first, y, len, 6f * h.u, 3f * h.u, ColorAnim.of(h.accent), width = width, anchorX = if (h.rtl) 1f else 0f))
    }

    val Steps = Piece { s, h -> stations(s, h, numbered = true) }

    val Timeline = Piece { s, h -> stations(s, h, numbered = false) }

    /** Tiles in a grid, each with a picture when it has one. */
    val Grid = Piece { s, h ->
        val texts = labels(s, h)
        val n = texts.size
        val wide = h.maxW > h.maxH * 1.4f
        val cols = when {
            n <= 3 -> n
            n == 4 -> 2
            n <= 6 -> 3
            else -> if (wide) 4 else 3
        }
        val gap = 20f * h.u
        val tw = minOf(250f * h.u, (minOf(h.maxW, 940f * h.u) - (cols - 1) * gap) / cols)
        val fits = h.fitAll(texts, Voices.label, 28f * h.u, tw - 30f * h.u, 2)
        val visuals = s.items.map { it.visual }
        val pictured = visuals.any { it.isNotEmpty() }
        val th = (if (pictured) 96f * h.u else 40f * h.u) + (fits.maxOfOrNull { it.height } ?: 0f) + 30f * h.u
        val flow = Flow.of(n, tw, th, gap, gap, cols * tw + (cols - 1) * gap + 1f, cols, h.rtl)
        val slots = flow.centres.map { (x, y) -> Slot(x - tw / 2f, y - th / 2f, tw, th, 20f * h.u, h.card) }
        Laid(flow.w, flow.h, slots) { t ->
            (0 until n).map { i ->
                val (cx, cy) = flow.centres[i]
                val at = t.revealAt(i)
                val ly = if (pictured) cy + th / 2f - 18f * h.u - fits[i].height / 2f else cy
                val glyph = visuals.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { h.glyph(it, cx, cy - th / 2f + 52f * h.u, 50f * h.u, ColorAnim.of(h.accentOnCard), at + 0.1f) }
                val pick = h.toggled(listOf(h.rect(cx, cy, tw, th, 20f * h.u, null, stroke = Stroke(h.accent.fill(), 3.5f * h.u))), t.pickSteps(i), "grid-pick")
                val tick = t.checkedAt(i)?.let { h.tick(cx + h.dir * (tw / 2f - 10f * h.u), cy - th / 2f + 10f * h.u, 38f * h.u, it) }
                h.pop(listOfNotNull(h.card(cx, cy, tw, th, 20f * h.u), glyph, h.words(texts[i], fits[i], h.ink, cx, ly, h.card, at + 0.1f, Kit.Reveal.Fade, name = "grid"), pick, tick),
                    Box.around(cx, cy, tw, th), at, "grid")
            }
        }
    }

    /** Layers stacked from the bottom up; each new one drops onto the pile. */
    val Stack = Piece { s, h ->
        val texts = labels(s, h)
        val n = texts.size
        val w = minOf(h.maxW, 640f * h.u)
        val fits = h.fitAll(texts, Voices.body, 32f * h.u, w - 80f * h.u, 1)
        val sh = 92f * h.u
        val gap = 12f * h.u
        val ht = n * sh + (n - 1) * gap
        val ys = (0 until n).map { i -> ht - sh / 2f - i * (sh + gap) }
        val slots = ys.map { y -> Slot(0f, y - sh / 2f, w, sh, 18f * h.u, h.card) }
        Laid(w, ht, slots) { t ->
            (0 until n).map { i ->
                val y = ys[i]
                val at = t.revealAt(i)
                val tone = androidx.compose.ui.graphics.lerp(h.card, h.accent, 0.05f + 0.07f * i / n.coerceAtLeast(1))
                val pick = h.toggled(
                    listOf(h.rect(w / 2f, y, w, sh, 18f * h.u, h.accentBody), h.words(texts[i], fits[i], h.onAccent, w / 2f, y, h.accentBody, 0f, Kit.Reveal.None, name = "slab-on")),
                    t.pickSteps(i), "slab-pick",
                )
                val tick = t.checkedAt(i)?.let { h.tick(if (h.rtl) 30f * h.u else w - 30f * h.u, y, 38f * h.u, it, ring = tone) }
                Kit.item(
                    Move.Drop, Box(0f, y - sh / 2f, w, y + sh / 2f), at, h.dir,
                    listOfNotNull(h.card(w / 2f, y, w, sh, 18f * h.u, ColorAnim.of(tone)), h.words(texts[i], fits[i], h.ink, w / 2f, y, tone, at + 0.1f, Kit.Reveal.Fade, name = "slab"), pick, tick),
                    "slab",
                )
            }
        }
    }

    /** Bubbles taking turns: the first voice on the reading-start side, the reply in the accent. */
    val Chat = Piece { s, h ->
        val texts = labels(s, h)
        val maxB = minOf(h.maxW * 0.78f, 620f * h.u)
        val padX = 28f * h.u
        val padY = 20f * h.u
        val fits: List<Fitted> = texts.map { h.fit(it, Voices.body, 30f * h.u, maxB - padX * 2f, 3) }
        val w = minOf(h.maxW, 820f * h.u)
        val gap = 16f * h.u
        val heights = fits.map { it.height + padY * 2f }
        val ht = heights.sum() + gap * (texts.size - 1)
        var y = 0f
        val boxes = fits.mapIndexed { i, f ->
            val bw = f.width + padX * 2f
            val startSide = i % 2 == 0
            val left = if (startSide == !h.rtl) 0f else w - bw
            Box(left, y, left + bw, y + heights[i]).also { y += heights[i] + gap }
        }
        val slots = boxes.mapIndexed { i, b -> Slot(b.left, b.top, b.width, b.height, 26f * h.u, if (i % 2 == 0) h.card else h.accentBody) }
        Laid(w, ht, slots) { t ->
            boxes.mapIndexed { i, b ->
                val mine = i % 2 == 1
                val fill = if (mine) h.accentBody else h.card
                val ink = if (mine) h.onAccent else h.ink
                val pick = h.toggled(listOf(h.rect(b.centerX, b.centerY, b.width + 10f * h.u, b.height + 10f * h.u, 30f * h.u, null, stroke = Stroke(h.accent.fill(), 3f * h.u))), t.pickSteps(i),
                    "bubble-pick")
                val tick = t.checkedAt(i)?.let { h.tick(if (b.left < w / 2f) b.right - 8f * h.u else b.left + 8f * h.u, b.bottom - 8f * h.u, 34f * h.u, it) }
                h.pop(
                    listOfNotNull(
                        if (mine) h.rect(b.centerX, b.centerY, b.width, b.height, 26f * h.u, ColorAnim.of(fill), shadow = h.shadow()) else h.card(b.centerX, b.centerY, b.width, b.height, 26f * h.u),
                        h.words(texts[i], fits[i], ink, b.centerX, b.centerY, fill, t.revealAt(i) + 0.08f, Kit.Reveal.Fade, name = "bubble"), pick, tick,
                    ),
                    b, t.revealAt(i), "bubble",
                )
            }
        }
    }
}
