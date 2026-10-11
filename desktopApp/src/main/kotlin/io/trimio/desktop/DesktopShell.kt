package io.trimio.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.MediaKind
import io.trimio.core.data.SettingsRepository
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigSecondaryButton
import io.trimio.core.designsystem.signature.SigSectionLabel
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigTextButton
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioPreferences
import io.trimio.core.model.text.Language
import io.trimio.engine.models.ModelSpec
import io.trimio.shared.TrimioApp
import io.trimio.shared.ui.TrimioAppTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * The desktop window's content: a side rail made for mouse and keyboard (new edit from a video or
 * a voice recording, the drop target, the output folder, what this machine runs) next to the shared
 * app, whose screens lay themselves out for the window. Dropping a video or a recording anywhere on
 * the window starts a new edit. Both wear the app's theme (noir, paper or the system's).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopShell(context: DesktopContext, systemLanguage: Language) {
    val settings by koinInject<SettingsRepository>().settings.collectAsStateWithLifecycle()
    var hovering by remember { mutableStateOf(false) }
    val target = remember(context) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val media = droppedFiles(event).mapNotNull { f -> MediaTypes.kindOf(f)?.let { f to it } }.firstOrNull() ?: return false
                context.incoming.open(media.first.absolutePath, media.second)
                return true
            }
        }
    }
    TrimioAppTheme(settings, systemLanguage, TrimioPreferences()) {
        Row(
            Modifier.fillMaxSize().background(Sig.colors.canvas)
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target),
        ) {
            DesktopRail(context, hovering, Modifier.width(RAIL_WIDTH).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(Sig.colors.line))
            // The screens adapt to the window themselves (wide layouts side by side).
            Box(Modifier.weight(1f).fillMaxHeight()) { TrimioApp(systemLanguage) }
        }
    }
}

private val RAIL_WIDTH = 300.dp

@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> = runCatching {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
}.getOrDefault(emptyList())

@Composable
private fun DesktopRail(context: DesktopContext, hovering: Boolean, modifier: Modifier) {
    val c = Sig.colors
    val scope = rememberCoroutineScope()
    val fa = Trimio.language == Language.Persian
    fun tr(persian: String, english: String) = if (fa) persian else english
    var gpus by remember { mutableStateOf<List<Gpu>?>(null) }
    LaunchedEffect(context) { gpus = runCatching { context.detectGpus() }.getOrDefault(emptyList()) }

    Column(modifier.background(c.surface).padding(SigSpace.xl), verticalArrangement = Arrangement.spacedBy(SigSpace.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            appIcon()?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))) }
            Spacer(Modifier.width(SigSpace.md))
            Brand()
        }
        SigButton(
            tr("ساخت از ویدیو", "New from video"),
            onClick = { scope.launch { context.picker.pick(MediaKind.Video)?.let { context.incoming.open(it.value, MediaKind.Video) } } },
            modifier = Modifier.fillMaxWidth(),
            trailing = TrimioIcons.VideoFrame,
        )
        SigSecondaryButton(
            tr("ساخت از صدا", "New from voice"),
            onClick = { scope.launch { context.picker.pick(MediaKind.Audio)?.let { context.incoming.open(it.value, MediaKind.Audio) } } },
            modifier = Modifier.fillMaxWidth(),
            icon = TrimioIcons.Mic,
        )
        DropZone(hovering, tr("ویدیو یا صدا را هر جای پنجره رها کن", "Drop a video or voice recording anywhere"))
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Hairline()
            SigSectionLabel(tr("خروجی‌ها", "Finished videos"), trailing = "OUTPUT", modifier = Modifier.padding(top = 8.dp))
            Text(context.dirs.output.absolutePath, style = Sig.type.meta.copy(fontSize = 11.sp), color = c.ink)
            SigTextButton(tr("باز کردن پوشه", "Open folder"), onClick = { context.dirs.output.mkdirs(); context.sharer.reveal(context.dirs.output) })
        }
        MachineCard(context.hardware, gpus, DesktopDirectors.offered(context.hardware.ramGb), DesktopDirectors.recommended(context.hardware), fa)
    }
}

/** "trimio" with the ember full stop, left to right in every language. */
@Composable
private fun Brand() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            buildAnnotatedString {
                append("trimio")
                withStyle(SpanStyle(color = Sig.colors.action)) { append(".") }
            },
            style = Sig.type.figure.copy(fontSize = 22.sp, lineHeight = 26.sp), color = Sig.colors.ink,
        )
    }
}

@Composable
private fun DropZone(hovering: Boolean, label: String) {
    val c = Sig.colors
    val shape = RoundedCornerShape(SigSpace.radiusLg)
    Column(
        Modifier.fillMaxWidth().height(120.dp)
            .background(if (hovering) c.action.copy(alpha = 0.14f) else c.ink.copy(alpha = 0.04f), shape)
            .border(1.5.dp, if (hovering) c.action else c.lineStrong, shape)
            .padding(SigSpace.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TrimioIcon(TrimioIcons.Download, null, tint = if (hovering) c.action else c.muted)
        Spacer(Modifier.height(SigSpace.sm))
        Text(label, style = Sig.type.meta, color = c.muted)
    }
}

@Composable
private fun MachineCard(hardware: DesktopHardware, gpus: List<Gpu>?, offered: List<ModelSpec>, recommended: ModelSpec, fa: Boolean) {
    val c = Sig.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Hairline()
        SigSectionLabel(if (fa) "این رایانه" else "This computer", trailing = "MACHINE", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        val gpu = when {
            gpus == null -> if (fa) "در حال بررسی…" else "checking…"
            gpus.isEmpty() -> if (fa) "شناخته نشد" else "not detected"
            else -> gpus.joinToString { g -> g.name + (g.vramMb?.let { " · ${(it + 512) / 1024} GB" } ?: "") }
        }
        Text((if (fa) "رم: " else "RAM: ") + "${hardware.ramGb} GB", style = Sig.type.meta, color = c.ink)
        Text("GPU: $gpu", style = Sig.type.meta, color = c.ink)
        Text(if (fa) "کارگردان‌های این دستگاه" else "Directors offered here", style = Sig.type.meta, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        offered.forEach { spec ->
            val title = if (fa) spec.titleFa else spec.titleEn
            val best = spec.id == recommended.id
            Text(title + if (best) (if (fa) "  ✓ پیشنهادی" else "  ✓ recommended") else "", style = Sig.type.meta.copy(fontSize = 11.5.sp), color = if (best) c.ink else c.muted)
        }
    }
}

/** The window and rail icon, drawn from the brand mark (desktopApp/icons). */
fun appIcon(): ImageBitmap? = runCatching {
    val bytes = DesktopContext::class.java.getResourceAsStream("/trimio-icon.png")!!.use { it.readBytes() }
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
