package io.trimio.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.trimio.shared.IncomingMedia
import io.trimio.shared.initTrimio
import io.trimio.shared.uiLanguageFor
import org.koin.dsl.module
import java.awt.Dimension
import java.awt.Frame
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
    val hardware = DesktopHardware.basic()
    val incoming = IncomingMedia()
    var frame: Frame? = null
    val picker = DesktopFilePicker { frame }
    initTrimio(desktopModule(dirs, hardware, picker, incoming))
    args.map(::File).forEach { file -> MediaTypes.kindOf(file)?.let { incoming.open(file.absolutePath, it) } }

    val context = DesktopContext(dirs, hardware, picker, DesktopSharer(dirs.output), incoming)
    val icon = appIcon()?.let(::BitmapPainter)
    application {
        val state = rememberWindowState(width = 1360.dp, height = 880.dp, position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center))
        Window(onCloseRequest = ::exitApplication, state = state, title = "Trimio", icon = icon) {
            window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
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

private const val MIN_WIDTH = 1040
private const val MIN_HEIGHT = 680
