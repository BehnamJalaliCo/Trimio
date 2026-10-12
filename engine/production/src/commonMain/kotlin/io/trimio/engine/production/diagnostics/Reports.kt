package io.trimio.engine.production.diagnostics

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where run reports live: beside the run's checkpoints (`<key>.report.json`), with the run's
 * death marker (`<key>.marker`, one short line: what the process is inside right now) and the
 * device's latest engine summary (`engine.json`, for the models screen). Null [dir]: kept in
 * memory only (tests, web previews). Every write is atomic (a temporary file moved over).
 */
class Reports(private val dir: Path?) {
    private val memory = mutableMapOf<String, String>()

    fun load(key: String): RunReport? = read(file(key, REPORT)) { json.decodeFromString(RunReport.serializer(), it) }

    fun save(report: RunReport) = write(file(report.key, REPORT), json.encodeToString(RunReport.serializer(), report))

    /** The marker's line ("entering transcribe@<session>"), or null when the last attempt finished cleanly. */
    fun marker(key: String): String? = read(file(key, MARKER)) { it.trim() }?.ifEmpty { null }

    fun mark(key: String, line: String?) {
        val path = file(key, MARKER)
        if (line == null) remove(path) else write(path, line)
    }

    /** The engine as the latest run on this device measured it, for the models screen and speed modes. */
    fun engine(): EngineRecord? = read(dir?.let { Path(it, ENGINE) } ?: Path(ENGINE)) { json.decodeFromString(EngineRecord.serializer(), it) }

    fun saveEngine(record: EngineRecord) = write(dir?.let { Path(it, ENGINE) } ?: Path(ENGINE), json.encodeToString(EngineRecord.serializer(), record))

    /** Forgets a run's report and marker (its project was deleted). */
    fun delete(key: String) {
        remove(file(key, REPORT))
        remove(file(key, MARKER))
    }

    private fun file(key: String, suffix: String): Path {
        val name = key.filter { c -> c.isLetterOrDigit() || c == '-' } + suffix
        return dir?.let { Path(it, name) } ?: Path(name)
    }

    private fun <T> read(path: Path, parse: (String) -> T): T? {
        if (dir == null) return memory[path.toString()]?.let { runCatching { parse(it) }.getOrNull() }
        if (!SystemFileSystem.exists(path)) return null
        // A torn or older-format file reads as nothing.
        return runCatching { parse(SystemFileSystem.source(path).buffered().use { it.readString() }) }.getOrNull()
    }

    private fun write(path: Path, text: String) {
        if (dir == null) {
            memory[path.toString()] = text
            return
        }
        runCatching {
            path.parent?.let { SystemFileSystem.createDirectories(it) }
            val tmp = Path(path.toString() + ".tmp")
            SystemFileSystem.sink(tmp).buffered().use { it.writeString(text) }
            SystemFileSystem.atomicMove(tmp, path)
        }
    }

    private fun remove(path: Path) {
        if (dir == null) memory.remove(path.toString()) else runCatching { SystemFileSystem.delete(path, mustExist = false) }
    }

    companion object {
        private const val REPORT = ".report.json"
        private const val MARKER = ".marker"
        private const val ENGINE = "engine.json"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}

/**
 * What the engines did on this device in the latest run that used them: the native build, the
 * GPU and how many of the model's layers it took, the threads, and every model's measured speed
 * so far ([speeds], generated tokens per second, for choosing fast / balanced / pro).
 */
@Serializable
data class EngineRecord(
    val atMs: Long,
    /** "opencl", "vulkan", "cuda", "metal" or "cpu". */
    val backend: String? = null,
    val gpu: String? = null,
    val gpuLayers: Int? = null,
    val totalLayers: Int? = null,
    val threads: Int? = null,
    val model: String? = null,
    /** Model id → generated tokens per second measured on this device (the latest run's). */
    val speeds: Map<String, Float> = emptyMap(),
    /** Whisper's real-time factor in the latest run that listened (below 1 is faster than real time). */
    val speechRealTime: Float? = null,
    val renderFps: Float? = null,
)
