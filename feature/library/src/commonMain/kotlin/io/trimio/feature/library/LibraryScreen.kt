package io.trimio.feature.library

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
import io.trimio.core.designsystem.signature.SigSecondaryButton
import io.trimio.core.designsystem.signature.SigSectionLabel
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigTopRow
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.engine.models.PackState

/**
 * «کتابخانه‌ها»: the downloadable libraries that make an edit richer — animated emoji and icons,
 * recorded sound, textures and looks — each with what it improves, its size, and download, pause
 * or delete; the room they take; «دانلود همه»; and the creator's own animations (files imported on
 * desktop).
 */
@Composable
fun LibraryScreen(ui: LibraryUi, actions: LibraryActions) {
    SigScreen {
        SigAdaptive { width ->
            if (width.wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = SigSpace.xl), horizontalArrangement = Arrangement.spacedBy(SigSpace.xxl)) {
                    Column(Modifier.weight(0.85f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        Header(ui, actions)
                        Mine(ui, actions)
                    }
                    Column(Modifier.weight(1.15f).widthIn(max = 640.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 58.dp)) {
                        Shelves(ui, actions)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Header(ui, actions)
                    Shelves(ui, actions)
                    Mine(ui, actions)
                }
            }
        }
    }
}

@Composable
private fun Header(ui: LibraryUi, actions: LibraryActions) {
    val c = Sig.colors
    SigTopRow(if (ui.desktop) "LIBRARIES · ON DESKTOP" else "LIBRARIES · ON DEVICE", actions.onBack, backLabel = tr("بازگشت", "Back"))
    Column(Modifier.gutter().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SigHeroTitle(tr("کتابخانه‌ها", "Libraries"))
        Text(
            tr(
                "هر کتابخانه به کارگردان چیزهای تازه می‌دهد تا ادیت حرفه‌ای‌تر شود؛ هستهٔ اصلی داخل اپ است و بقیه هر وقت خواستی دانلود می‌شود. همه بدون اینترنت کار می‌کنند.",
                "Each library gives the director more to work with; the core is inside the app and the rest downloads when you want it. All of it works offline.",
            ),
            style = Sig.type.meta, color = c.muted,
        )
        Storage(ui)
        DownloadAll(ui, actions)
        if (!ui.catalogueKnown) {
            Text(
                tr("فهرست کتابخانه‌ها هنوز گرفته نشده؛ با اتصال به اینترنت به‌روز می‌شود.", "The list of libraries has not been fetched yet; it updates once you are online."),
                style = Sig.type.meta, color = c.action,
            )
        }
    }
}

/** Room taken by each kind of library against the free space. */
@Composable
private fun Storage(ui: LibraryUi) {
    val c = Sig.colors
    val parts = listOf(PackGroup.Art to c.action, PackGroup.Sound to c.live, PackGroup.Visual to c.emphasis, PackGroup.Mine to c.ink.copy(alpha = 0.5f))
        .map { (g, colour) -> ui.group(g).filter { it.installed }.sumOf { it.sizeBytes } to colour }
    val used = parts.sumOf { it.first }.coerceAtLeast(ui.usedBytes)
    val total = (ui.freeBytes?.let { it + used } ?: used).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SigSectionLabel(tr("فضای اشغال‌شده", "Storage used"), trailing = "${size(used)} USED" + (ui.freeBytes?.let { " · ${size(it)} FREE" } ?: ""))
        Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.08f))) {
            parts.filter { it.first > 0 }.forEach { (bytes, colour) -> Box(Modifier.weight(bytes.toFloat() / total).fillMaxHeight().background(colour)) }
            val rest = total - parts.sumOf { it.first }
            if (rest > 0) Box(Modifier.weight(rest.toFloat() / total).fillMaxHeight())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(c.action, tr("تصویر متحرک", "Animation"))
            Legend(c.live, tr("صدا", "Sound"))
            Legend(c.emphasis, tr("بافت و رنگ", "Texture & look"))
        }
        // The folder of animations the director fetched on demand (emptied from Settings).
        Text(
            localizedNumber(tr("انیمیشن‌های ذخیره‌شده از جستجوی آنلاین: ${sizeFa(ui.animationBytes)}", "Animations kept from online search: ${size(ui.animationBytes)}")),
            style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted,
        )
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = Sig.type.meta.copy(fontSize = 11.sp), color = Sig.colors.muted)
    }
}

/** «دانلود همه»: the size still to fetch and a Wi‑Fi hint, or the progress while it runs. */
@Composable
private fun DownloadAll(ui: LibraryUi, actions: LibraryActions) {
    val c = Sig.colors
    SigGlass(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DirectorDot(size = 12.dp)
                DirectorText(tr("ادیت حرفه‌ای‌تر با همهٔ کتابخانه‌ها", "Richer edits with every library"))
            }
            when {
                ui.allInstalled && ui.packs.isNotEmpty() -> SigChip(
                    tr("همهٔ کتابخانه‌ها نصب است", "Every library is installed"), container = c.emphasisSoft, content = c.emphasisSoftContent,
                    leading = { TrimioIcon(TrimioIcons.Check, null, tint = c.emphasisSoftContent, size = 13.dp) },
                )
                ui.downloading -> {
                    val rows = ui.packs.filter { it.state is PackState.Downloading || it.state == PackState.Queued || it.state == PackState.Installing }
                    Text(localizedNumber(tr("در حال دانلود؛ ${rows.size} کتابخانه در صف", "Downloading · ${rows.size} in the queue")), style = Sig.type.bodyStrong, color = c.ink)
                    SigSecondaryButton(tr("توقف همه", "Pause all"), actions.onPauseAll, Modifier.fillMaxWidth(), icon = TrimioIcons.Pause)
                }
                else -> {
                    Text(
                        localizedNumber(tr("دانلود همه: ${sizeFa(ui.remainingBytes)}", "Download all · ${size(ui.remainingBytes)}")),
                        style = Sig.type.title, color = c.ink,
                    )
                    Text(
                        tr("حجم زیاد است؛ بهتر است با وای‌فای و با شارژر دانلود شود. هر وقت خواستی متوقف کن، از همان‌جا ادامه می‌دهد.",
                            "It is large: best over Wi‑Fi and on the charger. Pause any time; it resumes where it stopped."),
                        style = Sig.type.meta, color = c.muted,
                    )
                    SigButton(tr("دانلود همه", "Download all"), actions.onDownloadAll, Modifier.fillMaxWidth().height(50.dp), trailing = TrimioIcons.Download, enabled = ui.remainingBytes > 0)
                }
            }
        }
    }
}

@Composable
private fun Shelves(ui: LibraryUi, actions: LibraryActions) {
    Column(Modifier.gutter().padding(top = 26.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val sections = listOf(
            Triple(PackGroup.Art, tr("تصویر متحرک", "Animation"), "EMOJI · ICONS · STICKERS"),
            Triple(PackGroup.Sound, tr("صدا و موسیقی", "Sound & music"), "SFX · MUSIC"),
            Triple(PackGroup.Visual, tr("بافت، نور و رنگ", "Texture, light & look"), "OVERLAYS · LUTS"),
        )
        sections.forEachIndexed { i, (group, name, micro) ->
            val rows = ui.group(group)
            if (rows.isNotEmpty()) {
                SigSectionLabel(name, if (i == 0) Modifier else Modifier.padding(top = 22.dp), trailing = micro)
                rows.forEach { PackLine(it, actions) }
            }
        }
    }
}

/** One library: title, what it improves, count and size, and its one action at the end. */
@Composable
private fun PackLine(row: PackRow, actions: LibraryActions) {
    val c = Sig.colors
    Column {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(row.titleFa, row.titleEn), style = Sig.type.bodyStrong, color = c.ink, maxLines = 1)
                    if (row.recommended && !row.installed) SigChip(tr("پیشنهادی", "Suggested"), Modifier.height(22.dp), container = c.emphasisSoft, content = c.emphasisSoftContent)
                    if (row.installed) SigChip(tr("نصب شده", "Installed"), Modifier.height(22.dp), container = c.ink.copy(alpha = 0.08f), content = c.ink)
                }
                val summary = tr(row.summaryFa, row.summaryEn)
                if (summary.isNotEmpty()) Text(summary, style = Sig.type.meta.copy(fontSize = 12.sp, lineHeight = 18.sp), color = c.muted, maxLines = 2)
                Text(status(row), style = Sig.type.meta.copy(fontSize = 11.sp), color = if (row.state is PackState.Failed || row.state is PackState.NoSpace) c.action else c.faint, maxLines = 1)
                progressOf(row.state)?.let { Progress(it, paused = row.state is PackState.Paused) }
            }
            LineAction(row, actions)
        }
    }
}

@Composable
private fun LineAction(row: PackRow, actions: LibraryActions) {
    when (val s = row.state) {
        is PackState.Downloading, PackState.Queued, PackState.Installing -> RoundAction(TrimioIcons.Pause, tr("توقف", "Pause")) { actions.onPause(row.id) }
        is PackState.Paused -> RoundAction(TrimioIcons.Play, tr("ادامه", "Resume"), accent = true) { actions.onDownload(row.id) }
        is PackState.Installed -> if (s.update != null) {
            RoundAction(TrimioIcons.Undo, tr("به‌روزرسانی", "Update"), accent = true) { actions.onDownload(row.id) }
        } else {
            RoundAction(TrimioIcons.Trash, tr("حذف", "Delete")) { actions.onDelete(row.id) }
        }
        else -> RoundAction(TrimioIcons.Download, tr("دانلود", "Download"), accent = s is PackState.Failed) { actions.onDownload(row.id) }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = Sig.colors
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(if (accent) c.action else Color.Transparent)
            .border(1.dp, if (accent) c.action else c.lineStrong, CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { TrimioIcon(icon, label, tint = if (accent) c.onAction else c.ink, size = 17.dp) }
}

private fun progressOf(s: PackState): Float? = when (s) {
    is PackState.Downloading -> s.fraction
    is PackState.Paused -> s.fraction
    PackState.Installing -> 1f
    else -> null
}

@Composable
private fun Progress(fraction: Float, paused: Boolean) {
    val c = Sig.colors
    val tint = if (paused) c.muted else c.action
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(tint))
        }
        MicroLabel(localizedNumber("${(fraction * 100).toInt()}%"), color = tint)
    }
}

@Composable
private fun status(row: PackRow): String {
    val count = if (row.items > 0) tr("${row.items} مورد، ", "${row.items} items · ") else ""
    return localizedNumber(
        when (val s = row.state) {
            is PackState.Downloading -> tr("دانلود: ${sizeFa(s.bytes)} از ${sizeFa(s.total)}", "Downloading · ${size(s.bytes)} of ${size(s.total)}")
            is PackState.Paused -> tr("متوقف: ${sizeFa(s.bytes)} از ${sizeFa(s.total)}", "Paused · ${size(s.bytes)} of ${size(s.total)}")
            PackState.Queued -> tr("در صف: ${sizeFa(row.sizeBytes)}", "Queued · ${size(row.sizeBytes)}")
            PackState.Installing -> tr("در حال نصب…", "Installing…")
            is PackState.NoSpace -> tr("فضا کم است: ${sizeFa(s.needed)} لازم، ${sizeFa(s.free)} آزاد", "Not enough room · needs ${size(s.needed)}, ${size(s.free)} free")
            is PackState.Failed -> tr("دانلود ناموفق؛ دوباره تلاش کن", "Download failed; try again")
            is PackState.Installed -> s.update?.let { u -> tr("نسخهٔ تازه آماده است: ${sizeFa(u.size)}", "New version ready · ${size(u.size)}") }
                ?: "$count${tr(sizeFa(row.sizeBytes), size(row.sizeBytes))}"
            is PackState.Available -> "$count${tr(sizeFa(row.sizeBytes), size(row.sizeBytes))}"
        },
    )
}

/** The creator's own animations: files imported on desktop. */
@Composable
private fun Mine(ui: LibraryUi, actions: LibraryActions) {
    val c = Sig.colors
    Column(Modifier.gutter().padding(top = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SigSectionLabel(tr("انیمیشن‌های من", "My animations"), trailing = "LOTTIE")
        ui.group(PackGroup.Mine).forEach { PackLine(it, actions) }
        if (ui.canImport) {
            SigSecondaryButton(tr("افزودن انیمیشن", "Add animations"), actions.onImport, Modifier.fillMaxWidth(), icon = TrimioIcons.Plus)
            Text(
                tr("فایل‌های ‎.json‎ یا ‎.lottie‎ (یا یک پوشه) را انتخاب کن؛ هر کدام بررسی می‌شود و کارگردان از روی نام و لایه‌هایش پیدایش می‌کند.",
                    "Choose .json or .lottie files (or a folder); each is checked, and the director finds it by its name and layers."),
                style = Sig.type.meta.copy(fontSize = 12.sp), color = c.muted,
            )
        }
        ui.importNote?.let { Text(localizedNumber(tr(it.fa, it.en)), style = Sig.type.meta.copy(fontSize = 12.sp), color = c.ink) }
    }
}

private const val MIB = 1L shl 20
private const val GIB = 1L shl 30

/** "1.4 GB", "230 MB" (Latin micro labels and English copy). */
internal fun size(bytes: Long): String = when {
    bytes >= GIB -> ((bytes * 10 + GIB / 2) / GIB).let { "${it / 10}.${it % 10} GB" }
    bytes >= MIB -> "${(bytes + MIB / 2) / MIB} MB"
    else -> "${maxOf(1, (bytes + 512) / 1024)} KB"
}

/** "۱٫۴ گیگابایت", "۲۳۰ مگابایت" (digits localised by the caller). */
internal fun sizeFa(bytes: Long): String = when {
    bytes >= GIB -> ((bytes * 10 + GIB / 2) / GIB).let { if (it % 10 == 0L) "${it / 10} گیگابایت" else "${it / 10}٫${it % 10} گیگابایت" }
    bytes >= MIB -> "${(bytes + MIB / 2) / MIB} مگابایت"
    else -> "${maxOf(1, (bytes + 512) / 1024)} کیلوبایت"
}

/** The library screen bound to its view model. */
@Composable
fun LibraryRoute(viewModel: LibraryViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LibraryScreen(ui, viewModel.actions(onBack))
}
