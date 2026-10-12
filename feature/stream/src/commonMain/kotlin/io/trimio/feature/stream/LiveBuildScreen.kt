package io.trimio.feature.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.DisplayText
import io.trimio.core.designsystem.signature.FootagePlaceholder
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.LiveDot
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigTextButton
import io.trimio.core.designsystem.signature.emphasised
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Board 2, third screen: the build as it happens. A giant percentage beside the newest frame of
 * the edit forming, the tick bar, the director's note in its own voice, the six named stages, the
 * ten judges lighting up as each gives 10, and the words heard so far. Cancel is always one tap.
 */
@Composable
fun LiveBuildScreen(build: LiveBuild, onCancel: () -> Unit) {
    SigScreen {
        SigAdaptive { width ->
            if (width.wide) {
                Row(Modifier.fillMaxSize().padding(SigSpace.xl), horizontalArrangement = Arrangement.spacedBy(SigSpace.xxl)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SigSpace.lg)) {
                        FormingFrame(build.previews, Modifier.weight(1f).aspectRatio(9f / 16f), radius = SigSpace.radiusLg)
                        FrameStrip(build.previews)
                    }
                    Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        BuildTop(build, onCancel, Modifier)
                        Readout(build, showFrame = false)
                        Details(build)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BuildTop(build, onCancel, Modifier.gutter())
                    Column(Modifier.gutter()) {
                        Readout(build, showFrame = true)
                        Details(build)
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildTop(build: LiveBuild, onCancel: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(tr("در حال ساخت", "Building"), build.styleName).joinToString(" · "),
            style = Sig.type.meta, color = Sig.colors.muted, modifier = Modifier.weight(1f),
        )
        SigTextButton(tr("لغو", "Cancel"), onCancel)
    }
}

/** Percentage, the forming frame, the tick bar and the frame/time line. */
@Composable
private fun Readout(build: LiveBuild, showFrame: Boolean) {
    val c = Sig.colors
    val pct by animateFloatAsState(build.progress.coerceIn(0f, 1f) * 100f, Trimio.motion.effects())
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Text("%", style = Sig.type.figure.copy(fontSize = 22.sp), color = c.action, modifier = Modifier.padding(top = 18.dp))
        DisplayText(localizedNumber("${pct.toInt()}"), style = Sig.type.giant.copy(fontSize = 128.sp, lineHeight = 120.sp), modifier = Modifier.weight(1f))
        if (showFrame) FormingFrame(build.previews, Modifier.size(92.dp, 150.dp))
    }
    TickBar(build.progress, Modifier.padding(top = 18.dp))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        val frames = when {
            build.framesTotal > 0 -> tr("${build.framesDone} از ${build.framesTotal} فریم", "${build.framesDone} of ${build.framesTotal} frames")
            build.linesTotal > 0 -> tr("خط ${build.linesDone} از ${build.linesTotal}", "line ${build.linesDone} of ${build.linesTotal}")
            else -> stageName(build.stage)
        }
        Text(localizedNumber(frames), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted, modifier = Modifier.weight(1f))
        build.etaSeconds?.let { Text(localizedNumber(tr("حدود $it ثانیه", "about $it s")), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted) }
    }
}

/** The newest preview of the edit, with the earlier ones fanned behind it: the edit is forming. */
@Composable
private fun FormingFrame(previews: List<ImageBitmap>, modifier: Modifier, radius: Dp = 14.dp) {
    val shape = RoundedCornerShape(radius)
    Box(modifier) {
        previews.dropLast(1).takeLast(2).forEachIndexed { i, frame ->
            val back = 2 - i
            Image(
                frame, null, Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = 3f * back; translationX = 6.dp.toPx() * back; alpha = 0.35f; scaleX = 0.94f; scaleY = 0.94f
                }.clip(shape),
                contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.fillMaxSize().clip(shape).border(1.dp, Sig.colors.lineStrong, shape)) {
            val newest = previews.lastOrNull()
            if (newest != null) Image(newest, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else FootagePlaceholder(Modifier.fillMaxSize())
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveDot(size = 5.dp)
                MicroLabel("LIVE", color = SigNoir.emphasis)
            }
        }
    }
}

/** Desktop: every preview so far, oldest first, as a film strip under the stage. */
@Composable
private fun FrameStrip(previews: List<ImageBitmap>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(6) { i ->
            val frame = previews.getOrNull(i)
            val shape = RoundedCornerShape(8.dp)
            Box(Modifier.size(54.dp, 96.dp).clip(shape).background(Sig.colors.surface).border(1.dp, Sig.colors.line, shape)) {
                if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

/** Forty ticks: done in ember, the current one live, the rest waiting. Fills left to right, as on the board (a meter, not text). */
@Composable
private fun TickBar(progress: Float, modifier: Modifier = Modifier, ticks: Int = 40) {
    val c = Sig.colors
    val done = (progress.coerceIn(0f, 1f) * ticks).toInt()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Ticks(c, done, progress, ticks, modifier) }
}

@Composable
private fun Ticks(c: io.trimio.core.designsystem.signature.SigColors, done: Int, progress: Float, ticks: Int, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(ticks) { i ->
            val color = when {
                i < done -> c.action
                i == done && progress < 1f -> c.live
                else -> c.ink.copy(alpha = 0.12f)
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(1.5.dp)).background(color))
        }
    }
}

@Composable
private fun Details(build: LiveBuild) {
    Column(Modifier.padding(top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        build.directorNote?.let { DirectorNote(build.stage, it, build.noteEmphasis) }
        StageList(build)
        JuryRow(build.judges, active = build.stage == BuildStage.Jury)
        if (build.transcript.isNotBlank()) {
            Text(
                emphasised(build.transcript, *build.transcriptEmphasis.toTypedArray()),
                style = Sig.type.body.copy(fontSize = 13.5.sp, lineHeight = 24.sp), color = Sig.colors.muted, maxLines = 3,
            )
        }
    }
}

@Composable
private fun DirectorNote(stage: BuildStage, note: String, emphasis: List<String>) {
    SigGlass(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DirectorDot(size = 12.dp)
                Text(
                    tr("کارگردان · ${stage.fa}", "Director · ${stage.en}"),
                    style = Sig.type.meta.copy(fontWeight = FontWeight(700), brush = Sig.colors.directorInkBrush),
                )
            }
            Text(emphasised(note, *emphasis.toTypedArray()), style = Sig.type.body.copy(lineHeight = 25.sp), color = Sig.colors.ink)
        }
    }
}

/** The six stages: done ones with their time, the running one marked, the rest faint. */
@Composable
private fun StageList(build: LiveBuild) {
    val c = Sig.colors
    Column {
        BuildStage.entries.forEach { stage ->
            val state = stage.ordinal.compareTo(build.stage.ordinal)
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                MicroLabel("0${stage.ordinal + 1}", color = if (state == 0) c.action else c.faint, modifier = Modifier.width(26.dp))
                Text(
                    stageName(stage),
                    style = if (state == 0) Sig.type.bodyStrong else Sig.type.body,
                    color = when { state < 0 -> c.muted; state == 0 -> c.ink; else -> c.faint },
                    modifier = Modifier.weight(1f),
                )
                when {
                    state == 0 && build.progress < 1f -> MicroLabel("RUNNING", color = c.action)
                    state < 0 || build.progress >= 1f -> MicroLabel(build.stageSeconds[stage]?.let { "${(it * 10).roundToInt() / 10f}s" } ?: "DONE", color = c.muted)
                }
            }
            Hairline()
        }
    }
}

/**
 * The ten judges. When the panel speaks, they light one after another (a beat each); a 10 lights
 * in the live colour with the score in it, anything less stays an ember ring with its score.
 */
@Composable
private fun JuryRow(judges: List<JudgeScore>, active: Boolean) {
    val c = Sig.colors
    val scored = judges.count { it.score != null }
    var lit by remember { mutableIntStateOf(0) }
    val reduced = Trimio.motion.isReduced
    LaunchedEffect(scored) {
        if (reduced) lit = scored
        while (lit < scored) { delay(JUDGE_BEAT_MS); lit++ }
        if (lit > scored) lit = scored
    }
    val perfect = judges.take(lit).count { it.perfect }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("هیئت داوران", "The jury"), style = Sig.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
            Text(
                when {
                    scored == 0 && active -> tr("در حال داوری…", "Judging…")
                    scored == 0 -> tr("بعد از برنامهٔ ادیت", "After the plan")
                    else -> localizedNumber(tr("$perfect از ۱۰ داور ۱۰ دادند", "$perfect of 10 gave 10"))
                },
                style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            judges.forEachIndexed { i, judge -> JudgeBead(judge.takeIf { i < lit }, pending = active && i == lit) }
        }
    }
}

@Composable
internal fun JudgeBead(judge: JudgeScore?, pending: Boolean = false, size: Dp = 27.dp) {
    val c = Sig.colors
    val score = judge?.score
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (judge?.perfect == true) c.live else c.ink.copy(alpha = 0.05f))
            .border(1.dp, when { judge?.perfect == true -> c.live; score != null -> c.action; pending -> c.ink.copy(alpha = 0.5f); else -> c.line }, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (score != null) {
            Text(
                localizedNumber("${score.roundToInt()}"),
                style = Sig.type.meta.copy(fontSize = 10.sp, fontWeight = FontWeight(800)),
                color = if (judge.perfect) c.canvas else c.action,
            )
        }
    }
}

@Composable
private fun stageName(stage: BuildStage) = tr(stage.fa, stage.en)

private const val JUDGE_BEAT_MS = 110L

