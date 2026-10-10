package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.AnimBuilder
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Mask
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
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * Reported speech as a chat thread: "someone DM'd me and said …" shown the way premium social
 * edits do it. A generic header (never any real app's UI), then each message in turn: a typing
 * indicator that morphs into the bubble with a spring, the words, read ticks on replies; the
 * thread scrolls up like a real one. Persian threads are mirrored: incoming on the right.
 */
object MessageRecipes {

    /** One bubble as laid out: its text, whether it is the speaker's reply, its fitted size and times. */
    private class Bubble(
        val text: String, val outgoing: Boolean, val fitted: Fitted, val w: Float, val h: Float,
        /** Typing starts, then the indicator morphs into the bubble (its landing). */
        val typing: Float, val land: Float,
    )

    /** The card: its box, where the thread area is, and the type metrics everything scales from. */
    private class Card(val cx: Float, val cy: Float, val w: Float, val h: Float, val size: Float, val header: Float, val rtl: Boolean) {
        val left get() = cx - w / 2f
        val top get() = cy - h / 2f
        val pad get() = size * 0.75f
        val tail get() = size * 0.55f
        val areaTop get() = top + header
        val areaBottom get() = top + h - pad
        val areaLeft get() = left + pad + tail
        val areaRight get() = left + w - pad - tail
        val indicatorW get() = size * 2.9f
        val indicatorH get() = size * 1.7f
    }

    /**
     * A chat thread from [Cue.items] in order; an item starting with ">" is the speaker's reply
     * (accent, on the reading-end side), the rest are incoming. [Cue.label] names the sender.
     */
    val Message = object : Recipe("message", Kind.Element) {
        override val preferredHeight = 0.42f
        override val minHold = 3f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val raw = cue.items.ifEmpty { cue.text.split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() } }.take(MAX_BUBBLES)
            if (raw.isEmpty()) return Built(emptyList())
            val rtl = cue.rtl || raw.any { it.any { c -> c in '؀'..'ۿ' } }
            val stage = !cue.overMedia
            val w = cue.width * (if (stage) 1f else 0.94f)
            val size = w * (if (stage) 0.05f else 0.046f)
            val card = Card(cue.x, cue.y, w, cue.height, size, size * 2.6f, rtl)
            val bubbles = layout(cue, look, fit, card, raw, at)
            val nodes = mutableListOf<Node>()
            nodes += body(cue, look, card, at)
            nodes += header(cue, look, fit, card, at)
            nodes += thread(look, card, bubbles)
            val replies = bubbles.filter { it.outgoing }
            return Built(
                listOf(group(cue, nodes)),
                camera = replies.map { Craft.punch(it.land, 0.015f) },
                sfx = listOf(Sfx(at, SfxKind.Swish, 0.45f)) + bubbles.flatMap { b ->
                    listOf(Sfx(b.typing, SfxKind.Tick, 0.2f), Sfx(b.land, if (b.outgoing) SfxKind.Swish else SfxKind.Pop, if (b.outgoing) 0.5f else 0.45f))
                },
            )
        }

        /** Fits every bubble and decides when each one types and lands (on its quote when it is said). */
        private fun layout(cue: Cue, look: Look, fit: Fitter, card: Card, raw: List<String>, at: Float): List<Bubble> {
            val voice = voice(look)
            val maxText = (card.areaRight - card.areaLeft) * MAX_WIDTH - card.size * 1.3f
            val out = mutableListOf<Bubble>()
            var prev = at + 0.35f + TYPING
            for ((i, item) in raw.withIndex()) {
                val outgoing = item.startsWith(">")
                val text = item.removePrefix(">").trim()
                val f = fit.fit(text, voice, card.size, maxText, (card.areaBottom - card.areaTop) * 0.8f, maxLines = MAX_LINES)
                val ticks = if (outgoing) card.size * 1.1f else 0f
                val bw = maxOf(f.width + card.size * 1.3f + ticks, card.indicatorW)
                val bh = maxOf(f.height + card.size * 0.84f, card.indicatorH)
                val reading = out.lastOrNull()?.let { maxOf(0.7f, it.text.length * 0.035f) } ?: 0f
                val spoken = cue.itemTimes.getOrNull(i)?.minus(Craft.LEAD)
                // Never before the card has opened, nor on top of the previous bubble's landing.
                val earliest = out.lastOrNull()?.let { it.land + 0.55f } ?: (at + 0.35f + TYPING)
                val land = maxOf(spoken ?: (prev + reading), earliest)
                out += Bubble(text, outgoing, f, bw, bh, land - TYPING, land)
                prev = land
            }
            return out
        }

        private fun voice(look: Look) = look.body.copy(weight = minOf(look.body.weight, 700), lineHeight = 1.32f)

        /** The card: smoked canvas over footage (it never depends on the picture), glass on a stage. */
        private fun body(cue: Cue, look: Look, card: Card, at: Float) = ShapeNode(
            ShapeSpec.Rect(card.w.anim, card.h.anim, (card.size * 0.9f * maxOf(look.roundness, 0.3f)).anim),
            fill = if (cue.overMedia) {
                Fill.Linear(listOf(look.canvas.copy(alpha = 0.9f), look.canvas.copy(alpha = 0.78f)), angle = 90f.anim)
            } else {
                Fill.Linear(listOf(look.card, look.card.copy(alpha = look.card.alpha * 0.5f)), angle = 70f.anim)
            },
            stroke = Stroke(look.cardRim.fill(), 2f),
            shadow = Shadow(Color.Black.copy(alpha = if (cue.overMedia) 0.38f else 0.22f), 0f, 18f, 40f),
            transform = Transform(
                x = card.cx.anim, y = card.cy.anim + Anim.tween(card.size * 1.2f, 0f, at, at + 0.6f, Easing.ExpoOut),
                scale = Anim.tween(0.92f, 1f, at, at + 0.6f, Easing.Land), opacity = Anim.tween(0f, 1f, at, at + 0.18f),
            ),
            name = "message-card",
        )

        /** A generic contact: an initial on a gradient disc, the name, a green "online" dot. */
        private fun header(cue: Cue, look: Look, fit: Fitter, card: Card, at: Float): List<Node> {
            val name = cue.label?.takeIf { it.isNotBlank() } ?: if (card.rtl) "پیام جدید" else "New message"
            val y = card.top + card.header * 0.5f
            val d = card.header * 0.56f
            val start = if (card.rtl) 1f else -1f
            val avatarX = card.cx + start * (card.w / 2f - card.pad - d / 2f)
            val nf = fit.fit(name, look.body.copy(weight = 800), card.size * 0.95f, card.w * 0.6f, d * 0.8f, maxLines = 1)
            val status = if (card.rtl) "آنلاین" else "online"
            val sf = fit.fit(status, look.body.copy(weight = 600), card.size * 0.62f, card.w * 0.4f, d * 0.5f, maxLines = 1)
            val textEdge = avatarX - start * (d / 2f + card.size * 0.55f)
            val show = Anim.tween(0f, 1f, at + 0.15f, at + 0.4f)
            val initial = name.first { !it.isWhitespace() }.toString()
            val inf = fit.fit(initial, look.headline, d * 0.5f, d * 0.8f, d * 0.8f, maxLines = 1)
            val dot = sf.type.size * 0.55f
            val statusX = textEdge - start * (dot + sf.type.size * 0.35f + sf.width / 2f)
            return listOf(
                ShapeNode(
                    ShapeSpec.Ellipse(d.anim, d.anim), fill = Fill.Linear(listOf(look.hot, look.accent), angle = 45f.anim),
                    transform = Transform(x = avatarX.anim, y = y.anim, scale = Anim.tween(0f, 1f, at + 0.1f, at + 0.55f, Easing.Spring(0.5f, 2.2f))),
                    name = "message-avatar",
                ),
                TextNode(
                    initial, inf.type, look.onAccent.fill(), rtl = card.rtl,
                    transform = Transform(x = avatarX.anim, y = y.anim, opacity = show), name = "message-initial",
                ),
                TextNode(
                    name, nf.type, look.ink.fill(), rtl = card.rtl, animators = listOf(rise(at + 0.15f)),
                    transform = Transform(x = (textEdge - start * nf.width / 2f).anim, y = (y - nf.height * 0.28f).anim), name = "message-name",
                ),
                ShapeNode(
                    ShapeSpec.Ellipse(dot.anim, dot.anim), fill = Color(ONLINE).fill(),
                    transform = Transform(x = (textEdge - start * dot / 2f).anim, y = (y + nf.height * 0.36f).anim, opacity = show), name = "message-online",
                ),
                TextNode(
                    status, sf.type, look.muted.let { if (cue.overMedia) look.ink.copy(alpha = 0.7f) else it }.fill(), rtl = card.rtl,
                    transform = Transform(x = statusX.anim, y = (y + nf.height * 0.36f).anim, opacity = show), name = "message-status",
                ),
                ShapeNode(
                    ShapeSpec.Rect((card.w - card.pad * 2f).anim, 1.5f.anim), fill = look.ink.copy(alpha = 0.12f).fill(),
                    transform = Transform(x = card.cx.anim, y = (card.top + card.header).anim, scaleX = Anim.tween(0f, 1f, at + 0.1f, at + 0.6f, Easing.ExpoOut)),
                    name = "message-rule",
                ),
            )
        }

        /**
         * The thread, clipped to its area with a soft top edge: every bubble's bottom rides up
         * as the ones after it arrive (first by the typing indicator, then by the full bubble).
         */
        private fun thread(look: Look, card: Card, bubbles: List<Bubble>): Group {
            val nodes = mutableListOf<Node>()
            for ((j, b) in bubbles.withIndex()) {
                val bottom = AnimBuilder(card.areaBottom, b.typing)
                for (k in j + 1 until bubbles.size) {
                    val next = bubbles[k]
                    bottom.hold(next.typing)
                    bottom.by(bottom.value - card.indicatorH - card.size * GAP, SCROLL, Easing.ExpoOut)
                    bottom.hold(next.land)
                    bottom.by(bottom.value - (next.h - card.indicatorH), SCROLL, Easing.ExpoOut)
                }
                nodes += bubble(look, card, b, bottom.build())
            }
            val areaH = card.areaBottom - card.areaTop + card.size * 0.4f
            val clip = ShapeNode(
                ShapeSpec.Rect((card.w).anim, areaH.anim),
                fill = Fill.Linear(listOf(Color.Transparent, Color.White, Color.White), angle = 90f.anim, stops = listOf(0f, 0.14f, 1f)),
                transform = Transform(x = card.cx.anim, y = (card.areaTop + areaH / 2f - card.size * 0.1f).anim),
            )
            return Group(nodes, mask = Mask(clip), name = "message-thread")
        }

        /**
         * One message, built around its bottom outer corner (where the tail is): typing dots, then
         * the bubble springs to size from that corner, then the words and, on a reply, read ticks.
         */
        private fun bubble(look: Look, card: Card, b: Bubble, bottom: Anim): Group {
            // Incoming sits on the reading-start side; the reply on the other.
            val onRight = card.rtl != b.outgoing
            val inward = if (onRight) -1f else 1f
            // Solid, not translucent: the corner, tail and body overlap and must read as one shape.
            val fill = if (b.outgoing) look.accent else androidx.compose.ui.graphics.lerp(look.canvas, look.ink, INCOMING)
            val ink = if (b.outgoing) look.onAccent else look.ink
            val r = minOf(card.size * 0.95f * maxOf(look.roundness, 0.35f), card.indicatorH / 2f)
            val morph = b.land
            // Local space: the corner sits at (ox, oy) of a generous box, so the pop scales around it.
            val ox = card.w
            val oy = card.h
            fun at(dx: Float, dy: Float) = Transform(x = (ox + dx).anim, y = (oy + dy).anim)
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(
                ShapeSpec.Rect(
                    anim(card.indicatorW, morph) { to(b.w, morph + 0.42f, Easing.BackOut(1.3f)) },
                    anim(card.indicatorH, morph) { to(b.h, morph + 0.42f, Easing.BackOut(1.3f)) }, r.anim,
                ),
                fill = fill.fill(), transform = at(0f, 0f).copy(anchorX = if (onRight) 1f else 0f, anchorY = 1f),
                name = if (b.outgoing) "message-reply" else "message-bubble",
            )
            // The tail: a squared corner and a small curl on the bottom outer corner.
            nodes += ShapeNode(ShapeSpec.Rect(r.anim, r.anim), fill = fill.fill(), transform = at(inward * r / 2f, -r / 2f), name = "message-corner")
            nodes += ShapeNode(
                ShapeSpec.Path(if (onRight) TAIL_RIGHT else TAIL_LEFT, TAIL_BOX, card.tail), fill = fill.fill(),
                transform = at(-inward * (card.tail / 2f - 1f), -card.tail / 2f), name = "message-tail",
            )
            val d = card.size * 0.32f
            for (i in 0 until 3) {
                val phase = b.typing + 0.1f + i * 0.09f
                val bounce = anim(0f, phase) { by(-d * 0.7f, 0.14f, Easing.SineInOut); by(0f, 0.14f, Easing.SineInOut); by(-d * 0.5f, 0.14f, Easing.SineInOut); by(0f, 0.14f, Easing.SineInOut) }
                val dot = at(inward * card.indicatorW / 2f + (i - 1) * d * 1.6f, -card.indicatorH / 2f)
                nodes += ShapeNode(
                    ShapeSpec.Ellipse(d.anim, d.anim), fill = ink.copy(alpha = 0.75f).fill(),
                    transform = dot.copy(y = dot.y + bounce, opacity = anim(0f, b.typing + 0.05f) { by(1f, 0.1f); hold(morph); by(0f, 0.08f) }),
                    end = morph + 0.1f, name = "message-dot",
                )
            }
            // In the bubble's own box: text from the reading-start side, ticks in the reading-end corner.
            val left = if (onRight) -b.w else 0f
            val padX = card.size * 0.65f
            val textX = if (card.rtl) left + b.w - padX - b.fitted.width / 2f else left + padX + b.fitted.width / 2f
            nodes += words(card, b, at(textX, -b.h / 2f), ink)
            if (b.outgoing) {
                val s = card.size * 0.62f
                val tickX = if (card.rtl) left + padX * 0.8f + s / 2f else left + b.w - padX * 0.8f - s * 0.82f
                nodes += listOf(0f, 1f).map { k ->
                    val t = morph + 0.35f + k * 0.35f
                    ShapeNode(
                        ShapeSpec.Path(Icons.path("check")!!, Icons.VIEWPORT, s), stroke = Stroke(ink.copy(alpha = 0.75f).fill(), s * 0.11f),
                        trimEnd = Anim.tween(0f, 1f, t, t + 0.25f, Easing.ExpoOut), transform = at(tickX + k * s * 0.32f, -card.size * 0.6f), start = t, name = "message-tick",
                    )
                }
            }
            val edge = if (onRight) card.areaRight else card.areaLeft
            return Group(
                nodes, width = ox * 2f, height = oy * 2f,
                transform = Transform(
                    x = edge.anim, y = bottom,
                    scale = anim(0.6f, b.typing) { by(1f, 0.35f, Easing.Spring(0.5f, 2.2f)); hold(morph); by(1.04f, 0.1f, Easing.ExpoOut); by(1f, 0.35f, Easing.Land) },
                    opacity = Anim.tween(0f, 1f, b.typing, b.typing + 0.12f),
                ),
                start = b.typing - 0.02f, name = "message-item",
            )
        }

        /** The words: they rise in as the bubble opens; long ones type on. */
        private fun words(card: Card, b: Bubble, place: Transform, ink: Color): Node {
            val chars = b.text.count { !it.isWhitespace() }
            val reveal = if (chars > LONG) {
                TextAnimator(TextUnit.Char, UnitState(opacity = 0f), at = b.land + 0.15f, duration = 0.08f, stagger = minOf(1.4f, chars * 0.02f) / chars, ease = Easing.Standard)
            } else {
                TextAnimator(TextUnit.Word, UnitState(dy = 0.35f, opacity = 0f), at = b.land + 0.12f, duration = 0.45f, stagger = 0.04f, ease = Easing.ExpoOut)
            }
            return TextNode(
                b.text, b.fitted.type, ink.fill(), maxWidth = b.fitted.width + 1f, align = TextAlign.Start, rtl = card.rtl, animators = listOf(reveal),
                transform = place, start = b.land, name = "message-text",
            )
        }

        /** The whole card lifts and fades out together at the end of its cue. */
        private fun group(cue: Cue, children: List<Node>): Group {
            val exitAt = cue.out - EXIT
            return Group(
                children,
                transform = Transform(
                    anchorX = 0f, anchorY = 0f,
                    y = anim(0f, exitAt) { by(-28f, EXIT, Easing.Exit) },
                    opacity = anim(1f, exitAt) { by(0f, EXIT, Easing.Exit) },
                ),
                start = cue.at - Craft.LEAD - 0.05f, end = cue.out + 0.02f, name = name,
            )
        }
    }

    private fun rise(at: Float) = TextAnimator(TextUnit.Word, UnitState(dy = 1.3f), at = at, duration = 0.6f, stagger = 0.06f, ease = Easing.ExpoOut, clipToLine = true)

    private const val MAX_BUBBLES = 6
    private const val MAX_LINES = 6
    /** A bubble hugs its text up to this share of the thread width. */
    private const val MAX_WIDTH = 0.78f
    /** Seconds the typing indicator shows before it becomes the bubble. */
    private const val TYPING = 0.45f
    private const val SCROLL = 0.35f
    private const val GAP = 0.32f
    private const val LONG = 60
    private const val EXIT = 0.28f
    private const val INCOMING = 0.16f
    /** The universal "online" green: a status colour, not a brand or look colour. */
    private const val ONLINE = 0xFF3DDC84
    private const val TAIL_BOX = 24f
    /** A curl leaving the bubble's bottom corner to the right (and its mirror). */
    private const val TAIL_RIGHT = "M0 6C1 15 6 21 20 24H0Z"
    private const val TAIL_LEFT = "M24 6C23 15 18 21 4 24H24Z"

    val all: List<Recipe> = listOf(Message)
}
