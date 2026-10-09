package io.trimio.engine.render

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.trimio.core.designsystem.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Fonts for offline rendering, outside composition. Loads the bundled Vazirmatn variable font and
 * exposes each weight through the font's `wght` axis.
 */
object RenderFonts {
    private var cached: FontFamily? = null

    @OptIn(ExperimentalResourceApi::class)
    suspend fun vazirmatn(): FontFamily {
        cached?.let { return it }
        val bytes = Res.readBytes("font/vazirmatn.ttf")
        val weights = listOf(300, 400, 500, 600, 700, 800, 900)
        return FontFamily(weights.map { platformFont("vazirmatn", bytes, FontWeight(it)) }).also { cached = it }
    }
}

/** A font from raw bytes with its variation axis set to [weight]. */
internal expect fun platformFont(identity: String, bytes: ByteArray, weight: FontWeight): Font
