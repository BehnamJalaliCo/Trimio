package io.trimio.shared.di

import io.ktor.client.HttpClient
import io.trimio.core.data.FileProjectStore
import io.trimio.core.data.JobRunner
import io.trimio.core.data.MediaKind
import io.trimio.core.data.MemoryProjectStore
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.SettingsRepository
import io.trimio.core.pipeline.PipelineOrchestrator
import io.trimio.engine.assets.AssetLibrary
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelStore
import io.trimio.engine.styles.StylePackRepository
import io.trimio.feature.create.CreateViewModel
import io.trimio.feature.editor.EditorViewModel
import io.trimio.feature.editor.ExportViewModel
import io.trimio.feature.gallery.GalleryViewModel
import io.trimio.feature.settings.SettingsViewModel
import io.trimio.feature.stream.BuildStreamViewModel
import io.trimio.feature.studio.StudioViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Where the app keeps its files; null directories mean in-memory (web preview builds). */
data class AppPaths(
    val projects: Path?,
    val settingsFile: Path?,
    val models: Path,
)

/** Builds the two pipelines with each platform's engines (see `platformModule`s). */
interface PipelineFactory {
    /** All nine stages: probe, clean, recognise, align, analyse, direct, match assets, render, publish. */
    fun build(): PipelineOrchestrator

    /** Render and publish only, from an edited timeline. */
    fun export(): PipelineOrchestrator
}

/** The official Claude client where the platform has one (Android/desktop: the Java SDK). */
fun interface ClaudeFactory {
    fun create(apiKey: String, model: String): LanguageModel
}

/** Platform hooks around model downloads (Android: a user-initiated data-transfer job). */
fun interface DownloadHooks {
    fun onActiveChanged(active: Boolean)
}

val AppScope = named("app-scope")

data class CreateArgs(val uri: String, val kind: MediaKind, val styleId: String?)

/**
 * The dependency graph shared by every platform. A platform module must provide: [AppPaths],
 * SecretStore, MediaPicker, Sharer, DeviceInfo, MediaProbe, [HttpClient], [PipelineFactory] and
 * optionally [ClaudeFactory] and [DownloadHooks].
 */
val appModule: Module = module {
    single(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { ProjectRepository(get<AppPaths>().projects?.let(::FileProjectStore) ?: MemoryProjectStore()) }
    single { SettingsRepository(get<AppPaths>().settingsFile) }
    single { StylePackRepository() }
    single { AssetLibrary() }
    single { CloudModels(get(), get(), getOrNull<ClaudeFactory>()?.let { f -> { key: String, model: String -> f.create(key, model) } }) }
    single {
        val hooks = getOrNull<DownloadHooks>()
        ModelManager(ModelStore(get<AppPaths>().models, get<HttpClient>()), get(AppScope), onActiveChanged = { hooks?.onActiveChanged(it) })
    }
    single { JobRunner(get(AppScope), get(), { get<PipelineFactory>().build() }, { get<PipelineFactory>().export() }) }

    viewModel { StudioViewModel(get(), get()) }
    viewModel { (args: CreateArgs) -> CreateViewModel(args.uri, args.kind, get(), get(), get(), get(), get(), get(), args.styleId, get()) }
    viewModel { (projectId: String) -> BuildStreamViewModel(get(), get(), projectId) }
    viewModel { (projectId: String) -> EditorViewModel(projectId, get(), get()) }
    viewModel { (projectId: String) -> ExportViewModel(projectId, get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), getOrNull(), get()) }
    viewModel { GalleryViewModel(get()) }
}
