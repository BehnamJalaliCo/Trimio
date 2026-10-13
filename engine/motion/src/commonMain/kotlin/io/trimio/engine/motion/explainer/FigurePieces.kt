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
import io.trimio.engine.motion.recipe.Kit

/** Figures: branching paths to a goal, a hand-drawn tangle, a thinking indicator and a profile card. */
internal object FigurePieces {

    /** Paths from several starts converging on a goal chip; a pick lights the best path. */
    val Branches = Piece { s, h ->
        val texts = s.labels.map { h.say(it) }
        val n = maxOf(texts.size, if (s.value != null && s.value > 1) s.value.toInt().coerceAtMost(6) else 3)
        val goal = h.say(s.text.ifEmpty { s.caption }.ifEmpty { "•" })
        val gf = h.fit(goal, Voices.body, 30f * h.u, 280f * h.u, 1)
        val gw = gf.width + 46f * h.u
        val gh = 64f * h.u
        val w = minOf(h.maxW, 760f * h.u)
        val spread = 108f * h.u
        val lf = if (texts.any { it.isNotEmpty() }) h.fitAll(texts.map { it.ifEmpty { " " } }, Voices.label, 24f * h.u, w * 0.45f, 1) else emptyList()
        val labelRoom = if (lf.isEmpty()) 0f else 40f * h.u
        val ht = (n - 1) * spread + 40f * h.u + labelRoom
        val dot = 30f * h.u
        val sx = if (h.rtl) w - dot / 2f - 4f * h.u else dot / 2f + 4f * h.u
        val gx = if (h.rtl) gw / 2f else w - gw / 2f
        val gy = labelRoom + (ht - labelRoom) / 2f
        val ys = (0 until n).map { i -> labelRoom + 20f * h.u + i * spread }
        val slots = ys.map { y -> Slot(minOf(sx, gx), minOf(y, gy) - 3f * h.u, kotlin.math.abs(gx - sx), kotlin.math.abs(gy - y) + 6f * h.u, 3f * h.u, h.muted) } +
            Slot(gx - gw / 2f, gy - gh / 2f, gw, gh, gh / 2f, h.card)
        Laid(w, ht, slots) { t ->
            val nodes = ArrayList<Node>()
            val endX = gx + h.dir * (gw / 2f + 6f * h.u)
            for (i in 0 until n) {
                val y = ys[i]
                val at = t.revealAt(i)
                val pts = (0..28).map { k ->
                    val f = k / 28f
                    val e = f * f * (3f - 2f * f)
                    (sx + (endX - sx) * f) to (y + (gy - y) * e)
                }
                val picked = (t.pickSteps(i).map { it.first to true } + listOfNotNull(t.checkedAt(i)?.let { it to true })).minByOrNull { it.first }?.first
                val base = h.muted.copy(alpha = 0.55f)
                nodes += h.stroke(pts, ColorAnim.of(base), 3.5f * h.u, at, at + 0.8f, name = "branch")
                picked?.let { p -> nodes += h.stroke(pts, ColorAnim.of(h.accent), 6f * h.u, p, p + 0.7f, name = "branch-best") }
                nodes += h.pop(listOf(h.dot(sx, y, dot, ColorAnim.of(h.ground), stroke = Stroke(h.muted.fill(), 3f * h.u))), Box.around(sx, y, dot, dot), at, "branch-start")
                picked?.let { p -> nodes += h.tick(sx, y, dot + 6f * h.u, p) }
                lf.getOrNull(i)?.takeIf { texts[i].isNotEmpty() }?.let { f ->
                    val lx = if (h.rtl) sx + dot / 2f - f.width / 2f else sx - dot / 2f + f.width / 2f
                    val color = picked?.let { ColorAnim.tween(h.muted, h.accentInk, it, it + Track.TURN) } ?: ColorAnim.of(h.muted)
                    nodes += h.words(texts[i], f, color, lx, y - 34f * h.u, h.ground, at + 0.2f, Kit.Reveal.Fade, name = "branch-label")
                }
            }
            nodes += h.pop(
                listOf(h.card(gx, gy, gw, gh, gh / 2f), h.words(goal, gf, h.ink, gx, gy, h.card, t.enter + 0.1f, Kit.Reveal.Fade, name = "goal")),
                Box.around(gx, gy, gw, gh), t.enter, "goal",
            )
            nodes
        }
    }

    /** A tangled knot drawn on by hand; it takes the accent when highlighted. */
    val Tangle = Piece { s, h ->
        val w = minOf(h.maxW, 470f * h.u)
        val kh = 210f * h.u
        val label = h.say(s.text.ifEmpty { s.caption })
        val lf = label.takeIf { it.isNotEmpty() }?.let { h.fit(it, Voices.head, 42f * h.u, minOf(h.maxW, 760f * h.u), 2) }
        val ww = maxOf(w, lf?.width ?: 0f)
        val ht = kh + (lf?.let { it.height + 24f * h.u } ?: 0f)
        val ox = (ww - w) / 2f
        Laid(ww, ht, listOf(Slot(ox + w * 0.1f, kh * 0.2f, w * 0.8f, kh * 0.6f, kh * 0.3f, h.ink))) { t ->
            val pts = Scribbles.tangle(h.seed, w, kh).map { (x, y) -> (ox + if (h.rtl) w - x else x) to y }
            val marks = (t.marks + t.picks.map { it.first }).sorted()
            val color = Track.steps(h.ink, marks.take(1).map { it to h.accentInk })
            val draw = maxOf(1.3f, minOf(2.2f, (t.leave - t.enter) * 0.4f))
            listOfNotNull(
                h.stroke(pts, color, 4.6f * h.u, t.enter, t.enter + draw, name = "tangle"),
                h.stroke(pts.map { (x, y) -> x + 2.2f * h.u to y + 1.4f * h.u }, ColorAnim.of(h.ink.copy(alpha = 0.28f)), 2f * h.u, t.enter + 0.08f, t.enter + draw + 0.08f, name = "tangle-echo"),
                lf?.let { h.words(label, it, h.ink, ww / 2f, kh + 24f * h.u + it.height / 2f, h.ground, t.enter + draw * 0.6f, name = "tangle-label") },
            )
        }
    }

    /** Three dots taking turns in a soft pill (it is thinking), with quiet words beside it. */
    val Thinking = Piece { s, h ->
        val pw = 128f * h.u
        val ph = 62f * h.u
        val label = h.say(s.text.ifEmpty { s.caption })
        val lf = label.takeIf { it.isNotEmpty() }?.let { h.fit(it, Voices.kicker, 32f * h.u, minOf(h.maxW, 800f * h.u) - pw - 30f * h.u, 2) }
        val gap = 28f * h.u
        val w = pw + (lf?.let { it.width + gap } ?: 0f)
        val ht = maxOf(ph, lf?.height ?: 0f)
        val px = if (h.rtl) w - pw / 2f else pw / 2f
        val cy = ht / 2f
        Laid(w, ht, listOf(Slot(px - pw / 2f, cy - ph / 2f, pw, ph, ph / 2f, h.card))) { t ->
            val dots = (0 until 3).map { k ->
                val x = px + (k - 1) * 28f * h.u * h.dir
                Group(listOf(h.dot(x, cy, 15f * h.u, ColorAnim.of(h.accent))), Transform(anchorX = 0f, anchorY = 0f, y = bounce(t.enter + 0.2f + k * 0.16f, t.leave + 0.6f, 9f * h.u)), name = "thinking-dot")
            }
            listOfNotNull(h.pop(listOf(h.card(px, cy, pw, ph, ph / 2f)) + dots, Box.around(px, cy, pw, ph), t.enter, "thinking")) +
                listOfNotNull(lf?.let { h.words(label, it, h.muted, if (h.rtl) w - pw - gap - it.width / 2f else pw + gap + it.width / 2f, cy, h.ground, t.enter + 0.2f, Kit.Reveal.Fade, name = "thinking-label") })
        }
    }

    /** A soft hop every [PERIOD] seconds between [from] and [to]. */
    private fun bounce(from: Float, to: Float, lift: Float): Anim {
        val keys = ArrayList<Anim.Key>()
        keys += Anim.Key(from, 0f, Easing.Hold)
        var t = from
        while (t + PERIOD <= to && keys.size < MAX_KEYS) {
            keys += Anim.Key(t + PERIOD * 0.22f, -lift, Easing.SineInOut)
            keys += Anim.Key(t + PERIOD * 0.44f, 0f, Easing.SineInOut)
            keys += Anim.Key(t + PERIOD, 0f, Easing.Hold)
            t += PERIOD
        }
        return Anim.keys(keys)
    }

    private const val PERIOD = 1.05f
    private const val MAX_KEYS = 600

    /** A person card: an avatar, a name and tags (the role) that arrive one by one. */
    val Profile = Piece { s, h ->
        val name = h.say(s.text.ifEmpty { s.caption }.ifEmpty { s.labels.firstOrNull().orEmpty() })
        val tags = (if (s.items.isNotEmpty()) s.labels else listOfNotNull(s.caption.takeIf { it.isNotEmpty() && s.text.isNotEmpty() })).map { h.say(it) }.take(3)
        val av = 112f * h.u
        val pad = 34f * h.u
        val inner = minOf(h.maxW, 640f * h.u) - av - pad * 3f
        val nf = h.fit(name.ifEmpty { "…" }, Voices.head, 50f * h.u, inner, 1)
        val tf = h.fitAll(tags, Voices.label, 23f * h.u, inner, 1)
        val tagH = 42f * h.u
        val tagW = tf.map { it.width + 28f * h.u }
        val textW = maxOf(nf.width, tagW.sum() + (tags.size - 1).coerceAtLeast(0) * 10f * h.u)
        val w = av + pad * 3f + textW
        val ht = maxOf(av, nf.height + if (tags.isEmpty()) 0f else tagH + 14f * h.u) + pad * 2f
        val ax = if (h.rtl) w - pad - av / 2f else pad + av / 2f
        val textStart = if (h.rtl) w - pad * 2f - av else pad * 2f + av
        val block = nf.height + if (tags.isEmpty()) 0f else tagH + 14f * h.u
        val ny = ht / 2f - block / 2f + nf.height / 2f
        val ty = ny + nf.height / 2f + 14f * h.u + tagH / 2f
        Laid(w, ht, listOf(Slot(0f, 0f, w, ht, h.radius, h.card))) { t ->
            val nodes = ArrayList<Node>()
            nodes += h.card(w / 2f, ht / 2f, w, ht)
            nodes += h.pop(listOf(h.dot(ax, ht / 2f, av, ColorAnim.of(h.wash)), h.glyph(s.visual.ifEmpty { "user" }, ax, ht / 2f, av * 0.5f, ColorAnim.of(h.readable(h.accent, h.wash)), t.enter + 0.15f)), Box.around(ax, ht / 2f, av, av), t.enter + 0.05f, "avatar")
            nodes += h.words(name.ifEmpty { "…" }, nf, h.ink, textStart + h.dir * nf.width / 2f, ny, h.card, t.enter + 0.1f, name = "profile-name")
            var x = textStart
            for ((i, tag) in tags.withIndex()) {
                val tw = tagW[i]
                val cx = x + h.dir * tw / 2f
                val at = maxOf(t.revealAt(i), t.enter + 0.35f + i * 0.15f)
                val fill: Color = h.accentBody
                nodes += h.pop(listOf(h.rect(cx, ty, tw, tagH, tagH / 2f, fill), h.words(tag, tf[i], h.onAccent, cx, ty, fill, at + 0.05f, Kit.Reveal.None, name = "profile-tag")), Box.around(cx, ty, tw, tagH), at, "tag")
                x += h.dir * (tw + 10f * h.u)
            }
            h.toggled(listOf(h.rect(w / 2f, ht / 2f, w, ht, h.radius, null, stroke = Stroke(h.accent.fill(), 3.5f * h.u))), t.pickSteps(0), "profile-pick")?.let { nodes += it }
            nodes
        }
    }
}
