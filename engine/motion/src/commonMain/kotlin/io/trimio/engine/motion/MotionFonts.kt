package io.trimio.engine.motion

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.trimio.core.brand.BrandFonts

/**
 * The brand families for offline rendering (outside composition). Variable faces expose every
 * weight through their `wght` axis; static faces register each cut at its own weight so the
 * resolver picks the nearest one.
 */
class MotionFonts private constructor(private val families: Map<BrandFonts.Role, FontFamily>) {

    fun family(role: BrandFonts.Role): FontFamily = families.getValue(role)

    companion object {
        private val variableWeights = (1..10).map { it * 100 }
        private val displayCuts = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 950, 1000)
        private val accentCuts = listOf(300, 400, 500, 600, 700, 900)

        private var cached: MotionFonts? = null

        suspend fun load(): MotionFonts {
            cached?.let { return it }
            val ui = BrandFonts.bytes("ui")
            val expressive = BrandFonts.bytes("expressive")
            fun variable(id: String, bytes: ByteArray, weights: List<Int>) =
                FontFamily(weights.map { platformFont(id, bytes, FontWeight(it), variable = true) })
            // Without the brand key every role falls back to one variable face: keep its axis live.
            suspend fun static(prefix: String, cuts: List<Int>) = FontFamily(
                cuts.map { w ->
                    val bytes = BrandFonts.bytes("${prefix}_$w")
                    platformFont("${prefix}_$w", bytes, FontWeight(w), variable = bytes.contentEquals(ui))
                },
            )
            return MotionFonts(
                mapOf(
                    BrandFonts.Role.Ui to variable("ui", ui, variableWeights),
                    BrandFonts.Role.Expressive to variable("expressive", expressive, variableWeights.take(9)),
                    BrandFonts.Role.Display to static("display", displayCuts),
                    BrandFonts.Role.Accent to static("accent", accentCuts),
                ),
            ).also { cached = it }
        }
    }
}

/** A font from raw bytes; variable faces get their `wght` axis set to [weight]. */
internal expect fun platformFont(identity: String, bytes: ByteArray, weight: FontWeight, variable: Boolean): Font
