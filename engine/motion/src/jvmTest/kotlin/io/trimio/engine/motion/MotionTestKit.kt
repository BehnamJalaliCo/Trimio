package io.trimio.engine.motion

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.io.File

/** Off-screen rendering of compositions for tests: PNG stills, contact sheets and MP4 via ffmpeg. */
class MotionTestKit(val composition: Composition, media: MediaSource = MediaSource.None) {
    private val surface = Surface.makeRaster(ImageInfo(composition.width, composition.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
    private val scope = CanvasDrawScope()
    val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
    val renderer = MotionRenderer(composition, measurer, runBlocking { MotionFonts.load() }, media)

    fun draw(t: Float): org.jetbrains.skia.Image {
        surface.canvas.clear(0xFF000000.toInt())
        scope.draw(Density(1f), LayoutDirection.Ltr, surface.canvas.asComposeCanvas(), Size(composition.width.toFloat(), composition.height.toFloat())) {
            renderer.render(this, t)
        }
        return surface.makeImageSnapshot()
    }

    fun png(t: Float, file: File) {
        file.parentFile.mkdirs()
        file.writeBytes(draw(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    /** Frames at [times] tiled into one image ([columns] wide), scaled by [scale]. */
    fun contactSheet(times: List<Float>, file: File, columns: Int = 4, scale: Float = 0.25f) {
        val w = (composition.width * scale).toInt()
        val h = (composition.height * scale).toInt()
        val rows = (times.size + columns - 1) / columns
        val sheet = Surface.makeRasterN32Premul(w * columns, h * rows)
        for ((i, t) in times.withIndex()) {
            val img = draw(t)
            sheet.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeXYWH(((i % columns) * w).toFloat(), ((i / columns) * h).toFloat(), w.toFloat(), h.toFloat()))
        }
        file.parentFile.mkdirs()
        file.writeBytes(sheet.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    fun mp4(file: File, from: Float = 0f, to: Float = composition.duration, audio: File? = null) {
        file.parentFile.mkdirs()
        val fps = composition.fps
        val process = ProcessBuilder(
            buildList {
                addAll(listOf("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${composition.width}x${composition.height}", "-r", "$fps", "-i", "-"))
                if (audio != null) addAll(listOf("-i", audio.absolutePath, "-c:a", "aac", "-b:a", "192k"))
                addAll(listOf("-c:v", "libx264", "-preset", "medium", "-crf", "17", "-pix_fmt", "yuv420p", "-movflags", "+faststart", file.absolutePath))
            },
        ).redirectErrorStream(true).start()
        val out = process.outputStream.buffered(1 shl 20)
        val bitmap = Bitmap().apply { allocPixels(ImageInfo(composition.width, composition.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
        var f = (from * fps).toInt()
        while (f < (to * fps).toInt()) {
            draw(f.toFloat() / fps).readPixels(bitmap)
            out.write(bitmap.readPixels()!!)
            f++
        }
        out.close()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "ffmpeg failed: $log" }
    }

    companion object {
        /** Voices a cue list with the procedural effects and writes a 48 kHz mono WAV. */
        fun soundtrack(cues: List<io.trimio.engine.motion.recipe.Sfx>, duration: Float, file: File) {
            val rate = 48_000
            val mix = FloatArray((duration * rate).toInt())
            val sfx = io.trimio.engine.assets.ProceduralSfx(rate)
            for ((i, cue) in cues.withIndex()) {
                val (id, gain) = when (cue.kind) {
                    io.trimio.engine.motion.recipe.SfxKind.Whoosh -> "whoosh" to 0.5f
                    io.trimio.engine.motion.recipe.SfxKind.Swish -> "swoosh" to 0.35f
                    io.trimio.engine.motion.recipe.SfxKind.Hit -> "impact" to 0.45f
                    io.trimio.engine.motion.recipe.SfxKind.Boom -> "impact" to 0.8f
                    io.trimio.engine.motion.recipe.SfxKind.Pop -> "pop" to 0.4f
                    io.trimio.engine.motion.recipe.SfxKind.Click -> "glitch" to 0.35f
                    io.trimio.engine.motion.recipe.SfxKind.Riser -> "riser" to 0.3f
                    io.trimio.engine.motion.recipe.SfxKind.Tick -> "tick" to 0.3f
                    io.trimio.engine.motion.recipe.SfxKind.Shimmer -> "shimmer" to 0.35f
                }
                val pcm = sfx.generate(id, seed = i + 1) ?: continue
                val start = (cue.at * rate).toInt()
                for (j in pcm.samples.indices) {
                    val k = start + j
                    if (k in mix.indices) mix[k] += pcm.samples[j] * gain * cue.gain
                }
            }
            val peak = mix.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1f) ?: 1f
            val bytes = java.io.ByteArrayOutputStream()
            fun le(v: Int, n: Int) = repeat(n) { bytes.write((v shr (8 * it)) and 0xFF) }
            bytes.write("RIFF".toByteArray()); le(36 + mix.size * 2, 4); bytes.write("WAVEfmt ".toByteArray())
            le(16, 4); le(1, 2); le(1, 2); le(rate, 4); le(rate * 2, 4); le(2, 2); le(16, 2)
            bytes.write("data".toByteArray()); le(mix.size * 2, 4)
            for (v in mix) le((v / peak * 32_000f).toInt(), 2)
            file.parentFile.mkdirs()
            file.writeBytes(bytes.toByteArray())
        }

        fun ffmpeg(): Boolean = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
    }
}
