package io.trimio.engine.illustration.quality

import io.trimio.engine.illustration.Colors
import io.trimio.engine.illustration.Illustration
import io.trimio.engine.illustration.Raster
import io.trimio.engine.illustration.style.FilmLook
import kotlin.math.abs
import kotlin.math.max

/** Measured look of one picture: how much ink, how much accent, and how much colour that belongs to neither. */
data class StyleStats(
    /** Share of the picture's opaque pixels darker than 40% grey (lines, hatching, dark fills). */
    val ink: Float,
    /** Share of opaque pixels in the accent's hue. */
    val accent: Float,
    /** Share of opaque pixels with a colour that is neither grey nor the accent (must be ~0). */
    val foreign: Float,
    /** Share of the frame the picture covers. */
    val coverage: Float,
) {
    companion object {
        fun of(image: Raster, look: FilmLook): StyleStats {
            val accentHue = Colors.hue(look.accent)
            val accentDark = Colors.hue(look.dark)
            var opaque = 0
            var ink = 0
            var accent = 0
            var foreign = 0
            for (c in image.argb) {
                if (Colors.a(c) < OPAQUE) continue
                opaque++
                if (Colors.luma(c) < MID) ink++
                if (Colors.saturation(c) < GREY || Colors.luma(c) < DARK_ENOUGH) continue
                val hue = Colors.hue(c)
                when {
                    Colors.hueDistance(hue, accentHue) <= HUE_TOLERANCE -> accent++
                    Colors.hueDistance(hue, accentDark) <= HUE_TOLERANCE -> Unit
                    else -> foreign++
                }
            }
            val n = max(1, opaque).toFloat()
            return StyleStats(ink / n, accent / n, foreign / n, opaque.toFloat() / image.argb.size)
        }

        private const val OPAQUE = 128
        private const val MID = 0.4f
        private const val GREY = 0.2f
        private const val DARK_ENOUGH = 0.12f
        private const val HUE_TOLERANCE = 22f
    }
}

/**
 * The jury's checks over a film's illustrations: every picture styled with the film's one look, no
 * colour outside grey and the accent pair, ink density within a band around the film's median (no
 * picture much heavier or lighter than the rest), each picture showing its request, and none with
 * letter-like artefacts.
 */
object StyleCheck {
    data class Issue(val index: Int, val kind: String, val detail: String)

    data class Report(val issues: List<Issue>, val stats: List<StyleStats>) {
        val consistent: Boolean get() = issues.none { it.kind in setOf("look", "colour", "density") }
        val clean: Boolean get() = issues.isEmpty()

        /** 0..10 for the jury: two points off per issue, never below zero. */
        val score: Float get() = (MAX_SCORE - issues.size * ISSUE_COST).coerceAtLeast(0f)
    }

    fun film(pictures: List<Illustration>, look: FilmLook, minFit: Float = MIN_FIT): Report {
        val stats = pictures.map { StyleStats.of(it.image, look) }
        val inks = stats.map { it.ink }.sorted()
        val median = inks.getOrElse(inks.size / 2) { 0f }
        val issues = ArrayList<Issue>()
        pictures.forEachIndexed { i, p ->
            val s = stats[i]
            if (p.look != look.id) issues += Issue(i, "look", "styled with ${p.look}, film is ${look.id}")
            if (s.foreign > MAX_FOREIGN) issues += Issue(i, "colour", "foreign colour on ${pct(s.foreign)} of the picture")
            if (median > 0f && (s.ink > median * DENSITY_BAND || s.ink < median / DENSITY_BAND)) {
                issues += Issue(i, "density", "ink ${pct(s.ink)} against the film's ${pct(median)}")
            }
            if (p.fit < minFit) issues += Issue(i, "subject", "shows ${pct(p.fit)} of what was asked; missing ${p.missing}")
            val text = TextArtefacts.find(p.image)
            if (text.textLike) issues += Issue(i, "text", "letter-like marks (${text.longestChain} in a row)")
        }
        return Report(issues, stats)
    }

    private fun pct(v: Float) = "${(v * PERCENT).toInt()}%"

    /** Relative difference of two stats' ink (tests). */
    fun inkDistance(a: StyleStats, b: StyleStats): Float = abs(a.ink - b.ink) / max(a.ink, b.ink).coerceAtLeast(EPS)

    private const val MAX_FOREIGN = 0.005f
    /**
     * Ink legitimately varies with the subject (a person in a dark coat against a pale cloud is four to
     * six times heavier); only a picture far outside that range is a different hand.
     */
    private const val DENSITY_BAND = 8f
    private const val MIN_FIT = 0.5f
    private const val MAX_SCORE = 10f
    private const val ISSUE_COST = 2f
    private const val PERCENT = 100
    private const val EPS = 1e-6f
}
