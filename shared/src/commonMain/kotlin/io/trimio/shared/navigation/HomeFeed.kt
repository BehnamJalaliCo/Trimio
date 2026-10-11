package io.trimio.shared.navigation

import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.core.data.Connectivity
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.ProjectStatus
import io.trimio.core.data.SettingsRepository
import io.trimio.core.model.style.DesignStyle
import io.trimio.core.model.text.Language
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.ModelState
import io.trimio.engine.production.ProductionRunner
import io.trimio.engine.production.ProductionState
import io.trimio.feature.studio.HomeProject
import io.trimio.feature.studio.HomeReadiness
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** Home's rows and status chip, from the stored projects, the live run, the posters and the models. */
object HomeFeed {

    /** Stored projects joined with the live run: its exact progress and its newest frame as the poster. */
    fun projects(repository: ProjectRepository, productions: ProductionProjects, runner: ProductionRunner, settings: SettingsRepository, system: Language): Flow<List<HomeProject>> =
        combine(repository.projects, productions.posters, runner.active, settings.settings) { list, posters, active, s ->
            val persian = (s.language ?: system) == Language.Persian
            list.map { row(it, posters[it.id], active, persian) }
        }

    fun row(p: Project, poster: ImageBitmap?, active: ProductionState?, persian: Boolean): HomeProject {
        val record = p.production
        val live = active?.takeIf { record != null && it.key == record.key && !it.finished && p.status == ProjectStatus.Building }
        val styleId = record?.styleId ?: p.styleId
        return HomeProject(
            id = p.id,
            title = p.title,
            styleName = ProductionUi.styleName(styleId, persian) ?: styleId?.let(DesignStyle::fromId)?.let { if (persian) it.nameFa else it.nameEn },
            audioOnly = p.isAudioOnly,
            durationMs = record?.durationMs?.takeIf { it > 0 } ?: p.timeline?.durationMs ?: 0,
            updatedAt = p.updatedAt,
            status = p.status,
            progress = live?.progress ?: record?.progress ?: 0f,
            fresh = record != null && p.status == ProjectStatus.Ready && !record.seen,
            interrupted = p.error == ProductionProjects.INTERRUPTED || p.error == ProductionProjects.CANCELLED,
            poster = live?.previews?.lastOrNull() ?: poster,
        )
    }

    /**
     * The director's state for the status chip: ready on the device (offline or online), ready in
     * the cloud, its models downloading, or still missing. Online and the cloud key are checked
     * again every [CHECK_MS].
     */
    fun readiness(models: ModelManager, settings: SettingsRepository, cloud: CloudModels, connectivity: Connectivity?, previewOnly: Boolean): Flow<HomeReadiness> {
        if (previewOnly) return flowOf(HomeReadiness(HomeReadiness.Director.Preview))
        val reach = flow {
            while (true) {
                val online = connectivity?.let { runCatching { it.online() }.getOrDefault(false) } ?: false
                val key = runCatching { cloud.firstAvailable() != null }.getOrDefault(false)
                emit(online to key)
                delay(CHECK_MS)
            }
        }.distinctUntilChanged()
        return combine(models.states, settings.settings, reach) { states, s, (online, key) ->
            readiness(models.catalog, states, online, key && s.director == DirectorBackend.Cloud)
        }.distinctUntilChanged()
    }

    fun readiness(catalog: List<ModelSpec>, states: Map<String, ModelState>, online: Boolean, cloud: Boolean): HomeReadiness {
        fun installed(kind: ModelKind) = catalog.any { it.kind == kind && states[it.id].let { s -> s == ModelState.Installed || s is ModelState.UpdateAvailable } }
        val speech = installed(ModelKind.Speech)
        val defaults = catalog.filter { it.isDefault }
        val downloading = defaults.any { states[it.id] is ModelState.Downloading }
        val director = when {
            speech && cloud && online -> HomeReadiness.Director.Cloud
            speech && installed(ModelKind.Language) -> HomeReadiness.Director.Ready
            downloading -> HomeReadiness.Director.Downloading
            else -> HomeReadiness.Director.Missing
        }
        val total = defaults.sumOf { it.sizeBytes }.coerceAtLeast(1)
        val done = defaults.sumOf { spec ->
            when (val st = states[spec.id]) {
                ModelState.Installed, is ModelState.UpdateAvailable -> spec.sizeBytes
                is ModelState.Downloading -> st.bytes
                else -> 0L
            }
        }
        return HomeReadiness(director, online, (done.toFloat() / total).coerceIn(0f, 1f))
    }

    private const val CHECK_MS = 15_000L
}
