package io.trimio.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.trimio.shared.IncomingMedia
import io.trimio.shared.initTrimio
import io.trimio.shared.uiLanguageFor
import org.koin.dsl.module
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File
import java.util.Locale

/** Installer version, set by Gradle's `packageVersion` through the launcher (jpackage.app-version). */
private val appVersion: String = System.getProperty("jpackage.app-version") ?: "dev"

/**
 * Starts Trimio on the desktop. Arguments are files to open: the installers register Trimio for
 * .mp4 and .mov, so "Open with Trimio" lands here and starts a new edit.
 */
fun main(args: Array<String>) {
    BundledTools.configure()
    val dirs = DesktopDirs.of().create()
    BundledTools.state(dirs)
    val hardware = DesktopHardware.basic()
    val incoming = IncomingMedia()
    var frame: Frame? = null
    val picker = DesktopFilePicker { frame }
    initTrimio(desktopModule(dirs, hardware, picker, incoming))
    args.map(::File).forEach { file -> MediaTypes.kindOf(file)?.let { incoming.open(file.absolutePath, it) } }

    val context = DesktopContext(dirs, hardware, picker, DesktopSharer(dirs.output), incoming)
    val icon = appIcon()?.let(::BitmapPainter)
    application {
        val fit = WindowFit.of(GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds)
        val state = rememberWindowState(
            placement = if (fit.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            width = fit.width.dp, height = fit.height.dp, position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
        )
        Window(onCloseRequest = ::exitApplication, state = state, title = "Trimio", icon = icon) {
            window.minimumSize = Dimension(fit.minWidth, fit.minHeight)
            frame = window
            DesktopShell(context, uiLanguageFor(Locale.getDefault().toLanguageTag()))
        }
    }
}

/** The desktop services plus the window's own inbox for dropped and opened files. */
fun desktopModule(dirs: DesktopDirs, hardware: DesktopHardware, picker: DesktopFilePicker, incoming: IncomingMedia) =
    module {
        includes(desktopPlatformModule(dirs, hardware, appVersion, picker))
        single { incoming }
    }

/**
 * The first window size: the designed 1360×880 when the screen's usable area (scaled pixels, taskbar
 * excluded) holds it, else a little smaller than that area; a small screen (a 1080p laptop at 150 %
 * is 1280×~680) opens maximized so the title bar with its buttons is always on screen.
 */
internal data class WindowFit(val width: Int, val height: Int, val minWidth: Int, val minHeight: Int, val maximized: Boolean) {
    companion object {
        fun of(usable: Rectangle): WindowFit {
            val w = minOf(WIDTH, (usable.width * SHARE).toInt())
            val h = minOf(HEIGHT, (usable.height * SHARE).toInt())
            val small = usable.width < WIDTH || usable.height < HEIGHT
            return WindowFit(w, h, minOf(MIN_WIDTH, usable.width), minOf(MIN_HEIGHT, usable.height), small)
        }

        private const val WIDTH = 1360
        private const val HEIGHT = 880
        private const val MIN_WIDTH = 960
        private const val MIN_HEIGHT = 600
        private const val SHARE = 0.94
    }
}
