package io.trimio.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.shader.ShaderSources
import io.trimio.core.designsystem.shader.rememberShader
import io.trimio.core.designsystem.shader.rememberShaderTime
import io.trimio.core.designsystem.shader.uniform
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.localizedNumber

/** One arc of the outer ring: a pipeline stage sized by its weight. */
@Immutable
data class RingSegment(val weight: Float, val fill: Float, val active: Boolean, val failed: Boolean = false)

/**
 * The build screen's centrepiece: a glass sphere filling with liquid to [progress] (0..1),
 * wrapped in a segmented ring showing each stage, with the percentage in the middle.
 */
@Composable
fun LiquidProgressOrb(
    progress: Float,
    segments: List<RingSegment>,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 260.dp,
    /** False for decorative use (onboarding), where a percentage would mean nothing. */
    showValue: Boolean = true,
) {
    val colors = Trimio.colors
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), Trimio.motion.spatialSlow(), label = "orb-progress")
    val shader = rememberShader(ShaderSources.liquidOrb)
    val time = rememberShaderTime()
    val pulse by rememberInfiniteTransition(label = "ring-pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val totalWeight = segments.sumOf { it.weight.toDouble() }.toFloat().takeIf { it > 0f } ?: return@Canvas
            val gap = if (segments.size > 1) 4f else 0f
            val usable = 360f - gap * segments.size
            var start = -90f + gap / 2

            for (segment in segments) {
                val sweep = usable * segment.weight / totalWeight
                val base = if (segment.failed) colors.danger else colors.primary
                drawArc(base.copy(alpha = 0.16f), start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val filled = sweep * segment.fill.coerceIn(0f, 1f)
                if (filled > 0.5f) {
                    val brush = Brush.sweepGradient(listOf(colors.accentCyan, colors.primary, colors.accentMagenta, colors.accentCyan))
                    val alpha = if (segment.active) 0.65f + 0.35f * pulse else 1f
                    drawArc(brush, start, filled, false, topLeft, arcSize, alpha = alpha, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                start += sweep + gap
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(size * 0.09f)
                .clip(CircleShape)
                .drawBehind {
                    if (shader == null) {
                        val level = this.size.height * (1f - animated)
                        drawRect(Brush.verticalGradient(listOf(colors.accentCyan, colors.primary)), topLeft = Offset(0f, level))
                        return@drawBehind
                    }
                    shader.uniform("iResolution", this.size.width, this.size.height)
                    shader.uniform("iTime", time.value)
                    shader.uniform("iProgress", animated)
                    shader.uniform("cDeep", colors.primary.darken(0.45f))
                    shader.uniform("cSurface", colors.accentCyan)
                    shader.uniform("cGlow", colors.accentMagenta)
                    drawRect(shader.brush())
                },
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showValue) Text(
                text = localizedNumber("${(animated * 100).toInt()}") + localizedPercentSign(),
                style = Trimio.type.display.copy(fontSize = (size.value * 0.2f).sp, fontFeatureSettings = "tnum"),
                color = colors.textPrimary,
            )
            Text(
                text = label,
                style = Trimio.type.label,
                color = colors.textPrimary.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun localizedPercentSign(): String = if (Trimio.language.isRtl) "٪" else "%"

private fun Color.darken(amount: Float) = Color(red * (1 - amount), green * (1 - amount), blue * (1 - amount), alpha)
