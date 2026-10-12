package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * The two devices an edit uses most, a counter and a stamp, in more than one cut: their signature
 * forms ([ElementRecipes.Counter], [ElementRecipes.Stamp]) stay the first treatment, so a score
 * without a variety seed looks exactly as before.
 */
object FigureFamilies {

    private fun free(look: Look, cue: Cue): Color? = look.canvas.takeIf { !cue.overMedia }

    private fun inkFree(look: Look, cue: Cue) = free(look, cue)?.let { Kit.inkOn(look, it) } ?: look.ink

    /** Only one variant of the signature cut (it has its own choreography). */
    private fun signatureOnce(weights: List<Float>, layouts: Int) = weights.mapIndexed { v, w -> if (v % layouts == 0 && v != 0) 0f else w }

    // ------------------------------------------------------------------ counter

    /** A big number: the signature count-up, a figure on a card beside its label, or a figure over an accent bar with an eyebrow. */
    val Counter = object : Family("counter", listOf(Move.Pop, Move.Rise, Move.Flip)) {
        override val minHold = 2.2f
        override val preferredHeight = 0.24f

        override fun variantWeights(look: Look, cue: Cue): List<Float> = signatureOnce(super.variantWeights(look, cue), layouts)

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built = when (p.layout) {
            1 -> card(cue, look, fit, p)
            2 -> bar(cue, look, fit, p)
            else -> ElementRecipes.Counter.build(cue, look, fit)
        }

        private fun sample(cue: Cue) = cue.prefix + Kit.format(cue.value ?: 0f, cue.decimals, false) + cue.suffix

        private fun label(cue: Cue) = cue.label ?: cue.text.takeIf { it.isNotBlank() }

        private fun color(look: Look, cue: Cue) = if ((cue.value ?: 0f) >= (cue.from ?: 0f)) look.positive else look.negative

        /** On a card: the figure at the reading start, its label beside it, an arrow chip for its direction. */
        private fun card(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.34f))
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val pad = b.height * 0.16f
            val settle = Craft.pace(cue.energy, 1.4f, 0.8f)
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.18f, "counter-card").toMutableList()
            val l = label(cue)
            val nf = Kit.fit(fit, sample(cue), Kit.numberVoice(look, p.typo), b.height * 0.56f, b.width * (if (l != null) 0.55f else 0.84f), b.height * 0.7f)
            val nx = Kit.fromStart(Kit.startX(b, p.rtl, pad), nf.width, p.rtl)
            val figure = Kit.tint(look, color(look, cue), s.ground)
            nodes += Kit.number(cue.value ?: 0f, cue.from ?: 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size, figure, nx, b.centerY, p.at + 0.15f, settle, s.ground, p.rtl)
            if (l != null) {
                val room = b.width - nf.width - pad * 3f
                val lf = Kit.fit(fit, l, Kit.titleVoice(look, p.typo), b.height * 0.2f, room, b.height * 0.7f, lines = 2)
                nodes += Kit.words(l, lf, ink, Kit.fromStart(Kit.startX(b, p.rtl, pad * 2f + nf.width), lf.width, p.rtl), b.centerY, p.at + 0.35f, s.ground, p.rtl, name = "counter-label")
            }
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at, SfxKind.Riser, 0.4f), Sfx(p.at + 0.15f + settle, SfxKind.Pop, 0.7f)), listOf(Craft.punch(p.at + 0.15f + settle, 0.02f)))
        }

        /** An eyebrow label, the figure big, and an accent bar sweeping under it as it lands. */
        private fun bar(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val settle = Craft.pace(cue.energy, 1.4f, 0.8f)
            val l = label(cue)
            val ef = l?.let { Kit.fit(fit, it, Kit.labelVoice(look, p.typo), b.height * 0.11f, b.width * 0.9f, b.height * 0.18f) }
            val nf = Kit.fit(fit, sample(cue), Kit.numberVoice(look, p.typo), b.height * 0.56f, b.width * 0.92f, b.height * 0.62f)
            val barH = b.height * 0.06f
            val total = (ef?.height?.times(1.3f) ?: 0f) + nf.height + barH * 2f
            var y = b.centerY - total / 2f
            val nodes = mutableListOf<Node>()
            if (l != null && ef != null) {
                nodes += Kit.words(l, ef, ground?.let { Kit.tint(look, look.accent, it) } ?: look.accent, Kit.fromStart(Kit.startX(b, p.rtl, 0f), ef.width, p.rtl), y + ef.height / 2f, p.at, ground, p.rtl, Kit.Reveal.Fade, name = "counter-eyebrow")
                y += ef.height * 1.3f
            }
            val nx = Kit.fromStart(Kit.startX(b, p.rtl, 0f), nf.width, p.rtl)
            nodes += Kit.number(cue.value ?: 0f, cue.from ?: 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size, inkFree(look, cue), nx, y + nf.height / 2f, p.at + 0.1f, settle, ground, p.rtl)
            y += nf.height + barH
            val land = p.at + 0.1f + settle
            nodes += ShapeNode(
                ShapeSpec.Rect(Anim.tween(0f, nf.width, p.at + 0.2f, land, Easing.ExpoOut), barH.anim, (barH / 2f * look.roundness).anim), fill = color(look, cue).fill(),
                transform = Transform(x = Kit.startX(b, p.rtl, 0f).anim, y = y.anim, anchorX = if (p.rtl) 1f else 0f), name = "counter-bar",
            )
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at, SfxKind.Riser, 0.4f), Sfx(land, SfxKind.Pop, 0.7f)), listOf(Craft.punch(land, 0.025f)))
        }
    }

    // ------------------------------------------------------------------ stamp

    /** A verdict word: the signature rubber stamp, a round seal, or a strip of tape slapped across. */
    val Stamp = object : Family("stamp", listOf(Move.Pop, Move.Drop, Move.Focus)) {
        override val minHold = 1.5f
        override val preferredHeight = 0.14f

        override fun variantWeights(look: Look, cue: Cue): List<Float> = signatureOnce(super.variantWeights(look, cue), layouts)

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built = when (p.layout) {
            1 -> seal(cue, look, fit, p)
            2 -> tape(cue, look, fit, p)
            else -> ElementRecipes.Stamp.build(cue, look, fit)
        }

        private fun text(cue: Cue) = cue.text.ifBlank { cue.label ?: "" }

        /** A round seal: a double ring in the heat colour, the word across it, turned a little as it lands. */
        private fun seal(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val d = minOf(cue.height * 1.6f, cue.width * 0.42f)
            val b = Kit.box(cue, d, d)
            val word = text(cue)
            val land = p.at + 0.16f
            val color = free(look, cue)?.let { Kit.tint(look, look.hot, it) } ?: look.hot
            val f = Kit.fit(fit, word, look.headline, d * 0.2f, d * 0.74f, d * 0.4f, lines = 2)
            val ground = free(look, cue)
            val parts = listOf(
                ShapeNode(ShapeSpec.Ellipse(d.anim, d.anim), stroke = Stroke(color.fill(), d * 0.035f), transform = Transform(x = b.centerX.anim, y = b.centerY.anim), name = "seal-ring"),
                ShapeNode(ShapeSpec.Ellipse((d * 0.84f).anim, (d * 0.84f).anim), stroke = Stroke(color.fill(), d * 0.012f), transform = Transform(x = b.centerX.anim, y = b.centerY.anim), name = "seal-ring-inner"),
                Kit.words(word, f, color, b.centerX, b.centerY, p.at, ground, p.rtl, Kit.Reveal.None, name = "seal-text"),
            )
            val seal = Kit.pivot(
                parts, b.centerX, b.centerY, b,
                Transform(scale = Anim.tween(2.4f, 1f, p.at, land, Easing.ExpoIn), rotation = Anim.tween(-30f * p.dir, -9f * p.dir, p.at, land, Easing.ExpoIn), opacity = Anim.tween(0f, 1f, p.at, p.at + 0.08f)),
                "seal",
            )
            return Kit.built(cue, look, p, b, listOf(seal), name, listOf(Sfx(land, SfxKind.Boom, 1f)), listOf(Craft.punch(land, 0.05f), Craft.shake(land, 10f, cue.seed)))
        }

        /** A strip of tape across the slot, the word printed on it; it slaps on and settles. */
        private fun tape(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val word = text(cue)
            val h = minOf(cue.height * 0.7f, cue.width * 0.16f)
            val f = Kit.fit(fit, word, look.headline, h * 0.56f, cue.width * 0.8f, h * 0.8f)
            val w = minOf(cue.width, f.width + h * 1.6f)
            val b = Kit.box(cue, w, h * 1.6f)
            val s = Kit.surface(look, cue, Kit.Tone.Accent)
            val land = p.at + 0.14f
            val edge = ShapeNode(
                ShapeSpec.Path(TAPE, 100f, w), fill = s.fill, shadow = io.trimio.engine.motion.Shadow(Color.Black.copy(alpha = 0.35f), 0f, 8f, 18f),
                transform = Transform(x = b.centerX.anim, y = b.centerY.anim, scaleY = (h / w).anim), name = "tape-strip",
            )
            val parts = listOf(edge, Kit.words(word, f, Kit.inkOn(look, s.ground), b.centerX, b.centerY, p.at, s.ground, p.rtl, Kit.Reveal.None, name = "tape-text"))
            val strip = Kit.pivot(
                parts, b.centerX, b.centerY, b,
                Transform(scale = Anim.tween(1.5f, 1f, p.at, land, Easing.ExpoIn), rotation = anim(-12f * p.dir, p.at) { to(-4f * p.dir, land, Easing.ExpoIn) }),
                "tape",
            )
            return Kit.built(cue, look, p, b, listOf(strip), name, listOf(Sfx(land, SfxKind.Hit, 0.8f)), listOf(Craft.punch(land, 0.03f)))
        }
    }

    /** A strip of tape with torn ends, filling a 100 box. */
    private const val TAPE = "M3 4L0 14L4 26L1 40L5 52L1 64L4 78L0 90L3 96H97L100 86L96 74L99 60L95 48L99 36L96 22L100 10L97 4Z"

    val all: List<Recipe> = listOf(Counter, Stamp)
}
