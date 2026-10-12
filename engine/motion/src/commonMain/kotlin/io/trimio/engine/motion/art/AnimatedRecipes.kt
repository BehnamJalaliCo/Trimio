package io.trimio.engine.motion.art

import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.recipe.Built
import io.trimio.engine.motion.recipe.Craft
import io.trimio.engine.motion.recipe.Cue
import io.trimio.engine.motion.recipe.Fitter
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Recipe
import io.trimio.engine.motion.recipe.Sfx
import io.trimio.engine.motion.recipe.SfxKind

/**
 * Beats built around an animated picture (the beat's `visual`, resolved by the director's picture
 * search to an animated emoji or a self-drawing line icon; a still icon works too):
 *
 * - `animated` — the picture plays in its slot, large, with the line's label: as a sticker over a
 *   label (signature) or beside it, the label on the reading side.
 * - `live-reaction` — a small reaction that pops beside what is said (a laugh, a wow, a fire) and drifts
 *   up as it fades, once or as a little burst of three.
 */
object AnimatedRecipes {

    val Animated = object : Recipe("animated", Kind.Element) {
        override val preferredHeight = 0.26f
        override val minHold = 1.6f
        override val variants = 2
        override val layouts = 2

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val icon = cue.visual ?: cue.itemVisuals.firstOrNull() ?: return Built(emptyList())
            val t0 = cue.at - Craft.LEAD
            val label = cue.label ?: cue.text.takeIf { it.isNotBlank() }
            val beside = cue.variant % 2 == 1 && label != null
            val nodes = mutableListOf<Node>()
            val box: Box
            if (beside) {
                val size = minOf(cue.height * 0.9f, cue.width * 0.36f)
                val gap = size * 0.12f
                val textW = cue.width - size - gap
                val lf = fit.fit(label!!, look.headline.copy(weight = 900), size * 0.34f, textW, cue.height * 0.8f, maxLines = 2)
                val rowW = size + gap + lf.width
                // The picture leads in reading order: right of the words in Persian, left in English.
                val iconX = if (cue.rtl) cue.x + rowW / 2f - size / 2f else cue.x - rowW / 2f + size / 2f
                val textX = if (cue.rtl) cue.x - rowW / 2f + lf.width / 2f else cue.x + rowW / 2f - lf.width / 2f
                nodes += AnimatedLayers.sticker(icon, iconX, cue.y, size, t0, cue.out, look, cue.seed, cue.overMedia, cue.rtl)
                nodes += label(label, lf.type, textX, cue.y, t0 + 0.2f, cue, look)
                box = Box.around(cue.x, cue.y, rowW, maxOf(size, lf.height))
            } else {
                val labelH = if (label != null) cue.height * 0.22f else 0f
                val size = minOf(cue.height - labelH, cue.width * 0.55f) * 0.92f
                val y = cue.y - labelH / 2f
                nodes += AnimatedLayers.sticker(icon, cue.x, y, size, t0, cue.out, look, cue.seed, cue.overMedia, cue.rtl)
                var w = size
                if (label != null) {
                    val lf = fit.fit(label, look.headline.copy(weight = 900), labelH * 0.7f, cue.width * 0.9f, labelH, maxLines = 1)
                    nodes += label(label, lf.type, cue.x, y + size / 2f + labelH * 0.55f, t0 + 0.25f, cue, look)
                    w = maxOf(w, lf.width)
                }
                box = Box.around(cue.x, cue.y, w, size + labelH)
            }
            return Built(listOf(exit(cue, nodes, name)), sfx = listOf(Sfx(cue.at, SfxKind.Pop, 0.7f)), box = box)
        }
    }

    val Reaction = object : Recipe("live-reaction", Kind.Element) {
        override val preferredHeight = 0.14f
        override val minHold = 1.2f
        override val variants = 2

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val icon = cue.visual ?: return Built(emptyList())
            val t0 = cue.at - Craft.LEAD
            val size = minOf(cue.height, cue.width * 0.4f) * 0.9f
            val burst = cue.variant % 2 == 1
            val copies = if (burst) BURST else 1
            val nodes = mutableListOf<Node>()
            for (k in 0 until copies) {
                val start = t0 + k * BURST_STEP
                val s = if (k == 0) size else size * (0.62f - 0.1f * k)
                val dx = if (k == 0) 0f else (if ((k + cue.seed) % 2 == 0) -1f else 1f) * size * (0.55f + 0.2f * k)
                val drift = anim(0f, start + 0.5f) { by(-size * (0.25f + 0.35f * k), (cue.out - start - 0.5f).coerceAtLeast(0.3f), Easing.SineInOut) }
                val layers = AnimatedLayers.sticker(icon, cue.x + dx, cue.y, s, start, cue.out, look, cue.seed + k, cue.overMedia, cue.rtl)
                nodes += Group(layers, transform = Transform(anchorX = 0f, anchorY = 0f, y = drift), name = "reaction-$k")
            }
            val box = Box.around(cue.x, cue.y, size * if (burst) 2.6f else 1f, size)
            val sfx = (0 until copies).map { k -> Sfx(cue.at + k * BURST_STEP, SfxKind.Pop, if (k == 0) 0.6f else 0.35f) }
            return Built(listOf(exit(cue, nodes, name)), sfx = sfx, box = box)
        }
    }

    private fun label(text: String, type: io.trimio.engine.motion.TypeSpec, x: Float, y: Float, at: Float, cue: Cue, look: Look) = TextNode(
        text, type, look.ink.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, type.size),
        animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.3f), at = at, duration = 0.6f, stagger = 0.06f, ease = Easing.ExpoOut, clipToLine = true)),
        transform = Transform(x = x.anim, y = y.anim), name = "animated-label",
    )

    /** Leaves with a small swell then shrinks away about the slot centre, gone at [Cue.out]. */
    private fun exit(cue: Cue, children: List<Node>, name: String): Group {
        val exitAt = cue.out - 0.3f
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

    private const val BURST = 3
    private const val BURST_STEP = 0.18f

    val all: List<Recipe> = listOf(Animated, Reaction)
}
