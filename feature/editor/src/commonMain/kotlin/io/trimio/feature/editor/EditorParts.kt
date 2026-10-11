package io.trimio.feature.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.signature.SigRoundButton
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.tr
import kotlin.math.roundToInt

/*
 * The editor's parts as board 3 draws them. The editor lives over footage, so every part uses the
 * dark material ([SigNoir]) whatever the app's theme: bone ink on warm glass.
 */

internal val Ink = SigNoir.ink
internal val InkSoft = SigNoir.ink.copy(alpha = 0.6f)
internal val InkLabel = SigNoir.ink.copy(alpha = 0.7f)

/** The board's figures and timecodes: the accent family, left to right. */
@Composable
internal fun figureStyle(size: Float = 12f, weight: Int = 700): TextStyle =
    Sig.type.figure.copy(fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = FontWeight(weight))

/** A timecode or figure, isolated left-to-right inside Persian text. */
@Composable
internal fun Figure(text: String, modifier: Modifier = Modifier, color: Color = Ink, size: Float = 12f, weight: Int = 700) {
    Text("⁦$text⁩", modifier, style = figureStyle(size, weight), color = color, maxLines = 1)
}

/** The top bar: back at the reading start; undo (and redo when there is one) and the ember export at the end. */
@Composable
internal fun EditorTopBar(ui: EditorUi, actions: EditorActions, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SigRoundButton(TrimioIcons.Back, tr("بازگشت", "Back"), actions.onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.canRedo) SigRoundButton(EditorIcons.Redo, tr("انجام دوباره", "Redo"), actions.onRedo)
            SigRoundButton(EditorIcons.Undo, tr("واگرد", "Undo"), { if (ui.canUndo) actions.onUndo() }, Modifier.alpha(if (ui.canUndo) 1f else DIMMED))
            ExportPill(actions.onExport)
        }
    }
}

private const val DIMMED = 0.45f

@Composable
private fun ExportPill(onClick: () -> Unit) {
    val shape = RoundedCornerShape(21.dp)
    Row(
        Modifier.height(42.dp).shadow(14.dp, shape, ambientColor = SigNoir.action, spotColor = SigNoir.action.copy(alpha = 0.5f)).clip(shape)
            .background(SigNoir.action).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrimioIcon(EditorIcons.Export, null, tint = SigNoir.onAction, size = 18.dp)
        Text(tr("خروجی", "Export"), style = Sig.type.button.copy(fontSize = 14.sp), color = SigNoir.onAction)
    }
}

/** The five tools as a glass rail over the footage; the open one in acid. Tapping it again folds the panel. */
@Composable
internal fun ToolRail(tool: EditorTool, folded: Boolean, onTool: (EditorTool) -> Unit, modifier: Modifier = Modifier) {
    SigGlass(modifier.width(52.dp), shape = RoundedCornerShape(26.dp), overMedia = true) {
        Column(Modifier.padding(vertical = 12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EditorTool.entries.forEach { t ->
                val on = t == tool && !folded
                val tint = if (on) SigNoir.emphasis else InkSoft
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Tab) { onTool(t) }.padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    TrimioIcon(toolIcon(t), null, tint = tint, size = 21.dp)
                    Text(toolName(t), style = Sig.type.meta.copy(fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight(600)), color = tint, maxLines = 1)
                }
            }
        }
    }
}

internal fun toolIcon(t: EditorTool): ImageVector = when (t) {
    EditorTool.Text -> TrimioIcons.Text
    EditorTool.Style -> EditorIcons.Style
    EditorTool.Sound -> TrimioIcons.Music
    EditorTool.Elements -> EditorIcons.Spark
    EditorTool.Cut -> EditorIcons.Cut
}

@Composable
internal fun toolName(t: EditorTool): String = when (t) {
    EditorTool.Text -> tr("متن", "Text")
    EditorTool.Style -> tr("سبک", "Style")
    EditorTool.Sound -> tr("صدا", "Sound")
    EditorTool.Elements -> tr("المان", "Elements")
    EditorTool.Cut -> tr("برش", "Cut")
}

/** A panel's title at the reading start and its note at the end. */
@Composable
internal fun PanelHeader(title: String, modifier: Modifier = Modifier, size: Float = 14f, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f, fill = false), style = Sig.type.bodyStrong.copy(fontSize = size.sp, lineHeight = (size + 4).sp), color = Ink, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** A line in the director's voice: the iridescent bead and a quiet label; tappable when [onClick] is given. */
@Composable
internal fun DirectorLine(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DirectorDot(size = 10.dp)
        Text(text, style = Sig.type.meta.copy(fontSize = 11.5.sp, fontWeight = FontWeight(600)), color = InkSoft, maxLines = 1)
    }
}

/** A quiet label and its value (sliders, toggles). */
@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = Sig.type.meta.copy(fontWeight = FontWeight(600)), color = InkLabel, maxLines = 1)
}

/** The board's switch: ember with the knob at the end when on; quiet with a grey knob when off. */
@Composable
internal fun EditorToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: String = "") {
    val knob by animateFloatAsState(if (on) 1f else 0f, Trimio.motion.spatialFast())
    Box(
        modifier.size(46.dp, 28.dp).clip(RoundedCornerShape(14.dp))
            .background(if (on) SigNoir.action else Ink.copy(alpha = 0.14f))
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!on) }
            .semantics { contentDescription = label; stateDescription = if (on) "on" else "off" },
    ) {
        // Offsets follow the layout direction: the knob travels toward the reading end.
        val travel = 18f * knob
        Box(
            Modifier.align(Alignment.CenterStart).padding(horizontal = 3.dp).offset(x = travel.dp).size(22.dp).clip(CircleShape)
                .background(if (on) Color.White else SigNoir.muted.copy(alpha = if (enabled) 1f else 0.4f)),
        )
    }
}

/**
 * The board's slider: a hairline track, ember from the reading start to the value, a white
 * thumb. [onChange] follows the drag; [onDone] when the finger lifts.
 */
@Composable
internal fun EditorSlider(target: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, onDone: () -> Unit = {}, label: String = "") {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The thumb follows the finger at once; the edit catches up a moment later.
    var dragged by remember { mutableStateOf<Float?>(null) }
    val value = dragged ?: target
    val move = { v: Float -> dragged = v; onChange(v) }
    val lift = { dragged = null; onDone() }
    BoxWithConstraints(modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = label; stateDescription = "${(value * 100).roundToInt()}%" }) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        fun at(x: Float) = ((if (rtl) width - x else x) / width).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(rtl, width) { detectTapGestures { move(at(it.x)); lift() } }
                .pointerInput(rtl, width) { detectHorizontalDragGestures(onDragEnd = { lift() }, onDragCancel = { lift() }) { change, _ -> move(at(change.position.x)) } },
        ) {
            Canvas(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp)) {
                drawRoundRect(Ink.copy(alpha = 0.12f), cornerRadius = CornerRadius(2.dp.toPx()))
                val w = size.width * value.coerceIn(0f, 1f)
                drawRoundRect(SigNoir.action, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(2.dp.toPx()))
            }
            val thumb = (this@BoxWithConstraints.maxWidth - 18.dp) * value.coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.CenterStart).offset(x = thumb).size(18.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White))
        }
    }
}

/**
 * Bars of a waveform, time running left to right on every language (a timeline's axis);
 * [tint] colours a bar by its index (marks, selections), [base] the rest.
 */
@Composable
internal fun Waveform(peaks: List<Float>, modifier: Modifier = Modifier, base: Color = Ink.copy(alpha = 0.38f), maxBar: Float = 24f, tint: (Int) -> Color? = { null }) {
    Canvas(modifier) { drawBars(peaks, base, maxBar, tint) }
}

internal fun DrawScope.drawBars(peaks: List<Float>, base: Color, maxBar: Float, tint: (Int) -> Color?) {
    if (peaks.isEmpty()) return
    val gap = 2.dp.toPx()
    val bar = ((size.width - gap * (peaks.size - 1)) / peaks.size).coerceAtLeast(1f)
    val radius = CornerRadius(1.dp.toPx())
    peaks.forEachIndexed { i, p ->
        val h = (3.dp.toPx() + p * maxBar.dp.toPx()).coerceAtMost(size.height)
        val x = i * (bar + gap)
        drawRoundRect(tint(i) ?: base, topLeft = Offset(x, (size.height - h) / 2f), size = Size(bar, h), cornerRadius = radius)
    }
}

/** Removed stretches as the board hatches them: ember stripes between ember edges. */
internal fun DrawScope.drawHatched(from: Float, to: Float) {
    val a = from * size.width
    val b = to * size.width
    if (b <= a) return
    clipRect(a, 0f, b, size.height) {
        drawRect(SigNoir.action.copy(alpha = 0.15f), topLeft = Offset(a, 0f), size = Size(b - a, size.height))
        val step = 9.dp.toPx()
        var x = a - size.height
        while (x < b) {
            drawLine(SigNoir.action.copy(alpha = 0.55f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 4.dp.toPx() * 0.7f)
            x += step
        }
    }
    drawLine(SigNoir.action, Offset(a, 0f), Offset(a, size.height), 1.5.dp.toPx())
    drawLine(SigNoir.action, Offset(b, 0f), Offset(b, size.height), 1.5.dp.toPx())
}

/** Time, the round play button and the director's line for the open tool. */
@Composable
internal fun Transport(ui: EditorUi, onPlay: () -> Unit, modifier: Modifier = Modifier, link: String? = null, onLink: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Figure("${clock(ui.position)} / ${clock(ui.duration)}", color = InkSoft, size = 11.5f, weight = 600) }
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(Ink).clickable(role = Role.Button, onClick = onPlay)
                .semantics { contentDescription = if (ui.playing) "pause" else "play" },
            contentAlignment = Alignment.Center,
        ) { TrimioIcon(if (ui.playing) TrimioIcons.Pause else TrimioIcons.Play, null, tint = SigNoir.canvas, size = 20.dp) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { link?.let { DirectorLine(it, onClick = onLink) } }
    }
}

/** The board's 1dp rule between list rows, on the dark material. */
@Composable
internal fun EditorRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Ink.copy(alpha = 0.08f)))
}

/** mm:ss. */
internal fun clock(seconds: Float): String {
    val s = seconds.coerceAtLeast(0f).toInt()
    return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
}

/** mm:ss.t (tenths), for word spans. */
internal fun clockTenths(seconds: Float): String {
    val tenths = (seconds.coerceAtLeast(0f) * 10).roundToInt()
    return clock(tenths / 10f) + "." + (tenths % 10)
}
