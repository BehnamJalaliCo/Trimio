package io.trimio.engine.production

import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.autopilot.Understanding
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The expensive, deterministic halves of a run, saved as they finish: the words (whisper) and the
 * reading (the director model). A run the system killed restarts from [pending] and skips both;
 * planning, the jury and the render are fast enough to redo. Null [dir]: nothing is kept.
 */
class Checkpoints(private val dir: Path?) {

    @Serializable
    data class Saved(val transcript: Transcript, val understanding: Understanding? = null)

    fun load(key: String): Saved? = read(file(key)) { json.decodeFromString(Saved.serializer(), it) }

    fun save(key: String, saved: Saved) = write(file(key), json.encodeToString(Saved.serializer(), saved))

    /** Forgets a run's words and reading (the creator deleted the project). */
    fun delete(key: String) {
        val path = file(key) ?: return
        runCatching { SystemFileSystem.delete(path, mustExist = false) }
    }

    /** The run in progress, to restart after process death. */
    fun pending(): ProductionRequest? = read(dir?.let { Path(it, PENDING) }) { json.decodeFromString(ProductionRequest.serializer(), it) }

    fun setPending(request: ProductionRequest?) {
        val path = dir?.let { Path(it, PENDING) } ?: return
        if (request == null) runCatching { SystemFileSystem.delete(path, mustExist = false) } else write(path, json.encodeToString(ProductionRequest.serializer(), request))
    }

    private fun file(key: String) = dir?.let { Path(it, key.filter { c -> c.isLetterOrDigit() || c == '-' } + ".json") }

    private fun <T> read(path: Path?, parse: (String) -> T): T? = path?.takeIf { SystemFileSystem.exists(it) }?.let { p ->
        // A torn or older-format file is no checkpoint: the run starts over.
        runCatching { parse(SystemFileSystem.source(p).buffered().use { it.readString() }) }.getOrNull()
    }

    private fun write(path: Path?, text: String) {
        path ?: return
        runCatching {
            path.parent?.let { SystemFileSystem.createDirectories(it) }
            val tmp = Path(path.toString() + ".tmp")
            SystemFileSystem.sink(tmp).buffered().use { it.writeString(text) }
            SystemFileSystem.atomicMove(tmp, path)
        }
    }

    companion object {
        val None = Checkpoints(null)
        private const val PENDING = "pending.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
