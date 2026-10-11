package io.trimio.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The editor's own marks, drawn in the Trimio line hand (24-unit grid, round caps), from the board's icon set. */
internal object EditorIcons {
    private fun icon(name: String, fill: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = if (fill) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** Half-filled disc: style. */
    val Style: ImageVector = ImageVector.Builder("style", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f) { moveTo(12f, 4f); arcTo(8f, 8f, 0f, true, true, 11.99f, 4f) }
        path(fill = SolidColor(Color.Black)) { moveTo(12f, 4f); arcTo(8f, 8f, 0f, false, false, 12f, 20f); close() }
    }.build()

    val Spark = icon("spark") {
        moveTo(12f, 3f); lineTo(13.8f, 8.2f); lineTo(19f, 10f); lineTo(13.8f, 11.8f); lineTo(12f, 17f); lineTo(10.2f, 11.8f); lineTo(5f, 10f); lineTo(10.2f, 8.2f); close()
    }

    val Cut = icon("cut") {
        moveTo(6f, 4.5f); arcTo(2.5f, 2.5f, 0f, true, true, 5.99f, 4.5f)
        moveTo(6f, 14.5f); arcTo(2.5f, 2.5f, 0f, true, true, 5.99f, 14.5f)
        moveTo(8f, 8.5f); lineTo(20f, 17f); moveTo(8f, 15.5f); lineTo(20f, 7f)
    }

    val Export = icon("export") {
        moveTo(12f, 15f); lineTo(12f, 3f); moveTo(7f, 8f); lineTo(12f, 3f); lineTo(17f, 8f)
        moveTo(5f, 13f); lineTo(5f, 19f); arcTo(2f, 2f, 0f, false, false, 7f, 21f); lineTo(17f, 21f); arcTo(2f, 2f, 0f, false, false, 19f, 19f); lineTo(19f, 13f)
    }

    val Bolt = icon("bolt") { moveTo(13f, 3f); lineTo(5f, 14f); lineTo(11f, 14f); lineTo(10f, 21f); lineTo(18f, 10f); lineTo(12f, 10f); close() }

    val Star = icon("star") {
        moveTo(12f, 4f); lineTo(14.4f, 9f); lineTo(20f, 9.6f); lineTo(15.8f, 13.4f); lineTo(17f, 19f); lineTo(12f, 16.1f)
        lineTo(7f, 19f); lineTo(8.2f, 13.4f); lineTo(4f, 9.6f); lineTo(9.6f, 9f); close()
    }

    val Redo = icon("redo") { moveTo(15f, 14f); lineTo(20f, 9f); lineTo(15f, 4f); moveTo(20f, 9f); lineTo(10f, 9f); arcTo(6f, 6f, 0f, false, false, 10f, 21f); lineTo(13f, 21f) }

    val Undo = icon("undo") { moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f); moveTo(4f, 9f); lineTo(14f, 9f); arcTo(6f, 6f, 0f, false, true, 14f, 21f); lineTo(11f, 21f) }

    val Restore = icon("restore") { moveTo(4f, 12f); arcTo(8f, 8f, 0f, true, false, 6.3f, 6.3f); moveTo(4f, 4f); lineTo(4f, 9f); lineTo(9f, 9f) }
}
