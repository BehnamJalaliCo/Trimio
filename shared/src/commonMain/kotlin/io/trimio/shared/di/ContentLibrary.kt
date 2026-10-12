package io.trimio.shared.di

import io.ktor.client.HttpClient
import io.trimio.engine.assets.art.ArtEntry
import io.trimio.engine.assets.art.ArtLicence
import io.trimio.engine.assets.art.ArtSource
import io.trimio.engine.assets.packs.ContentPacks
import io.trimio.engine.assets.packs.OnlineArt
import io.trimio.engine.assets.packs.OnlineHit
import io.trimio.engine.assets.packs.PackDir
import io.trimio.engine.llm.SecretStore
import io.trimio.engine.models.LottieFilesApi
import io.trimio.engine.models.PackIndexClient
import io.trimio.engine.models.PackManager
import io.trimio.engine.motion.art.MotionArt
import io.trimio.engine.motion.art.MotionArts
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
 * the in-app core set and the catalogue of the pack-only sets load once, and the creator's
 * LottieFiles key (when there is one) connects LottieFiles as the online source asked after the
 * packs. Started with the app.
 */
class ContentLibrary(
    private val packs: PackManager,
    private val secrets: SecretStore,
    private val http: HttpClient,
    scope: CoroutineScope,
    /** Where the «dismissed» mark of the home suggestion is kept (null: memory only). */
    private val marks: Path? = null,
    /** The on-demand animations (no key; the «LottieFiles» folder first), the online source when the creator connected no key of their own. */
    private val animations: OnlineArt? = null,
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
        scope.launch { LibraryCore.load()?.let { core -> core.catalogues.forEach(ContentPacks::addCatalogue); core.index?.let(::seed) } }
        scope.launch { packs.active.collect { list -> ContentPacks.use(list.map { PackDir(it.id, it.kind.name, it.dir) }) } }
        scope.launch { connect(secrets.read(LibraryViewModel.LOTTIE_KEY)) }
        scope.launch { packs.refresh() }
    }

    /** The catalogue shipped with this build, until the channel's own is fetched. */
    private fun seed(index: String) = runCatching { packs.seed(PackIndexClient.parseIndex(index)) }

    private companion object {
        const val DISMISSED = ".suggestion-dismissed"
    }

    /** Connects LottieFiles with the creator's key as the online picture source, or (no key) the on-demand animations. */
    fun connect(key: String?) {
        ContentPacks.online = key?.takeIf { it.isNotBlank() }?.let { LottieFilesOnline(LottieFilesApi(http, it)) } ?: animations
    }
}

/**
 * LottieFiles as an [OnlineArt]: the developer API's search with the creator's key, each
 * animation's document fetched through the edit's budgeted GET and kept only when the engine
 * draws it. Answers are remembered per query for the life of the app.
 */
private class LottieFilesOnline(private val api: LottieFilesApi) : OnlineArt {
    private val answers = HashMap<String, List<OnlineHit>>()

    override suspend fun search(query: String, limit: Int, fetch: suspend (String) -> String?): List<OnlineHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        answers[q]?.let { return it }
        val page = api.search(q, (limit * 2).coerceAtMost(LottieFilesApi.PAGE)) ?: return emptyList()
        val hits = mutableListOf<OnlineHit>()
        for (a in page.items) {
            if (hits.size >= limit) break
            val doc = fetch(a.jsonUrl) ?: continue
            if (MotionArts.readOrWhy(MotionArt.FORMAT_LOTTIE, doc).first == null) continue
            hits += OnlineHit(ArtEntry(SOURCE, a.id.toString(), a.name.ifBlank { q }, listOf(q) + a.name.lowercase().split(' ').filter { it.length > 1 }), doc)
        }
        answers[q] = hits
        return hits
    }

    companion object {
        val SOURCE = ArtSource(
            id = "lottiefiles", title = "LottieFiles", author = "", licence = ArtLicence.of(LibraryViewModel.LOTTIE_LICENCE), credit = null,
            home = "https://lottiefiles.com", url = "lottiefiles:{id}", format = MotionArt.FORMAT_LOTTIE, width = 512f, height = 512f, loops = true,
        )
    }
}
