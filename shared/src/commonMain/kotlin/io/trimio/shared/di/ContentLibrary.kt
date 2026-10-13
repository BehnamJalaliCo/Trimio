package io.trimio.shared.di

import io.trimio.engine.assets.packs.ContentPacks
import io.trimio.engine.assets.packs.OnlineArt
import io.trimio.engine.assets.packs.PackDir
import io.trimio.engine.models.PackIndexClient
import io.trimio.engine.models.PackManager
import io.trimio.feature.library.LibraryCore
import io.trimio.feature.library.LibraryViewModel
import io.trimio.feature.studio.LibraryHint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Keeps the engine's view of the content libraries in step with the app: the installed packs
 * ([PackManager.active]) go to [ContentPacks] (the director's picture search, recorded sounds),
 * the in-app core set and the catalogue of the pack-only sets load once, and the free on-demand
 * animations ([animations]) are the online source asked after the packs. Started with the app.
 */
class ContentLibrary(
    private val packs: PackManager,
    scope: CoroutineScope,
    /** Where the «dismissed» mark of the home suggestion is kept (null: memory only). */
    private val marks: Path? = null,
    /** The on-demand animations (their local folder first): the online source the director asks after the packs. */
    animations: OnlineArt? = null,
) {
    private val dismissed = MutableStateFlow(marks?.let { runCatching { SystemFileSystem.exists(Path(it, DISMISSED)) }.getOrDefault(false) } ?: false)

    /** The home card's suggestion: the recommended libraries not installed yet, until dismissed. */
    val hint: StateFlow<LibraryHint?> = combine(packs.catalog, packs.states, dismissed) { catalog, states, off ->
        if (off) null else LibraryViewModel.suggested(catalog, states).takeIf { it.isNotEmpty() }?.let { s -> LibraryHint(s.size, s.sumOf { it.size }) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun dismiss() {
        dismissed.value = true
        marks?.let { dir -> runCatching { SystemFileSystem.createDirectories(dir); SystemFileSystem.sink(Path(dir, DISMISSED)).close() } }
    }

    init {
        ContentPacks.online = animations
        scope.launch { LibraryCore.load()?.let { core -> core.catalogues.forEach(ContentPacks::addCatalogue); core.index?.let(::seed) } }
        scope.launch { packs.active.collect { list -> ContentPacks.use(list.map { PackDir(it.id, it.kind.name, it.dir) }) } }
        scope.launch { packs.refresh() }
    }

    /** The catalogue shipped with this build, until the channel's own is fetched. */
    private fun seed(index: String) = runCatching { packs.seed(PackIndexClient.parseIndex(index)) }

    private companion object {
        const val DISMISSED = ".suggestion-dismissed"
    }
}
