package io.trimio.core.designsystem.signature

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.component.GlassScene
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.component.filmGrain
import io.trimio.core.designsystem.icon.TrimioIcons

/** Measures the space the screen gets (not the device) and hands its width class to [content]. */
@Composable
fun SigAdaptive(modifier: Modifier = Modifier, content: @Composable BoxScope.(WindowWidth) -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) { content(WindowWidth.of(maxWidth)) }
}

/**
 * A Signature screen: the canvas (with film grain on noir), glass that blurs what is behind it,
 * and content kept clear of the system bars.
 */
@Composable
fun SigScreen(modifier: Modifier = Modifier, background: @Composable BoxScope.() -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    val c = Sig.colors
    GlassScene(
        modifier.fillMaxSize().background(c.canvas).filmGrain(if (c.isDark) 0.03f else 0f),
        background = background,
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), content = content)
    }
}

/**
 * The top row of the boards: the tracked micro label at the reading end and a round glass button
 * at the start (back, or close for sheets and results).
 */
@Composable
fun SigTopRow(micro: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, backIcon: ImageVector = TrimioIcons.Back, backLabel: String = "") {
    Row(modifier.fillMaxWidth().height(58.dp).gutter(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) SigRoundButton(backIcon, backLabel, onBack, overMedia = false) else Box(Modifier.size(42.dp))
        MicroLabel(micro)
    }
}

/** Screen hero title ("مدل‌ها.") with the ember full stop, drawn after the last word in reading order. */
@Composable
fun SigHeroTitle(text: String, modifier: Modifier = Modifier, size: Int = 46) {
    val style = Sig.type.hero.copy(fontSize = size.sp, lineHeight = (size * 1.05f).sp)
    DisplayText(
        buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = Sig.colors.action)) { append(".") }
        },
        style = style,
        modifier = modifier,
    )
}

/** Section label as in the boards: the name at the start, an optional Latin micro note at the end. */
@Composable
fun SigSectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Sig.type.meta, color = Sig.colors.muted)
        trailing?.let { MicroLabel(it) }
    }
}

/** Segmented choice on a quiet track; the chosen segment is an ink slab (bone on noir, black on paper). */
@Composable
fun <T> SigSegmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
    /** Options this device cannot do stay visible, faint and inert. */
    enabled: (T) -> Boolean = { true },
    height: Dp = 46.dp,
) {
    val c = Sig.colors
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp)).background(c.ink.copy(alpha = 0.06f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val live = enabled(option)
            val ink = when {
                on -> c.canvas
                live -> c.muted
                else -> c.faint.copy(alpha = 0.6f)
            }
            Row(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(if (on) c.ink else Color.Transparent)
                    .clickable(enabled = live, role = Role.Tab) { onSelect(option) },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.invoke(option)?.let { TrimioIcon(it, null, tint = ink, size = 16.dp) }
                Text(label(option), style = Sig.type.bodyStrong.copy(fontSize = 13.sp), color = ink, maxLines = 1)
            }
        }
    }
}

/** The secondary action: a dark slab with a hairline rim, beside the ember primary. */
@Composable
fun SigSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Sig.colors
    val shape = RoundedCornerShape(SigSpace.radiusMd)
    Row(
        modifier.height(SigSpace.controlHeight).clip(shape).background(c.surface).border(1.dp, c.lineStrong, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Sig.type.button, color = c.ink)
        icon?.let { TrimioIcon(it, null, tint = c.ink, size = 20.dp) }
    }
}

/** A quiet text action (links such as "جزئیات فنی"). */
@Composable
fun SigTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        style = Sig.type.meta.copy(fontWeight = FontWeight(600)),
        color = Sig.colors.muted,
    )
}

/** Single- or multi-line text input in Signature type, with a muted placeholder. */
@Composable
fun SigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = Sig.type.body,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = Sig.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = style.copy(color = c.ink),
        cursorBrush = SolidColor(c.action),
        maxLines = maxLines,
        singleLine = maxLines == 1,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(fontWeight = FontWeight(400)), color = c.faint)
                inner()
            }
        },
    )
}

/**
 * A numbered option in a "what can we do?" list (errors, empty states). [director] marks the
 * option the director recommends.
 */
@Composable
fun SigOptionRow(index: Int, title: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier, director: Boolean = false) {
    val c = Sig.colors
    Column(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⁦0$index⁩", style = Sig.type.index.copy(fontSize = 16.sp), color = c.muted, modifier = Modifier.width(26.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Sig.type.bodyStrong, color = c.ink)
                Text(hint, style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted)
            }
            if (director) DirectorDot(size = 12.dp)
        }
    }
}

/** A designed state (empty or error): an icon tile, a big honest line, what happened and what to do. */
@Composable
fun SigStateBlock(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, tint: Color = Sig.colors.action) {
    val c = Sig.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            TrimioIcon(icon, null, tint = tint, size = 22.dp)
        }
        SigHeroTitle(title, size = 38)
        Text(body, style = Sig.type.body.copy(fontSize = 13.sp), color = c.muted)
    }
}

/**
 * Stand-in for footage that has no frame yet (preview builds, review renders): key light, cool
 * fill, bokeh and a rim-lit subject, so the layout reads as it will over real video.
 */
@Composable
fun FootagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier.background(Color(0xFF0D0C0B)).drawBehind {
            val w = size.width
            val h = size.height
            drawRect(Brush.radialGradient(listOf(Color(0xA6FF965A), Color.Transparent), center = Offset(w * 0.74f, h * 0.26f), radius = w * 0.5f))
            drawRect(Brush.radialGradient(listOf(Color(0x593C5AA0), Color.Transparent), center = Offset(w * 0.12f, h * 0.78f), radius = w * 0.55f))
            val bokeh = Offset(w * 0.3f, h * 0.26f)
            drawCircle(Brush.radialGradient(listOf(Color(0x8CFFD2A0), Color.Transparent), center = bokeh, radius = w * 0.14f), radius = w * 0.14f, center = bokeh)
            val rim = Brush.horizontalGradient(listOf(Color(0xFF0B0908), Color(0xFF1C1512), Color(0xFF6B3A22)), startX = w * 0.22f, endX = w * 0.74f)
            drawRoundRect(rim, topLeft = Offset(w * 0.22f, h * 0.48f), size = Size(w * 0.56f, h * 0.62f), cornerRadius = CornerRadius(w * 0.24f))
            drawOval(rim, topLeft = Offset(w * 0.36f, h * 0.24f), size = Size(w * 0.28f, h * 0.27f))
        },
    )
}

/** A live dot (lime on noir, ink on paper) for "LIVE" and running states. */
@Composable
fun LiveDot(modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(modifier.size(size).clip(CircleShape).background(Sig.colors.live))
}
