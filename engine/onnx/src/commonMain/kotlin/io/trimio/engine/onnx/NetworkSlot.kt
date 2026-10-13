package io.trimio.engine.onnx

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * At most one perception network open at a time. A phone cannot hold the director, whisper and
 * every add-on together, so stages take turns: [use] opens a network (or reuses the one already
 * open for the same file), and opening another first closes it. [release] frees it before a big
 * stage (the director, the renderer) needs the memory.
 *
 * Calls queue on one lock, so ONNX Runtime's threads never compete with themselves.
 */
class NetworkSlot(private val engine: NeuralEngine) {
    private val mutex = Mutex()
    private var path: String? = null
    private var network: Network? = null

    /** Loads so far (a reused network does not count): tests and reports read it. */
    var loads: Int = 0
        private set

    /** The file whose network is open now, if any. */
    val resident: String? get() = path

    suspend fun <T> use(file: String, config: SessionConfig = SessionConfig(), block: suspend (Network) -> T): T = mutex.withLock {
        val net = network?.takeIf { path == file } ?: run {
            closeOpen()
            engine.load(file, config).also {
                network = it
                path = file
                loads++
            }
        }
        block(net)
    }

    suspend fun release() = mutex.withLock { closeOpen() }

    private fun closeOpen() {
        network?.close()
        network = null
        path = null
    }
}
