package io.trimio.shared.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.MediaKind
import io.trimio.core.data.Sharer
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.model.text.Language
import io.trimio.engine.production.AttachedMedia
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

/** Saves a finished MP4 to the system gallery; platforms that have one bind it (otherwise the file is shared). */
fun interface GallerySaver {
    suspend fun save(path: String, title: String): Boolean
}

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
 * screens stay pure functions of their models.
 */
@Composable
internal fun ProduceEntry(route: Route.Produce, onResult: (seconds: Int) -> Unit, onClose: () -> Unit, onModels: () -> Unit) {
    val runner = koinInject<ProductionRunner>()
    val active by runner.active.collectAsStateWithLifecycle()
    val persian = Trimio.language == Language.Persian
    val clock = remember(route) { TimeSource.Monotonic.markNow() }
    val seconds = remember(route) { mutableStateMapOf<BuildStage, Float>() }
    var stageStart by remember(route) { mutableStateOf(0f) }
    var current by remember(route) { mutableStateOf(BuildStage.Listen) }
    val s = active?.takeIf { it.key == route.request.key }
    LaunchedEffect(route) { if (runner.active.value?.key != route.request.key) runner.start(route.request) }
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
    when (s?.status) {
        ProductionStatus.Done -> LaunchedEffect(s.key) { onResult(elapsed.toInt()) }
        ProductionStatus.Failed -> {
            val failure = BuildFailures.of(s.error, current, persian)
            val missingModel = failure.primary == BuildFailures.of("no model", current, persian).primary
            BuildFailureScreen(
                failure,
                onOption = { i -> if (i == 0) (if (missingModel) onModels() else runner.start(route.request)) else onClose() },
                onPrimary = { if (missingModel) onModels() else runner.start(route.request) },
                onClose = onClose,
            )
        }
        ProductionStatus.Cancelled -> BuildFailureScreen(
            BuildFailures.cancelled(current, persian),
            onOption = { i -> if (i == 0) runner.start(route.request) else onClose() },
            onPrimary = { runner.start(route.request) },
            onClose = onClose,
        )
        else -> LiveBuildScreen(
            s?.let { ProductionUi.live(it, route.styleName, seconds.toMap(), elapsed, persian) } ?: LiveBuild(styleName = route.styleName),
            onCancel = runner::cancel,
        )
    }
}

/** The finished edit: feedback and variations start a new run on the same runner. */
@Composable
internal fun ResultEntry(route: Route.Result, onProduce: (ProductionRequest) -> Unit, onClose: () -> Unit) {
    val runner = koinInject<ProductionRunner>()
    val active by runner.active.collectAsStateWithLifecycle()
    val koin = getKoin()
    val sharer = remember { koin.getOrNull<Sharer>() }
    val saver = remember { koin.getOrNull<GallerySaver>() }
    var saved by remember(route) { mutableStateOf(false) }
    val s = active
    val edit = s?.edit
    val result = s?.let { ProductionUi.result(it, route.buildSeconds, route.version, route.styleName, saved) }
    if (edit == null || result == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(saving) {
        if (saving) {
            saved = saver?.save(edit.output, result.title) ?: run { sharer?.share(edit.output, result.title); false }
            saving = false
        }
    }
    ResultScreen(
        result,
        ResultActions(
            onClose = onClose,
            onFeedback = { text ->
                runner.revise(edit, text)
                onProduce(edit.request.copy(seed = edit.request.seed + 1))
            },
            onVariation = {
                runner.vary(edit)
                onProduce(edit.request.copy(seed = edit.request.seed + 1))
            },
            onSave = { saving = true },
            onShare = { sharer?.share(edit.output, result.title) },
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
