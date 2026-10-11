package io.trimio.engine.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.sin

/**
 * The renderer's runtime shaders: compiled once per renderer on first use (renderers on other
 * threads own their own), or null when the device rejects a source — then the CPU fallback
 * draws. [enabled] false forces the fallback everywhere (old devices, tests comparing the two).
 * It also keeps this frame's stage image, which glass panels sample instead of re-evaluating
 * the field, and the low-resolution copy halation gathers its glow from.
 */
internal class FxPrograms {
    var enabled = true
    private val programs = HashMap<ShaderFamily, RuntimeFx?>()
    private var glassProgram: RuntimeFx? = null
    private var glassTried = false

    private var stageImage: ImageBitmap? = null
    private var stageOf: Effect.Shader? = null
    private var stageAt = Float.NaN
    private var bloom: ImageBitmap? = null

    fun of(family: ShaderFamily): RuntimeFx? = if (!enabled) null else programs.getOrPut(family) {
        runCatching { RuntimeFx(source(family)) }.getOrNull()
    }

    fun glass(): RuntimeFx? {
        if (!enabled) return null
        if (!glassTried) glassProgram = runCatching { RuntimeFx(FxSources.glass) }.getOrNull().also { glassTried = true }
        return glassProgram
    }

    /** The stage field [e] at [t] at its reduced resolution, rendered once per frame (shader or fallback). */
    fun stage(scope: DrawScope, e: Effect.Shader, t: Float, size: Size): ImageBitmap {
        val k = scaleOf(e.family)
        val w = (size.width / k).toInt().coerceAtLeast(1)
        val h = (size.height / k).toInt().coerceAtLeast(1)
        val cached = stageImage?.takeIf { it.width == w && it.height == h }
        if (cached != null && stageOf == e && stageAt == t) return cached
        val image = cached ?: ImageBitmap(w, h).also { stageImage = it }
        val small = Size(w.toFloat(), h.toFloat())
        val program = of(e.family)
        CanvasDrawScope().draw(scope, scope.layoutDirection, Canvas(image), small) {
            if (program != null) {
                program.stageUniforms(e, t, small)
                drawRect(program.brush(), size = small)
            } else {
                stageFallback(e, t, small)
            }
        }
        stageOf = e
        stageAt = t
        return image
    }

    /** [content] at a quarter of its size (smoothly filtered), for wide, cheap glows. */
    fun bloom(scope: DrawScope, content: ImageBitmap): ImageBitmap {
        val w = (content.width / BLOOM_SCALE).coerceAtLeast(1)
        val h = (content.height / BLOOM_SCALE).coerceAtLeast(1)
        val image = bloom?.takeIf { it.width == w && it.height == h } ?: ImageBitmap(w, h).also { bloom = it }
        CanvasDrawScope().draw(scope, scope.layoutDirection, Canvas(image), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.Transparent, size = size, blendMode = BlendMode.Clear)
            drawImage(content, dstSize = IntSize(w, h), filterQuality = FilterQuality.Low)
        }
        return image
    }

    private fun source(f: ShaderFamily) = when (f) {
        ShaderFamily.Glow -> FxSources.glow
        ShaderFamily.Leaks -> FxSources.leaks
        ShaderFamily.Bokeh -> FxSources.bokeh
        ShaderFamily.Dust -> FxSources.dust
        ShaderFamily.Liquid -> FxSources.liquid
        ShaderFamily.Chromatic -> FxSources.chromatic
        ShaderFamily.Lens -> FxSources.lens
        ShaderFamily.Glitch -> FxSources.glitch
        ShaderFamily.Ripple -> FxSources.ripple
        ShaderFamily.Halation -> FxSources.halation
    }

    companion object {
        const val BLOOM_SCALE = 4
    }
}

/** How much smaller than the frame a stage field renders (smooth fields lose nothing; particles keep detail). */
private fun scaleOf(f: ShaderFamily) = if (f == ShaderFamily.Dust) 3 else 4

private fun RuntimeFx.palette(colors: List<Color>, overlay: Boolean) {
    for (i in 0 until 4) {
        val c = if (overlay && i == 0) Color.Black else colors.getOrElse(i) { colors.last() }
        uniform("c$i", c.red, c.green, c.blue)
    }
}

private fun RuntimeFx.stageUniforms(e: Effect.Shader, t: Float, size: Size) {
    uniform("uSize", size.width, size.height)
    uniform("uTime", t)
    uniform("uSeed", e.seed.toFloat())
    uniform("uAmount", e.amount.at(t))
    palette(e.colors, e.overlay)
}

/** A stage background: the family's field at reduced resolution, smoothly scaled up. */
internal fun DrawScope.shaderStage(e: Effect.Shader, t: Float, alpha: Float, size: Size, soft: SoftFields) {
    val image = soft.fx.stage(this, e, t, size)
    drawImage(
        image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = alpha,
        filterQuality = FilterQuality.High, blendMode = if (e.overlay) BlendMode.Screen else BlendMode.SrcOver,
    )
}

/**
 * Frosted glass: this frame's stage image seen through the panel, rendered at half resolution
 * (frost is soft) and clipped at full resolution so the rim stays crisp. Nothing without a
 * compiled shader: the card's own translucent body is the fallback.
 */
internal fun DrawScope.shaderGlass(e: Effect.Glass, t: Float, alpha: Float, size: Size, soft: SoftFields) {
    val a = (e.opacity.at(t) * alpha).coerceIn(0f, 1f)
    if (a <= 0f || e.stage.overlay) return
    val program = soft.fx.glass() ?: return
    soft.fx.of(e.stage.family) ?: return
    val stage = soft.fx.stage(this, e.stage, t, size)
    val k = GLASS_SCALE.toFloat()
    val image = soft.draw(this, size, key = null, scale = GLASS_SCALE) { s ->
        program.child("stage", ImageShader(stage, TileMode.Clamp, TileMode.Clamp))
        program.uniform("uStage", stage.width.toFloat(), stage.height.toFloat())
        program.uniform("uSize", s.width, s.height)
        program.uniform("uFrost", e.frost)
        program.uniform("uPanel", e.x / k, e.y / k, e.width / k, e.height / k)
        program.uniform("uRadius", e.radius / k)
        drawRect(program.brush(), topLeft = Offset(e.x / k - 2f, e.y / k - 2f), size = Size(e.width / k + 4f, e.height / k + 4f))
    }
    val clip = Path().apply { addRoundRect(RoundRect(e.x, e.y, e.x + e.width, e.y + e.height, CornerRadius(e.radius))) }
    clipPath(clip) {
        drawImage(
            image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = a, filterQuality = FilterQuality.High,
        )
    }
}

/** A filtered group's off-screen [content] drawn through its distortion (or the CPU fallback). */
internal fun DrawScope.drawFiltered(content: ImageBitmap, filter: Filter, t: Float, alpha: Float, size: Size, fx: FxPrograms) {
    val amount = filter.amount.at(t)
    val program = fx.of(filter.family)
    if (program == null) return filterFallback(content, filter.family, amount, t, alpha)
    program.child("content", ImageShader(content, TileMode.Clamp, TileMode.Clamp))
    if (filter.family == ShaderFamily.Halation) {
        program.child("bloom", ImageShader(fx.bloom(this, content), TileMode.Clamp, TileMode.Clamp))
        program.uniform("uBloomScale", 1f / FxPrograms.BLOOM_SCALE)
    }
    program.uniform("uSize", size.width, size.height)
    program.uniform("uTime", t)
    program.uniform("uAmount", amount)
    program.uniform("uProgress", filter.progress.at(t))
    program.uniform("uSeed", filter.seed.toFloat())
    drawRect(program.brush(), size = size, alpha = alpha)
}

/**
 * Without a shader compiler the distortions keep their gist: an RGB split (three channel copies
 * added together, offset; exact over opaque content) for the chromatic, lens and glitch families; the content as it is for
 * ripple and halation.
 */
private fun DrawScope.filterFallback(content: ImageBitmap, family: ShaderFamily, amount: Float, t: Float, alpha: Float) {
    val dx = when (family) {
        ShaderFamily.Chromatic, ShaderFamily.Lens -> amount * size.height * 0.006f
        ShaderFamily.Glitch -> amount * size.width * (0.006f + 0.01f * ((sin(t * 75f) + 1f) / 2f))
        else -> 0f
    }
    if (dx < 0.5f) return drawImage(content, alpha = alpha)
    val channels = listOf(-dx to floatArrayOf(1f, 0f, 0f), 0f to floatArrayOf(0f, 1f, 0f), dx to floatArrayOf(0f, 0f, 1f))
    for ((i, ch) in channels.withIndex()) {
        val (off, m) = ch
        val matrix = ColorMatrix(floatArrayOf(m[0], 0f, 0f, 0f, 0f, 0f, m[1], 0f, 0f, 0f, 0f, 0f, m[2], 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        drawImage(
            content, topLeft = Offset(off, 0f), alpha = alpha, colorFilter = ColorFilter.colorMatrix(matrix),
            blendMode = if (i == 0) BlendMode.SrcOver else BlendMode.Plus,
        )
    }
}

/** The CPU stand-ins for the stage families: the same composition with gradients and discs. */
private fun DrawScope.stageFallback(e: Effect.Shader, t: Float, s: Size) {
    val c = e.colors
    val base = if (e.overlay) Color.Black else c[0]
    val accent = c.getOrElse(1) { c.last() }
    val hot = c.getOrElse(2) { accent }
    val ink = c.getOrElse(3) { accent }
    val amount = e.amount.at(t)
    val d = max(s.width, s.height)
    drawRect(base, size = s)
    fun glow(color: Color, at: Offset, radius: Float, a: Float) = drawRect(
        Brush.radialGradient(listOf(color.copy(alpha = a * amount), color.copy(alpha = a * amount * 0.3f), Color.Transparent), center = at, radius = radius), size = s,
    )
    when (e.family) {
        ShaderFamily.Glow -> {
            glow(accent, Offset(s.width * (0.5f + 0.2f * sin(t * 0.11f + e.seed)), -s.height * 0.05f), d * 0.7f, 0.5f)
            glow(hot, Offset(s.width * 0.5f, s.height * 1.05f), d * 0.4f, 0.14f)
        }
        ShaderFamily.Leaks -> {
            val p = t * 0.06f + e.seed
            glow(hot, Offset(s.width * (-0.05f + 0.22f * sin(p * 1.3f)), s.height * 0.18f), d * 0.45f, 0.55f)
            glow(accent, Offset(s.width * (1.05f - 0.2f * sin(p * 1.1f + 2f)), s.height * 0.84f), d * 0.4f, 0.4f)
        }
        ShaderFamily.Bokeh, ShaderFamily.Dust -> {
            val n = if (e.family == ShaderFamily.Bokeh) 14 else 40
            for (i in 0 until n) {
                val hx = fract(sin(i * 12.9898f + e.seed * 78.233f) * 43758.547f)
                val hy = fract(sin(i * 39.346f + e.seed * 11.135f) * 24634.635f)
                val r = if (e.family == ShaderFamily.Bokeh) d * (0.05f + 0.1f * fract(hx * 7.3f)) else d * 0.004f
                val y = fract(hy - t * 0.012f) * s.height
                val color = if (e.family == ShaderFamily.Dust) ink else if (i % 2 == 0) accent else hot
                drawCircle(color.copy(alpha = (if (e.family == ShaderFamily.Bokeh) 0.16f else 0.5f) * amount), r, Offset(hx * s.width, y))
            }
        }
        else -> {
            glow(accent, Offset(s.width * (0.5f + 0.4f * sin(t * 0.12f + e.seed)), s.height * 0.3f), d * 0.6f, 0.3f)
            glow(hot, Offset(s.width * (0.5f - 0.4f * sin(t * 0.09f)), s.height * 0.75f), d * 0.6f, 0.26f)
        }
    }
}

private fun fract(x: Float) = x - kotlin.math.floor(x)

private const val GLASS_SCALE = 2
