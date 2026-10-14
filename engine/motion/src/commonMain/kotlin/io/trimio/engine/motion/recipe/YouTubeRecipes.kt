package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Fit
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.MediaNode
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * The devices of a long YouTube video (docs/research/YOUTUBE_LONGFORM.md): the subscribe ask, the channel's
 * watermark and the chapter card. All three sit in a corner of a 16:9 frame and stay small: a long-form
 * viewer watches the speaker, not the graphics.
 */
object YouTubeRecipes {
    /**
     * The subscribe ask: a red pill with a bell slides up, a pointer comes in and taps it, the pill turns
     * grey («سابسکرایب شد»), the bell rings. [Cue.text] replaces the call, [Cue.label] the done state.
     */
    val Subscribe = object : Recipe("yt-subscribe", Kind.Element) {
        override val preferredHeight = 0.12f
        override val minHold = 3.2f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val call = cue.text.ifBlank { if (cue.rtl) "سابسکرایب" else "SUBSCRIBE" }
            val done = cue.label?.takeIf { it.isNotBlank() } ?: if (cue.rtl) "سابسکرایب شد" else "SUBSCRIBED"
            val h = minOf(cue.height * 0.62f, cue.width * 0.12f)
            val tf = fit.fit(call, look.headline, h * 0.46f, cue.width * 0.4f, h, maxLines = 1)
            val df = fit.fit(done, look.headline, h * 0.42f, cue.width * 0.4f, h, maxLines = 1)
            val w = maxOf(tf.width, df.width) + h * 1.1f
            val t0 = cue.at - Craft.LEAD
            val tap = t0 + TAP_AFTER
            val y = cue.y
            val enter = Transform(
                x = cue.x.anim, y = y.anim + Anim.tween(h * 0.9f, 0f, t0, t0 + 0.45f, Easing.ExpoOut),
                opacity = Anim.tween(0f, 1f, t0, t0 + 0.18f), scale = anim(1f, tap) { by(0.92f, 0.08f, Easing.ExpoOut); by(1f, 0.3f, Easing.Land) },
            )
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(
                ShapeSpec.Rect(w.anim, h.anim, (h / 2f).anim), fill = Fill.Solid(ColorAnim.tween(RED, DONE_GREY, tap, tap + 0.18f)),
                shadow = Shadow(Color.Black.copy(alpha = 0.4f), 0f, h * 0.12f, h * 0.4f), transform = enter, name = "subscribe-pill",
            )
            fun label(text: String, f: Fitted, shown: Anim, n: String) = Craft.onBody(
                TextNode(
                    text, f.type, Color.White.fill(), rtl = cue.rtl,
                    transform = Transform(x = (cue.x + (if (cue.rtl) h * 0.28f else -h * 0.28f)).anim, y = enter.y, opacity = enter.opacity * shown, scale = enter.scale), name = n,
                ),
            )
            nodes += label(call, tf, Anim.tween(1f, 0f, tap + 0.05f, tap + 0.2f), "subscribe-call")
            nodes += label(done, df, Anim.tween(0f, 1f, tap + 0.12f, tap + 0.3f), "subscribe-done")
            // The bell on the end side of the pill, ringing once the tap lands.
            val bellX = cue.x + (if (cue.rtl) -1f else 1f) * (w / 2f - h * 0.5f)
            val ring = Anim.keys(List(RINGS * 2 + 1) { k -> Anim.Key(tap + 0.2f + k * 0.07f, if (k == RINGS * 2) 0f else if (k % 2 == 0) 18f else -18f, Easing.SineInOut) })
            nodes += ShapeNode(
                ShapeSpec.Path(Icons.path("bell")!!, Icons.VIEWPORT, h * 0.52f), stroke = Stroke(Color.White.fill(), h * 0.06f),
                transform = Transform(x = bellX.anim, y = enter.y, opacity = enter.opacity, rotation = ring, anchorY = 0.15f), name = "subscribe-bell",
            )
            // The pointer comes from below the pill and taps its middle.
            val pointer = Transform(
                x = (cue.x + h * 0.1f).anim + Anim.tween(w * 0.35f, 0f, tap - 0.55f, tap, Easing.QuintOut),
                y = (y + h * 0.42f).anim + Anim.tween(h * 1.4f, 0f, tap - 0.55f, tap, Easing.QuintOut),
                opacity = Anim.tween(0f, 1f, tap - 0.55f, tap - 0.35f) * Anim.tween(1f, 0f, tap + 0.6f, tap + 0.85f),
                scale = anim(1f, tap) { by(0.82f, 0.07f, Easing.ExpoOut); by(1f, 0.2f, Easing.Land) },
            )
            nodes += ShapeNode(
                ShapeSpec.Path(Icons.path("pointer")!!, Icons.VIEWPORT, h * 0.7f), fill = Color.White.fill(), stroke = Stroke(Color.Black.copy(alpha = 0.7f).fill(), h * 0.03f),
                transform = pointer, start = tap - 0.6f, end = tap + 0.9f, name = "subscribe-pointer",
            )
            return Built(listOf(group(cue, nodes, name)), sfx = listOf(Sfx(t0, SfxKind.Swish, 0.35f), Sfx(tap, SfxKind.Click, 0.7f), Sfx(tap + 0.2f, SfxKind.Tick, 0.4f)))
        }
    }

    /**
     * The channel's mark in a corner for the whole video ([Cue.media], the creator's own logo): small, a little
     * transparent, fading in after the opening and out before the end screen; never animated beyond that.
     */
    val Watermark = object : Recipe("yt-watermark", Kind.Element) {
        override val preferredHeight = 0.09f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val media = cue.media ?: return Built(emptyList())
            val h = cue.height
            val w = minOf(h * cue.aspect.coerceIn(1f, 3f), cue.width)
            val shown = Anim.tween(0f, OPACITY, cue.at, cue.at + 0.8f) * Anim.tween(1f, 0f, cue.out - 0.6f, cue.out)
            val node = MediaNode(media, w, h, Fit.Contain, transform = Transform(x = cue.x.anim, y = cue.y.anim, opacity = shown), start = cue.at, end = cue.out, name = name)
            return Built(listOf(node))
        }
    }

    /**
     * A chapter's card as the chapter starts: a thin accent rule, the chapter's number small above its title,
     * sliding in from the reading side and leaving the same way; [Cue.label] is the number line («فصل ۲»).
     */
    val Chapter = object : Recipe("yt-chapter", Kind.Element) {
        override val preferredHeight = 0.16f
        override val minHold = 2.6f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val t0 = cue.at - Craft.LEAD
            val tf = fit.fit(cue.text, look.headline, cue.height * 0.42f, cue.width * 0.9f, cue.height * 0.6f, maxLines = 2)
            val label = cue.label?.takeIf { it.isNotBlank() }
            val lf = label?.let { fit.fit(it, look.caption, cue.height * 0.2f, cue.width * 0.8f, cue.height * 0.3f, maxLines = 1) }
            val side = if (cue.rtl) 1f else -1f
            val edge = cue.x + side * cue.width * 0.45f
            val slide = Anim.tween(side * cue.width * 0.2f, 0f, t0, t0 + 0.55f, Easing.ExpoOut)
            val shown = Anim.tween(0f, 1f, t0, t0 + 0.25f)
            val anchor = if (cue.rtl) 1f else 0f
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(
                ShapeSpec.Rect((cue.height * 0.05f).anim, (tf.height + (lf?.height ?: 0f)).anim, 0f.anim), fill = look.accent.fill(),
                transform = Transform(x = (edge + side * cue.height * 0.06f).anim, y = cue.y.anim, scaleY = Anim.tween(0f, 1f, t0, t0 + 0.4f, Easing.ExpoOut)), name = "chapter-rule",
            )
            lf?.let { f ->
                nodes += TextNode(
                    label, f.type, look.accent.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, f.type.size),
                    transform = Transform(x = edge.anim + slide, y = (cue.y - tf.height / 2f).anim, anchorX = anchor, opacity = shown), name = "chapter-number",
                )
            }
            nodes += TextNode(
                cue.text, tf.type, look.ink.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, tf.type.size),
                animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.2f), at = t0 + 0.1f, duration = 0.55f, stagger = 0.06f, ease = Easing.ExpoOut, clipToLine = true)),
                transform = Transform(x = edge.anim + slide, y = (cue.y + (lf?.height ?: 0f) / 2f).anim, anchorX = anchor, opacity = shown), name = "chapter-title",
            )
            return Built(listOf(group(cue, nodes, name)), sfx = listOf(Sfx(t0, SfxKind.Whoosh, 0.3f)))
        }
    }

    val all: List<Recipe> = listOf(Subscribe, Watermark, Chapter)

    /** The whole device fades out together at the end of its cue. */
    private fun group(cue: Cue, children: List<Node>, name: String): Group {
        val exitAt = cue.out - 0.3f
        return Group(
            children,
            transform = Transform(anchorX = 0f, anchorY = 0f, opacity = anim(1f, exitAt) { by(0f, 0.3f, Easing.Exit) }),
            start = cue.at - 0.8f, end = cue.out + 0.02f, name = name,
        )
    }

    /** YouTube's own red, and the grey of a subscribed button. */
    private val RED = Color(0xFFFF0033)
    private val DONE_GREY = Color(0xFF3F3F3F)
    private const val TAP_AFTER = 1.2f
    private const val RINGS = 3
    private const val OPACITY = 0.78f
}
