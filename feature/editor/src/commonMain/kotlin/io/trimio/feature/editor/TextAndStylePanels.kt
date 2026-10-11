package io.trimio.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigActionSquare
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.signature.SigTextField
import io.trimio.core.designsystem.theme.tr
import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.production.CaptionDensity
import io.trimio.feature.create.StyleCards
import io.trimio.feature.create.StyleSwatch

/** متن: the line's words as chips, what to do with the picked ones, the line's sound, and the director. */
@Composable
internal fun TextPanel(ui: EditorUi, actions: EditorActions) {
    val t = ui.text
    var editing by remember(t.selection, t.quote) { mutableStateOf(false) }
    var rewriting by remember { mutableStateOf(false) }
    Column {
        PanelHeader(if (t.quote.isEmpty()) tr("متن", "Text") else "«${t.quote}»") {
            MicroLabel("${clockTenths(t.spanStart)} → ${clockTenths(t.spanEnd)}", color = Ink.copy(alpha = 0.5f))
        }
        when {
            rewriting -> Composer(
                hint = tr("مثلاً «تیتر اول رو کوتاه‌تر کن»", "e.g. “make the first title shorter”"),
                initial = "", onSend = { rewriting = false; actions.onRewrite(it) }, onClose = { rewriting = false }, director = true,
            )
            editing -> Composer(
                hint = tr("متن تازه", "New text"), initial = t.quote,
                onSend = { editing = false; actions.onEditText(it) }, onClose = { editing = false }, director = false,
            )
            else -> {
                Chips(t, actions)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionTile(EditorIcons.Star, tr("تأکید", "Emphasis"), t.emphasised, actions.onEmphasise, Modifier.weight(1f))
                    ActionTile(TrimioIcons.Text, tr("ویرایش", "Edit"), false, { editing = true }, Modifier.weight(1f))
                    ActionTile(EditorIcons.Bolt, tr("ضربه", "Hit"), t.hit, actions.onHit, Modifier.weight(1f))
                    ActionTile(
                        if (t.cut) EditorIcons.Restore else EditorIcons.Cut, if (t.cut) tr("بازگردانی", "Restore") else tr("حذف", "Delete"),
                        false, actions.onDelete, Modifier.weight(1f),
                    )
                }
            }
        }
        LineTimeline(ui, actions.onSeek, Modifier.padding(top = 12.dp))
        Transport(ui, actions.onPlay, link = tr("بازنویسی با کارگردان", "Rewrite with the director"), onLink = { rewriting = true })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(t: TextTool, actions: EditorActions) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        t.chips.forEach { chip ->
            val picked = t.selection?.let { chip.index in it } == true
            val shape = RoundedCornerShape(10.dp)
            val weight = when {
                picked -> 900
                chip.emphasised -> 800
                else -> 600
            }
            Box(
                Modifier.clip(shape)
                    .background(
                        when {
                            picked -> SigNoir.emphasis
                            chip.cut -> Color.Transparent
                            chip.emphasised -> Ink.copy(alpha = 0.12f)
                            else -> Ink.copy(alpha = 0.06f)
                        },
                    )
                    .let { if (picked) it.border(2.dp, Color.White, shape) else it }
                    .pointerInput(chip.index) { detectTapGestures(onTap = { actions.onWord(chip.index, false) }, onLongPress = { actions.onWord(chip.index, true) }) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(
                    chip.text,
                    style = Sig.type.body.copy(
                        fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight(weight),
                        textDecoration = if (chip.cut) TextDecoration.LineThrough else null,
                    ),
                    color = (if (picked) SigNoir.onEmphasis else Ink).copy(alpha = if (chip.cut) 0.35f else 1f),
                )
                if (chip.hit) Box(Modifier.align(Alignment.TopEnd).padding(top = 0.dp).size(5.dp).clip(RoundedCornerShape(3.dp)).background(SigNoir.action))
            }
        }
    }
}

/** One of the four word actions: acid when it is on for the picked words. */
@Composable
private fun ActionTile(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val content = if (on) SigNoir.onEmphasis else Ink
    Column(
        modifier.height(54.dp).clip(RoundedCornerShape(12.dp)).background(if (on) SigNoir.emphasis else Ink.copy(alpha = 0.08f))
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        TrimioIcon(icon, null, tint = content, size = 18.dp)
        Text(label, style = Sig.type.meta.copy(fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight(700)), color = content, maxLines = 1)
    }
}

/** The line on its own clock: the words as a strip (the picked ones in acid), its sound, the playhead. */
@Composable
private fun LineTimeline(ui: EditorUi, onSeek: (Float) -> Unit, modifier: Modifier) {
    val t = ui.text
    val span = (t.windowEnd - t.windowStart).coerceAtLeast(0.01f)
    Box(
        modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.32f))
            .pointerInput(t.windowStart, span) { detectTapGestures { onSeek(t.windowStart + span * (it.x / size.width)) } },
    ) {
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 8.dp).height(14.dp)) {
            t.segments.forEach { s ->
                val a = ((s.start - t.windowStart) / span).coerceIn(0f, 1f) * size.width
                val b = ((s.end - t.windowStart) / span).coerceIn(0f, 1f) * size.width
                drawRoundRect(
                    if (s.selected) SigNoir.emphasis else Ink.copy(alpha = 0.16f), topLeft = Offset(a, 0f), size = Size((b - a - 2.dp.toPx()).coerceAtLeast(2f), size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
            }
        }
        Waveform(t.peaks, Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 28.dp).height(34.dp))
        Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            val f = ((ui.position - t.windowStart) / span)
            if (f in 0f..1f) {
                val x = f * size.width
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent), startX = x - 8.dp.toPx(), endX = x + 8.dp.toPx()), Offset(x - 8.dp.toPx(), 0f), Size(16.dp.toPx(), size.height))
                drawRect(Color.White, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            }
        }
    }
}

/** A line to type into: the creator's own text, or their words to the director (with its bead). */
@Composable
internal fun Composer(hint: String, initial: String, onSend: (String) -> Unit, onClose: () -> Unit, director: Boolean) {
    var text by remember { mutableStateOf(initial) }
    val send = { if (text.isNotBlank()) onSend(text.trim()) }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).height(56.dp).clip(RoundedCornerShape(16.dp)).background(Ink.copy(alpha = 0.08f))
            .padding(start = 14.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (director) DirectorDot(size = 14.dp)
        Box(Modifier.weight(1f)) {
            SigTextField(
                text, { text = it }, hint, Modifier.fillMaxWidth(), style = Sig.type.body.copy(color = Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { send() }),
            )
        }
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
            TrimioIcon(TrimioIcons.Close, tr("بستن", "Close"), tint = InkSoft, size = 18.dp)
        }
        SigActionSquare(if (director) TrimioIcons.Send else TrimioIcons.Check, if (director) tr("ارسال", "Send") else tr("ثبت", "Apply"), { send() }, size = 44.dp)
    }
}

/** سبک: the styles (the director's pick marked), motion energy, caption density, and before/after. */
@Composable
internal fun StylePanel(ui: EditorUi, actions: EditorActions) {
    val s = ui.style
    Column {
        PanelHeader(tr("سبک", "Style")) {
            StyleBook.of(s.director)?.let { DirectorLine(tr("کارگردان: ${it.nameFa.substringBefore(' ')} بهترین است", "Director: ${it.nameEn} fits best")) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.styles.forEach { id -> StyleBook.of(id)?.let { StyleCardTile(it.id, it.nameFa.substringBefore(' '), it.nameEn, id == s.selected) { actions.onStyle(id) } } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(tr("انرژی حرکت", "Motion energy"))
            Figure("${s.energy}")
        }
        EditorSlider(s.energy / 100f, { actions.onEnergy(it, false) }, onDone = { actions.onEnergy(s.energy / 100f, true) }, label = tr("انرژی حرکت", "Motion energy"))
        FieldLabel(tr("چگالی زیرنویس", "Caption density"), Modifier.padding(top = 10.dp))
        DensitySegments(s.density, actions.onDensity, Modifier.padding(top = 8.dp))
        Transport(ui, actions.onPlay, link = tr("مقایسهٔ قبل و بعد", "Compare before and after"), onLink = { actions.onCompare(!s.comparing) })
    }
}

/** A style as a still of its own look, its short name at the foot; the chosen one in the ember ring. */
@Composable
private fun StyleCardTile(id: String, nameFa: String, nameEn: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val card = StyleCards.all.firstOrNull { it.id == id } ?: return
    Box(
        Modifier.size(86.dp, 110.dp).let { if (selected) it.border(2.dp, SigNoir.action, shape) else it }.padding(if (selected) 2.dp else 0.dp)
            .clip(RoundedCornerShape(if (selected) 12.dp else 14.dp)).clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        StyleSwatch(card.copy(sample = card.sample), Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(34.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (card.dark) 0.35f else 0f)))))
        Text(
            tr(nameFa, nameEn), style = Sig.type.meta.copy(fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight(700)),
            color = if (card.dark) Ink else Color(0xFF111111), maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun DensitySegments(selected: CaptionDensity, onSelect: (CaptionDensity) -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(Ink.copy(alpha = 0.07f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CaptionDensity.entries.forEach { d ->
            val on = d == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(if (on) Ink else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(d) },
                contentAlignment = Alignment.Center,
            ) {
                Text(densityName(d), style = Sig.type.bodyStrong.copy(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight(700)), color = if (on) SigNoir.canvas else InkSoft)
            }
        }
    }
}

@Composable
private fun densityName(d: CaptionDensity): String = when (d) {
    CaptionDensity.Word -> tr("کلمه‌ای", "Word")
    CaptionDensity.Phrase -> tr("عبارتی", "Phrase")
    CaptionDensity.Sentence -> tr("جمله‌ای", "Sentence")
}

/** Padding inside the glass panel. */
internal val PanelPadding = PaddingValues(14.dp)

/** The ember glow under a pressed control. */
internal fun Modifier.glow(on: Boolean): Modifier = if (on) shadow(10.dp, RoundedCornerShape(14.dp), ambientColor = SigNoir.action) else this
