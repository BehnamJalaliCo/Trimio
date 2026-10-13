package io.trimio.engine.motion.explainer

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

/**
 * The stage is never faded or empty: sampled every half second of the reference narration's film
 * (outside the cut between chapters), the live stage always holds clear ink — at least one element
 * at full strength, not a whole stage dimmed or a morph passing through nothing.
 */
class ExplainerInkTest {
    @Test
    fun theStageAlwaysHoldsInk() {
        for (lang in listOf("fa", "en")) {
            val film = ExplainerLab.film("assistants-$lang")
            val out = ExplainerLab.compose(film, 1080, 1920)
            val stage = out.report.frame.stage
            val bg = PaletteBook.named(film.art.palette).background
            val chapters = film.chapters.sortedBy { it.start }
            val first = chapters.first().beats.minOf { it.at } + SETTLE
            val cuts = chapters.drop(1).map { it.start }
            val end = out.composition.duration - 0.1
            val low = ArrayList<String>()
            ExplainerLab.frames(out.composition, SCALE).use { frames ->
                var t = first
                while (t < end) {
                    val nearCut = cuts.any { c -> t > c - CUT_BEFORE && t < c + CUT_AFTER }
                    val opening = chapters.any { c -> t >= c.start && t < (c.beats.minOfOrNull { it.at } ?: c.start) + SETTLE }
                    if (!nearCut && !opening) {
                        val share = ink(frames.draw(t.toFloat()), stage, bg)
                        if (share < MIN_INK) low += "$lang ${"%.1f".format(t)}s ink ${"%.4f".format(share)}"
                    }
                    t += STEP
                }
            }
            if (low.isNotEmpty()) fail("faded or empty stage:\n" + low.joinToString("\n"))
        }
    }

    /** The share of the stage's pixels clearly off the paper. */
    private fun ink(img: org.jetbrains.skia.Image, stage: io.trimio.engine.motion.recipe.Box, bg: androidx.compose.ui.graphics.Color): Float {
        val bmp = Bitmap()
        bmp.allocPixels(ImageInfo.makeN32(img.width, img.height, ColorAlphaType.PREMUL))
        img.readPixels(bmp, 0, 0)
        val bytes = bmp.readPixels()!!
        bmp.close()
        val w = img.width
        val paper = intArrayOf((bg.blue * 255).toInt(), (bg.green * 255).toInt(), (bg.red * 255).toInt())
        var ink = 0
        var all = 0
        for (y in (stage.top * SCALE).toInt() until (stage.bottom * SCALE).toInt().coerceAtMost(img.height)) {
            for (x in (stage.left * SCALE).toInt() until (stage.right * SCALE).toInt().coerceAtMost(w)) {
                val i = (y * w + x) * 4
                val d = (0..2).maxOf { c -> abs((bytes[i + c].toInt() and 0xFF) - paper[c]) }
                all++
                if (d > STRONG) ink++
            }
        }
        return ink / all.coerceAtLeast(1).toFloat()
    }

    private companion object {
        const val SCALE = 0.2f
        const val STEP = 0.5
        const val SETTLE = 0.6
        const val CUT_BEFORE = 0.8
        const val CUT_AFTER = 0.2
        const val STRONG = 60
        const val MIN_INK = 0.004f
    }
}
