package io.trimio.shared.navigation

import androidx.compose.runtime.Composable
import io.trimio.core.data.DeviceInfo
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.model.text.Language
import io.trimio.feature.editor.ExportArgs
import io.trimio.feature.editor.ExportRoute
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The export screen for the edit [Route.Export.projectId] names (a production's run key or its
 * project's id; [io.trimio.engine.production.ProducedEdits] finds the edit).
 */
@Composable
internal fun ExportEntry(route: Route.Export, onBack: () -> Unit) {
    val device = koinInject<DeviceInfo>()
    val args = ExportArgs(route.projectId, persian = Trimio.language == Language.Persian, desktop = desktop(device))
    ExportRoute(koinViewModel(key = "export-" + route.projectId) { parametersOf(args) }, onBack = onBack)
}

/** Desktop builds name their operating system ("Windows 11", "macOS 15", "Linux 6.8"). */
private fun desktop(device: DeviceInfo) = listOf("Windows", "macOS", "Linux").any { device.platform.startsWith(it) }
