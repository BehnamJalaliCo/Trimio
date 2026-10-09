package io.trimio.feature.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.designsystem.component.AuroraBackground
import io.trimio.core.designsystem.component.ButtonKind
import io.trimio.core.designsystem.component.GlassScene
import io.trimio.core.designsystem.component.TrimioButton
import io.trimio.core.designsystem.component.filmGrain
import io.trimio.core.designsystem.component.FilmStrip
import io.trimio.core.designsystem.component.GlassChip
import io.trimio.core.designsystem.component.GlassPanel
import io.trimio.core.designsystem.component.LiquidProgressOrb
import io.trimio.core.designsystem.component.RingSegment
import io.trimio.core.designsystem.component.StepItem
import io.trimio.core.designsystem.component.StepRail
import io.trimio.core.designsystem.component.StepState
import io.trimio.core.designsystem.component.StreamWord
import io.trimio.core.designsystem.component.WaveformStrip
import io.trimio.core.designsystem.component.WordStream
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.style.DesignStyle
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.core.pipeline.JobStatus
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StageStatus

@Composable
fun BuildStreamRoute(viewModel: BuildStreamViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val style = viewModel.job.styleId?.let(DesignStyle::fromId)
    BuildStreamScreen(
        state = state,
        styleName = style?.let { tr(it.nameFa, it.nameEn) },
        onDevice = viewModel.job.director == DirectorBackend.OnDevice,
        onCancel = viewModel::cancel,
        onRestart = viewModel::start,
    )
}

/**
 * The live build screen: the user watches their edit being made, stage by stage,
 * from 0 to 100%. Stateless; driven entirely by [BuildStreamState].
 */
@Composable
fun BuildStreamScreen(
    state: BuildStreamState,
    styleName: String?,
    onDevice: Boolean,
    onCancel: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Trimio.colors
    GlassScene(
        modifier = modifier.fillMaxSize(),
        background = { AuroraBackground(Modifier.fillMaxSize().filmGrain(), energy = { state.energy }) },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TrimioSpacing.screenGutter, vertical = TrimioSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TrimioSpacing.lg),
        ) {
            Header(state, styleName, onDevice)

            LiquidProgressOrb(
                progress = state.overall,
                segments = state.stages.map {
                    RingSegment(
                        weight = it.id.weight.toFloat(),
                        fill = if (it.isFinished) 1f else it.fraction,
                        active = it.status == StageStatus.Running,
                        failed = it.status == StageStatus.Failed,
                    )
                },
                label = currentLabel(state),
            )

            EtaLine(state)

            LivePanel(state)

            GlassPanel(Modifier.fillMaxWidth()) {
                StepRail(
                    state.stages.map {
                        StepItem(
                            title = tr(it.id.titleFa, it.id.titleEn),
                            state = when (it.status) {
                                StageStatus.Pending -> StepState.Pending
                                StageStatus.Running -> StepState.Active
                                StageStatus.Done, StageStatus.Restored -> StepState.Done
                                StageStatus.Failed -> StepState.Failed
                            },
                            fraction = it.fraction,
                        )
                    },
                )
            }

            TrimioButton(
                text = when {
                    state.status == JobStatus.Completed -> tr("ساخت دوباره", "Build again")
                    state.status == JobStatus.Failed -> tr("تلاش دوباره", "Try again")
                    else -> tr("توقف", "Stop")
                },
                kind = if (state.isFinished) ButtonKind.Primary else ButtonKind.Glass,
                onClick = if (state.isFinished) onRestart else onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Header(state: BuildStreamState, styleName: String?, onDevice: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
        Text(
            text = when (state.status) {
                JobStatus.Completed -> tr("ویدیوی شما آماده است", "Your video is ready")
                JobStatus.Failed -> tr("ساخت متوقف شد", "Build stopped")
                else -> tr("در حال ساخت ویدیو", "Building your video")
            },
            style = Trimio.type.headline,
            color = Trimio.colors.textPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
            styleName?.let { GlassChip(it, accent = Trimio.colors.accentCyan) }
            GlassChip(if (onDevice) tr("پردازش روی گوشی", "On-device") else tr("کارگردان ابری", "Cloud director"))
        }
    }
}

@Composable
private fun currentLabel(state: BuildStreamState): String = when (state.status) {
    JobStatus.Completed -> tr("تمام شد", "Done")
    JobStatus.Failed -> tr("خطا", "Error")
    else -> state.current?.let { tr(it.titleFa, it.titleEn) } ?: tr("آماده\u200Cسازی", "Preparing")
}

@Composable
private fun EtaLine(state: BuildStreamState) {
    val text = when {
        state.status == JobStatus.Failed -> state.error ?: tr("خطای ناشناخته", "Unknown error")
        state.status == JobStatus.Completed -> tr("خروجی ذخیره شد", "Export saved")
        state.etaMs != null -> {
            val seconds = (state.etaMs / 1000).coerceAtLeast(1)
            val formatted = if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "$seconds"
            tr("حدود ${localizedNumber(formatted)} ${if (seconds >= 60) "دقیقه" else "ثانیه"} باقی مانده", "About $formatted ${if (seconds >= 60) "min" else "s"} left")
        }
        else -> tr("در حال تخمین زمان…", "Estimating time…")
    }
    Text(text, style = Trimio.type.label, color = if (state.status == JobStatus.Failed) Trimio.colors.danger else Trimio.colors.textSecondary)
}

/** Shows what the current stage is producing: waveform, words, plan, assets, frames. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LivePanel(state: BuildStreamState) {
    val colors = Trimio.colors
    val stage = state.current
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(TrimioSpacing.md)) {
            SectionTitle(tr("زنده", "Live"), live = !state.isFinished)

            state.note?.let { Text(tr(it.fa, it.en), style = Trimio.type.caption, color = colors.textSecondary) }

            AnimatedVisibility(stage == StageId.AudioCleanup || (stage == null && state.waveform.isNotEmpty() && !state.isFinished)) {
                WaveformStrip(state.waveform)
            }

            AnimatedVisibility(state.words.isNotEmpty(), enter = fadeIn() + expandVertically()) {
                val latest = state.words.lastOrNull()?.index
                WordStream(state.words.map { StreamWord(it.index, it.text, it.emphasis, isLatest = it.index == latest && stage == StageId.Transcription) })
            }

            AnimatedVisibility(state.plan.isNotEmpty(), enter = fadeIn() + expandVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(TrimioSpacing.xs)) {
                    SectionTitle(tr("نقشه\u0654 کارگردان", "Director's plan"))
                    state.plan.forEachIndexed { i, line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(localizedNumber("${i + 1}."), style = Trimio.type.numeric, color = colors.accentCyan)
                            Spacer(Modifier.size(TrimioSpacing.sm))
                            Text(tr(line.fa, line.en), style = Trimio.type.body, color = colors.textPrimary)
                        }
                    }
                }
            }

            AnimatedVisibility(state.assets.isNotEmpty(), enter = fadeIn() + expandVertically()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.xs), verticalArrangement = Arrangement.spacedBy(TrimioSpacing.xs)) {
                    state.assets.forEach { GlassChip(tr(it.fa, it.en), accent = colors.accentMagenta) }
                }
            }

            AnimatedVisibility(state.framesTotal > 0, enter = fadeIn() + expandVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(TrimioSpacing.xs)) {
                    FilmStrip(state.framesRendered, state.framesTotal)
                    Text(
                        tr(
                            "فریم ${localizedNumber(state.framesRendered.toString())} از ${localizedNumber(state.framesTotal.toString())}",
                            "Frame ${state.framesRendered} of ${state.framesTotal}",
                        ),
                        style = Trimio.type.caption,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, live: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
        if (live) Box(Modifier.size(8.dp).clip(CircleShape).background(Trimio.colors.danger))
        Text(text, style = Trimio.type.label, color = Trimio.colors.textSecondary)
    }
}
