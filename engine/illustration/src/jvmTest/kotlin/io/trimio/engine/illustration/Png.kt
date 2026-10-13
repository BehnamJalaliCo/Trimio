package io.trimio.engine.illustration

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Test helpers: PNG output and contact sheets. */
object Png {
    fun image(r: Raster, background: Int? = null): BufferedImage {
        val img = BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until r.height) {
            for (x in 0 until r.width) {
                val c = r[x, y]
                img.setRGB(x, y, if (background != null) Colors.over(c, background) else c)
            }
        }
        return img
    }

    fun write(r: Raster, file: File, background: Int? = null) {
        file.parentFile?.mkdirs()
        ImageIO.write(image(r, background), "png", file)
    }

    /** A grid of [cells] (picture and caption), [columns] wide, each cell [cell] px, on [paper]. */
    fun sheet(cells: List<Pair<Raster, String>>, columns: Int, cell: Int, file: File, title: String, backdrop: (Int) -> Int? = { null }) {
        val rows = (cells.size + columns - 1) / columns
        val captionH = 34
        val titleH = 40
        val img = BufferedImage(columns * cell, titleH + rows * (cell + captionH), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color(40, 40, 46)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        g.drawString(title, 10, 27)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        cells.forEachIndexed { i, (r, caption) ->
            val x = (i % columns) * cell
            val y = titleH + (i / columns) * (cell + captionH)
            backdrop(i)?.let { c ->
                g.color = Color(c, true)
                val d = (cell * 0.8).toInt()
                g.fillOval(x + (cell - d) / 2, y + (cell - d) / 2 + cell / 10, d, d)
            }
            val s = minOf((cell - 8).toFloat() / r.width, (cell - 8).toFloat() / r.height)
            val w = (r.width * s).toInt()
            val h = (r.height * s).toInt()
            g.drawImage(image(r.resized(maxOf(1, w), maxOf(1, h))), x + (cell - w) / 2, y + (cell - h) / 2, null)
            g.color = Color(70, 70, 80)
            caption.chunked(48).take(2).forEachIndexed { k, line -> g.drawString(line, x + 6, y + cell + 13 + k * 14) }
        }
        g.dispose()
        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)
    }
}
