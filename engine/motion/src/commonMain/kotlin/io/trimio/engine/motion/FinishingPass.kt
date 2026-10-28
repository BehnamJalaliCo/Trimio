package io.trimio.engine.motion

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import kotlin.math.abs
import kotlin.math.exp

/**
 * The finishing pass as decisions, not pixels: every number [Finishing] specifies is turned into
 * the concrete gate, mix, curve or amplitude a shader (or a ColorFilter fallback) then applies.
 * Pure so the look can be argued over and tested before a single frame is rendered.
 */
object FinishingPass {

    /** Soft highlight gate for halation: darks stay clean, only the top of the range glows. */
    const val HALATION_LO = 0.55f
    const val HALATION_HI = 0.95f

    /** Film grain lands at 1–2 of 255 code values: texture, never snow. */
    const val GRAIN_CODE_MIN = 1f
    const val GRAIN_CODE_MAX = 2f

    /** Grain re-aims at 12 Hz — alive, but the encoder is not fed fresh noise every frame. */
    const val GRAIN_HZ = 12f

    /** Sub-frame taps never exceed this, whatever the travel. */
    const val MAX_SHUTTER_SAMPLES = 6

    /** How far a midtone deviates in the grain tile (it is built around 0.5). */
    private const val GRAIN_TILE_SPREAD = 0.5f

    /** S-curve blend per unit of contrast above 1 (1.06 → a hair of an S). */
    private const val CONTRAST_TO_S = 4f

    /** Warm/cool tilt as a channel gain (matches the compiler's grade matrix). */
    private const val TEMP_R = 0.08f
    private const val TEMP_B = 0.1f

    /** Base gaussian radius a diffusion filter spreads to. */
    private const val DIFFUSION_BASE_RADIUS = 12f

    private const val LN2 = 0.6931472f

    enum class Channel { R, G, B }

    /** How far the low-pass is mixed in, and how wide it is, per physical diffusion filter. */
    data class DiffusionMix(val radiusPx: Float, val mix: Float)

    /** Soft gate: 0 below [lo], 1 above [hi], smoothstep between — no hard halo edge. */
    fun halationGate(luminance: Float, lo: Float = HALATION_LO, hi: Float = HALATION_HI): Float {
        if (luminance <= lo) return 0f
        if (luminance >= hi) return 1f
        val x = (luminance - lo) / (hi - lo)
        return x * x * (3f - 2f * x)
    }

    /** Strength × gate: what the halation shader adds at this luminance. */
    fun halationGain(strength: Float, luminance: Float): Float =
        strength.coerceIn(0f, 1f) * halationGate(luminance)

    /**
     * The mix `k` and radius of `mix(c, c+blur(lowpass(c)), k)` per filter: ProMist is a whisper,
     * Glimmerglass a soft sheen, CineBloom a wide dreamy bloom.
     */
    fun diffusionOf(diffusion: Finishing.FilmLook.Diffusion, baseRadiusPx: Float = DIFFUSION_BASE_RADIUS): DiffusionMix =
        when (diffusion) {
            Finishing.FilmLook.Diffusion.None -> DiffusionMix(0f, 0f)
            Finishing.FilmLook.Diffusion.ProMist -> DiffusionMix(baseRadiusPx * 0.75f, 0.22f)
            Finishing.FilmLook.Diffusion.Glimmerglass -> DiffusionMix(baseRadiusPx * 1.1f, 0.32f)
            Finishing.FilmLook.Diffusion.CineBloom -> DiffusionMix(baseRadiusPx * 1.6f, 0.45f)
        }

    /** Mild S around 0.5: [contrast] 1.06 leans toward the film curve, below 1 flattens. */
    fun sCurve(v: Float, contrast: Float): Float {
        val x = v.coerceIn(0f, 1f)
        val smooth = x * x * (3f - 2f * x)
        val k = ((contrast - 1f) * CONTRAST_TO_S).coerceIn(-1f, 1f)
        return (x + (smooth - x) * k).coerceIn(0f, 1f)
    }

    /** One channel of the finishing grade: exposure, S-curve, then the warm/cool tilt. */
    fun gradeChannel(v: Float, channel: Channel, grade: Finishing.Grade): Float {
        val exposed = (v * exp(grade.exposure * LN2)).coerceAtMost(4f)
        val curved = sCurve(exposed, grade.contrast)
        val tilted = when (channel) {
            Channel.R -> curved * (1f + TEMP_R * grade.temperature)
            Channel.G -> curved
            Channel.B -> curved * (1f - TEMP_B * grade.temperature)
        }
        return tilted.coerceIn(0f, 1f)
    }

    /** Saturation around the channel's own luma (ColorFilter path); unity leaves the curve alone. */
    fun saturate(v: Float, luma: Float, saturation: Float): Float =
        (luma + (v - luma) * saturation).coerceIn(0f, 1f)

    /** Grain peak in 0..1 (1–2 of 255 code values, scaled by [amount]). */
    fun grainCodeValues(amount: Float): Float {
        val a = amount.coerceIn(0f, 1f)
        return (GRAIN_CODE_MIN + (GRAIN_CODE_MAX - GRAIN_CODE_MIN) * a) / 255f
    }

    /** Overlay alpha that lands the grey tile at [grainCodeValues] on a midtone. */
    fun grainOverlayAlpha(amount: Float): Float = grainCodeValues(amount) / GRAIN_TILE_SPREAD

    /** Grain's time quantised to [GRAIN_HZ], so consecutive frames share one noise aim. */
    fun grainFrame(t: Float): Int = (t.coerceAtLeast(0f) * GRAIN_HZ).toInt()

    /**
     * Adaptive shutter sampling for a node travelling [travelPx] across one shutter: slow moves
     * take two taps, smears near the radius cap take six. Replaces a fixed sample count so a
     * static frame is never paid for and a whip pan is never under-sampled.
     */
    fun shutterSamples(travelPx: Float, shutterAngleDeg: Float = 180f): Int {
        val speed = travelPx * 360f / shutterAngleDeg.coerceAtLeast(1f)
        return Finishing.blurFor(speed).samples.coerceIn(2, MAX_SHUTTER_SAMPLES)
    }

    /** The full-frame finishing filter, or null when the look asks for nothing. */
    fun filter(film: Finishing.FilmLook?, grade: Finishing.Grade? = null): Filter? {
        val f = film ?: return null
        val alive = f.halationStrength > 0f ||
            f.diffusion != Finishing.FilmLook.Diffusion.None ||
            f.grainAmount > 0f ||
            grade != null
        return if (alive) Filter(ShaderFamily.Finish, Anim.One, film = f, grade = grade) else null
    }

    /**
     * The composited picture wrapped in one finishing pass. Grain in the same shader sits under
     * whatever the caller draws next (type), which is the whole point.
     */
    fun wrap(
        nodes: List<Node>,
        film: Finishing.FilmLook?,
        grade: Finishing.Grade? = null,
        name: String = "finish",
    ): List<Node> {
        if (nodes.isEmpty()) return nodes
        val f = filter(film, grade) ?: return nodes
        return listOf(Group(nodes, name = name, filter = f))
    }

    /** CPU fallback grade (no shader compiler): exposure, contrast, saturation, temperature. */
    fun gradeColorFilter(grade: Finishing.Grade): ColorFilter {
        val gain = exp(grade.exposure * LN2)
        val c = grade.contrast
        val off = 128f * (1f - c) / 255f
        val r = c * gain * (1f + TEMP_R * grade.temperature)
        val b = c * gain * (1f - TEMP_B * grade.temperature)
        val m = ColorMatrix().apply { setToSaturation(grade.saturation) }
        m.timesAssign(
            ColorMatrix(
                floatArrayOf(
                    r, 0f, 0f, 0f, off,
                    0f, c * gain, 0f, 0f, off,
                    0f, 0f, b, 0f, off,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        return ColorFilter.colorMatrix(m)
    }
}

/**
 * Motion-blur decisions, kept out of the renderer so a zooming clip can be tested without a
 * canvas. Footage counts: a punch-in's smear is the camera's, not a bug.
 */
object MotionBlurDecide {
    /** Node kinds the renderer draws; effects and counters never take motion blur. */
    enum class Kind { Media, Text, Group, Shape, Vector, Effect, Counter }

    /** Sum of edge travel between two axis-aligned bounds, in px. */
    fun travel(a: Rect, b: Rect): Float =
        abs(a.left - b.left) + abs(a.top - b.top) + abs(a.right - b.right) + abs(a.bottom - b.bottom)

    /** Whether this kind may be motion-blurred at all. */
    fun blurs(kind: Kind): Boolean = when (kind) {
        Kind.Effect, Kind.Counter -> false
        Kind.Media, Kind.Text, Kind.Group, Kind.Shape, Kind.Vector -> true
    }

    /**
     * Whether the travel earns blur. Threshold is doubled because [travel] sums four edges of the
     * same move; text also blurs when its units slide even if the line box holds still.
     */
    fun isMoving(travelPx: Float, unitsMove: Boolean, thresholdPx: Float): Boolean =
        travelPx > thresholdPx * 2f || unitsMove
}
