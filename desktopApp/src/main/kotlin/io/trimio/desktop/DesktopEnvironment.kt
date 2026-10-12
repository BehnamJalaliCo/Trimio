package io.trimio.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/** The desktop operating systems Trimio knows how to lay out its files on. */
enum class DesktopOs {
    Windows, MacOs, Linux;

    companion object {
        val current: DesktopOs = of(System.getProperty("os.name").orEmpty())

        fun of(osName: String): DesktopOs = when {
            osName.startsWith("Windows", ignoreCase = true) -> Windows
            osName.startsWith("Mac", ignoreCase = true) -> MacOs
            else -> Linux
        }
    }
}

/**
 * Where the desktop app keeps its files. Everything the app owns lives under one data directory
 * (Windows: `%APPDATA%\Trimio`, macOS: `~/Library/Application Support/Trimio`, Linux:
 * `$XDG_DATA_HOME/trimio`): models, signed packs, cache, projects, settings. Finished videos go
 * where people look for them: `Videos\Trimio` (`~/Movies/Trimio` on macOS).
 *
 * `-Dtrimio.home` moves both under one directory, for tests and portable installs.
 */
data class DesktopDirs(val data: File, val output: File) {
    val models: File get() = File(data, "models")
    val packs: File get() = File(data, "packs")
    val cache: File get() = File(data, "cache")
    val projects: File get() = File(data, "projects")
    val productions: File get() = File(data, "productions")
    val crashes: File get() = File(data, "crashes")
    val settingsFile: File get() = File(data, "settings.json")
    val remoteCache: File get() = File(cache, "remote.json")
    val secretsFile: File get() = File(data, "secrets.properties")

    /** Legacy pipeline renders before they are published to [output]. */
    val renders: File get() = File(cache, "renders")

    fun create(): DesktopDirs = apply { listOf(data, models, packs, cache, projects, productions, renders, output).forEach { it.mkdirs() } }

    companion object {
        fun of(
            os: DesktopOs = DesktopOs.current,
            env: (String) -> String? = System::getenv,
            home: File = File(System.getProperty("user.home")),
            override: String? = System.getProperty("trimio.home"),
            videos: () -> File? = { if (os == DesktopOs.Windows) windowsVideosFolder() else null },
        ): DesktopDirs {
            if (!override.isNullOrBlank()) return DesktopDirs(File(override, "data"), File(override, "Videos/Trimio"))
            return when (os) {
                DesktopOs.Windows -> DesktopDirs(
                    File(env("APPDATA")?.takeIf(String::isNotBlank) ?: File(home, "AppData/Roaming").path, "Trimio"),
                    File(videos() ?: File(env("USERPROFILE")?.takeIf(String::isNotBlank)?.let(::File) ?: home, "Videos"), "Trimio"),
                )
                DesktopOs.MacOs -> DesktopDirs(File(home, "Library/Application Support/Trimio"), File(home, "Movies/Trimio"))
                DesktopOs.Linux -> DesktopDirs(
                    File(env("XDG_DATA_HOME")?.takeIf(String::isNotBlank) ?: File(home, ".local/share").path, "trimio"),
                    File(home, "Videos/Trimio"),
                )
            }
        }

        /**
         * The user's Videos library, which may have been moved (to another drive, or OneDrive). The
         * shell keeps its resolved path in the registry; null falls back to %USERPROFILE%\Videos.
         */
        private fun windowsVideosFolder(): File? = runCatching {
            val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders"
            val out = Commands.run(listOf("reg", "query", key, "/v", "My Video")) ?: return null
            out.lineSequence().firstOrNull { "My Video" in it }?.substringAfter("REG_SZ")?.trim()?.takeIf(String::isNotEmpty)?.let(::File)
        }.getOrNull()
    }
}

/**
 * The tools and engines packaged with the app. jpackage unpacks `desktopApp/resources/<os>` into
 * the install and names it in `compose.application.resources.dir`; this points the engines at it
 * through the system properties they read ([io.trimio.core.model.tools.MediaTools],
 * `NativeLibraries`, `LlamaNative`). Properties already set on the command line win, and running
 * from source (no resources dir) keeps ffmpeg from the PATH and libx264.
 */
object BundledTools {
    fun configure(resources: File? = System.getProperty("compose.application.resources.dir")?.let(::File), os: DesktopOs = DesktopOs.current) {
        if (resources == null || !resources.isDirectory) return
        val ffmpeg = File(resources, "ffmpeg")
        if (ffmpeg.isDirectory) {
            setDefault("trimio.ffmpeg.dir", ffmpeg.absolutePath)
            // The bundled build is LGPL without libx264; Windows encodes with Media Foundation (docs/DESKTOP.md).
            if (os == DesktopOs.Windows) setDefault("trimio.h264.encoder", "h264_mf")
        }
        val native = File(resources, "native")
        if (native.isDirectory) {
            setDefault("trimio.native.dir", native.absolutePath)
            // GPU builds first; each falls back to the next when its runtime is missing.
            setDefault("trimio.native.variants", if (os == DesktopOs.MacOs) "metal,cpu" else "vulkan,cpu")
        }
    }

    /**
     * Engine state kept in the app's cache: the GPU render guard (a render that crashed the
     * process inside the graphics driver leaves it, and later exports draw on the CPU).
     */
    fun state(dirs: DesktopDirs) {
        setDefault("trimio.render.gpu.marker", File(dirs.cache, "gpu-render.state").absolutePath)
    }

    private fun setDefault(key: String, value: String) {
        if (System.getProperty(key).isNullOrBlank()) System.setProperty(key, value)
    }
}

/** Short-lived helper processes (registry, GPU queries), never allowed to hang the app. */
internal object Commands {
    fun run(command: List<String>, timeoutSeconds: Long = 4): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Wait first: these answers are a few lines, well inside the pipe buffer.
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        process.inputStream.bufferedReader().readText().takeIf { process.exitValue() == 0 }
    }.getOrNull()
}
