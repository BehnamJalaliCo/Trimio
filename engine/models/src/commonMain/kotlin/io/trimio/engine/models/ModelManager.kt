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
    data class Failed(val message: String) : ModelState
}

/**
 * App-wide model downloads with observable state. Downloads run in [scope] (the application
 * scope), so they continue across screens; on Android a user-initiated data-transfer job keeps the
 * process alive and shows the system download notification while one is running.
 */
class ModelManager(
    private val store: ModelStore,
    private val scope: CoroutineScope,
    val catalog: List<ModelSpec> = ModelCatalog.all,
    /** Called when the first download starts and after the last one ends (Android starts/stops its job). */
    private val onActiveChanged: (Boolean) -> Unit = {},
) {
    private val _states = MutableStateFlow(catalog.associate { it.id to initial(it) })
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    fun isInstalled(spec: ModelSpec) = store.isInstalled(spec)

    fun pathOf(spec: ModelSpec): String = store.pathOf(spec).toString()

    fun refresh() = _states.update { current ->
        catalog.associate { it.id to if (jobs[it.id]?.isActive == true) current.getValue(it.id) else initial(it) }
    }

    fun download(spec: ModelSpec) {
        if (jobs[spec.id]?.isActive == true || store.isInstalled(spec)) return
        val wasIdle = jobs.values.none { it.isActive }
        set(spec.id, ModelState.Downloading(0f, 0))
        jobs[spec.id] = scope.launch {
            try {
                store.install(spec) { state ->
                    if (state is DownloadState.Progress) set(spec.id, ModelState.Downloading(state.fraction, state.bytes))
                }
                set(spec.id, ModelState.Installed)
            } catch (e: CancellationException) {
                set(spec.id, ModelState.NotInstalled)
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

    private fun initial(spec: ModelSpec): ModelState = if (store.isInstalled(spec)) ModelState.Installed else ModelState.NotInstalled

    private fun set(id: String, state: ModelState) = _states.update { it + (id to state) }
}
