package io.trimio.feature.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.Project
import io.trimio.core.designsystem.component.ComposerField
import io.trimio.core.designsystem.component.GlassIconButton
import io.trimio.core.designsystem.component.GlassPanel
import io.trimio.core.designsystem.component.PillSelector
import io.trimio.core.designsystem.component.SectionLabel
import io.trimio.core.designsystem.component.TrimioButton
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.component.TrimioScreen
import io.trimio.core.designsystem.component.TrimioSwitch
import io.trimio.core.designsystem.component.TrimioTopBar
import io.trimio.core.designsystem.component.pressable
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.timeline.Timeline
import io.trimio.engine.director.EditPlanSchema
import io.trimio.engine.render.PreviewClock
import io.trimio.engine.render.TimelinePreview
import io.trimio.engine.styles.StylePack
import kotlinx.coroutines.launch

@Composable
fun EditorRoute(viewModel: EditorViewModel, onBack: () -> Unit, onExport: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    EditorScreen(
        state = state,
        clock = viewModel.clock,
        onBack = { scope.launch { viewModel.flush(); onBack() } },
        onExport = { scope.launch { viewModel.flush()?.let { onExport(it.id) } } },
        onUndo = viewModel::undo,
        onTab = viewModel::selectTab,
        onTogglePlay = viewModel::togglePlay,
        onSelectWord = viewModel::selectWord,
        onEditWord = viewModel::setWordText,
        onToggleEmphasis = viewModel::toggleEmphasis,
        onToggleRemoved = viewModel::toggleRemoved,
        onStyle = viewModel::setStyle,
        onMusic = viewModel::setMusic,
        onSfx = viewModel::setSfx,
    )
}

/**
 * Text-first editor: the live preview (footage under the real renderer), a CapCut-style
 * multi-track timeline, and three panels — words (edit, emphasise, cut), style (live morph) and
 * sound (music mood, effects). Every change is instant in the preview and undoable.
 */
@Composable
fun EditorScreen(
    state: EditorState,
    clock: PreviewClock,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onUndo: () -> Unit,
    onTab: (EditorTab) -> Unit,
    onTogglePlay: () -> Unit,
    onSelectWord: (Int?) -> Unit,
    onEditWord: (Int, String) -> Unit,
    onToggleEmphasis: (Int) -> Unit,
    onToggleRemoved: (Int) -> Unit,
    onStyle: (StylePack) -> Unit,
    onMusic: (String) -> Unit,
    onSfx: (Boolean) -> Unit,
) {
    val project = state.project
    val timeline = state.timeline
    val style = state.style
    TrimioScreen(dimAurora = 0.7f) {
        Column(Modifier.fillMaxSize()) {
            TrimioTopBar(
                title = project?.title ?: "",
                subtitle = if (state.saved) tr("ذخیره شد", "Saved") else tr("در حال ذخیره…", "Saving…"),
                onBack = onBack,
                backLabel = tr("بازگشت", "Back"),
            ) {
                if (state.canUndo) GlassIconButton(TrimioIcons.Undo, tr("برگرداندن", "Undo"), onUndo)
                GlassIconButton(TrimioIcons.Share, tr("خروجی", "Export"), onExport, highlighted = true)
            }
            if (project == null || timeline == null || style == null) return@Column

            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // Side by side on tablets and unfolded foldables; stacked on phones.
                if (maxWidth > 760.dp) {
                    Row(Modifier.fillMaxSize().padding(horizontal = TrimioSpacing.screenGutter), horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.lg)) {
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TrimioSpacing.md)) {
                            Preview(project, timeline, style, clock, onTogglePlay, Modifier.weight(1f).fillMaxWidth())
                            TimelineTracks(timeline, clock, project.input.durationMs, project.waveform, Project.WAVEFORM_STEP_MS, label = tr("تایم\u200Cلاین", "Timeline"))
                        }
                        Panels(state, Modifier.weight(1f), onTab, onSelectWord, onEditWord, onToggleEmphasis, onToggleRemoved, onStyle, onMusic, onSfx)
                    }
                } else {
                    val previewMax = maxHeight * 0.42f
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
                        Preview(project, timeline, style, clock, onTogglePlay, Modifier.fillMaxWidth().heightIn(max = previewMax).padding(horizontal = TrimioSpacing.screenGutter))
                        TimelineTracks(timeline, clock, project.input.durationMs, project.waveform, Project.WAVEFORM_STEP_MS, label = tr("تایم\u200Cلاین", "Timeline"))
                        Panels(state, Modifier.weight(1f).padding(horizontal = TrimioSpacing.screenGutter), onTab, onSelectWord, onEditWord, onToggleEmphasis, onToggleRemoved, onStyle, onMusic, onSfx)
                    }
                }
            }
        }
    }
}

@Composable
private fun Preview(project: Project, timeline: Timeline, style: StyleSpec, clock: PreviewClock, onTogglePlay: () -> Unit, modifier: Modifier) {
    val aspect = timeline.canvas.widthPx.toFloat() / timeline.canvas.heightPx
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(TrimioRadius.lg))
                .background(Color.Black)
                .border(1.dp, Trimio.colors.glassStroke, RoundedCornerShape(TrimioRadius.lg)),
        ) {
            val footage = FootagePlayer(project.input, timeline, clock, Modifier.fillMaxSize())
            // Switching style morphs the picture: the new look scales in over the old one.
            val motion = Trimio.motion
            AnimatedContent(
                targetState = style,
                transitionSpec = { (fadeIn(motion.effects()) + scaleIn(motion.spatial(), initialScale = 1.04f)) togetherWith fadeOut(motion.effectsFast()) },
                label = "style-morph",
            ) { s ->
                TimelinePreview(timeline, s, Modifier.fillMaxSize(), clock, externalFootage = footage)
            }
            PlayButton(clock, onTogglePlay, Modifier.align(Alignment.BottomStart).padding(TrimioSpacing.sm))
        }
    }
}

@Composable
private fun PlayButton(clock: PreviewClock, onToggle: () -> Unit, modifier: Modifier) {
    var playing by remember { mutableStateOf(clock.playing) }
    Box(
        modifier.size(40.dp).pressable { onToggle(); playing = clock.playing }.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) { TrimioIcon(if (playing) TrimioIcons.Pause else TrimioIcons.Play, if (playing) tr("مکث", "Pause") else tr("پخش", "Play"), tint = Color.White, size = 18.dp) }
}

@Composable
private fun Panels(
    state: EditorState,
    modifier: Modifier,
    onTab: (EditorTab) -> Unit,
    onSelectWord: (Int?) -> Unit,
    onEditWord: (Int, String) -> Unit,
    onToggleEmphasis: (Int) -> Unit,
    onToggleRemoved: (Int) -> Unit,
    onStyle: (StylePack) -> Unit,
    onMusic: (String) -> Unit,
    onSfx: (Boolean) -> Unit,
) {
    Column(modifier) {
        PillSelector(
            options = EditorTab.entries,
            selected = state.tab,
            label = {
                when (it) {
                    EditorTab.Text -> tr("متن", "Words")
                    EditorTab.Style -> tr("سبک", "Style")
                    EditorTab.Sound -> tr("صدا", "Sound")
                }
            },
            leading = {
                when (it) {
                    EditorTab.Text -> TrimioIcons.Text
                    EditorTab.Style -> TrimioIcons.Palette
                    EditorTab.Sound -> TrimioIcons.Music
                }
            },
            onSelect = onTab,
        )
        Spacer(Modifier.height(TrimioSpacing.sm))
        val motion = Trimio.motion
        AnimatedContent(state.tab, transitionSpec = { fadeIn(motion.effects()) togetherWith fadeOut(motion.effectsFast()) }, label = "tab") { tab ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (tab) {
                    EditorTab.Text -> WordsPanel(state, onSelectWord, onEditWord, onToggleEmphasis, onToggleRemoved)
                    EditorTab.Style -> StylePanel(state, onStyle)
                    EditorTab.Sound -> SoundPanel(state, onMusic, onSfx)
                }
                Spacer(Modifier.height(TrimioSpacing.xxl))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordsPanel(
    state: EditorState,
    onSelectWord: (Int?) -> Unit,
    onEditWord: (Int, String) -> Unit,
    onToggleEmphasis: (Int) -> Unit,
    onToggleRemoved: (Int) -> Unit,
) {
    val colors = Trimio.colors
    val transcript = state.project?.transcript
    if (transcript == null || transcript.words.isEmpty()) {
        Text(tr("گفتاری در این فایل پیدا نشد.", "No speech was found in this file."), style = Trimio.type.body, color = colors.textSecondary)
        return
    }
    val selected = state.selectedWord
    AnimatedVisibility(selected != null, enter = slideInVertically { -it / 2 } + fadeIn(), exit = slideOutVertically { -it / 2 } + fadeOut()) {
        if (selected != null) WordActions(transcript.words[selected].text, selected, selected in state.emphasized, selected in state.removedWords, onEditWord, onToggleEmphasis, onToggleRemoved, onClose = { onSelectWord(null) })
    }
    // Words flow in the spoken language's direction, whatever the UI language.
    val rtl = transcript.language.isRtl
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = TrimioSpacing.sm)) {
            transcript.words.forEachIndexed { i, w ->
                val removed = i in state.removedWords
                val emphasized = i in state.emphasized
                val isSelected = i == selected
                val shape = RoundedCornerShape(TrimioRadius.sm)
                Text(
                    state.timeline?.clipsOf<io.trimio.core.model.timeline.CaptionClip>()?.firstOrNull { it.wordIndex == i }?.text ?: w.text,
                    style = Trimio.type.body.copy(textDirection = TextDirection.Content, textDecoration = if (removed) TextDecoration.LineThrough else null),
                    color = when {
                        removed -> colors.textTertiary
                        emphasized -> Color.White
                        w.confidence < 0.5f -> colors.accentAmber
                        else -> colors.textPrimary
                    },
                    modifier = Modifier
                        .pressable(pressedScale = 0.92f) { onSelectWord(if (isSelected) null else i) }
                        .clip(shape)
                        .then(if (emphasized && !removed) Modifier.background(Brush.horizontalGradient(listOf(colors.primary, colors.accentMagenta))) else Modifier.background(colors.glassFill))
                        .then(if (isSelected) Modifier.border(1.5.dp, colors.accentCyan, shape) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun WordActions(
    text: String,
    index: Int,
    emphasized: Boolean,
    removed: Boolean,
    onEditWord: (Int, String) -> Unit,
    onToggleEmphasis: (Int) -> Unit,
    onToggleRemoved: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember(index) { mutableStateOf(text) }
    GlassPanel(Modifier.fillMaxWidth().padding(bottom = TrimioSpacing.sm), contentPadding = PaddingValues(TrimioSpacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrimioIcon(TrimioIcons.Edit, null, tint = Trimio.colors.accentCyan, size = 18.dp)
                Spacer(Modifier.width(TrimioSpacing.sm))
                ComposerField(draft, { draft = it }, tr("متن کلمه", "Word text"), Modifier.weight(1f), singleLine = true)
                GlassIconButton(TrimioIcons.Check, tr("ثبت", "Apply"), { onEditWord(index, draft) })
                GlassIconButton(TrimioIcons.Close, tr("بستن", "Close"), onClose)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
                TrimioButton(
                    if (emphasized) tr("حذف تأکید", "Remove emphasis") else tr("تأکید", "Emphasise"),
                    { onToggleEmphasis(index) },
                    kind = io.trimio.core.designsystem.component.ButtonKind.Glass,
                    modifier = Modifier.weight(1f),
                    leading = { TrimioIcon(TrimioIcons.Sparkle, null, tint = Trimio.colors.accentMagenta, size = 18.dp) },
                )
                TrimioButton(
                    if (removed) tr("برگرداندن", "Restore") else tr("بریدن", "Cut"),
                    { onToggleRemoved(index) },
                    kind = io.trimio.core.designsystem.component.ButtonKind.Glass,
                    modifier = Modifier.weight(1f),
                    leading = { TrimioIcon(TrimioIcons.Scissors, null, tint = Trimio.colors.accentAmber, size = 18.dp) },
                )
            }
        }
    }
}

@Composable
private fun StylePanel(state: EditorState, onStyle: (StylePack) -> Unit) {
    val timeline = state.timeline ?: return
    val colors = Trimio.colors
    SectionLabel(tr("روی هر سبک بزن تا ویدیو همان لحظه عوض شود", "Tap a style to restyle the edit instantly"))
    val rows = state.packs.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(TrimioSpacing.md)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.md)) {
                row.forEach { pack ->
                    val selected = timeline.styleId == pack.id
                    val shape = RoundedCornerShape(TrimioRadius.md)
                    Column(Modifier.weight(1f).pressable { onStyle(pack) }) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(0.8f).clip(shape)
                                .border(if (selected) 2.dp else 1.dp, if (selected) colors.accentCyan else colors.glassStroke, shape),
                        ) {
                            val clock = remember(pack.id) { PreviewClock(minOf(timeline.durationMs / 3, 2_000)) }
                            TimelinePreview(timeline.copy(styleId = pack.id), pack.spec, Modifier.fillMaxSize(), clock)
                        }
                        Text(tr(pack.nameFa, pack.nameEn), style = Trimio.type.label, color = if (selected) colors.accentCyan else colors.textPrimary, modifier = Modifier.padding(top = TrimioSpacing.xs))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SoundPanel(state: EditorState, onMusic: (String) -> Unit, onSfx: (Boolean) -> Unit) {
    SectionLabel(tr("موسیقی پس\u200Cزمینه", "Music bed"))
    PillSelector(
        options = EditPlanSchema.MUSIC_MOODS,
        selected = state.musicMood,
        label = { musicLabel(it) },
        leading = { if (it == "none") null else TrimioIcons.Music },
        onSelect = onMusic,
    )
    Spacer(Modifier.height(TrimioSpacing.lg))
    io.trimio.core.designsystem.component.ListRow(
        title = tr("افکت\u200Cهای صوتی", "Sound effects"),
        description = tr("صدای ورود المان\u200Cها و تأکیدها", "Whooshes and hits on emphasis and elements"),
        icon = TrimioIcons.Sparkle,
    ) { TrimioSwitch(state.sfxOn, onSfx) }
    Text(
        tr("موسیقی زیر صدای گوینده خودکار کم می\u200Cشود و در پایان محو می\u200Cشود.", "Music ducks under the voice automatically and fades out at the end."),
        style = Trimio.type.caption,
        color = Trimio.colors.textTertiary,
    )
}

@Composable
private fun musicLabel(id: String): String = when (id) {
    "none" -> tr("بدون موسیقی", "None")
    "uplifting" -> tr("امیدبخش", "Uplifting")
    "energetic" -> tr("پرانرژی", "Energetic")
    "chill" -> tr("آرام", "Chill")
    "cinematic" -> tr("سینمایی", "Cinematic")
    "corporate" -> tr("رسمی", "Corporate")
    "tense" -> tr("پرتنش", "Tense")
    else -> id
}
