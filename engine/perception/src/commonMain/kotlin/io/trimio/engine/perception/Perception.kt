package io.trimio.engine.perception

import io.trimio.engine.models.ModelSpec
import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.NetworkSlot
import io.trimio.engine.onnx.NeuralEngine
import io.trimio.engine.onnx.NeuralEngineException
import io.trimio.engine.onnx.NoEngine
import io.trimio.engine.onnx.SessionConfig
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString

/** Where an installed add-on's file is; null when it is not installed. */
fun interface ModelFiles {
    fun pathOf(spec: ModelSpec): String?

    companion object {
        val None = ModelFiles { null }
    }
}

/**
 * The perception add-ons' shared context: the neural engine, where the models are, and the one
 * slot they take turns in. Every feature asks [network] for its model and takes its model-free
 * path when the answer is null (not installed, no runtime, or the model failed to open); [notes]
 * keeps why, for the run report.
 */
class Perception(
    val engine: NeuralEngine = NoEngine,
    val files: ModelFiles = ModelFiles.None,
    val slot: NetworkSlot = NetworkSlot(engine),
) {
    private val log = ArrayList<String>()

    /** Fallbacks taken and models used, in order: "faces: yunet-2023mar on Cpu", "beats: no model, onset fallback". */
    val notes: List<String> get() = log.toList()

    /** Features run one after another (they share [slot]), so the log needs no lock. */
    fun note(line: String) {
        if (log.size < MAX_NOTES) log += line
    }

    /** Whether [spec] (and its companions, by [companions]) is installed and the engine runs. */
    fun has(spec: ModelSpec, vararg companions: ModelSpec): Boolean =
        engine.available() && files.pathOf(spec) != null && companions.all { files.pathOf(it) != null }

    /**
     * Runs [block] with [spec]'s network, or returns null (and notes why) when it is missing or
     * fails; a failure inside [block] other than the engine's propagates.
     */
    suspend fun <T> network(feature: String, spec: ModelSpec, config: SessionConfig = SessionConfig(), block: suspend (Network) -> T): T? {
        val path = files.pathOf(spec)
        if (path == null || !engine.available()) {
            note("$feature: ${spec.id} not installed, fallback")
            return null
        }
        return try {
            slot.use(path, config) { net ->
                note("$feature: ${spec.id} on ${net.accelerator}")
                block(net)
            }
        } catch (e: NeuralEngineException) {
            note("$feature: ${spec.id} failed (${e.message?.take(MAX_REASON)}), fallback")
            null
        }
    }

    /** An installed helper file (a tokenizer, a dictionary, filters) as text; null when missing or unreadable. */
    fun readText(spec: ModelSpec): String? = files.pathOf(spec)?.let { p ->
        runCatching { SystemFileSystem.source(Path(p)).buffered().use { it.readString() } }.getOrNull()
    }

    /** An installed helper file as bytes; null when missing or unreadable. */
    fun readBytes(spec: ModelSpec): ByteArray? = files.pathOf(spec)?.let { p ->
        runCatching { SystemFileSystem.source(Path(p)).buffered().use { it.readByteArray() } }.getOrNull()
    }

    /** Frees the resident network before a big stage. */
    suspend fun release() = slot.release()

    private companion object {
        const val MAX_NOTES = 200
        const val MAX_REASON = 160
    }
}
