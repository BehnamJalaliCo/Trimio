package io.trimio.engine.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** What the library screen shows for one pack. */
sealed interface PackState {
    /** In the channel, not on this device ([partial] bytes of an earlier attempt are kept). */
    data class Available(val partial: Long = 0) : PackState

    /** Waiting behind another download (a «download all»). */
    data object Queued : PackState

    data class Downloading(val bytes: Long, val total: Long) : PackState {
        val fraction: Float get() = if (total <= 0) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /** Stopped by the creator; resumes from [bytes]. */
    data class Paused(val bytes: Long, val total: Long) : PackState {
        val fraction: Float get() = if (total <= 0) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /** Unpacking and checking the download. */
    data object Installing : PackState

    /** On this device; [update] is the channel's newer release, if any. */
    data class Installed(val version: Int, val update: PackManifest? = null) : PackState

    /** Not enough room: [needed] bytes, [free] free. */
    data class NoSpace(val needed: Long, val free: Long) : PackState

    data class Failed(val message: String) : PackState
}

/** A pack in use: its kind and the folder of its active release (the engine's catalogues read it). */
data class ActivePack(val id: String, val kind: PackKind, val version: Int, val dir: String, val local: Boolean = false)

/**
 * App-wide pack downloads with observable state, like [ModelManager]: transfers run in [scope] so
 * they continue across screens, one at a time (a «download all» queues the rest). Pause cancels the
 * transfer and keeps the partial file; download resumes it. [active] lists what is installed, for
 * the engine's catalogues (the art search, recorded sounds) to merge.
 */
class PackManager(
    private val store: PackStore,
    private val index: PackIndexClient,
    private val scope: CoroutineScope,
    /** Called when the first download starts and after the last one ends (Android keeps a transfer job). */
    private val onActiveChanged: (Boolean) -> Unit = {},
) {
    private val _catalog = MutableStateFlow(index.cached())
    private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())
    private val _active = MutableStateFlow<List<ActivePack>>(emptyList())

    /** The channel's packs (last fetched or cached) plus the local ones. */
    val catalog: StateFlow<List<PackManifest>> = _catalog.asStateFlow()
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()
    val active: StateFlow<List<ActivePack>> = _active.asStateFlow()

    /** The transfer running and the ones waiting; updated atomically (calls come from the UI and from transfers). */
    private data class Line(val running: String? = null, val queue: List<String> = emptyList(), val job: Job? = null)

    private val line = MutableStateFlow(Line())

    init {
        recompute()
    }

    /** Fetches the channel's index; false when offline (the cached one stays). */
    suspend fun refresh(): Boolean {
        val fresh = index.fetch()
        if (fresh != null) _catalog.value = fresh
        recompute()
        return fresh != null
    }

    /** Every pack: the channel's, then installed ones it no longer lists (and local imports). */
    fun all(): List<PackManifest> {
        val listed = _catalog.value
        val ids = listed.map { it.id }.toSet()
        return listed + store.installed().values.map { it.manifest }.filter { it.id !in ids }
    }

    fun download(id: String) {
        val m = _catalog.value.firstOrNull { it.id == id } ?: return
        val s = _states.value[id]
        if (s is PackState.Installed && s.update == null) return
        var added = false
        var wasIdle = false
        line.update { l ->
            added = id != l.running && id !in l.queue
            wasIdle = l.running == null && l.queue.isEmpty()
            if (added) l.copy(queue = l.queue + m.id) else l
        }
        if (!added) return
        set(id, PackState.Queued)
        if (wasIdle) onActiveChanged(true)
        pump()
    }

    /** Downloads every pack not yet installed (and every update), one after another. */
    fun downloadAll() = all().filter { needsDownload(it.id) }.forEach { download(it.id) }

    /** What «download all» still has to fetch, in bytes. */
    fun remainingBytes(): Long = all().filter { needsDownload(it.id) }.sumOf { (it.size - store.partial(it)).coerceAtLeast(0) }

    fun pause(id: String) {
        var queued = false
        val l = line.updateAndGet { l -> queued = id in l.queue; if (queued) l.copy(queue = l.queue - id) else l }
        if (queued) set(id, initial(id)) else if (l.running == id) l.job?.cancel()
    }

    /** Removes [id] (its files and any partial download); a running transfer is stopped first. */
    fun delete(id: String) {
        val running = line.value.takeIf { it.running == id }?.job
        pause(id)
        if (running == null) {
            store.uninstall(id)
            recompute()
        } else {
            running.invokeOnCompletion { store.uninstall(id); recompute() }
        }
    }

    /** Installs [files] imported by the creator as the local pack [id] (their own animations). */
    fun installLocal(id: String, kind: PackKind, titleFa: String, titleEn: String, files: Map<String, ByteArray>, items: Int) {
        store.installLocal(id, kind, titleFa, titleEn, files, items)
        recompute()
    }

    /** Bytes the installed packs take. */
    fun usedBytes(): Long = store.installed().keys.sumOf(store::diskSize)

    /** Suspends until no download is running. */
    suspend fun awaitIdle() {
        while (true) {
            val j = line.value.job ?: return
            j.join()
        }
    }

    private fun needsDownload(id: String): Boolean = when (val s = _states.value[id]) {
        is PackState.Installed -> s.update != null
        else -> !(all().firstOrNull { it.id == id }?.local ?: true)
    }

    private fun pump() {
        var claimed: String? = null
        line.update { l ->
            claimed = if (l.running == null) l.queue.firstOrNull() else null
            claimed?.let { Line(it, l.queue.drop(1)) } ?: l
        }
        val id = claimed ?: return
        val m = _catalog.value.firstOrNull { it.id == id }
        if (m == null) {
            line.update { it.copy(running = null) }
            return pump()
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { run(m) }
        line.update { if (it.running == id) it.copy(job = job) else it }
        // Bookkeeping on completion, so a transfer paused even before it started still frees the line.
        job.invokeOnCompletion { cause -> finish(m, cause) }
        job.start()
    }

    private suspend fun run(m: PackManifest) {
        try {
            set(m.id, PackState.Downloading(store.partial(m), m.size))
            store.install(m) { bytes, total ->
                set(m.id, if (bytes >= total) PackState.Installing else PackState.Downloading(bytes, total))
            }
            recompute()
        } catch (e: PackSpaceException) {
            set(m.id, PackState.NoSpace(e.needed, e.free))
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            set(m.id, PackState.Failed(e.message ?: "download failed"))
        }
    }

    private fun finish(m: PackManifest, cause: Throwable?) {
        if (cause is CancellationException) set(m.id, store.partial(m).let { if (it > 0) PackState.Paused(it, m.size) else PackState.Available() })
        val l = line.updateAndGet { if (it.running == m.id) Line(queue = it.queue) else it }
        if (l.running == null && l.queue.isEmpty()) onActiveChanged(false)
        pump()
    }

    private fun recompute() {
        val installed = store.installed()
        _active.value = installed.values.mapNotNull { p ->
            store.activeDir(p.manifest.id)?.let { ActivePack(p.manifest.id, p.manifest.kind, p.manifest.version, it.toString(), p.manifest.local) }
        }
        _states.update { current ->
            all().associate { m ->
                val s = current[m.id]
                m.id to if (m.id == line.value.running || s == PackState.Queued) s ?: initial(m.id) else initial(m.id)
            }
        }
    }

    private fun initial(id: String): PackState {
        val installed = store.installed()[id]
        val listed = _catalog.value.firstOrNull { it.id == id }
        if (installed != null && store.activeDir(id) != null) {
            val update = listed?.takeIf { !installed.manifest.local && it.version > installed.manifest.version }
            return PackState.Installed(installed.manifest.version, update)
        }
        val partial = listed?.let(store::partial) ?: 0L
        return if (partial > 0 && listed != null) PackState.Paused(partial, listed.size) else PackState.Available(partial)
    }

    private fun set(id: String, state: PackState) = _states.update { it + (id to state) }
}
