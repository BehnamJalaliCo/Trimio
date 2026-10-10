package io.trimio.core.designsystem.signature

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import io.trimio.core.designsystem.component.LocalGlassBackdrop
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.theme.Trimio

/** The primary action: ember slab with a warm glow and a lit top edge. */
@Composable
fun SigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: ImageVector? = null, enabled: Boolean = true) {
    val c = Sig.colors
    val shape = RoundedCornerShape(SigSpace.radiusMd)
    Row(
        modifier
            .height(SigSpace.controlHeight)
            .shadow(if (enabled) 22.dp else 0.dp, shape, ambientColor = c.action, spotColor = c.action.copy(alpha = 0.55f))
            .clip(shape)
            .background(if (enabled) c.action else c.surfaceHigh)
            .drawWithContent {
                drawContent()
                drawLine(Color.White.copy(alpha = 0.35f), Offset(size.height / 3, 0.5f), Offset(size.width - size.height / 3, 0.5f), strokeWidth = 1.dp.toPx())
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Sig.type.button, color = if (enabled) c.onAction else c.muted)
        trailing?.let { TrimioIcon(it, null, tint = if (enabled) c.onAction else c.muted, size = 20.dp) }
    }
}

/** Square ember action (send, confirm) used inside composers. */
@Composable
fun SigActionSquare(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    val c = Sig.colors
    Box(
        modifier.size(size).clip(RoundedCornerShape(14.dp)).background(c.action).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { TrimioIcon(icon, label, tint = c.onAction, size = 22.dp) }
}

/**
 * Warm glass: backdrop blur when inside a GlassScene, a warm top-to-bottom tint, a lit rim and a
 * deep soft shadow. Solid surface when transparency is reduced.
 */
@Composable
fun SigGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(SigSpace.radiusLg),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Glass floating on footage or artwork: always the dark material, whatever the theme. */
    overMedia: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = if (overMedia) SigNoir else Sig.colors
    val backdrop = LocalGlassBackdrop.current
    val solid = Trimio.preferences.reduceTransparency
    val blur = if (backdrop != null && !solid) {
        Modifier.hazeBlur(
            input = HazeInput.Backdrop(backdrop),
            style = HazeBlurStyle {
                blurRadius(24.dp)
                noiseFactor(0.1f)
                backgroundColor(c.canvas)
                colorEffects(listOf(HazeColorEffect.tint(c.canvas.copy(alpha = 0.35f))))
            },
        )
    } else {
        Modifier
    }
    Box(
        modifier
            .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.6f))
            .clip(shape)
            .then(blur)
            .background(if (solid) c.surfaceHigh else Color.Transparent)
            .background(Brush.linearGradient(listOf(c.glassTop, c.glassBottom), start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)))
            .border(1.dp, Brush.verticalGradient(listOf(c.glassRim, c.glassRim.copy(alpha = 0.08f))), shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Small rounded label (status, counts). */
@Composable
fun SigChip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = Sig.colors.ink.copy(alpha = 0.08f),
    content: Color = Sig.colors.ink,
    stroke: Color? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.height(28.dp).clip(shape).background(container).let { if (stroke != null) it.border(1.5.dp, stroke, shape) else it }.padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, style = Sig.type.meta.copy(fontWeight = FontWeight(600)), color = content)
    }
}

/** A 1dp rule; [strong] for section borders. */
@Composable
fun Hairline(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(modifier.fillMaxWidth().height(1.dp).background(if (strong) Sig.colors.lineStrong else Sig.colors.line))
}

/** The director's mark: an iridescent bead with a soft halo. Appears only where the AI acts. */
@Composable
fun DirectorDot(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val c = Sig.colors
    Box(
        modifier
            .size(size)
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(c.director[1].copy(alpha = 0.55f), Color.Transparent), radius = this.size.minDimension), radius = this.size.minDimension)
            }
            .clip(CircleShape)
            .background(c.directorBrush),
    )
}

/** Text in the director's iridescent ink (only for the AI's voice). */
@Composable
fun DirectorText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = Sig.type.meta.copy(fontWeight = FontWeight(700), brush = Sig.colors.directorInkBrush))
}

/**
 * Builds [text] with the spans in [emphasised] set on the emphasis block (lime with ink), the way
 * the app marks what matters in a brief or a transcript.
 */
@Composable
fun emphasised(text: String, vararg emphasised: String): AnnotatedString {
    val c = Sig.colors
    return buildAnnotatedString {
        var i = 0
        val marks = emphasised.mapNotNull { e -> text.indexOf(e).takeIf { it >= 0 }?.let { it to it + e.length } }.sortedBy { it.first }
        for ((start, end) in marks) {
            if (start < i) continue
            append(text.substring(i, start))
            withStyle(SpanStyle(background = c.emphasis, color = c.onEmphasis, fontWeight = FontWeight(800))) { append(text.substring(start, end)) }
            i = end
        }
        append(text.substring(i))
    }
}

/** Tracked Latin micro label, always left-to-right. */
@Composable
fun MicroLabel(text: String, modifier: Modifier = Modifier, color: Color = Sig.colors.muted) {
    Text("⁦$text⁩", modifier, style = Sig.type.micro, color = color)
}

/** Round glass icon button for top bars over footage. */
@Composable
fun SigRoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, overMedia: Boolean = true) {
    SigGlass(modifier.size(42.dp).clickable(role = Role.Button, onClick = onClick), shape = CircleShape, overMedia = overMedia) {
        TrimioIcon(icon, label, tint = if (overMedia) Sig.colors.onMedia else Sig.colors.ink, size = 20.dp, modifier = Modifier.align(Alignment.Center))
    }
}

/** Padding helper for the screen gutter. */
fun Modifier.gutter(): Modifier = padding(horizontal = SigSpace.gutter)

/**
 * Display text laid out like the design files (CSS line boxes): every line, the first included,
 * is exactly `lineHeight` tall with the glyphs centred in it. Compose otherwise gives the first
 * line the font's full natural ascent, which pushes tall display faces (line height < 1.1) down.
 */
@Composable
fun DisplayText(text: AnnotatedString, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, color: Color = Sig.colors.ink) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val l = layout
            if (l == null || l.lineCount == 0) return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            val lineHeight = style.lineHeight.toPx()
            val ascent = l.getLineBaseline(0) - l.getLineTop(0)
            val last = l.lineCount - 1
            val descent = l.getLineBottom(last) - l.getLineBaseline(last)
            val cssBaseline = lineHeight / 2f + (ascent - descent) / 2f
            val shift = (l.getLineBaseline(0) - cssBaseline).toInt()
            val height = (lineHeight * l.lineCount).toInt()
            layout(placeable.width, height) { placeable.place(0, -shift) }
        },
        style = style,
        color = color,
        onTextLayout = { layout = it },
    )
}

@Composable
fun DisplayText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, color: Color = Sig.colors.ink) =
    DisplayText(AnnotatedString(text), style, modifier, color)
