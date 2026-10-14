package io.trimio.desktop

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
import io.trimio.engine.autopilot.WebKnowledge
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.llm.SecretStore
import io.trimio.engine.llm.cloud.ClaudeLanguageModel
import io.trimio.engine.media.FfmpegMediaSource
import io.trimio.engine.media.MediaProbe
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelStore
import io.trimio.engine.production.AutoEditor
import io.trimio.engine.production.Checkpoints
import io.trimio.engine.production.JvmProductionPlatform
import io.trimio.engine.production.LocalDirectors
import io.trimio.engine.production.ProductionPlatform
import io.trimio.engine.production.Productions
import io.trimio.feature.create.AttachmentPicker
import io.trimio.shared.di.AppPaths
import io.trimio.shared.di.ClaudeFactory
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
            // Content libraries: %APPDATA%\Trimio\packs\content (the style packs keep the folder's own files).
            contentPacks = Path(java.io.File(dirs.packs, "content").absolutePath),
            // The on-demand animations: %APPDATA%\Trimio\LottieFiles.
            animations = Path(java.io.File(dirs.data, "LottieFiles").absolutePath),
        )
    }
    single<io.trimio.feature.library.AnimationFilePicker> { DesktopAnimationPicker() }
    single<SecretStore> { FileSecretStore(dirs.secretsFile) }
    single<MediaPicker> { picker }
    single<AttachmentPicker> { picker }
    single { DesktopSharer(dirs.output, os) }
    single<Sharer> { get<DesktopSharer>() }
    single<GallerySaver> { DesktopGallerySaver(dirs.output, get()) }
    // The result screen plays the finished edit in the app (the bundled ffmpeg, the sound card as the clock).
    single { io.trimio.shared.player.ffmpegVideoPlayers() }
    // The create screen's speeds: the directors offered here, and the dedicated cards' memory (asked once, off the UI thread).
    single<io.trimio.engine.models.DirectorOffer> {
        io.trimio.engine.models.DirectorOffer { catalog -> DesktopDirectors.catalogFor(hardware.ramGb, catalog).filter { it.kind == io.trimio.engine.models.ModelKind.Language } }
    }
    single<io.trimio.engine.models.GraphicsProbe> {
        val cards = lazy { DesktopHardware.detect().gpus.filter { it.vramMb != null } }
        io.trimio.engine.models.GraphicsProbe {
            withContext(Dispatchers.IO) { cards.value }.takeIf { it.isNotEmpty() }
                ?.let { g -> io.trimio.engine.models.GraphicsMemory(g.map { it.name }, g.sumOf { it.vramMb ?: 0L } shl 20) }
        }
    }
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
        // The retrieval add-on widens the picture search only while its embedder is installed (else the word search alone).
        // One retrieval add-on for pictures and the music bed (text↔picture and text↔sound by meaning).
        val recall = directors().pictureRecall()
        io.trimio.engine.autopilot.ArtShelf.recall = io.trimio.engine.autopilot.ArtShelf.Recall(recall::search)
        io.trimio.engine.autopilot.MusicShelf.recall = recall::music
        JvmProductionPlatform(
            dirs.output, recognizer = { recognizer }, directors = { choice -> directors().director(choice) },
            lighter = { id -> directors().lighter(id) }, speech = { directors().speechModel { settings.settings.value.speechModelId } },
            perception = { directors().perception() },
            diffusion = { directors().diffusion(get<DeviceProfile>().threads) },
        )
    }
    // «سنجش سرعت این دستگاه»: the director and speech model timed every way this device runs them; the best is kept.
    single<io.trimio.engine.models.SpeedTest> {
        val settings = get<SettingsRepository>()
        val models = get<ModelManager>()
        val store = ModelStore(get<AppPaths>().models, get())
        val catalog = DesktopDirectors.catalogFor(hardware.ramGb, models.catalog)
        LocalDirectors(store, hardware.memory, catalog, preferred = { settings.settings.value.localModelId }).speedTest { settings.settings.value.speechModelId }
    }
    single<AutoEditor> {
        Productions(get(), get(), WebKnowledge.cached(get(), File(dirs.cache, "web").absolutePath), threads = { get<DeviceProfile>().threads }, app = "Trimio $appVersion · ${platformName(os)}")
    }
}

private fun platformName(os: DesktopOs) = when (os) {
    DesktopOs.Windows -> "Windows"
    DesktopOs.MacOs -> "macOS"
    DesktopOs.Linux -> "Linux"
} + " " + System.getProperty("os.version").orEmpty()

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
