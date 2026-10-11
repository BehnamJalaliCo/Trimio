package io.trimio.desktop

import androidx.compose.ui.text.font.FontFamily
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.trimio.core.data.Connectivity
import io.trimio.core.data.CrashLog
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.StorageInfo
import io.trimio.core.data.DeviceProfile
import io.trimio.core.data.GallerySaver
import io.trimio.core.data.MediaPicker
import io.trimio.core.data.SettingsRepository
import io.trimio.core.data.Sharer
import io.trimio.core.data.ThermalMonitor
import io.trimio.engine.asr.whisper.InstalledWhisperRecognizer
import io.trimio.engine.autopilot.WebKnowledge
import io.trimio.engine.director.DefaultDirectorModels
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.llm.SecretStore
import io.trimio.engine.llm.cloud.ClaudeLanguageModel
import io.trimio.engine.llm.local.LocalModels
import io.trimio.engine.media.FfmpegMediaSource
import io.trimio.engine.media.MediaProbe
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelStore
import io.trimio.engine.production.AutoEditor
import io.trimio.engine.production.Checkpoints
import io.trimio.engine.production.JvmProductionPlatform
import io.trimio.engine.production.LocalDirectors
import io.trimio.engine.production.ProductionPlatform
import io.trimio.engine.production.Productions
import io.trimio.engine.render.DesktopVideoExporter
import io.trimio.engine.render.ExportRequest
import io.trimio.engine.render.OutputPublisher
import io.trimio.engine.render.RenderFonts
import io.trimio.engine.render.VideoExporter
import io.trimio.feature.create.AttachmentPicker
import io.trimio.shared.di.AppPaths
import io.trimio.shared.di.ClaudeFactory
import io.trimio.shared.di.EnginePipelines
import io.trimio.shared.di.PipelineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * Services backed by the desktop: files under [DesktopDirs], the system file dialogs, ffmpeg for
 * media, whisper.cpp and llama.cpp through JNI, Skia rendering and the production engine
 * ([JvmProductionPlatform]) writing finished videos to Videos\Trimio.
 */
fun desktopPlatformModule(
    dirs: DesktopDirs,
    hardware: DesktopHardware,
    appVersion: String,
    picker: DesktopFilePicker,
    os: DesktopOs = DesktopOs.current,
): Module = module {
    single {
        AppPaths(
            projects = Path(dirs.projects.absolutePath),
            settingsFile = Path(dirs.settingsFile.absolutePath),
            models = Path(dirs.models.absolutePath),
            packs = Path(dirs.packs.absolutePath),
            remoteCache = Path(dirs.remoteCache.absolutePath),
        )
    }
    single<SecretStore> { FileSecretStore(dirs.secretsFile) }
    single<MediaPicker> { picker }
    single<AttachmentPicker> { picker }
    single { DesktopSharer(dirs.output, os) }
    single<Sharer> { get<DesktopSharer>() }
    single<GallerySaver> { DesktopGallerySaver(dirs.output, get()) }
    single<Connectivity> { DesktopConnectivity() }
    single { DeviceInfo(ramGb = hardware.ramGb, platform = platformName(os), appVersion = appVersion, cores = hardware.cores) }
    single<StorageInfo> { StorageInfo { runCatching { dirs.models.apply { mkdirs() }.usableSpace }.getOrNull()?.takeIf { it > 0 } } }
    single<ThermalMonitor> { ThermalMonitor.None }
    single { DeviceProfile.of(hardware.ramGb, hardware.cores) }
    single { CrashLog(Path(dirs.crashes.absolutePath)) }
    single<MediaProbe> { FfmpegMediaSource() }
    single { HttpClient(OkHttp) }
    single { ClaudeFactory { key, model -> ClaudeLanguageModel(key, model) } }

    // The automatic engine: ffmpeg + Skia + whisper.cpp + the director this machine is offered.
    single { Checkpoints(Path(dirs.productions.absolutePath)) }
    single<ProductionPlatform> {
        val settings = get<SettingsRepository>()
        val models = get<ModelManager>()
        val store = ModelStore(get<AppPaths>().models, get())
        // Built per run: the catalogue and the user's picks may change between runs.
        fun directors() = LocalDirectors(
            store, hardware.memory, DesktopDirectors.catalogFor(hardware.ramGb, models.catalog),
            preferred = { settings.settings.value.localModelId }, cloud = { get<CloudModels>().firstAvailable() },
        )
        val recognizer = directors().recognizer { settings.settings.value.speechModelId }
        JvmProductionPlatform(dirs.output, recognizer = { recognizer }, directors = { choice -> directors().director(choice) })
    }
    single<AutoEditor> {
        Productions(get(), get(), WebKnowledge(get()), threads = { get<DeviceProfile>().threads })
    }

    // Legacy pipelines for JobRunner (the screens not yet moved to the production engine).
    single<PipelineFactory> {
        val settings = get<SettingsRepository>()
        val models = get<ModelManager>()
        val store = ModelStore(get<AppPaths>().models, get())
        val ram = hardware.ramGb
        val media = FfmpegMediaSource()
        EnginePipelines(
            probe = media,
            decoder = media,
            recognizer = InstalledWhisperRecognizer {
                val installed = models.catalog.filter { it.kind == ModelKind.Speech && models.isInstalled(it) && it.tier.minRamGb <= ram }
                (installed.firstOrNull { it.id == settings.settings.value.speechModelId } ?: installed.maxByOrNull { it.sizeBytes })?.let(models::pathOf)
            },
            styles = get(),
            directors = DefaultDirectorModels(get<CloudModels>()) {
                LocalModels(store, ram, DesktopDirectors.catalogFor(ram, models.catalog), hardware.memory).best(settings.settings.value.localModelId)
            },
            library = get(),
            exporter = FontsFirst { fonts -> DesktopVideoExporter(fonts) },
            publisher = OutputFolderPublisher(dirs.output),
            outputPath = { jobId -> File(dirs.renders, "$jobId.mp4").absolutePath },
            threads = { get<DeviceProfile>().threads },
        )
    }
}

private fun platformName(os: DesktopOs) = when (os) {
    DesktopOs.Windows -> "Windows"
    DesktopOs.MacOs -> "macOS"
    DesktopOs.Linux -> "Linux"
} + " " + System.getProperty("os.version").orEmpty()

/** Loads the render font once, then hands it to the real exporter. */
private class FontsFirst(private val create: (FontFamily) -> VideoExporter) : VideoExporter {
    private var delegate: VideoExporter? = null
    override suspend fun export(request: ExportRequest, onFrame: suspend (Int, Int) -> Unit): String {
        val exporter = delegate ?: create(RenderFonts.vazirmatn()).also { delegate = it }
        return exporter.export(request, onFrame)
    }
}

/** Moves a finished legacy render into the output folder under its display name. */
private class OutputFolderPublisher(private val output: File) : OutputPublisher {
    override suspend fun publish(path: String, displayName: String): String = withContext(Dispatchers.IO) {
        output.mkdirs()
        val base = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").removeSuffix(".mp4").ifBlank { "Trimio" }
        val target = generateSequence(0) { it + 1 }.map { n -> File(output, if (n == 0) "$base.mp4" else "$base ($n).mp4") }.first { !it.exists() }
        File(path).copyTo(target)
        File(path).delete()
        target.absolutePath
    }
}

/**
 * API keys for the optional cloud director, in the user's own app-data folder (owner-only where
 * the file system has POSIX permissions; the Windows profile folder is already per-user).
 * Protecting them with DPAPI / the macOS Keychain is listed in docs/DESKTOP.md.
 */
private class FileSecretStore(private val file: File) : SecretStore {
    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) { load().getProperty(name) }

    override suspend fun write(name: String, value: String) = update { it.setProperty(name, value) }

    override suspend fun delete(name: String) = update { it.remove(name) }

    private suspend fun update(change: (Properties) -> Unit) = withContext(Dispatchers.IO) {
        synchronized(this@FileSecretStore) {
            val props = load().also(change)
            file.parentFile.mkdirs()
            file.outputStream().use { props.store(it, null) }
            runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
        Unit
    }

    private fun load() = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
}
