package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.VectorNode
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.visual.VectorIcon

/**
 * Things people talk about — a stethoscope, an engine, a pizza, a house — as stickers from the
 * visual vocabulary. Domain-free: the director names the object, the recipe does the rest.
 */
object ObjectRecipes {

    /** Idle float after landing: a slow, small bob so a held object never looks frozen. */
    private fun float(from: Float, until: Float, px: Float, seed: Int): Anim =
        Anim.Noise(seed, 0.45f, anim(0f, from) { by(px, 0.6f); hold(until) })

    /**
     * One sticker for the object being talked about: it springs in with a twist while its parts
     * assemble, casts a soft shadow, floats while it is discussed, and pops out.
     */
    val Sticker = object : Recipe("object", Kind.Element) {
        override val preferredHeight = 0.24f
        override val minHold = 1.4f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val icon = cue.visual ?: cue.itemVisuals.firstOrNull() ?: return Built(emptyList())
            val at = cue.at - Craft.LEAD
            val label = cue.label ?: cue.text.takeIf { it.isNotBlank() }
            val labelH = if (label != null) cue.height * 0.2f else 0f
            val size = minOf(cue.height - labelH, cue.width * 0.5f) * 0.92f
            val y = cue.y - labelH / 2f
            val nodes = sticker(icon, cue.x, y, size, at, cue.out, look, cue.seed)
            if (label != null) {
                val lf = fit.fit(label, look.headline.copy(weight = 900), labelH * 0.7f, cue.width * 0.9f, labelH, maxLines = 1)
                nodes += TextNode(
                    label, lf.type, look.ink.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, lf.type.size),
                    animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.3f), at = at + 0.25f, duration = 0.6f, stagger = 0.06f, ease = Easing.ExpoOut, clipToLine = true)),
                    transform = Transform(x = cue.x.anim, y = (y + size / 2f + labelH * 0.55f).anim), name = "sticker-label",
                )
            }
            return Built(listOf(exitGroup(cue, nodes, name)), sfx = listOf(Sfx(cue.at, SfxKind.Pop, 0.7f)))
        }
    }

    /**
     * Several objects in a row, each appearing as it is named (ingredients, symptoms, car parts,
     * steps of a routine), assembling in place with a little stagger of their own.
     */
    val Row = object : Recipe("objects", Kind.Element) {
        override val preferredHeight = 0.24f
        override val minHold = 2f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val icons = cue.itemVisuals.withIndex().filter { it.value != null }.take(MAX_ROW)
            if (icons.isEmpty()) return Built(emptyList())
            val n = icons.size
            val labelH = cue.height * 0.18f
            val gap = cue.width * 0.04f
            val size = minOf((cue.width - gap * (n - 1)) / n, cue.height - labelH)
            val rowW = n * size + (n - 1) * gap
            val nodes = mutableListOf<Node>()
            for ((k, iv) in icons.withIndex()) {
                val (i, icon) = iv
                val offset = k * (size + gap) + size / 2f
                val x = if (cue.rtl) cue.x + rowW / 2f - offset else cue.x - rowW / 2f + offset
                val y = cue.y - labelH / 2f
                val t0 = (cue.itemTimes.getOrNull(i) ?: (cue.at + k * 0.35f)) - Craft.LEAD
                nodes += sticker(icon!!, x, y, size * 0.86f, t0, cue.out, look, cue.seed + i)
                val name = cue.items.getOrNull(i)
                if (name != null) {
                    val lf = fit.fit(name, look.body.copy(weight = 800), labelH * 0.55f, size * 1.2f, labelH, maxLines = 1)
                    nodes += TextNode(
                        name, lf.type, look.ink.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, lf.type.size),
                        transform = Transform(x = x.anim, y = (y + size / 2f + labelH * 0.45f).anim, opacity = Anim.tween(0f, 1f, t0 + 0.25f, t0 + 0.5f)),
                        name = "objects-label",
                    )
                }
            }
            val sfx = icons.mapIndexed { k, iv -> Sfx((cue.itemTimes.getOrNull(iv.index) ?: (cue.at + k * 0.35f)), SfxKind.Pop, 0.5f) }
            return Built(listOf(exitGroup(cue, nodes, name)), sfx = sfx)
        }
    }

    private fun sticker(icon: VectorIcon, x: Float, y: Float, size: Float, t0: Float, out: Float, look: Look, seed: Int): MutableList<Node> {
        val landed = t0 + 0.7f
        val bob = float(landed, out, size * 0.03f, seed)
        val nodes = mutableListOf<Node>()
        // A soft contact shadow under the object grounds it.
        nodes += ShapeNode(
            ShapeSpec.Ellipse((size * 0.7f).anim, (size * 0.12f).anim),
            fill = Fill.Radial(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)),
            transform = Transform(
                x = x.anim, y = (y + size * 0.52f).anim, scale = Anim.tween(0.2f, 1f, t0 + 0.1f, t0 + 0.6f, Easing.ExpoOut) + bob * (-0.004f).anim,
                opacity = Anim.tween(0f, 1f, t0 + 0.1f, t0 + 0.4f),
            ),
            name = "sticker-shadow",
        )
        nodes += VectorNode(
            icon, size, tint = look.ink.anim, assemble = Anim.tween(0f, 1f, t0, t0 + 0.75f, Easing.Linear),
            shadow = Shadow(Color.Black.copy(alpha = 0.3f), 0f, size * 0.04f, size * 0.08f),
            transform = Transform(
                x = x.anim, y = y.anim + bob,
                scale = anim(0.3f, t0) { by(1f, 0.65f, Easing.Spring(0.5f, 2.2f)) },
                rotation = anim(if (seed % 2 == 0) -14f else 14f, t0) { by(0f, 0.7f, Easing.Spring(0.45f, 1.8f)) } + Anim.Noise(seed + 3, 0.35f, anim(0f, landed) { by(2.5f, 0.6f); hold(out) }),
                opacity = Anim.tween(0f, 1f, t0, t0 + 0.08f),
            ),
            name = "sticker-${icon.name}",
        )
        return nodes
    }

    /** Leaves with a pop: a quick swell, then shrinking away, around the slot's centre. */
    private fun exitGroup(cue: Cue, children: List<Node>, name: String): Group {
        val exitAt = cue.out - 0.3f
        // Bounds of twice the centre put the group's pivot exactly on the slot centre.
        return Group(
            children,
            transform = Transform(
                anchorX = 0.5f, anchorY = 0.5f, x = cue.x.anim, y = cue.y.anim,
                scale = anim(1f, exitAt) { by(1.06f, 0.1f, Easing.ExpoOut); by(0.6f, 0.2f, Easing.Exit) },
                opacity = anim(1f, exitAt + 0.1f) { by(0f, 0.2f, Easing.Exit) },
            ),
            width = cue.x * 2f, height = cue.y * 2f,
            start = cue.at - 0.6f, end = cue.out + 0.02f, name = name,
        )
    }

    private const val MAX_ROW = 5

    val all: List<Recipe> = listOf(Sticker, Row)
}
