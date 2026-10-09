package io.trimio.engine.render

import androidx.compose.ui.graphics.Color
import io.trimio.core.model.style.Palette

/** Parses `#RRGGBB` / `#RRGGBBAA`. Invalid input renders magenta so broken packs are obvious. */
fun parseColor(hex: String): Color {
    val h = hex.removePrefix("#")
    val v = h.toLongOrNull(16) ?: return Color.Magenta
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(((v and 0xFF) shl 24) or (v shr 8))
        else -> Color.Magenta
    }
}

/** A style palette resolved to colours once per renderer. */
class ResolvedPalette(palette: Palette) {
    val background: List<Color> = palette.background.map(::parseColor).ifEmpty { listOf(Color.Black) }
    val text = parseColor(palette.text)
    val accent = parseColor(palette.accent)
    val accent2 = parseColor(palette.accent2)
    val emphasisText = parseColor(palette.emphasisText)
    val shadow = parseColor(palette.shadow)

    fun role(name: String): Color = when (name) {
        "accent" -> accent
        "accent2" -> accent2
        "emphasisText" -> emphasisText
        else -> text
    }

    fun background(i: Int): Color = background[i.coerceIn(0, background.lastIndex)]
}
