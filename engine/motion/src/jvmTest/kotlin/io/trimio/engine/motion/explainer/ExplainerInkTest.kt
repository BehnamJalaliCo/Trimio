package io.trimio.engine.motion.explainer

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

/**
 * The stage is never faded or empty: sampled every half second of the reference narration's film
 * (outside the cut between chapters), the live stage always holds clear ink — never a whole stage dimmed
 * or a morph passing through nothing. A lone small piece (a kicker, the thinking dots) is about 0.15 % of
 * the stage; an empty or faded stage is under 0.1 %.
 */
class ExplainerInkTest {
    @Test
    fun theStageAlwaysHoldsInk() {
        val low = listOf("fa", "en").flatMap(::faded)
        if (low.isNotEmpty()) fail("faded or empty stage:\n" + low.joinToString("\n"))
    }

    /** The sampled moments of the [lang] reference film whose stage holds too little ink. */
    private fun faded(lang: String): List<String> {
        val film = ExplainerLab.film("assistants-$lang")
        val out = ExplainerLab.compose(film, 1080, 1920)
        val stage = out.report.frame.stage
        val bg = PaletteBook.named(film.art.palette).background
        val chapters = film.chapters.sortedBy { it.start }
        val cuts = chapters.drop(1).map { it.start }
        fun skipped(t: Double) = cuts.any { c -> t > c - CUT_BEFORE && t < c + CUT_AFTER } ||
            chapters.any { c -> t >= c.start && t < (c.beats.minOfOrNull { it.at } ?: c.start) + SETTLE }
        val times = generateSequence(chapters.first().beats.minOf { it.at } + SETTLE) { it + STEP }
            .takeWhile { it < out.composition.duration - 0.1 }.filterNot(::skipped).toList()
        return ExplainerLab.frames(out.composition, SCALE).use { frames ->
            times.mapNotNull { t ->
                val share = ink(frames.draw(t.toFloat()), stage, bg)
                if (share < MIN_INK) "$lang ${"%.1f".format(t)}s ink ${"%.4f".format(share)}" else null
            }
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
        const val MIN_INK = 0.001f
    }
}
