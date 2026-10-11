package io.trimio.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.engine.production.ElementKind
import io.trimio.engine.production.FactElement
import kotlin.math.abs
import kotlin.math.roundToInt

/** صدا: the music (the director's pick marked), its level under the voice, the effects and the mix's loudness. */
@Composable
internal fun SoundPanel(ui: EditorUi, actions: EditorActions, listHeight: Dp = 126.dp) {
    val s = ui.sound
    Column {
        PanelHeader(tr("صدا", "Sound")) {
            val ok = abs(s.lufs + TARGET_LUFS) < LUFS_TOLERANCE
            Figure("${s.lufs.roundToInt()} LUFS" + if (ok) " ✓" else "", color = if (ok) SigNoir.emphasis else InkSoft, size = 11.5f)
        }
        Column(Modifier.padding(top = 12.dp).heightIn(max = listHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.moods.forEachIndexed { i, m -> MoodLine(m, i, s, actions) }
            MoodLine(null, s.moods.size, s, actions)
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(tr("موسیقی زیر صدا", "Music under voice"))
            Figure("${s.musicDb.roundToInt()} dB", color = if (s.selected == null) InkSoft else Ink)
        }
        val span = MAX_DB - MIN_DB
        EditorSlider((s.musicDb - MIN_DB) / span, { actions.onMusicDb(MIN_DB + it * span, false) }, onDone = { actions.onMusicDb(s.musicDb, true) }, label = tr("موسیقی زیر صدا", "Music under voice"))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(tr("افکت روی تأکیدها", "Effects on accents"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SigChip(localizedNumber(tr("${s.effectCount} افکت", "${s.effectCount} effects")), Modifier.height(24.dp), container = Ink.copy(alpha = 0.1f), content = Ink)
                EditorToggle(s.effects, actions.onEffects, label = tr("افکت روی تأکیدها", "Effects on accents"))
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.32f))) {
            val marked = s.cues.map { (it * s.peaks.size).toInt() }.toSet()
            Waveform(
                s.peaks, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).height(32.dp), base = Ink.copy(alpha = 0.35f), maxBar = 26f,
                tint = { i -> SigNoir.action.takeIf { s.effects && (i in marked || i - 1 in marked) } },
            )
        }
        Transport(ui, actions.onPlay, link = tr("میکس خودکار", "Auto mix"), onLink = actions.onAutoMix)
    }
}

/** One music choice; [mood] null is "no music". The playing one sits in the ember ring. */
@Composable
private fun MoodLine(mood: MoodRow?, index: Int, s: SoundTool, actions: EditorActions) {
    val selected = mood?.id == s.selected
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Ink.copy(alpha = if (selected) 0.08f else 0.05f))
            .let { if (selected) it.border(1.5.dp, SigNoir.action, shape) else it }
            .clickable(role = Role.RadioButton) { actions.onMusic(mood?.id) }.padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (a, b) = TILES[index % TILES.size]
        val tile = if (mood == null) Brush.linearGradient(listOf(Ink.copy(alpha = 0.16f), Ink.copy(alpha = 0.06f))) else Brush.linearGradient(listOf(a, b))
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(tile), contentAlignment = Alignment.Center) {
            TrimioIcon(if (mood == null) TrimioIcons.Close else TrimioIcons.Music, null, tint = Ink, size = 18.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(mood?.let { tr(it.nameFa, it.nameEn) } ?: tr("بی‌موسیقی", "No music"), style = Sig.type.bodyStrong.copy(fontSize = 13.sp, lineHeight = 17.sp), color = Ink, maxLines = 1)
            mood?.let {
                val synced = if (selected) tr(" · هم‌گام با کات‌ها", " · on the cuts") else ""
                Text(localizedNumber(tr("${it.bpm} BPM", "${it.bpm} BPM")) + synced, style = Sig.type.meta.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = InkSoft, maxLines = 1)
            }
        }
        if (mood != null && mood.id == s.director) SigChip(tr("کارگردان", "Director"), Modifier.height(24.dp), container = SigNoir.emphasis.copy(alpha = 0.12f), content = SigNoir.emphasis)
    }
}

/** المان‌ها: every graphic with its switch, time and kind; adding one; the director's further suggestions. */
@Composable
internal fun ElementsPanel(ui: EditorUi, actions: EditorActions, listHeight: Dp = 232.dp) {
    val e = ui.elements
    Column {
        PanelHeader(tr("المان‌ها", "Elements")) {
            if (e.suggestions > 0) DirectorLine(localizedNumber(tr("${e.suggestions} پیشنهاد کارگردان", "${e.suggestions} director suggestions")))
        }
        Column(Modifier.padding(top = 6.dp).heightIn(max = listHeight).verticalScroll(rememberScrollState())) {
            if (e.rows.isEmpty()) {
                Text(tr("این ادیت هنوز المانی ندارد.", "This edit has no elements yet."), style = Sig.type.meta, color = InkSoft, modifier = Modifier.padding(vertical = 12.dp))
            }
            e.rows.forEachIndexed { i, row -> ElementLine(row, actions, last = i == e.rows.lastIndex) }
        }
        if (e.adding) {
            AddMenu(ui, actions)
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WideButton(tr("افزودن المان", "Add element"), { actions.onAdding(true) }, Modifier.weight(1f)) { TrimioIcon(TrimioIcons.Plus, null, tint = Ink, size = 18.dp) }
                WideButton(tr("پیشنهاد بیشتر", "More suggestions"), actions.onMore, Modifier.weight(1f), enabled = e.more) { DirectorDot(size = 12.dp) }
            }
        }
        Transport(ui, actions.onPlay)
    }
}

@Composable
private fun ElementLine(row: ElementRow, actions: EditorActions, last: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val (tile, glyph) = kindTile(row.kind)
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tile), contentAlignment = Alignment.Center) {
                Text(glyph, style = figureStyle(13f, 900), color = SigNoir.canvas)
            }
            Column(Modifier.weight(1f)) {
                Text(title(row), style = Sig.type.bodyStrong.copy(fontSize = 13.sp, lineHeight = 17.sp), color = Ink, maxLines = 1)
                Text(sub(row), style = Sig.type.meta.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = InkSoft, maxLines = 1)
            }
            if (row.suggested) DirectorDot(size = 10.dp)
            EditorToggle(row.on, { actions.onElement(row.key, it) }, label = title(row))
        }
        if (!last) EditorRule()
    }
}

@Composable
private fun title(row: ElementRow): String {
    val text = row.title.take(TITLE_CHARS)
    return localizedNumber(
        when (row.kind) {
            FactElement.Kind.Title -> tr("تیتر «$text»", "Title “$text”")
            FactElement.Kind.Number -> tr("شمارندهٔ $text", "Counter $text")
            FactElement.Kind.Chart -> tr("نمودار $text", "Chart $text")
            FactElement.Kind.Icon -> tr("آیکون $text", "Icon $text")
            FactElement.Kind.Brand -> tr("نشان $text", "Logo $text")
            FactElement.Kind.Ask -> tr("دعوت به کامنت «$text»", "Comment ask “$text”")
            FactElement.Kind.Picture -> tr("تصویر $text", "Picture $text")
            FactElement.Kind.Screen -> tr("صفحه $text", "Screen $text")
            FactElement.Kind.Other -> text
        }.trim(),
    )
}

@Composable
private fun sub(row: ElementRow): String {
    val on = row.word?.trim('.', '،', ',')?.let { tr(" · روی «$it»", " · on “$it”") }.orEmpty()
    val origin = if (row.suggested) tr(" · پیشنهاد کارگردان", " · director's suggestion") else ""
    return localizedNumber(clock(row.at)) + on + origin
}

/** Each kind's tile colour and mark, as the board gives them. */
private fun kindTile(kind: FactElement.Kind): Pair<Color, String> = when (kind) {
    FactElement.Kind.Number -> SigNoir.emphasis to "%"
    FactElement.Kind.Brand -> Color(0xFFFFB547) to "₿"
    FactElement.Kind.Chart -> Ink to "≈"
    FactElement.Kind.Title -> Ink to "T"
    FactElement.Kind.Icon -> SigNoir.action to "✦"
    FactElement.Kind.Ask -> SigNoir.action to "@"
    FactElement.Kind.Picture -> Color(0xFF7FB2FF) to "▣"
    FactElement.Kind.Screen -> Color(0xFF7FB2FF) to "▤"
    FactElement.Kind.Other -> Ink.copy(alpha = 0.7f) to "•"
}

@Composable
private fun AddMenu(ui: EditorUi, actions: EditorActions) {
    val quote = ui.text.quote
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (quote.isEmpty()) tr("اول در «متن» کلمه‌ای را انتخاب کن.", "Pick words in Text first.") else tr("روی «$quote»:", "On “$quote”:"),
            style = Sig.type.meta, color = InkSoft, maxLines = 1,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WideButton(tr("تیتر", "Title"), { actions.onAdd(ElementKind.Headline) }, Modifier.weight(1f), enabled = quote.isNotEmpty()) {}
            WideButton(tr("ضربه", "Impact"), { actions.onAdd(ElementKind.Impact) }, Modifier.weight(1f), enabled = quote.isNotEmpty()) {}
            WideButton(tr("شمارنده", "Counter"), { actions.onAdd(ElementKind.Counter) }, Modifier.weight(1f), enabled = ui.text.hasNumber) {}
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Ink.copy(alpha = 0.08f)).clickable(role = Role.Button) { actions.onAdding(false) },
                contentAlignment = Alignment.Center,
            ) {
                TrimioIcon(TrimioIcons.Close, tr("بستن", "Close"), tint = InkSoft, size = 18.dp)
            }
        }
    }
}

@Composable
private fun WideButton(text: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true, leading: @Composable () -> Unit) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(Ink.copy(alpha = 0.08f)).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Text(text, style = Sig.type.bodyStrong.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight(700)), color = if (enabled) Ink else InkSoft.copy(alpha = 0.4f), maxLines = 1)
    }
}

/** برش: the voice with what the cut removes hatched, and the three cuts the engine makes, with their real counts. */
@Composable
internal fun CutPanel(ui: EditorUi, actions: EditorActions) {
    val c = ui.cut
    Column {
        PanelHeader(tr("برش", "Cut")) {
            // One left-to-right run: the source's length, then the edit's in acid.
            Text(
                buildAnnotatedString {
                    append("\u2066${clock(c.sourceDuration)} → ")
                    withStyle(SpanStyle(color = SigNoir.emphasis)) { append(clock(c.duration)) }
                    append("\u2069")
                },
                style = figureStyle(), color = InkLabel, maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(76.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.32f))) {
            Waveform(c.peaks, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).height(60.dp), maxBar = 30f)
            Canvas(Modifier.fillMaxWidth().height(76.dp)) { c.removed.forEach { drawHatched(it.start, it.endInclusive) } }
        }
        Column(Modifier.padding(top = 8.dp)) {
            CutLine(
                tr("حذف سکوت‌ها", "Remove silences"),
                localizedNumber(tr("${c.pauses} مکث · ${seconds(c.pauseSeconds)} ثانیه", "${c.pauses} pauses · ${seconds(c.pauseSeconds)} s")),
                c.silences, actions.onSilences, enabled = c.pauses > 0,
            )
            EditorRule()
            CutLine(
                tr("حذف تکیه‌کلام‌ها", "Remove filler words"),
                if (c.fillerCount == 0) {
                    tr("تکیه‌کلامی شنیده نشد", "No filler words heard")
                } else {
                    localizedNumber(c.fillerWords.joinToString("، ") { "«$it»" } + tr(" · ${c.fillerCount} مورد", " · ${c.fillerCount} found"))
                },
                c.fillers, actions.onFillers, enabled = c.fillerCount > 0,
            )
            EditorRule()
            CutLine(
                tr("کات روی ضرب موسیقی", "Cut on the beat"),
                if (c.beatCutsAvailable) localizedNumber(tr("جابه‌جایی کات‌ها تا ۸۰ میلی‌ثانیه", "Cuts move up to 80 ms")) else tr("این ادیت موسیقی ندارد", "This edit has no music"),
                c.beatCuts, actions.onBeatCuts, enabled = c.beatCutsAvailable,
            )
        }
        Transport(ui, actions.onPlay)
    }
}

@Composable
private fun ColumnScope.CutLine(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Sig.type.bodyStrong.copy(fontSize = 13.sp, lineHeight = 17.sp), color = Ink, maxLines = 1)
            Text(sub, style = Sig.type.meta.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = InkSoft, maxLines = 1)
        }
        EditorToggle(on, onChange, enabled = enabled, label = title)
    }
}

/** 4.1 → "4.1", with the Persian decimal mark in Persian (digits are localised by the caller). */
@Composable
private fun seconds(s: Float): String {
    val tenths = (s * 10).roundToInt()
    return "${tenths / 10}" + tr("٫", ".") + "${tenths % 10}"
}

private const val TARGET_LUFS = 14f
private const val LUFS_TOLERANCE = 1f
private const val MIN_DB = -32f
private const val MAX_DB = -6f
private const val TITLE_CHARS = 22
private val TILES = listOf(
    Color(0xFFFF5B2E) to Color(0xFF7A2A10), Color(0xFF7FB2FF) to Color(0xFF1D2B55), Color(0xFFD7FF3A) to Color(0xFF3D4A0C),
    Color(0xFFFFB547) to Color(0xFF6B3A10), Color(0xFFB7A6FF) to Color(0xFF2C2357), Color(0xFFFF5470) to Color(0xFF5A1422),
)
