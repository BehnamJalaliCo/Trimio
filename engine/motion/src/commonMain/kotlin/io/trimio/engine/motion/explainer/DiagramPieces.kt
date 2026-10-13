package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.recipe.Fitted
import io.trimio.engine.motion.recipe.Kit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Relations: versus, before → after, a cycle, a flow of boxes and a map around a centre. */
internal object DiagramPieces {
    private fun labels(s: Spec, h: Hand) = s.labels.map { h.say(it) }.ifEmpty { listOf(h.say(s.text.ifEmpty { "…" })) }

    /** A pill node: card body, label, an accent overlay while picked and a tick when checked. */
    private fun node(h: Hand, t: Track, i: Int, text: String, f: Fitted, cx: Float, cy: Float, w: Float, ht: Float, r: Float): List<Node> {
        val pick = h.toggled(
            listOf(h.rect(cx, cy, w, ht, r, h.accentBody), h.words(text, f, h.onAccent, cx, cy, h.accentBody, 0f, Kit.Reveal.None, name = "node-on")),
            t.pickSteps(i), "node-pick",
        )
        val tick = t.checkedAt(i)?.let { h.tick(cx + h.dir * (w / 2f - 6f * h.u), cy - ht / 2f + 6f * h.u, 34f * h.u, it) }
        return listOfNotNull(h.card(cx, cy, w, ht, r), h.words(text, f, h.ink, cx, cy, h.card, t.revealAt(i) + 0.08f, Kit.Reveal.Fade, name = "node"), pick, tick)
    }

    /** An arrow from ([x0], [y0]) to ([x1], [y1]) in local px, drawn on from [at]. */
    private fun arrow(h: Hand, x0: Float, y0: Float, x1: Float, y1: Float, color: Color, at: Float, width: Float = 4f * h.u): List<Node> {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val ux = dx / len
        val uy = dy / len
        val head = 14f * h.u
        val shaft = h.stroke(listOf(x0 to y0, x1 to y1), ColorAnim.of(color), width, at, at + 0.35f, smooth = false, name = "arrow")
        val tip = listOf((x1 - ux * head - uy * head * 0.7f) to (y1 - uy * head + ux * head * 0.7f), x1 to y1, (x1 - ux * head + uy * head * 0.7f) to (y1 - uy * head - ux * head * 0.7f))
        return listOf(shaft, h.stroke(tip, ColorAnim.of(color), width, at + 0.25f, at + 0.45f, smooth = false, name = "arrow-head"))
    }

    /** Two sides facing each other; a pick crowns the winner and quiets the other. */
    val Versus = Piece { s, h ->
        val texts = labels(s, h).let { if (it.size < 2) it + listOf(h.say(s.caption.ifEmpty { "?" })) else it }.take(2)
        val vs = 76f * h.u
        val cw = minOf(380f * h.u, (minOf(h.maxW, 940f * h.u) - vs - 20f * h.u) / 2f)
        val fits = h.fitAll(texts, Voices.head, 40f * h.u, cw - 50f * h.u, 2)
        val visuals = s.items.map { it.visual }
        val pictured = visuals.any { it.isNotEmpty() }
        val ch = (fits.maxOf { it.height } + 70f * h.u + if (pictured) 80f * h.u else 0f).coerceAtLeast(190f * h.u)
        val w = cw * 2f + vs + 20f * h.u
        val xs = listOf(cw / 2f, w - cw / 2f).let { if (h.rtl) it.reversed() else it }
        Laid(w, ch, xs.map { Slot(it - cw / 2f, 0f, cw, ch, h.radius, h.card) }) { t ->
            val nodes = ArrayList<Node>()
            for (k in 0..1) {
                val x = xs[k]
                val at = t.revealAt(k)
                val others = t.pickSteps(1 - k)
                val quiet = others.map { (time, on) -> time to if (on) 0.45f else 1f }
                val ty = if (pictured) ch / 2f + 36f * h.u else ch / 2f
                val parts = listOfNotNull(
                    h.card(x, ch / 2f, cw, ch),
                    visuals.getOrNull(k)?.takeIf { it.isNotEmpty() }?.let { h.glyph(it, x, 30f * h.u + 32f * h.u, 56f * h.u, ColorAnim.of(h.accentOnCard), at + 0.1f) },
                    h.words(texts[k], fits[k], h.ink, x, ty, h.card, at + 0.1f, name = "versus"),
                    h.toggled(listOf(h.rect(x, ch / 2f, cw, ch, h.radius, null, stroke = Stroke(h.accent.fill(), 4f * h.u))), t.pickSteps(k), "versus-pick"),
                    t.checkedAt(k)?.let { h.tick(x + h.dir * (cw / 2f - 8f * h.u), 8f * h.u, 40f * h.u, it) },
                )
                val body = h.pop(parts, Box(x - cw / 2f, 0f, x + cw / 2f, ch), at, "versus-side")
                nodes += Group(listOf(body), Transform(anchorX = 0f, anchorY = 0f, opacity = fade(quiet)), name = "versus-quiet")
            }
            val vf = h.fit(if (h.rtl) "یا" else "vs", Voices.label, 26f * h.u, vs, 1)
            nodes += h.pop(listOf(h.dot(w / 2f, ch / 2f, vs, ColorAnim.of(h.ink)), h.words(if (h.rtl) "یا" else "vs", vf, h.ground, w / 2f, ch / 2f, h.ink, 0f, Kit.Reveal.None, name = "vs")),
                Box.around(w / 2f, ch / 2f, vs, vs), t.enter + 0.2f, "vs")
            nodes
        }
    }

    /** Opacity stepping between levels at times (1 before the first). */
    private fun fade(steps: List<Pair<Float, Float>>): Anim {
        if (steps.isEmpty()) return Anim.One
        val keys = ArrayList<Anim.Key>()
        keys += Anim.Key(steps.first().first - 0.001f, 1f, Easing.Hold)
        for ((t, v) in steps) {
            val start = maxOf(t, keys.last().t + 0.001f)
            keys += Anim.Key(start, keys.last().v, Easing.Hold)
            keys += Anim.Key(start + 0.35f, v, Easing.Standard)
        }
        return Anim.keys(keys)
    }

    /** Before → after: the old state quiet on the reading-start side, the new one in the accent. */
    val BeforeAfter = Piece { s, h ->
        val texts = labels(s, h).let { if (it.size < 2) listOf(h.say(s.caption.ifEmpty { "…" })) + it else it }.take(2)
        val tags = if (h.rtl) listOf("قبل", "بعد") else listOf("Before", "After")
        val gapW = 110f * h.u
        val cw = minOf(360f * h.u, (minOf(h.maxW, 920f * h.u) - gapW) / 2f)
        val fits = h.fitAll(texts, Voices.body, 34f * h.u, cw - 50f * h.u, 3)
        val tagF = h.fitAll(tags, Voices.label, 24f * h.u, cw, 1)
        val ch = fits.maxOf { it.height } + 120f * h.u
        val w = cw * 2f + gapW
        val xs = listOf(cw / 2f, w - cw / 2f).let { if (h.rtl) it.reversed() else it }
        Laid(w, ch, xs.mapIndexed { k, x -> Slot(x - cw / 2f, 0f, cw, ch, h.radius, if (k == 0) h.card else h.wash) }) { t ->
            val nodes = ArrayList<Node>()
            for (k in 0..1) {
                val x = xs[k]
                val at = t.revealAt(k)
                val after = k == 1
                val body = if (after) h.wash else h.card
                val tagFill = if (after) h.accentBody else h.pal.track
                val tagInk = if (after) h.onAccent else h.readable(h.muted, h.pal.track)
                val tw = tagF[k].width + 30f * h.u
                nodes += h.pop(
                    listOfNotNull(
                        h.card(x, ch / 2f, cw, ch, fill = ColorAnim.of(body), rim = if (after) h.accent.copy(alpha = 0.6f) else h.pal.rim),
                        h.rect(x, 36f * h.u, tw, 42f * h.u, 21f * h.u, tagFill),
                        h.words(tags[k], tagF[k], tagInk, x, 36f * h.u, tagFill, at + 0.1f, Kit.Reveal.None, name = "ba-tag"),
                        h.words(texts[k], fits[k], h.ink, x, 60f * h.u + (ch - 60f * h.u) / 2f, body, at + 0.15f, name = "ba-text"),
                        t.checkedAt(k)?.let { h.tick(x + h.dir * (cw / 2f - 8f * h.u), 8f * h.u, 40f * h.u, it) },
                    ),
                    Box(x - cw / 2f, 0f, x + cw / 2f, ch), at, "ba-side",
                )
            }
            val a = xs[0] + h.dir * (cw / 2f + 22f * h.u)
            val b = xs[1] - h.dir * (cw / 2f + 22f * h.u)
            nodes += arrow(h, a, ch / 2f, b, ch / 2f, h.accent, t.revealAt(1) - 0.2f, 5f * h.u)
            nodes
        }
    }

    /** Items around a circle with arcs leading from each to the next. */
    val Cycle = Piece { s, h ->
        val texts = labels(s, h)
        val n = texts.size
        val fits = h.fitAll(texts, Voices.label, 27f * h.u, 230f * h.u, 1)
        val pw = (fits.maxOf { it.width } + 44f * h.u).coerceAtLeast(120f * h.u)
        val ph = 62f * h.u
        val r = minOf(250f * h.u, (minOf(h.maxW, 900f * h.u) - pw) / 2f, (h.maxH * 0.8f - ph) / 2f).coerceAtLeast(120f * h.u)
        val w = r * 2f + pw
        val ht = r * 2f + ph
        val cx = w / 2f
        val cy = ht / 2f
        val angles = (0 until n).map { i -> -PI.toFloat() / 2f + h.dir * i * 2f * PI.toFloat() / n }
        val pos = angles.map { a -> (cx + r * cos(a)) to (cy + r * sin(a)) }
        Laid(w, ht, pos.map { (x, y) -> Slot(x - pw / 2f, y - ph / 2f, pw, ph, ph / 2f, h.card) }) { t ->
            val nodes = ArrayList<Node>()
            for (i in 0 until n) {
                val a0 = angles[i] + h.dir * 0.32f
                val a1 = angles[i] + h.dir * (2f * PI.toFloat() / n - 0.32f)
                if (n > 1) {
                    val pts = (0..20).map { k -> val a = a0 + (a1 - a0) * k / 20f; (cx + r * cos(a)) to (cy + r * sin(a)) }
                    val at = t.revealAt((i + 1) % n).let { if (i + 1 == n) maxOf(it, t.revealAt(i)) else it } - 0.15f
                    nodes += h.stroke(pts, ColorAnim.of(h.accent.copy(alpha = 0.75f)), 4f * h.u, at, at + 0.45f, name = "cycle-arc")
                }
            }
            for (i in 0 until n) {
                val (x, y) = pos[i]
                nodes += h.pop(node(h, t, i, texts[i], fits[i], x, y, pw, ph, ph / 2f), Box.around(x, y, pw, ph), t.revealAt(i), "cycle-node")
            }
            nodes
        }
    }

    /** Boxes joined by arrows, across when they fit, else down. */
    val Flow = Piece { s, h ->
        val texts = labels(s, h)
        val n = texts.size
        val maxW = minOf(h.maxW, 980f * h.u)
        val gap = 64f * h.u
        val fitsA = h.fitAll(texts, Voices.body, 30f * h.u, 240f * h.u, 2)
        val bwA = fitsA.maxOf { it.width } + 48f * h.u
        val across = n * bwA + (n - 1) * gap <= maxW
        val fits = if (across) fitsA else h.fitAll(texts, Voices.body, 32f * h.u, minOf(h.maxW, 640f * h.u) - 60f * h.u, 2)
        val bw = fits.maxOf { it.width } + 56f * h.u
        val bh = fits.maxOf { it.height } + 44f * h.u
        val w = if (across) n * bw + (n - 1) * gap else bw
        val ht = if (across) bh else n * bh + (n - 1) * gap
        val centres = (0 until n).map { i ->
            if (across) h.row(n, bw, gap, w)[i] to bh / 2f else w / 2f to (i * (bh + gap) + bh / 2f)
        }
        Laid(w, ht, centres.map { (x, y) -> Slot(x - bw / 2f, y - bh / 2f, bw, bh, 18f * h.u, h.card) }) { t ->
            val nodes = ArrayList<Node>()
            for (i in 1 until n) {
                val (x0, y0) = centres[i - 1]
                val (x1, y1) = centres[i]
                val at = t.revealAt(i) - 0.2f
                nodes += if (across) {
                    arrow(h, x0 + h.dir * (bw / 2f + 10f * h.u), y0, x1 - h.dir * (bw / 2f + 10f * h.u), y1, h.muted, at)
                } else {
                    arrow(h, x0, y0 + bh / 2f + 10f * h.u, x1, y1 - bh / 2f - 10f * h.u, h.muted, at)
                }
            }
            for (i in 0 until n) {
                val (x, y) = centres[i]
                nodes += h.pop(node(h, t, i, texts[i], fits[i], x, y, bw, bh, 18f * h.u), Box.around(x, y, bw, bh), t.revealAt(i), "flow-node")
            }
            nodes
        }
    }

    /** A centre with the ideas around it, joined by drawn lines. */
    val Map = Piece { s, h ->
        val texts = labels(s, h)
        val n = texts.size
        val centre = h.say(s.text.ifEmpty { s.caption }.ifEmpty { "•" })
        val cf = h.fit(centre, Voices.head, 38f * h.u, 300f * h.u, 2)
        val fits = h.fitAll(texts, Voices.label, 26f * h.u, 220f * h.u, 1)
        val pw = fits.maxOf { it.width } + 40f * h.u
        val ph = 58f * h.u
        val w = minOf(h.maxW, 920f * h.u)
        val ht = minOf(h.maxH * 0.9f, if (w > 800f * h.u) 560f * h.u else 520f * h.u)
        val cx = w / 2f
        val cy = ht / 2f
        val rx = w / 2f - pw / 2f
        val ry = ht / 2f - ph / 2f
        val angles = (0 until n).map { i -> -PI.toFloat() / 2f + h.dir * (i + 0.5f) * 2f * PI.toFloat() / n }
        val pos = angles.map { a -> (cx + rx * cos(a)) to (cy + ry * sin(a)) }
        val cw = cf.width + 60f * h.u
        val chh = cf.height + 40f * h.u
        val slots = listOf(Slot(cx - cw / 2f, cy - chh / 2f, cw, chh, chh / 2f, h.accentBody)) + pos.map { (x, y) -> Slot(x - pw / 2f, y - ph / 2f, pw, ph, ph / 2f, h.card) }
        Laid(w, ht, slots) { t ->
            val nodes = ArrayList<Node>()
            for (i in 0 until n) {
                val (x, y) = pos[i]
                val at = t.revealAt(i) - 0.1f
                nodes += h.stroke(listOf(cx to cy, (cx + (x - cx) * 0.92f) to (cy + (y - cy) * 0.92f)), ColorAnim.of(h.muted.copy(alpha = 0.6f)), 3f * h.u, at, at + 0.4f, smooth = false,
                    name = "map-line")
            }
            nodes += h.pop(
                listOf(h.rect(cx, cy, cw, chh, chh / 2f, ColorAnim.of(h.accentBody), shadow = h.shadow()), h.words(centre, cf, h.onAccent, cx, cy, h.accentBody, t.enter + 0.1f, name = "map-centre")),
                Box.around(cx, cy, cw, chh), t.enter, "map-centre",
            )
            for (i in 0 until n) {
                val (x, y) = pos[i]
                nodes += h.pop(node(h, t, i, texts[i], fits[i], x, y, pw, ph, ph / 2f), Box.around(x, y, pw, ph), t.revealAt(i), "map-node")
            }
            nodes
        }
    }
}
