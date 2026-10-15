package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Decoration
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * Words with a voice: a kinetic quote, a chapter card, a single chat bubble or comment, a poll,
 * a callout pointing at the speaker, and the lower-third in more than one cut.
 */
object TalkFamilies {

    private fun free(look: Look, cue: Cue): Color? = look.canvas.takeIf { !cue.overMedia }

    private fun inkFree(look: Look, cue: Cue) = free(look, cue)?.let { Kit.inkOn(look, it) } ?: look.ink

    /** Words rising as they are said (or on a stagger), emphasis inked in the accent. */
    private fun spoken(
        cue: Cue, look: Look, text: String, f: Fitted, color: Color, x: Float, y: Float, at: Float, ground: Color?, align: TextAlign, unit: UnitState,
    ): TextNode {
        val n = text.split(' ').count { it.isNotEmpty() }
        val times = cue.wordTimes?.takeIf { it.size == n && text == cue.text }?.map { it - Craft.LEAD }
        val reveal = TextAnimator(TextUnit.Word, unit, at = at, duration = 0.5f, stagger = 0.07f, ease = Easing.ExpoOut, times = times, clipToLine = unit.dy > 0.9f)
        val starts = times ?: List(n) { at + it * 0.07f }
        val accent = ground?.let { Kit.tint(look, look.accent, it) } ?: look.accent
        val marks = cue.emphasis.filter { it < n }.map { Decoration.Ink(it..it, starts.getOrElse(it) { at } + 0.3f, accent) }
        val node = TextNode(
            text, f.type, color.fill(), maxWidth = f.width + 2f, align = align, rtl = cue.rtl || Kit.persian(text), animators = listOf(reveal), decorations = marks,
            transform = Transform(x = x.anim, y = y.anim), name = "quote-text",
        )
        if (ground != null) {
            node.onBody = true
            node.ground = ground
        }
        return node
    }

    /** A pair of opening quotation marks (language-neutral), filling a 100 box. */
    private const val QUOTES = "M8 62C8 34 22 16 44 10L47 19C33 25 27 35 27 44C36 44 44 51 44 61C44 72 36 80 25 80C15 80 8 72 8 62Z" +
        "M56 62C56 34 70 16 92 10L95 19C81 25 75 35 75 44C84 44 92 51 92 61C92 72 84 80 73 80C63 80 56 72 56 62Z"

    /** A callout's ring round the speaker's head, as a share of the head's own size. */
    private const val RING_AIR = 1.3f

    /** A callout's note: its type size as a share of its slot's width, and at most this share of the slot's height. */
    private const val NOTE_SIZE = 0.1f
    private const val NOTE_HEIGHT = 0.45f

    // ------------------------------------------------------------------ quote

    /** A quote ([Cue.text]) with who said it ([Cue.label]): under big marks, beside a rule, or on a card. */
    val Quote = object : Family("quote", listOf(Move.Rise, Move.Focus, Move.Wipe)) {
        override val minHold = 1.6f
        override val preferredHeight = 0.3f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            if (cue.text.isBlank()) return Built(emptyList())
            return when (p.layout) {
                1 -> rule(cue, look, fit, p)
                2 -> card(cue, look, fit, p)
                else -> marks(cue, look, fit, p)
            }
        }

        private fun who(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, w: Float, size: Float): Pair<String, Fitted>? =
            cue.label?.takeIf { it.isNotBlank() }?.let { "— $it" to Kit.fit(fit, "— $it", Kit.labelVoice(look, p.typo), size, w, size * 1.6f) }

        private fun marks(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val q = minOf(b.height * 0.22f, b.width * 0.18f)
            val by = who(cue, look, fit, p, b.width * 0.8f, b.height * 0.07f)
            val textH = b.height - q * 0.9f - (by?.second?.height?.times(1.8f) ?: 0f)
            val f = Kit.fit(fit, cue.text, Kit.titleVoice(look, p.typo), b.height * 0.16f, b.width, textH, lines = 4)
            val accent = ground?.let { Kit.tint(look, look.accent, it) } ?: look.accent
            val nodes = mutableListOf<Node>(
                ShapeNode(
                    ShapeSpec.Path(QUOTES, 100f, q), fill = accent.fill(),
                    transform = Transform(
                        x = b.centerX.anim, y = (b.top + q * 0.45f).anim, scale = Anim.tween(0.3f, 1f, p.at, p.at + 0.5f, Easing.Spring(0.5f, 2.2f)),
                        scaleX = (if (p.rtl) -1f else 1f).anim,
                    ),
                    name = "quote-marks",
                ),
            )
            val ty = b.top + q * 0.95f + f.height / 2f
            nodes += spoken(cue, look, cue.text, f, inkFree(look, cue), b.centerX, ty, p.at + 0.2f, ground, TextAlign.Center, UnitState(dy = 1.2f))
            by?.let { (s, wf) ->
                val y = ty + f.height / 2f + wf.height * 1.1f
                nodes += Kit.line(b.centerX - q * 0.4f, y - wf.height * 0.75f, b.centerX + q * 0.4f, y - wf.height * 0.75f, accent, 3f, p.at + 0.6f, name = "quote-rule")
                nodes += Kit.words(s, wf, ground?.let { Kit.quiet(look, it) } ?: look.ink, b.centerX, y, p.at + 0.7f, ground, p.rtl, Kit.Reveal.Fade, name = "quote-by")
            }
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at, SfxKind.Shimmer, 0.4f)))
        }

        private fun rule(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val bar = b.width * 0.014f
            val by = who(cue, look, fit, p, b.width * 0.8f, b.height * 0.075f)
            val room = b.width - bar * 4f
            val f = Kit.fit(fit, cue.text, Kit.titleVoice(look, p.typo), b.height * 0.17f, room, b.height - (by?.second?.height?.times(1.6f) ?: 0f), lines = 4)
            val total = f.height + (by?.second?.height?.times(1.6f) ?: 0f)
            val top = b.centerY - total / 2f
            val edge = Kit.startX(b, p.rtl, 0f)
            val nodes = mutableListOf<Node>(
                ShapeNode(
                    ShapeSpec.Rect(bar.anim, Anim.tween(0f, total, p.at, p.at + 0.6f, Easing.ExpoOut), (bar / 2f).anim), fill = look.accent.fill(),
                    transform = Transform(x = (edge - p.dir * (-bar / 2f)).anim, y = top.anim, anchorY = 0f), name = "quote-bar",
                ),
            )
            val textEdge = edge + p.dir * bar * 3f
            nodes += spoken(
                cue, look, cue.text, f, inkFree(look, cue), Kit.fromStart(textEdge, f.width, p.rtl), top + f.height / 2f, p.at + 0.15f, ground, TextAlign.Start,
                UnitState(dy = 0.6f, opacity = 0f, blur = 0.15f),
            )
            by?.let { (s, wf) ->
                nodes += Kit.words(
                    s, wf, ground?.let { Kit.tint(look, look.accent, it) } ?: look.accent, Kit.fromStart(textEdge, wf.width, p.rtl),
                    top + f.height + wf.height * 0.9f, p.at + 0.6f, ground, p.rtl, Kit.Reveal.Fade, name = "quote-by",
                )
            }
            return Kit.built(cue, look, p, b, nodes, name)
        }

        private fun card(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val pad = minOf(b.width, b.height) * 0.1f
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, pad, "quote-card").toMutableList()
            val q = b.height * 0.42f
            nodes += ShapeNode(
                ShapeSpec.Path(QUOTES, 100f, q), fill = look.accent.copy(alpha = 0.14f).fill(),
                transform = Transform(x = Kit.endX(b, p.rtl, q * 0.55f).anim, y = (b.top + q * 0.42f).anim, scaleX = (if (p.rtl) -1f else 1f).anim), name = "quote-watermark",
            )
            val d = b.height * 0.17f
            val f = Kit.fit(fit, cue.text, Kit.titleVoice(look, p.typo), b.height * 0.14f, b.width - pad * 2f, b.height - pad * 2f - d * 1.4f, lines = 4)
            nodes += spoken(
                cue, look, cue.text, f, ink, Kit.fromStart(Kit.startX(b, p.rtl, pad), f.width, p.rtl), b.top + pad + f.height / 2f, p.at + 0.2f, s.ground,
                TextAlign.Start, UnitState(dy = 1.2f),
            )
            cue.label?.takeIf { it.isNotBlank() }?.let { name ->
                val y = b.bottom - pad - d / 2f
                val ax = Kit.startX(b, p.rtl, pad + d / 2f)
                val accent = Kit.surface(look, cue, Kit.Tone.Accent)
                nodes += Kit.dot(ax, y, d, accent.ground, "quote-avatar")
                val initial = name.trim().take(1)
                val inf = Kit.fit(fit, initial, look.headline, d * 0.5f, d * 0.8f, d * 0.8f)
                nodes += Kit.words(initial, inf, Kit.inkOn(look, accent.ground), ax, y, p.at + 0.5f, accent.ground, p.rtl, Kit.Reveal.Pop, name = "quote-initial")
                val nf = Kit.fit(fit, name, Kit.labelVoice(look, p.typo), d * 0.42f, b.width - pad * 2f - d * 1.5f, d)
                nodes += Kit.words(name, nf, Kit.quiet(look, s.ground), Kit.fromStart(ax - p.dir * (-d * 0.75f), nf.width, p.rtl), y, p.at + 0.6f, s.ground, p.rtl, Kit.Reveal.Fade, name = "quote-by")
            }
            return Kit.built(cue, look, p, b, nodes, name)
        }
    }

    // ------------------------------------------------------------------ chapter

    /** A chapter or part card: a big numeral beside the title, a centred eyebrow and title, or a slanted band. */
    val Chapter = object : Family("chapter", listOf(Move.Wipe, Move.Rise, Move.Focus)) {
        override val minHold = 1.8f
        override val preferredHeight = 0.26f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val title = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            val n = cue.value?.toInt() ?: (cue.seed % 3 + 1)
            return when (p.layout) {
                1 -> centred(cue, look, fit, p, title, n)
                2 -> band(cue, look, fit, p, title, n)
                else -> numeral(cue, look, fit, p, title, n)
            }
        }

        private fun eyebrow(p: Kit.Plan, n: Int) = Kit.say(p.rtl, "بخش ${Kit.digits("$n", true)}", "PART $n")

        private fun numeral(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, title: String, n: Int): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val num = Kit.digits(if (n < 10) "0$n" else "$n", p.rtl)
            val nf = Kit.fit(fit, num, Kit.numberVoice(look, p.typo), b.height * 0.8f, b.width * 0.36f, b.height * 0.9f)
            val accent = ground?.let { Kit.tint(look, look.accent, it) } ?: look.accent
            val numeral = Kit.words(num, nf, accent, Kit.fromStart(Kit.startX(b, p.rtl, 0f), nf.width, p.rtl), b.centerY, p.at, ground, p.rtl, Kit.Reveal.Rise, name = "chapter-number")
            val nodes = mutableListOf<Node>(numeral)
            val room = b.width - nf.width - b.width * 0.06f
            val ef = Kit.fit(fit, eyebrow(p, n), Kit.labelVoice(look, p.typo), b.height * 0.1f, room, b.height * 0.16f)
            val tf = Kit.fit(fit, title, Kit.titleVoice(look, p.typo), b.height * 0.26f, room, b.height * 0.62f, lines = 2)
            val edge = Kit.startX(b, p.rtl, nf.width + b.width * 0.06f)
            val top = b.centerY - (ef.height + tf.height) / 2f
            nodes += Kit.words(
                eyebrow(p, n), ef, ground?.let { Kit.quiet(look, it) } ?: look.ink, Kit.fromStart(edge, ef.width, p.rtl), top + ef.height / 2f, p.at + 0.2f,
                ground, p.rtl, Kit.Reveal.Fade, name = "chapter-eyebrow",
            )
            nodes += Kit.words(title, tf, inkFree(look, cue), Kit.fromStart(edge, tf.width, p.rtl), top + ef.height + tf.height / 2f, p.at + 0.3f, ground, p.rtl, name = "chapter-title")
            nodes += Kit.line(edge, top + ef.height + tf.height + 10f, edge + p.dir * minOf(room, tf.width), top + ef.height + tf.height + 10f, accent, 4f, p.at + 0.5f, 0.6f, "chapter-rule")
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at, SfxKind.Whoosh, 0.5f)))
        }

        private fun centred(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, title: String, n: Int): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val ef = Kit.fit(fit, eyebrow(p, n), Kit.labelVoice(look, p.typo), b.height * 0.11f, b.width * 0.5f, b.height * 0.18f)
            val tf = Kit.fit(fit, title, Kit.titleVoice(look, p.typo), b.height * 0.3f, b.width, b.height * 0.66f, lines = 2)
            val top = b.centerY - (ef.height * 1.4f + tf.height) / 2f
            val ey = top + ef.height / 2f
            val accent = ground?.let { Kit.tint(look, look.accent, it) } ?: look.accent
            val nodes = mutableListOf<Node>(
                Kit.words(eyebrow(p, n), ef, accent, b.centerX, ey, p.at, ground, p.rtl, Kit.Reveal.Fade, name = "chapter-eyebrow"),
                Kit.line(b.centerX - ef.width / 2f - 24f, ey, b.left + b.width * 0.08f, ey, accent.copy(alpha = 0.7f), 3f, p.at + 0.1f, name = "chapter-rule"),
                Kit.line(b.centerX + ef.width / 2f + 24f, ey, b.right - b.width * 0.08f, ey, accent.copy(alpha = 0.7f), 3f, p.at + 0.1f, name = "chapter-rule"),
                Kit.words(title, tf, inkFree(look, cue), b.centerX, top + ef.height * 1.4f + tf.height / 2f, p.at + 0.25f, ground, p.rtl, name = "chapter-title"),
            )
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at, SfxKind.Shimmer, 0.4f)))
        }

        private fun band(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, title: String, n: Int): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.36f))
            val s = Kit.surface(look, cue, Kit.Tone.Accent)
            val ink = Kit.inkOn(look, s.ground)
            val h = b.height * 0.64f
            val tilt = -p.dir * 3.5f
            val chip = Kit.surface(look, cue, Kit.Tone.Ink)
            val num = Kit.digits("$n", p.rtl)
            val cf = Kit.fit(fit, num, Kit.numberVoice(look, p.typo), h * 0.5f, h, h * 0.7f)
            val chipW = h * 0.9f
            val chipX = Kit.fromStart(Kit.startX(b, p.rtl, b.width * 0.03f), chipW, p.rtl)
            val tf = Kit.fit(fit, title, Kit.titleVoice(look, p.typo), h * 0.5f, b.width - chipW - b.width * 0.12f, h * 0.8f)
            val inner = listOf(
                Kit.rect(b.centerX, b.centerY, b.width, h, s.ground, h * 0.08f * look.roundness, "chapter-band"),
                Kit.rect(chipX, b.centerY, chipW, h * 0.8f, chip.ground, h * 0.1f * look.roundness, "chapter-chip"),
                Kit.words(num, cf, Kit.inkOn(look, chip.ground), chipX, b.centerY, p.at + 0.2f, chip.ground, p.rtl, Kit.Reveal.Pop, name = "chapter-number"),
                Kit.words(title, tf, ink, Kit.fromStart(Kit.startX(b, p.rtl, chipW + b.width * 0.07f), tf.width, p.rtl), b.centerY, p.at + 0.25f, s.ground, p.rtl, name = "chapter-title"),
            )
            val tilted = Kit.pivot(inner, b.centerX, b.centerY, b, Transform(rotation = tilt.anim), "chapter-tilt")
            return Kit.built(cue, look, p, b, listOf(tilted), name, listOf(Sfx(p.at, SfxKind.Hit, 0.5f)))
        }
    }

    // ------------------------------------------------------------------ bubble

    /**
     * One message ([Cue.text]) from someone ([Cue.label]): an incoming bubble after a typing
     * moment, a social comment with its like count, or the speaker's own reply with read ticks.
     * Generic chrome only, never any real app's.
     */
    val Bubble = object : Family("bubble", listOf(Move.Pop, Move.Rise, Move.Slide)) {
        override val minHold = 2.6f
        override val preferredHeight = 0.2f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            if (cue.text.isBlank()) return Built(emptyList())
            return when (p.layout) {
                1 -> comment(cue, look, fit, p)
                2 -> reply(cue, look, fit, p)
                else -> incoming(cue, look, fit, p)
            }
        }

        private fun avatar(look: Look, fit: Fitter, p: Kit.Plan, name: String, cx: Float, cy: Float, d: Float, at: Float): List<Node> {
            // The accent disc with a ring of heat: the initial on it is the look's on-accent ink.
            val disc = ShapeNode(
                ShapeSpec.Ellipse(d.anim, d.anim), fill = look.accent.fill(), stroke = Stroke(look.hot.fill(), d * 0.06f),
                transform = Transform(x = cx.anim, y = cy.anim, scale = Anim.tween(0f, 1f, at, at + 0.4f, Easing.Spring(0.5f, 2.2f))), name = "bubble-avatar",
            )
            val initial = name.trim().take(1).ifEmpty { "•" }
            val f = Kit.fit(fit, initial, look.headline, d * 0.48f, d * 0.8f, d * 0.8f)
            return listOf(disc, Kit.words(initial, f, look.onAccent, cx, cy, at + 0.1f, look.accent, p.rtl, Kit.Reveal.Fade, name = "bubble-initial"))
        }

        private fun incoming(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val d = minOf(b.height * 0.32f, 96f)
            val size = minOf(b.width * 0.06f, b.height * 0.2f)
            val f = Kit.fit(fit, cue.text, look.body.copy(weight = maxOf(look.body.weight, 700)), size, b.width - d * 1.6f - size * 1.6f, b.height * 0.7f, lines = 3)
            val bw = f.width + size * 1.6f
            val bh = f.height + size * 1.1f
            val name = cue.label ?: Kit.say(p.rtl, "پیام جدید", "New message")
            val nameF = Kit.fit(fit, name, Kit.labelVoice(look, p.typo), size * 0.6f, b.width * 0.6f, size)
            val total = nameF.height * 1.3f + bh
            val top = b.centerY - total / 2f
            val ax = Kit.startX(b, p.rtl, d / 2f)
            val bubbleStart = ax - p.dir * (-d * 0.7f)
            val bx = Kit.fromStart(bubbleStart, bw, p.rtl)
            val by = top + nameF.height * 1.3f + bh / 2f
            val fill = androidx.compose.ui.graphics.lerp(Kit.body(look, cue), look.ink, if (Kit.light(look.canvas)) 0.06f else 0.12f)
            val typing = p.at + 0.2f
            val land = typing + 0.6f
            val ink = Kit.inkOn(look, fill)
            val nodes = mutableListOf<Node>()
            nodes += avatar(look, fit, p, name, ax, by + bh / 2f - d / 2f, d, p.at)
            nodes += Kit.words(
                name, nameF, free(look, cue)?.let { Kit.quiet(look, it) } ?: look.ink, Kit.fromStart(bubbleStart, nameF.width, p.rtl), top + nameF.height / 2f,
                p.at + 0.1f, free(look, cue), p.rtl, Kit.Reveal.Fade, name = "bubble-name",
            )
            val r = minOf(bh / 2f, size * 1.1f) * maxOf(look.roundness, 0.4f)
            nodes += ShapeNode(
                ShapeSpec.Rect(anim(size * 2.6f, land) { to(bw, land + 0.4f, Easing.BackOut(1.3f)) }, anim(size * 1.6f, land) { to(bh, land + 0.4f, Easing.BackOut(1.3f)) }, r.anim),
                fill = fill.fill(), stroke = Stroke(look.cardRim.fill(), 1.5f),
                transform = Transform(x = (bubbleStart).anim, y = (by + bh / 2f).anim, anchorX = if (p.rtl) 1f else 0f, anchorY = 1f), name = "bubble",
            )
            nodes += dots(ink, bubbleStart + p.dir * size * 1.3f, by + bh / 2f - size * 0.8f, size, typing, land)
            nodes += Kit.words(cue.text, f, ink, bx, by, land + 0.15f, fill, p.rtl, Kit.Reveal.Fade, name = "bubble-text")
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(typing, SfxKind.Tick, 0.25f), Sfx(land, SfxKind.Pop, 0.5f)))
        }

        /** Three typing dots bouncing until [until]. */
        private fun dots(ink: Color, x: Float, y: Float, size: Float, at: Float, until: Float): List<Node> = (0 until 3).map { i ->
            val d = size * 0.3f
            val phase = at + i * 0.09f
            val bounce = anim(0f, phase) { while (time + 0.26f <= until) { by(-d * 0.6f, 0.13f, Easing.SineInOut); by(0f, 0.13f, Easing.SineInOut) } }
            ShapeNode(
                ShapeSpec.Ellipse(d.anim, d.anim), fill = ink.copy(alpha = 0.7f).fill(),
                transform = Transform(x = (x + (i - 1) * d * 1.6f).anim, y = y.anim + bounce, opacity = anim(0f, at) { by(1f, 0.1f); hold(until); by(0f, 0.08f) }),
                end = until + 0.1f, name = "bubble-dot",
            )
        }

        private fun comment(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val pad = minOf(b.height * 0.12f, 40f)
            val d = minOf(b.height * 0.28f, 96f)
            val name = cue.label ?: Kit.say(p.rtl, "کاربر", "viewer")
            val nf = Kit.fit(fit, name, look.body.copy(weight = 800), d * 0.44f, b.width * 0.4f, d * 0.6f)
            val when0 = Kit.say(p.rtl, "۲ دقیقه پیش", "2m ago")
            val wf = Kit.fit(fit, when0, Kit.labelVoice(look, p.typo), d * 0.3f, b.width * 0.3f, d * 0.5f)
            val tf = Kit.fit(fit, cue.text, look.body.copy(weight = maxOf(look.body.weight, 700)), b.width * 0.048f, b.width - pad * 3.4f - d * 1.9f, b.height - pad * 2.2f - d * 0.7f, lines = 3)
            val h = minOf(b.height, pad * 2f + d * 0.6f + tf.height + d * 0.7f)
            val card = Box(b.left, b.centerY - h / 2f, b.right, b.centerY + h / 2f)
            val nodes = Kit.panel(look, cue, s, card.centerX, card.centerY, card.width, card.height, pad, "comment-card").toMutableList()
            val ax = Kit.startX(card, p.rtl, pad + d / 2f)
            nodes += avatar(look, fit, p, name, ax, card.top + pad + d / 2f, d, p.at + 0.1f)
            val textEdge = Kit.startX(card, p.rtl, pad * 1.6f + d)
            val headY = card.top + pad + nf.height / 2f
            nodes += Kit.words(name, nf, ink, Kit.fromStart(textEdge, nf.width, p.rtl), headY, p.at + 0.15f, s.ground, p.rtl, Kit.Reveal.Fade, name = "comment-name")
            nodes += Kit.words(
                when0, wf, Kit.quiet(look, s.ground), Kit.fromStart(textEdge + p.dir * (nf.width + pad * 0.6f), wf.width, p.rtl), headY, p.at + 0.2f, s.ground,
                p.rtl, Kit.Reveal.Fade, name = "comment-time",
            )
            nodes += Kit.words(
                cue.text, tf, ink, Kit.fromStart(textEdge, tf.width, p.rtl), headY + nf.height / 2f + pad * 0.4f + tf.height / 2f, p.at + 0.3f, s.ground, p.rtl,
                Kit.Reveal.Rise, name = "comment-text",
            )
            val heartX = Kit.endX(card, p.rtl, pad + d * 0.25f)
            val heartY = card.centerY
            val pop = p.at + 1.1f
            nodes += ShapeNode(
                ShapeSpec.Path(Icons.path("heart")!!, Icons.VIEWPORT, d * 0.5f), fill = look.hot.fill(), stroke = Stroke(look.hot.fill(), d * 0.03f),
                transform = Transform(x = heartX.anim, y = heartY.anim, scale = anim(0f, pop) { by(1.35f, 0.18f, Easing.ExpoOut); by(1f, 0.3f, Easing.Land) }), name = "comment-heart",
            )
            val likes = cue.value ?: 128f
            val lf = Kit.fit(fit, Kit.format(likes, 0, p.rtl), Kit.labelVoice(look, p.typo), d * 0.28f, d, d * 0.5f)
            nodes += Kit.number(likes, likes - 1f, 0, "", "", Kit.labelVoice(look, p.typo), lf.type.size, Kit.quiet(look, s.ground), heartX, heartY + d * 0.42f, pop, 0.25f, s.ground, p.rtl)
            return Kit.built(cue, look, p, card, nodes, name, listOf(Sfx(pop, SfxKind.Pop, 0.5f)))
        }

        private fun reply(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val s = Kit.surface(look, cue, Kit.Tone.Accent)
            val ink = Kit.inkOn(look, s.ground)
            val size = minOf(b.width * 0.062f, b.height * 0.22f)
            val f = Kit.fit(fit, cue.text, look.body.copy(weight = maxOf(look.body.weight, 700)), size, b.width * 0.8f, b.height * 0.7f, lines = 3)
            val bw = f.width + size * 2.6f
            val bh = f.height + size * 1.1f
            // The reply sits on the reading end, like the sender's own messages do.
            val end = Kit.endX(b, p.rtl, 0f)
            val cx = Kit.fromEnd(end, bw, p.rtl)
            val r = minOf(bh / 2f, size * 1.1f) * maxOf(look.roundness, 0.4f)
            val nodes = mutableListOf<Node>(
                ShapeNode(ShapeSpec.Rect(bw.anim, bh.anim, r.anim), fill = s.fill, shadow = s.shadow, transform = Transform(x = cx.anim, y = b.centerY.anim), name = "reply"),
                Kit.words(cue.text, f, ink, cx - p.dir * size * 0.5f, b.centerY, p.at + 0.15f, s.ground, p.rtl, Kit.Reveal.Fade, name = "reply-text"),
            )
            val tick = size * 0.6f
            val tx = Kit.fromEnd(end, 0f, p.rtl) - p.dir * size * 0.85f
            for (k in 0..1) {
                val t = p.at + 0.6f + k * 0.3f
                nodes += Kit.icon("check", tx + p.dir * k * tick * 0.35f, b.centerY + bh / 2f - size * 0.55f, tick, ink.copy(alpha = 0.8f), t, tick * 0.11f)
            }
            val heart = p.at + 1.4f
            val hx = Kit.fromStart(Kit.fromEnd(end, bw, p.rtl) - p.dir * bw / 2f, 0f, p.rtl)
            val disc = Kit.surface(look, cue)
            nodes += ShapeNode(
                ShapeSpec.Ellipse((size * 1.3f).anim, (size * 1.3f).anim), fill = disc.ground.copy(alpha = 1f).fill(), stroke = Stroke(look.cardRim.fill(), 2f),
                transform = Transform(x = hx.anim, y = (b.centerY + bh / 2f).anim, scale = anim(0f, heart) { by(1.2f, 0.2f, Easing.ExpoOut); by(1f, 0.3f, Easing.Land) }), name = "reaction",
            )
            nodes += ShapeNode(
                ShapeSpec.Path(Icons.path("heart")!!, Icons.VIEWPORT, size * 0.8f), fill = look.hot.fill(),
                transform = Transform(x = hx.anim, y = (b.centerY + bh / 2f).anim, scale = anim(0f, heart + 0.05f) { by(1f, 0.35f, Easing.Spring(0.45f, 2.4f)) }), name = "reaction-heart",
            )
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at, SfxKind.Swish, 0.5f), Sfx(heart, SfxKind.Pop, 0.5f)))
        }
    }

    // ------------------------------------------------------------------ poll

    /**
     * A question ([Cue.text]) with options ([Cue.items]) and results ([Cue.points], percents):
     * result bars, a question sticker with an answer field, or two big answer buttons.
     */
    val Poll = object : Family("poll", listOf(Move.Pop, Move.Rise, Move.Flip)) {
        override val minHold = 3f
        override val preferredHeight = 0.3f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val q = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            // Results are only shown when they were said: an invented «۶۴٪» under a question the speaker asked
            // reached the owner's reel. Without them the question is a sticker with an answer field.
            val points = cue.points?.takeIf { it.isNotEmpty() } ?: return sticker(cue, look, fit, p, q)
            val options = Kit.items(cue, 4).ifEmpty { listOf(Kit.say(p.rtl, "بله", "Yes"), Kit.say(p.rtl, "نه", "No")) }
            val shares = options.indices.map { points.getOrNull(it) ?: 0f }
            return when (p.layout) {
                1 -> sticker(cue, look, fit, p, q)
                2 -> buttons(cue, look, fit, p, q, options, shares)
                else -> results(cue, look, fit, p, q, options, shares)
            }
        }

        private fun header(look: Look, fit: Fitter, p: Kit.Plan, q: String, b: Box, ground: Color, ink: Color, size: Float): Pair<Node, Float> {
            val f = Kit.fit(fit, q, Kit.titleVoice(look, p.typo), size, b.width * 0.88f, size * 2.6f, lines = 2)
            return Kit.words(q, f, ink, b.centerX, b.top + size * 0.5f + f.height / 2f, p.at + 0.1f, ground, p.rtl, name = "poll-question") to f.height + size
        }

        private fun results(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, q: String, options: List<String>, shares: List<Float>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.08f, "poll-card").toMutableList()
            val (head, headH) = header(look, fit, p, q, b, s.ground, ink, b.height * 0.1f)
            nodes += head
            val pad = b.width * 0.05f
            val rowH = (b.height - headH - pad) / options.size
            val best = shares.indices.maxBy { shares[it] }
            val total = shares.sum().coerceAtLeast(1f)
            for ((i, o) in options.withIndex()) {
                val top = b.top + headH + i * rowH
                val row = Box(b.left + pad, top + rowH * 0.12f, b.right - pad, top + rowH * 0.88f)
                val t0 = p.at + 0.3f + i * 0.15f
                val share = shares[i] / total
                val raised = Kit.surface(look, cue, Kit.Tone.Raised)
                val barColor = if (i == best) look.accent else androidx.compose.ui.graphics.lerp(raised.ground, look.ink, 0.12f)
                val fillW = row.width * share
                val parts = mutableListOf<Node>(
                    Kit.rect(row.centerX, row.centerY, row.width, row.height, raised.ground, row.height * 0.3f * look.roundness, "poll-track"),
                    ShapeNode(
                        ShapeSpec.Rect(Anim.tween(row.height * 0.1f, fillW, t0 + 0.4f, t0 + 1.3f, Easing.ExpoOut), row.height.anim, (row.height * 0.3f * look.roundness).anim), fill = barColor.fill(),
                        transform = Transform(x = Kit.startX(row, p.rtl, 0f).anim, y = row.centerY.anim, anchorX = if (p.rtl) 1f else 0f), name = "poll-bar",
                    ),
                )
                // The option's words sit where the bar may or may not reach: held to the worse of the two.
                val worse = if (Kit.ratio(ink, barColor) < Kit.ratio(ink, raised.ground)) barColor else raised.ground
                val textInk = if (Kit.ratio(ink, worse) >= Kit.FLOOR) ink else Kit.inkOn(look, worse)
                val of = Kit.fit(fit, o, Kit.titleVoice(look, p.typo), row.height * 0.46f, row.width * 0.66f, row.height * 0.8f)
                parts += Kit.words(
                    o, of, textInk, Kit.fromStart(Kit.startX(row, p.rtl, row.height * 0.4f), of.width, p.rtl), row.centerY, t0 + 0.1f, worse, p.rtl,
                    Kit.Reveal.Fade, name = "poll-option",
                )
                val pf = Kit.fit(fit, "100%", Kit.numberVoice(look, p.typo), row.height * 0.44f, row.width * 0.24f, row.height * 0.8f)
                parts += Kit.number(
                    shares[i], 0f, 0, "", if (p.rtl) "٪" else "%", Kit.numberVoice(look, p.typo), pf.type.size, textInk,
                    Kit.fromEnd(Kit.endX(row, p.rtl, row.height * 0.4f), pf.width, p.rtl), row.centerY, t0 + 0.4f, 0.9f, worse, p.rtl,
                )
                nodes += Kit.item(p.move, row, t0, p.dir, parts, "poll-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, options.indices.map { Sfx(p.at + 0.7f + it * 0.15f, SfxKind.Tick, 0.4f) })
        }

        private fun sticker(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, q: String): Built {
            val b = Kit.box(cue, cue.width * 0.86f, minOf(cue.height, cue.width * 0.5f))
            val s = Kit.surface(look, cue)
            val head = Kit.surface(look, cue, Kit.Tone.Accent)
            val headH = b.height * 0.56f
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.12f, "question-card").toMutableList()
            nodes += Kit.rect(b.centerX, b.top + headH / 2f, b.width, headH, head.ground, b.height * 0.12f * look.roundness, "question-head")
            val qf = Kit.fit(fit, q, Kit.titleVoice(look, p.typo), headH * 0.3f, b.width * 0.86f, headH * 0.8f, lines = 2)
            nodes += Kit.words(q, qf, Kit.inkOn(look, head.ground), b.centerX, b.top + headH / 2f, p.at + 0.15f, head.ground, p.rtl, name = "question")
            val field = Box(b.left + b.width * 0.06f, b.top + headH + b.height * 0.08f, b.right - b.width * 0.06f, b.bottom - b.height * 0.08f)
            val raised = Kit.surface(look, cue, Kit.Tone.Raised)
            nodes += Kit.rect(field.centerX, field.centerY, field.width, field.height, raised.ground, field.height * 0.3f * look.roundness, "answer-field")
            val hint = Kit.say(p.rtl, "جوابت رو بنویس…", "Type your answer…")
            val hf = Kit.fit(fit, hint, look.body.copy(weight = maxOf(look.body.weight, 600)), field.height * 0.38f, field.width * 0.8f, field.height * 0.7f)
            val hx = Kit.fromStart(Kit.startX(field, p.rtl, field.height * 0.4f), hf.width, p.rtl)
            nodes += Kit.words(hint, hf, Kit.quiet(look, raised.ground), hx, field.centerY, p.at + 0.4f, raised.ground, p.rtl, Kit.Reveal.Type, name = "answer-hint")
            val caretX = Kit.startX(field, p.rtl, field.height * 0.3f)
            val blink = Anim.keys(List(12) { k -> Anim.Key(p.at + 0.4f + k * 0.45f, if (k % 2 == 0) 1f else 0f, Easing.Hold) })
            nodes += ShapeNode(
                ShapeSpec.Rect(4f.anim, (field.height * 0.5f).anim), fill = look.accent.fill(),
                transform = Transform(x = caretX.anim, y = field.centerY.anim, opacity = blink), name = "caret",
            )
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at, SfxKind.Pop, 0.5f)))
        }

        private fun buttons(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, q: String, options: List<String>, shares: List<Float>): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.62f))
            val ground = free(look, cue)
            val qf = Kit.fit(fit, q, Kit.titleVoice(look, p.typo), b.height * 0.14f, b.width, b.height * 0.4f, lines = 2)
            val nodes = mutableListOf<Node>(Kit.words(q, qf, inkFree(look, cue), b.centerX, b.top + qf.height / 2f, p.at, ground, p.rtl, name = "poll-question"))
            val two = options.take(2)
            val gap = b.width * 0.05f
            val w = (b.width - gap) / 2f
            val h = minOf(b.height - qf.height - gap, w * 0.55f)
            val top = b.bottom - h
            val best = shares.take(2).let { if (it.size < 2 || it[0] >= it[1]) 0 else 1 }
            val tap = p.at + 1.2f
            for ((i, o) in two.withIndex()) {
                val left = if ((i == 0) != p.rtl) b.left else b.right - w
                val btn = Box(left, top, left + w, b.bottom)
                val s = Kit.surface(look, cue, if (i == best) Kit.Tone.Accent else Kit.Tone.Card)
                val ink = Kit.inkOn(look, s.ground)
                val t0 = p.at + 0.2f + i * 0.15f
                val parts = Kit.panel(look, cue, s, btn.centerX, btn.centerY, btn.width, btn.height, h * 0.22f, "poll-button").toMutableList()
                val of = Kit.fit(fit, o, Kit.titleVoice(look, p.typo), h * 0.26f, w * 0.8f, h * 0.4f)
                parts += Kit.words(o, of, ink, btn.centerX, btn.centerY - h * 0.12f, t0 + 0.1f, s.ground, p.rtl, Kit.Reveal.Pop, name = "poll-option")
                val pf = Kit.fit(fit, "100%", Kit.numberVoice(look, p.typo), h * 0.2f, w * 0.5f, h * 0.3f)
                parts += Kit.number(
                    shares.getOrElse(i) { 0f }, 0f, 0, "", if (p.rtl) "٪" else "%", Kit.numberVoice(look, p.typo), pf.type.size, ink, btn.centerX,
                    btn.centerY + h * 0.24f, tap + 0.1f, 0.8f, s.ground, p.rtl,
                )
                if (i == best) {
                    parts += ShapeNode(
                        ShapeSpec.Ellipse((h * 0.4f).anim, (h * 0.4f).anim), stroke = Stroke(ink.fill(), 4f),
                        transform = Transform(
                            x = btn.centerX.anim, y = btn.centerY.anim, scale = Anim.tween(0.2f, 3.2f, tap, tap + 0.6f, Easing.ExpoOut),
                            opacity = Anim.tween(0.8f, 0f, tap, tap + 0.6f),
                        ),
                        start = tap, end = tap + 0.65f, name = "tap",
                    )
                }
                nodes += Kit.item(p.move, btn, t0, p.dir, parts, "poll-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(tap, SfxKind.Click, 0.6f)), listOf(Craft.punch(tap, 0.015f)))
        }
    }

    // ------------------------------------------------------------------ callout

    /**
     * A note pointing at the speaker ([Cue.text]): a hand-drawn arrow curling down to them, a
     * leader line ending in a dot, or a ring drawn around the head; the frame punches in as it lands.
     */
    val Callout = object : Family("callout", listOf(Move.Wipe, Move.Pop, Move.Focus)) {
        override val minHold = 2f
        override val preferredHeight = 0.16f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val text = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            val fw = cue.frameWidth.takeIf { it > 0f } ?: (cue.width / 0.82f)
            val fh = cue.frameHeight.takeIf { it > 0f } ?: (fw * 16f / 9f)
            // Where the speaker is: their head when it is known (the side of it nearest the note), else a guess below the
            // slot toward the frame's middle (on a stage, the foot of the slot).
            val head = cue.head
            val target = when {
                head != null -> (if (cue.y < head.top) head.centerX to head.top else head.centerX to head.bottom)
                cue.overMedia -> fw / 2f to (cue.y + cue.height / 2f + fh * 0.1f).coerceAtMost(fh * 0.5f)
                else -> cue.x to cue.y + cue.height * 0.42f
            }
            return when {
                p.layout == 1 -> leader(cue, look, fit, p, text, target)
                // A ring is drawn around the head itself: where it is not known, a ring would circle the wall.
                p.layout == 2 && head != null -> ring(cue, look, fit, p, text, head)
                else -> arrow(cue, look, fit, p, text, target)
            }
        }

        private fun label(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, text: String, b: Box, pill: Boolean): Pair<List<Node>, Box> {
            val voice = if (p.typo == Typo.Data) Kit.titleVoice(look, p.typo) else look.headline
            // A note read at a glance on a phone: large type, two lines at most.
            val f = Kit.fit(fit, text, voice, minOf(b.height * NOTE_HEIGHT, b.width * NOTE_SIZE), b.width * 0.8f, minOf(b.height * 0.7f, b.width * 0.3f), lines = 2)
            val w = f.width + (if (pill) f.type.size * 1.2f else 0f)
            val h = f.height + (if (pill) f.type.size * 0.6f else 0f)
            val cx = Kit.fromStart(Kit.startX(b, p.rtl, 0f), w, p.rtl)
            val cy = b.top + h / 2f
            if (!pill) {
                val ground = free(look, cue)
                return listOf(Kit.words(text, f, inkFree(look, cue), cx, cy, p.at, ground, p.rtl, name = "callout-text")) to Box.around(cx, cy, w, h)
            }
            val s = Kit.surface(look, cue, Kit.Tone.Accent)
            return listOf(
                Kit.rect(cx, cy, w, h, s.ground, h / 2f * look.roundness, "callout-pill"),
                Kit.words(text, f, Kit.inkOn(look, s.ground), cx, cy, p.at + 0.1f, s.ground, p.rtl, name = "callout-text"),
            ) to Box.around(cx, cy, w, h)
        }

        private fun arrow(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, text: String, target: Pair<Float, Float>): Built {
            val slot = Box.around(cue.x, cue.y, cue.width, cue.height)
            val (words, lb) = label(cue, look, fit, p, text, slot, pill = false)
            val sx = if (p.rtl) lb.left - 20f else lb.right + 20f
            val sy = lb.centerY
            val tx = target.first + p.dir * cue.width * 0.12f
            val ty = target.second
            val bend = sx + (tx - sx) * 0.9f
            val pts = (0..24).map { i ->
                val u = i / 24f
                // A quadratic curl from the label out and down to the speaker.
                val x = (1 - u) * (1 - u) * sx + 2 * (1 - u) * u * bend + u * u * tx
                val y = (1 - u) * (1 - u) * sy + 2 * (1 - u) * u * sy + u * u * ty
                x to y
            }
            val color = look.accent
            val w = maxOf(6f, cue.width * 0.009f)
            val draw = p.at + 0.25f
            val nodes = words.toMutableList()
            nodes += Kit.path(pts, color, w, draw, 0.5f, smooth = true, name = "callout-arrow")
            val (hx, hy) = pts.last()
            val (px, py) = pts[pts.size - 3]
            val ang = kotlin.math.atan2(hy - py, hx - px)
            val head = w * 4.5f
            for (s in listOf(-1f, 1f)) {
                val a = ang + kotlin.math.PI.toFloat() + s * 0.5f
                nodes += Kit.line(hx, hy, hx + head * kotlin.math.cos(a), hy + head * kotlin.math.sin(a), color, w, draw + 0.45f, 0.15f, "callout-head")
            }
            val box = Box(minOf(slot.left, tx - head), slot.top, maxOf(slot.right, tx + head), maxOf(slot.bottom, ty + head))
            return Kit.built(cue, look, p, box, nodes, name, listOf(Sfx(draw, SfxKind.Swish, 0.5f)), listOf(Craft.punch(draw + 0.5f, 0.05f)))
        }

        private fun leader(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, text: String, target: Pair<Float, Float>): Built {
            val slot = Box.around(cue.x, cue.y, cue.width, cue.height)
            val (words, lb) = label(cue, look, fit, p, text, slot, pill = true)
            val color = look.accent
            val draw = p.at + 0.3f
            val tx = target.first + p.dir * cue.width * 0.1f
            val ty = target.second
            val elbowX = lb.centerX
            val nodes = words.toMutableList()
            nodes += Kit.line(elbowX, lb.bottom, elbowX, ty - 40f, color, 4f, draw, 0.3f, "leader")
            nodes += Kit.line(elbowX, ty - 40f, tx, ty, color, 4f, draw + 0.25f, 0.25f, "leader")
            nodes += Kit.dot(tx, ty, 22f, color, "leader-dot")
            nodes += ShapeNode(
                ShapeSpec.Ellipse(22f.anim, 22f.anim), stroke = Stroke(color.fill(), 3f),
                transform = Transform(
                    x = tx.anim, y = ty.anim, scale = anim(1f, draw + 0.5f) { by(3f, 0.9f, Easing.ExpoOut) }, opacity = anim(1f, draw + 0.5f) { by(0f, 0.9f) },
                ), start = draw + 0.5f, name = "leader-ping",
            )
            val box = Box(minOf(slot.left, tx - 40f), slot.top, maxOf(slot.right, tx + 40f), maxOf(slot.bottom, ty + 40f))
            return Kit.built(cue, look, p, box, nodes, name, listOf(Sfx(draw, SfxKind.Click, 0.5f)), listOf(Craft.punch(draw + 0.5f, 0.04f)))
        }

        private fun ring(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, text: String, head: Box): Built {
            val slot = Box.around(cue.x, cue.y, cue.width, cue.height)
            val (words, lb) = label(cue, look, fit, p, text, Box(slot.left, slot.top, slot.right, slot.top + slot.height * 0.7f), pill = true)
            val fw = cue.frameWidth.takeIf { it > 0f } ?: (cue.width / 0.82f)
            // Round the head with a little air: the ellipse below is 1.15 times as tall as it is wide.
            val r = maxOf(head.width, head.height / 1.15f) / 2f * RING_AIR
            val cx = head.centerX
            val cy = head.centerY
            val draw = p.at + 0.25f
            val pts = (0..60).map { i ->
                val a = -1.2f + i / 60f * 2.15f * kotlin.math.PI.toFloat()
                val wob = 1f + 0.04f * kotlin.math.sin(a * 3f)
                cx + r * kotlin.math.cos(a) * wob to cy + r * 1.15f * kotlin.math.sin(a) * wob
            }
            val nodes = words.toMutableList()
            nodes += Kit.path(pts, look.accent, maxOf(7f, fw * 0.008f), draw, 0.6f, smooth = true, name = "callout-ring")
            val box = Box(minOf(slot.left, cx - r * 1.1f), slot.top, maxOf(slot.right, cx + r * 1.1f), maxOf(lb.bottom, cy + r * 1.25f))
            return Kit.built(cue, look, p, box, nodes, name, listOf(Sfx(draw, SfxKind.Swish, 0.5f)), listOf(Craft.punch(draw + 0.3f, 0.06f)))
        }
    }

    // ------------------------------------------------------------------ lower third

    /**
     * Name and role in four cuts: the signature bar ([ElementRecipes.LowerThird]), a pill with an
     * initial, a two-tone news block, and a name over a sweeping underline.
     */
    val LowerThirds = object : Family("lower-third", listOf(Move.Slide, Move.Wipe, Move.Rise), layouts = 4) {
        override val minHold = 2.6f
        override val preferredHeight = 0.12f

        // The signature cut has its own choreography: one variant of it.
        override fun variantWeights(look: Look, cue: Cue): List<Float> =
            super.variantWeights(look, cue).mapIndexed { v, w -> if (v % layouts == 0 && v != 0) 0f else w }

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val name = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            val role = cue.label?.takeIf { cue.text.isNotBlank() }
            return when (p.layout) {
                1 -> pill(cue, look, fit, p, name, role)
                2 -> twoTone(cue, look, fit, p, name, role)
                3 -> underline(cue, look, fit, p, name, role)
                else -> ElementRecipes.LowerThird.build(cue, look, fit)
            }
        }

        private fun pill(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, name: String, role: String?): Built {
            val h = minOf(cue.height * 0.9f, cue.width * 0.19f)
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val d = h * 0.74f
            val nf = Kit.fit(fit, name, look.body.copy(weight = 800), h * (if (role != null) 0.3f else 0.38f), cue.width * 0.7f, h * 0.45f)
            val rf = role?.let { Kit.fit(fit, it, Kit.labelVoice(look, p.typo), h * 0.2f, cue.width * 0.7f, h * 0.3f) }
            val w = d + h * 0.5f + maxOf(nf.width, rf?.width ?: 0f) + h * 0.5f
            val slot = Box.around(cue.x, cue.y, cue.width, cue.height)
            val left = if (p.rtl) slot.right - w else slot.left
            val b = Box(left, cue.y - h / 2f, left + w, cue.y + h / 2f)
            val accent = Kit.surface(look, cue, Kit.Tone.Accent)
            val ax = Kit.startX(b, p.rtl, h * 0.13f + d / 2f)
            val initial = name.trim().take(1)
            val inf = Kit.fit(fit, initial, look.headline, d * 0.5f, d * 0.8f, d * 0.8f)
            val textEdge = Kit.startX(b, p.rtl, h * 0.13f + d + h * 0.3f)
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, h / 2f, "name-pill").toMutableList()
            nodes += Kit.dot(ax, b.centerY, d, accent.ground, "name-avatar")
            nodes += Kit.words(initial, inf, Kit.inkOn(look, accent.ground), ax, b.centerY, p.at + 0.15f, accent.ground, p.rtl, Kit.Reveal.Pop, name = "name-initial")
            val ny = if (rf != null) b.centerY - h * 0.13f else b.centerY
            nodes += Kit.words(name, nf, ink, Kit.fromStart(textEdge, nf.width, p.rtl), ny, p.at + 0.2f, s.ground, p.rtl, name = "name")
            if (role != null && rf != null) nodes += Kit.words(
                role, rf, Kit.quiet(look, s.ground), Kit.fromStart(textEdge, rf.width, p.rtl), b.centerY + h * 0.2f, p.at + 0.35f, s.ground, p.rtl,
                Kit.Reveal.Fade, name = "role",
            )
            return Kit.built(cue, look, p, b, nodes, this.name, listOf(Sfx(p.at, SfxKind.Swish, 0.5f)))
        }

        private fun twoTone(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, name: String, role: String?): Built {
            val h = minOf(cue.height * 0.55f, cue.width * 0.125f)
            val top = Kit.surface(look, cue, Kit.Tone.Accent)
            val bottom = Kit.surface(look, cue, Kit.Tone.Ink)
            val nf = Kit.fit(fit, name, look.headline, h * 0.6f, cue.width * 0.8f, h * 0.8f)
            val rf = role?.let { Kit.fit(fit, it, Kit.labelVoice(look, p.typo), h * 0.38f, cue.width * 0.7f, h * 0.6f) }
            val w1 = nf.width + h * 0.8f
            val w2 = (rf?.width ?: 0f) + h * 0.7f
            val slot = Box.around(cue.x, cue.y, cue.width, cue.height)
            val edge = Kit.startX(slot, p.rtl, 0f)
            val total = h + (if (rf != null) h * 0.78f else 0f)
            val y1 = cue.y - total / 2f + h / 2f
            val x1 = Kit.fromStart(edge, w1, p.rtl)
            val nodes = mutableListOf<Node>(
                ShapeNode(
                    ShapeSpec.Rect(Anim.tween(0f, w1, p.at, p.at + 0.45f, Easing.ExpoOut), h.anim), fill = top.fill,
                    transform = Transform(x = edge.anim, y = y1.anim, anchorX = if (p.rtl) 1f else 0f), name = "name-block",
                ),
                Kit.words(name, nf, Kit.inkOn(look, top.ground), x1, y1, p.at + 0.2f, top.ground, p.rtl, name = "name"),
            )
            if (rf != null && role != null) {
                val y2 = y1 + h / 2f + h * 0.39f
                val inset = edge + p.dir * h * 0.3f
                nodes += ShapeNode(
                    ShapeSpec.Rect(Anim.tween(0f, w2, p.at + 0.2f, p.at + 0.6f, Easing.ExpoOut), (h * 0.78f).anim), fill = bottom.fill,
                    transform = Transform(x = inset.anim, y = y2.anim, anchorX = if (p.rtl) 1f else 0f), name = "role-block",
                )
                nodes += Kit.words(role, rf, Kit.inkOn(look, bottom.ground), Kit.fromStart(inset, w2, p.rtl), y2, p.at + 0.4f, bottom.ground, p.rtl, Kit.Reveal.Fade, name = "role")
            }
            val b = Box(minOf(edge, edge + p.dir * maxOf(w1, w2 + h * 0.3f)), cue.y - total / 2f, maxOf(edge, edge + p.dir * maxOf(w1, w2 + h * 0.3f)), cue.y + total / 2f)
            return Kit.built(cue, look, p, b, nodes, this.name, listOf(Sfx(p.at, SfxKind.Swish, 0.5f)))
        }

        private fun underline(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, name: String, role: String?): Built {
            val ground = free(look, cue)
            val nf = Kit.fit(fit, name, Kit.titleVoice(look, p.typo), cue.height * 0.42f, cue.width * 0.86f, cue.height * 0.5f)
            val rf = role?.let { Kit.fit(fit, it, Kit.labelVoice(look, p.typo), cue.height * 0.2f, cue.width * 0.8f, cue.height * 0.3f) }
            val slot = Box.around(cue.x, cue.y, cue.width, cue.height)
            val edge = Kit.startX(slot, p.rtl, 0f)
            val ny = cue.y - (if (rf != null) cue.height * 0.12f else 0f)
            val sq = nf.height * 0.36f
            val nodes = mutableListOf<Node>(
                Kit.rect(edge - p.dir * (-sq / 2f), ny, sq, sq, look.accent, 0f, "name-square"),
                Kit.words(name, nf, inkFree(look, cue), Kit.fromStart(edge + p.dir * sq * 1.8f, nf.width, p.rtl), ny, p.at + 0.1f, ground, p.rtl, name = "name"),
                Kit.line(edge, ny + nf.height * 0.58f, edge + p.dir * (nf.width + sq * 1.8f), ny + nf.height * 0.58f, look.accent, 5f, p.at + 0.25f, 0.55f, "name-rule"),
            )
            if (rf != null && role != null) {
                nodes += Kit.words(
                    role, rf, ground?.let { Kit.quiet(look, it) } ?: look.ink, Kit.fromStart(edge + p.dir * sq * 1.8f, rf.width, p.rtl),
                    ny + nf.height * 0.58f + rf.height * 0.9f, p.at + 0.45f, ground, p.rtl, Kit.Reveal.Fade, name = "role",
                )
            }
            val w = nf.width + sq * 1.8f
            val b = Box(minOf(edge, edge + p.dir * w), ny - nf.height / 2f, maxOf(edge, edge + p.dir * w), ny + nf.height * 0.58f + (rf?.height?.times(1.4f) ?: 10f))
            return Kit.built(cue, look, p, b, nodes, this.name, listOf(Sfx(p.at, SfxKind.Swish, 0.5f)))
        }
    }

    val all: List<Recipe> = listOf(Quote, Chapter, Bubble, Poll, Callout, LowerThirds)
}
