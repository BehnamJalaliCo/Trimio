package io.trimio.core.designsystem.shader

import io.trimio.core.designsystem.theme.DarkTrimioColors
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compiles every shader with the real Skia SkSL compiler and rasterises it. Catches syntax that
 * would only fail at runtime on a device. Renders land in build/shader-previews for visual review.
 */
class ShaderCompileTest {

    private val out = File("build/shader-previews").apply { mkdirs() }

    private fun render(name: String, source: String, w: Int, h: Int, uniforms: RuntimeShaderBuilder.() -> Unit): IntArray {
        val builder = RuntimeShaderBuilder(RuntimeEffect.makeForShader(source))
        builder.uniform("iResolution", w.toFloat(), h.toFloat())
        builder.uniforms()
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.clear(0xFF101018.toInt())
        surface.canvas.drawRect(Rect.makeWH(w.toFloat(), h.toFloat()), Paint().apply { shader = builder.makeShader() })
        val image = surface.makeImageSnapshot()
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        val bitmap = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(w, h) }
        image.readPixels(bitmap)
        return IntArray(w * h) { bitmap.getColor(it % w, it / w) }
    }

    private fun RuntimeShaderBuilder.color(name: String, c: androidx.compose.ui.graphics.Color) = uniform(name, c.red, c.green, c.blue)

    private fun distinctColours(pixels: IntArray) = pixels.toSet().size

    @Test
    fun auroraCompilesAndProducesVariedImage() {
        val c = DarkTrimioColors
        val pixels = render("aurora", ShaderSources.aurora, 360, 640) {
            uniform("iTime", 12f)
            uniform("iEnergy", 0.6f)
            color("cBase", c.auroraBase); color("cA", c.auroraA); color("cB", c.auroraB); color("cC", c.auroraC)
        }
        assertTrue(distinctColours(pixels) > 1000, "aurora should be a rich gradient, not flat")
    }

    @Test
    fun liquidOrbFillsBottomAndIsTransparentAtCorners() {
        val c = DarkTrimioColors
        listOf(0.25f, 0.62f, 0.95f).forEach { progress ->
            val size = 300
            val pixels = render("liquid-orb-${(progress * 100).toInt()}", ShaderSources.liquidOrb, size, size) {
                uniform("iTime", 3f)
                uniform("iProgress", progress)
                color("cDeep", c.primary); color("cSurface", c.accentCyan); color("cGlow", c.accentMagenta)
            }
            val background = 0xFF101018.toInt()
            assertTrue(pixels[0] == background, "corner outside the sphere must stay transparent")
            val bottomCentre = pixels[(size * 0.9).toInt() * size + size / 2]
            assertTrue(bottomCentre != background, "liquid must fill the bottom of the sphere")
        }
    }
}
