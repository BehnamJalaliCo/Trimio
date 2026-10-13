package io.trimio.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.DirectorText
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigHeroTitle
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SigSectionLabel
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigTopRow
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.ModelFit
import io.trimio.engine.models.ModelState

@Composable
fun ModelsRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val states by viewModel.models.states.collectAsStateWithLifecycle()
    val device = viewModel.device
    val desktop = listOf("Android", "iOS", "Web").none { device.platform.startsWith(it) }
    // The engine as the latest build measured it (read once per visit: it changes only when a build ends).
    val engine = remember { viewModel.engine?.latest() }
    val hardware = HardwareLine(
        engine?.gpu, engine?.backend, engine?.gpuLayers, engine?.totalLayers, engine?.threads ?: viewModel.threads, measured = engine != null,
    )
    val ui = ModelShelf.build(
        viewModel.models.catalog, states, DeviceMemory(device.ramGb, desktop), settings.localModelId, settings.speechModelId,
        hardware = hardware, speeds = engine?.speeds.orEmpty(),
    )
    val byId = viewModel.models.catalog.associateBy { it.id }
    ModelManagerScreen(
        ui,
        ModelActions(
            onBack = onBack,
            onDownload = { id -> byId[id]?.let(viewModel.models::download) },
            onCancel = { id -> byId[id]?.let(viewModel.models::cancel) },
            onDelete = { id -> byId[id]?.let(viewModel.models::delete) },
            onUpdate = { id -> (states[id] as? ModelState.UpdateAvailable)?.let { viewModel.models.download(it.to) } },
            onUse = { id -> viewModel.update { it.copy(localModelId = id) } },
            onDownloadAll = { ids -> ids.mapNotNull { byId[it] }.forEach(viewModel.models::download) },
        ),
    )
}

/**
 * Board 4, fourth screen: the models on this device. What is installed and how much room it
 * takes, the director the device runs best (recommended), and each tier with its fit badge,
 * size, and download, update or delete.
 */
@Composable
fun ModelManagerScreen(ui: ModelsUi, actions: ModelActions) {
    SigScreen {
        SigAdaptive { width ->
            if (width.wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = SigSpace.xl), horizontalArrangement = Arrangement.spacedBy(SigSpace.xxl)) {
                    Column(Modifier.weight(0.85f).fillMaxHeight().verticalScroll(rememberScrollState())) { Header(ui, actions) }
                    Column(Modifier.weight(1.15f).widthIn(max = 640.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 58.dp)) { Shelves(ui, actions) }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Header(ui, actions)
                    Shelves(ui, actions)
                }
            }
        }
    }
}

@Composable
private fun Header(ui: ModelsUi, actions: ModelActions) {
    val c = Sig.colors
    SigTopRow(if (ui.desktop) "MODELS · ON DESKTOP" else "MODELS · ON DEVICE", actions.onBack, backLabel = tr("بازگشت", "Back"))
    Column(Modifier.gutter().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SigHeroTitle(tr("مدل‌ها", "Models"))
        Storage(ui)
        ui.hardware?.let { Hardware(it) }
        Recommended(ui, actions)
        if (ui.directors.none { it.installed }) {
            Text(
                tr("هنوز کارگردانی نصب نشده؛ تا آن موقع ساخت با قواعد و بدون مدل انجام می‌شود.", "No director installed yet; until then builds use the rules engine, no model."),
                style = Sig.type.meta, color = c.muted,
            )
        }
    }
}

/** Room taken: directors in ember, speech in the live colour, against free space when known. */
@Composable
private fun Storage(ui: ModelsUi) {
    val c = Sig.colors
    val directors = ui.directors.filter { it.installed }.sumOf { it.sizeBytes }
    val speech = ui.speech.filter { it.installed }.sumOf { it.sizeBytes }
    val total = (ui.freeBytes?.let { it + directors + speech } ?: (directors + speech)).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SigSectionLabel(
            tr("فضای اشغال‌شده", "Storage used"),
            trailing = "${gb(directors + speech)} USED" + (ui.freeBytes?.let { " · ${gb(it)} FREE" } ?: ""),
        )
        Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.08f))) {
            if (directors > 0) Box(Modifier.weight(directors.toFloat() / total).fillMaxHeight().background(c.action))
            if (speech > 0) Box(Modifier.weight(speech.toFloat() / total).fillMaxHeight().background(c.live))
            val rest = total - directors - speech
            if (rest > 0) Box(Modifier.weight(rest.toFloat() / total).fillMaxHeight())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(c.action, tr("کارگردان", "Director"))
            Legend(c.live, tr("گفتار", "Speech"))
            Text(localizedNumber(tr("این دستگاه · ${ui.ramGb} گیگ رم", "This device · ${ui.ramGb} GB RAM")), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted)
        }
    }
}

/** «موتور: …»: the GPU (its name as the driver gives it) and the layers it takes, or the CPU threads; measured by the latest build. */
@Composable
private fun Hardware(line: HardwareLine) {
    val c = Sig.colors
    val (device, detail) = engineLine(line)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        TrimioIcon(TrimioIcons.Layers, null, tint = if ((line.gpuLayers ?: 0) > 0) c.action else c.muted, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The device's own name stays as written (digits included); the numbers after it are the reader's.
            Text(device, style = Sig.type.meta.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight(600)), color = c.ink)
            Text(localizedNumber(detail), style = Sig.type.meta.copy(fontSize = 11.5.sp, lineHeight = 18.sp), color = c.muted)
        }
    }
}

/** The line's two halves: where models run ("موتور: ⁦Adreno 830⁩ · opencl"), then layers and threads. */
@Composable
internal fun engineLine(line: HardwareLine): Pair<String, String> {
    val cpu = line.threads?.let { tr("$it رشتهٔ CPU", "$it CPU threads") } ?: "CPU"
    val layers = line.gpuLayers ?: 0
    val gpu = line.gpu
    return when {
        !line.measured -> tr("موتور: CPU", "Engine: CPU") to tr("$cpu · کارت گرافیک در اولین ساخت سنجیده می‌شود", "$cpu · the GPU is measured on the first build")
        gpu != null && layers > 0 -> tr("موتور: ⁦$gpu · ${line.backend ?: "gpu"}⁩", "Engine: $gpu · ${line.backend ?: "gpu"}") to
            tr("$layers از ${line.totalLayers ?: "?"} لایه روی GPU · $cpu", "$layers of ${line.totalLayers ?: "?"} layers on the GPU · $cpu")
        else -> tr("موتور: فقط CPU (⁦${line.backend ?: "cpu"}⁩)", "Engine: CPU only (${line.backend ?: "cpu"})") to cpu
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = Sig.type.meta.copy(fontSize = 11.sp), color = Sig.colors.muted)
    }
}

/** "Recommended for your device": the director's voice, the tier, why, and one action. */
@Composable
private fun Recommended(ui: ModelsUi, actions: ModelActions) {
    val row = ui.recommended ?: return
    val c = Sig.colors
    SigGlass(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DirectorDot(size = 12.dp)
                DirectorText(tr("پیشنهاد برای دستگاه شما", "Recommended for your device"))
            }
            Text("${tr(row.nameFa, row.nameEn)} · ⁦${row.tier}⁩", style = Sig.type.title, color = c.ink)
            Text(
                localizedNumber(
                    tr(
                        "بزرگ‌ترین کارگردانی که روی این ${if (ui.desktop) "سیستم" else "گوشی"} راحت کار می‌کند؛ حدود ${gbFa(row.footprintBytes)} گیگ رم هنگام ساخت، ${gbFa(row.sizeBytes)} گیگ دانلود.",
                        "The largest director this ${if (ui.desktop) "computer" else "phone"} runs comfortably: " +
                            "about ${gb(row.footprintBytes)} of RAM while building, ${gb(row.sizeBytes)} to download.",
                    ),
                ),
                style = Sig.type.meta, color = c.muted,
            )
            when (val s = row.state) {
                is ModelState.Downloading -> Progress(s.fraction)
                ModelState.NotInstalled, is ModelState.Failed ->
                    SigButton(tr("دانلود", "Download"), { actions.onDownload(row.id) }, Modifier.fillMaxWidth().height(50.dp), trailing = TrimioIcons.Download)
                else -> if (!row.active) {
                    SigButton(tr("استفاده از این کارگردان", "Use this director"), { actions.onUse(row.id) }, Modifier.fillMaxWidth().height(50.dp))
                } else {
                    SigChip(
                        tr("فعال و آماده", "Active and ready"), container = c.emphasisSoft, content = c.emphasisSoftContent,
                        leading = { TrimioIcon(TrimioIcons.Check, null, tint = c.emphasisSoftContent, size = 13.dp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Shelves(ui: ModelsUi, actions: ModelActions) {
    Column(Modifier.gutter().padding(top = 26.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SigSectionLabel(tr("کارگردان", "Director"), trailing = "LANGUAGE · VISION")
        ui.directors.forEach { ModelLine(it, ui.desktop, actions) }
        SigSectionLabel(tr("گفتار", "Speech"), Modifier.padding(top = 22.dp), trailing = "WHISPER")
        ui.speech.forEach { ModelLine(it, ui.desktop, actions) }
        if (ui.boosts.isNotEmpty()) Boosts(ui, actions)
    }
}

/**
 * «تقویت کیفیت»: the add-on tiers. The one-tap set for this device first, then each tier's add-ons
 * with what they bring, their download and the memory they hold while their stage runs.
 */
@Composable
private fun Boosts(ui: ModelsUi, actions: ModelActions) {
    val c = Sig.colors
    SigSectionLabel(tr("تقویت کیفیت", "Quality boost"), Modifier.padding(top = 22.dp), trailing = "ADD-ONS")
    Text(
        tr(
            "مدل‌های کمکی که روی خود دستگاه کار می‌کنند: تصویر، صدا و نوشتهٔ درست برای هر جمله. هر کدام در مرحلهٔ خودش بار می‌شود و بعد آزاد.",
            "Helper models that run on this device: the right picture, sound and spelling for every sentence. Each loads for its own stage, then frees its memory.",
        ),
        style = Sig.type.meta, color = c.muted, modifier = Modifier.padding(bottom = 8.dp),
    )
    ui.boostPlan?.let { BoostPlanCard(it, ui.desktop, actions) }
    ui.boosts.forEach { tier ->
        Text(tr(tier.titleFa, tier.titleEn), style = Sig.type.bodyStrong, color = c.ink, modifier = Modifier.padding(top = 14.dp))
        tier.rows.forEach { BoostLine(it, ui.desktop, actions) }
    }
}

/** «پیشنهاد برای این دستگاه»: how many add-ons, the download still to do, and one button for all of it. */
@Composable
private fun BoostPlanCard(plan: BoostPlan, desktop: Boolean, actions: ModelActions) {
    val c = Sig.colors
    SigGlass(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DirectorDot(size = 12.dp)
                DirectorText(tr("پیشنهاد برای این دستگاه", "Recommended for this device"))
            }
            Text(
                localizedNumber(
                    tr(
                        "${plan.ids.size} مدل کمکی که روی این ${if (desktop) "سیستم" else "گوشی"} کنار کارگردان جا می‌شوند؛ بیشترین رم در یک مرحله حدود ${gbFa(plan.ramBytes)} گیگ.",
                        "${plan.ids.size} add-ons that fit beside the director on this ${if (desktop) "computer" else "phone"}; at most about ${gb(plan.ramBytes)} of RAM in one stage.",
                    ),
                ),
                style = Sig.type.meta, color = c.muted,
            )
            if (plan.done) {
                SigChip(
                    tr("همه نصب شده‌اند", "All installed"), container = c.emphasisSoft, content = c.emphasisSoftContent,
                    leading = { TrimioIcon(TrimioIcons.Check, null, tint = c.emphasisSoftContent, size = 13.dp) },
                )
            } else {
                SigButton(
                    localizedNumber(tr("دانلود همه · ${gbFa(plan.downloadBytes)} گیگ", "Download all · ${gb(plan.downloadBytes)}")),
                    { actions.onDownloadAll(plan.missingIds) }, Modifier.fillMaxWidth().height(50.dp), trailing = TrimioIcons.Download,
                )
            }
        }
    }
}

/** One add-on: its name, what it brings, the fit badge and memory, and its one action. */
@Composable
private fun BoostLine(boost: BoostRow, desktop: Boolean, actions: ModelActions) {
    val c = Sig.colors
    val row = boost.row
    val blocked = row.fit == ModelFit.TooBig && !row.installed
    Column {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(row.nameFa, row.nameEn), style = Sig.type.bodyStrong, color = if (blocked) c.faint else c.ink, maxLines = 1)
                    if (boost.recommended) SigChip(tr("پیشنهادی", "Suggested"), Modifier.height(22.dp), container = c.emphasisSoft, content = c.emphasisSoftContent)
                }
                Text(tr(boost.purposeFa, boost.purposeEn), style = Sig.type.meta.copy(fontSize = 12.sp, lineHeight = 18.sp), color = c.ink.copy(alpha = 0.8f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FitBadge(row.fit, desktop)
                    val laptop = if (boost.laptopOnly) tr(" · فقط لپ‌تاپ", " · laptop only") else ""
                    Text(
                        localizedNumber(tr("${ramFa(row.footprintBytes)} رم در مرحلهٔ خودش$laptop", "${gb(row.footprintBytes)} RAM in its stage$laptop")),
                        style = Sig.type.meta.copy(fontSize = 11.sp), color = if (row.state is ModelState.Failed) c.action else c.muted, maxLines = 1,
                    )
                }
                (row.state as? ModelState.Downloading)?.let { Progress(it.fraction) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LineAction(row, blocked, actions)
                MicroLabel(gb(row.sizeBytes), color = c.faint)
            }
        }
    }
}

/** One model: name and tier, the fit badge, memory and speed, and its one action at the end. */
@Composable
private fun ModelLine(row: ModelRow, desktop: Boolean, actions: ModelActions) {
    val c = Sig.colors
    val blocked = row.fit == ModelFit.TooBig && !row.installed
    Column {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${tr(row.nameFa, row.nameEn)} · ⁦${row.tier}⁩", style = Sig.type.bodyStrong, color = if (blocked) c.faint else c.ink, maxLines = 1)
                    if (row.active) SigChip(tr("فعال", "Active"), Modifier.height(22.dp), container = c.action, content = c.onAction)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FitBadge(row.fit, desktop)
                    Text(subtitle(row), style = Sig.type.meta.copy(fontSize = 11.sp), color = if (row.state is ModelState.Failed) c.action else c.muted, maxLines = 1)
                }
                (row.state as? ModelState.Downloading)?.let { Progress(it.fraction) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LineAction(row, blocked, actions)
                MicroLabel(gb(row.sizeBytes), color = c.faint)
            }
        }
    }
}

@Composable
private fun LineAction(row: ModelRow, blocked: Boolean, actions: ModelActions) {
    val s = row.state
    when {
        blocked -> RoundAction(TrimioIcons.Key, tr("بیش از حافظهٔ این دستگاه", "More than this device holds"), enabled = false) {}
        s is ModelState.Downloading -> RoundAction(TrimioIcons.Close, tr("لغو دانلود", "Cancel download")) { actions.onCancel(row.id) }
        s is ModelState.UpdateAvailable -> RoundAction(TrimioIcons.Undo, tr("به‌روزرسانی", "Update"), accent = true) { actions.onUpdate(row.id) }
        row.installed -> RoundAction(TrimioIcons.Trash, tr("حذف", "Delete")) { actions.onDelete(row.id) }
        else -> RoundAction(TrimioIcons.Download, tr("دانلود", "Download"), accent = s is ModelState.Failed) { actions.onDownload(row.id) }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Sig.colors
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(if (accent) c.action else Color.Transparent)
            .border(1.dp, if (accent) c.action else c.lineStrong, CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { TrimioIcon(icon, label, tint = when { accent -> c.onAction; enabled -> c.ink; else -> c.faint }, size = 17.dp) }
}

/** The DeviceFit badge: lime block for recommended, quiet for fits, outlined for experimental, faint for too big. */
@Composable
private fun FitBadge(fit: ModelFit, desktop: Boolean) {
    val c = Sig.colors
    val (label, container, content) = when (fit) {
        ModelFit.Recommended -> Triple(tr("مناسب این دستگاه", "Right for this device"), c.emphasisSoft, c.emphasisSoftContent)
        ModelFit.Fits -> Triple(tr("جا می‌شود", "Fits"), c.ink.copy(alpha = 0.08f), c.ink)
        ModelFit.Experimental -> Triple(tr("آزمایشی", "Experimental"), Color.Transparent, c.muted)
        ModelFit.TooBig -> Triple(if (desktop) tr("بیش از رم سیستم", "Over this RAM") else tr("بیش از رم گوشی", "Over this phone's RAM"), Color.Transparent, c.faint)
    }
    SigChip(label, Modifier.height(22.dp), container = container, content = content, stroke = if (container == Color.Transparent) c.line else null)
}

@Composable
private fun Progress(fraction: Float) {
    val c = Sig.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.action))
        }
        MicroLabel("${(fraction * 100).toInt()}%", color = c.action)
    }
}

@Composable
private fun subtitle(row: ModelRow): String = when (val s = row.state) {
    is ModelState.Failed -> tr("دانلود ناموفق؛ دوباره تلاش کن", "Download failed; try again")
    is ModelState.UpdateAvailable -> localizedNumber(tr("نسخهٔ تازه · ${s.to.notesFa ?: "بهتر و دقیق‌تر"}", "New version · ${s.to.notesEn ?: "better and more accurate"}"))
    else -> localizedNumber(
        row.measuredTokensPerSecond?.let { tps ->
            val v = (tps * 10).toInt().let { "${it / 10}٫${it % 10}" }
            tr("${gbFa(row.footprintBytes)} گیگ رم · $v توکن/ث", "${gb(row.footprintBytes)} RAM · ${v.replace('٫', '.')} tok/s here")
        } ?: tr("${gbFa(row.footprintBytes)} گیگ رم · ${speed(row)}", "${gb(row.footprintBytes)} RAM · ${speedEn(row)}"),
    )
}

private fun speed(row: ModelRow) = when {
    row.sizeBytes < FAST -> "سریع"
    row.sizeBytes < BALANCED -> "متعادل"
    else -> "دقیق‌تر، کندتر"
}

private fun speedEn(row: ModelRow) = when {
    row.sizeBytes < FAST -> "fast"
    row.sizeBytes < BALANCED -> "balanced"
    else -> "most precise, slower"
}

private const val GIB = 1L shl 30
private const val MIB = 1L shl 20
private const val KIB = 1L shl 10
private const val FAST = 4L * GIB
private const val BALANCED = 8L * GIB

/** "2.7 GB", "640 MB", "900 KB" (Latin micro labels). */
private fun gb(bytes: Long): String {
    val tenths = (bytes * 10 + GIB / 2) / GIB
    return when {
        bytes < MIB -> "${(bytes + KIB / 2) / KIB} KB"
        bytes < GIB -> "${(bytes + MIB / 2) / MIB} MB"
        else -> "${tenths / 10}.${tenths % 10} GB"
    }
}

/** "۲٫۷ گیگ" or, under a gigabyte, "۲۴۰ مگ" (Persian copy; digits localised by the caller). */
private fun ramFa(bytes: Long): String = if (bytes < GIB) "${((bytes + MIB / 2) / MIB).coerceAtLeast(1)} مگ" else "${gbFa(bytes)} گیگ"

/** "۲٫۷" (Persian copy; digits localised by the caller). */
private fun gbFa(bytes: Long): String {
    val tenths = (bytes * 10 + GIB / 2) / GIB
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}٫${tenths % 10}"
}
