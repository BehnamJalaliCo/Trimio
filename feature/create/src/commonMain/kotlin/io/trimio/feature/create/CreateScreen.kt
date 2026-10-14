package io.trimio.feature.create

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.MediaKind
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.DirectorText
import io.trimio.core.designsystem.signature.DisplayText
import io.trimio.core.designsystem.signature.FootagePlaceholder
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.signature.SigOptionRow
import io.trimio.core.designsystem.signature.SigRoundButton
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SigSectionLabel
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigTextButton
import io.trimio.core.designsystem.signature.SigTextField
import io.trimio.core.designsystem.signature.SigTopRow
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.input.MediaInfo

/** [onProduce] hands the brief to the automatic engine (the production runner), which makes the project. */
@Composable
fun CreateRoute(
    viewModel: CreateViewModel,
    onBack: () -> Unit,
    onProduce: (CreateState) -> Unit,
    onPickMedia: (MediaKind) -> Unit = {},
    /** The models screen, for a speed whose director is not installed yet. */
    onModels: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CreateScreen(
        state,
        CreateActions(
            onBack = onBack, onPickMedia = onPickMedia, onPrompt = viewModel::setPrompt, onExpert = viewModel::setExpert,
            onAddTerm = viewModel::addTerm, onRemoveTerm = viewModel::removeTerm, onAttach = viewModel::attach, onDetach = viewModel::detach,
            onCaption = viewModel::setCaption, onStyle = viewModel::setStyle,
            onSpeed = { mode -> if (!viewModel.setSpeed(mode)) onModels() },
            onEdit = viewModel::setEdit,
            onSubtitles = viewModel::setSubtitles,
            onCreate = { if (state.canCreate) onProduce(state) },
        ),
    )
}

/**
 * Board 2, first screen: the footage (or the voice) on top, the brief as large type with what
 * matters on the lime block, what the director understood, the user's attachments and exact
 * spellings, the style (automatic by default) and one ember action. On wide windows the footage
 * becomes a stage beside the brief.
 */
@Composable
fun CreateScreen(state: CreateState, actions: CreateActions, pickerOpen: Boolean = false) {
    var picking by remember { mutableStateOf(pickerOpen) }
    val ranked = remember(state.brief) { StyleCards.ranked(state.brief) }
    SigScreen {
        SigAdaptive(Modifier.imePadding()) { width ->
            when {
                !state.hasMedia -> ChooseMedia(actions)
                width.wide -> Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.9f).fillMaxHeight().padding(SigSpace.xl), verticalArrangement = Arrangement.spacedBy(SigSpace.lg)) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            MediaHero(state, actions, Modifier.fillMaxHeight().aspectRatio(9f / 16f).clip(RoundedCornerShape(SigSpace.radiusLg)), framed = true)
                        }
                        CreateAction(state, actions)
                    }
                    Column(Modifier.weight(1.1f).widthIn(max = 640.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = SigSpace.xl)) {
                        BriefColumn(state, actions, ranked) { picking = true }
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            MediaHero(state, actions, Modifier.fillMaxWidth().height(268.dp))
                            BriefColumn(state, actions, ranked) { picking = true }
                        }
                        // The brief fades into the action at its foot: what is below reads as more to scroll to.
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Sig.colors.canvas))))
                    }
                    CreateAction(state, actions, Modifier.gutter().padding(bottom = 8.dp))
                }
            }
            if (picking) {
                StylePickerOverlay(width.wide, onDismiss = { picking = false }) { sheet, columns ->
                    StylePicker(ranked, state.styleId, actions.onStyle, onClose = { picking = false }, onConfirm = { picking = false }, modifier = sheet, columns = columns)
                }
            }
        }
    }
}

@Composable
private fun BriefColumn(state: CreateState, actions: CreateActions, ranked: List<Pair<StyleCard, Int>>, onAllStyles: () -> Unit) {
    Column(Modifier.fillMaxWidth().gutter().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (!state.probing && state.info == null) UnreadableMedia(state, actions)
        PromptBlock(state, actions)
        if (state.prompt.isNotBlank()) Understood(state)
        if (state.kind == MediaKind.Video) EditKindBlock(state.edit, state.subtitles, actions.onEdit, actions.onSubtitles)
        Attachments(state.attachments, actions)
        Glossary(state.glossary, actions)
        StyleRow(state, ranked, actions.onStyle, onAllStyles)
        Spacer(Modifier.height(4.dp))
    }
}

/** The footage on top, faded into the canvas, with the format chip and the way back. */
@Composable
private fun MediaHero(state: CreateState, actions: CreateActions, modifier: Modifier, framed: Boolean = false) {
    val c = Sig.colors
    Box(modifier) {
        if (state.kind == MediaKind.Video) FootagePlaceholder(Modifier.fillMaxSize()) else VoiceArt(Modifier.fillMaxSize())
        if (!framed) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to c.canvas)))
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SigRoundButton(TrimioIcons.Back, tr("بازگشت", "Back"), actions.onBack)
            FormatChip(state.info, state.kind, state.probing)
        }
    }
}

@Composable
private fun FormatChip(info: MediaInfo?, kind: MediaKind, probing: Boolean) {
    val text = when {
        probing -> tr("در حال خواندن…", "Reading…")
        info == null -> tr("خوانده نشد", "Unreadable")
        // Latin format notes stay left-to-right inside the Persian line (no reversed "1080×1920").
        else -> listOfNotNull(
            info.video?.takeIf { kind == MediaKind.Video }?.let { v -> "\u2066${ratio(v.displayWidth, v.displayHeight)} · ${minOf(v.displayWidth, v.displayHeight)}p\u2069" }
                ?: tr("فقط صدا", "Audio only"),
            localizedNumber(clock(info.durationMs)),
        ).joinToString("  ·  ")
    }
    SigGlass(Modifier.height(32.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 12.dp), overMedia = true) {
        Text(text, Modifier.align(Alignment.Center), style = Sig.type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight(600)), color = SigNoir.ink)
    }
}

/** Audio-only projects have no footage: the voice itself, as a warm waveform on the noir canvas. */
@Composable
private fun VoiceArt(modifier: Modifier) {
    val ember = Sig.colors.action
    Box(
        modifier.background(Color(0xFF100D0B)).drawBehind {
            drawRect(Brush.radialGradient(listOf(ember.copy(alpha = 0.32f), Color.Transparent), center = Offset(size.width * 0.7f, size.height * 0.35f), radius = size.width * 0.7f))
            val bars = 48
            val step = size.width / bars
            for (i in 0 until bars) {
                val t = i / bars.toFloat()
                val h = size.height * 0.36f * (0.25f + 0.75f * kotlin.math.abs(kotlin.math.sin(t * 19f) * kotlin.math.cos(t * 7f)))
                drawRoundRect(
                    if (i % 7 == 3) ember else SigNoir.ink.copy(alpha = 0.55f),
                    topLeft = Offset(i * step + step * 0.25f, size.height * 0.5f - h / 2), size = Size(step * 0.5f, h), cornerRadius = CornerRadius(step),
                )
            }
        },
    )
}

/** The brief: large type, with the words that matter on the lime block as the user types them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptBlock(state: CreateState, actions: CreateActions) {
    val c = Sig.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DirectorDot(size = 10.dp)
            Spacer(Modifier.width(8.dp))
            DirectorText(tr("کارگردان گوش می‌دهد", "The director is listening"), Modifier.weight(1f))
            ModeSwitch(state.expert, actions.onExpert)
        }
        val style = Sig.type.lead.copy(fontSize = if (state.expert) 16.sp else 19.sp, lineHeight = if (state.expert) 28.sp else 33.sp, fontWeight = FontWeight(600))
        val emphasis = SpanStyle(background = c.emphasis, color = c.onEmphasis, fontWeight = FontWeight(800))
        BasicTextField(
            value = state.prompt,
            onValueChange = actions.onPrompt,
            modifier = Modifier.fillMaxWidth(),
            textStyle = style.copy(color = c.ink),
            cursorBrush = SolidColor(c.action),
            minLines = if (state.expert) 5 else 2,
            maxLines = if (state.expert) 14 else 5,
            visualTransformation = remember(state.glossary, c) { EmphasisTransformation(state.glossary, emphasis) },
            decorationBox = { inner ->
                Box {
                    if (state.prompt.isEmpty()) Text(placeholder(state.expert), style = style.copy(fontWeight = FontWeight(400)), color = c.faint)
                    inner()
                }
            },
        )
        Hairline()
        val examples = if (state.expert) expertStarters() else examples()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            examples.forEach { (label, text) ->
                Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { actions.onPrompt(appendTo(state.prompt, text, state.expert)) }) {
                    SigChip(label, content = c.muted, stroke = c.line, container = Color.Transparent)
                }
            }
        }
    }
}

@Composable
private fun ModeSwitch(expert: Boolean, onExpert: (Boolean) -> Unit) {
    val c = Sig.colors
    Row(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(c.ink.copy(alpha = 0.06f)).padding(3.dp)) {
        listOf(false to tr("ساده", "Simple"), true to tr("تخصصی", "Expert")).forEach { (mode, label) ->
            val on = mode == expert
            Box(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(if (on) c.ink else Color.Transparent)
                    .clickable(role = Role.Tab) { onExpert(mode) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = Sig.type.meta.copy(fontSize = 11.5.sp, fontWeight = FontWeight(700)), color = if (on) c.canvas else c.muted) }
        }
    }
}

/** What the director took from the brief, in the board's three hairline columns. */
@Composable
private fun Understood(state: CreateState) {
    val b = state.brief
    val energy = b.energy?.let { localizedNumber("${(it * 100).toInt()}") } ?: tr("خودکار", "Auto")
    val emphasis = b.emphasize.take(2).joinToString(tr("، ", ", ")).ifEmpty { tr("اعداد", "Numbers") }
    Column {
        Hairline(strong = true)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ReadoutCell(tr("انرژی", "Energy"), energy, Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(Sig.colors.line))
            ReadoutCell(tr("مقصد", "For"), destination(state), Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(Sig.colors.line))
            ReadoutCell(tr("تأکید", "Emphasis"), emphasis, Modifier.weight(1.3f))
        }
        Hairline(strong = true)
    }
}

@Composable
private fun ReadoutCell(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
        Text(label, style = Sig.type.meta.copy(fontSize = 11.sp), color = Sig.colors.muted)
        Text(value, style = Sig.type.bodyStrong.copy(fontSize = 15.sp), color = Sig.colors.ink, maxLines = 1)
    }
}

/** Pictures, clips and logos with an optional caption each; the director places them as they are spoken of. */
@Composable
private fun Attachments(items: List<Attachment>, actions: CreateActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SigSectionLabel(tr("پیوست‌ها", "Attachments"), trailing = "PICTURE · CLIP · LOGO")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { AttachmentCard(it, actions) }
            AddAttachment(actions.onAttach)
        }
    }
}

@Composable
private fun AttachmentCard(item: Attachment, actions: CreateActions) {
    val c = Sig.colors
    Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp))) {
            val thumb = item.thumbnail
            when {
                thumb != null -> Image(thumb, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                item.kind == AttachmentKind.Logo -> LogoPlaceholder(item.name, Modifier.fillMaxSize())
                else -> FootagePlaceholder(Modifier.fillMaxSize())
            }
            SigGlass(Modifier.align(Alignment.TopStart).padding(6.dp).height(22.dp), shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 8.dp), overMedia = true) {
                Text(kindLabel(item.kind), Modifier.align(Alignment.Center), style = Sig.type.meta.copy(fontSize = 10.sp, fontWeight = FontWeight(700)), color = SigNoir.ink)
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .clickable(role = Role.Button) { actions.onDetach(item.id) },
                contentAlignment = Alignment.Center,
            ) { TrimioIcon(TrimioIcons.Close, tr("حذف", "Remove"), tint = SigNoir.ink, size = 12.dp) }
        }
        SigTextField(
            item.caption, { actions.onCaption(item.id, it) }, tr("توضیح کوتاه (اختیاری)", "Caption (optional)"),
            style = Sig.type.meta.copy(fontSize = 11.5.sp), maxLines = 2,
        )
    }
}

@Composable
private fun LogoPlaceholder(name: String, modifier: Modifier) {
    val c = Sig.colors
    Box(modifier.background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.ink), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = Sig.type.figure.copy(fontSize = 24.sp), color = c.canvas)
        }
    }
}

@Composable
private fun AddAttachment(onAttach: (AttachmentKind) -> Unit) {
    val c = Sig.colors
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.size(112.dp).clip(shape).border(1.dp, c.lineStrong, shape).padding(vertical = 6.dp), verticalArrangement = Arrangement.SpaceEvenly) {
        listOf(AttachmentKind.Picture to TrimioIcons.VideoFrame, AttachmentKind.Clip to TrimioIcons.Film, AttachmentKind.Logo to TrimioIcons.Sparkle).forEach { (kind, icon) ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onAttach(kind) }.padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                TrimioIcon(TrimioIcons.Plus, null, tint = c.action, size = 14.dp)
                Text(kindLabel(kind), style = Sig.type.meta.copy(fontWeight = FontWeight(700)), color = c.ink, modifier = Modifier.weight(1f))
                TrimioIcon(icon, null, tint = c.muted, size = 14.dp)
            }
        }
    }
}

/** Exact spellings: each chip goes into the prompt the engine reads. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Glossary(terms: List<String>, actions: CreateActions) {
    val c = Sig.colors
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SigSectionLabel(tr("واژه‌نامه", "Glossary"), trailing = "EXACT SPELLING")
        Text(
            tr("اسم دقیق سایت، برند یا اصطلاح؛ کارگردان همین املا را می‌نویسد.", "Exact site, brand or term names; the director writes them exactly so."),
            style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            terms.forEach { term ->
                val t = EnginePrompt.term(term)
                SigChip(
                    t.meaning?.let { "${t.text} · $it" } ?: t.text, stroke = c.lineStrong, container = Color.Transparent,
                    leading = {
                        Box(Modifier.size(16.dp).clip(CircleShape).clickable(role = Role.Button) { actions.onRemoveTerm(term) }, contentAlignment = Alignment.Center) {
                            TrimioIcon(TrimioIcons.Close, tr("حذف", "Remove"), tint = c.muted, size = 11.dp)
                        }
                    },
                )
            }
            Row(
                Modifier.height(28.dp).widthIn(min = 150.dp).clip(RoundedCornerShape(14.dp)).background(c.ink.copy(alpha = 0.06f)).padding(horizontal = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                TrimioIcon(TrimioIcons.Plus, null, tint = c.action, size = 13.dp)
                SigTextField(
                    draft, { draft = it }, tr("مثلاً tetherland.com", "e.g. tetherland.com"),
                    style = Sig.type.meta.copy(fontWeight = FontWeight(600)),
                    modifier = Modifier.width(140.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.onAddTerm(draft); draft = "" }),
                )
            }
        }
    }
}

/** "خودکار" plus the director's two best fits; every style is one tap away in the picker. */
@Composable
private fun StyleRow(state: CreateState, ranked: List<Pair<StyleCard, Int>>, onStyle: (String?) -> Unit, onAll: () -> Unit) {
    val chosen = ranked.firstOrNull { it.first.id == state.styleId }
    val shown = (listOfNotNull(chosen) + ranked).distinctBy { it.first.id }.take(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("سبک · کارگردان پیشنهاد می‌دهد", "Style · the director suggests"), style = Sig.type.meta, color = Sig.colors.muted, modifier = Modifier.weight(1f))
            SigTextButton(localizedNumber(tr("همهٔ 28 سبک", "All 28 styles")), onAll)
        }
        Row(Modifier.widthIn(max = 460.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StyleTile(null, state.styleId == null, { onStyle(null) }, Modifier.weight(1f))
            shown.forEach { (card, fit) -> StyleTile(card, card.id == state.styleId, { onStyle(card.id) }, Modifier.weight(1f), fit = fit) }
        }
    }
}

@Composable
private fun CreateAction(state: CreateState, actions: CreateActions, modifier: Modifier = Modifier) {
    val c = Sig.colors
    val onDevice = state.director == io.trimio.core.pipeline.DirectorBackend.OnDevice
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.hot) {
            SigChip(
                tr("گوشی داغ است؛ ساخت آرام‌تر و خنک‌تر انجام می‌شود", "Your phone is hot: the build runs cooler and slower"),
                container = c.emphasisSoft, content = c.emphasisSoftContent,
            )
        }
        if (onDevice) SpeedChoice(state.speeds, state.speed, actions.onSpeed)
        SigButton(
            if (state.creating) tr("در حال آماده‌سازی…", "Preparing…") else tr("بساز", "Make it"),
            actions.onCreate, Modifier.fillMaxWidth(), trailing = TrimioIcons.ChevronForward, enabled = state.canCreate,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TrimioIcon(TrimioIcons.Phone, null, tint = c.faint, size = 12.dp)
            Text(
                if (onDevice) speedLine(state.speeds, state.speed) else tr("روی همین دستگاه · بدون اینترنت", "On this device · offline"),
                style = Sig.type.meta.copy(fontSize = 11.sp), color = c.faint, maxLines = 2,
            )
        }
    }
}

@Composable
private fun UnreadableMedia(state: CreateState, actions: CreateActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("این فایل خوانده نشد.", "This file could not be read."), style = Sig.type.title, color = Sig.colors.ink)
        Text(
            tr("فایل ممکن است ناقص یا در قالبی باشد که این دستگاه نمی‌شناسد.", "It may be incomplete, or in a format this device does not know."),
            style = Sig.type.meta, color = Sig.colors.muted,
        )
        SigOptionRow(1, tr("فایل دیگری انتخاب کن", "Choose another file"), tr("ویدیو یا فقط صدا", "A video or audio only"), { actions.onPickMedia(state.kind) }, director = true)
    }
}

/** Opened without a file: the board's two entry tiles (video, audio only) under a display question. */
@Composable
private fun BoxScope.ChooseMedia(actions: CreateActions) {
    val c = Sig.colors
    Column(Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp).fillMaxWidth()) {
        SigTopRow("NEW · PROJECT", actions.onBack, backLabel = tr("بازگشت", "Back"))
        DisplayText(
            buildAnnotatedString {
                append(tr("از چه\nبسازیم", "Start\nfrom what"))
                withStyle(SpanStyle(color = c.action)) { append(tr("؟", "?")) }
            },
            style = Sig.type.hero,
            modifier = Modifier.gutter().padding(top = 18.dp, bottom = 26.dp),
        )
        Hairline(strong = true)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).gutter()) {
            EntryTile(tr("ویدیو", "Video"), tr("موشن روی تصویر خودت", "Motion over your footage"), "A", TrimioIcons.VideoFrame, Modifier.weight(1f).padding(end = 14.dp)) {
                actions.onPickMedia(MediaKind.Video)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.lineStrong))
            EntryTile(tr("فقط صدا", "Audio only"), tr("فیلم توضیحی از صفر ساخته می‌شود", "An explainer film made from scratch"), "B", TrimioIcons.Mic, Modifier.weight(1f).padding(start = 14.dp)) {
                actions.onPickMedia(MediaKind.Audio)
            }
        }
        Hairline(strong = true)
        Text(
            tr("ویدیوی حرف‌زدن یا یک فایل صدا؛ بقیه را کارگردان می‌سازد.", "A talking video or an audio file; the director makes the rest."),
            style = Sig.type.meta, color = c.muted, modifier = Modifier.gutter().padding(top = 16.dp),
        )
    }
}

@Composable
private fun EntryTile(title: String, hint: String, letter: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = Sig.colors
    Column(modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TrimioIcon(icon, null, tint = c.ink)
            MicroLabel(letter)
        }
        Text(title, style = Sig.type.title, color = c.ink, modifier = Modifier.padding(top = 18.dp))
        Text(hint, style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted)
    }
}

/**
 * Puts the words that matter on the lime block while the user types: glossary terms, «quoted»
 * words and numbers. Offsets are unchanged, so editing and the cursor behave as plain text.
 */
private class EmphasisTransformation(glossary: List<String>, private val style: SpanStyle) : VisualTransformation {
    private val terms = glossary.map { EnginePrompt.term(it).text }.filter { it.length >= 2 }

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val ranges = buildList {
            terms.forEach { t -> Regex(Regex.escape(t), RegexOption.IGNORE_CASE).findAll(raw).forEach { add(it.range) } }
            QUOTED.findAll(raw).forEach { add(it.range) }
            NUMBER.findAll(raw).forEach { add(it.range) }
        }
        val out = buildAnnotatedString {
            append(raw)
            ranges.forEach { addStyle(style, it.first, it.last + 1) }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is EmphasisTransformation && other.terms == terms && other.style == style
    override fun hashCode() = terms.hashCode() * 31 + style.hashCode()

    private companion object {
        val QUOTED = Regex("«[^»]{1,40}»")
        val NUMBER = Regex("[0-9۰-۹]+(?:[.,٫][0-9۰-۹]+)?[%٪]?")
    }
}

@Composable
private fun placeholder(expert: Boolean) = if (expert) {
    tr(
        "بریف کامل: هوک سه ثانیهٔ اول، مقصد (ریلز، یوتیوب)، روی چه چیزی تأکید شود، موسیقی و ریتم، دعوت به اقدام…",
        "Full brief: the first three seconds' hook, destination (Reels, YouTube), what to emphasise, music and pace, call to action…",
    )
} else {
    tr("چه می‌خواهی؟ مثلاً «یک ریلز پرانرژی، روی اعداد تأکید کن»", "What do you want? e.g. “an energetic Reel, emphasise the numbers”")
}

@Composable
private fun examples(): List<Pair<String, String>> = listOf(
    tr("ریلز پرانرژی", "Energetic Reel") to tr("یک ریلز پرانرژی بساز؛ هوک در سه ثانیهٔ اول", "Make an energetic Reel; hook in the first three seconds"),
    tr("روی اعداد تأکید کن", "Emphasise numbers") to tr("روی اعداد و درصدها تأکید کن", "Emphasise the numbers and percentages"),
    tr("آرام و لوکس", "Calm and premium") to tr("آرام و لوکس، موسیقی آرام", "Calm and premium, soft music"),
    tr("آموزشی با زیرنویس", "Tutorial, subtitles") to tr("آموزشی، زیرنویس کامل", "Tutorial with full subtitles"),
)

@Composable
private fun expertStarters(): List<Pair<String, String>> = listOf(
    tr("+ هوک", "+ Hook") to tr("هوک: ", "Hook: "),
    tr("+ مقصد", "+ Destination") to tr("مقصد: ریلز اینستاگرام", "Destination: Instagram Reels"),
    tr("+ تأکید", "+ Emphasis") to tr("تأکید: ", "Emphasis: "),
    tr("+ موسیقی", "+ Music") to tr("موسیقی: ", "Music: "),
    tr("+ دعوت به اقدام", "+ Call to action") to tr("دعوت به اقدام: ", "Call to action: "),
)

private fun appendTo(prompt: String, text: String, expert: Boolean): String = when {
    prompt.isBlank() -> text
    expert -> prompt.trimEnd() + "\n" + text
    else -> prompt.trimEnd() + "، " + text
}

@Composable
private fun destination(state: CreateState): String {
    val p = state.prompt
    return when {
        "یوتیوب" in p || "youtube" in p.lowercase() -> tr("یوتیوب", "YouTube")
        "شورتس" in p || "shorts" in p.lowercase() -> tr("شورتس", "Shorts")
        else -> tr("ریلز", "Reels")
    }
}

@Composable
private fun kindLabel(kind: AttachmentKind) = when (kind) {
    AttachmentKind.Picture -> tr("عکس", "Picture")
    AttachmentKind.Clip -> tr("کلیپ", "Clip")
    AttachmentKind.Logo -> tr("لوگو", "Logo")
}

private fun ratio(w: Int, h: Int): String {
    tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    val g = gcd(w, h).coerceAtLeast(1)
    return "${w / g}:${h / g}"
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
}
