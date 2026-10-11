@file:Suppress("MatchingDeclarationName") // The production flow's entries, and the models they build (ProductionUi).

package io.trimio.shared.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
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
import io.trimio.feature.stream.JudgeScore
import io.trimio.feature.stream.LiveBuild
import io.trimio.feature.stream.LiveBuildScreen
import io.trimio.feature.stream.ResultActions
import io.trimio.feature.stream.ResultScreen
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
    )

    fun styleName(id: String?, persian: Boolean): String? = ProductionStyles.of(id)?.let { if (persian) it.nameFa else it.nameEn }

    /** A style language id, or an earlier gallery pack id ("brutalism"), as the engine's style id. */
    fun styleId(id: String): String? = ProductionStyles.all.firstOrNull { it.id == id || it.pack == id }?.id

    /** A stored project's finished edit, as the result screen shows it (null until it has a file). */
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

    fun live(s: ProductionState, styleName: String?, seconds: Map<BuildStage, Float>, elapsed: Float, persian: Boolean): LiveBuild {
        val stage = stage(s)
        val (done, total) = frames(s.step) ?: (0 to 0)
        val eta = if (s.progress > MIN_PROGRESS_FOR_ETA) (elapsed * (1 - s.progress) / s.progress).toInt().coerceAtLeast(1) else null
        return LiveBuild(
            styleName = styleName, progress = s.progress, stage = stage, stageSeconds = seconds, previews = s.previews,
            framesDone = done, framesTotal = total, etaSeconds = eta,
            directorNote = s.report.lastOrNull()?.takeIf { stage >= BuildStage.Jury } ?: note(stage, persian),
            judges = judges(s),
        )
    }

    fun result(s: ProductionState, seconds: Int, version: Int, styleName: String?, saved: Boolean): BuildResult? {
        val edit = s.edit ?: return null
        val compiled = edit.production.compiled
        return BuildResult(
            title = edit.request.prompt.take(TITLE_LENGTH),
            styleName = styleName,
            durationMs = (compiled.composition.duration * MS).toLong(),
            buildSeconds = seconds,
            onDevice = edit.request.director !is io.trimio.engine.production.DirectorChoice.Cloud,
            scenes = edit.production.score.scenes.size.coerceAtLeast(1),
            words = edit.transcript.words.size,
            effects = compiled.sfx.size,
            lufs = MASTER_LUFS,
            judges = judges(s),
            findings = s.panel?.findings.orEmpty(),
            poster = s.previews.lastOrNull(),
            version = version,
            saved = saved,
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
    val key = project?.production?.key ?: route.request.key
    val s = active?.takeIf { it.key == key }
    val retry: () -> Unit = { if (project != null) productions.retry(project) else runner.start(route.request) }
    LaunchedEffect(route) {
        if (runner.active.value?.key != key && project?.status != ProjectStatus.Failed) retry()
    }
    val elapsed = clock.elapsedNow().inWholeMilliseconds / 1000f
    val stageNow = s?.let(ProductionUi::stage)
    LaunchedEffect(stageNow) {
        if (stageNow != null && stageNow != current) {
            val now = clock.elapsedNow().inWholeMilliseconds / 1000f
            seconds[current] = now - stageStart
            stageStart = now
            current = stageNow
        }
    }
    val stopped = when {
        s != null -> s.status.takeIf { it == ProductionStatus.Failed || it == ProductionStatus.Cancelled }?.let { it to s.error }
        project?.status == ProjectStatus.Failed -> stoppedAs(project.error) to project.error
        else -> null
    }
    when {
        s?.status == ProductionStatus.Done -> LaunchedEffect(s.key) { onResult(elapsed.toInt()) }
        stopped?.first == ProductionStatus.Failed -> {
            val failure = BuildFailures.of(stopped.second, current, persian)
            val missingModel = failure.primary == BuildFailures.of("no model", current, persian).primary
            BuildFailureScreen(
                failure,
                onOption = { i -> if (i == 0) (if (missingModel) onModels() else retry()) else onClose() },
                onPrimary = { if (missingModel) onModels() else retry() },
                onClose = onClose,
            )
        }
        stopped != null -> BuildFailureScreen(
            BuildFailures.cancelled(current, persian),
            onOption = { i -> if (i == 0) retry() else onClose() },
            onPrimary = { retry() },
            onClose = onClose,
        )
        else -> LiveBuildScreen(
            s?.let { ProductionUi.live(it, route.styleName, seconds.toMap(), elapsed, persian) } ?: LiveBuild(styleName = route.styleName),
            onCancel = runner::cancel,
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
    val result = live?.let { ProductionUi.result(it, route.buildSeconds, route.version, route.styleName, saved) }?.let { r -> project?.let { r.copy(title = it.title) } ?: r }
        ?: project?.let { ProductionUi.result(it, posters[it.id], persian, saved) }
    val output = edit?.output ?: project?.outputUri
    if (result == null || output == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    LaunchedEffect(project?.id) { project?.let { productions.markSeen(it.id) } }
    val edits = koinInject<ProducedEdits>()
    val exportId = (project?.id ?: edit?.request?.key)?.takeIf { edits.find(it) != null }
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
    ResultScreen(
        result,
        ResultActions(
            onClose = onClose,
            onFeedback = { text -> next(text) },
            onVariation = { next(null) },
            onSave = { saving = true },
            onShare = { sharer?.share(output, result.title) },
            // Other frames, sizes and rates, captions and the pro package: while the edit itself is held.
            onExport = exportId?.let { id -> { onExport(id) } },
            // The editor works on the held edit when the director's workbench came with it.
            onEdit = exportId?.takeIf { edits.find(it)?.editable == true }?.let { id -> { onEdit(id) } },
        ),
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
