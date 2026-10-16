package io.trimio.engine.motion.art

import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.VectorNode
import io.trimio.engine.motion.anim
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
 * A designer's template (After Effects, exported as Lottie) played as the beat: the motion, the
 * easing and the graphic are the designer's; the engine only fits it to the slot, fills its text
 * slots with what is said (the line first, then the label) and stretches its rest so it builds
 * in as the words start and leaves as they end.
 *
 * The beat's `visual` names the template (`template:<id>`); the editor that chose it decided what
 * the moment is (a title, a point, a number, the ask) — this recipe never decides, it performs.
 */
object TemplateRecipes {

    val Template = object : Recipe("template", Kind.Element) {
        override val preferredHeight = 0.16f
        override val minHold = 1.2f

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val icon = cue.visual ?: return Built(emptyList())
            val art = icon.motion ?: return Built(emptyList())
            val aspect = art.width / art.height.coerceAtLeast(1f)
            val w = minOf(cue.width, cue.height * aspect)
            val h = w / aspect
            val t0 = cue.at - Craft.LEAD
            val node = VectorNode(
                icon, size = if (aspect >= 1f) w else h,
                transform = Transform(x = cue.x.anim, y = cue.y.anim, opacity = fade(art, cue)),
                start = t0, end = cue.out + FADE,
                name = "template",
            )
            node.play = clock(art, t0, cue.out)
            node.texts = listOfNotNull(cue.text.takeIf { it.isNotBlank() }, cue.label?.takeIf { it.isNotBlank() })
            return Built(listOf(node), sfx = listOf(Sfx(cue.at, SfxKind.Whoosh, 0.55f)), box = Box.around(cue.x, cue.y, w, h))
        }
    }

    val all: List<Recipe> = listOf(Template)

    /**
     * A designer's accent ([io.trimio.engine.motion.score.BeatScore.accent]) placed from the box
     * where its beat landed, by its role (the icon's first keyword):
     * - `underline` — a stroke under the box, as wide as it;
     * - `circle` — a ring drawn round it;
     * - `arrow` — beside it on the side reading starts from, pointing at it (mirrored in Persian);
     * - `burst`, `sparkle` — at its top corner on the reading side, half behind it;
     * - `tap`, `swipe` — a hand under its end, pressing or swiping (the ask);
     * - `check`, `cross`, `warning` — a mark beside its end (a gain, a loss, a caution).
     * It plays from as the beat lands to when it leaves, fading if it has no exit of its own.
     */
    fun accent(icon: io.trimio.engine.motion.visual.VectorIcon, box: Box, at: Float, out: Float, rtl: Boolean): List<io.trimio.engine.motion.Node> {
        val art = icon.motion ?: return emptyList()
        val role = icon.keywords.firstOrNull().orEmpty()
        val aspect = art.width / art.height.coerceAtLeast(1f)
        val start = if (rtl) box.right else box.left
        val end = if (rtl) box.left else box.right
        val toward = if (rtl) -1f else 1f
        // (centre x, centre y, width) of the accent.
        val (cx, cy, w) = when (role) {
            "underline" -> (box.width * 0.9f).let { uw -> Triple(box.centerX, box.bottom + box.height * UNDER_GAP + uw / aspect / 2f, uw) }
            "circle" -> Triple(box.centerX, box.centerY, maxOf(box.width, box.height * aspect) * RING)
            "arrow" -> (box.height * SIDE * aspect).let { aw -> Triple(start - toward * (aw / 2f + box.height * GAP), box.centerY, aw) }
            "burst", "sparkle" -> (box.height * CORNER * aspect).let { bw -> Triple(start, box.top, bw) }
            "tap", "swipe" -> (box.height * SIDE * aspect).let { hw -> Triple(end - toward * hw * 0.2f, box.bottom + box.height * SIDE * 0.35f, hw) }
            else -> (box.height * MARK * aspect).let { mw -> Triple(end + toward * (mw / 2f + box.height * GAP), box.centerY, mw) }
        }
        val mirror = rtl && role == "arrow"
        val shown = if (mirror) icon.copy(motion = art.styled(Colouring.Own, mirror = true)) else icon
        val t0 = at - Craft.LEAD
        val node = VectorNode(
            shown, size = if (aspect >= 1f) w else w / aspect,
            transform = Transform(x = cx.anim, y = cy.anim, opacity = fade(art, out)),
            start = t0, end = out + FADE, name = "accent-$role",
        )
        node.play = clock(art, t0, out)
        return listOf(node)
    }

    /**
     * The template's own clock on the composition's: it builds in at its designed speed from [t0],
     * rests for as long as the words need, and plays its own exit to end at [out]. Shorter than the
     * design, the whole of it plays faster; with no rest it builds in and holds its last frame.
     */
    fun clock(art: MotionArt, t0: Float, out: Float): Anim {
        val d = art.duration
        val span = (out - t0).coerceAtLeast(MIN_SPAN)
        val hold = art.hold
        val keys = when {
            hold == null -> listOf(Anim.Key(t0, 0f, Easing.Linear), Anim.Key(t0 + d, d, Easing.Linear))
            hold.start + (d - hold.endInclusive) > span -> listOf(Anim.Key(t0, 0f, Easing.Linear), Anim.Key(t0 + span, d, Easing.Linear))
            else -> listOf(
                Anim.Key(t0, 0f, Easing.Linear),
                Anim.Key(t0 + hold.start, hold.start, Easing.Linear),
                Anim.Key(out - (d - hold.endInclusive), hold.endInclusive, Easing.Linear),
                Anim.Key(out, d, Easing.Linear),
            )
        }
        return Anim.keys(keys)
    }

    /** A template with no exit of its own fades as the words end; one that leaves by itself is left to it. */
    private fun fade(art: MotionArt, cue: Cue): Anim = fade(art, cue.out)

    private fun fade(art: MotionArt, out: Float): Anim =
        if (art.hold != null) Anim.One else anim(1f, out - FADE) { by(0f, FADE * 2f, Easing.Exit) }

    private const val FADE = 0.2f
    private const val UNDER_GAP = 0.05f
    private const val RING = 1.3f
    private const val SIDE = 0.9f
    private const val GAP = 0.15f
    private const val CORNER = 1.1f
    private const val MARK = 0.8f
    private const val MIN_SPAN = 0.4f
}
