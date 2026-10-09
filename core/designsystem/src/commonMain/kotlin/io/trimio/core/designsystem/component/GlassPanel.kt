package io.trimio.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing

/**
 * Frosted-glass surface: translucent fill, light-catching gradient stroke (bright top-start edge)
 * and a soft sheen. Backdrop blur is added per platform later behind the same API.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    radius: Dp = TrimioRadius.lg,
    contentPadding: PaddingValues = PaddingValues(TrimioSpacing.lg),
    tint: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Trimio.colors
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .shadow(elevation = TrimioSpacing.xl, shape = shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape)
            .background(colors.glassFill)
            .background(
                Brush.linearGradient(
                    colors = listOf(tint.copy(alpha = tint.alpha * 0.35f), Color.Transparent),
                ),
            )
            .background(
                Brush.linearGradient(
                    colors = listOf(colors.glassHighlight.copy(alpha = 0.10f), Color.Transparent),
                    start = Offset.Zero,
                    end = Offset(0f, Float.POSITIVE_INFINITY),
                ),
            )
            .border(
                width = TrimioSpacing.xxs / 2,
                brush = Brush.linearGradient(listOf(colors.glassHighlight, colors.glassStroke, colors.glassStroke.copy(alpha = 0.05f))),
                shape = shape,
            )
            .padding(contentPadding),
        content = content,
    )
}
