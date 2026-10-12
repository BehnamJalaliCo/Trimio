package io.trimio.feature.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigHeroTitle
import io.trimio.core.designsystem.signature.SigRoundButton
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SigSecondaryButton
import io.trimio.core.designsystem.signature.SigSectionLabel
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigTextButton
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** One labelled value ("موتور" → "vulkan · RTX 3050 Ti · 33/33 layers"). */
data class TechFact(val label: String, val value: String)

/** A stage or sub-step with its time (null while it runs) and its latest sub-progress. */
data class TechStage(val name: String, val seconds: Float?, val detail: String? = null)

/** One sitting of the jury: its lowest and mean score and what the judges found. */
data class TechSitting(val index: Int, val min: Float, val mean: Float, val findings: List<String>)

/**
 * A run's technical report as «جزئیات فنی» shows it: what ran where (device, models, the native
 * build, GPU layers, threads), every stage's time, the speeds (model tokens per second, whisper's
 * real-time factor, render frames per second, memory), each sitting of the jury, and what went
 * wrong (process deaths, stalls, errors). [text] is the whole report, copied or shared as
 * [fileName]. Built by the app from the engine's report, so the screen stays a pure function.
 */
data class TechnicalDetails(
    val runId: String,
    /** "در حال ساخت · تلاش ۲", "failed · attempt 1". */
    val status: String,
    val running: Boolean,
    val summary: List<TechFact>,
    val stages: List<TechStage>,
    val metrics: List<TechFact>,
    val jury: List<TechSitting>,
    val problems: List<String>,
    val text: String,
    val fileName: String,
)

/**
 * «جزئیات فنی»: a run's technical report, live while it builds and kept with the project after.
 * Copy puts the whole report on the clipboard; share hands it to other apps as a text file.
 */
@Composable
fun TechnicalDetailsScreen(details: TechnicalDetails, onShare: () -> Unit, onClose: () -> Unit) {
    SigScreen {
        SigAdaptive { width ->
            if (width.wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = SigSpace.xl), horizontalArrangement = Arrangement.spacedBy(SigSpace.xxl)) {
                    Column(Modifier.weight(0.9f).fillMaxHeight()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Top(details, onClose, Modifier)
                            Facts(details)
                        }
                        Actions(details, onShare, Modifier.padding(vertical = 12.dp))
                    }
                    Column(Modifier.weight(1.1f).widthIn(max = 680.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 58.dp, bottom = 24.dp)) {
                        Timeline(details)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Top(details, onClose, Modifier.gutter())
                        Column(Modifier.gutter()) {
                            Facts(details)
                            Timeline(details)
                        }
                    }
                    Actions(details, onShare, Modifier.gutter().padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Top(details: TechnicalDetails, onClose: () -> Unit, modifier: Modifier) {
    val c = Sig.colors
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SigRoundButton(TrimioIcons.Close, tr("بستن", "Close"), onClose, overMedia = false)
            MicroLabel("TECHNICAL · ${details.runId.uppercase().take(RUN_ID)}")
        }
        SigHeroTitle(tr("جزئیات فنی", "Technical details"), size = 40)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SigChip(
                details.status,
                container = if (details.running) c.emphasisSoft else c.ink.copy(alpha = 0.08f),
                content = if (details.running) c.emphasisSoftContent else c.ink,
            )
            if (details.problems.isNotEmpty()) {
                SigChip(localizedNumber(tr("${details.problems.size} مورد", "${details.problems.size} issues")), container = c.action.copy(alpha = 0.16f), content = c.action)
            }
        }
    }
}

/** The headline facts, the problems and the speeds. */
@Composable
private fun Facts(details: TechnicalDetails) {
    Column(Modifier.padding(top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (details.problems.isNotEmpty()) Problems(details.problems)
        FactCard(details.summary)
        if (details.metrics.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SigSectionLabel(tr("سرعت و حافظه", "Speed and memory"))
                FactCard(details.metrics)
            }
        }
    }
}

@Composable
private fun FactCard(facts: List<TechFact>) {
    val c = Sig.colors
    SigGlass(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        Column {
            facts.forEachIndexed { i, f ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(f.label, style = Sig.type.meta, color = c.muted, modifier = Modifier.width(96.dp))
                    Text(f.value, style = Sig.type.body.copy(fontSize = 13.5.sp, lineHeight = 20.sp, textDirection = TextDirection.Content), color = c.ink, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Deaths, stalls and errors first: they are why the report is read. */
@Composable
private fun Problems(problems: List<String>) {
    val c = Sig.colors
    val shape = RoundedCornerShape(SigSpace.radiusMd)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.action.copy(alpha = 0.08f)).border(1.dp, c.action.copy(alpha = 0.45f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(tr("چه چیزی درست پیش نرفت", "What went wrong"), style = Sig.type.bodyStrong, color = c.action)
        problems.take(MAX_PROBLEMS).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape).background(c.action))
                Text(line, style = Sig.type.meta.copy(fontSize = 12.sp, lineHeight = 18.sp, textDirection = TextDirection.Content), color = c.ink)
            }
        }
    }
}

/** Stage times as bars against the longest, the jury's sittings, and the report's text. */
@Composable
private fun Timeline(details: TechnicalDetails) {
    Column(Modifier.padding(top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (details.stages.isNotEmpty()) Stages(details.stages)
        if (details.jury.isNotEmpty()) Sittings(details.jury)
        ReportText(details.text)
    }
}

@Composable
private fun Stages(stages: List<TechStage>) {
    val c = Sig.colors
    val total = stages.sumOf { (it.seconds ?: 0f).toDouble() }.toFloat()
    val longest = stages.maxOf { it.seconds ?: 0f }.coerceAtLeast(1f)
    Column {
        SigSectionLabel(tr("مراحل", "Stages"), trailing = duration(total))
        stages.forEach { s ->
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.name, style = Sig.type.body.copy(fontSize = 13.sp), color = if (s.seconds == null) c.ink else c.muted, maxLines = 1, modifier = Modifier.width(118.dp))
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.06f))) {
                    val share = ((s.seconds ?: longest) / longest).coerceIn(0.02f, 1f)
                    Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(CircleShape).background(if (s.seconds == null) c.live else c.action))
                }
                MicroLabel(s.seconds?.let(::duration) ?: "RUNNING", color = if (s.seconds == null) c.action else c.muted, modifier = Modifier.widthIn(min = 56.dp))
            }
            s.detail?.let { Text(it, style = Sig.type.meta.copy(fontSize = 10.5.sp), color = c.faint, modifier = Modifier.padding(bottom = 4.dp)) }
        }
    }
}

@Composable
private fun Sittings(jury: List<TechSitting>) {
    val c = Sig.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SigSectionLabel(tr("نشست‌های هیئت داوران", "Jury sittings"), trailing = localizedNumber("${jury.size}"))
        jury.forEach { j ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MicroLabel("#${j.index}", color = c.faint)
                    Text(
                        localizedNumber(tr("کمینه ${one(j.min)} · میانگین ${one(j.mean)}", "min ${one(j.min)} · mean ${one(j.mean)}")),
                        style = Sig.type.body.copy(fontSize = 13.sp, fontWeight = FontWeight(600)), color = if (j.min >= PERFECT) c.ink else c.action,
                    )
                    Text(localizedNumber(tr("${j.findings.size} ایراد", "${j.findings.size} findings")), style = Sig.type.meta, color = c.muted)
                }
                j.findings.take(2).forEach { Text(it, style = Sig.type.meta.copy(fontSize = 11.sp, textDirection = TextDirection.Content), color = c.muted, maxLines = 2) }
            }
        }
    }
}

/** The report itself, left to right as it is written, scrollable sideways; a page at a time until opened in full. */
@Composable
private fun ReportText(text: String) {
    val c = Sig.colors
    var full by remember { mutableStateOf(false) }
    val lines = text.lines()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SigSectionLabel(tr("متن گزارش", "Report text"), trailing = localizedNumber(tr("${lines.size} خط", "${lines.size} lines")))
        val shape = RoundedCornerShape(SigSpace.radiusMd)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                Text(
                    if (full || lines.size <= PAGE) text else lines.take(PAGE).joinToString("\n") + "\n…",
                    style = Sig.type.meta.copy(fontSize = 10.5.sp, lineHeight = 15.sp, textDirection = TextDirection.Ltr), color = c.ink, softWrap = false,
                )
            }
        }
        if (lines.size > PAGE) SigTextButton(if (full) tr("کوتاه‌تر", "Show less") else tr("نمایش کامل", "Show all"), { full = !full })
    }
}

@Composable
private fun Actions(details: TechnicalDetails, onShare: () -> Unit, modifier: Modifier) {
    @Suppress("DEPRECATION") // The common clipboard API that takes plain text on every platform.
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SigSecondaryButton(
            if (copied) tr("کپی شد", "Copied") else tr("کپی", "Copy"),
            {
                clipboard.setText(AnnotatedString(details.text))
                copied = true
            },
            Modifier.weight(1f), icon = if (copied) TrimioIcons.Check else TrimioIcons.Layers,
        )
        SigButton(tr("ارسال فایل گزارش", "Share report file"), onShare, Modifier.weight(1.6f), trailing = TrimioIcons.Share)
    }
}

private fun duration(seconds: Float): String {
    val s = seconds.roundToInt()
    return when {
        seconds < 10f -> "${(seconds * 10).roundToInt() / 10f}s"
        s < 60 -> "${s}s"
        else -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
    }
}

private fun one(v: Float): String {
    val r = (v * 10).roundToInt()
    return if (r % 10 == 0) "${r / 10}" else "${r / 10}.${r % 10}"
}

private const val RUN_ID = 18
private const val MAX_PROBLEMS = 6
private const val PAGE = 40
private const val COPIED_MS = 1_800L
private const val PERFECT = 9.95f
