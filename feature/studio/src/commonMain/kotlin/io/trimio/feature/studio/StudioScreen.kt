package io.trimio.feature.studio

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.MediaKind
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectStatus
import io.trimio.core.designsystem.component.GlassChip
import io.trimio.core.designsystem.component.GlassIconButton
import io.trimio.core.designsystem.component.GlassPanel
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.component.TrimioScreen
import io.trimio.core.designsystem.component.pressable
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.style.DesignStyle
import io.trimio.engine.render.PreviewClock
import io.trimio.engine.render.TimelinePreview

@Composable
fun StudioRoute(
    viewModel: StudioViewModel,
    onCreate: (uri: String, kind: MediaKind) -> Unit,
    onOpen: (Project) -> Unit,
    onGallery: () -> Unit,
    onSettings: () -> Unit,
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    StudioScreen(
        projects = projects,
        now = viewModel.now(),
        onNew = { kind -> viewModel.pick(kind) { uri -> onCreate(uri, kind) } },
        onOpen = onOpen,
        onGallery = onGallery,
        onSettings = onSettings,
    )
}

/**
 * Home. One hero action (make something new, from a video or from audio only) and the user's
 * projects as a Bento grid: the newest takes a double-width tile, every tile with a finished edit
 * plays it live, silently, with the real renderer.
 */
@Composable
fun StudioScreen(
    projects: List<Project>,
    now: Long,
    onNew: (MediaKind) -> Unit,
    onOpen: (Project) -> Unit,
    onGallery: () -> Unit,
    onSettings: () -> Unit,
) {
    TrimioScreen(dimAurora = 0.25f) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Phones get two columns, tablets and unfolded foldables four.
            val columns = if (maxWidth > 700.dp) 4 else 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(horizontal = TrimioSpacing.screenGutter, vertical = TrimioSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.md),
                verticalArrangement = Arrangement.spacedBy(TrimioSpacing.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header(onSettings) }
                item(span = { GridItemSpan(maxLineSpan) }) { NewProjectCard(onNew) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(top = TrimioSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("پروژه\u200Cها", "Projects"), style = Trimio.type.title, color = Trimio.colors.textPrimary, modifier = Modifier.weight(1f))
                        if (projects.isNotEmpty()) Text(localizedNumber(projects.size.toString()), style = Trimio.type.numeric, color = Trimio.colors.textTertiary)
                    }
                }
                if (projects.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
                }
                itemsIndexed(projects, key = { _, p -> p.id }, span = { i, _ -> GridItemSpan(if (i == 0) minOf(2, maxLineSpan) else 1) }) { index, project ->
                    ProjectTile(project, now, featured = index == 0, enterIndex = index, onClick = { onOpen(project) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) { GalleryTile(onGallery) }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(TrimioSpacing.xxl)) }
            }
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = TrimioSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Trimio",
                style = Trimio.type.display.copy(
                    brush = Brush.horizontalGradient(listOf(Trimio.colors.textPrimary, Trimio.colors.accentCyan, Trimio.colors.accentMagenta)),
                ),
            )
            Text(tr("ویدیو یا صدا بده، موشن\u200Cگرافی بگیر", "Bring a video or a voice. Get motion graphics."), style = Trimio.type.label, color = Trimio.colors.textSecondary)
        }
        GlassIconButton(TrimioIcons.Settings, tr("تنظیمات", "Settings"), onSettings)
    }
}

@Composable
private fun NewProjectCard(onNew: (MediaKind) -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(TrimioSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(TrimioSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
                TrimioIcon(TrimioIcons.Sparkle, null, tint = Trimio.colors.accentCyan)
                Text(tr("ساخت ویدیوی جدید", "Make something new"), style = Trimio.type.title, color = Trimio.colors.textPrimary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.md)) {
                SourceTile(
                    title = tr("از ویدیو", "From a video"),
                    hint = tr("زیرنویس، کات و موشن روی فیلم", "Captions, cuts, motion on footage"),
                    icon = TrimioIcons.Film,
                    colors = listOf(Trimio.colors.primary, Trimio.colors.accentMagenta),
                    modifier = Modifier.weight(1f),
                    onClick = { onNew(MediaKind.Video) },
                )
                SourceTile(
                    title = tr("فقط صدا", "Audio only"),
                    hint = tr("سبک، کل تصویر را می\u200Cسازد", "The style paints the picture"),
                    icon = TrimioIcons.Mic,
                    colors = listOf(Trimio.colors.auroraB, Trimio.colors.accentCyan),
                    modifier = Modifier.weight(1f),
                    onClick = { onNew(MediaKind.Audio) },
                )
            }
        }
    }
}

@Composable
private fun SourceTile(title: String, hint: String, icon: ImageVector, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(TrimioRadius.md)
    Column(
        modifier
            .pressable(onClick = onClick)
            .clip(shape)
            .background(Brush.linearGradient(colors.map { it.copy(alpha = 0.85f) }, start = Offset.Zero, end = Offset.Infinite))
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
            .padding(TrimioSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(TrimioSpacing.xs),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            TrimioIcon(icon, null, tint = Color.White)
        }
        Spacer(Modifier.height(TrimioSpacing.sm))
        Text(title, style = Trimio.type.title, color = Color.White)
        Text(hint, style = Trimio.type.caption, color = Color.White.copy(alpha = 0.8f), maxLines = 2)
    }
}

@Composable
private fun ProjectTile(project: Project, now: Long, featured: Boolean, enterIndex: Int, onClick: () -> Unit) {
    val colors = Trimio.colors
    val motion = Trimio.motion
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(motion.staggerMs(enterIndex).toLong())
        enter.animateTo(1f, motion.spatial())
    }
    val shape = RoundedCornerShape(TrimioRadius.lg)
    Box(
        Modifier
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 40f
            }
            .fillMaxWidth()
            .aspectRatio(if (featured) 1.25f else 0.72f)
            .pressable(onClick = onClick)
            .clip(shape)
            .background(colors.canvasRaised)
            .border(1.dp, colors.glassStroke, shape),
    ) {
        val timeline = project.timeline
        val style = project.style
        if (timeline != null && style != null) {
            val clock = remember(project.id) { PreviewClock(minOf(timeline.durationMs / 3, 2_500)) }
            TimelinePreview(timeline, style, Modifier.fillMaxSize(), clock)
        } else {
            Placeholder(project)
        }
        // Legibility scrim for the label.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
        Column(Modifier.align(Alignment.BottomStart).padding(TrimioSpacing.md), verticalArrangement = Arrangement.spacedBy(TrimioSpacing.xxs)) {
            StatusChip(project.status)
            Text(project.title, style = if (featured) Trimio.type.title else Trimio.type.label, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(relativeTime(project.updatedAt, now), project.styleId?.let(DesignStyle::fromId)?.let { tr(it.nameFa, it.nameEn) }).joinToString(" · "),
                style = Trimio.type.caption,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Placeholder(project: Project) {
    val colors = Trimio.colors
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(colors.auroraA, colors.auroraBase, colors.auroraB), Offset.Zero, Offset(size.width, size.height)))
        // A quiet waveform motif for audio projects, film perforations for video.
        if (project.isAudioOnly) {
            val bars = 24
            val w = size.width / (bars * 2f)
            for (i in 0 until bars) {
                val h = size.height * (0.12f + 0.25f * kotlin.math.abs(kotlin.math.sin(i * 0.7f + project.id.hashCode() % 7)))
                drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(w * (2 * i + 0.5f), size.height * 0.45f - h / 2), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2))
            }
        } else {
            for (i in 0 until 8) {
                val y = size.height * (i + 0.5f) / 8f
                drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(size.width * 0.05f, y - 6f), androidx.compose.ui.geometry.Size(14f, 12f), androidx.compose.ui.geometry.CornerRadius(3f))
                drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(size.width * 0.95f - 14f, y - 6f), androidx.compose.ui.geometry.Size(14f, 12f), androidx.compose.ui.geometry.CornerRadius(3f))
            }
        }
    }
}

@Composable
private fun StatusChip(status: ProjectStatus) {
    val colors = Trimio.colors
    when (status) {
        ProjectStatus.Building -> GlassChip(tr("در حال ساخت", "Building"), accent = colors.accentCyan)
        ProjectStatus.Failed -> GlassChip(tr("ناموفق", "Failed"), accent = colors.danger)
        ProjectStatus.Draft -> GlassChip(tr("پیش\u200Cنویس", "Draft"))
        ProjectStatus.Ready -> Unit
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = TrimioSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Trimio.colors.primary.copy(alpha = 0.6f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) { TrimioIcon(TrimioIcons.Wand, null, tint = Trimio.colors.textPrimary, size = 30.dp) }
        Text(tr("هنوز پروژه\u200Cای نساختی", "No projects yet"), style = Trimio.type.title, color = Trimio.colors.textPrimary)
        Text(
            tr("یه ویدیو یا ویس انتخاب کن و بگو چی می\u200Cخوای؛ بقیه\u200Cاش با Trimio.", "Pick a video or a voice note and say what you want. Trimio does the rest."),
            style = Trimio.type.body,
            color = Trimio.colors.textSecondary,
        )
    }
}

@Composable
private fun GalleryTile(onClick: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.md)) {
            TrimioIcon(TrimioIcons.Palette, null, tint = Trimio.colors.accentMagenta)
            Column(Modifier.weight(1f)) {
                Text(tr("گالری سبک\u200Cها", "Style gallery"), style = Trimio.type.title, color = Trimio.colors.textPrimary)
                Text(tr("هر سبک را زنده ببین", "See every style, live"), style = Trimio.type.label, color = Trimio.colors.textSecondary)
            }
            TrimioIcon(TrimioIcons.ChevronForward, null, tint = Trimio.colors.textTertiary)
        }
    }
}

@Composable
private fun relativeTime(then: Long, now: Long): String {
    val minutes = ((now - then) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> tr("همین الان", "just now")
        minutes < 60 -> localizedNumber(tr("$minutes دقیقه پیش", "$minutes min ago"))
        minutes < 60 * 24 -> localizedNumber(tr("${minutes / 60} ساعت پیش", "${minutes / 60} h ago"))
        else -> localizedNumber(tr("${minutes / 1440} روز پیش", "${minutes / 1440} d ago"))
    }
}
