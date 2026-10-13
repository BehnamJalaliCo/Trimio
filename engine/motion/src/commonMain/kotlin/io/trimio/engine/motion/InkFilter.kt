package io.trimio.engine.motion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * A picture drawn as ink ([Grade.ink]): its darkness becomes [ink]'s opacity (luminance to alpha,
 * steepened by [contrast]) and its paper drops out, so a sketch sits on any ground.
 */
internal fun inkFilter(ink: Color, contrast: Float): ColorFilter {
    val k = contrast.coerceAtLeast(0.1f)
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, ink.red * 255f,
                0f, 0f, 0f, 0f, ink.green * 255f,
                0f, 0f, 0f, 0f, ink.blue * 255f,
                -0.2126f * k, -0.7152f * k, -0.0722f * k, 0f, 255f * k,
            ),
        ),
    )
}
