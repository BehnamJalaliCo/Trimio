package io.trimio.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing

/**
 * Standard screen frame: aurora canvas with film grain behind (docs/DESIGN.md §2 layer 0), glass
 * backdrop for any [GlassPanel] inside, and content inset from system bars and cutouts.
 */
@Composable
fun TrimioScreen(
    modifier: Modifier = Modifier,
    energy: () -> Float = { 0f },
    dimAurora: Float = 0.35f,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Trimio.colors
    GlassScene(
        modifier = modifier.fillMaxSize().background(colors.canvas),
        background = {
            AuroraBackground(Modifier.fillMaxSize().filmGrain(), energy = energy)
            // Content is the hero: the aurora is dimmed behind dense screens.
            Box(Modifier.fillMaxSize().background(colors.canvas.copy(alpha = dimAurora)))
        },
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), content = content)
    }
}

/** Icon tinted with the current text colour unless told otherwise. */
@Composable
fun TrimioIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Trimio.colors.textPrimary, size: Dp = 22.dp) {
    Icon(icon, contentDescription, modifier.size(size), tint = tint)
}

/** 44dp round glass button (48dp touch target with its padding). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Trimio.colors.textPrimary,
    highlighted: Boolean = false,
) {
    val colors = Trimio.colors
    Box(
        modifier
            .padding(2.dp)
            .size(44.dp)
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(if (highlighted) colors.primary.copy(alpha = 0.35f) else colors.glassFill)
            .border(1.dp, if (highlighted) colors.primary else colors.glassStroke, CircleShape)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { TrimioIcon(icon, null, tint = tint) }
}

/** Screen header: optional back button, title (and subtitle), trailing actions. */
@Composable
fun TrimioTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = TrimioSpacing.md, vertical = TrimioSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            GlassIconButton(io.trimio.core.designsystem.icon.TrimioIcons.Back, backLabel, onBack)
            Spacer(Modifier.width(TrimioSpacing.sm))
        }
        Column(Modifier.weight(1f).padding(horizontal = TrimioSpacing.xs)) {
            Text(title, style = Trimio.type.headline, color = Trimio.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Trimio.type.label, color = Trimio.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.xs), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Small uppercase-tracked section label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(vertical = TrimioSpacing.sm), style = Trimio.type.label, color = Trimio.colors.textTertiary)
}

/**
 * Horizontal single-choice pills. The selected pill fills with the brand gradient; selection moves
 * with a spring and a light haptic tick.
 */
@Composable
fun <T> PillSelector(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable (T) -> ImageVector?)? = null,
) {
    val colors = Trimio.colors
    val haptics = Trimio.haptics
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val shape = RoundedCornerShape(TrimioRadius.xl)
            val text by animateColorAsState(if (isSelected) colors.onPrimary else colors.textSecondary, Trimio.motion.effects(), label = "pill")
            Row(
                Modifier
                    .pressable(role = Role.RadioButton) {
                        if (!isSelected) haptics.tick()
                        onSelect(option)
                    }
                    .clip(shape)
                    .then(
                        if (isSelected) Modifier.background(Brush.horizontalGradient(listOf(colors.primary, colors.accentMagenta)))
                        else Modifier.background(colors.glassFill).border(1.dp, colors.glassStroke, shape),
                    )
                    .padding(horizontal = TrimioSpacing.lg, vertical = TrimioSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.xs),
            ) {
                leading?.invoke(option)?.let { TrimioIcon(it, null, tint = text, size = 16.dp) }
                Text(label(option), style = Trimio.type.label, color = text)
            }
        }
    }
}

/** Two-state switch with a springy thumb and gradient track. */
@Composable
fun TrimioSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = Trimio.colors
    val offset by animateDpAsState(if (checked) 20.dp else 0.dp, Trimio.motion.spatialFast(), label = "thumb")
    val glow by animateFloatAsState(if (checked) 1f else 0f, Trimio.motion.effects(), label = "track")
    Box(
        modifier
            .size(width = 48.dp, height = 28.dp)
            .pressable(role = Role.Switch) { onCheckedChange(!checked) }
            .clip(RoundedCornerShape(14.dp))
            .drawBehind {
                drawRect(colors.glassFill)
                drawRect(Brush.horizontalGradient(listOf(colors.primary, colors.accentMagenta)), alpha = glow)
            }
            .border(1.dp, colors.glassStroke, RoundedCornerShape(14.dp))
            .padding(3.dp),
    ) {
        Box(Modifier.offset(x = offset).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

/** A settings-style row: icon, title + description, trailing control. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(pressedScale = 0.985f, onClick = onClick) else Modifier)
            .padding(vertical = TrimioSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.md),
    ) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(TrimioRadius.sm)).background(Trimio.colors.glassFill),
                contentAlignment = Alignment.Center,
            ) { TrimioIcon(icon, null, tint = Trimio.colors.accentCyan, size = 20.dp) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Trimio.type.title, color = Trimio.colors.textPrimary)
            if (description != null) Text(description, style = Trimio.type.label, color = Trimio.colors.textSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/**
 * Multi-line glass text field in the chat composer style. [placeholder] shows while empty;
 * the field grows up to [maxLines].
 */
@Composable
fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = 6,
    singleLine: Boolean = false,
    obscured: Boolean = false,
) {
    val colors = Trimio.colors
    val style = Trimio.type.body.copy(color = colors.textPrimary)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = style,
        cursorBrush = SolidColor(colors.accentCyan),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else maxLines,
        visualTransformation = if (obscured) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style, color = colors.textTertiary)
                inner()
            }
        },
    )
}

/** Solid raised card for structural content (docs/DESIGN.md §2: glass is only for temporary layers). */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    padding: Dp = TrimioSpacing.lg,
    accent: Color = Color.Transparent,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Trimio.colors
    val shape = RoundedCornerShape(TrimioRadius.lg)
    Column(
        modifier
            .clip(shape)
            .background(colors.canvasRaised.copy(alpha = 0.92f))
            .border(1.dp, if (accent.alpha > 0f) accent.copy(alpha = 0.6f) else colors.glassStroke, shape)
            .padding(padding),
        content = content,
    )
}

/** Thin progress line with a bright head, for downloads and quick tasks. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = Trimio.colors.accentCyan) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), Trimio.motion.spatial(), label = "progress")
    Box(
        modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Trimio.colors.glassFill).drawBehind {
            drawRect(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color)), size = size.copy(width = size.width * animated))
        },
    )
}
