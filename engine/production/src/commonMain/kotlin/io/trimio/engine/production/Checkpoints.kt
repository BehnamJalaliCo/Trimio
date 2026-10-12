package io.trimio.engine.production

import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.autopilot.Delivery
import io.trimio.engine.autopilot.Understanding
import io.trimio.engine.production.diagnostics.Reports
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The expensive halves of a run, saved as they finish: the words (whisper), the reading (the
 * director model) and, once the run is done, the director's whole decision ([Delivery]). A run
 * the system killed restarts from [pending] and skips the first two; a finished edit is made
 * again from the third ([AutoEditor.reopen]) after the app restarts, for the editor and exports,
 * without whisper or the model. Null [dir]: nothing is kept.
 */
class Checkpoints(private val dir: Path?) {

    /** Each run's technical report and death marker, kept beside its checkpoint ([RunReport]). */
    val reports: Reports = Reports(dir)

    @Serializable
    data class Saved(
        /** The recognised words on the source clock, as whisper gave them. */
        val transcript: Transcript,
        val understanding: Understanding? = null,
        /** The finished edit as the director delivered it (absent while the run is going, and in files of earlier versions). */
        val delivery: Delivery? = null,
    )

    fun load(key: String): Saved? = read(file(key)) { json.decodeFromString(Saved.serializer(), it) }

    fun save(key: String, saved: Saved) = write(file(key), json.encodeToString(Saved.serializer(), saved))

    /** Forgets a run's words, reading and technical report (the creator deleted the project). */
    fun delete(key: String) {
        reports.delete(key)
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
