package io.trimio.core.designsystem.signature

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window width classes (Material breakpoints). The same screens run on phones and on the desktop
 * app: Compact stacks one column, Expanded splits into a stage and a control column.
 */
enum class WindowWidth {
    Compact, Medium, Expanded;

    val wide: Boolean get() = this == Expanded

    companion object {
        fun of(width: Dp): WindowWidth = when {
            width < 600.dp -> Compact
            width < 840.dp -> Medium
            else -> Expanded
        }
    }
}
