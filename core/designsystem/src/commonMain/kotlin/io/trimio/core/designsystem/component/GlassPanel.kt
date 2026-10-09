package io.trimio.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.trimio.core.designsystem.theme.Depth
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing

private val LocalGlassBackdrop = staticCompositionLocalOf<HazeState?> { null }

/**
 * Marks [background] as the backdrop that glass surfaces inside [content] blur through.
 * Put the canvas (aurora, video, artwork) in [background] and the UI in [content].
 */
@Composable
fun GlassScene(
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberHazeState()
    Box(modifier) {
        Box(Modifier.matchParentSize().hazeSource(state), content = background)
        CompositionLocalProvider(LocalGlassBackdrop provides state) {
            Box(Modifier.matchParentSize(), content = content)
        }
    }
}

/** docs/DESIGN.md §3: Regular adapts tint for legibility; Clear is for bold content over video. */
enum class GlassVariant { Regular, Clear }

/**
 * Liquid-glass surface for temporary layers (sheets, toolbars over video, progress cards):
 * real backdrop blur with film-grain noise, light-catching rim (bright top-start, dark bottom-end),
 * inner top sheen and a soft drop shadow. Falls back to a solid raised surface when transparency
 * is reduced or there is no [GlassScene] backdrop.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    radius: Dp = TrimioRadius.lg,
    contentPadding: PaddingValues = PaddingValues(TrimioSpacing.lg),
    variant: GlassVariant = GlassVariant.Regular,
    depth: Depth = Depth.Glass,
    tint: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Trimio.colors
    val shape = RoundedCornerShape(radius)
    val backdrop = LocalGlassBackdrop.current
    val solid = Trimio.preferences.reduceTransparency || backdrop == null

    val surface = if (solid) {
        Modifier.background(colors.canvasRaised)
    } else {
        val scrim = if (variant == GlassVariant.Regular) colors.canvas.copy(alpha = 0.38f) else colors.canvas.copy(alpha = 0.12f)
        Modifier.hazeBlur(
            input = HazeInput.Backdrop(backdrop!!),
            style = HazeBlurStyle {
                blurRadius(if (variant == GlassVariant.Regular) 28.dp else 18.dp)
                noiseFactor(0.12f)
                backgroundColor(colors.canvas)
                colorEffects(
                    listOfNotNull(
                        HazeColorEffect.tint(scrim),
                        HazeColorEffect.tint(colors.glassFill),
                        tint.takeIf { it.alpha > 0f }?.let { HazeColorEffect.tint(it.copy(alpha = it.alpha * 0.18f)) },
                    ),
                )
            },
        )
    }

    Box(
        modifier = modifier
            .shadow(elevation = depth.shadow, shape = shape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(shape)
            .then(surface)
            .drawWithContent {
                drawContent()
                // Inner sheen: light falling on the top edge of the glass.
                drawRect(
                    Brush.verticalGradient(
                        0f to colors.glassHighlight.copy(alpha = if (solid) 0.04f else 0.10f),
                        0.35f to Color.Transparent,
                    ),
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    0f to colors.glassHighlight.copy(alpha = 0.55f),
                    0.45f to colors.glassStroke,
                    1f to Color.Black.copy(alpha = 0.25f),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
                shape = shape,
            )
            .padding(contentPadding),
        content = content,
    )
}
