package io.trimio.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json

/** Where projects are persisted. */
interface ProjectStore {
    suspend fun loadAll(): List<Project>
    suspend fun save(project: Project)
    suspend fun delete(id: String)
}

/** One JSON file per project in [directory]; written to a temp file and renamed, so a crash never corrupts one. */
class FileProjectStore(private val directory: Path, private val fs: FileSystem = SystemFileSystem) : ProjectStore {
    override suspend fun loadAll(): List<Project> = withContext(Dispatchers.Default) {
        if (!fs.exists(directory)) return@withContext emptyList()
        fs.list(directory).filter { it.name.endsWith(".json") }.mapNotNull { path ->
            // A file from a newer app version or a damaged one is skipped, never fatal.
            runCatching { DataJson.decodeFromString(Project.serializer(), fs.source(path).buffered().use { it.readString() }) }.getOrNull()
        }
    }

    override suspend fun save(project: Project) = withContext(Dispatchers.Default) {
        fs.createDirectories(directory)
        val tmp = Path(directory, "${project.id}.json.tmp")
        fs.sink(tmp).buffered().use { it.writeString(DataJson.encodeToString(Project.serializer(), project)) }
        fs.atomicMove(tmp, Path(directory, "${project.id}.json"))
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        fs.delete(Path(directory, "$id.json"), mustExist = false)
    }
}

class MemoryProjectStore : ProjectStore {
    private val items = mutableMapOf<String, Project>()
    override suspend fun loadAll() = items.values.toList()
    override suspend fun save(project: Project) { items[project.id] = project }
    override suspend fun delete(id: String) { items.remove(id) }
}

/** Projects as observable state, newest first. */
class ProjectRepository(private val store: ProjectStore) {
    private val mutex = Mutex()
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()
    private var loaded = false

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        _projects.value = store.loadAll().sortedByDescending { it.updatedAt }
        loaded = true
    }

    fun get(id: String): Project? = _projects.value.firstOrNull { it.id == id }

    suspend fun save(project: Project) = mutex.withLock {
        store.save(project)
        _projects.update { list -> (listOf(project) + list.filter { it.id != project.id }).sortedByDescending { it.updatedAt } }
    }

    suspend fun update(id: String, change: (Project) -> Project): Project? {
        val current = get(id) ?: return null
        val next = change(current).copy(updatedAt = Clock.now())
        save(next)
        return next
    }

    suspend fun delete(id: String) = mutex.withLock {
        store.delete(id)
        _projects.update { list -> list.filter { it.id != id } }
    }
}

internal val DataJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Wall-clock millis, wrapped so tests can stay deterministic where needed. */
object Clock {
    fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
