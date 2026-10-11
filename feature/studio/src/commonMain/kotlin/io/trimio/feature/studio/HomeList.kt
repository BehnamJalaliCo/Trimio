package io.trimio.feature.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.data.ProjectStatus
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.FootagePlaceholder
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigPaper
import io.trimio.core.designsystem.signature.SigSecondaryButton
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import kotlinx.coroutines.launch

/**
 * One project in the indexed list: its number, poster, name, what it is and when, and its state
 * (ready, the build's percentage with a live bar, or stopped). Tap opens it; long-press or a swipe
 * asks to delete it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectRow(index: Int, project: HomeProject, now: Long, last: Boolean, actions: HomeActions, modifier: Modifier = Modifier) {
    val c = Sig.colors
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        swipe,
        backgroundContent = { DeleteUnderlay() },
        modifier = modifier,
        // The swipe only asks; the row comes back while the dialog decides.
        onDismiss = {
            actions.onDelete(project.id)
            scope.launch { swipe.reset() }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().background(c.canvas)
                .combinedClickable(role = Role.Button, onLongClick = { actions.onDelete(project.id) }) { actions.onOpen(project.id) }
                .gutter(),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⁦" + index.toString().padStart(2, '0') + "⁩", style = Sig.type.index, color = c.muted, modifier = Modifier.width(36.dp))
                Poster(project, Modifier.size(50.dp, 66.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.title, style = Sig.type.bodyStrong.copy(fontSize = 14.5.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(meta(project, now), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    if (project.status == ProjectStatus.Building) ProgressLine(project.progress, Modifier.padding(top = 8.dp))
                }
                StatusChip(project)
            }
            if (!last) Hairline()
        }
    }
}

@Composable
private fun DeleteUnderlay() {
    val c = Sig.colors
    Row(
        Modifier.fillMaxSize().background(c.action).padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("حذف", "Delete"), style = Sig.type.bodyStrong, color = c.onAction)
        TrimioIcon(TrimioIcons.Trash, null, tint = c.onAction, size = 20.dp)
    }
}

@Composable
private fun meta(p: HomeProject, now: Long): String {
    val parts = when (p.status) {
        // The style names the edit; without one, a voice project says so.
        ProjectStatus.Building -> listOfNotNull(p.styleName ?: tr("فقط صدا", "Audio only").takeIf { p.audioOnly }, tr("در حال ساخت", "being made"))
        ProjectStatus.Failed -> listOfNotNull(p.styleName, if (p.interrupted) tr("متوقف شد", "stopped") else tr("ساخته نشد", "not made"), whenLine(p.updatedAt, now))
        else -> listOfNotNull(p.styleName, p.durationMs.takeIf { it > 0 }?.let { clockLine(it) }, whenLine(p.updatedAt, now))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun StatusChip(p: HomeProject) {
    val c = Sig.colors
    when {
        p.status == ProjectStatus.Building ->
            SigChip(localizedNumber(tr("${(p.progress * 100).toInt()}٪", "${(p.progress * 100).toInt()}%")), container = c.emphasisSoft, content = c.emphasisSoftContent)
        p.status == ProjectStatus.Failed && p.interrupted -> SigChip(tr("ادامه", "Resume"), container = Color.Transparent, content = c.ink, stroke = c.lineStrong)
        p.status == ProjectStatus.Failed -> SigChip(tr("دوباره", "Retry"), container = Color.Transparent, content = c.action, stroke = c.action)
        p.fresh -> SigChip(tr("آماده", "Ready"), container = c.action, content = c.onAction)
        else -> SigChip(tr("آماده", "Ready"))
    }
}

/** The build's progress as a thin live bar, filling from the reading start. */
@Composable
private fun ProgressLine(progress: Float, modifier: Modifier) {
    val c = Sig.colors
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.ink.copy(alpha = 0.1f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(c.live))
    }
}

/** The edit's poster frame; until there is one, footage or (for a voice) the title on paper. */
@Composable
private fun Poster(p: HomeProject, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.clip(shape)) {
        val poster = p.poster
        val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.3f) }) }
        val stopped = p.status == ProjectStatus.Failed
        when {
            poster != null -> Image(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = if (stopped) grey else null)
            p.audioOnly -> VoiceCover(p.title, Modifier.fillMaxSize())
            else -> FootagePlaceholder(Modifier.fillMaxSize())
        }
        if (stopped) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
}

/** A voice project's cover: its first words on paper over a lime bar. */
@Composable
private fun VoiceCover(title: String, modifier: Modifier) {
    Box(modifier.background(SigPaper.canvas)) {
        Text(
            title.split(' ').take(2).joinToString("\n"), Modifier.padding(start = 5.dp, end = 5.dp, top = 8.dp),
            style = Sig.type.title.copy(fontSize = 10.sp, lineHeight = 12.sp), color = SigPaper.ink, maxLines = 2, overflow = TextOverflow.Clip,
        )
        Box(Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 8.dp).size(22.dp, 5.dp).background(Sig.colors.emphasis))
    }
}

/** A starting point on the empty home (board 1, fourth screen). */
@Composable
internal fun IdeaCard(idea: Idea, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Sig.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).border(1.dp, c.line, shape).clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdeaThumb(idea, Modifier.size(52.dp, 64.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f)) {
            Text(tr(idea.titleFa, idea.titleEn), style = Sig.type.bodyStrong.copy(fontSize = 14.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr(idea.hintFa, idea.hintEn), style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TrimioIcon(TrimioIcons.ChevronForward, null, tint = c.muted, size = 18.dp)
    }
}

/** The three ideas' covers, drawn as the board draws them: a dark number card, a paper episode card and a cool product glow. */
@Composable
private fun IdeaThumb(idea: Idea, modifier: Modifier) {
    val c = Sig.colors
    when (idea.id) {
        "podcast" -> Box(modifier.background(SigPaper.canvas)) {
            Text(
                tr("قسمت\n۱۲", "EP\n12"), Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp),
                style = Sig.type.title.copy(fontSize = 11.sp, lineHeight = 13.sp), color = SigPaper.ink,
            )
            Box(Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 8.dp).size(26.dp, 6.dp).background(c.emphasis))
        }
        "product" -> Box(
            modifier.background(Color(0xFF0A0F1A)).drawBehind {
                drawRect(Brush.radialGradient(listOf(Color(0xFF2B3D63), Color(0xFF0A0F1A)), center = Offset(size.width / 2, size.height * 0.3f), radius = size.width * 0.8f))
            },
        ) {
            Text(
                tr("نو", "New"), Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 8.dp),
                style = Sig.type.title.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight(700), brush = Brush.linearGradient(c.director)),
            )
        }
        else -> Box(modifier.background(Color(0xFF0D0C0B))) {
            Text(
                tr("سود", "Profit"), Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 9.dp),
                style = Sig.type.hero.copy(fontSize = 15.sp, lineHeight = 18.sp), color = c.onMedia,
            )
            Box(Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 5.dp).size(18.dp, 2.dp).background(c.action))
        }
    }
}

/** The style gallery, one quiet row: every style, live. */
@Composable
internal fun GalleryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Sig.colors
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.ink.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                TrimioIcon(TrimioIcons.Palette, null, tint = c.ink, size = 18.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(tr("گالری سبک‌ها", "Style gallery"), style = Sig.type.bodyStrong.copy(fontSize = 14.sp), color = c.ink)
                Text(tr("هر سبک را زنده ببین و با آن شروع کن", "See every style live, and start from one"), style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted)
            }
            MicroLabel("STYLES")
            TrimioIcon(TrimioIcons.ChevronForward, null, tint = c.muted, size = 18.dp)
        }
        Hairline()
    }
}

/** Asks before a project and its files are deleted. */
@Composable
internal fun DeleteDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Sig.colors
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(22.dp).widthIn(max = 380.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.lineStrong, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.action.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                TrimioIcon(TrimioIcons.Trash, null, tint = c.action, size = 20.dp)
            }
            Text(tr("«$title» پاک شود؟", "Delete “$title”?"), style = Sig.type.title.copy(fontSize = 19.sp), color = c.ink)
            Text(
                tr(
                    "ویدیو، پوستر و فایل‌های این پروژه از این دستگاه پاک می‌شوند. نسخه‌ای که در گالری ذخیره کرده‌ای می‌ماند.",
                    "The video, its poster and the project's files are removed from this device. A copy you saved to your gallery stays.",
                ),
                style = Sig.type.body.copy(fontSize = 13.sp), color = c.muted,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SigSecondaryButton(tr("انصراف", "Cancel"), onDismiss, Modifier.weight(1f).height(50.dp))
                SigButton(tr("پاک کن", "Delete"), onConfirm, Modifier.weight(1f).height(50.dp))
            }
        }
    }
}
