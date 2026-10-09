package io.trimio.core.api

import io.trimio.engine.styles.InstalledPackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/** Downloaded style packs, one signed envelope per file; verified again every time they load. */
class FilePackStore(private val directory: Path, private val fs: FileSystem = SystemFileSystem) : InstalledPackStore {
    override suspend fun readAll(): List<String> = withContext(Dispatchers.Default) {
        if (!fs.exists(directory)) return@withContext emptyList()
        fs.list(directory).filter { it.name.endsWith(".json") }.mapNotNull { path ->
            runCatching { fs.source(path).buffered().use { it.readString() } }.getOrNull()
        }
    }

    override suspend fun write(id: String, envelope: String) = withContext(Dispatchers.Default) {
        fs.createDirectories(directory)
        val tmp = Path(directory, "$id.json.tmp")
        fs.sink(tmp).buffered().use { it.writeString(envelope) }
        fs.atomicMove(tmp, Path(directory, "$id.json"))
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        fs.delete(Path(directory, "$id.json"), mustExist = false)
    }
}

/** In-memory packs for builds without app storage (web preview). */
class MemoryPackStore : InstalledPackStore {
    private val items = mutableMapOf<String, String>()
    override suspend fun readAll() = items.values.toList()
    override suspend fun write(id: String, envelope: String) { items[id] = envelope }
    override suspend fun delete(id: String) { items.remove(id) }
}
