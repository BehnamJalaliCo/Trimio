package io.trimio.shared.navigation

import androidx.compose.runtime.Composable
import io.trimio.core.data.ProjectRepository
import io.trimio.engine.production.ProductionRequest
import io.trimio.engine.production.ProductionRunner
import io.trimio.feature.editor.EditorRoute
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The editor for the edit [Route.Editor.projectId] names (a project's id or a production's run
 * key; [io.trimio.engine.production.ProducedEdits] finds it). Export goes to the export screen by
 * the same id; the creator's words to the director start the project's next version, as on the
 * result screen.
 */
@Composable
internal fun EditorEntry(
    route: Route.Editor,
    onBack: () -> Unit,
    onExport: (id: String) -> Unit,
    onProduce: (request: ProductionRequest, projectId: String?, version: Int) -> Unit,
) {
    val productions = koinInject<ProductionProjects>()
    val projects = koinInject<ProjectRepository>()
    val runner = koinInject<ProductionRunner>()
    EditorRoute(
        koinViewModel(key = "editor-" + route.projectId) { parametersOf(route.projectId) },
        onBack = onBack,
        onExport = onExport,
        onRewrite = { edit, feedback ->
            val project = projects.get(route.projectId)
            val request = project?.let { productions.next(it, edit, feedback) }
                ?: edit.request.copy(seed = edit.request.seed + 1).also { runner.revise(edit, feedback) }.takeIf { project == null }
            request?.let { onProduce(it, project?.id, (project?.production?.version ?: 1) + 1) }
        },
    )
}
