package io.trimio.engine.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the settings screen shows for one model. */
sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val fraction: Float, val bytes: Long) : ModelState
    data object Installed : ModelState
    /** An older release is installed and works; [to] is the catalogue's newer one. */
    data class UpdateAvailable(val to: ModelSpec) : ModelState
    data class Failed(val message: String) : ModelState
}

/** A newer release of an installed model. */
data class ModelUpdate(val spec: ModelSpec, val installedVersion: Int)

/**
 * App-wide model downloads with observable state. Downloads run in [scope] (the application
 * scope), so they continue across screens; on Android a user-initiated data-transfer job keeps the
 * process alive and shows the system download notification while one is running.
 */
class ModelManager(
    private val store: ModelStore,
    private val scope: CoroutineScope,
    catalog: List<ModelSpec> = ModelCatalog.all,
    /** Called when the first download starts and after the last one ends (Android starts/stops its job). */
    private val onActiveChanged: (Boolean) -> Unit = {},
) {
    /** The catalogue in effect: built-in plus the server's additions and newer releases. */
    var catalog: List<ModelSpec> = catalog
        private set
    private val _states = MutableStateFlow(catalog.associate { it.id to initial(it) })
    private val _updates = MutableStateFlow(findUpdates())

    /** Installed models with a newer release in the catalogue (shown and notified apart from app updates). */
    val updates: StateFlow<List<ModelUpdate>> = _updates.asStateFlow()
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    fun isInstalled(spec: ModelSpec) = store.isInstalled(spec)

    fun pathOf(spec: ModelSpec): String = store.pathOf(spec).toString()

    fun refresh() {
        _states.update { current ->
            catalog.associate { it.id to if (jobs[it.id]?.isActive == true) current[it.id] ?: initial(it) else initial(it) }
        }
        _updates.value = findUpdates()
    }

    /** Adopts a newer catalogue (after the server answered); recomputes states and pending updates. */
    fun updateCatalog(next: List<ModelSpec>) {
        if (next == catalog) return
        catalog = next
        refresh()
    }

    /** Downloads the newer release; the installed one keeps working until the new one is verified. */
    fun applyUpdate(update: ModelUpdate) = download(update.spec)

    fun applyAllUpdates() = _updates.value.forEach(::applyUpdate)

    fun download(spec: ModelSpec) {
        if (jobs[spec.id]?.isActive == true || store.isCurrent(spec)) return
        val wasIdle = jobs.values.none { it.isActive }
        set(spec.id, ModelState.Downloading(0f, 0))
        jobs[spec.id] = scope.launch {
            try {
                store.install(spec) { state ->
                    if (state is DownloadState.Progress) set(spec.id, ModelState.Downloading(state.fraction, state.bytes))
                }
                set(spec.id, ModelState.Installed)
                _updates.value = findUpdates()
            } catch (e: CancellationException) {
                set(spec.id, initial(spec))
                throw e
            } catch (e: Exception) {
                set(spec.id, ModelState.Failed(e.message ?: "Download failed"))
            } finally {
                jobs.remove(spec.id)
                if (jobs.values.none { it.isActive }) onActiveChanged(false)
            }
        }
        if (wasIdle) onActiveChanged(true)
    }

    /** Downloads every default model that is missing (first-run setup). */
    fun downloadDefaults() = catalog.filter { it.isDefault }.forEach(::download)

    fun cancel(spec: ModelSpec) {
        jobs[spec.id]?.cancel()
    }

    fun delete(spec: ModelSpec) {
        cancel(spec)
        store.delete(spec)
        set(spec.id, ModelState.NotInstalled)
    }

    /** Suspends until no download is running (the Android job's lifetime). */
    suspend fun awaitIdle() {
        while (true) {
            val active = jobs.values.filter { it.isActive }
            if (active.isEmpty()) return
            active.forEach { it.join() }
        }
    }

    val hasActiveDownloads: Boolean get() = jobs.values.any { it.isActive }

    private fun initial(spec: ModelSpec): ModelState = when {
        store.isCurrent(spec) -> ModelState.Installed
        store.isInstalled(spec) -> ModelState.UpdateAvailable(spec)
        else -> ModelState.NotInstalled
    }

    private fun findUpdates(): List<ModelUpdate> = catalog.mapNotNull { spec ->
        val record = store.installedRecord(spec.id) ?: return@mapNotNull null
        if (store.isInstalled(spec) && !store.isCurrent(spec) && spec.version > record.version) ModelUpdate(spec, record.version) else null
    }

    private fun set(id: String, state: ModelState) = _states.update { it + (id to state) }
}
