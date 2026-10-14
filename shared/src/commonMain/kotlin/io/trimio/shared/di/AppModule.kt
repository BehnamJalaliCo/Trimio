package io.trimio.shared.di

import io.ktor.client.HttpClient
import io.trimio.core.api.FilePackStore
import io.trimio.core.api.MemoryPackStore
import io.trimio.core.api.RemoteRepository
import io.trimio.core.api.RemoteService
import io.trimio.core.data.Billing
import io.trimio.core.data.Connectivity
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.Entitlements
import io.trimio.core.data.FileProjectStore
import io.trimio.core.data.NoBilling
import io.trimio.core.data.MediaKind
import io.trimio.core.data.MemoryProjectStore
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.SettingsRepository
import io.trimio.core.model.text.Language
import io.trimio.engine.assets.AssetLibrary
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelStore
import io.trimio.engine.models.PackChannel
import io.trimio.engine.models.PackIndexClient
import io.trimio.engine.models.PackKeys
import io.trimio.engine.models.PackManager
import io.trimio.engine.models.PackStore
import io.trimio.engine.production.AppProductionRunner
import io.trimio.engine.production.AutoEditor
import io.trimio.engine.production.Checkpoints
import io.trimio.engine.production.EditExports
import io.trimio.engine.production.ProducedEdits
import io.trimio.engine.production.ProductionPlatform
import io.trimio.engine.production.ProductionRunner
import io.trimio.engine.production.RecentEdits
import io.trimio.engine.styles.StylePackRepository
import io.trimio.feature.create.CreateViewModel
import io.trimio.feature.editor.EditDrafts
import io.trimio.feature.editor.EditorViewModel
import io.trimio.feature.editor.ExportArgs
import io.trimio.feature.editor.ExportViewModel
import io.trimio.feature.gallery.GalleryViewModel
import io.trimio.feature.library.AnimationFilePicker
import io.trimio.feature.library.LibraryViewModel
import io.trimio.feature.settings.SettingsViewModel
import io.trimio.feature.studio.StudioViewModel
import io.trimio.shared.navigation.HomeFeed
import io.trimio.shared.navigation.ProductionProjects
import io.trimio.shared.navigation.ProjectEdits
import io.trimio.shared.navigation.TechnicalUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Where the app keeps its files; null directories mean in-memory (web preview builds). */
data class AppPaths(
    val projects: Path?,
    val settingsFile: Path?,
    /** Null where models cannot be stored (web). */
    val models: Path?,
    /** Downloaded style packs. */
    val packs: Path? = null,
    /** Last good remote config and catalogue. */
    val remoteCache: Path? = null,
    /** Downloaded content libraries (art, sound, overlays, looks; not backed up on Android). */
    val contentPacks: Path? = null,
    /** «LottieFiles»: single animations fetched on demand for an edit, reused by every later one. */
    val animations: Path? = null,
)

/** The official Claude client where the platform has one (Android/desktop: the Java SDK). */
fun interface ClaudeFactory {
    fun create(apiKey: String, model: String): LanguageModel
}

/** Platform hooks around model downloads (Android: a user-initiated data-transfer job). */
fun interface DownloadHooks {
    fun onActiveChanged(active: Boolean)
}

val AppScope = named("app-scope")

data class CreateArgs(val uri: String, val kind: MediaKind, val styleId: String?, val prompt: String? = null)

/**
 * The dependency graph shared by every platform. A platform module must provide: [AppPaths],
 * SecretStore, MediaPicker, Sharer, DeviceInfo, MediaProbe, [HttpClient], the production engine
 * ([AutoEditor] and its [Checkpoints]) and optionally [ClaudeFactory], [DownloadHooks] and a
 * store [Billing].
 */
val appModule: Module = module {
    single(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { ProjectRepository(get<AppPaths>().projects?.let(::FileProjectStore) ?: MemoryProjectStore()) }
    single { SettingsRepository(get<AppPaths>().settingsFile) }
    single { StylePackRepository(get<AppPaths>().packs?.let(::FilePackStore) ?: MemoryPackStore()) }
    single { RemoteService(get()) }
    single { RemoteRepository(get(), get<AppPaths>().remoteCache, get(), get<DeviceInfo>().versionCode) }
    single { Entitlements(getOrNull<Billing>() ?: NoBilling, get<RemoteRepository>().state.map { it.config.paywallProduct }, get(AppScope)) }
    single { AssetLibrary() }
    single { CloudModels(get(), get(), getOrNull<ClaudeFactory>()?.let { f -> { key: String, model: String -> f.create(key, model) } }) }
    single {
        val hooks = getOrNull<DownloadHooks>()
        ModelManager(
            ModelStore(get<AppPaths>().models, get<HttpClient>()), get(AppScope),
            catalog = get<RemoteRepository>().models(ModelCatalog.all),
            onActiveChanged = { hooks?.onActiveChanged(it) },
        )
    }
    // Content libraries («کتابخانه‌ها»): signed packs from the channel, the creator's imports, and their link to the engine.
    single {
        val free = getOrNull<io.trimio.core.data.StorageInfo>()
        val dir = get<AppPaths>().contentPacks ?: Path("packs-preview")
        PackManager(
            PackStore(dir, get(), PackKeys.content, appBuild = get<DeviceInfo>().versionCode.takeIf { it > 0 } ?: Int.MAX_VALUE, freeBytes = { free?.freeBytes() }),
            PackIndexClient(get(), PackChannel(), Path(dir, "index.json")), get(AppScope),
            // The same platform hook as models (Android: the user-initiated transfer job keeps the download alive).
            onActiveChanged = { active -> getOrNull<DownloadHooks>()?.onActiveChanged(active) },
        )
    }
    // Animations the director fetches on demand (when «جستجوی انیمیشن آنلاین» is on), kept in the «LottieFiles» folder.
    single {
        val settings = get<SettingsRepository>()
        io.trimio.engine.autopilot.KtorWebPort.animations(get(), (get<AppPaths>().animations ?: Path("LottieFiles")).toString()) { settings.settings.value.onlineAnimations }
    }
    single(createdAtStart = true) {
        ContentLibrary(get(), get(AppScope), get<AppPaths>().contentPacks, animations = get<io.trimio.engine.assets.art.LottieFilesSource>())
    }
    // The automatic engine: one app-wide run that outlives its screen; screens and the foreground service observe it.
    single { AppProductionRunner(get(), get(AppScope), get()) }
    single<ProductionRunner> { get<AppProductionRunner>() }
    // The edits this process finished; started with the app so no edit is missed.
    single(createdAtStart = true) { RecentEdits(get(), get(AppScope)) }
    // Finished edits by project id, run key or draft id (exports, the editor): held, or made again from what their run kept.
    single<ProducedEdits> { ProjectEdits(get(), get(), get()) }

    // Every production is a project on home: saved as it starts, followed to its file (or its failure), versions and drafts kept.
    single { ProductionProjects(get(), get(), get(), get(AppScope), get<AppPaths>().projects, get()) }
    single<EditDrafts> { get<ProductionProjects>() }

    viewModel { (system: Language) ->
        val device = get<DeviceInfo>()
        StudioViewModel(
            HomeFeed.projects(get(), get(), get(), get(), system),
            HomeFeed.readiness(get(), get(), get(), getOrNull<Connectivity>(), device.previewOnly),
            get(),
        )
    }
    viewModel { (args: CreateArgs) ->
        // A platform's own attachment chooser (desktop) wins over the system media chooser.
        val attachments = getOrNull<io.trimio.feature.create.AttachmentPicker>() ?: io.trimio.shared.navigation.mediaAttachments(get())
        // «سریع / متعادل / حرفه‌ای»: the installed directors this device runs (desktops name theirs and their graphics card).
        val device = get<DeviceInfo>()
        val desktop = listOf("Android", "iOS", "Web").none { device.platform.startsWith(it) }
        val speeds = io.trimio.feature.create.SpeedSource(
            get(), io.trimio.engine.models.DeviceMemory(device.ramGb, desktop),
            getOrNull<io.trimio.engine.models.DirectorOffer>() ?: io.trimio.engine.models.DirectorOffer.All, getOrNull<io.trimio.engine.models.GraphicsProbe>(),
        )
        CreateViewModel(args.uri, args.kind, get(), get(), get(), get(), args.styleId, get(), attachments, args.prompt, speeds)
    }
    viewModel { (projectId: String) -> EditorViewModel(projectId, get(), get(), get()) }
    // Exports need the platform's encoder; platforms without the production engine (web previews) have none.
    viewModel { (args: ExportArgs) -> ExportViewModel(args, get(), getOrNull<ProductionPlatform>()?.let(::EditExports), get(), getOrNull(), getOrNull()) }
    viewModel {
        val animations = get<io.trimio.engine.assets.art.LottieFilesSource>()
        SettingsViewModel(
            get(), get(), get(), get(), getOrNull(), get(), get(),
            engine = getOrNull(), threads = getOrNull<io.trimio.core.data.DeviceProfile>()?.threads,
            animationBytes = animations::bytes, clearAnimations = animations::clear, speedTest = getOrNull(),
        )
    }
    // The engine as the latest build on this device measured it (GPU, layers, threads, model speeds).
    single<io.trimio.core.data.EngineFacts> {
        val checkpoints = get<io.trimio.engine.production.Checkpoints>()
        io.trimio.core.data.EngineFacts { checkpoints.reports.engine()?.let(TechnicalUi::engine) }
    }
    viewModel { GalleryViewModel() }
    viewModel {
        val device = get<DeviceInfo>()
        val free = getOrNull<io.trimio.core.data.StorageInfo>()
        LibraryViewModel(
            get(), validate = { doc -> io.trimio.engine.motion.art.MotionArts.readOrWhy(io.trimio.engine.motion.art.MotionArt.FORMAT_LOTTIE, doc).second },
            picker = getOrNull<AnimationFilePicker>(), freeBytes = { free?.freeBytes() },
            desktop = listOf("Android", "iOS", "Web").none { device.platform.startsWith(it) },
            animationBytes = get<io.trimio.engine.assets.art.LottieFilesSource>()::bytes,
        )
    }
}
