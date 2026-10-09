package io.trimio.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Trimio's line icon set: 24-unit grid, 1.8 stroke, round caps — one consistent hand across the
 * app, no icon-font dependency, identical on every platform. Directional icons are auto-mirrored
 * in right-to-left layouts (docs/DESIGN.md §9); object icons are not.
 */
object TrimioIcons {

    private fun icon(name: String, mirrored: Boolean = false, filled: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirrored).apply {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    val Back = icon("back", mirrored = true) { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
    val ChevronForward = icon("chevron", mirrored = true) { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    val Close = icon("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Plus = icon("plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Check = icon("check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
    val Send = icon("send") { moveTo(12f, 19f); lineTo(12f, 5f); moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) }

    val Play = icon("play", filled = true) { moveTo(8f, 5.5f); lineTo(19f, 12f); lineTo(8f, 18.5f); close() }
    val Pause = icon("pause", filled = true) {
        moveTo(7f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(7f, 19f); close()
        moveTo(14f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(14f, 19f); close()
    }

    val Sparkle = icon("sparkle") {
        moveTo(12f, 3f); curveTo(12.6f, 8.2f, 15.8f, 11.4f, 21f, 12f); curveTo(15.8f, 12.6f, 12.6f, 15.8f, 12f, 21f)
        curveTo(11.4f, 15.8f, 8.2f, 12.6f, 3f, 12f); curveTo(8.2f, 11.4f, 11.4f, 8.2f, 12f, 3f); close()
    }
    val Wand = icon("wand") {
        moveTo(4f, 20f); lineTo(15f, 9f)
        moveTo(14f, 5f); lineTo(14f, 3f); moveTo(19f, 10f); lineTo(21f, 10f); moveTo(17.5f, 6.5f); lineTo(19f, 5f)
        moveTo(13f, 11f); lineTo(17f, 7f)
    }
    val Film = icon("film") {
        moveTo(5f, 4f); lineTo(19f, 4f); quadTo(20f, 4f, 20f, 5f); lineTo(20f, 19f); quadTo(20f, 20f, 19f, 20f)
        lineTo(5f, 20f); quadTo(4f, 20f, 4f, 19f); lineTo(4f, 5f); quadTo(4f, 4f, 5f, 4f); close()
        moveTo(8f, 4f); lineTo(8f, 20f); moveTo(16f, 4f); lineTo(16f, 20f)
        moveTo(4f, 9f); lineTo(8f, 9f); moveTo(4f, 15f); lineTo(8f, 15f); moveTo(16f, 9f); lineTo(20f, 9f); moveTo(16f, 15f); lineTo(20f, 15f)
    }
    val Mic = icon("mic") {
        moveTo(9f, 6f); arcTo(3f, 3f, 0f, false, true, 15f, 6f); lineTo(15f, 11f); arcTo(3f, 3f, 0f, false, true, 9f, 11f); close()
        moveTo(5.5f, 11f); arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 11f); moveTo(12f, 17.5f); lineTo(12f, 21f)
    }
    val Wave = icon("wave") {
        moveTo(3f, 12f); lineTo(3f, 12f); moveTo(6.5f, 9f); lineTo(6.5f, 15f); moveTo(10f, 5f); lineTo(10f, 19f)
        moveTo(13.5f, 8f); lineTo(13.5f, 16f); moveTo(17f, 10f); lineTo(17f, 14f); moveTo(20.5f, 11.5f); lineTo(20.5f, 12.5f)
    }
    /** Sliders: reads as "settings" in every culture, unlike a gear that can look like a sun at small sizes. */
    val Settings = icon("settings") {
        moveTo(4f, 6f); lineTo(20f, 6f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 18f); lineTo(20f, 18f)
        moveTo(9f, 4f); lineTo(9f, 8f); moveTo(15f, 10f); lineTo(15f, 14f); moveTo(7f, 16f); lineTo(7f, 20f)
    }
    val Download = icon("download") {
        moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f); moveTo(5f, 20f); lineTo(19f, 20f)
    }
    val Share = icon("share") {
        moveTo(12f, 15f); lineTo(12f, 3f); moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f)
        moveTo(6f, 11f); lineTo(5f, 11f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 11f); lineTo(18f, 11f)
    }
    val Trash = icon("trash") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
        moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f); moveTo(10f, 11f); lineTo(10f, 16f); moveTo(14f, 11f); lineTo(14f, 16f)
    }
    val Key = icon("key") {
        moveTo(7.5f, 11f); arcTo(4f, 4f, 0f, true, true, 7.49f, 11f); moveTo(11f, 13f); lineTo(20f, 13f); lineTo(20f, 16f); moveTo(16.5f, 13f); lineTo(16.5f, 15.5f)
    }
    val Cloud = icon("cloud") {
        moveTo(7f, 18f); arcTo(4f, 4f, 0f, false, true, 7.5f, 10.03f); arcTo(5.5f, 5.5f, 0f, false, true, 18f, 11f)
        arcTo(3.5f, 3.5f, 0f, false, true, 17.5f, 18f); close()
    }
    val Phone = icon("phone") {
        moveTo(8f, 2.5f); lineTo(16f, 2.5f); quadTo(18f, 2.5f, 18f, 4.5f); lineTo(18f, 19.5f); quadTo(18f, 21.5f, 16f, 21.5f)
        lineTo(8f, 21.5f); quadTo(6f, 21.5f, 6f, 19.5f); lineTo(6f, 4.5f); quadTo(6f, 2.5f, 8f, 2.5f); close(); moveTo(11f, 18.5f); lineTo(13f, 18.5f)
    }
    val Edit = icon("edit") {
        moveTo(4f, 20f); lineTo(4.5f, 16f); lineTo(16f, 4.5f); lineTo(19.5f, 8f); lineTo(8f, 19.5f); close(); moveTo(13.5f, 7f); lineTo(17f, 10.5f)
    }
    val Scissors = icon("scissors") {
        moveTo(6f, 9f); arcTo(2.5f, 2.5f, 0f, true, true, 5.99f, 9f); moveTo(6f, 20f); arcTo(2.5f, 2.5f, 0f, true, true, 5.99f, 20f)
        moveTo(8f, 8f); lineTo(20f, 18f); moveTo(8f, 16f); lineTo(20f, 6f)
    }
    val Music = icon("music") {
        moveTo(9f, 18f); lineTo(9f, 5f); lineTo(20f, 3f); lineTo(20f, 16f)
        moveTo(9f, 18f); arcTo(3f, 2.5f, 0f, true, true, 8.99f, 18f); moveTo(20f, 16f); arcTo(3f, 2.5f, 0f, true, true, 19.99f, 16f)
    }
    val Layers = icon("layers") {
        moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close(); moveTo(3f, 12.5f); lineTo(12f, 17.5f); lineTo(21f, 12.5f)
        moveTo(3f, 16.5f); lineTo(12f, 21.5f); lineTo(21f, 16.5f)
    }
    val Text = icon("text") { moveTo(5f, 6f); lineTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 6f); moveTo(12f, 4f); lineTo(12f, 20f); moveTo(9f, 20f); lineTo(15f, 20f) }
    val Globe = icon("globe") {
        moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 11.99f, 3f); moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f); curveTo(8f, 8f, 8f, 16f, 12f, 21f); moveTo(12f, 3f); curveTo(16f, 8f, 16f, 16f, 12f, 21f)
    }
    val Undo = icon("undo", mirrored = true) { moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f); moveTo(4f, 9f); lineTo(14f, 9f); arcTo(5.5f, 5.5f, 0f, false, true, 14f, 20f); lineTo(11f, 20f) }
    val Palette = icon("palette") {
        moveTo(12f, 3f); arcTo(9f, 9f, 0f, false, false, 12f, 21f); curveTo(13.5f, 21f, 14f, 20f, 13.3f, 18.8f); curveTo(12.5f, 17.4f, 13.4f, 16f, 15f, 16f)
        lineTo(17f, 16f); arcTo(4f, 4f, 0f, false, false, 21f, 12f); arcTo(9f, 9f, 0f, false, false, 12f, 3f); close()
        moveTo(7.5f, 11f); lineTo(7.5f, 11f); moveTo(10f, 7.5f); lineTo(10f, 7.5f); moveTo(14.5f, 7.5f); lineTo(14.5f, 7.5f)
    }
}
