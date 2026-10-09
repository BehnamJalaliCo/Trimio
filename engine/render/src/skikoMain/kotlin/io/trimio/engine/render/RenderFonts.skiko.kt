package io.trimio.engine.render

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as SkikoFont

internal actual fun platformFont(identity: String, bytes: ByteArray, weight: FontWeight): Font =
    SkikoFont("$identity-${weight.weight}", { bytes }, weight, FontStyle.Normal, FontVariation.Settings(weight, FontStyle.Normal))
