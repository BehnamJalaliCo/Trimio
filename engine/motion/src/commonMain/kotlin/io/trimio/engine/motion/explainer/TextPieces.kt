package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.recipe.Kit
import io.trimio.engine.motion.recipe.Look
import kotlin.math.abs
import kotlin.math.floor

/**
 * The word primitives: heading, kicker, big word, stat, quote, note, badge and icon. Each can be
 * built word by word, underlined by hand when highlighted, and the figures roll.
 */
internal object TextPieces {

    /** The words of a text primitive: its text, else its items joined. */
    private fun words(s: Spec, h: Hand) = h.say(s.text.ifEmpty { s.labels.joinToString(" ") }.ifEmpty { s.caption }.ifEmpty { "…" })

    /** A text line that reveals (or builds word by word), with a hand-drawn underline when marked. */
    private fun line(
        s: Spec, h: Hand, voice: Look.Voice, size: Float, width: Float, lines: Int, color: (Track) -> ColorAnim, name: String,
    ): Laid {
        val str = words(s, h)
        val f = h.fit(str, voice, size, minOf(h.maxW, width), lines)
        val marked = StageOp.Highlight in s.ops || StageOp.Fill in s.ops
        val under = if (marked) f.type.size * 0.42f else 0f
        val skeleton = h.pal.track
        val slot = Slot(0f, 0f, f.width, f.height, 8f * h.u, skeleton)
        return Laid(f.width, f.height + under, listOf(slot)) { t ->
            val node = h.words(str, f, color(t), f.width / 2f, f.height / 2f, h.ground, t.enter, name = name)
            val built = t.build?.let { (a, b) -> node.copyWith(h.wordByWord(a, b, str.split(' ').size)) } ?: node
            listOf(built) + underline(h, t, f.width, f.height, f.type.size)
        }
    }

    private fun io.trimio.engine.motion.TextNode.copyWith(anim: io.trimio.engine.motion.TextAnimator) = io.trimio.engine.motion.TextNode(
        text, type, fill, maxWidth, align, rtl, listOf(anim), decorations, stroke, shadow, transform, start, end, mask, blend, name,
    ).also { it.onBody = onBody; it.ground = ground }

    /** A hand-drawn underline under a [w]-wide text, drawn on at the first whole mark. */
    fun underline(h: Hand, t: Track, w: Float, top: Float, size: Float): List<Node> {
        val at = t.firstMark ?: t.picks.firstOrNull()?.first ?: return emptyList()
        val amp = size * 0.07f
        val pts = Scribbles.underline(h.seed + 11, w * 1.02f, amp).map { (x, y) -> x - w * 0.01f to y + top + size * 0.02f }
        return listOf(h.stroke(pts, ColorAnim.of(h.accent), maxOf(3f * h.u, size * 0.075f), at, at + 0.55f, name = "underline"))
    }

    val Heading = Piece { s, h -> line(s, h, Voices.head, 60f * h.u, 900f * h.u, 2, { ColorAnim.of(h.ink) }, "heading") }

    val Kicker = Piece { s, h -> line(s, h, Voices.kicker, 34f * h.u, 820f * h.u, 2, { ColorAnim.of(h.muted) }, "kicker") }

    /** Huge words in the accent: they arrive quiet and take the colour as they land. */
    val BigWord = Piece { s, h ->
        line(s, h, Voices.big, 116f * h.u, 900f * h.u, 2, { t -> ColorAnim.tween(h.muted, h.accentInk, t.enter + 0.25f, t.enter + 0.9f) }, "big-word")
    }

    val Note = Piece { s, h ->
        val str = words(s, h)
        val size = 30f * h.u
        val f = h.fit(str, Voices.kicker, size, minOf(h.maxW, 760f * h.u) - 40f * h.u, 2)
        val dash = 22f * h.u
        val gap = 16f * h.u
        val w = f.width + dash + gap
        Laid(w, f.height, listOf(Slot(0f, 0f, w, f.height, 8f * h.u, h.pal.track))) { t ->
            val tx = if (h.rtl) f.width / 2f else dash + gap + f.width / 2f
            val dx = if (h.rtl) f.width + gap + dash / 2f else dash / 2f
            val text = h.words(str, f, h.muted, tx, f.height / 2f, h.ground, t.enter + 0.1f, Kit.Reveal.Fade, name = "note")
            val bar = h.rect(dx, f.height / 2f, dash, 4f * h.u, 2f * h.u, ColorAnim.of(h.accent), width = Anim.tween(0f, dash, t.enter, t.enter + 0.4f, Easing.ExpoOut))
            listOf(bar, text) + underline(h, t, f.width, f.height, size)
        }
    }

    /** A big rolling figure with its label under it. */
    val Stat = Piece { s, h ->
        val v = s.value ?: s.items.firstOrNull()?.value ?: 0.0
        val decimals = decimalsOf(v)
        val unit = s.unit
        val size = 150f * h.u
        val label = h.say(s.text.ifEmpty { s.caption }.ifEmpty { s.labels.firstOrNull().orEmpty() })
        val lf = label.takeIf { it.isNotEmpty() }?.let { h.fit(it, Voices.label, 32f * h.u, minOf(h.maxW, 640f * h.u), 2) }
        val maxV = maxOf(abs(v), s.items.maxOfOrNull { abs(it.value ?: 0.0) } ?: 0.0)
        val fw = h.figureWidth(if (maxV >= 1) floor(maxV) * 1.0 + 0.9 else maxV, decimals, unit, size)
        val fh = size * 1.05f
        val w = maxOf(fw, lf?.width ?: 0f)
        val ht = fh + (lf?.let { it.height + 18f * h.u } ?: 0f)
        Laid(w, ht, listOf(Slot((w - fw) / 2f, 0f, fw, fh, 16f * h.u, h.wash))) { t ->
            val value = t.value(v, from = 0.0) { it.toFloat() }
            val n = h.counter(value, decimals, unit, size, h.accentInk, w / 2f, fh / 2f, h.ground, t.enter)
            listOfNotNull(n, lf?.let { h.words(label, it, h.muted, w / 2f, fh + 18f * h.u + it.height / 2f, h.ground, t.enter + 0.2f, Kit.Reveal.Fade, name = "stat-label") })
        }
    }

    /** A quotation on a card: a large accent mark, the words, and who said it. */
    val Quote = Piece { s, h ->
        val str = words(s, h)
        val inner = minOf(h.maxW, 820f * h.u) - 120f * h.u
        val f = h.fit(str, Voices.body, 40f * h.u, inner, 4)
        val by = s.caption.takeIf { it.isNotEmpty() }?.let { h.say(it) }
        val bf = by?.let { h.fit(it, Voices.kicker, 28f * h.u, inner, 1) }
        val pad = 56f * h.u
        val top = pad + 46f * h.u
        val w = maxOf(f.width, bf?.width ?: 0f) + pad * 2f
        val ht = f.height + top + pad + (bf?.let { it.height + 18f * h.u } ?: 0f)
        Laid(w, ht, listOf(Slot(0f, 0f, w, ht, h.radius, h.card))) { t ->
            val mark = if (h.rtl) "«" else "“"
            val mf = h.fit(mark, Voices.big, 92f * h.u, 200f * h.u, 1)
            val mx = if (h.rtl) w - pad - mf.width / 2f else pad + mf.width / 2f
            val tx = w / 2f
            listOfNotNull(
                h.card(w / 2f, ht / 2f, w, ht),
                h.words(mark, mf, h.accentOnCard, mx, pad * 0.5f + mf.height * 0.32f, h.card, t.enter + 0.1f, Kit.Reveal.Pop, name = "quote-mark"),
                h.words(str, f, h.ink, tx, top + f.height / 2f, h.card, t.enter + 0.15f, Kit.Reveal.Fade, name = "quote"),
                bf?.let { h.words(by, it, h.muted, tx, top + f.height + 18f * h.u + it.height / 2f, h.card, t.enter + 0.5f, Kit.Reveal.Fade, name = "quote-by") },
            )
        }
    }

    /** A pill in the accent: a label, with a small picture when it has one. */
    val Badge = Piece { s, h ->
        val str = words(s, h)
        val f = h.fit(str, Voices.body, 30f * h.u, minOf(h.maxW, 600f * h.u), 1)
        val ph = 66f * h.u
        val icon = s.visual.isNotEmpty()
        val iw = if (icon) 34f * h.u + 12f * h.u else 0f
        val w = f.width + 56f * h.u + iw
        Laid(w, ph, listOf(Slot(0f, 0f, w, ph, ph / 2f, h.accentBody))) { t ->
            val textX = if (h.rtl) (w - iw) / 2f else (w + iw) / 2f
            val iconX = if (h.rtl) w - 28f * h.u - 17f * h.u else 28f * h.u + 17f * h.u
            listOfNotNull(
                h.rect(w / 2f, ph / 2f, w, ph, ph / 2f, ColorAnim.of(h.accentBody), shadow = h.shadow()),
                if (icon) h.glyph(s.visual, iconX, ph / 2f, 34f * h.u, ColorAnim.of(h.onAccent), t.enter + 0.15f) else null,
                h.words(str, f, h.onAccent, textX, ph / 2f, h.accentBody, t.enter + 0.1f, Kit.Reveal.Fade, name = "badge"),
            )
        }
    }

    /** One picture in a soft disc, its label under it. */
    val Icon = Piece { s, h ->
        val d = 210f * h.u
        val label = h.say(s.text.ifEmpty { s.caption }.ifEmpty { s.labels.firstOrNull().orEmpty() })
        val lf = label.takeIf { it.isNotEmpty() }?.let { h.fit(it, Voices.label, 34f * h.u, minOf(h.maxW, 640f * h.u), 2) }
        val w = maxOf(d, lf?.width ?: 0f)
        val ht = d + (lf?.let { it.height + 24f * h.u } ?: 0f)
        Laid(w, ht, listOf(Slot((w - d) / 2f, 0f, d, d, d / 2f, h.wash))) { t ->
            val on = t.marks + t.picks.map { it.first }
            val disc = Track.steps(h.wash, on.sorted().take(1).map { it to h.accentBody })
            val glyphColor = Track.steps(h.accent.let { h.readable(it, h.wash) }, on.sorted().take(1).map { it to h.onAccent })
            listOfNotNull(
                h.pop(listOf(h.dot(w / 2f, d / 2f, d, disc, "disc")), Box(w / 2f - d / 2f, 0f, w / 2f + d / 2f, d), t.enter, "disc"),
                h.glyph(s.visual.ifEmpty { s.text.ifEmpty { s.caption } }, w / 2f, d / 2f, d * 0.46f, glyphColor, t.enter + 0.2f),
                lf?.let { h.words(label, it, h.ink, w / 2f, d + 24f * h.u + it.height / 2f, h.ground, t.enter + 0.3f, Kit.Reveal.Fade, name = "icon-label") },
            )
        }
    }

    /**
     * A hand-drawn mark on its own (no element to mark): an accent stroke under its words. The
     * stage draws marks around other elements itself ([marking]).
     */
    val Mark = Piece { s, h ->
        val str = s.text.takeIf { it.isNotEmpty() }?.let { h.say(it) }
        val f = str?.let { h.fit(it, Voices.head, 52f * h.u, minOf(h.maxW, 760f * h.u), 2) }
        val w = f?.width ?: (360f * h.u)
        val size = f?.type?.size ?: (48f * h.u)
        val th = f?.height ?: 0f
        Laid(w, th + size * 0.42f, listOf(Slot(0f, 0f, w, maxOf(th, 12f * h.u), 8f * h.u, h.pal.track))) { t ->
            val at = t.enter + 0.25f
            val pts = Scribbles.underline(h.seed + 5, w, size * 0.07f).map { (x, y) -> x to y + th + size * 0.02f }
            listOfNotNull(
                f?.let { h.words(str, it, h.ink, w / 2f, th / 2f, h.ground, t.enter, name = "mark-words") },
                h.stroke(pts, ColorAnim.of(h.accent), maxOf(3f * h.u, size * 0.08f), at, at + 0.6f, name = "mark"),
            )
        }
    }

    /**
     * A hand-drawn mark around another element's box ([w] × [h] local px of that element): a loop,
     * or an underline under it, drawn on from [at].
     */
    fun marking(h: Hand, w: Float, ht: Float, loop: Boolean, at: Float, seed: Long): List<Node> {
        val pad = 26f * h.u
        return if (loop) {
            val pts = Scribbles.loop(seed, w + pad * 2f, ht + pad * 2f).map { (x, y) -> x - pad to y - pad }
            listOf(h.stroke(pts, ColorAnim.of(h.accent), 5f * h.u, at, at + 0.7f, name = "mark-loop"))
        } else {
            val amp = 7f * h.u
            val pts = Scribbles.underline(seed, w * 1.04f, amp).map { (x, y) -> x - w * 0.02f to y + ht + 8f * h.u }
            listOf(h.stroke(pts, ColorAnim.of(h.accent), 5.5f * h.u, at, at + 0.55f, name = "mark-under"))
        }
    }

    fun decimalsOf(v: Double): Int = if (abs(v - kotlin.math.round(v)) < 1e-6) 0 else 1

    /** A tick for a whole element at its reading-end top corner. */
    fun cornerTick(h: Hand, w: Float, at: Float): Node {
        val d = 40f * h.u
        val x = if (h.rtl) d * 0.2f else w - d * 0.2f
        return h.tick(x, d * 0.2f, d, at)
    }
}
