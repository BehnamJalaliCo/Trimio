package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Decoration
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Fit
import io.trimio.engine.motion.Grade
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Mask
import io.trimio.engine.motion.MediaNode
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.VectorNode
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.recipe.Fitted
import io.trimio.engine.motion.recipe.Icons
import io.trimio.engine.motion.recipe.Kit

/**
 * The illustrated-news devices (they work on a diagram stage too): a headline that reveals word by
 * word with its punchline on a dark box, the hero illustration rising on a spring, a speech bubble, a
 * dashed annotation box, a flip calendar that counts down with a page curl, and accent stars.
 */
internal object NewsPieces {
    private const val STAR = "M12 2l2.9 6.9 7.1.6-5.4 4.7 1.6 7-6.2-3.8-6.2 3.8 1.6-7L2 9.5l7.1-.6z"

    /** A line's centre x in a block [w] wide, set as the hand sets headlines. */
    private fun lineX(h: Hand, w: Float, lw: Float) = when (h.align) {
        TextAlign.Center -> w / 2f
        else -> if (h.rtl) w - lw / 2f else lw / 2f
    }

    /** Words that slide in one by one from the reading start, from [at], [stagger] apart. */
    private fun slide(h: Hand, at: Float, stagger: Float) =
        TextAnimator(TextUnit.Word, UnitState(dx = -0.4f * h.dir, opacity = 0f), at = at, duration = 0.42f, stagger = stagger, ease = Easing.ExpoOut)

    /** Kicker (light), the main line word by word, the punchline on a dark box, a grey second line. */
    val Headline = Piece { s, h ->
        val maxW = minOf(h.maxW, 940f * h.u)
        val main = h.say(s.text.ifEmpty { s.labels.joinToString(" ") }.ifEmpty { "…" })
        val kicker = s.kicker.takeIf { it.isNotEmpty() }?.let { h.say(it) }
        val punch = s.punch.takeIf { it.isNotEmpty() }?.let { h.say(it) }
        val second = s.caption.takeIf { it.isNotEmpty() }?.let { h.say(it) }
        val light = Voices.kicker.copy(weight = 300)
        val kf = kicker?.let { h.fit(it, light, 44f * h.u, maxW, 1) }
        // One line when it can stay large; two when one would shrink it too far.
        val mf = h.fit(main, Voices.head, 72f * h.u, maxW, 1).let { one -> if (one.type.size >= 72f * h.u * ONE_LINE) one else h.fit(main, Voices.head, 72f * h.u, maxW, 2) }
        val pf = punch?.let { h.fit(it, Voices.head, 58f * h.u, maxW - 40f * h.u, 2) }
        val sf = second?.let { h.fit(it, Voices.kicker.copy(weight = 500), 40f * h.u, maxW, 2) }
        val gap = 12f * h.u
        val boxPad = 0.26f
        val rows = listOfNotNull(kf, mf, pf, sf)
        val w = rows.maxOf { it.width } + (pf?.let { it.type.size * boxPad * 2f } ?: 0f)
        var y = 0f
        val ys = rows.map { f -> (y + f.height / 2f).also { y += f.height + gap } }
        val ht = y - gap + (pf?.let { it.type.size * 0.2f } ?: 0f)
        val slots = rows.mapIndexed { i, f -> Slot(lineX(h, w, f.width) - f.width / 2f, ys[i] - f.height / 2f, f.width, f.height, 8f * h.u, if (f === pf) h.ink else h.pal.track) }
        Laid(w, ht, slots) { t ->
            val words = main.split(' ').count { it.isNotEmpty() }
            val (from, to) = t.build ?: (t.enter + 0.12f to t.enter + 0.12f + words * 0.11f)
            val stagger = ((to - from) / words.coerceAtLeast(1)).coerceIn(0.06f, 0.4f)
            val mainEnd = from + stagger * words + 0.25f
            val nodes = ArrayList<Node>()
            var i = 0
            kf?.let { nodes += h.words(kicker, it, h.muted, lineX(h, w, it.width), ys[i++], h.ground, t.enter, Kit.Reveal.Fade, align = h.align, name = "kicker") }
            nodes += line(h, main, mf, h.ink, lineX(h, w, mf.width), ys[i++], slide(h, from, stagger), emptyList(), "headline")
            pf?.let { f ->
                val n = punch.split(' ').count { it.isNotEmpty() }
                val block = Decoration.Block(0 until n, mainEnd + 0.2f, h.ink, h.ground, duration = 0.4f, padX = boxPad, padTop = 0.1f, padBottom = 0.12f, radius = 0.1f)
                nodes += line(h, punch, f, h.ink, lineX(h, w, f.width + f.type.size * boxPad * 2f), ys[i++], slide(h, mainEnd, 0.08f), listOf(block), "punch")
            }
            sf?.let { nodes += h.words(second, it, h.muted, lineX(h, w, it.width), ys[i], h.ground, mainEnd + 0.5f, Kit.Reveal.Fade, align = h.align, name = "second-line") }
            nodes + TextPieces.underline(h, t, mf.width, (kf?.let { it.height + gap } ?: 0f) + mf.height, mf.type.size)
        }
    }

    private fun line(h: Hand, s: String, f: Fitted, color: Color, cx: Float, cy: Float, anim: TextAnimator, decorations: List<Decoration>, name: String): TextNode {
        val node = TextNode(
            s, f.type, Fill.Solid(ColorAnim.of(color)), maxWidth = f.width + 2f, align = h.align, rtl = h.isRtl(s),
            animators = listOf(anim), decorations = decorations, transform = Transform(x = cx.anim, y = cy.anim), name = name,
        )
        node.onBody = true
        node.ground = h.ground
        return node
    }

    /** The hero illustration in the film's style, rising on a spring over an accent disc, breathing while it stays. */
    val Hero = Piece { s, h ->
        val query = s.illustration.ifEmpty { s.visual }.ifEmpty { s.text }.ifEmpty { "person" }
        val art = h.illustrations.find(query, h.style)
        val aspect = when (art) {
            is Illustration.Raster -> art.width / art.height.coerceAtLeast(1f)
            is Illustration.Vector -> art.icon.width / art.icon.height.coerceAtLeast(1f)
            null -> 1f
        }.coerceIn(0.4f, 2.5f)
        val maxW = minOf(h.maxW, 940f * h.u)
        val maxH = minOf(h.maxH, 1000f * h.u)
        val w = minOf(maxW, maxH * aspect)
        val ht = w / aspect
        val d = minOf(w, ht) * 1.02f
        Laid(w, ht, listOf(Slot(w / 2f - d / 2f, ht * 0.56f - d / 2f, d, d, d / 2f, h.accent))) { t ->
            val rise = Anim.keys(Anim.Key(t.enter, ht * 0.6f, Easing.Hold), Anim.Key(t.enter + 0.95f, 0f, Easing.Spring(0.48f, 1.7f)))
            val breathe = Anim.tween(1f, 1.035f, t.enter + 0.9f, t.leave + 0.6f, Easing.SineInOut)
            val disc = if (h.pal.accentScene) null else Kit.item(
                io.trimio.engine.motion.recipe.Move.Pop, Box.around(w / 2f, ht * 0.56f, d, d), t.enter + 0.05f, h.dir,
                listOf(h.dot(w / 2f, ht * 0.56f, d, ColorAnim.of(h.accent), "hero-disc")), "hero-disc",
            )
            val body = Group(listOf(picture(h, art, query, w, ht, t)), Transform(anchorX = 0f, anchorY = 0f, y = rise, opacity = Anim.tween(0f, 1f, t.enter, t.enter + 0.2f)), name = "hero-rise")
            listOfNotNull(disc, Kit.pivot(listOf(body), w / 2f, ht, Box(0f, 0f, w, ht), Transform(scale = breathe), "hero"))
        }
    }

    /** The illustration itself: a styled raster, a tinted vector, or the stage's own glyph drawn by hand. */
    private fun picture(h: Hand, art: Illustration?, query: String, w: Float, ht: Float, t: Track): Node = when (art) {
        is Illustration.Raster -> MediaNode(
            IllustrationMedia.PREFIX + art.id, w, ht, Fit.Contain,
            // The film's sketch treatment: the drawing's darks in the ink, its paper gone, on any ground.
            grade = Grade(contrast = (if (h.style == IllustrationStyle.PencilSketch) 1.25f else 1f).anim, ink = h.ink),
            transform = Transform(x = (w / 2f).anim, y = (ht / 2f).anim), name = "hero-art",
        )
        is Illustration.Vector -> VectorNode(art.icon, minOf(w, ht), tint = ColorAnim.of(h.ink), transform = Transform(x = (w / 2f).anim, y = (ht / 2f).anim), name = "hero-art")
        null -> sketch(h, query, w, ht, t)
    }

    /** No illustration: the keyword's glyph, large, drawn on in two pencil passes. */
    private fun sketch(h: Hand, query: String, w: Float, ht: Float, t: Track): Node {
        val size = minOf(w, ht) * 0.7f
        return when (val g = Glyphs.resolve(query, h.icons)) {
            is Glyph.Picture -> VectorNode(g.icon, size, tint = ColorAnim.of(h.ink), transform = Transform(x = (w / 2f).anim, y = (ht / 2f).anim), name = "hero-art")
            is Glyph.Line -> Group(
                listOf(
                    ShapeNode(
                        ShapeSpec.Path(g.path, Icons.VIEWPORT, size), stroke = Stroke(h.ink.copy(alpha = 0.3f).fill(), size * 0.012f),
                        trimEnd = Anim.tween(0f, 1f, t.enter + 0.05f, t.enter + 1.1f, Easing.ExpoOut), transform = Transform(x = (w / 2f + 3f * h.u).anim,
                            y = (ht / 2f + 2f * h.u).anim), name = "sketch-echo",
                    ),
                    ShapeNode(
                        ShapeSpec.Path(g.path, Icons.VIEWPORT, size), stroke = Stroke(h.ink.fill(), size * 0.024f),
                        trimEnd = Anim.tween(0f, 1f, t.enter, t.enter + 0.9f, Easing.ExpoOut), transform = Transform(x = (w / 2f).anim, y = (ht / 2f).anim), name = "sketch",
                    ),
                ),
                name = "hero-sketch",
            )
        }
    }

    /** A speech bubble that pops with its words typed in; its tail points down to the hero. */
    val Bubble = Piece { s, h ->
        val str = h.say(s.text.ifEmpty { s.caption }.ifEmpty { "…" })
        val f = h.fit(str, Voices.body, 34f * h.u, minOf(h.maxW, 600f * h.u), 2)
        val padX = 28f * h.u
        val padY = 18f * h.u
        val tail = 20f * h.u
        val bw = f.width + padX * 2f
        val bh = f.height + padY * 2f
        Laid(bw, bh + tail, listOf(Slot(0f, 0f, bw, bh, bh / 2f, h.card))) { t ->
            val tx = if (h.rtl) bw * 0.78f else bw * 0.22f
            val tip = ShapeNode(
                ShapeSpec.Path("M0 0L24 0L4 22Z", 24f, tail * 1.2f), fill = Fill.Solid(ColorAnim.of(h.card)),
                transform = Transform(x = tx.anim, y = (bh - 2f * h.u).anim, anchorX = 0f, anchorY = 0f, scaleX = (if (h.rtl) -1f else 1f).anim), name = "bubble-tail",
            )
            val body = h.rect(bw / 2f, bh / 2f, bw, bh, minOf(bh / 2f, 30f * h.u), ColorAnim.of(h.card), stroke = Stroke(h.pal.rim.fill(), 2f * h.u), shadow = h.shadow())
            val words = h.words(str, f, h.ink, bw / 2f, bh / 2f, h.card, t.enter + 0.15f, Kit.Reveal.Type, name = "bubble")
            listOf(h.pop(listOf(tip, body, words), Box(0f, 0f, bw, bh + tail), t.enter, "bubble"))
        }
    }

    /** A dashed selection box that draws on, with anchor dots at two corners and a pointer. */
    val Annotation = Piece { s, h ->
        val str = h.say(s.text.ifEmpty { s.caption }.ifEmpty { "…" })
        val f = h.fit(str, Voices.body, 40f * h.u, minOf(h.maxW, 860f * h.u) - 70f * h.u, 2)
        val pad = 26f * h.u
        val o = 12f * h.u
        val bw = f.width + pad * 2f
        val bh = f.height + pad * 1.4f
        Laid(bw + o * 2f, bh + o * 2f, listOf(Slot(o, o, bw, bh, 4f * h.u, h.pal.track))) { t ->
            val draw = 0.6f
            val dashes = dashes(h, o, o, bw, bh, t.enter, draw)
            val wash = h.toggled(listOf(h.rect(o + bw / 2f, o + bh / 2f, bw, bh, 4f * h.u, h.wash)), (t.marks.map { it to true } + t.picks.map { it.first to true }).sortedBy { it.first }.take(1),
                "annotation-pick")
            val start = if (h.rtl) o + bw else o
            val end = if (h.rtl) o else o + bw
            val dotA = h.pop(listOf(h.dot(end, o, 14f * h.u, ColorAnim.of(h.ink))), Box.around(end, o, 14f * h.u, 14f * h.u), t.enter + draw * 0.4f, "anchor")
            val dotB = h.pop(listOf(h.dot(start, o + bh, 14f * h.u, ColorAnim.of(h.ink))), Box.around(start, o + bh, 14f * h.u, 14f * h.u), t.enter + draw, "anchor")
            val pointer = ShapeNode(
                ShapeSpec.Path("M0 0L16 6L6 16Z", 16f, 22f * h.u), fill = Fill.Solid(ColorAnim.of(h.ink)),
                transform = Transform(x = (start - h.dir * 4f * h.u).anim, y = (o + bh + 2f * h.u).anim, anchorX = 0f, anchorY = 0f, scaleX = (if (h.rtl) 1f else -1f).anim,
                    opacity = Anim.tween(0f, 1f, t.enter + draw, t.enter + draw + 0.15f)),
                name = "pointer",
            )
            listOfNotNull(wash) + dashes + listOf(dotA, dotB, pointer, h.words(str, f, h.ink, o + bw / 2f, o + bh / 2f, h.ground, t.enter + draw * 0.7f, Kit.Reveal.Fade, name = "annotation"))
        }
    }

    /** Dashes around a box, appearing one after another around it from the top reading-start corner. */
    private fun dashes(h: Hand, x: Float, y: Float, w: Float, ht: Float, at: Float, span: Float): List<Node> {
        val dash = 13f * h.u
        val gap = 8f * h.u
        val th = 2.6f * h.u
        val color = ColorAnim.of(h.ink.copy(alpha = 0.75f))
        val segs = ArrayList<Triple<Float, Float, Boolean>>()
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
            val len = kotlin.math.hypot(x1 - x0, y1 - y0)
            val n = ((len + gap) / (dash + gap)).toInt().coerceAtLeast(1)
            for (k in 0 until n) {
                val f = (k * (dash + gap) + dash / 2f) / len
                segs += Triple(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, x0 == x1)
            }
        }
        val (l, r) = if (h.rtl) (x + w) to x else x to (x + w)
        edge(l, y, r, y)
        edge(r, y, r, y + ht)
        edge(r, y + ht, l, y + ht)
        edge(l, y + ht, l, y)
        return segs.mapIndexed { k, (cx, cy, vertical) ->
            val a = at + span * k / segs.size
            h.rect(cx, cy, if (vertical) th else dash, if (vertical) dash else th, th / 2f, color, opacity = Anim.tween(0f, 1f, a, a + 0.05f), name = "dash")
        }
    }

    /** A calendar tile: a label on the accent band, a number on the dark page; each new number peels the old page away. */
    val Calendar = Piece { s, h ->
        val label = h.say(s.caption.ifEmpty { s.text }.ifEmpty { s.labels.firstOrNull().orEmpty() })
        val w = 360f * h.u
        val band = 118f * h.u
        val page = 262f * h.u
        val ht = band + page
        val lf = label.takeIf { it.isNotEmpty() }?.let { h.fit(it, Voices.head, 48f * h.u, w - 36f * h.u, 1) }
        Laid(w, ht, listOf(Slot(0f, 0f, w, band, 22f * h.u, h.accent), Slot(0f, band, w, page, 22f * h.u, h.ink))) { t ->
            val pages = countdown(s, t)
            val ink = ExplainerPalette.readable(h.accent, h.ink, h.ground)
            // The band is the accent made readable for words (a mid-tone accent darkens just enough).
            val bandColor = h.accentBody
            val bandInk = h.onAccent
            val nodes = ArrayList<Node>()
            nodes += h.rect(w / 2f, ht / 2f + 3f * h.u, w, ht, 24f * h.u, ColorAnim.of(h.ink.copy(alpha = 0.18f)), name = "calendar-shadow")
            // Pages from the last (bottom) up to the first: each peels away to show the next.
            for ((k, p) in pages.withIndex().reversed()) {
                val (n, end) = p
                val str = h.say("$n")
                val nf = h.fit(str, Voices.number, 196f * h.u, w, 1)
                val body = listOf(
                    h.rect(w / 2f, band + page / 2f, w, page, 22f * h.u, h.ink, "page"),
                    h.rect(w / 2f, band + 12f * h.u, w, 24f * h.u, 0f, h.ink, "page-top"),
                    h.words(str, nf, ink, w / 2f, band + page / 2f, h.ink, if (k == 0) t.enter + 0.2f else 0f, if (k == 0) Kit.Reveal.Pop else Kit.Reveal.None, name = "calendar-number"),
                )
                nodes += if (end == null) Group(body, name = "page") else peel(h, body, w, band, page, end)
            }
            nodes += h.rect(w / 2f, band / 2f, w, band, 22f * h.u, bandColor, "calendar-band")
            nodes += h.rect(w / 2f, band - 11f * h.u, w, 22f * h.u, 0f, bandColor, "calendar-band-foot")
            lf?.let { nodes += h.words(label, it, bandInk, w / 2f, band / 2f, bandColor, t.enter + 0.1f, Kit.Reveal.Fade, name = "calendar-label") }
            listOf(h.pop(nodes, Box(0f, 0f, w, ht), t.enter, "calendar"))
        }
    }

    /** The numbers shown, each with when its page peels away (null: it stays). */
    private fun countdown(s: Spec, t: Track): List<Pair<Int, Float?>> {
        val start = (s.value ?: s.items.size.takeIf { it > 1 }?.toDouble() ?: 3.0).toInt().coerceIn(1, 31)
        // A day count is a whole number of at least one; a share (≤ 1) says nothing to a calendar.
        val targets = t.fills.mapNotNull { f -> f.to?.takeIf { it >= 1.0 }?.let { f.at to it.toInt().coerceAtMost(99) } }
        val out = ArrayList<Pair<Int, Float?>>()
        if (targets.isEmpty()) {
            // A countdown by itself: one page every half second, from just after it lands.
            for (n in start downTo 1) out += n to (if (n > 1) t.enter + 0.75f + (start - n) * STEP else null)
            return out
        }
        var current = start
        for ((at, target) in targets) {
            val steps = (current - target).coerceAtLeast(0)
            for (k in 0 until steps) out += (current - k) to (at + k * FAST_STEP)
            current = target
        }
        out += current to null
        return out
    }

    private const val STEP = 0.55f
    private const val ONE_LINE = 0.8f
    private const val FAST_STEP = 0.3f

    /** A page peeling from its bottom reading-end corner at [at]: a sweeping mask and a curled flap. */
    private fun peel(h: Hand, body: List<Node>, w: Float, top: Float, page: Float, at: Float): Node = peel(body, w, top, page, at, 0.42f, h.rtl, h.u)

    /**
     * [body] ([w] wide, its page from [top] for [page] px) peeling away from its bottom reading-end
     * corner over [dur] from [at]: a mask sweeps along the fold while a shaded flap curls over it.
     */
    fun peel(body: List<Node>, w: Float, top: Float, page: Float, at: Float, dur: Float, rtl: Boolean, u: Float): Node {
        val reach = w + page
        val fold = Anim.tween(0f, reach * 1.05f, at, at + dur, Easing.Bezier(0.4f, 0f, 0.6f, 1f))
        val side = (w + page) * 2f
        val sx = if (rtl) -1f else 1f
        val corner = if (rtl) 0f else w
        val bottom = top + page
        // The kept part: everything behind the fold line, a rotated square whose edge is the fold.
        val k = side / 2f / kotlin.math.sqrt(2f)
        val maskX = Anim.tween(corner - sx * k, corner - sx * (k + reach * 1.05f / 2f), at, at + dur, Easing.Bezier(0.4f, 0f, 0.6f, 1f))
        val maskY = Anim.tween(bottom - k, bottom - k - reach * 1.05f / 2f, at, at + dur, Easing.Bezier(0.4f, 0f, 0.6f, 1f))
        val mask = ShapeNode(
            ShapeSpec.Rect(side.anim, side.anim), fill = Color.White.fill(),
            transform = Transform(x = maskX, y = maskY, rotation = (45f * sx).anim), name = "peel-mask",
        )
        val flap = ShapeNode(
            ShapeSpec.Path("M0 0L0 100L100 0Z", 100f, 100f), fill = Fill.Linear(listOf(Color(0xFFFFFFFF), Color(0xFFDCDCD8)), angle = 45f.anim),
            shadow = Shadow(Color.Black.copy(alpha = 0.16f), -4f * u * sx, -4f * u, 12f * u),
            transform = Transform(
                x = Anim.tween(corner, corner - sx * reach * 1.05f, at, at + dur, Easing.Bezier(0.4f, 0f, 0.6f, 1f)),
                y = Anim.tween(bottom, bottom - reach * 1.05f, at, at + dur, Easing.Bezier(0.4f, 0f, 0.6f, 1f)),
                anchorX = 0f, anchorY = 0f, scaleX = fold * (sx / 100f).anim, scaleY = fold * (1f / 100f).anim,
                opacity = Anim.keys(Anim.Key(at, 1f, Easing.Hold), Anim.Key(at + dur * 0.7f, 1f, Easing.Hold), Anim.Key(at + dur, 0f, Easing.Standard)),
            ),
            start = at, end = at + dur + 0.05f, name = "peel-flap",
        )
        val kept = Group(body, width = w, height = top + page, mask = Mask(mask), end = at + dur, name = "page")
        // The flap folds over the page: it never reaches past the page's own edges.
        val edge = ShapeNode(ShapeSpec.Rect(w.anim, page.anim), fill = Color.White.fill(), transform = Transform(x = (w / 2f).anim, y = (top + page / 2f).anim), name = "peel-edge")
        val folded = Group(listOf(flap), width = w, height = top + page, mask = Mask(edge), start = at, end = at + dur + 0.05f, name = "peel-fold")
        return Group(listOf(kept, folded), name = "peeling-page")
    }

    /** A five-point star in the accent at [cx], [cy] that spins in with a spring at [at]. */
    fun star(h: Hand, cx: Float, cy: Float, size: Float, at: Float, turn: Float, color: Color = h.emphasis): Node = ShapeNode(
        ShapeSpec.Path(STAR, 24f, size), fill = Fill.Solid(ColorAnim.of(color)),
        transform = Transform(
            x = cx.anim, y = cy.anim, rotation = Anim.tween(turn - 70f, turn, at, at + 0.8f, Easing.Spring(0.55f, 1.6f)),
            scale = Anim.tween(0f, 1f, at, at + 0.7f, Easing.Spring(0.5f, 1.8f)),
        ),
        start = at, name = "star",
    )

    /** A small cluster of accent stars (on a diagram stage; a news scene sets them at its corners). */
    val Stars = Piece { _, h ->
        val w = 300f * h.u
        val ht = 170f * h.u
        val spots = listOf(Triple(0.3f, 0.55f, 120f), Triple(0.7f, 0.35f, 84f), Triple(0.62f, 0.8f, 56f))
        Laid(w, ht, spots.map { (x, y, d) -> Slot(w * x - d * h.u / 2f, ht * y - d * h.u / 2f, d * h.u, d * h.u, d * h.u / 2f, h.accent) }) { t ->
            spots.mapIndexed { i, (x, y, d) -> star(h, w * x, ht * y, d * h.u, t.revealAt(i), -12f + i * 17f) }
        }
    }
}
