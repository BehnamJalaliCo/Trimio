package io.trimio.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.JobRunner
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.SettingsRepository
import io.trimio.core.data.Sharer
import io.trimio.core.designsystem.component.ButtonKind
import io.trimio.core.designsystem.component.LiquidProgressOrb
import io.trimio.core.designsystem.component.PillSelector
import io.trimio.core.designsystem.component.RingSegment
import io.trimio.core.designsystem.component.SectionLabel
import io.trimio.core.designsystem.component.TrimioButton
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
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.Resolution
import io.trimio.core.model.interchange.Interchange
import io.trimio.core.model.interchange.SourceMedia
import io.trimio.core.pipeline.JobStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Platform presets: aspect and the frame rate each platform plays best. */
enum class ExportPreset(val aspect: AspectRatio, val fa: String, val en: String) {
    Reels(AspectRatio.Portrait9x16, "ریلز اینستاگرام", "Instagram Reels"),
    Shorts(AspectRatio.Portrait9x16, "یوتیوب شورتس / تیک\u200Cتاک", "YouTube Shorts / TikTok"),
    YouTube(AspectRatio.Landscape16x9, "یوتیوب", "YouTube"),
    Feed(AspectRatio.Portrait4x5, "پست اینستاگرام", "Instagram feed"),
    Square(AspectRatio.Square1x1, "مربعی", "Square"),
}

data class ExportUiState(
    val project: Project? = null,
    val preset: ExportPreset = ExportPreset.Reels,
    val resolution: Resolution = Resolution.FullHd,
    val frameRate: Int = 30,
    val running: Boolean = false,
    val progress: Float = 0f,
    val status: JobStatus? = null,
    val error: String? = null,
    val outputUri: String? = null,
)

class ExportViewModel(
    private val projectId: String,
    private val projects: ProjectRepository,
    private val runner: JobRunner,
    private val settings: SettingsRepository,
    private val sharer: Sharer,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()
    private var following: Job? = null

    init {
        val p = projects.get(projectId)
        val canvas = p?.timeline?.canvas
        _state.value = ExportUiState(
            project = p,
            preset = ExportPreset.entries.firstOrNull { it.aspect == canvas?.aspect } ?: ExportPreset.Reels,
            resolution = settings.settings.value.exportResolution,
            frameRate = canvas?.frameRate ?: settings.settings.value.exportFrameRate,
        )
    }

    fun setPreset(p: ExportPreset) = _state.update { it.copy(preset = p) }
    fun setResolution(r: Resolution) = _state.update { it.copy(resolution = r) }
    fun setFrameRate(fps: Int) = _state.update { it.copy(frameRate = fps) }

    fun export() {
        val s = _state.value
        val project = projects.get(projectId) ?: return
        val timeline = project.timeline ?: return
        viewModelScope.launch {
            settings.update { it.copy(exportResolution = s.resolution, exportFrameRate = s.frameRate) }
            val updated = projects.update(projectId) {
                it.copy(timeline = timeline.copy(canvas = CanvasSpec(s.preset.aspect, s.resolution, s.frameRate)))
            } ?: return@launch
            val run = runner.export(updated) ?: return@launch
            _state.update { it.copy(running = true, progress = 0f, status = JobStatus.Running, error = null, outputUri = null) }
            following?.cancel()
            following = launch {
                run.state.collect { ps ->
                    if (ps == null) return@collect
                    _state.update { it.copy(progress = ps.overall, status = ps.status, error = ps.error, running = ps.status == JobStatus.Running) }
                    if (ps.status == JobStatus.Completed) _state.update { it.copy(outputUri = projects.get(projectId)?.outputUri) }
                }
            }
        }
    }

    /** Project files for Premiere (FCP7 XML + SRT) or DaVinci Resolve / Final Cut (FCPXML + SRT). */
    fun shareForEditor(premiere: Boolean) {
        val project = projects.get(projectId) ?: return
        val timeline = project.timeline ?: return
        val video = (project.input as? io.trimio.core.model.input.InputSource.Video)?.format
        val name = project.input.uri.value.substringAfterLast('/').substringBefore('?').ifBlank { "source" }
        val source = SourceMedia(project.input.uri.value, name, project.input.durationMs, video?.displayWidth ?: 0, video?.displayHeight ?: 0, video != null)
        val base = project.title.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifBlank { "trimio" }
        val files = buildMap {
            if (premiere) put("$base-premiere.xml", Interchange.xmeml(timeline, source, project.title))
            else put("$base.fcpxml", Interchange.fcpxml(timeline, source, project.title))
            put("$base.srt", Interchange.srt(timeline))
        }
        sharer.shareFiles(files, project.title)
    }

    fun share() {
        val uri = _state.value.outputUri ?: return
        sharer.share(uri, _state.value.project?.title ?: "Trimio")
    }
}

@Composable
fun ExportRoute(viewModel: ExportViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExportScreen(state, onBack, viewModel::setPreset, viewModel::setResolution, viewModel::setFrameRate, viewModel::export, viewModel::share, onDone, viewModel::shareForEditor)
}

@Composable
fun ExportScreen(
    state: ExportUiState,
    onBack: () -> Unit,
    onPreset: (ExportPreset) -> Unit,
    onResolution: (Resolution) -> Unit,
    onFrameRate: (Int) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDone: () -> Unit,
    onShareForEditor: (premiere: Boolean) -> Unit = {},
) {
    val colors = Trimio.colors
    TrimioScreen(dimAurora = 0.45f, energy = { if (state.running) 0.6f else 0f }) {
        Column(Modifier.fillMaxSize()) {
            TrimioTopBar(tr("خروجی", "Export"), subtitle = state.project?.title, onBack = onBack, backLabel = tr("بازگشت", "Back"))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = TrimioSpacing.screenGutter),
                verticalArrangement = Arrangement.spacedBy(TrimioSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.running || state.status == JobStatus.Completed) {
                    Spacer(Modifier.height(TrimioSpacing.lg))
                    LiquidProgressOrb(
                        progress = state.progress,
                        segments = listOf(RingSegment(weight = 1f, fill = state.progress, active = state.running)),
                        label = when (state.status) {
                            JobStatus.Completed -> tr("آماده شد", "Ready")
                            else -> tr("در حال ساخت فایل", "Rendering your file")
                        },
                    )
                    if (state.status == JobStatus.Completed) {
                        Text(tr("ویدیو در گالری ذخیره شد", "Saved to your gallery"), style = Trimio.type.title, color = colors.success)
                        TrimioButton(tr("اشتراک\u200Cگذاری", "Share"), onShare, Modifier.fillMaxWidth(), leading = { TrimioIcon(TrimioIcons.Share, null, tint = colors.onPrimary) })
                        TrimioButton(tr("بازگشت به استودیو", "Back to studio"), onDone, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
                    }
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel(tr("مقصد", "Destination"))
                        PresetGrid(state.preset, onPreset)
                    }
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel(tr("کیفیت", "Quality"))
                        PillSelector(
                            options = Resolution.entries,
                            selected = state.resolution,
                            label = { localizedNumber(if (it == Resolution.Uhd4k) "4K" else "${it.shortEdge}p") },
                            onSelect = onResolution,
                        )
                    }
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel(tr("نرخ فریم", "Frame rate"))
                        PillSelector(options = listOf(24, 30, 60), selected = state.frameRate, label = { localizedNumber("$it fps") }, onSelect = onFrameRate)
                    }
                    Text(
                        tr(
                            "ویدیوهای HDR خودکار به SDR تبدیل می\u200Cشوند تا در همه\u0654 شبکه\u200Cها درست دیده شوند. صدا AAC ۴۸ کیلوهرتز با بلندی استاندارد.",
                            "HDR footage is tone-mapped to SDR so it looks right everywhere. Audio is 48 kHz AAC at standard loudness.",
                        ),
                        style = Trimio.type.caption,
                        color = colors.textTertiary,
                    )
                    state.error?.let { Text(it, style = Trimio.type.label, color = colors.danger) }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
                        SectionLabel(tr("ادامه در نرم\u200Cافزار حرفه\u200Cای", "Continue in a pro editor"))
                        Row(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
                            TrimioButton("Premiere", { onShareForEditor(true) }, Modifier.weight(1f), kind = ButtonKind.Glass)
                            TrimioButton("DaVinci / FCP", { onShareForEditor(false) }, Modifier.weight(1f), kind = ButtonKind.Glass)
                        }
                        Text(
                            tr("کات\u200Cها و زیرنویس (SRT) منتقل می\u200Cشوند؛ فایل اصلی را در برنامه\u0654 مقصد دوباره پیوند بده.", "Cuts and captions (SRT) travel; relink the original file in the other app."),
                            style = Trimio.type.caption, color = colors.textTertiary,
                        )
                    }
                }
            }
            if (!state.running && state.status != JobStatus.Completed) {
                TrimioButton(
                    tr("ساخت فایل نهایی", "Export video"),
                    onExport,
                    Modifier.fillMaxWidth().padding(TrimioSpacing.screenGutter),
                    leading = { TrimioIcon(TrimioIcons.Download, null, tint = colors.onPrimary) },
                )
            }
        }
    }
}

@Composable
private fun PresetGrid(selected: ExportPreset, onSelect: (ExportPreset) -> Unit) {
    val colors = Trimio.colors
    Column(verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
        ExportPreset.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
                row.forEach { preset ->
                    val isSelected = preset == selected
                    val shape = RoundedCornerShape(TrimioRadius.md)
                    Row(
                        Modifier.weight(1f).pressable { onSelect(preset) }.clip(shape)
                            .background(if (isSelected) colors.primary.copy(alpha = 0.22f) else colors.glassFill)
                            .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) colors.accentCyan else colors.glassStroke, shape)
                            .padding(TrimioSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.md),
                    ) {
                        // A tiny frame of the exact aspect ratio.
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.aspectRatio(preset.aspect.ratio, matchHeightConstraintsFirst = preset.aspect.isPortrait)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Brush.linearGradient(listOf(colors.primary, colors.accentMagenta))),
                            )
                        }
                        Column {
                            Text(tr(preset.fa, preset.en), style = Trimio.type.label, color = colors.textPrimary)
                            Text(localizedNumber("${preset.aspect.w}:${preset.aspect.h}"), style = Trimio.type.caption, color = colors.textSecondary)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
