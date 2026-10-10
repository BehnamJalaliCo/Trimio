package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import kotlin.math.ceil

/** Brand moments: products named on camera appear as their own marks, with a signature entrance. */
object BrandRecipes {

    /**
     * App-icon tiles in each brand's colour. As a brand is named its tile flips in big above the
     * row (the hero spot): the mark traces its outline and fills, a glow in the brand colour
     * breathes out, a ring bursts. When the next brand is named it flies down into its place in
     * the row, so the row builds itself in the order they were said.
     */
    val Logos = object : Recipe("logos", Kind.Element) {
        override val preferredHeight = 0.3f
        override val minHold = 2.4f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val names = cue.items.ifEmpty { cue.text.split('|', '،', ',').map { it.trim() }.filter { it.isNotEmpty() } }.take(MAX_LOGOS)
            if (names.isEmpty()) return Built(emptyList())
            val marks = names.mapIndexed { i, n -> cue.marks.getOrNull(i) ?: BrandMark(n, n, look.hot, null) }
            val perRow = minOf(names.size, PER_ROW)
            val rows = ceil(names.size / perRow.toFloat()).toInt()
            val labelShare = 0.3f
            // The row(s) sit at the bottom of the slot; the hero spot fills the space above.
            val s = minOf(cue.width / (perRow + (perRow - 1) * GAP), cue.height / (rows * (1f + labelShare + GAP) + HERO))
            val gap = s * GAP
            val rowH = s * (1f + labelShare)
            val rowsTop = cue.y + cue.height / 2f - rows * rowH - (rows - 1) * gap
            val heroY = (cue.y - cue.height / 2f + rowsTop) / 2f
            val heroScale = minOf(HERO, (rowsTop - (cue.y - cue.height / 2f)) / s * 0.82f).coerceAtLeast(1f)
            val times = names.indices.map { i -> (cue.itemTimes.getOrNull(i) ?: (cue.at + i * 0.5f)) - Craft.LEAD }
            val nodes = mutableListOf<Node>()
            for (i in names.indices) {
                val r = i / perRow
                val k = i % perRow
                val inRow = minOf(perRow, names.size - r * perRow)
                val rowW = inRow * s + (inRow - 1) * gap
                val offset = k * (s + gap) + s / 2f
                val x = if (cue.rtl) cue.x + rowW / 2f - offset else cue.x - rowW / 2f + offset
                val y = rowsTop + r * (rowH + gap) + s / 2f
                // Leave the hero spot when the next brand is named; the last one leaves in time for the row to be read.
                val natural = minOf(times.getOrNull(i + 1) ?: (times[i] + 1.1f), times[i] + 1.4f).coerceAtLeast(times[i] + 0.75f)
                val leave = if (i == names.lastIndex) minOf(natural, cue.out - 1.1f).coerceAtLeast(times[i] + 0.6f) else natural
                nodes += tile(marks[i], Slot(x, y, cue.x, heroY, heroScale), s, times[i], leave, cue, look, fit)
            }
            val sfx = times.map { Sfx(it + Craft.LEAD, SfxKind.Pop, 0.6f) } + Sfx(times.first(), SfxKind.Shimmer, 0.4f)
            return Built(listOf(group(cue, nodes)), sfx = sfx)
        }

        /** Where a tile rests in the row, and the hero spot it enters at. */
        private inner class Slot(val x: Float, val y: Float, val heroX: Float, val heroY: Float, val heroScale: Float)

        private fun tile(mark: BrandMark, slot: Slot, s: Float, t0: Float, leave: Float, cue: Cue, look: Look, fit: Fitter): List<Node> {
            val lum = 0.2126f * mark.color.red + 0.7152f * mark.color.green + 0.0722f * mark.color.blue
            // Light brand colours sit on a dark tile as coloured marks; everything else is a white mark on colour.
            val tileColor = if (lum > LIGHT) Color(0xFF16130F) else mark.color
            val glyphColor = if (lum > LIGHT) mark.color else Color.White
            val glowColor = if (lum < DARK) Color.White else mark.color
            val land = leave + FLY
            val x = anim(slot.heroX, leave) { to(slot.x, land, Easing.ExpoInOut) }
            val y = anim(slot.heroY, leave) { to(slot.y, land, Easing.ExpoInOut) }
            val scale = anim(0.4f * slot.heroScale, t0) { to(slot.heroScale, t0 + 0.6f, Easing.Spring(0.55f, 2.1f)); hold(leave); to(1f, land, Easing.ExpoInOut) }
            val flip = Anim.tween(-95f, 0f, t0, t0 + 0.6f, Easing.Spring(0.62f, 1.8f))
            val shown = anim(0f, t0) { by(1f, 0.1f, Easing.Linear) }
            fun at(op: Anim = shown) = Transform(x = x, y = y, scale = scale, rotationY = flip, opacity = op, perspective = s * 6f)
            val nodes = mutableListOf<Node>()
            // A glow in the brand colour breathes out behind the hero, and fades as it joins the row.
            nodes += ShapeNode(
                ShapeSpec.Ellipse((s * 2.1f).anim, (s * 2.1f).anim),
                fill = Fill.Radial(listOf(glowColor.copy(alpha = 0.5f), glowColor.copy(alpha = 0.16f), Color.Transparent)),
                transform = Transform(
                    x = x, y = y, scale = scale,
                    opacity = anim(0f, t0 + 0.3f) { by(1f, 0.25f, Easing.ExpoOut); by(0.55f, 0.5f); hold(leave - 0.05f); to(0f, leave + 0.12f, Easing.Standard) },
                ),
                name = "logo-glow",
            )
            nodes += ShapeNode(
                ShapeSpec.Rect(s.anim, s.anim, (s * RADIUS).anim), fill = tileColor.fill(),
                stroke = Stroke(Color.White.copy(alpha = 0.16f).fill(), maxOf(1.5f, s * 0.012f)),
                transform = at(), name = "logo-tile",
            )
            val inner = s * GLYPH
            if (mark.path != null) {
                // The mark traces its outline, then fills while the outline fades.
                nodes += ShapeNode(
                    ShapeSpec.Path(mark.path, Icons.VIEWPORT, inner), stroke = Stroke(glyphColor.fill(), maxOf(1.2f, s * 0.012f)),
                    trimEnd = Anim.tween(0f, 1f, t0 + 0.12f, t0 + 0.6f, Easing.ExpoOut),
                    transform = at(op = shown * anim(1f, t0 + 0.6f) { by(0f, 0.25f) }), end = t0 + 0.9f, name = "logo-trace",
                )
                nodes += ShapeNode(
                    ShapeSpec.Path(mark.path, Icons.VIEWPORT, inner), fill = glyphColor.fill(),
                    transform = at(op = shown * Anim.tween(0f, 1f, t0 + 0.45f, t0 + 0.7f)), name = "logo-mark",
                )
            } else {
                val letters = mark.monogram
                val f = fit.fit(letters, look.number.copy(weight = 900), s * 0.4f, s * 0.78f, s * 0.6f, maxLines = 1)
                nodes += TextNode(letters, f.type, glyphColor.fill(), rtl = false, transform = at(op = shown * Anim.tween(0f, 1f, t0 + 0.2f, t0 + 0.45f)), name = "logo-monogram")
            }
            // A ring bursts from the hero as the mark completes.
            nodes += ShapeNode(
                ShapeSpec.Rect(s.anim, s.anim, (s * RADIUS).anim), stroke = Stroke(glowColor.copy(alpha = 0.9f).fill(), maxOf(2f, s * 0.016f)),
                transform = Transform(
                    x = slot.heroX.anim, y = slot.heroY.anim, scale = Anim.tween(slot.heroScale, slot.heroScale * 1.4f, t0 + 0.4f, t0 + 1f, Easing.ExpoOut),
                    opacity = Anim.tween(0.9f, 0f, t0 + 0.4f, t0 + 1f, Easing.ExpoOut),
                ),
                start = t0 + 0.38f, end = t0 + 1.05f, name = "logo-ring",
            )
            // The name appears under the tile once it has taken its place.
            val lf = fit.fit(mark.title, look.label.copy(weight = 700, tracking = 0.02f), s * 0.19f, s * 1.6f, s * 0.26f, maxLines = 1)
            nodes += TextNode(
                mark.title, lf.type, Craft.secondary(cue, look).fill(), rtl = false, shadow = Craft.shadow(cue, look, lf.type.size),
                transform = Transform(x = slot.x.anim, y = (slot.y + s * 0.5f + s * 0.19f).anim, opacity = Anim.tween(0f, 1f, land - 0.1f, land + 0.25f)),
                name = "logo-label",
            )
            return nodes
        }

        private fun group(cue: Cue, children: List<Node>): Group {
            val exitAt = cue.out - 0.28f
            return Group(
                children,
                transform = Transform(
                    anchorX = 0f, anchorY = 0f,
                    y = anim(0f, exitAt) { by(-26f, 0.28f, Easing.Exit) },
                    opacity = anim(1f, exitAt) { by(0f, 0.28f, Easing.Exit) },
                ),
                start = cue.at - 0.6f, end = cue.out + 0.02f, name = name,
            )
        }
    }

    private const val MAX_LOGOS = 8
    private const val PER_ROW = 4
    private const val HERO = 1.7f
    private const val FLY = 0.45f
    private const val DARK = 0.15f
    private const val GAP = 0.3f
    private const val RADIUS = 0.24f
    private const val GLYPH = 0.56f
    private const val LIGHT = 0.82f

    val all: List<Recipe> = listOf(Logos)
}
