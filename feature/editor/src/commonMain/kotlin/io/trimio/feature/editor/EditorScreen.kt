package io.trimio.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.component.GlassScene
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.FootagePlaceholder
import io.trimio.core.designsystem.signature.LiveDot
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.theme.tr

/**
 * Board 3: the editor over the playing edit. Five tools in a glass rail (text, style, sound,
 * elements, cut), the open tool's panel in warm glass at the foot, export and undo at the top. On
 * a wide window the edit and the panel sit side by side. [frame] is the edit as rendered now.
 */
@Composable
fun EditorScreen(ui: EditorUi, actions: EditorActions, frame: ImageBitmap?) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(SigNoir.canvas).focusRequester(focus).focusable()
            .onKeyEvent { e -> e.type == KeyEventType.KeyDown && shortcut(e.key, e.isCtrlPressed || e.isMetaPressed, e.isShiftPressed, actions) },
    ) {
        SigAdaptive { width -> if (width.wide) Wide(ui, actions, frame) else Phone(ui, actions, frame) }
    }
}

/** Desktop keys: space plays, ⌘/Ctrl+Z undoes, with Shift (or Y) redoes. */
private fun shortcut(key: Key, command: Boolean, shift: Boolean, actions: EditorActions): Boolean = when {
    key == Key.Spacebar && !command -> true.also { actions.onPlay() }
    command && key == Key.Z && shift -> true.also { actions.onRedo() }
    command && key == Key.Z -> true.also { actions.onUndo() }
    command && key == Key.Y -> true.also { actions.onRedo() }
    else -> false
}

@Composable
private fun Phone(ui: EditorUi, actions: EditorActions, frame: ImageBitmap?) {
    GlassScene(
        Modifier.fillMaxSize(),
        background = {
            // The whole frame's width shows (nothing of the edit is cropped), from the top, under the bar.
            Stage(frame, ContentScale.FillWidth, Modifier.fillMaxSize().onSizeChanged { actions.onStageWidth(it.width) }, Alignment.TopCenter)
            // The board's grade: a little dark under the bar, clear over the picture, deep under the panel.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.4f), 0.18f to Color.Transparent, 0.4f to Color.Transparent,
                        0.6f to Color.Black.copy(alpha = if (ui.folded) 0.25f else 0.7f), 1f to Color.Black.copy(alpha = if (ui.folded) 0.35f else 0.7f),
                    ),
                ),
            )
        },
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            EditorTopBar(ui, actions, Modifier.padding(horizontal = 22.dp).padding(top = 8.dp))
            Column(Modifier.align(Alignment.TopEnd).padding(top = 74.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ToolRail(ui.tool, ui.folded, actions.onTool)
            }
            StageNotes(ui, Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
            AnimatedVisibility(
                !ui.folded, Modifier.align(Alignment.BottomCenter).imePadding(),
                enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut(),
            ) {
                SigGlass(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 18.dp),
                    shape = RoundedCornerShape(26.dp), contentPadding = PaddingValues(14.dp), overMedia = true,
                ) { Panel(ui, actions, wide = false) }
            }
            if (ui.folded) FoldedTransport(ui, actions, Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp))
        }
    }
}

@Composable
private fun Wide(ui: EditorUi, actions: EditorActions, frame: ImageBitmap?) {
    GlassScene(
        Modifier.fillMaxSize(),
        // The edit's own light, soft and far behind the glass.
        background = { Stage(frame, ContentScale.Crop, Modifier.fillMaxSize().blur(90.dp).alpha(0.32f)) },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp, vertical = 12.dp)) {
            EditorTopBar(ui, actions)
            Row(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        val shape = RoundedCornerShape(20.dp)
                        Box(Modifier.fillMaxHeight().aspectRatio(9f / 16f).clip(shape).border(1.dp, Color.White.copy(alpha = 0.14f), shape)) {
                            Stage(frame, ContentScale.Fit, Modifier.fillMaxSize().onSizeChanged { actions.onStageWidth(it.width) })
                            StageNotes(ui, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                        }
                        ToolRail(ui.tool, folded = false, onTool = actions.onTool)
                    }
                }
                // The panel is as tall as its tool needs, scrolling past the window's height.
                BoxWithConstraints(Modifier.width(440.dp).fillMaxHeight()) {
                    val room = maxHeight - 36.dp
                    SigGlass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), contentPadding = PaddingValues(18.dp), overMedia = true) {
                        Column(Modifier.heightIn(max = room).verticalScroll(rememberScrollState())) { Panel(ui, actions, wide = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(ui: EditorUi, actions: EditorActions, wide: Boolean) {
    when (ui.tool) {
        EditorTool.Text -> TextPanel(ui, actions)
        EditorTool.Style -> StylePanel(ui, actions)
        EditorTool.Sound -> SoundPanel(ui, actions, listHeight = if (wide) 420.dp else 126.dp)
        EditorTool.Elements -> ElementsPanel(ui, actions, listHeight = if (wide) 520.dp else 232.dp)
        EditorTool.Cut -> CutPanel(ui, actions)
    }
}

/** The edit as rendered now; the board's footage stand-in until the first frame is drawn. */
@Composable
private fun Stage(frame: ImageBitmap?, scale: ContentScale, modifier: Modifier, alignment: Alignment = Alignment.Center) {
    if (frame == null) FootagePlaceholder(modifier) else Image(frame, null, modifier, alignment = alignment, contentScale = scale)
}

/** What the stage is showing besides the edit: the director's version (compare), the work in hand, a warning. */
@Composable
private fun StageNotes(ui: EditorUi, modifier: Modifier) {
    val line = when {
        ui.style.comparing -> tr("قبل · ادیت کارگردان", "Before · the director's edit")
        ui.working -> tr("در حال اعمال تغییر…", "Applying…")
        ui.mixing -> tr("میکس صدا…", "Mixing sound…")
        else -> null
    }
    AnimatedVisibility(line != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        SigGlass(shape = RoundedCornerShape(16.dp), overMedia = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!ui.style.comparing) LiveDot(size = 6.dp)
                Text(line.orEmpty(), style = Sig.type.meta.copy(fontSize = 11.5.sp, fontWeight = FontWeight(700)), color = Ink, maxLines = 1)
            }
        }
    }
}

/** With the panel folded away: time and play in a small glass pill. */
@Composable
private fun FoldedTransport(ui: EditorUi, actions: EditorActions, modifier: Modifier) {
    SigGlass(modifier, shape = RoundedCornerShape(30.dp), overMedia = true, contentPadding = PaddingValues(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Figure("${clock(ui.position)} / ${clock(ui.duration)}", color = InkSoft, size = 11.5f, weight = 600)
            Box(Modifier.size(42.dp).clip(CircleShape).background(Ink).clickable(role = Role.Button, onClick = actions.onPlay), contentAlignment = Alignment.Center) {
                TrimioIcon(if (ui.playing) TrimioIcons.Pause else TrimioIcons.Play, null, tint = SigNoir.canvas, size = 20.dp)
            }
            Spacer(Modifier.height(1.dp))
        }
    }
}

/** The edit cannot be opened (no longer held, or made where it cannot be changed): say so and go back. */
@Composable
fun EditorUnavailable(onBack: () -> Unit, changeable: Boolean) {
    Box(Modifier.fillMaxSize().background(SigNoir.canvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.align(Alignment.Center).padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(SigNoir.action.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                TrimioIcon(EditorIcons.Cut, null, tint = SigNoir.action, size = 22.dp)
            }
            Text(
                if (changeable) tr("این ادیت دیگر باز نیست.", "This edit is no longer open.") else tr("این ادیت را نمی‌شود تغییر داد.", "This edit can't be changed."),
                style = Sig.type.title, color = Ink,
            )
            Text(
                if (changeable) {
                    tr("ادیت‌ها تا وقتی برنامه باز است نگه داشته می‌شوند. از نتیجه دوباره بساز یا نسخهٔ تازه بگیر.", "Edits are kept while the app is open. Make it again from the result.")
                } else {
                    tr("روی این دستگاه ادیت خودکار انجام نمی‌شود؛ ویرایش روی اندروید و ویندوز است.", "Automatic edits don't run here; editing is on Android and Windows.")
                },
                style = Sig.type.body.copy(fontSize = 13.sp), color = InkSoft,
            )
            Box(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(SigNoir.action).clickable(role = Role.Button, onClick = onBack).padding(horizontal = 22.dp, vertical = 14.dp)) {
                Text(tr("بازگشت", "Back"), style = Sig.type.button, color = SigNoir.onAction)
            }
        }
    }
}
