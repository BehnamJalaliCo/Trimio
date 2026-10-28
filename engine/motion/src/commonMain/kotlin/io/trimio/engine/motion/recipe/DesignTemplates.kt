package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
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
 * A designer's template shelf: ten hand-crafted looks (the CapCut lesson - a human designs the
 * moment, the engine fills it). Each carries its own signature, its own three-layer choreography
 * (primary travel, lagged follow-through, fade overlapping the travel) and its timing from the
 * P1 design tokens. The director names one the way a cut is chosen in After Effects: by the
 * moment it was designed for.
 *
 * Craft floors every template keeps: entry 200-350 ms, exit at 65-75% of it, stagger 30-60 ms,
 * overshoot in band (premium 0-3%, playful 5-15%), two to four words a shot, Persian set right
 * to left with the entrance from the right, and nothing cheap (no glow, no particle, no shake).
 */
object DesignTemplates {

    // ------------------------------------------------------------------ timing tokens

    /** Designed entry durations (s): the 200-350 ms band, one per hand. */
    private const val ENTRY_SNAP = 0.22f
    private const val ENTRY_LIFT = 0.28f
    private const val ENTRY_GLIDE = 0.34f

    /** Exits run 65-75% of the entry - the craft band, never the same length. */
    private const val EXIT_SHARE = 0.7f

    /** Word/row stagger in the 30-60 ms band. */
    private const val STAGGER_TIGHT = 0.035f
    private const val STAGGER_SOFT = 0.055f

    /** Overshoot springs: premium rings a whisper; playful a real land. */
    private val SpringPremium = Easing.Spring(0.8f, 2.0f)
    private val SpringPlayful = Easing.Spring(0.55f, 2.2f)

    /** The fade overlaps the travel by this much (s) so the two never share a first frame. */
    private const val FADE_LEAD = 0.05f

    /** Follow-through lag (s) and the secondary channel damping. */
    private const val FOLLOW_LAG = 0.06f
    private const val FOLLOW_DAMP = 0.65f

    private fun exitSpan(entry: Float) = entry * EXIT_SHARE

    /** The house three-layer entrance: primary + lagged trail + overlapping fade. */
    private fun layers(at: Float, entry: Float, primary: Anim, trailPeak: Float = 1.2f): Transform {
        val fade = anim(0f, at - FADE_LEAD - entry) { by(1f, FADE_LEAD + entry * 2.2f, Easing.Linear) }
        val trail = Anim.Follow(
            anim(0f, at) {
                by(trailPeak, entry * 0.4f, Easing.ExpoOut)
                by(0f, entry * 0.6f, Easing.ExpoOut)
            },
            FOLLOW_LAG,
            FOLLOW_DAMP,
        )
        return Transform(x = primary, opacity = fade, blur = trail)
    }

    /** The house exit: lift, soften and fade over the exit window, gone at [out]. */
    private fun exitOf(out: Float, entry: Float): Transform {
        val span = exitSpan(entry)
        val t = out - span
        return Transform(
            y = anim(0f, t) { by(-22f, span, Easing.Exit) },
            opacity = anim(1f, t) { by(0f, span, Easing.Exit) },
            blur = anim(0f, t) { by(2.5f, span, Easing.Exit) },
        )
    }

    /** Wraps children in the entrance/exit layers and keeps the group alive to [out]. */
    private fun staged(
        cue: Cue,
        entry: Float,
        primary: Anim,
        children: List<Node>,
        name: String,
        at: Float,
        trailPeak: Float = 1.2f,
    ): Group {
        val enter = layers(at, entry, primary, trailPeak)
        val leave = exitOf(cue.out, entry)
        val box = Box.around(cue.x, cue.y, cue.width, cue.height)
        val pad = maxOf(40f, minOf(box.width, box.height) * 0.12f)
        val b = Box(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        val inner = Group(
            children,
            Transform(x = (-b.left).anim, y = (-b.top).anim, anchorX = 0f, anchorY = 0f),
            name = "$name-body",
        )
        val cx = b.centerX
        val cy = b.centerY
        val t = enter.copy(
            x = cx.anim + enter.x,
            y = cy.anim + leave.y,
            anchorX = 0.5f,
            anchorY = 0.5f,
            opacity = enter.opacity * leave.opacity,
            blur = enter.blur + leave.blur,
        )
        return Group(
            listOf(inner),
            transform = t,
            width = b.width,
            height = b.height,
            start = at - 0.05f,
            end = cue.out + 0.02f,
            name = name,
        )
    }

    private fun edge(cue: Cue): Float = if (cue.rtl) cue.x + cue.width / 2f else cue.x - cue.width / 2f

    private fun dir(cue: Cue): Float = if (cue.rtl) -1f else 1f

    // ================================================================== lower-thirds

    /**
     * Signature: a thin accent bar on the reading-start edge that draws up as the name rises
     * behind its mask; a small tick lands under the role. Simple, quiet, always readable.
     */
    val NameBar = object : Recipe("name-bar", Kind.Element) {
        override val preferredHeight = 0.12f
        override val minHold = 2.4f

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val name = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            val role = cue.label?.takeIf { cue.text.isNotBlank() }
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_LIFT
            val d = dir(cue)
            val e = edge(cue)
            val barW = maxOf(6f, cue.height * 0.07f)
            val nameF = fit.fit(name, look.headline, cue.height * 0.42f, cue.width * 0.82f, cue.height * 0.5f, maxLines = 1)
            val roleF = role?.let { fit.fit(it, look.body, nameF.type.size * 0.42f, cue.width * 0.82f, cue.height * 0.28f, maxLines = 1) }
            val textEdge = e + d * (barW * 2.2f)
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(
                ShapeSpec.Rect(barW.anim, Anim.tween(0f, cue.height * 0.88f, at, at + entry, Easing.ExpoOut), (barW / 2f).anim),
                fill = look.accent.fill(),
                transform = Transform(x = e.anim, y = cue.y.anim),
                name = "name-bar",
            )
            nodes += TextNode(
                name, nameF.type, look.ink.fill(), rtl = cue.rtl,
                animators = listOf(
                    TextAnimator(TextUnit.Word, UnitState(dy = 1.3f), at = at + 0.06f, duration = entry * 1.6f, stagger = STAGGER_TIGHT, ease = Easing.ExpoOut, clipToLine = true),
                ),
                shadow = Craft.shadow(cue, look, nameF.type.size),
                transform = Transform(x = (textEdge + d * nameF.width / 2f).anim, y = (cue.y - cue.height * 0.12f).anim),
                name = "name",
            )
            if (role != null && roleF != null) {
                nodes += TextNode(
                    role, roleF.type, Craft.secondary(cue, look).fill(), rtl = cue.rtl,
                    shadow = Craft.shadow(cue, look, roleF.type.size),
                    transform = Transform(
                        x = (textEdge + d * roleF.width / 2f).anim + Anim.tween(-d * 24f, 0f, at + 0.12f, at + 0.12f + entry * 1.4f, Easing.ExpoOut),
                        y = (cue.y + cue.height * 0.24f).anim,
                        opacity = Anim.tween(0f, 1f, at + 0.12f, at + 0.12f + entry * 1.2f, Easing.ExpoOut),
                    ),
                    name = "role",
                )
                nodes += ShapeNode(
                    ShapeSpec.Rect((roleF.width * 0.35f).anim, 3f.anim, 1.5f.anim), fill = look.accent.fill(),
                    transform = Transform(
                        x = (textEdge + d * roleF.width * 0.18f).anim, y = (cue.y + cue.height * 0.4f).anim,
                        scaleX = Anim.tween(0f, 1f, at + 0.22f, at + 0.22f + entry, Easing.ExpoOut),
                    ),
                    name = "name-tick",
                )
            }
            val travel = Anim.tween(d * 36f, 0f, at, at + entry, Easing.ExpoOut)
            val minX = minOf(e, e + d * (barW * 2.2f + maxOf(nameF.width, roleF?.width ?: 0f)))
            val maxX = maxOf(e, e + d * (barW * 2.2f + maxOf(nameF.width, roleF?.width ?: 0f)))
            val box = Box(minX, cue.y - cue.height * 0.5f, maxX, cue.y + cue.height * 0.5f)
            return Built(
                listOf(staged(cue, entry, travel, nodes, name, at)),
                sfx = listOf(Sfx(cue.at, SfxKind.Swish, 0.45f)),
                box = box,
            )
        }
    }

    /**
     * Signature: a soft card plate with an accent side-stripe, sliding in from the reading start
     * with a premium spring; the name and role sit in hierarchy on it.
     */
    val NamePlate = object : Recipe("name-plate", Kind.Element) {
        override val preferredHeight = 0.14f
        override val minHold = 2.6f

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val name = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            val role = cue.label?.takeIf { cue.text.isNotBlank() }
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_GLIDE
            val d = dir(cue)
            val h = minOf(cue.height * 0.92f, cue.width * 0.2f)
            val nameF = fit.fit(name, look.headline, h * (if (role != null) 0.32f else 0.4f), cue.width * 0.72f, h * 0.48f, maxLines = 1)
            val roleF = role?.let { fit.fit(it, look.body, h * 0.2f, cue.width * 0.72f, h * 0.3f, maxLines = 1) }
            val stripeW = h * 0.12f
            val pad = h * 0.22f
            val w = stripeW + pad + maxOf(nameF.width, roleF?.width ?: 0f) + pad
            val slot = Box.around(cue.x, cue.y, cue.width, cue.height)
            val left = if (cue.rtl) slot.right - w else slot.left
            val plate = Box(left, cue.y - h / 2f, left + w, cue.y + h / 2f)
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(
                ShapeSpec.Rect(w.anim, h.anim, (h * 0.18f * look.roundness).anim),
                fill = if (cue.overMedia) look.canvas.copy(alpha = 0.92f).fill() else s.fill,
                stroke = Stroke(look.cardRim.fill(), 2f),
                shadow = Shadow(Color.Black.copy(alpha = if (Kit.light(look.canvas)) 0.18f else 0.36f), 0f, 14f, 32f),
                transform = Transform(x = plate.centerX.anim, y = plate.centerY.anim),
                name = "plate",
            )
            val stripeX = if (cue.rtl) plate.right - stripeW / 2f else plate.left + stripeW / 2f
            nodes += ShapeNode(
                ShapeSpec.Rect(stripeW.anim, (h * 0.72f).anim, (stripeW / 2f).anim), fill = look.accent.fill(),
                transform = Transform(x = stripeX.anim, y = plate.centerY.anim),
                name = "plate-stripe",
            )
            val textEdge = stripeX + d * (stripeW / 2f + pad * 0.6f)
            val ground = if (cue.overMedia) Kit.over(look.canvas.copy(alpha = 0.92f), look.canvas) else s.ground
            nodes += TextNode(
                name, nameF.type, (if (cue.overMedia) look.ink else ink).fill(), rtl = cue.rtl,
                animators = listOf(
                    TextAnimator(TextUnit.Word, UnitState(dy = 1.2f), at = at + 0.08f, duration = entry * 1.3f, stagger = STAGGER_TIGHT, ease = Easing.ExpoOut, clipToLine = true),
                ),
                shadow = Craft.shadow(cue, look, nameF.type.size),
                transform = Transform(
                    x = (textEdge + d * nameF.width / 2f).anim,
                    y = (if (role != null) plate.centerY - h * 0.12f else plate.centerY).anim,
                ),
                name = "name",
            )
            if (role != null && roleF != null) {
                nodes += TextNode(
                    role, roleF.type, (if (cue.overMedia) look.muted.copy(alpha = 0.95f) else Kit.quiet(look, ground)).fill(), rtl = cue.rtl,
                    shadow = Craft.shadow(cue, look, roleF.type.size),
                    transform = Transform(
                        x = (textEdge + d * roleF.width / 2f).anim,
                        y = (plate.centerY + h * 0.18f).anim,
                        opacity = Anim.tween(0f, 1f, at + 0.16f, at + 0.16f + entry, Easing.ExpoOut),
                    ),
                    name = "role",
                )
            }
            val travel = Anim.tween(-d * minOf(w * 0.55f, 90f), 0f, at, at + entry, SpringPremium)
            return Built(
                listOf(staged(cue, entry, travel, nodes, name, at, trailPeak = 0.8f)),
                sfx = listOf(Sfx(cue.at, SfxKind.Swish, 0.5f)),
                box = plate,
            )
        }
    }
