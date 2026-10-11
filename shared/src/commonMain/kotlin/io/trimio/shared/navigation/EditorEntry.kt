package io.trimio.shared.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.trimio.core.data.ProjectRepository
import io.trimio.engine.production.ProductionRequest
import io.trimio.engine.production.ProductionRunner
import io.trimio.feature.editor.EditorRoute
import io.trimio.feature.editor.EditorViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The editor for the edit [Route.Editor.projectId] names (a project's id or a production's run
 * key; [io.trimio.engine.production.ProducedEdits] opens it, after a restart too). Export goes to
 * the export screen by the id the editor hands over (its unsaved changes under a draft id); the
 * creator's words to the director start the project's next version, as on the result screen.
 *
 * Leaving with changes, by the back button or the system's back ([onLeaving] answers back first),
 * renders them as the project's next version ([ProductionProjects.retouch]); without changes it
 * is a plain back.
 */
@Composable
internal fun EditorEntry(
    route: Route.Editor,
    onBack: () -> Unit,
    onExport: (id: String) -> Unit,
    onProduce: (request: ProductionRequest, projectId: String?, version: Int, retouch: Boolean) -> Unit,
    onLeaving: (handler: (() -> Boolean)?) -> Unit,
) {
    val productions = koinInject<ProductionProjects>()
    val projects = koinInject<ProjectRepository>()
    val runner = koinInject<ProductionRunner>()
    val viewModel = koinViewModel<EditorViewModel>(key = "editor-" + route.projectId) { parametersOf(route.projectId) }
    DisposableEffect(viewModel) {
        onLeaving {
            val changes = viewModel.leave()
            val edit = viewModel.opened()
            if (changes == null || edit == null) return@onLeaving false
            val project = projects.get(route.projectId)
            val request = if (project != null) productions.retouch(project, edit, changes) else edit.request.also { runner.retouch(edit, changes) }
            request?.let { onProduce(it, project?.id, (project?.production?.version ?: 1) + 1, true) } != null
        }
        onDispose { onLeaving(null) }
    }
    EditorRoute(
        viewModel,
        onBack = onBack,
        onExport = onExport,
        onRewrite = { edit, feedback ->
            val project = projects.get(route.projectId)
            val request = project?.let { productions.next(it, edit, feedback) }
                ?: edit.request.copy(seed = edit.request.seed + 1).also { runner.revise(edit, feedback) }.takeIf { project == null }
            request?.let { onProduce(it, project?.id, (project?.production?.version ?: 1) + 1, false) }
        },
    )
}
