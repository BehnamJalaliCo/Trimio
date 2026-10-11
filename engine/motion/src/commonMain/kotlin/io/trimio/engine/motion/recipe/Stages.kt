package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Effect
import io.trimio.engine.motion.EffectNode
import io.trimio.engine.motion.Filter
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShaderFamily

/**
 * Where the shader families meet the looks. A look names its stage ([Look.backdrop], which a
 * style's `background` sets: "glow", "leaks", "bokeh", "dust", "liquid", or a loose alias) and
 * its lens ([Look.lens]: "halation", "lens", "chromatic"); the compiler asks here for the stage,
 * the lens over the picture and the distortion of a "glitch" or "ripple" cut, and recipes
 * ask for the frosted glass behind their cards.
 */
object Stages {
    /** Stage palette: canvas, accent, hot, ink (what [FxSources]' c0…c3 mean). */
    fun palette(look: Look): List<Color> = listOf(look.canvas, look.accent, look.hot, look.ink)

    /**
     * The look's shader stage, or null when it keeps the drifting aurora. Always seed 1: glass
     * redraws this very field, so every scene's stage must be the same one.
     */
    fun shader(look: Look): Effect.Shader? =
        ShaderFamily.named(look.backdrop)?.takeIf { !it.filter }?.let { Effect.Shader(it, palette(look)) }

    /** A graphic scene's background: the look's shader stage, else the aurora in [colors]. */
    fun background(look: Look, colors: List<Color>, seed: Int): Effect = shader(look) ?: Effect.Aurora(colors, seed = seed)

    /** The look's lens over the picture (film halation, a wide lens, a chromatic edge), never over text; or null. */
    fun lens(look: Look): Filter? = ShaderFamily.named(look.lens)?.takeIf { it.filter }?.let { Filter(it, LENS_AMOUNT.let(Anim::Const)) }

    /**
     * Frosted glass behind a card whose box is [x], [y] (top-left), [width] × [height], fading in
     * with [opacity]; null over footage or on a look without a shader stage.
     */
    fun glass(look: Look, cue: Cue, x: Float, y: Float, width: Float, height: Float, radius: Float, opacity: Anim): Node? {
        if (cue.overMedia) return null
        val stage = shader(look) ?: return null
        return EffectNode(Effect.Glass(stage, x, y, width, height, radius, opacity = opacity), name = "glass")
    }

    /** Scene transitions drawn as distortions of the whole frame. */
    val CUTS = setOf("glitch", "ripple")

    /**
     * The distortions of the cuts ([kind] to the cut's time) that are [CUTS]: a glitch burst
     * peaking on the cut, a ripple ring expanding through it. One filter per family.
     */
    fun cuts(cuts: List<Pair<String, Float>>): List<Filter> {
        val glitches = cuts.filter { it.first == "glitch" }.map { it.second }
        val ripples = cuts.filter { it.first == "ripple" }.map { it.second }
        return listOfNotNull(
            glitches.takeIf { it.isNotEmpty() }?.let { at -> Filter(ShaderFamily.Glitch, peaks(at, GLITCH_IN, GLITCH_OUT)) },
            ripples.takeIf { it.isNotEmpty() }?.let { at ->
                Filter(ShaderFamily.Ripple, peaks(at, RIPPLE_IN, RIPPLE_OUT), progress = sweeps(at, RIPPLE_IN, RIPPLE_OUT))
            },
        )
    }

    /** 0 → 1 on each cut → 0, rising over [rise] and falling over [fall]. */
    private fun peaks(at: List<Float>, rise: Float, fall: Float): Anim = Anim.keys(
        at.sorted().flatMap { c ->
            listOf(Anim.Key(c - rise, 0f, Easing.Hold), Anim.Key(c, 1f, Easing.Linear), Anim.Key(c + fall, 0f, Easing.ExpoOut))
        },
    )

    /** A ring's progress through each cut: 0 at the start of its window, 1 at its end. */
    private fun sweeps(at: List<Float>, rise: Float, fall: Float): Anim = Anim.keys(
        at.sorted().flatMap { c -> listOf(Anim.Key(c - rise, 0f, Easing.Hold), Anim.Key(c + fall, 1f, Easing.QuintOut)) },
    )

    private const val LENS_AMOUNT = 0.6f
    private const val GLITCH_IN = 0.18f
    private const val GLITCH_OUT = 0.22f
    private const val RIPPLE_IN = 0.25f
    private const val RIPPLE_OUT = 0.55f
}
