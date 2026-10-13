package io.trimio.feature.studio

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.MediaKind
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.DisplayText
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.WindowWidth
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.text.Language

/**
 * Home with the system pickers: every way in (a tile, an idea, the composer) picks the media first,
 * then [onCreate] opens the brief with it, the idea's or the composer's words, and the idea's style.
 */
@Composable
fun StudioRoute(
    viewModel: StudioViewModel,
    onCreate: (uri: String, kind: MediaKind, prompt: String?, styleId: String?) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onGallery: () -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
    announcement: String? = null,
    library: LibraryHint? = null,
    onLibrary: () -> Unit = {},
    onDismissLibrary: () -> Unit = {},
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val persian = Trimio.language == Language.Persian
    val actions = HomeActions(
        onNew = { kind -> viewModel.pick(kind) { uri -> onCreate(uri, kind, null, null) } },
        onIdea = { idea -> viewModel.pick(idea.kind) { uri -> onCreate(uri, idea.kind, if (persian) idea.briefFa else idea.briefEn, idea.styleId) } },
        onCompose = { wish -> Idea.kindFor(wish).let { kind -> viewModel.pick(kind) { uri -> onCreate(uri, kind, wish, null) } } },
        onOpen = onOpen, onDelete = onDelete, onGallery = onGallery, onSettings = onSettings, onModels = onModels,
        onLibrary = onLibrary, onDismissLibrary = onDismissLibrary,
    )
    // Suggested only after setup: once the director can work, not while the models are still coming.
    val ready = readiness.director == HomeReadiness.Director.Ready || readiness.director == HomeReadiness.Director.Cloud
    StudioScreen(HomeState(projects, readiness, announcement, viewModel.now(), library?.takeIf { ready }), actions)
}

/**
 * Home (board 1): the brand, the director's status and settings; today's date over the display
 * question; the two ways in (a video, or the voice alone); then either ideas to start from or the
 * creator's projects as an indexed list; and the composer, to tell the director what to make. On
 * wide windows the ways in and the projects sit side by side.
 */
@Composable
fun StudioScreen(state: HomeState, actions: HomeActions) {
    val entrance = rememberEntrance()
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val rowActions = actions.copy(onDelete = { deleting = it })
    SigScreen {
        SigAdaptive { width ->
            when (width) {
                WindowWidth.Expanded -> WideHome(state, rowActions, entrance)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    PhoneHome(state, rowActions, entrance, Modifier.widthIn(max = 640.dp))
                }
            }
        }
        state.projects.firstOrNull { it.id == deleting }?.let { project ->
            DeleteDialog(project.title, onConfirm = { actions.onDelete(project.id); deleting = null }, onDismiss = { deleting = null })
        }
    }
}

@Composable
private fun PhoneHome(state: HomeState, actions: HomeActions, e: Entrance, modifier: Modifier) {
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = COMPOSER_CLEARANCE)) {
            item(key = "top") { TopBar(state.readiness, actions, e) }
            item(key = "hero") { Hero(state, e, Modifier.gutter().padding(top = 22.dp)) }
            item(key = "tiles") { EntryTiles(actions.onNew, e, Modifier.padding(top = 20.dp)) }
            state.library?.let { hint -> item(key = "library") { LibraryCard(hint, actions.onLibrary, actions.onDismissLibrary, Modifier.gutter().padding(top = 18.dp)) } }
            state.announcement?.takeIf { it.isNotBlank() }?.let { item(key = "note") { Announcement(it, Modifier.gutter().padding(top = 18.dp)) } }
            listSection(state, actions, e)
            item(key = "gallery") { GalleryRow(actions.onGallery, Modifier.gutter().padding(top = 18.dp)) }
        }
        BottomScrim()
        Composer(actions.onCompose, e, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = boardBottom(28.dp)))
    }
}

/** Wide windows: the ways in on one side, the projects (or ideas) and the composer on the other. */
@Composable
private fun WideHome(state: HomeState, actions: HomeActions, e: Entrance) {
    val c = Sig.colors
    Column(Modifier.fillMaxSize()) {
        TopBar(state.readiness, actions, e)
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.weight(0.95f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Hero(state, e, Modifier.gutter().padding(top = 22.dp), size = 64)
                EntryTiles(actions.onNew, e, Modifier.padding(top = 28.dp))
                state.library?.let { LibraryCard(it, actions.onLibrary, actions.onDismissLibrary, Modifier.gutter().padding(top = 18.dp)) }
                state.announcement?.takeIf { it.isNotBlank() }?.let { Announcement(it, Modifier.gutter().padding(top = 18.dp)) }
                GalleryRow(actions.onGallery, Modifier.gutter().padding(top = 18.dp, bottom = 24.dp))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().padding(bottom = 24.dp).background(c.line))
            Box(Modifier.weight(1.05f).fillMaxHeight()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = COMPOSER_CLEARANCE)) { listSection(state, actions, e, first = true) }
                BottomScrim()
                Composer(actions.onCompose, e, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = boardBottom(28.dp)))
            }
        }
    }
}

/** Ideas to start from on an empty home, otherwise the projects as an indexed list. */
private fun LazyListScope.listSection(state: HomeState, actions: HomeActions, e: Entrance, first: Boolean = false) {
    val top = if (first) 6.dp else if (state.projects.isEmpty()) 22.dp else 20.dp
    if (state.projects.isEmpty()) {
        item(key = "ideas-label") { SectionLabel(tr("یک ایده برای شروع", "An idea to start with"), "TRY", e, Modifier.padding(top = top)) }
        itemsIndexed(Idea.all, key = { _, idea -> "idea-" + idea.id }) { i, idea ->
            IdeaCard(idea, { actions.onIdea(idea) }, Modifier.gutter().padding(top = if (i == 0) 12.dp else 10.dp).rise(e, ROWS_AT + i * ROW_STAGGER, ROW_DURATION))
        }
    } else {
        item(key = "projects-label") {
            SectionLabel(tr("پروژه‌ها", "Projects"), "INDEX · " + state.projects.size.toString().padStart(2, '0'), e, Modifier.padding(top = top))
        }
        itemsIndexed(state.projects, key = { _, p -> p.id }) { i, project ->
            ProjectRow(
                index = i + 1, project = project, now = state.now, last = i == state.projects.lastIndex, actions = actions,
                modifier = Modifier.padding(top = if (i == 0) 6.dp else 0.dp).rise(e, ROWS_AT + minOf(i, MAX_STAGGERED) * ROW_STAGGER, ROW_DURATION),
            )
        }
    }
}

@Composable
private fun TopBar(readiness: HomeReadiness, actions: HomeActions, e: Entrance) {
    val c = Sig.colors
    Row(Modifier.fillMaxWidth().gutter().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        BrandMark(Modifier.fadeIn(e, 100, 500))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.rise(e, 250, 600).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = actions.onModels)) { ReadinessChip(readiness) }
            // With no director yet, the libraries sit right beside it: both are downloads the creator chooses.
            if (readiness.director == HomeReadiness.Director.Missing) {
                Box(Modifier.rise(e, 270, 600).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = actions.onLibrary)) {
                    SigChip(tr("کتابخانه‌ها", "Libraries"), leading = { TrimioIcon(TrimioIcons.Download, null, tint = c.ink, size = 13.dp) })
                }
            }
            Box(
                Modifier.pop(e, 300, 700).size(32.dp).clip(CircleShape).border(1.dp, c.lineStrong, CircleShape)
                    .clickable(role = Role.Button, onClick = actions.onSettings),
                contentAlignment = Alignment.Center,
            ) { TrimioIcon(TrimioIcons.Sliders, tr("تنظیمات", "Settings"), tint = c.ink, size = 16.dp) }
        }
    }
}

/** "trimio" with the ember full stop, always left to right. */
@Composable
internal fun BrandMark(modifier: Modifier = Modifier) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            buildAnnotatedString {
                append("trimio")
                withStyle(SpanStyle(color = Sig.colors.action)) { append(".") }
            },
            modifier,
            style = Sig.type.figure.copy(fontSize = 17.sp, lineHeight = 22.sp),
            color = Sig.colors.ink,
        )
    }
}

@Composable
private fun ReadinessChip(readiness: HomeReadiness) {
    val c = Sig.colors
    val percent = (readiness.progress * 100).toInt()
    val text = when (readiness.director) {
        HomeReadiness.Director.Ready -> if (readiness.online) tr("آنلاین · آماده", "Online · ready") else tr("آفلاین · آماده", "Offline · ready")
        HomeReadiness.Director.Cloud -> tr("ابری · آماده", "Cloud · ready")
        HomeReadiness.Director.Downloading -> localizedNumber(tr("دانلود کارگردان · $percent٪", "Getting the director · $percent%"))
        HomeReadiness.Director.Missing -> tr("کارگردان دانلود نشده", "Director not downloaded")
        HomeReadiness.Director.Preview -> tr("نسخهٔ نمایشی", "Preview")
    }
    SigChip(
        text,
        leading = {
            if (readiness.director == HomeReadiness.Director.Missing) Box(Modifier.size(8.dp).clip(CircleShape).background(c.action)) else DirectorDot(size = 10.dp)
        },
    )
}

/** Today's date over the display question: a first motion to make, or what to make today. */
@Composable
private fun Hero(state: HomeState, e: Entrance, modifier: Modifier, size: Int = 50) {
    val c = Sig.colors
    val first = state.projects.isEmpty()
    val lines = if (first) tr("اولین موشنت\nرا بساز", "Make your\nfirst motion") else tr("امروز چه\nبسازیم", "What do we\nmake today")
    val mark = if (first) "." else tr("؟", "?")
    val split = lines.split('\n')
    BoxWithConstraints(modifier) {
        val style = fittedHero(split.mapIndexed { i, l -> if (i == split.lastIndex) l + mark else l }, Sig.type.hero.copy(fontSize = size.sp, lineHeight = size.sp), maxWidth)
        Column {
            Text(todayLine(state.now), Modifier.rise(e, 350, 600), style = Sig.type.meta, color = c.muted)
            Spacer(Modifier.height(8.dp))
            split.forEachIndexed { i, line ->
                DisplayText(
                    buildAnnotatedString {
                        append(line)
                        if (i == split.lastIndex) withStyle(SpanStyle(color = c.action)) { append(mark) }
                    },
                    style = style,
                    modifier = Modifier.maskReveal(e, HERO_AT + i * HERO_STAGGER, HERO_DURATION),
                )
            }
        }
    }
}

/** The two ways in, between drawn rules: a video (A) and the voice alone (B). */
@Composable
private fun EntryTiles(onNew: (MediaKind) -> Unit, e: Entrance, modifier: Modifier) {
    val c = Sig.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier.fillMaxWidth()) {
        Hairline(Modifier.drawRule(e, 700, 900, rtl), strong = true)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).gutter()) {
            EntryTile(
                tr("ویدیو", "Video"), tr("موشن روی تصویر خودت", "Motion over your footage"), "A", TrimioIcons.VideoFrame,
                Modifier.weight(1f).rise(e, 820, 800), endPadding = 14.dp,
            ) { onNew(MediaKind.Video) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.lineStrong).fadeIn(e, 820, 800))
            EntryTile(
                tr("فقط صدا", "Audio only"), tr("فیلم توضیحی از صفر ساخته می‌شود", "An explainer film made from scratch"), "B", TrimioIcons.Mic,
                Modifier.weight(1f).rise(e, 900, 800), startPadding = 14.dp,
            ) { onNew(MediaKind.Audio) }
        }
        Hairline(Modifier.drawRule(e, 760, 900, rtl), strong = true)
    }
}

@Composable
private fun EntryTile(
    title: String,
    hint: String,
    letter: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    startPadding: Dp = 0.dp,
    endPadding: Dp = 0.dp,
    onClick: () -> Unit,
) {
    val c = Sig.colors
    Column(modifier.clickable(role = Role.Button, onClick = onClick).padding(start = startPadding, end = endPadding, top = 14.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TrimioIcon(icon, null, tint = c.ink, size = 22.dp)
            MicroLabel(letter)
        }
        // Line boxes as in the board (CSS): the title's 27.5 and the hint's single line.
        DisplayText(title, style = Sig.type.title, color = c.ink, modifier = Modifier.padding(top = 16.dp))
        DisplayText(hint, style = Sig.type.meta.copy(fontSize = 11.5.sp, lineHeight = 17.sp), color = c.muted)
    }
}

@Composable
private fun SectionLabel(title: String, micro: String, e: Entrance, modifier: Modifier) {
    Row(modifier.fillMaxWidth().gutter().rise(e, 1000, 700), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Sig.type.bodyStrong.copy(fontSize = 14.sp), color = Sig.colors.ink)
        MicroLabel(micro)
    }
}

/** A note from the team (or the preview notice), quiet, under the ways in. */
@Composable
private fun Announcement(text: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        DirectorDot(Modifier.padding(top = 4.dp), size = 10.dp)
        Text(text, style = Sig.type.meta, color = Sig.colors.muted)
    }
}

/** Fades the list into the canvas behind the floating composer. */
@Composable
private fun BoxScope.BottomScrim() {
    val c = Sig.colors
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.62f to c.canvas, 1f to c.canvas)))
}

/**
 * The composer: tell the director what to make. Sending picks the media, then opens the brief
 * with these words; with nothing typed yet it simply takes the focus.
 */
@Composable
private fun Composer(onCompose: (String) -> Unit, e: Entrance, modifier: Modifier) {
    val c = Sig.colors
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun send() {
        if (text.isBlank()) runCatching { focus.requestFocus() } else onCompose(text.trim())
    }
    SigGlass(
        modifier.fillMaxWidth().height(62.dp).slideUp(e, 1250, 1000)
            .let { if (text.isNotEmpty()) it.border(1.5.dp, c.director[1].copy(alpha = 0.55f), RoundedCornerShape(20.dp)) else it },
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BreathingDot()
            val style = Sig.type.body.copy(fontSize = 14.5.sp, fontWeight = FontWeight(400))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                textStyle = style.copy(color = c.ink),
                cursorBrush = SolidColor(c.action),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(tr("به کارگردان بگو چه می‌خواهی…", "Tell the director what you want…"), style = style, color = c.muted, maxLines = 1)
                        inner()
                    }
                },
            )
            SendButton(::send)
        }
    }
}

/** The ember send square, giving a little under the finger (a spring, as in the motion board). */
@Composable
private fun SendButton(onClick: () -> Unit) {
    val c = Sig.colors
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "send")
    Box(
        Modifier.size(46.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(14.dp)).background(c.action)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { TrimioIcon(TrimioIcons.Send, tr("بفرست", "Send"), tint = c.onAction, size = 22.dp) }
}

/** The director's bead, breathing while it waits (still with reduced motion). */
@Composable
private fun BreathingDot() {
    val reduced = Trimio.motion.isReduced
    val breath = rememberInfiniteTransition(label = "director")
    val phase by breath.animateFloat(0f, 1f, infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse), label = "breath")
    DirectorDot(Modifier.graphicsLayer { if (!reduced) (1f + 0.12f * (phase * 2f - 1f)).let { scaleX = it; scaleY = it } })
}

private val COMPOSER_CLEARANCE = 124.dp
private const val HERO_AT = 450
private const val HERO_STAGGER = 110
private const val HERO_DURATION = 900
private const val ROWS_AT = 1080
private const val ROW_STAGGER = 70
private const val ROW_DURATION = 800
private const val MAX_STAGGERED = 5
private const val BREATH_MS = 820
