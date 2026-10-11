package io.trimio.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.designsystem.signature.LiveDot
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.engine.production.Edit
import kotlinx.coroutines.launch

/**
 * The editor for the produced edit its view model was opened with. [onExport] gets the id the
 * export finds the edit by (the edit as it stands is kept there first); [onRewrite] hands the
 * creator's words to the director, with the edit they were looking at.
 */
@Composable
fun EditorRoute(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onExport: (String) -> Unit,
    onRewrite: (edit: Edit, feedback: String) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    when (val s = state) {
        EditorViewModel.State.Opening -> Box(Modifier.fillMaxSize().background(SigNoir.canvas)) { LiveDot(Modifier.align(Alignment.Center)) }
        is EditorViewModel.State.Unavailable -> EditorUnavailable(onBack, s.changeable)
        is EditorViewModel.State.Open -> EditorScreen(
            s.ui,
            EditorActions(
                onBack = onBack,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onExport = { scope.launch { viewModel.publishForExport()?.let(onExport) } },
                onPlay = viewModel::play,
                onSeek = { viewModel.seek(it) },
                onTool = viewModel::tool,
                onWord = viewModel::word,
                onEmphasise = { viewModel.emphasise() },
                onEditText = { viewModel.editText(it) },
                onHit = { viewModel.hit() },
                onDelete = { viewModel.delete() },
                onRewrite = { text -> viewModel.edit()?.let { onRewrite(it, text) } },
                onStyle = { viewModel.style(it) },
                onEnergy = viewModel::energy,
                onDensity = { viewModel.density(it) },
                onCompare = viewModel::compare,
                onMusic = { viewModel.music(it) },
                onMusicDb = viewModel::musicDb,
                onEffects = { viewModel.effects(it) },
                onAutoMix = { viewModel.autoMix() },
                onElement = { k, on -> viewModel.element(k, on) },
                onAdding = viewModel::adding,
                onAdd = viewModel::add,
                onMore = viewModel::more,
                onSilences = { viewModel.silences(it) },
                onFillers = { viewModel.fillers(it) },
                onBeatCuts = { viewModel.beatCuts(it) },
                onStageWidth = viewModel::frameWidth,
            ),
            frame,
        )
    }
}
