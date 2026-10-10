package io.trimio.engine.motion

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as SkikoFont

internal actual fun platformFont(identity: String, bytes: ByteArray, weight: FontWeight, variable: Boolean): Font =
    SkikoFont(
        "$identity-${weight.weight}", { bytes }, weight, FontStyle.Normal,
        if (variable) FontVariation.Settings(weight, FontStyle.Normal) else FontVariation.Settings(),
    )
