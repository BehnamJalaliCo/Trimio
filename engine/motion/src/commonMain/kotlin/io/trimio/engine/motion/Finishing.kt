package io.trimio.engine.motion

import kotlin.math.roundToInt

/**
 * The finishing layer as a pure parameter model: motion blur, film look and grade as measured
 * numbers only. Shaders draw these later — this file is the spec as code, so the look can be
 * argued over, clamped and tested before a single pixel is rendered.
 */
object Finishing {

    /**
     * Sub-frame motion blur as accumulative sampling, not post-hoc smearing: each tap is drawn at
     * its true sub-frame position, so blur rides moving groups only and static text stays sharp.
     * [shutterAngleDeg] is how much of a frame's travel blurs (180 = the film standard);
     * [maxRadiusPx] caps the smear so a whip pan never turns to mush.
     */
    data class MotionBlur(
        val samples: Int = 4,
        val shutterAngleDeg: Float = 180f,
        val maxRadiusPx: Float = 20f,
    )

    /**
     * The film look as parameters, physically modelled per spektrafilm: emulsion scatter
     * (diffusion + grain) and the base-reflected red glow of halation. Params only — the GPL code
     * is never embedded; shaders interpret these numbers however they are written.
     */
    data class FilmLook(
        val halationStrength: Float = 0.35f,
        val halationRadiusPx: Float = 24f,
        val grainAmount: Float = 0.05f,
        val diffusion: Diffusion = Diffusion.ProMist,
    ) {
        /** Named after the physical diffusion filters they emulate. */
        enum class Diffusion { None, ProMist, Glimmerglass, CineBloom }
    }

    /**
     * The finishing grade, values mirroring the look's grade defaults: a hair of exposure, a touch
     * of contrast, saturation held at unity, a warm bias — filmic without a separate grade pass.
     */
    data class Grade(
        val exposure: Float = 0.04f,
        val contrast: Float = 1.06f,
        val saturation: Float = 1f,
        val temperature: Float = 0.15f,
    )

    /**
     * The blur a group moving at [speedPxPerFrame] earns: the shutter slice of one frame's travel
     * (angle over a full circle), clamped to (0, [MotionBlur.maxRadiusPx]] — never a zero-width
     * smear, never past the cap. Samples scale 2..6 with the share of the radius budget the move
     * uses: slow moves need two taps, capped smears need six.
     */
    fun blurFor(speedPxPerFrame: Float): MotionBlur {
        val base = MotionBlur()
        val radius = (speedPxPerFrame * base.shutterAngleDeg / 360f).coerceIn(0.01f, base.maxRadiusPx)
        val samples = (2f + 4f * radius / base.maxRadiusPx).roundToInt().coerceIn(2, 6)
        return MotionBlur(samples, base.shutterAngleDeg, radius)
    }

    /** Halation lives in highlights: only luminance above the floor glows. */
    fun halationHighlightsOnly(luminance: Float): Boolean = luminance > 0.75f

    /**
     * Named looks for recipes and the UI: "clean" is digital clarity, "film" the measured house
     * look, "dream" trades grain for a stronger glow.
     */
    val filmPresets: Map<String, FilmLook> = mapOf(
        "clean" to FilmLook(halationStrength = 0f, grainAmount = 0f, diffusion = FilmLook.Diffusion.None),
        "film" to FilmLook(halationStrength = 0.35f, grainAmount = 0.05f, diffusion = FilmLook.Diffusion.ProMist),
        "dream" to FilmLook(halationStrength = 0.5f, grainAmount = 0.03f, diffusion = FilmLook.Diffusion.CineBloom),
    )
}
