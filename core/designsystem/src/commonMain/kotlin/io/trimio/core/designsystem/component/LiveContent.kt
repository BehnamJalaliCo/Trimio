package io.trimio.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing
import io.trimio.core.model.text.ScriptDetector

@Immutable
data class StreamWord(val key: Int, val text: String, val emphasis: Float = 0f, val isLatest: Boolean = false)

/**
 * Recognised words popping in one by one; emphasised words get a gradient pill.
 * Flow direction follows the *spoken* language, not the UI language: a Persian recording reads
 * right-to-left even in the English UI.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordStream(words: List<StreamWord>, modifier: Modifier = Modifier) {
    val spoken = ScriptDetector.detect(words.joinToString(" ") { it.text }, fallback = Trimio.language)
    CompositionLocalProvider(LocalLayoutDirection provides if (spoken.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(TrimioSpacing.xs),
        ) {
            words.forEach { word -> key(word.key) { WordChip(word) } }
        }
    }
}

@Composable
private fun WordChip(word: StreamWord) {
    val colors = Trimio.colors
    val appear = remember { Animatable(0f) }
    val motion = Trimio.motion
    LaunchedEffect(Unit) { appear.animateTo(1f, motion.expressive()) }

    val emphasised = word.emphasis >= 0.5f
    val shape = RoundedCornerShape(TrimioRadius.sm)
    Box(
        Modifier
            .graphicsLayer {
                val v = appear.value
                scaleX = 0.6f + 0.4f * v
                scaleY = 0.6f + 0.4f * v
                alpha = v.coerceIn(0f, 1f)
                translationY = (1f - v) * 12.dp.toPx()
            }
            .clip(shape)
            .then(
                when {
                    emphasised -> Modifier.background(Brush.horizontalGradient(listOf(colors.primary, colors.accentMagenta)))
                    word.isLatest -> Modifier.background(colors.glassFill).border(1.dp, colors.accentCyan.copy(alpha = 0.6f), shape)
                    else -> Modifier
                },
            )
            .padding(horizontal = TrimioSpacing.sm, vertical = TrimioSpacing.xxs),
    ) {
        Text(
            text = word.text,
            // Each word picks its own direction so punctuation stays on the correct side ("دوستان،", "BTC!").
            style = (if (emphasised) Trimio.type.title else Trimio.type.body).copy(textDirection = TextDirection.Content),
            color = if (emphasised || word.isLatest) colors.textPrimary else colors.textSecondary,
        )
    }
}

/** Mirrored loudness bars. */
@Composable
fun WaveformStrip(levels: List<Float>, modifier: Modifier = Modifier) {
    val colors = Trimio.colors
    Canvas(modifier.fillMaxWidth().height(40.dp)) {
        if (levels.isEmpty()) return@Canvas
        val slot = size.width / levels.size
        val bar = slot * 0.55f
        val brush = Brush.horizontalGradient(listOf(colors.accentCyan, colors.primary, colors.accentMagenta))
        levels.forEachIndexed { i, level ->
            val h = (size.height * level.coerceIn(0.04f, 1f))
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * slot + (slot - bar) / 2, (size.height - h) / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** A strip of frame cells lighting up as frames render. [previews] can later carry real thumbnails. */
@Composable
fun FilmStrip(rendered: Int, total: Int, modifier: Modifier = Modifier, cells: Int = 12) {
    val colors = Trimio.colors
    val lit = if (total <= 0) 0 else (rendered.toLong() * cells / total).toInt()
    val shimmer by rememberInfiniteTransition(label = "film").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.xs)) {
        repeat(cells) { i ->
            val state = when {
                i < lit -> 2
                i == lit && rendered in 1 until total -> 1
                else -> 0
            }
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when (state) {
                            2 -> Brush.linearGradient(
                                listOf(colors.auroraA, colors.auroraB, colors.auroraC),
                                start = Offset(shimmer * 200f - 100f, 0f),
                                end = Offset(shimmer * 200f + 100f, 300f),
                            )
                            1 -> Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.5f), colors.glassFill))
                            else -> Brush.verticalGradient(listOf(colors.glassFill, colors.glassFill))
                        },
                    )
                    .border(1.dp, if (state == 1) colors.accentCyan else colors.glassStroke, RoundedCornerShape(6.dp)),
            )
        }
    }
}

enum class StepState { Pending, Active, Done, Failed }

@Immutable
data class StepItem(val title: String, val state: StepState, val fraction: Float)

/** Vertical list of pipeline steps with status dots. */
@Composable
fun StepRail(steps: List<StepItem>, modifier: Modifier = Modifier) {
    val colors = Trimio.colors
    val pulse by rememberInfiniteTransition(label = "step").animateFloat(
        initialValue = 0.6f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dot",
    )
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
        steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = when (step.state) {
                    StepState.Pending -> colors.textTertiary.copy(alpha = 0.4f)
                    StepState.Active -> colors.accentCyan
                    StepState.Done -> colors.success
                    StepState.Failed -> colors.danger
                }
                Box(
                    Modifier
                        .size(10.dp)
                        .graphicsLayer { if (step.state == StepState.Active) { scaleX = pulse; scaleY = pulse } }
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    text = step.title,
                    modifier = Modifier.padding(horizontal = TrimioSpacing.md).weight(1f),
                    style = if (step.state == StepState.Active) Trimio.type.title else Trimio.type.body,
                    color = when (step.state) {
                        StepState.Pending -> colors.textTertiary
                        StepState.Active -> colors.textPrimary
                        StepState.Done -> colors.textSecondary
                        StepState.Failed -> colors.danger
                    },
                )
                if (step.state == StepState.Active) {
                    Box(Modifier.size(width = 56.dp, height = 4.dp).clip(CircleShape).background(colors.glassStroke)) {
                        Box(
                            Modifier
                                .fillMaxWidth(step.fraction.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(Brush.horizontalGradient(listOf(colors.accentCyan, colors.primary))),
                        )
                    }
                }
            }
        }
    }
}

/** Small translucent pill for chips and badges. */
@Composable
fun GlassChip(text: String, modifier: Modifier = Modifier, accent: Color = Color.Unspecified) {
    val colors = Trimio.colors
    val shape = RoundedCornerShape(TrimioRadius.xl)
    Box(
        modifier
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, if (accent == Color.Unspecified) colors.glassStroke else accent.copy(alpha = 0.7f), shape)
            .padding(horizontal = TrimioSpacing.md, vertical = TrimioSpacing.xs),
    ) {
        Text(text, style = Trimio.type.label, color = if (accent == Color.Unspecified) colors.textSecondary else accent)
    }
}
