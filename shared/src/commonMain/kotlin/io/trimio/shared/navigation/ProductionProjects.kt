package io.trimio.shared.navigation

import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.core.data.Clock
import io.trimio.core.data.JudgeVerdict
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.ProjectStatus
import io.trimio.core.data.ProductionRecord
import io.trimio.core.designsystem.image.decodeImage
import io.trimio.core.designsystem.image.encodeJpeg
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.input.VideoFormat
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.feature.editor.EditDrafts
import io.trimio.engine.production.Checkpoints
import io.trimio.engine.production.DirectorChoice
import io.trimio.engine.production.Edit
import io.trimio.engine.production.EditOverrides
import io.trimio.engine.production.Opened
import io.trimio.engine.production.ProducedEdits
import io.trimio.engine.production.ProductionRequest
import io.trimio.engine.production.ProductionRunner
import io.trimio.engine.production.ProductionState
import io.trimio.engine.production.ProductionStatus
import io.trimio.engine.production.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Every production is a project on home. A project is saved when its run starts, follows the run's
 * progress, and on completion keeps the file, a poster frame and the numbers the result screen
 * shows; a failed or interrupted run is kept with its reason, to retry. Variations, revisions and
 * the editor's renders are new versions of the same project; every version's file is kept until the
 * project is deleted, which removes them all.
 *
 * A version the editor made keeps the creator's changes with it ([ProductionRecord.overrides]);
 * changes not yet rendered are kept as the project's draft ([EditDrafts]), so nothing is lost.
 *
 * [folder] is where posters go (`projects/<id>/`); null keeps them in memory only (web previews).
 * [edits] opens a project's current edit (a failed render of the editor's changes is retried on it).
 */
class ProductionProjects(
    private val repository: ProjectRepository,
    private val runner: ProductionRunner,
    private val checkpoints: Checkpoints,
    private val scope: CoroutineScope,
    private val folder: Path?,
    private val edits: ProducedEdits? = null,
) : EditDrafts {
    private val lock = Mutex()
    private var started = false
    private var lastKey: String? = null
    private val runStarted = mutableMapOf<String, TimeMark>()
    /** Run keys the creator watched finish (the result screen was open): not marked as fresh. */
    private val watched = mutableSetOf<String>()
    private val _posters = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    /** Poster frames by project id, as they are loaded or made. */
    val posters: StateFlow<Map<String, ImageBitmap>> = _posters.asStateFlow()

    /** Starts following the runner (once per process); safe to call from every screen that needs it. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.load()
            interruptStale(runner.active.value?.key)
            loadPosters()
            runner.active.collect { s -> if (s != null) lock.withLock { apply(s) } }
        }
    }

    /** A new production from the create screen; returns the project's id. */
    fun create(request: ProductionRequest, title: String, info: MediaInfo?, styleId: String?): String {
        val now = Clock.now()
        val id = "p" + now.toString(BASE36) + Random.nextInt(ID_MIN, ID_MAX)
        val project = Project(
            id = id, title = title, createdAt = now, updatedAt = now,
            input = inputOf(request, info), prompt = request.prompt, styleId = styleId,
            director = if (request.director is DirectorChoice.Cloud) DirectorBackend.Cloud else DirectorBackend.OnDevice,
            status = ProjectStatus.Building,
            production = ProductionRecord(encode(request), request.key, keys = listOf(request.key), styleId = styleId),
        )
        runner.start(request)
        persist(project)
        return id
    }

    /** Runs a failed or interrupted project again, from its saved order. */
    fun retry(project: Project): ProductionRequest? {
        val record = project.production ?: return null
        val request = decode(record.request) ?: return null
        runner.start(request)
        persist(project.copy(status = ProjectStatus.Building, error = null, production = record.copy(key = request.key, progress = 0f)))
        return request
    }

    /** An earlier pipeline's project that never finished, made again by the automatic engine. */
    fun remake(project: Project, persian: Boolean): ProductionRequest {
        val request = ProductionRequest(
            media = project.input.uri.value,
            kind = if (project.isAudioOnly) SourceKind.Audio else SourceKind.Video,
            prompt = project.prompt,
            style = project.styleId?.let(ProductionUi::styleId),
            seed = Random.nextLong(1, Long.MAX_VALUE),
        )
        runner.start(request)
        val record = ProductionRecord(encode(request), request.key, keys = listOf(request.key), styleId = request.style)
        val title = project.title.ifBlank { titleOf(project.prompt, project.isAudioOnly, persian) }
        persist(project.copy(status = ProjectStatus.Building, error = null, outputUri = null, production = record, title = title))
        return request
    }

    /**
     * The next version of a finished project: another variation, or the creator's [feedback]. With
     * the live [edit] the engine reuses its words and reading directly; a stored project replays its
     * order with the next seed, and its saved words and reading skip whisper and the model.
     */
    fun next(project: Project, edit: Edit?, feedback: String? = null): ProductionRequest? {
        val record = project.production ?: return null
        val saved = decode(record.request) ?: return null
        val live = edit?.takeIf { it.request.key == record.key }
        val request = when {
            live != null && feedback != null -> live.request.copy(seed = live.request.seed + 1).also { runner.revise(live, feedback) }
            live != null -> live.request.copy(seed = live.request.seed + 1).also { runner.vary(live) }
            else -> {
                val base = saved.copy(seed = saved.seed + 1, prompt = feedback?.let { saved.prompt.trim() + "\n" + it.trim() } ?: saved.prompt)
                // The words and the reading carry over (the finished edit itself does not: the new run makes its own).
                record.keys.firstNotNullOfOrNull(checkpoints::load)?.let { checkpoints.save(base.key, it.copy(delivery = null)) }
                base.also(runner::start)
            }
        }
        persist(
            project.copy(
                status = ProjectStatus.Building, error = null, updatedAt = Clock.now(),
                // Unsaved editor changes belong to the version they were made on: a new direction starts clean.
                production = record.copy(request = encode(request), key = request.key, keys = (record.keys + request.key).distinct(), progress = 0f, draft = null),
            ),
        )
        return request
    }

    /**
     * The editor's changes rendered as the project's next version: [edit] (any edit of the
     * project's current run) with [overrides], drawn by the runner ([ProductionRunner.retouch]).
     * The changes are kept as the draft until the render is done, then with the version.
     */
    fun retouch(project: Project, edit: Edit, overrides: EditOverrides): ProductionRequest? {
        val record = project.production ?: return null
        if (edit.request.key != record.key) return null
        runner.retouch(edit, overrides)
        persist(project.copy(status = ProjectStatus.Building, error = null, updatedAt = Clock.now(), production = record.copy(progress = 0f, draft = EditOverrides.encode(overrides))))
        return edit.request
    }

    /** The render of the project's unsaved changes again (it failed or was stopped); false when there is nothing to render. */
    suspend fun retouchAgain(project: Project): Boolean {
        val overrides = draft(project.id) ?: return false
        val edit = (edits?.open(project.id) as? Opened.Ready)?.edit ?: return false
        return retouch(repository.get(project.id) ?: project, edit, overrides) != null
    }

    override fun draft(id: String): EditOverrides? {
        val record = repository.get(id)?.production ?: return null
        return EditOverrides.decode(record.draft)?.takeIf { it != (EditOverrides.decode(record.overrides) ?: EditOverrides()) }
    }

    override fun keep(id: String, overrides: EditOverrides?) {
        scope.launch {
            lock.withLock {
                val p = repository.get(id) ?: return@withLock
                val record = p.production ?: return@withLock
                val text = overrides?.takeIf { it != (EditOverrides.decode(record.overrides) ?: EditOverrides()) }?.let(EditOverrides::encode)
                if (text != record.draft) repository.save(p.copy(production = record.copy(draft = text)))
            }
        }
    }

    /** The creator opened the finished edit: it is no longer marked as new. */
    fun markSeen(projectId: String) {
        scope.launch {
            lock.withLock {
                val p = repository.get(projectId) ?: return@withLock
                val record = p.production ?: return@withLock
                watched += record.key
                if (p.status == ProjectStatus.Ready && !record.seen) repository.save(p.copy(production = record.copy(seen = true)))
            }
        }
    }

    /** Deletes the project and its files: renders, poster, saved words. A gallery copy the creator saved stays. */
    fun delete(project: Project) {
        scope.launch {
            lock.withLock {
                // The stored project may have moved on (a version finished) since the row was shown.
                val current = repository.get(project.id) ?: project
                val record = current.production
                if (record != null) {
                    if (runner.active.value?.let { it.key == record.key && !it.finished } == true) runner.cancel()
                    record.keys.forEach(checkpoints::delete)
                    withContext(Dispatchers.Default) {
                        (record.files + listOfNotNull(current.outputUri, record.poster) + current.outputUri?.let(io.trimio.engine.production.YouTubeFiles::paths).orEmpty()).distinct().forEach(::deleteFile)
                        folder?.let { runCatching { SystemFileSystem.delete(Path(it, project.id), mustExist = false) } }
                    }
                }
                _posters.update { it - project.id }
                repository.delete(project.id)
            }
        }
    }

    /** The saved order of a project, for the live build route. */
    fun requestOf(project: Project): ProductionRequest? = project.production?.let { decode(it.request) }

    private fun persist(project: Project) {
        scope.launch {
            lock.withLock {
                repository.save(project)
                // The run may have reported (or even finished) before the project was on disk.
                runner.active.value?.let { apply(it) }
            }
        }
    }

    private suspend fun apply(s: ProductionState) {
        if (s.key != lastKey) {
            lastKey = s.key
            interruptStale(s.key)
        }
        val project = repository.projects.value.firstOrNull { it.production?.key == s.key } ?: return
        val record = project.production ?: return
        if (s.status == ProductionStatus.Running) runStarted.getOrPut(s.key) { TimeSource.Monotonic.markNow() }
        when (s.status) {
            ProductionStatus.Running -> if (project.status != ProjectStatus.Building || s.progress - record.progress >= PROGRESS_STEP) {
                repository.save(project.copy(status = ProjectStatus.Building, production = record.copy(progress = s.progress)))
            }
            ProductionStatus.Done -> if (project.status != ProjectStatus.Ready) finish(project, record, s)
            ProductionStatus.Failed, ProductionStatus.Cancelled -> if (project.status == ProjectStatus.Building) {
                val error = if (s.status == ProductionStatus.Cancelled) CANCELLED else s.error ?: "failed"
                repository.save(stopped(project, record, error))
            }
        }
    }

    /** A run that ended without a file: a project with an earlier version keeps it; a first run is failed. */
    private fun stopped(project: Project, record: ProductionRecord, error: String): Project =
        if (project.outputUri != null) project.copy(status = ProjectStatus.Ready, error = error, production = record.copy(progress = 1f))
        else project.copy(status = ProjectStatus.Failed, error = error)

    /**
     * Projects still marked as building whose run is gone (another run replaced it, or the process
     * died). If the system restarts that run later, its first report marks the project building again.
     */
    private suspend fun interruptStale(activeKey: String?) {
        repository.projects.value
            .filter { it.status == ProjectStatus.Building && it.production != null && it.production?.key != activeKey }
            .forEach { p -> repository.save(stopped(p, p.production!!, INTERRUPTED)) }
    }

    private suspend fun finish(project: Project, record: ProductionRecord, s: ProductionState) {
        val edit = s.edit
        val output = s.output ?: edit?.output
        val compiled = edit?.production?.compiled
        val poster = s.previews.lastOrNull()
        val posterPath = poster?.let { savePoster(project.id, s.key, it) }
        if (poster != null) _posters.update { it + (project.id to poster) }
        val seconds = runStarted.remove(s.key)?.elapsedNow()?.inWholeSeconds?.toInt() ?: record.buildSeconds
        // The version keeps the creator's own changes (the editor's render); a new direction has none.
        val overrides = edit?.overrides?.takeUnless { it.isEmpty }
        repository.save(
            project.copy(
                status = ProjectStatus.Ready, updatedAt = Clock.now(), outputUri = output, error = null,
                production = record.copy(
                    // Earlier versions' files stay (the editor and export may still open them) until the project is deleted.
                    files = (record.files + listOfNotNull(output, posterPath)).distinct(),
                    progress = 1f, poster = posterPath ?: record.poster,
                    styleId = overrides?.style ?: edit?.request?.style ?: edit?.production?.score?.look ?: record.styleId,
                    overrides = overrides?.let(EditOverrides::encode),
                    // The draft is now this version, or (a new direction) belonged to the one before.
                    draft = null,
                    durationMs = compiled?.let { (it.composition.duration * MS).toLong() } ?: record.durationMs,
                    buildSeconds = seconds,
                    version = if (project.outputUri == null) 1 else record.version + 1,
                    onDevice = edit?.request?.director !is DirectorChoice.Cloud,
                    scenes = edit?.production?.score?.scenes?.size?.coerceAtLeast(1) ?: record.scenes,
                    words = edit?.transcript?.words?.size ?: record.words,
                    effects = compiled?.sfx?.size ?: record.effects,
                    judges = s.panel?.scores?.map { (judge, score) -> JudgeVerdict(judge, score) } ?: record.judges,
                    findings = s.panel?.findings ?: record.findings,
                    seen = s.key in watched,
                ),
            ),
        )
    }

    private suspend fun savePoster(id: String, key: String, image: ImageBitmap): String? {
        val dir = folder?.let { Path(it, id) } ?: return null
        val bytes = image.encodeJpeg() ?: return null
        return withContext(Dispatchers.Default) {
            runCatching {
                SystemFileSystem.createDirectories(dir)
                val path = Path(dir, "poster-" + key.filter { it.isLetterOrDigit() || it == '-' } + ".jpg")
                SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
                path.toString()
            }.getOrNull()
        }
    }

    private suspend fun loadPosters() {
        val found = withContext(Dispatchers.Default) {
            repository.projects.value.mapNotNull { p ->
                val file = p.production?.poster ?: return@mapNotNull null
                runCatching { SystemFileSystem.source(Path(file)).buffered().use { it.readByteArray() } }.getOrNull()?.let(::decodeImage)?.let { p.id to it }
            }
        }
        _posters.update { found.toMap() + it }
    }

    /** Only the app's own files: published copies (content://, the gallery) are the creator's. */
    private fun deleteFile(path: String) {
        if (path.contains("://")) return
        runCatching { SystemFileSystem.delete(Path(path), mustExist = false) }
    }

    private fun inputOf(request: ProductionRequest, info: MediaInfo?): InputSource {
        val uri = MediaUri(request.media)
        val video = info?.video
        return if (request.kind == SourceKind.Video) {
            InputSource.Video(uri, info?.durationMs ?: 0, video ?: VideoFormat(PORTRAIT_W, PORTRAIT_H, FPS))
        } else {
            InputSource.AudioOnly(uri, info?.durationMs ?: 0, CanvasSpec())
        }
    }

    companion object {
        /** The run was stopped by the creator. */
        const val CANCELLED = "cancelled"

        /** The app was closed, or another production replaced this one, before it finished. */
        const val INTERRUPTED = "interrupted"

        private const val PROGRESS_STEP = 0.02f
        private const val MS = 1000f
        private const val BASE36 = 36
        private const val ID_MIN = 1_000
        private const val ID_MAX = 9_999
        private const val PORTRAIT_W = 1080
        private const val PORTRAIT_H = 1920
        private const val FPS = 30f
        private const val TITLE_WORDS = 5
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(request: ProductionRequest): String = json.encodeToString(ProductionRequest.serializer(), request)

        fun decode(text: String): ProductionRequest? = runCatching { json.decodeFromString(ProductionRequest.serializer(), text) }.getOrNull()

        /** A project's name from the brief's first words, or a plain name when the brief is empty. */
        fun titleOf(prompt: String, audioOnly: Boolean, persian: Boolean): String {
            val words = prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                .split(Regex("\\s+")).map { it.trim('،', ',', '؛', ';', '.', ':', '«', '»', '"') }.filter { it.isNotBlank() }
            return when {
                words.isEmpty() && audioOnly -> if (persian) "صدای جدید" else "New voice edit"
                words.isEmpty() -> if (persian) "ویدیوی جدید" else "New video"
                else -> words.take(TITLE_WORDS).joinToString(" ") + if (words.size > TITLE_WORDS) "…" else ""
            }
        }
    }
}
