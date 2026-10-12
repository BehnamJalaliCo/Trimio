package io.trimio.engine.autopilot

import io.trimio.engine.motion.export.FfmpegFootage
import io.trimio.engine.motion.export.MotionExporter
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

/**
 * A contact sheet of a production for review: one small frame at every graphic once it has
 * landed (and between them where only captions run), each labelled with its time and what is up.
 */
object Sheet {
    private const val TILE_W = 270
    private const val TILE_H = 480
    private const val LABEL = 34
    private const val COLUMNS = 6
    private const val LAND = 0.8f

    fun times(p: Autopilot.Production): List<Float> {
        val graphics = p.compiled.beats.filter { it.recipe != "pop-captions" }
        val landed = graphics.map { minOf(it.at + LAND, (it.at + it.out) / 2f) }
        val d = p.compiled.composition.duration
        // Where nothing lands for three seconds, a look at what the viewer sees there too.
        val fill = (0 until (d / 3f).toInt()).map { it * 3f + 1.5f }.filter { t -> landed.none { kotlin.math.abs(it - t) < 1.5f } }
        return (landed + fill + 0.3f).filter { it < d }.map { kotlin.math.round(it * 10f) / 10f }.distinct().sorted()
    }

    fun draw(p: Autopilot.Production, video: File, out: File, times: List<Float> = times(p)) {
        val c = p.compiled.composition
        val rows = (times.size + COLUMNS - 1) / COLUMNS
        val sheet = Surface.makeRasterN32Premul(COLUMNS * TILE_W, rows * (TILE_H + LABEL))
        sheet.canvas.clear(Color.makeRGB(18, 18, 18))
        val font = Font(null, 15f)
        val ink = Paint().apply { color = Color.WHITE }
        FfmpegFootage(video, c.fps, c.width, c.height).use { footage ->
            val frames = MotionExporter.Frames(c, footage)
            times.forEachIndexed { i, t ->
                val x = (i % COLUMNS) * TILE_W.toFloat()
                val y = (i / COLUMNS) * (TILE_H + LABEL).toFloat()
                val image = frames.draw(t)
                sheet.canvas.drawImageRect(image, Rect.makeWH(c.width.toFloat(), c.height.toFloat()), Rect.makeXYWH(x, y + LABEL, TILE_W.toFloat(), TILE_H.toFloat()))
                val up = p.compiled.beats.filter { it.recipe != "pop-captions" && t >= it.at && t < it.out }.joinToString(" ") { it.recipe }
                sheet.canvas.drawString("${"%.1f".format(t)}s $up".take(40), x + 4f, y + 22f, font, ink)
            }
        }
        out.writeBytes(sheet.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
