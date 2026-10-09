package io.trimio.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing

/**
 * Press feedback shared by every tappable surface: spring scale-down, haptic click,
 * no ripple (the scale and glow are the feedback). Animated values are read in the draw phase.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, Trimio.motion.spatialFast(), label = "press")
    val haptics = Trimio.haptics
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role) {
            haptics.click()
            onClick()
        }
}

enum class ButtonKind { Primary, Glass, Ghost }

/** Pill button. Primary glows with the brand gradient; Glass sits on backdrops; Ghost is text-only. */
@Composable
fun TrimioButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = Trimio.colors
    val shape = RoundedCornerShape(TrimioRadius.xl)
    val glow = if (kind == ButtonKind.Primary && enabled) colors.primary else Color.Transparent

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp, minWidth = TrimioSpacing.touchTarget)
            .pressable(enabled = enabled, onClick = onClick)
            .drawBehind {
                // Soft bloom under the primary button, like light spilling from the gradient.
                if (glow.alpha > 0f) {
                    drawRoundRect(
                        brush = Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), Color.Transparent), radius = size.width * 0.7f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height),
                    )
                }
            }
            .clip(shape)
            .then(
                when (kind) {
                    ButtonKind.Primary -> Modifier.background(
                        if (enabled) Brush.horizontalGradient(listOf(colors.primary, colors.accentMagenta))
                        else Brush.horizontalGradient(listOf(colors.textTertiary, colors.textTertiary)),
                    )
                    ButtonKind.Glass -> Modifier.background(colors.glassFill).border(1.dp, colors.glassStroke, shape)
                    ButtonKind.Ghost -> Modifier
                },
            )
            .padding(horizontal = TrimioSpacing.xl, vertical = TrimioSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text,
            style = Trimio.type.title,
            color = when (kind) {
                ButtonKind.Primary -> colors.onPrimary
                ButtonKind.Glass -> colors.textPrimary
                ButtonKind.Ghost -> colors.accentCyan
            },
        )
    }
}
