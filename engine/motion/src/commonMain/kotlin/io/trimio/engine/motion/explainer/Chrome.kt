package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.recipe.Kit

/**
 * The chapter header: one heading per chapter (it arrives quiet and darkens as it lands, and
 * leaves as the stage clears) under the chapter mark — dashes that fill in the chapter's accent,
 * numerals, or a rule that grows with the film.
 */
internal class Header(
    private val text: TextLayoutEngine,
    private val pal: ExplainerPalette,
    private val frame: StageFrame,
    private val rtl: Boolean,
    private val chapters: List<Chapter>,
    private val duration: Float,
    private val mark: ChapterMark,
    persian: Boolean,
) {
    private val u = frame.u
    private val n = chapters.size
    private val hand = Hand(text, pal, 0, rtl, u, IconResolver.None, frame.width, frame.height, 0L).also { it.persian = persian }
    private val dir = if (rtl) -1f else 1f

    private fun start(c: Int) = chapters[c].start.toFloat()
    private fun end(c: Int) = if (c == n - 1) duration else chapters[c].end.toFloat()

    /** Centre x of something [w] wide set on the header's line (centred, or from the reading start). */
    private fun along(w: Float): Float = if (frame.titleAlign == TextAlign.Center) frame.titleX else frame.titleX + dir * w / 2f

    fun title(c: Int): Node {
        val ch = chapters[c]
        val str = hand.say(ch.title.trim().ifEmpty { "${c + 1}" })
        val lines = if (frame.grammar == StageGrammar.Split && !frame.portrait) 3 else 1
        val f = hand.fit(str, Voices.title, frame.titleSize, frame.titleWidth, lines)
        val at = start(c) + 0.05f
        val color = ColorAnim.tween(pal.muted, pal.ink, at + 0.15f, at + 0.95f, Easing.Standard)
        val node = TextNode(
            str, f.type, Fill.Solid(color), maxWidth = f.width + 2f, align = frame.titleAlign, rtl = rtl || Kit.persian(str),
            animators = listOfNotNull(Kit.Reveal.Rise.animator(at)), transform = Transform(x = along(f.width).anim, y = frame.titleTop.anim, anchorY = 0f), name = "title",
        )
        node.onBody = true
        node.ground = pal.background
        val out = end(c)
        val fade = if (c == n - 1) Anim.One else Anim.keys(Anim.Key(out - 0.34f, 1f, Easing.Hold), Anim.Key(out - 0.02f, 0f, Easing.Standard))
        val lift = if (c == n - 1) Anim.Zero else Anim.tween(0f, -14f * u, out - 0.34f, out, Easing.ExpoIn)
        return Group(listOf(node), Transform(anchorX = 0f, anchorY = 0f, opacity = fade, y = lift), start = start(c), end = out, name = "chapter-title")
    }

    fun marks(): List<Node> = when (mark) {
        ChapterMark.Dashes -> dashes()
        ChapterMark.Numerals -> numerals()
        ChapterMark.Rule -> rule()
        ChapterMark.None -> emptyList()
    }

    private fun dashes(): List<Node> {
        if (n == 0) return emptyList()
        // Many chapters share the header's width: dashes narrow (and their gaps with them) to fit.
        val room = minOf(frame.titleWidth, frame.safe.width * 0.8f)
        val gap = minOf(12f * u, room / n * 0.25f)
        val dw = minOf(36f * u, (room - (n - 1) * gap) / n)
        val total = n * dw + (n - 1) * gap
        val cx = along(total)
        return (0 until n).map { i ->
            val k = if (rtl) n - 1 - i else i
            val x = cx - total / 2f + k * (dw + gap) + dw / 2f
            val events = (0 until n).map { c -> start(c) + 0.1f to if (i <= c) pal.accent(chapters[c].accent) else pal.track }
            ShapeNode(
                ShapeSpec.Rect(dw.anim, (7f * u).anim, (3.5f * u).anim), fill = Fill.Solid(Track.steps(pal.track, events)),
                transform = Transform(x = x.anim, y = frame.markY.anim, opacity = Anim.tween(0f, 1f, start(0), start(0) + 0.4f)), name = "dash",
            )
        }
    }

    private fun two(i: Int): String = hand.say(i.toString().padStart(2, '0'))

    private fun numerals(): List<Node> = (0 until n).map { c ->
        val str = "${two(c + 1)}  /  ${two(n)}"
        val f = hand.fit(str, Voices.number, 30f * u, frame.titleWidth, 1)
        val node = TextNode(
            str, f.type, Fill.Solid(ColorAnim.of(pal.accentText(chapters[c].accent))), maxWidth = f.width + 2f, rtl = false,
            animators = listOfNotNull(Kit.Reveal.Fade.animator(start(c))), transform = Transform(x = along(f.width).anim, y = frame.markY.anim), name = "numeral",
        )
        node.onBody = true
        node.ground = pal.background
        Group(listOf(node), Transform(anchorX = 0f, anchorY = 0f, opacity = if (c == n - 1) Anim.One else Anim.tween(1f, 0f, end(c) - 0.3f, end(c))), start = start(c), end = end(c), name = "chapter-numeral")
    }

    private fun rule(): List<Node> {
        if (n == 0) return emptyList()
        val len = 220f * u
        val left = along(len) - len / 2f
        val anchor = if (rtl) 1f else 0f
        val x0 = if (rtl) left + len else left
        val keys = ArrayList<Anim.Key>()
        keys += Anim.Key(start(0), 0f, Easing.Hold)
        for (c in 0 until n) {
            keys += Anim.Key(maxOf(start(c), keys.last().t), keys.last().v, Easing.Hold)
            keys += Anim.Key(maxOf(start(c), keys.last().t) + 0.8f, len * (c + 1) / n, Easing.ExpoOut)
        }
        val colours = (0 until n).map { c -> start(c) to pal.accent(chapters[c].accent) }
        return listOf(
            ShapeNode(ShapeSpec.Rect(len.anim, (3f * u).anim, (1.5f * u).anim), fill = Fill.Solid(ColorAnim.of(pal.track)), transform = Transform(x = (left + len / 2f).anim, y = frame.markY.anim), name = "rule"),
            ShapeNode(
                ShapeSpec.Rect(Anim.keys(keys), (5f * u).anim, (2.5f * u).anim), fill = Fill.Solid(Track.steps(pal.accent(chapters[0].accent), colours.drop(1))),
                transform = Transform(x = x0.anim, y = frame.markY.anim, anchorX = anchor), name = "rule-fill",
            ),
        )
    }
}

/** Captions: a dark pill of a few words on a thin accent band, or quiet low words, or none. */
internal class Captions(private val text: TextLayoutEngine, private val pal: ExplainerPalette, private val frame: StageFrame, private val rtl: Boolean, persian: Boolean) {
    private val u = frame.u
    private val hand = Hand(text, pal, 0, rtl, u, IconResolver.None, frame.width, frame.height, 0L).also { it.persian = persian }

    fun nodes(style: CaptionStyle, chunks: List<CaptionChunk>, duration: Float): List<Node> = when (style) {
        CaptionStyle.None -> emptyList()
        CaptionStyle.BandPill -> listOf(band(duration)) + chunks.mapNotNull { pill(it, pal.pill, pal.onPill) }
        CaptionStyle.PlainLower -> chunks.mapNotNull { pill(it, null, pal.ink) }
    }

    private fun band(duration: Float): Node = ShapeNode(
        ShapeSpec.Rect(frame.width.anim, frame.band.height.anim), fill = Fill.Solid(ColorAnim.of(pal.bandColor)),
        transform = Transform(x = (frame.width / 2f).anim, y = frame.captionY.anim, opacity = Anim.tween(0f, 1f, 0f, 0.4f)), end = duration + 1f, name = "caption-band",
    )

    private fun pill(c: CaptionChunk, body: Color?, ink: Color): Node? {
        val words = hand.say(c.text.trim()).ifEmpty { return null }
        val a = c.start.toFloat()
        val b = c.end.toFloat().coerceAtLeast(a + 0.2f)
        val f = hand.fit(words, if (body != null) Voices.label else Voices.body, (if (body != null) 34f else 36f) * u, frame.safe.width * 0.82f, 2)
        val ground = body ?: pal.background
        val node = TextNode(words, f.type, Fill.Solid(ColorAnim.of(ink)), maxWidth = f.width + 2f, rtl = rtl || Kit.persian(words), transform = Transform(x = (frame.width / 2f).anim, y = frame.captionY.anim), name = "caption")
        node.onBody = true
        node.ground = ground
        val parts = listOfNotNull(
            body?.let {
                ShapeNode(
                    ShapeSpec.Rect((f.width + 48f * u).anim, (f.height + 20f * u).anim, (16f * u).anim), fill = Fill.Solid(ColorAnim.of(it)),
                    transform = Transform(x = (frame.width / 2f).anim, y = frame.captionY.anim), name = "caption-pill",
                )
            },
            node,
        )
        val fade = Anim.keys(Anim.Key(a, 0f, Easing.Hold), Anim.Key(a + 0.12f, 1f, Easing.Standard), Anim.Key(b - 0.06f, 1f, Easing.Hold), Anim.Key(b, 0f, Easing.Standard))
        return Group(parts, Transform(anchorX = 0f, anchorY = 0f, opacity = fade), start = a, end = b + 0.01f, name = "caption-chunk")
    }
}
