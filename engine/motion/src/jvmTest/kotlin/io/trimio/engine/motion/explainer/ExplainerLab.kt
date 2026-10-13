package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MotionTestKit
import io.trimio.engine.motion.export.MotionExporter
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

/** The explainer bench: the sample films, a stage on the test fonts, frames and contact sheets. */
object ExplainerLab {
    val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, Color.Black, Group(emptyList())))
    private val json = Json { ignoreUnknownKeys = true }

    fun film(name: String): ExplainerFilm =
        json.decodeFromString(ExplainerFilm.serializer(), ExplainerLab::class.java.getResource("/explainer/$name.json")!!.readText())

    fun stage(icons: IconResolver = IconResolver.None) = ExplainerStage(probe.renderer.text, icons)

    fun compose(film: ExplainerFilm, w: Int = 1080, h: Int = 1920): ExplainerStage.Output = stage().compose(film, w, h)

    /** A renderer for [c] at [scale] of its size. */
    fun frames(c: Composition, scale: Float = 1f) = MotionExporter.Frames(c, MediaSource.None, scale)

    /** Frames at [times] tiled [columns] wide, each [scale] of the composition. */
    fun sheet(c: Composition, times: List<Float>, file: File, columns: Int, scale: Float, crop: Rect? = null) {
        val frames = frames(c, scale)
        val src = crop?.let { Rect.makeLTRB(it.left * scale, it.top * scale, it.right * scale, it.bottom * scale) } ?: Rect.makeWH(frames.width.toFloat(), frames.height.toFloat())
        val w = src.width.toInt()
        val h = src.height.toInt()
        val rows = (times.size + columns - 1) / columns
        val gap = 6
        val surface = Surface.makeRasterN32Premul(columns * (w + gap) - gap, rows * (h + gap) - gap)
        surface.canvas.clear(0xFF202020.toInt())
        for ((i, t) in times.withIndex()) {
            val img: Image = frames.draw(t)
            surface.canvas.drawImageRect(img, src, Rect.makeXYWH(((i % columns) * (w + gap)).toFloat(), ((i / columns) * (h + gap)).toFloat(), w.toFloat(), h.toFloat()))
        }
        frames.close()
        file.parentFile.mkdirs()
        file.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    /** One frame from each composition (at its time), tiled [columns] wide at [scale]. */
    fun tiles(frames: List<Pair<Composition, Float>>, file: File, columns: Int, scale: Float) {
        val imgs = frames.map { (c, t) -> frames(c, scale).use { f -> f.draw(t).let { img -> Image.makeFromBitmap(org.jetbrains.skia.Bitmap.makeFromImage(img)) } } }
        val w = imgs.first().width
        val h = imgs.first().height
        val gap = 6
        val rows = (imgs.size + columns - 1) / columns
        val surface = Surface.makeRasterN32Premul(columns * (w + gap) - gap, rows * (h + gap) - gap)
        surface.canvas.clear(0xFF202020.toInt())
        for ((i, img) in imgs.withIndex()) surface.canvas.drawImage(img, ((i % columns) * (w + gap)).toFloat(), ((i / columns) * (h + gap)).toFloat())
        file.parentFile.mkdirs()
        file.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    /** Moments worth seeing: each beat once it has settled. */
    fun settled(film: ExplainerFilm): List<Float> = film.chapters.flatMap { ch -> ch.beats.map { (minOf(it.until, it.at + 1.1)).toFloat() } }.distinct().sorted()
}
