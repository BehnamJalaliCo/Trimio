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
import io.trimio.feature.settings.SettingsViewModel
import io.trimio.feature.studio.StudioViewModel
import io.trimio.shared.navigation.HomeFeed
import io.trimio.shared.navigation.ProductionProjects
import io.trimio.shared.navigation.ProjectEdits
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
        CreateViewModel(args.uri, args.kind, get(), get(), get(), get(), args.styleId, get(), attachments, args.prompt)
    }
    viewModel { (projectId: String) -> EditorViewModel(projectId, get(), get(), get()) }
    // Exports need the platform's encoder; platforms without the production engine (web previews) have none.
    viewModel { (args: ExportArgs) -> ExportViewModel(args, get(), getOrNull<ProductionPlatform>()?.let(::EditExports), get(), getOrNull(), getOrNull()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), getOrNull(), get(), get()) }
    viewModel { GalleryViewModel() }
}
