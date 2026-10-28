package io.trimio.engine.motion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * One two-buffer blend: its program source and the extra uniforms it reads beyond uSize and
 * uProgress. Anything a program declares must also be provided by [TransitionParams.uniforms] —
 * a test holds the two together, because a shader that declares an unfilled uniform fails to
 * compile at draw time on Android.
 */
data class TransitionProgram(val id: String, val source: String, val uniforms: Set<String> = emptySet())

/**
 * The tweenable knobs of a transition: every knob is an [Anim] so a picker can ease it through the
 * cut. Values are clamped where a shader would divide by zero or step over an edge. [uniforms] is
 * the uniform bag at time [t].
 */
data class TransitionParams(
    val amount: Anim = Anim.One,
    val color: Color = Color.Black,
    val colorPhase: Anim = 0.4f.anim,
    val smoothness: Anim = 0.1f.anim,
    /** Wipe direction in screen space (x right, y down); the ports keep their original defaults. */
    val directionX: Float = -1f,
    val directionY: Float = -1f,
    val blur: Anim = Anim.Zero,
    val squares: Anim = 20f.anim,
    val steps: Anim = 50f.anim,
    val opening: Anim = Anim.One,
    val soft: Anim = 0.02f.anim,
    val noiseScale: Anim = 1f.anim,
    /** Whip slide (px) and zoom punch (ratio): the in-house cuts' geometry, tweenable like the rest. */
    val slideX: Anim = Anim.Zero,
    val slideY: Anim = Anim.Zero,
    val zoom: Anim = 0.25f.anim,
    val seed: Int = 1,
) {
    fun uniforms(t: Float): Map<String, FloatArray> = mapOf(
        "uAmount" to floatArrayOf(amount.at(t)),
        "uColor" to floatArrayOf(color.red, color.green, color.blue),
        "uColorPhase" to floatArrayOf(colorPhase.at(t)),
        "uSmoothness" to floatArrayOf(smoothness.at(t).coerceAtLeast(MIN_KNOB)),
        "uDirection" to floatArrayOf(directionX, directionY),
        "uBlur" to floatArrayOf(blur.at(t).coerceAtLeast(0f)),
        "uSquares" to floatArrayOf(squares.at(t).coerceAtLeast(1f)),
        "uSteps" to floatArrayOf(steps.at(t).coerceAtLeast(0f)),
        "uOpening" to floatArrayOf(opening.at(t)),
        "uSoft" to floatArrayOf(soft.at(t).coerceAtLeast(MIN_KNOB)),
        "uNoiseScale" to floatArrayOf(noiseScale.at(t).coerceAtLeast(MIN_KNOB)),
        "uDir" to floatArrayOf(slideX.at(t), slideY.at(t)),
        "uZoom" to floatArrayOf(zoom.at(t).coerceAtLeast(0f)),
        "uSeed" to floatArrayOf(seed.toFloat()),
    )

    private companion object {
        /** Smallest step a shader may divide by or step over. */
        const val MIN_KNOB = 0.001f
    }
}

/**
 * The two-buffer transition engine: the outgoing frame and the incoming one blended by one small
 * program ([TransitionSources]) with a tweenable progress and parameters. The same source runs as
 * AGSL on Android 13+ and as SkSL on Skia through [RuntimeFx]; where no shader compiler is at hand
 * the blend falls back to a plain crossfade with exact endpoints. The whip/zoom scene moves
 * ([move]) live here too, so the compiler, the picker and the programs share one set of numbers.
 */
object TransitionShaders {

    /** Every blend the app ships: the eight gl-transitions ports, then the in-house cuts. */
    val programs: Map<String, TransitionProgram> = listOf(
        TransitionProgram("fade", TransitionSources.fade),
        TransitionProgram("fadecolor", TransitionSources.fadecolor, setOf("uColor", "uColorPhase")),
        TransitionProgram("directionalwarp", TransitionSources.directionalwarp, setOf("uSmoothness", "uDirection", "uBlur")),
        TransitionProgram("crosswarp", TransitionSources.crosswarp),
        TransitionProgram("lumawipe", TransitionSources.lumawipe, setOf("uSoft")),
        TransitionProgram("pixelize", TransitionSources.pixelize, setOf("uSquares", "uSteps")),
        TransitionProgram("circleopen", TransitionSources.circleopen, setOf("uSmoothness", "uOpening")),
        TransitionProgram("noisedissolve", TransitionSources.noisedissolve, setOf("uNoiseScale", "uSeed")),
        TransitionProgram("whip", TransitionSources.whip, setOf("uDir", "uBlur")),
        TransitionProgram("zoom", TransitionSources.zoom, setOf("uZoom", "uBlur")),
        TransitionProgram("glitchlite", TransitionSources.glitchlite, setOf("uAmount", "uSeed")),
    ).associateBy { it.id }

    /** Off forces the crossfade fallback everywhere (old devices, tests comparing the two). */
    var enabled: Boolean = true

    private val cache = HashMap<String, RuntimeFx?>()

    /** The compiled [program], or null where the device's compiler rejects the source. */
    internal fun compiled(program: TransitionProgram): RuntimeFx? {
        if (!enabled) return null
        return cache.getOrPut(program.id) { runCatching { RuntimeFx(program.source) }.getOrNull() }
    }

    /** The program a name resolves to; unknown ids land on the fade so a typo never throws. */
    fun program(name: String): TransitionProgram = programs[name] ?: programs.getValue(DEFAULT)

    /**
     * A scene group's whip/zoom move: the slide and punch keys with the blur that rides them. A
     * fast slide strobes frame by frame without a smear — the blur travels in the group's own blur
     * channel, which the renderer accumulates into a directional smear along the move.
     */
    fun move(enter: String?, exit: String?, s: Float, e: Float, w: Float, settle: Easing): Move {
        val x = mutableListOf(Anim.Key(s - 1f, 0f, Easing.Hold))
        val scale = mutableListOf(Anim.Key(s - 1f, 1f, Easing.Hold))
        val opacity = mutableListOf(Anim.Key(s - 1f, 1f, Easing.Hold))
        val blur = mutableListOf(Anim.Key(s - 1f, 0f, Easing.Hold))
        when (enter) {
            "whip" -> {
                x += Anim.Key(s, w * WHIP_TRAVEL, Easing.Hold)
                x += Anim.Key(s + WHIP_IN, 0f, settle)
                blur += Anim.Key(s, w * WHIP_BLUR, Easing.Hold)
                blur += Anim.Key(s + WHIP_IN, 0f, settle)
            }
            "zoom" -> {
                scale += Anim.Key(s, ZOOM_ENTER, Easing.Hold)
                scale += Anim.Key(s + ZOOM_IN, 1f, settle)
                opacity += Anim.Key(s, 0f, Easing.Hold)
                opacity += Anim.Key(s + ZOOM_FADE, 1f, Easing.Linear)
                blur += Anim.Key(s, w * ZOOM_BLUR, Easing.Hold)
                blur += Anim.Key(s + ZOOM_IN, 0f, settle)
            }
        }
        when (exit) {
            "whip" -> {
                x += Anim.Key(e - WHIP_OUT, 0f, Easing.Hold)
                x += Anim.Key(e, -w * WHIP_TRAVEL, Easing.ExpoIn)
                blur += Anim.Key(e - WHIP_OUT, 0f, Easing.Hold)
                blur += Anim.Key(e, w * WHIP_BLUR, Easing.ExpoIn)
            }
            "zoom" -> {
                scale += Anim.Key(e - ZOOM_OUT, 1f, Easing.Hold)
                scale += Anim.Key(e, ZOOM_EXIT, Easing.ExpoIn)
                opacity += Anim.Key(e - ZOOM_FADE, 1f, Easing.Hold)
                opacity += Anim.Key(e, 0f, Easing.Linear)
                blur += Anim.Key(e - ZOOM_OUT, 0f, Easing.Hold)
                blur += Anim.Key(e, w * ZOOM_BLUR, Easing.ExpoIn)
            }
        }
        return Move(Anim.keys(x), Anim.keys(scale), Anim.keys(opacity), Anim.keys(blur))
    }

    /** What a scene group plays for its cuts: slide, punch, fade and the blur along the move. */
    data class Move(val x: Anim, val scale: Anim, val opacity: Anim, val blur: Anim)

    private const val DEFAULT = "fade"
    private const val WHIP_TRAVEL = 1.1f
    private const val WHIP_IN = 0.24f
    private const val WHIP_OUT = 0.16f
    /** Whip smear as a share of the frame width: a full-width slide inside a quarter second. */
    private const val WHIP_BLUR = 0.022f
    private const val ZOOM_ENTER = 0.82f
    private const val ZOOM_EXIT = 1.4f
    private const val ZOOM_IN = 0.4f
    private const val ZOOM_OUT = 0.2f
    private const val ZOOM_FADE = 0.12f
    private const val ZOOM_BLUR = 0.012f
}

/**
 * Blends [from] into [to] through [program] at [progress] (0 = only [from], 1 = only [to]).
 * Without a shader compiler — or on a canvas that cannot draw runtime shaders — the blend falls
 * back to a plain crossfade: the endpoints stay exact, the middle is honest but plain.
 */
internal fun DrawScope.drawTransition(
    from: ImageBitmap,
    to: ImageBitmap,
    program: TransitionProgram,
    progress: Float,
    params: TransitionParams = TransitionParams(),
    t: Float = 0f,
) {
    val p = progress.coerceIn(0f, 1f)
    val fx = TransitionShaders.compiled(program)?.takeIf { it.drawsOn(drawContext.canvas) }
    if (fx == null) return crossfade(from, to, p)
    fx.child("fromTex", ImageShader(from, TileMode.Clamp, TileMode.Clamp))
    fx.child("toTex", ImageShader(to, TileMode.Clamp, TileMode.Clamp))
    fx.uniform("uSize", size.width, size.height)
    fx.uniform("uProgress", p)
    val uniforms = params.uniforms(t)
    for (name in program.uniforms) uniforms[name]?.let { fx.uniform(name, *it) }
    drawRect(fx.brush(), size = size)
}

private fun DrawScope.crossfade(from: ImageBitmap, to: ImageBitmap, progress: Float) {
    drawImage(from)
    if (progress > 0f) drawImage(to, alpha = progress)
}
