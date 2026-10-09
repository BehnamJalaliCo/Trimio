package io.trimio.core.brand

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.trimio.core.brand.resources.Res
import io.trimio.core.brand.resources.accent_300
import io.trimio.core.brand.resources.accent_400
import io.trimio.core.brand.resources.accent_500
import io.trimio.core.brand.resources.accent_600
import io.trimio.core.brand.resources.accent_700
import io.trimio.core.brand.resources.accent_900
import io.trimio.core.brand.resources.display_100
import io.trimio.core.brand.resources.display_1000
import io.trimio.core.brand.resources.display_200
import io.trimio.core.brand.resources.display_300
import io.trimio.core.brand.resources.display_400
import io.trimio.core.brand.resources.display_500
import io.trimio.core.brand.resources.display_600
import io.trimio.core.brand.resources.display_700
import io.trimio.core.brand.resources.display_800
import io.trimio.core.brand.resources.display_900
import io.trimio.core.brand.resources.display_950
import io.trimio.core.brand.resources.expressive
import io.trimio.core.brand.resources.ui
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

/**
 * The four type families of the brand, by role:
 *  - [ui]: interface text; variable (`wght` 100–1000), so weight can animate smoothly.
 *  - [expressive]: headlines and kinetic captions; variable (`wght` 100–900).
 *  - [display]: heavy display and poster type, 11 static cuts from hairline to "fat".
 *  - [accent]: a contrasting voice for labels, numbers and tags, 6 static cuts.
 */
object BrandFonts {
    private val variableWeights = (1..10).map { FontWeight(it * 100) }

    private val displayCuts: List<Pair<Int, FontResource>> = listOf(
        100 to Res.font.display_100, 200 to Res.font.display_200, 300 to Res.font.display_300,
        400 to Res.font.display_400, 500 to Res.font.display_500, 600 to Res.font.display_600,
        700 to Res.font.display_700, 800 to Res.font.display_800, 900 to Res.font.display_900,
        950 to Res.font.display_950, 1000 to Res.font.display_1000,
    )

    private val accentCuts: List<Pair<Int, FontResource>> = listOf(
        300 to Res.font.accent_300, 400 to Res.font.accent_400, 500 to Res.font.accent_500,
        600 to Res.font.accent_600, 700 to Res.font.accent_700, 900 to Res.font.accent_900,
    )

    @Composable
    fun ui(): FontFamily = FontFamily(variableWeights.map { Font(Res.font.ui, weight = it) })

    @Composable
    fun expressive(): FontFamily = FontFamily(variableWeights.take(9).map { Font(Res.font.expressive, weight = it) })

    @Composable
    fun display(): FontFamily = FontFamily(displayCuts.map { (w, res) -> Font(res, weight = FontWeight(w)) })

    @Composable
    fun accent(): FontFamily = FontFamily(accentCuts.map { (w, res) -> Font(res, weight = FontWeight(w)) })

    /** Raw font files for the offline renderer (outside composition), by role file name. */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun bytes(file: String): ByteArray = Res.readBytes("font/$file.ttf")

    /** Static cut of [family] nearest to [weight], for renderers that load one file per weight. */
    fun nearestCut(family: Role, weight: Int): String = when (family) {
        Role.Ui -> "ui"
        Role.Expressive -> "expressive"
        Role.Display -> "display_" + displayCuts.minBy { kotlin.math.abs(it.first - weight) }.first
        Role.Accent -> "accent_" + accentCuts.minBy { kotlin.math.abs(it.first - weight) }.first
    }

    enum class Role { Ui, Expressive, Display, Accent }
}
