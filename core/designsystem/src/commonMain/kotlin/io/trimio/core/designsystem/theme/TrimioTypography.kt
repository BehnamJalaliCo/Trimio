package io.trimio.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.resources.Res
import io.trimio.core.designsystem.resources.vazirmatn
import org.jetbrains.compose.resources.Font

@Immutable
data class TrimioTypography(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    /** Tabular figures for counters, timers and percentages so digits don't jitter while changing. */
    val numeric: TextStyle,
)

/**
 * Vazirmatn variable font (Persian + Latin in one family, OFL licensed). Every weight maps to the
 * same file through the `wght` axis, so animated weight changes are smooth and cost no extra assets.
 */
@Composable
fun vazirmatnFamily(): FontFamily = FontFamily(
    listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black)
        .map { Font(Res.font.vazirmatn, weight = it) },
)

@Composable
fun trimioTypography(): TrimioTypography {
    val family = vazirmatnFamily()
    fun style(size: Int, weight: FontWeight, line: Float = 1.35f, tracking: Float = 0f) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * line).sp,
        letterSpacing = tracking.em,
    )
    return TrimioTypography(
        display = style(44, FontWeight.Black, line = 1.1f, tracking = -0.02f),
        headline = style(28, FontWeight.ExtraBold, line = 1.2f, tracking = -0.01f),
        title = style(18, FontWeight.Bold),
        body = style(15, FontWeight.Normal, line = 1.6f),
        label = style(13, FontWeight.SemiBold, tracking = 0.01f),
        caption = style(11, FontWeight.Medium, tracking = 0.02f),
        numeric = style(15, FontWeight.Bold).copy(fontFeatureSettings = "tnum"),
    )
}
