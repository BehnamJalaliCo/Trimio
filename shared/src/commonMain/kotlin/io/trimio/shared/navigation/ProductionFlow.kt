@file:Suppress("MatchingDeclarationName") // The production flow's entries, and the models they build (ProductionUi).

package io.trimio.shared.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.GallerySaver
import io.trimio.core.data.MediaKind
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.ProjectStatus
import io.trimio.core.data.Sharer
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.model.text.Language
import io.trimio.engine.production.AttachedMedia
import io.trimio.engine.production.DirectorChoice
import io.trimio.engine.production.ProducedEdits
import io.trimio.engine.production.ProductionRequest
import io.trimio.engine.production.ProductionRunner
import io.trimio.engine.production.ProductionState
import io.trimio.engine.production.ProductionStatus
import io.trimio.engine.production.ProductionStyles
import io.trimio.engine.production.SourceKind
import io.trimio.feature.create.CreateState
import io.trimio.feature.create.EnginePrompt
import io.trimio.feature.stream.BuildFailureScreen
import io.trimio.feature.stream.BuildFailures
import io.trimio.feature.stream.BuildResult
import io.trimio.feature.stream.BuildStage
import io.trimio.feature.stream.BuildStall
import io.trimio.feature.stream.JudgeScore
import io.trimio.feature.stream.LiveBuild
import io.trimio.feature.stream.LiveBuildScreen
import io.trimio.feature.stream.ResultActions
import io.trimio.feature.stream.ResultScreen
import io.trimio.feature.stream.VideoPlayers
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * The glue between the screens' models (feature/create, feature/stream) and the production
 * service: requests from the brief, live and result models from [ProductionState].
 */
object ProductionUi {
    /** The create screen's brief as an order. Every new order gets its own seed: each run is a different edit. */
    fun request(uri: String, s: CreateState, persian: Boolean, seed: Long = Random.nextLong(1, Long.MAX_VALUE)) = ProductionRequest(
        media = uri,
        kind = if (s.kind == MediaKind.Audio) SourceKind.Audio else SourceKind.Video,
        prompt = s.prompt,
        glossary = EnginePrompt.compose("", s.glossary, emptyList(), persian),
        assets = s.attachments.map { AttachedMedia(it.uri, it.caption.trim().ifEmpty { null }) },
        style = s.styleId,
        seed = seed,
        // The chosen speed's installed director; without one the engine picks (the settings' choice or what fits).
        director = s.speedModel?.let { io.trimio.engine.production.DirectorChoice.Local(it) } ?: io.trimio.engine.production.DirectorChoice.Auto,
        edit = when (s.edit) {
            io.trimio.feature.create.EditChoice.Reel -> io.trimio.engine.production.EditKind.Reel
            io.trimio.feature.create.EditChoice.YouTube -> io.trimio.engine.production.EditKind.YouTube
            io.trimio.feature.create.EditChoice.YouTubePro -> io.trimio.engine.production.EditKind.YouTubePro
        },
    )

    fun styleName(id: String?, persian: Boolean): String? = ProductionStyles.of(id)?.let { if (persian) it.nameFa else it.nameEn }

    /** A style language id, or an earlier gallery pack id ("brutalism"), as the engine's style id. */
    fun styleId(id: String): String? = ProductionStyles.all.firstOrNull { it.id == id || it.pack == id }?.id

    /** A stored project's finished edit (its current version), as the result screen shows it (null until it has a file). */
    fun result(project: Project, poster: ImageBitmap?, persian: Boolean, saved: Boolean): BuildResult? {
        val record = project.production ?: return null
        if (project.outputUri == null) return null
        return BuildResult(
            title = project.title,
            styleName = styleName(record.styleId, persian),
            durationMs = record.durationMs,
            buildSeconds = record.buildSeconds,
            onDevice = record.onDevice,
            scenes = record.scenes.coerceAtLeast(1),
            words = record.words,
            effects = record.effects,
            lufs = MASTER_LUFS,
            judges = record.judges.map { JudgeScore(it.judge, it.score) }.ifEmpty { JudgeScore.Panel },
            findings = record.findings,
            poster = poster,
            version = record.version,
            saved = saved,
            edited = record.overrides != null,
            youtube = project.outputUri?.let(io.trimio.engine.production.YouTubeFiles::summary),
        )
    }

    fun stage(s: ProductionState): BuildStage = BuildStage.of(s.stage.name, s.step)

    fun judges(s: ProductionState): List<JudgeScore> =
        s.panel?.scores?.map { (id, score) -> JudgeScore(id, score) } ?: JudgeScore.Panel

    /** "render 120/450" → (120, 450). */
    fun frames(step: String): Pair<Int, Int>? {
        val m = RENDER.find(step) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** "understand 12/48" → (12, 48): lines the director has read. */
    fun lines(step: String): Pair<Int, Int>? {
        val m = UNDERSTAND.find(step) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    fun live(s: ProductionState, styleName: String?, seconds: Map<BuildStage, Float>, elapsed: Float, persian: Boolean): LiveBuild {
        val stage = stage(s)
        val (done, total) = frames(s.step) ?: (0 to 0)
        val (read, lines) = lines(s.step) ?: (0 to 0)
        val eta = if (s.progress > MIN_PROGRESS_FOR_ETA) (elapsed * (1 - s.progress) / s.progress).toInt().coerceAtLeast(1) else null
        return LiveBuild(
            styleName = styleName, progress = s.progress, stage = stage, stageSeconds = seconds, previews = s.previews,
            framesDone = done, framesTotal = total, linesDone = read, linesTotal = lines, etaSeconds = eta,
            directorNote = s.report.lastOrNull()?.takeIf { stage >= BuildStage.Jury } ?: note(stage, persian),
            judges = judges(s),
            rulesOnly = rulesOnly(s),
            stall = s.stall?.let { BuildStall(it.minutes, it.step, stage) },
        )
    }

    /** Why no model directed the run (its report says «rules (no model)…»), or null when one did. */
    private fun rulesOnly(s: ProductionState): String? = s.technical?.models
        ?.firstOrNull { it.name == io.trimio.engine.production.diagnostics.RunReport.DIRECTOR }?.value?.takeIf { it.startsWith("rules") }

    fun result(s: ProductionState, seconds: Int, version: Int, styleName: String?, saved: Boolean, persian: Boolean): BuildResult? {
        val edit = s.edit ?: return null
        val compiled = edit.production.compiled
        return BuildResult(
            title = edit.request.prompt.take(TITLE_LENGTH),
            // The creator's own style (the editor) names the version.
            styleName = edit.overrides.style?.let { styleName(it, persian) } ?: styleName,
            durationMs = (compiled.composition.duration * MS).toLong(),
            buildSeconds = seconds,
            onDevice = edit.request.director !is io.trimio.engine.production.DirectorChoice.Cloud,
            scenes = edit.production.score.scenes.size.coerceAtLeast(1),
            words = edit.transcript.words.size,
            effects = compiled.sfx.size,
            lufs = MASTER_LUFS,
            judges = judges(s),
            rulesOnly = rulesOnly(s),
            findings = s.panel?.findings.orEmpty(),
            poster = s.previews.lastOrNull(),
            version = version,
            saved = saved,
            edited = !edit.overrides.isEmpty,
        )
    }

    /** The director's line for a stage while it has nothing more specific to say. */
    private fun note(stage: BuildStage, persian: Boolean): String = when (stage) {
        BuildStage.Listen -> if (persian) "دارم گوش می‌دهم و کلمه‌ها را با املای درست می‌نویسم." else "Listening, and writing every word with the right spelling."
        BuildStage.Understand -> if (persian) "می‌فهمم حرف اصلی چیست، هوک کجاست و کدام لحظه‌ها مهم‌اند." else "Finding the point, the hook and the moments that matter."
        BuildStage.Plan -> if (persian) "صحنه‌ها را می‌چینم: تیتر، المان‌ها و برش‌ها درست روی گفتار." else "Laying out scenes: titles, elements and cuts right on the speech."
        BuildStage.Jury -> if (persian) "ده داور ادیت را می‌بینند؛ تا همه ۱۰ ندهند تحویل نمی‌دهم." else "Ten judges watch the edit; it ships only when all give 10."
        BuildStage.Sound -> if (persian) "موسیقی و افکت‌ها را روی ضرب می‌نشانم و صدا را مستر می‌کنم." else "Setting music and effects on the beat, and mastering the sound."
        BuildStage.Render -> if (persian) "فریم‌ها را با کیفیت نهایی رندر می‌کنم." else "Rendering every frame at full quality."
    }

    private val RENDER = Regex("render (\\d+)/(\\d+)")
    private val UNDERSTAND = Regex("understand (\\d+)/(\\d+)")
    private const val MIN_PROGRESS_FOR_ETA = 0.05f
    private const val TITLE_LENGTH = 48
    private const val MS = 1000f

    /** Every edit is mastered to this loudness (phase 5 quality bar). */
    private const val MASTER_LUFS = -14f
}

/**
 * The live build view over the app's production runner, its honest failure (and cancel) screens,
 * and the hand-over to the result. Stage times and the elapsed clock are kept here, so the
 * screens stay pure functions of their models. Opened from home for a project whose run is not
 * active, it shows the stored failure (or interruption) with a retry, or restarts the run.
 */
@Composable
internal fun ProduceEntry(route: Route.Produce, onResult: (seconds: Int) -> Unit, onClose: () -> Unit, onModels: () -> Unit) {
    val runner = koinInject<ProductionRunner>()
    val productions = koinInject<ProductionProjects>()
    val stored by koinInject<ProjectRepository>().projects.collectAsStateWithLifecycle()
    val project = route.projectId?.let { id -> stored.firstOrNull { it.id == id } }
    val active by runner.active.collectAsStateWithLifecycle()
    val persian = Trimio.language == Language.Persian
    val clock = remember(route) { TimeSource.Monotonic.markNow() }
    val seconds = remember(route) { mutableStateMapOf<BuildStage, Float>() }
    var stageStart by remember(route) { mutableStateOf(0f) }
    var current by remember(route) { mutableStateOf(BuildStage.Listen) }
    var reported by remember(route) { mutableStateOf(false) }
    val key = project?.production?.key ?: route.request.key
    val s = active?.takeIf { it.key == key }
    val scope = rememberCoroutineScope()
    val retry: () -> Unit = {
        when {
            // The editor's changes are rendered again (they are kept as the project's draft until done).
            route.retouch && project != null -> scope.launch { productions.retouchAgain(project) }
            project != null -> productions.retry(project)
            else -> runner.start(route.request)
        }
    }
    LaunchedEffect(route) {
        if (runner.active.value?.key != key && project?.status != ProjectStatus.Failed) retry()
    }
    val elapsed = clock.elapsedNow().inWholeMilliseconds / 1000f
    val stageNow = s?.let(ProductionUi::stage)
    LaunchedEffect(stageNow) {
        if (stageNow == null) return@LaunchedEffect
        if (stageNow != current) {
            val now = clock.elapsedNow().inWholeMilliseconds / 1000f
            // A stage the run never reported took no time (a render of the editor's changes starts at the render).
            if (reported) seconds[current] = now - stageStart
            stageStart = now
            current = stageNow
        }
        reported = true
    }
    val stopped = when {
        s != null -> s.status.takeIf { it == ProductionStatus.Failed || it == ProductionStatus.Cancelled }?.let { it to s.error }
        project?.status == ProjectStatus.Failed -> stoppedAs(project.error) to project.error
        else -> null
    }
    // «جزئیات فنی»: the run's technical report, live or kept with the project.
    var technical by remember(route) { mutableStateOf(false) }
    val report = rememberReport(key, s?.technical)
    val details: (() -> Unit)? = report?.let { { technical = true } }
    val cloud = koinInject<io.trimio.engine.llm.CloudModels>()
    // The order this screen runs: the project's saved one, else the route's.
    val order = project?.let(productions::requestOf) ?: route.request
    when {
        technical && report != null -> TechnicalOverlay(report, persian) { technical = false }
        s?.status == ProductionStatus.Done -> LaunchedEffect(s.key) { onResult(elapsed.toInt()) }
        stopped?.first == ProductionStatus.Failed && ProductionState.diedIn(stopped.second) != null -> BuildFailureScreen(
            BuildFailures.of(stopped.second, current, persian),
            // A smaller model, the cloud (when a key is connected, else the models), or the rules.
            onOption = { i ->
                when (i) {
                    0 -> runner.lighter(order)
                    1 -> scope.launch { if (cloud.firstAvailable() != null) runner.redirect(DirectorChoice.Cloud, order) else onModels() }
                    else -> runner.redirect(DirectorChoice.Rules, order)
                }
            },
            onPrimary = { runner.lighter(order) },
            onClose = onClose,
            onDetails = details,
        )
        stopped?.first == ProductionStatus.Failed -> {
            val failure = BuildFailures.of(stopped.second, current, persian)
            val missingModel = failure.primary == BuildFailures.of("no model", current, persian).primary
            BuildFailureScreen(
                failure,
                onOption = { i -> if (i == 0) (if (missingModel) onModels() else retry()) else onClose() },
                onPrimary = { if (missingModel) onModels() else retry() },
                onClose = onClose,
                onDetails = details,
            )
        }
        stopped != null -> BuildFailureScreen(
            BuildFailures.cancelled(current, persian),
            onOption = { i -> if (i == 0) retry() else onClose() },
            onPrimary = { retry() },
            onClose = onClose,
            onDetails = details,
        )
        else -> LiveBuildScreen(
            s?.let { ProductionUi.live(it, route.styleName, seconds.toMap(), elapsed, persian) } ?: LiveBuild(styleName = route.styleName),
            onCancel = runner::cancel,
            onDetails = details,
            onWait = runner::keepWaiting,
            onLighter = { runner.lighter() },
        )
    }
}

/** A stored run that ended without a file: stopped (by the creator, or the app closing) or failed. */
private fun stoppedAs(error: String?): ProductionStatus =
    if (error == ProductionProjects.CANCELLED || error == ProductionProjects.INTERRUPTED) ProductionStatus.Cancelled else ProductionStatus.Failed

/**
 * The finished edit: the run that just finished, or a project opened from home (its saved file,
 * poster and numbers). Feedback and variations start the project's next version on the runner.
 */
@Composable
internal fun ResultEntry(
    route: Route.Result,
    onProduce: (request: ProductionRequest, projectId: String?, version: Int) -> Unit,
    onClose: () -> Unit,
    onExport: (id: String) -> Unit = {},
    onEdit: (id: String) -> Unit = {},
) {
    val runner = koinInject<ProductionRunner>()
    val productions = koinInject<ProductionProjects>()
    val stored by koinInject<ProjectRepository>().projects.collectAsStateWithLifecycle()
    val posters by productions.posters.collectAsStateWithLifecycle()
    val project = route.projectId?.let { id -> stored.firstOrNull { it.id == id } }
    val active by runner.active.collectAsStateWithLifecycle()
    val koin = getKoin()
    val sharer = remember { koin.getOrNull<Sharer>() }
    val saver = remember { koin.getOrNull<GallerySaver>() }
    val persian = Trimio.language == Language.Persian
    var saved by remember(route) { mutableStateOf(false) }
    // The live edit when it is this project's current version (or there is no project).
    val live = active?.takeIf { s -> s.edit != null && project?.production?.key.let { it == null || it == s.key } }
    val edit = live?.edit
    val result = live?.let { ProductionUi.result(it, route.buildSeconds, route.version, route.styleName, saved, persian) }?.let { r -> project?.let { r.copy(title = it.title) } ?: r }
        ?: project?.let { ProductionUi.result(it, posters[it.id], persian, saved) }
    val output = edit?.output ?: project?.outputUri
    if (result == null || output == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    LaunchedEffect(project?.id) { project?.let { productions.markSeen(it.id) } }
    // The finished file plays in the app where the platform has a player; it stops when the screen goes or the app leaves the front.
    val video = remember(output) { koin.getOrNull<VideoPlayers>()?.let { runCatching { it.open(output) }.getOrNull() } }
    DisposableEffect(video) { onDispose { video?.close() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { video?.pause() }
    val leaving: (() -> Unit) -> () -> Unit = { go -> { video?.pause(); go() } }
    val edits = koinInject<ProducedEdits>()
    // The editor and the export open the edit held in memory, or make it again from what its run kept (after a restart).
    val editId = project?.id ?: edit?.request?.key
    val record = project?.production
    val openable by produceState(editId != null && edits.find(editId) != null, editId, record?.key, record?.overrides, project?.status, edit) {
        value = editId != null && edits.openable(editId)
    }
    val exportId = editId?.takeIf { openable }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(saving) {
        if (saving) {
            saved = saver?.save(output, result.title) ?: run { sharer?.share(output, result.title); false }
            saving = false
        }
    }
    fun next(feedback: String?) {
        val request = when {
            project != null -> productions.next(project, edit, feedback)
            edit != null -> edit.request.copy(seed = edit.request.seed + 1).also { if (feedback != null) runner.revise(edit, feedback) else runner.vary(edit) }
            else -> null
        }
        request?.let { onProduce(it, project?.id, result.version + 1) }
    }
    // «جزئیات فنی»: this version's technical report (live, or kept with the project).
    var technical by remember(route) { mutableStateOf(false) }
    val report = rememberReport(live?.key ?: project?.production?.key, live?.technical)
    if (technical && report != null) {
        TechnicalOverlay(report, persian) { technical = false }
        return
    }
    ResultScreen(
        result,
        ResultActions(
            onClose = leaving(onClose),
            onFeedback = { text -> video?.pause(); next(text) },
            onVariation = leaving { next(null) },
            onSave = { saving = true },
            onShare = leaving { sharer?.share(output, result.title) },
            // Other frames, sizes and rates, captions and the pro package: while the edit itself is held.
            onExport = exportId?.let { id -> leaving { onExport(id) } },
            // The editor works on the edit when the director's workbench came with it (a kept edit always has it).
            onEdit = exportId?.takeIf { edits.find(it)?.editable != false }?.let { id -> leaving { onEdit(id) } },
            onDetails = report?.let { leaving { technical = true } },
        ),
        video = video,
    )
}

/** Attachments through the system media chooser: clips as video, pictures and logos as images. */
fun mediaAttachments(picker: io.trimio.core.data.MediaPicker) = io.trimio.feature.create.AttachmentPicker { kind ->
    val media = if (kind == io.trimio.feature.create.AttachmentKind.Clip) MediaKind.Video else MediaKind.Image
    picker.pick(media)?.let { uri ->
        val name = uri.value.substringAfterLast('/').substringAfterLast("%2F").ifEmpty { kind.name.lowercase() }
        io.trimio.feature.create.Attachment(id = uri.value, kind = kind, uri = uri.value, name = name)
    }
}
