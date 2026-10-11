package io.trimio.feature.stream

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.FootagePlaceholder
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigActionSquare
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.signature.SigRoundButton
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SigSecondaryButton
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigTextField
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr

/**
 * Board 2, fourth screen, grown into the conversation the engine supports: the finished edit
 * playing, its numbers, what the jury said, a line to tell the director what to change, another
 * version, and saving or sharing the file.
 */
@Composable
fun ResultScreen(result: BuildResult, actions: ResultActions, player: (@Composable BoxScope.() -> Unit)? = null) {
    SigScreen {
        SigAdaptive { width ->
            if (width.wide) {
                Row(Modifier.fillMaxSize().padding(SigSpace.xl), horizontalArrangement = Arrangement.spacedBy(SigSpace.xxl)) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Preview(result, actions.onPlay, Modifier.fillMaxHeight().aspectRatio(9f / 16f), player)
                    }
                    Column(Modifier.weight(1f).widthIn(max = 580.dp).fillMaxHeight()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            ResultTop(result, actions.onClose, Modifier)
                            Body(result, actions)
                        }
                        Actions(result, actions, Modifier.padding(top = 12.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        ResultTop(result, actions.onClose, Modifier.gutter())
                        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                            Preview(result, actions.onPlay, Modifier.height(330.dp).aspectRatio(9f / 16f), player)
                        }
                        Column(Modifier.gutter()) { Body(result, actions) }
                    }
                    Actions(result, actions, Modifier.gutter().padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ResultTop(result: BuildResult, onClose: () -> Unit, modifier: Modifier) {
    val c = Sig.colors
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SigRoundButton(TrimioIcons.Close, tr("بستن", "Close"), onClose, overMedia = false)
            MicroLabel("DONE · ${clock(result.durationMs)}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SigChip(
                when {
                    result.edited -> localizedNumber(tr("نسخهٔ ${result.version} با ویرایش تو آماده شد", "Version ${result.version} with your edits is ready"))
                    result.version > 1 -> localizedNumber(tr("نسخهٔ ${result.version} آماده شد", "Version ${result.version} ready"))
                    else -> tr("آماده شد", "Ready")
                },
                container = c.emphasisSoft, content = c.emphasisSoftContent,
                leading = { TrimioIcon(TrimioIcons.Check, null, tint = c.emphasisSoftContent, size = 13.dp) },
            )
            Text(
                localizedNumber(took(result.buildSeconds, result.onDevice)),
                style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted, maxLines = 1,
            )
        }
    }
}

/** The exported file playing (the platform player when given), or its poster with the play control. */
@Composable
private fun Preview(result: BuildResult, onPlay: () -> Unit, modifier: Modifier, player: (@Composable BoxScope.() -> Unit)?) {
    val shape = RoundedCornerShape(SigSpace.radiusLg)
    Box(modifier.clip(shape).border(1.dp, Sig.colors.lineStrong, shape)) {
        when {
            player != null -> player()
            result.poster != null -> Image(result.poster, result.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> FootagePlaceholder(Modifier.fillMaxSize())
        }
        if (player == null) {
            SigGlass(Modifier.align(Alignment.Center).size(58.dp).clickable(role = Role.Button, onClick = onPlay), shape = CircleShape, overMedia = true) {
                TrimioIcon(TrimioIcons.Play, tr("پخش", "Play"), tint = SigNoir.ink, size = 22.dp, modifier = Modifier.align(Alignment.Center))
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(14.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(SigNoir.ink.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(0.12f).fillMaxHeight().background(SigNoir.ink))
            }
        }
    }
}

@Composable
private fun Body(result: BuildResult, actions: ResultActions) {
    Column(Modifier.padding(top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Stats(result)
        JurySummary(result)
        Feedback(actions.onFeedback, enabled = !result.working)
        val edit = actions.onEdit
        if (edit != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SigSecondaryButton(
                    if (result.edited) tr("ادامهٔ ویرایش", "Keep editing") else tr("باز کردن در ادیتور", "Open in editor"),
                    edit, Modifier.weight(1.5f).height(50.dp), icon = TrimioIcons.Edit,
                )
                SigSecondaryButton(tr("نسخهٔ دیگر", "Another version"), actions.onVariation, Modifier.weight(1f).height(50.dp), icon = TrimioIcons.Sparkle)
            }
        } else {
            SigSecondaryButton(tr("نسخهٔ دیگر بساز", "Make another version"), actions.onVariation, Modifier.fillMaxWidth().height(50.dp), icon = TrimioIcons.Sparkle)
        }
    }
}

/** The board's four numbers between hairlines: scenes, words, sound effects and loudness. */
@Composable
private fun Stats(result: BuildResult) {
    val c = Sig.colors
    Column {
        Hairline(strong = true)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            listOf(
                localizedNumber("${result.scenes}") to tr("صحنه", "Scenes"),
                localizedNumber("${result.words}") to tr("کلمه", "Words"),
                localizedNumber("${result.effects}") to tr("افکت صدا", "Effects"),
                "⁦${result.lufs.toInt()}⁩" to "LUFS",
            ).forEachIndexed { i, (value, label) ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                Column(Modifier.weight(1f).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, style = Sig.type.figure.copy(fontSize = 20.sp), color = c.ink)
                    Text(label, style = Sig.type.meta.copy(fontSize = 10.5.sp), color = c.muted)
                }
            }
        }
        Hairline(strong = true)
    }
}

@Composable
private fun JurySummary(result: BuildResult) {
    val c = Sig.colors
    val perfect = result.judges.count { it.perfect }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("هیئت داوران", "The jury"), style = Sig.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
            Text(
                if (perfect == result.judges.size) tr("هر ده داور ۱۰ دادند", "All ten gave 10") else localizedNumber(tr("$perfect از ۱۰ داور ۱۰ دادند", "$perfect of 10 gave 10")),
                style = Sig.type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight(700)), color = if (perfect == result.judges.size) c.ink else c.muted,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            result.judges.forEach { JudgeBead(it) }
        }
        result.findings.take(2).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 8.dp).size(4.dp).clip(CircleShape).background(c.faint))
                Text(line, style = Sig.type.meta, color = c.muted)
            }
        }
    }
}

/** "Tell the director": quick reactions, or the creator's own words. */
@Composable
private fun Feedback(onFeedback: (String) -> Unit, enabled: Boolean) {
    val c = Sig.colors
    var text by remember { mutableStateOf("") }
    val send = { if (enabled && text.isNotBlank()) { onFeedback(text.trim()); text = "" } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("به کارگردان بگو چه چیزی عوض شود", "Tell the director what to change"), style = Sig.type.meta, color = c.muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                tr("هوک رو بزرگ‌تر کن", "Bigger hook"), tr("پرانرژی‌ترش کن", "More energy"),
                tr("متن کمتر", "Less text"), tr("موسیقی آرام‌تر", "Softer music"),
            ).forEach { s ->
                Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, role = Role.Button) { onFeedback(s) }) {
                    SigChip(s, container = Color.Transparent, content = c.ink, stroke = c.lineStrong)
                }
            }
        }
        SigGlass(Modifier.fillMaxWidth().height(60.dp), contentPadding = PaddingValues(start = 16.dp, end = 7.dp)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                DirectorDot()
                SigTextField(
                    text, { text = it }, tr("مثلاً «هوک رو بزرگ‌تر کن»", "e.g. “make the hook bigger”"), Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                )
                SigActionSquare(TrimioIcons.Send, tr("ارسال", "Send"), { send() })
            }
        }
    }
}

@Composable
private fun Actions(result: BuildResult, actions: ResultActions, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SigButton(
            if (result.saved) tr("در گالری ذخیره شد", "Saved to gallery") else tr("ذخیره در گالری", "Save to gallery"),
            actions.onSave, Modifier.weight(if (actions.onExport != null) 1.4f else 2f), trailing = if (result.saved) TrimioIcons.Check else TrimioIcons.Download, enabled = !result.saved,
        )
        val export = actions.onExport
        if (export != null) {
            SigSecondaryButton(tr("خروجی", "Export"), export, Modifier.weight(1f), icon = TrimioIcons.Export)
            ShareSquare(actions.onShare)
        } else {
            SigSecondaryButton(tr("اشتراک", "Share"), actions.onShare, Modifier.weight(1f), icon = TrimioIcons.Share)
        }
    }
}

/** Share as a square beside the export action, when three actions share the row. */
@Composable
private fun ShareSquare(onShare: () -> Unit) {
    val c = Sig.colors
    val shape = RoundedCornerShape(SigSpace.radiusMd)
    Box(
        Modifier.size(SigSpace.controlHeight).clip(shape).background(c.surface).border(1.dp, c.lineStrong, shape).clickable(role = Role.Button, onClick = onShare),
        contentAlignment = Alignment.Center,
    ) { TrimioIcon(TrimioIcons.Share, tr("اشتراک", "Share"), tint = c.ink, size = 20.dp) }
}

@Composable
private fun took(seconds: Int, onDevice: Boolean): String {
    val m = seconds / 60
    val s = seconds % 60
    val where = if (onDevice) tr("روی همین دستگاه", "on this device") else tr("در ابر", "in the cloud")
    return when {
        m == 0 -> tr("در $s ثانیه $where", "in $s s $where")
        s == 0 -> tr("در $m دقیقه $where", "in $m min $where")
        else -> tr("در $m دقیقه و $s ثانیه $where", "in $m min $s s $where")
    }
}

internal fun clock(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
}
