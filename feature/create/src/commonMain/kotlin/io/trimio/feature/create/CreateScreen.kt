package io.trimio.feature.create

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.MediaKind
import io.trimio.core.designsystem.component.ComposerField
import io.trimio.core.designsystem.component.GlassChip
import io.trimio.core.designsystem.component.GlassIconButton
import io.trimio.core.designsystem.component.GlassPanel
import io.trimio.core.designsystem.component.PillSelector
import io.trimio.core.designsystem.component.SectionLabel
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.component.TrimioScreen
import io.trimio.core.designsystem.component.TrimioTopBar
import io.trimio.core.designsystem.component.pressable
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.input.AspectRatio
import io.trimio.core.model.style.CaptionMode
import io.trimio.core.model.style.DesignStyle
import io.trimio.core.model.text.Language
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.engine.director.Brief
import io.trimio.engine.director.Lexicon
import io.trimio.engine.styles.StylePack

@Composable
fun CreateRoute(viewModel: CreateViewModel, onBack: () -> Unit, onCreated: (String) -> Unit, onSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val persian = Trimio.language == Language.Persian
    CreateScreen(
        state = state,
        onBack = onBack,
        onPrompt = viewModel::setPrompt,
        onImprove = { viewModel.improvePrompt(persian) },
        onStyle = viewModel::setStyle,
        onDirector = viewModel::setDirector,
        onAspect = viewModel::setAspect,
        onCreate = { viewModel.create(onCreated) },
        onSettings = onSettings,
    )
}

/**
 * The prompt, as a conversation: the app asks, offers starting points, and shows live what it
 * understood from what the user typed (style, energy, music, cuts…) before anything is built.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateScreen(
    state: CreateState,
    onBack: () -> Unit,
    onPrompt: (String) -> Unit,
    onImprove: () -> Unit,
    onStyle: (String?) -> Unit,
    onDirector: (DirectorBackend) -> Unit,
    onAspect: (AspectRatio) -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = Trimio.colors
    TrimioScreen(dimAurora = 0.5f) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TrimioTopBar(
                title = tr("پروژه\u0654 جدید", "New project"),
                subtitle = if (state.kind == MediaKind.Video) tr("از ویدیو", "From a video") else tr("فقط صدا", "Audio only"),
                onBack = onBack,
                backLabel = tr("بازگشت", "Back"),
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = TrimioSpacing.screenGutter),
                verticalArrangement = Arrangement.spacedBy(TrimioSpacing.lg),
            ) {
                MediaCard(state)

                AssistantBubble(tr("چی می\u200Cخوای بسازی؟ ساده بگو یا یه بریف کامل بنویس.", "What should we make? Say it simply, or write a full brief."))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm), verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
                    suggestions().forEach { s -> Box(Modifier.pressable { onPrompt(s) }) { GlassChip(s) } }
                }

                AnimatedVisibility(state.prompt.isNotBlank(), enter = fadeIn() + scaleIn(initialScale = 0.96f), exit = fadeOut()) {
                    Understood(state.brief, state.packs)
                }

                Column {
                    SectionLabel(tr("سبک", "Style"))
                    StyleRow(state.packs, state.styleId ?: state.brief.styleId, onStyle)
                }

                if (state.kind == MediaKind.Audio) {
                    Column {
                        SectionLabel(tr("قاب خروجی", "Frame"))
                        PillSelector(
                            options = listOf(AspectRatio.Portrait9x16, AspectRatio.Portrait4x5, AspectRatio.Square1x1, AspectRatio.Landscape16x9),
                            selected = state.aspect,
                            label = { localizedNumber("${it.w}:${it.h}") },
                            onSelect = onAspect,
                        )
                    }
                }

                Column {
                    SectionLabel(tr("کارگردان", "Director"))
                    PillSelector(
                        options = DirectorBackend.entries,
                        selected = state.director,
                        label = { if (it == DirectorBackend.OnDevice) tr("روی گوشی", "On device") else tr("ابری", "Cloud") },
                        leading = { if (it == DirectorBackend.OnDevice) TrimioIcons.Phone else TrimioIcons.Cloud },
                        onSelect = { if (it == DirectorBackend.Cloud && !state.cloudConnected) onSettings() else onDirector(it) },
                    )
                    Text(
                        if (state.director == DirectorBackend.OnDevice) tr("خصوصی و آفلاین؛ چیزی از گوشی خارج نمی\u200Cشود.", "Private and offline: nothing leaves your phone.")
                        else tr("با کلید خودت؛ فقط متن گفتار و پرامپت ارسال می\u200Cشود.", "With your own key: only the transcript and prompt are sent."),
                        style = Trimio.type.caption,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = TrimioSpacing.xs),
                    )
                }
                Spacer(Modifier.height(TrimioSpacing.lg))
            }
            Composer(state, onPrompt, onImprove, onCreate)
        }
    }
}

@Composable
private fun MediaCard(state: CreateState) {
    val colors = Trimio.colors
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(TrimioSpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.md)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(TrimioRadius.sm))
                    .background(Brush.linearGradient(listOf(colors.primary, colors.accentMagenta))),
                contentAlignment = Alignment.Center,
            ) { TrimioIcon(if (state.kind == MediaKind.Video) TrimioIcons.Film else TrimioIcons.Wave, null, tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.kind == MediaKind.Video) tr("ویدیوی انتخاب\u200Cشده", "Selected video") else tr("صدای انتخاب\u200Cشده", "Selected audio"),
                    style = Trimio.type.title, color = colors.textPrimary,
                )
                val info = state.info
                Text(
                    when {
                        state.probing -> tr("در حال خواندن فایل…", "Reading the file…")
                        info == null -> tr("این فایل خوانده نشد", "Couldn't read this file") + (state.probeError?.let { " ($it)" } ?: "")
                        else -> localizedNumber(
                            listOfNotNull(
                                duration(info.durationMs),
                                info.video?.let { "${it.displayWidth}×${it.displayHeight}" },
                                info.video?.let { "${it.frameRate.toInt()} fps" },
                                info.video?.takeIf { it.isHdr }?.let { "HDR" },
                            ).joinToString(" · "),
                        )
                    },
                    style = Trimio.type.label,
                    color = if (!state.probing && state.info == null) colors.danger else colors.textSecondary,
                )
            }
            if (state.probing) CircularProgressIndicator(Modifier.size(22.dp), color = colors.accentCyan, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    val colors = Trimio.colors
    Row(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Brush.radialGradient(listOf(colors.accentCyan, colors.primary))),
            contentAlignment = Alignment.Center,
        ) { TrimioIcon(TrimioIcons.Sparkle, null, tint = Color.White, size = 16.dp) }
        Box(
            Modifier.clip(RoundedCornerShape(topStart = 4.dp, topEnd = TrimioRadius.md, bottomEnd = TrimioRadius.md, bottomStart = TrimioRadius.md))
                .background(colors.canvasRaised.copy(alpha = 0.9f))
                .border(1.dp, colors.glassStroke, RoundedCornerShape(topStart = 4.dp, topEnd = TrimioRadius.md, bottomEnd = TrimioRadius.md, bottomStart = TrimioRadius.md))
                .padding(horizontal = TrimioSpacing.md, vertical = TrimioSpacing.sm),
        ) { Text(text, style = Trimio.type.body, color = colors.textPrimary) }
    }
}

@Composable
private fun suggestions(): List<String> = listOf(
    tr("پرانرژی برای ریلز", "High-energy Reel"),
    tr("سیگنال کریپتو با تأکید روی اعداد", "Crypto signal, highlight the numbers"),
    tr("آرام و لوکس، بدون موزیک", "Calm and premium, no music"),
    tr("آموزشی، زیرنویس کامل", "Tutorial with full subtitles"),
    tr("سبک نئوبروتال، فان", "Playful neobrutalism"),
)

/** Live readout of the deterministic brief: the user sees what the Director will honour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Understood(brief: Brief, packs: List<StylePack>) {
    val colors = Trimio.colors
    Column(verticalArrangement = Arrangement.spacedBy(TrimioSpacing.xs)) {
        SectionLabel(tr("برداشت Trimio از پرامپت", "What Trimio understood"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm), verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
            brief.styleId?.let { id ->
                val name = packs.firstOrNull { it.id == id }?.let { tr(it.nameFa, it.nameEn) } ?: DesignStyle.fromId(id)?.let { tr(it.nameFa, it.nameEn) } ?: id
                GlassChip(tr("سبک: $name", "Style: $name"), accent = colors.accentMagenta)
            }
            brief.energy?.let { e ->
                GlassChip(
                    when {
                        e >= 0.75f -> tr("انرژی بالا", "High energy")
                        e <= 0.45f -> tr("آرام", "Calm")
                        else -> tr("انرژی متوسط", "Medium energy")
                    },
                    accent = colors.accentCyan,
                )
            }
            if (!brief.music) GlassChip(tr("بدون موزیک", "No music"), accent = colors.accentAmber)
            if (!brief.sfx) GlassChip(tr("بدون افکت صوتی", "No sound effects"), accent = colors.accentAmber)
            if (!brief.cutSilences) GlassChip(tr("مکث\u200Cها حفظ شوند", "Keep pauses"), accent = colors.accentAmber)
            brief.captionMode?.let { m ->
                GlassChip(
                    when (m) {
                        CaptionMode.Karaoke -> tr("زیرنویس کارائوکه", "Karaoke captions")
                        CaptionMode.SingleWord -> tr("تک\u200Cکلمه", "One word at a time")
                        CaptionMode.Phrase -> tr("زیرنویس کامل", "Full subtitles")
                        CaptionMode.BuildUp -> tr("کلمه به کلمه", "Word by word")
                    },
                    accent = colors.accentCyan,
                )
            }
            brief.topics.forEach { GlassChip(topicLabel(it), accent = colors.success) }
            brief.emphasize.take(4).forEach { GlassChip("«$it»", accent = colors.accentMagenta) }
            brief.headline?.let { GlassChip(tr("عنوان: $it", "Title: $it"), accent = colors.accentCyan) }
        }
    }
}

@Composable
private fun StyleRow(packs: List<StylePack>, selected: String?, onStyle: (String?) -> Unit) {
    val options: List<StylePack?> = listOf(null) + packs
    PillSelector(
        options = options,
        selected = packs.firstOrNull { it.id == selected },
        label = { it?.let { p -> tr(p.nameFa, p.nameEn) } ?: tr("خودکار", "Auto") },
        leading = { if (it == null) TrimioIcons.Sparkle else null },
        onSelect = { onStyle(it?.id) },
    )
}

@Composable
private fun Composer(state: CreateState, onPrompt: (String) -> Unit, onImprove: () -> Unit, onCreate: () -> Unit) {
    val colors = Trimio.colors
    GlassPanel(
        Modifier.fillMaxWidth().padding(horizontal = TrimioSpacing.md, vertical = TrimioSpacing.sm),
        radius = TrimioRadius.xl,
        contentPadding = PaddingValues(start = TrimioSpacing.lg, end = TrimioSpacing.xs, top = TrimioSpacing.xs, bottom = TrimioSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ComposerField(
                value = state.prompt,
                onValueChange = onPrompt,
                placeholder = tr("مثلا\u064B: پرانرژی، روی «سود» تأکید کن، بدون موزیک", "e.g. energetic, emphasise “profit”, no music"),
                modifier = Modifier.weight(1f).padding(vertical = TrimioSpacing.sm),
                maxLines = 5,
            )
            GlassIconButton(TrimioIcons.Wand, tr("بهبود پرامپت", "Improve prompt"), onImprove, tint = colors.accentCyan)
            Spacer(Modifier.width(TrimioSpacing.xxs))
            SendButton(enabled = state.canCreate, busy = state.creating, onClick = onCreate)
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    val colors = Trimio.colors
    Box(
        Modifier.padding(2.dp).size(48.dp).pressable(enabled = enabled && !busy, onClick = onClick).clip(CircleShape)
            .background(if (enabled) Brush.linearGradient(listOf(colors.primary, colors.accentMagenta)) else Brush.linearGradient(listOf(colors.textTertiary, colors.textTertiary))),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else TrimioIcon(TrimioIcons.Send, tr("بساز", "Create"), tint = Color.White)
    }
}

private fun duration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
private fun topicLabel(topic: Lexicon.Topic): String = when (topic) {
    Lexicon.Topic.Crypto -> tr("کریپتو", "Crypto")
    Lexicon.Topic.Forex -> tr("فارکس", "Forex")
    Lexicon.Topic.Trading -> tr("ترید", "Trading")
    Lexicon.Topic.Business -> tr("کسب\u200Cوکار", "Business")
    Lexicon.Topic.Tech -> tr("تکنولوژی", "Tech")
    Lexicon.Topic.Education -> tr("آموزشی", "Education")
    Lexicon.Topic.Motivation -> tr("انگیزشی", "Motivation")
    Lexicon.Topic.Ad -> tr("تبلیغاتی", "Ad")
}
