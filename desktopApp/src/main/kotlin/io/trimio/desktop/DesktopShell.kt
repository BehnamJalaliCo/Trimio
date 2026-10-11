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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.MediaKind
import io.trimio.core.data.SettingsRepository
import io.trimio.core.designsystem.component.AuroraBackground
import io.trimio.core.designsystem.component.ButtonKind
import io.trimio.core.designsystem.component.SurfaceCard
import io.trimio.core.designsystem.component.TrimioButton
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioSpacing
import io.trimio.core.designsystem.theme.TrimioTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.models.ModelSpec
import io.trimio.shared.TrimioApp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * The desktop window's content: a side rail made for mouse and keyboard (new edit from a video or
 * a voice recording, the drop target, the output folder, what this machine runs) next to the shared
 * app, whose screens stay in a readable column on wide windows. Dropping a video or a recording
 * anywhere on the window starts a new edit.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopShell(context: DesktopContext, systemLanguage: Language) {
    val settings by koinInject<SettingsRepository>().settings.collectAsStateWithLifecycle()
    val language = settings.language ?: systemLanguage
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
    TrimioTheme(language = language) {
        Row(
            Modifier.fillMaxSize().background(Trimio.colors.canvas)
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target),
        ) {
            DesktopRail(context, hovering, Modifier.width(RAIL_WIDTH).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                // The screens are laid out for a column; on very wide windows the aurora fills the sides.
                AuroraBackground(Modifier.fillMaxSize())
                Box(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxSize()) { TrimioApp(systemLanguage) }
            }
        }
    }
}

private val RAIL_WIDTH = 300.dp
private val CONTENT_MAX_WIDTH = 1200.dp

@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> = runCatching {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
}.getOrDefault(emptyList())

@Composable
private fun DesktopRail(context: DesktopContext, hovering: Boolean, modifier: Modifier) {
    val colors = Trimio.colors
    val scope = rememberCoroutineScope()
    val fa = Trimio.language == Language.Persian
    fun tr(persian: String, english: String) = if (fa) persian else english
    var gpus by remember { mutableStateOf<List<Gpu>?>(null) }
    LaunchedEffect(context) { gpus = runCatching { context.detectGpus() }.getOrDefault(emptyList()) }

    Column(
        modifier.background(colors.canvasRaised).padding(TrimioSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(TrimioSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            appIcon()?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.width(TrimioSpacing.md))
            Text("Trimio", style = Trimio.type.title, color = colors.textPrimary)
        }
        TrimioButton(
            tr("ساخت از ویدیو", "New from video"),
            onClick = { scope.launch { context.picker.pick(MediaKind.Video)?.let { context.incoming.open(it.value, MediaKind.Video) } } },
            modifier = Modifier.fillMaxWidth(),
            leading = { TrimioIcon(TrimioIcons.Film, null, tint = colors.onPrimary) },
        )
        TrimioButton(
            tr("ساخت از صدا", "New from voice"),
            onClick = { scope.launch { context.picker.pick(MediaKind.Audio)?.let { context.incoming.open(it.value, MediaKind.Audio) } } },
            modifier = Modifier.fillMaxWidth(),
            kind = ButtonKind.Glass,
            leading = { TrimioIcon(TrimioIcons.Mic, null) },
        )
        DropZone(hovering, tr("ویدیو یا صدا را اینجا رها کنید", "Drop a video or voice recording anywhere"))
        Spacer(Modifier.weight(1f))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Caption(tr("خروجی‌ها", "Finished videos"))
            Text(context.dirs.output.absolutePath, style = Trimio.type.caption, color = colors.textPrimary)
            TrimioButton(tr("باز کردن پوشه", "Open folder"), onClick = { context.dirs.output.mkdirs(); context.sharer.reveal(context.dirs.output) }, kind = ButtonKind.Ghost)
        }
        MachineCard(context.hardware, gpus, DesktopDirectors.offered(context.hardware.ramGb), DesktopDirectors.recommended(context.hardware), fa)
    }
}

@Composable
private fun DropZone(hovering: Boolean, label: String) {
    val colors = Trimio.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().height(120.dp)
            .background(if (hovering) colors.primary.copy(alpha = 0.18f) else colors.glassFill, shape)
            .border(1.5.dp, if (hovering) colors.accentCyan else colors.glassStroke, shape)
            .padding(TrimioSpacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TrimioIcon(TrimioIcons.Download, null, tint = if (hovering) colors.accentCyan else colors.textSecondary)
        Spacer(Modifier.height(TrimioSpacing.sm))
        Text(label, style = Trimio.type.caption, color = colors.textSecondary)
    }
}

@Composable
private fun MachineCard(hardware: DesktopHardware, gpus: List<Gpu>?, offered: List<ModelSpec>, recommended: ModelSpec, fa: Boolean) {
    val colors = Trimio.colors
    SurfaceCard(Modifier.fillMaxWidth()) {
        Caption(if (fa) "این رایانه" else "This computer")
        val gpu = when {
            gpus == null -> if (fa) "در حال بررسی…" else "checking…"
            gpus.isEmpty() -> if (fa) "شناخته نشد" else "not detected"
            else -> gpus.joinToString { g -> g.name + (g.vramMb?.let { " · ${(it + 512) / 1024} GB" } ?: "") }
        }
        Text((if (fa) "رم: " else "RAM: ") + "${hardware.ramGb} GB", style = Trimio.type.caption, color = colors.textPrimary)
        Text("GPU: $gpu", style = Trimio.type.caption, color = colors.textPrimary)
        Spacer(Modifier.height(TrimioSpacing.sm))
        Caption(if (fa) "کارگردان‌های این دستگاه" else "Directors offered here")
        offered.forEach { spec ->
            val title = if (fa) spec.titleFa else spec.titleEn
            val badge = if (spec.id == recommended.id) (if (fa) "  ✓ پیشنهادی" else "  ✓ recommended") else ""
            Text(title + badge, style = Trimio.type.caption, color = if (badge.isEmpty()) colors.textSecondary else colors.accentCyan)
        }
    }
}

@Composable
private fun Caption(text: String) = Text(text, style = Trimio.type.label, color = Trimio.colors.textTertiary)

/** The window and rail icon, drawn from the brand mark (desktopApp/icons). */
fun appIcon(): ImageBitmap? = runCatching {
    val bytes = DesktopContext::class.java.getResourceAsStream("/trimio-icon.png")!!.use { it.readBytes() }
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
