package io.trimio.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.FootagePlaceholder
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SigSecondaryButton
import io.trimio.core.designsystem.signature.SigSegmented
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigStateBlock
import io.trimio.core.designsystem.signature.SigTextButton
import io.trimio.core.designsystem.signature.SigTopRow
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr

/** What the export screen can ask for. */
data class ExportActions(
    val onBack: () -> Unit = {},
    val onDestination: (ExportDestination) -> Unit = {},
    val onQuality: (ExportQuality) -> Unit = {},
    val onFps: (Int) -> Unit = {},
    val onExport: () -> Unit = {},
    val onProject: () -> Unit = {},
    val onCaptions: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onShare: () -> Unit = {},
)

@Composable
fun ExportRoute(viewModel: ExportViewModel, onBack: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    ExportScreen(
        ui,
        ExportActions(
            onBack = onBack, onDestination = viewModel::setDestination, onQuality = viewModel::setQuality, onFps = viewModel::setFps,
            onExport = viewModel::export, onProject = viewModel::exportProject, onCaptions = viewModel::exportCaptions,
            onCancel = viewModel::cancel, onSave = viewModel::save, onShare = viewModel::share,
        ),
    )
}

/**
 * Board 4, first screen: the finished edit (poster, title, scenes · style · length, the jury's
 * verdict), where it goes (each destination is a frame the motion engine lays the edit out for),
 * its quality with an honest size and time, its frame rate, the hand-offs to professional editors
 * and captions, and one ember action that becomes the run's progress, then save and share.
 */
@Composable
fun ExportScreen(ui: ExportUi, actions: ExportActions) {
    SigScreen {
        SigAdaptive { width ->
            when {
                ui.missing -> Missing(actions)
                width.wide -> Row(Modifier.fillMaxSize().padding(horizontal = SigSpace.xl), horizontalArrangement = Arrangement.spacedBy(SigSpace.xxl)) {
                    Column(Modifier.weight(0.8f).fillMaxHeight()) {
                        SigTopRow("EXPORT", actions.onBack, backLabel = tr("بازگشت", "Back"))
                        Stage(ui, Modifier.weight(1f).fillMaxWidth().padding(vertical = SigSpace.lg))
                    }
                    Column(Modifier.weight(1.2f).widthIn(max = 620.dp).fillMaxHeight().padding(top = 58.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Summary(ui, Modifier.padding(top = 4.dp))
                            Choices(ui, actions)
                        }
                        Bottom(ui, actions, Modifier.padding(vertical = SigSpace.lg))
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        SigTopRow("EXPORT", actions.onBack, backLabel = tr("بازگشت", "Back"))
                        Column(Modifier.gutter()) {
                            Summary(ui, Modifier.padding(top = 16.dp), poster = true)
                            Choices(ui, actions)
                        }
                    }
                    Bottom(ui, actions, Modifier.gutter().padding(top = 12.dp, bottom = 26.dp))
                }
            }
        }
    }
}

/** Wide windows: the edit's poster as a stage, framed as the chosen destination's shape is not (it is the edit as made). */
@Composable
private fun Stage(ui: ExportUi, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(SigSpace.radiusLg)
        Box(Modifier.fillMaxHeight().aspectRatio(9f / 16f).clip(shape).border(1.dp, Sig.colors.lineStrong, shape)) {
            Poster(ui, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Poster(ui: ExportUi, modifier: Modifier) {
    val p = ui.poster
    if (p != null) Image(p, ui.title, modifier, contentScale = ContentScale.Crop) else FootagePlaceholder(modifier)
}

/** The edit: poster, title, «۰۰:۳۶ · نوآر · ۷ صحنه», and the jury's verdict. */
@Composable
private fun Summary(ui: ExportUi, modifier: Modifier, poster: Boolean = false) {
    val c = Sig.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (poster) Poster(ui, Modifier.size(72.dp, 128.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f)) {
            Text(ui.title, style = Sig.type.title.copy(fontSize = 20.sp), color = c.ink, maxLines = 2)
            Text(
                localizedNumber(listOfNotNull(clock(ui.durationMs), ui.styleName, tr("${ui.scenes} صحنه", "${ui.scenes} scenes")).joinToString(" · ")),
                style = Sig.type.meta, color = c.muted, modifier = Modifier.padding(top = 4.dp), maxLines = 1,
            )
            Verdict(ui.verdict, Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun Verdict(verdict: ExportVerdict, modifier: Modifier) {
    val c = Sig.colors
    when (verdict) {
        ExportVerdict.Approved -> SigChip(
            tr("کیفیت تأیید شد", "Quality approved"), modifier, container = c.emphasisSoft, content = c.emphasisSoftContent,
            leading = { TrimioIcon(TrimioIcons.Check, null, tint = c.emphasisSoftContent, size = 13.dp) },
        )
        is ExportVerdict.Best -> SigChip(localizedNumber(tr("بهترین نسخه از نگاه داورها · ${score(verdict.min)}", "The judges' best cut · ${score(verdict.min)}")), modifier)
        ExportVerdict.None -> Unit
    }
}

/** Destination, quality (with the size and time it costs) and frame rate, then the hand-offs. */
@Composable
private fun Choices(ui: ExportUi, actions: ExportActions) {
    val c = Sig.colors
    val locked = ui.busy
    Column {
        Label(tr("مقصد", "Destination"), Modifier.padding(top = 20.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportDestination.entries.forEach { d ->
                DestinationTile(d, d == ui.destination, enabled = !locked, Modifier.weight(1f)) { actions.onDestination(d) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Label(tr("کیفیت", "Quality"))
            Text(
                "⁦${ExportEstimate.label(ui.estimate)}⁩",
                style = Sig.type.meta.copy(fontFamily = Trimio.type.families.accent, fontWeight = FontWeight(600), fontSize = 11.5.sp, textDirection = TextDirection.Ltr),
                color = c.ink,
            )
        }
        SigSegmented(
            ExportQuality.entries, ui.quality, { it.label }, { if (!locked) actions.onQuality(it) }, Modifier.padding(top = 8.dp),
            enabled = { q -> (q to ui.fps) !in ui.unsupported }, height = 44.dp,
        )
        Label(tr("نرخ فریم", "Frame rate"), Modifier.padding(top = 14.dp))
        SigSegmented(
            ExportUi.Rates, ui.fps, { localizedNumber("$it") }, { if (!locked) actions.onFps(it) }, Modifier.padding(top = 8.dp),
            enabled = { r -> (ui.quality to r) !in ui.unsupported }, height = 44.dp,
        )
        Column(Modifier.padding(top = 18.dp, bottom = 12.dp)) {
            Hairline(strong = true)
            HandOff(TrimioIcons.Film, tr("پروژه برای ادیتور حرفه‌ای", "Project for a pro editor"), "Premiere · Final Cut · DaVinci · SRT", enabled = !locked, actions.onProject)
            HandOff(TrimioIcons.Text, tr("فقط زیرنویس (SRT)", "Captions only (SRT)"), tr("برای یوتیوب و پلیرها", "For YouTube and players"), enabled = !locked, actions.onCaptions, last = true)
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = Sig.type.meta, color = Sig.colors.muted)

/** A destination: its frame drawn to scale above its name; the chosen one is the ink slab. */
@Composable
private fun DestinationTile(d: ExportDestination, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Sig.colors
    val shape = RoundedCornerShape(14.dp)
    val ink = if (on) c.canvas else c.ink
    Column(
        modifier.height(86.dp).clip(shape).background(if (on) c.ink else Color.Transparent)
            .let { if (on) it else it.border(1.dp, c.lineStrong, shape) }
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val (w, h) = when (d.format) {
            "16:9" -> 32 to 18
            "1:1" -> 24 to 24
            else -> 16 to 28
        }
        Box(Modifier.size(w.dp, h.dp).border(2.dp, ink, RoundedCornerShape(3.dp)))
        Text(tr(d.fa, d.en), style = Sig.type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight(if (on) 800 else 700)), color = ink, maxLines = 1)
    }
}

/** A list row of the board: icon, title and a Latin or quiet line, the forward chevron. */
@Composable
private fun HandOff(icon: ImageVector, title: String, hint: String, enabled: Boolean, onClick: () -> Unit, last: Boolean = false) {
    val c = Sig.colors
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TrimioIcon(icon, null, tint = if (enabled) c.ink else c.faint, size = 22.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = Sig.type.bodyStrong.copy(fontSize = 14.sp), color = if (enabled) c.ink else c.faint)
                Text(hint, style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted, maxLines = 1)
            }
            TrimioIcon(TrimioIcons.ChevronForward, null, tint = c.muted, size = 18.dp)
        }
        if (!last) Hairline()
    }
}

/** The ember action, or the run in its place: progress, then save and share, or what went wrong. */
@Composable
private fun Bottom(ui: ExportUi, actions: ExportActions, modifier: Modifier) {
    val job = ui.job
    val quality = ui.quality.label
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            job == null -> Unit
            job.error != null -> Failed(job, actions)
            job.running -> Running(ui, job, actions)
            job.kind == ExportKind.Video -> Done(ui, job, actions)
            else -> FilesReady(job, actions)
        }
        val videoShown = job?.kind == ExportKind.Video && job.error == null
        if (!videoShown) {
            SigButton(
                tr("ساخت خروجی $quality", "Export $quality"), actions.onExport, Modifier.fillMaxWidth(),
                trailing = TrimioIcons.Export, enabled = job?.running != true,
            )
        }
    }
}

@Composable
private fun Running(ui: ExportUi, job: ExportJob, actions: ExportActions) {
    val c = Sig.colors
    val what = when (job.kind) {
        ExportKind.Video -> tr("ساخت خروجی ${ui.quality.label} · ${ui.destination.fa}", "Exporting ${ui.quality.label} · ${ui.destination.en}")
        ExportKind.Project -> tr("پروژه برای ادیتور حرفه‌ای", "Project for a pro editor")
        ExportKind.Captions -> tr("زیرنویس", "Captions")
    }
    SigGlass(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(what, style = Sig.type.bodyStrong.copy(fontSize = 14.sp), color = c.ink, maxLines = 1, modifier = Modifier.weight(1f))
                MicroLabel("${(job.progress * PERCENT).toInt()}%", color = c.action)
            }
            Bar(job.progress)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    localizedNumber(job.etaSeconds?.let { tr("حدود $it ثانیه مانده", "About $it s left") } ?: tr("فریم‌ها با کیفیت نهایی رندر می‌شوند", "Rendering every frame at full quality")),
                    style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted, modifier = Modifier.weight(1f), maxLines = 1,
                )
                SigTextButton(tr("لغو", "Cancel"), actions.onCancel)
            }
        }
    }
}

@Composable
private fun Bar(progress: Float) {
    val c = Sig.colors
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.1f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(c.action))
    }
}

@Composable
private fun Done(ui: ExportUi, job: ExportJob, actions: ExportActions) {
    val c = Sig.colors
    val name = job.files.firstOrNull()?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        SigChip(
            tr("آماده شد", "Ready"), container = c.emphasisSoft, content = c.emphasisSoftContent,
            leading = { TrimioIcon(TrimioIcons.Check, null, tint = c.emphasisSoftContent, size = 13.dp) },
        )
        MicroLabel(name.take(NAME), Modifier.weight(1f), color = c.faint)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val saveLabel = when {
            job.saved && ui.desktop -> tr("در پوشهٔ ویدیوها ذخیره شد", "Saved to Videos")
            job.saved -> tr("در گالری ذخیره شد", "Saved to gallery")
            ui.desktop -> tr("ذخیره در ویدیوها", "Save to Videos")
            else -> tr("ذخیره در گالری", "Save to gallery")
        }
        SigButton(saveLabel, actions.onSave, Modifier.weight(2f), trailing = if (job.saved) TrimioIcons.Check else TrimioIcons.Download, enabled = !job.saved)
        SigSecondaryButton(tr("اشتراک", "Share"), actions.onShare, Modifier.weight(1f), icon = TrimioIcons.Share)
    }
}

@Composable
private fun FilesReady(job: ExportJob, actions: ExportActions) {
    val c = Sig.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        SigChip(
            localizedNumber(tr("${job.files.size} فایل آماده است", "${job.files.size} files ready")), container = c.emphasisSoft, content = c.emphasisSoftContent,
            leading = { TrimioIcon(TrimioIcons.Check, null, tint = c.emphasisSoftContent, size = 13.dp) },
        )
        Spacer(Modifier.weight(1f))
        SigTextButton(tr("اشتراک دوباره", "Share again"), actions.onShare)
    }
}

@Composable
private fun Failed(job: ExportJob, actions: ExportActions) {
    val c = Sig.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.action))
            Text(tr("خروجی ساخته نشد؛ دوباره امتحان کن", "The export did not finish; try again"), style = Sig.type.bodyStrong.copy(fontSize = 14.sp), color = c.ink)
        }
        Text(job.error.orEmpty(), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted, maxLines = 2, modifier = Modifier.padding(start = 16.dp))
    }
}

/** The edit is no longer held (the app restarted since it was made). */
@Composable
private fun Missing(actions: ExportActions) {
    Column(Modifier.fillMaxSize()) {
        SigTopRow("EXPORT", actions.onBack, backLabel = tr("بازگشت", "Back"))
        SigStateBlock(
            TrimioIcons.Film, tr("این ویدیو باز نیست", "This edit is not open"),
            tr(
                "خروجی از ویدیوی آماده ساخته می‌شود. ویدیو را از خانه باز کن یا یک نسخهٔ تازه بساز؛ فایل قبلی در گالری می‌ماند.",
                "Exports are made from a finished edit. Open it from home or make a new version; the earlier file stays in your gallery.",
            ),
            Modifier.gutter().padding(top = 28.dp),
        )
    }
}

private fun clock(ms: Long): String {
    val s = (ms / MS).toInt()
    return "${(s / MINUTE).toString().padStart(2, '0')}:${(s % MINUTE).toString().padStart(2, '0')}"
}

private fun score(v: Float): String = ((v * TENTH).toInt() / TENTH).let { if (it % 1f == 0f) "${it.toInt()}" else "$it" }

private const val MS = 1000L
private const val MINUTE = 60
private const val PERCENT = 100
private const val NAME = 48
private const val TENTH = 10f
