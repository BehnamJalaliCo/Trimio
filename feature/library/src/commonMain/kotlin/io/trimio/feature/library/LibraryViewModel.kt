package io.trimio.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.trimio.engine.assets.packs.LottieImport
import io.trimio.engine.llm.SecretStore
import io.trimio.engine.models.LottieFilesApi
import io.trimio.engine.models.PackKind
import io.trimio.engine.models.PackManager
import io.trimio.engine.models.PackManifest
import io.trimio.engine.models.PackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Animations the creator chose on the desktop: each file's name and Lottie JSON (`.lottie` already unpacked), and an optional keywords CSV. */
data class PickedAnimations(val files: List<Pair<String, String>>, val csv: String? = null)

/** Lets the creator choose animation files or a folder (desktop); null where the platform has no picker. */
fun interface AnimationFilePicker {
    suspend fun pick(): PickedAnimations?
}

/**
 * The library screen's state and actions: the pack manager's catalogue and transfers, the room
 * they take, the creator's imports (checked with the engine's reader, [validate]) and LottieFiles
 * with the creator's own key (kept in [secrets]).
 */
class LibraryViewModel(
    private val packs: PackManager,
    private val secrets: SecretStore,
    private val http: HttpClient,
    /** Null when the engine draws the Lottie document, otherwise why not. */
    private val validate: (String) -> String?,
    private val picker: AnimationFilePicker? = null,
    private val freeBytes: () -> Long? = { null },
    private val desktop: Boolean = false,
    /** Called after the LottieFiles key changes (the app connects or drops the online picture source). */
    private val onLottieKey: (String?) -> Unit = {},
) : ViewModel() {
    private val lottie = MutableStateFlow(LottieUi())
    private val note = MutableStateFlow<Words?>(null)
    private val fetched = MutableStateFlow(packs.catalog.value.isNotEmpty())
    private var found: List<io.trimio.engine.models.LottieFilesAnimation> = emptyList()

    val ui: StateFlow<LibraryUi> = combine(packs.catalog, packs.states, packs.active, lottie, note) { _, states, _, l, n ->
        snapshot(states, l, n)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), snapshot(packs.states.value, LottieUi(), null))

    init {
        viewModelScope.launch { fetched.value = packs.refresh() || packs.catalog.value.isNotEmpty() }
        viewModelScope.launch { lottie.update { it.copy(hasKey = !secrets.read(LOTTIE_KEY).isNullOrBlank()) } }
    }

    private fun snapshot(states: Map<String, PackState>, l: LottieUi, n: String?): LibraryUi {
        val all = packs.all()
        val sizes = packs.active.value.associate { it.id to (all.firstOrNull { m -> m.id == it.id }?.let { m -> maxOf(m.size, m.unpacked) } ?: 0L) }
        return LibraryUi(
            packs = LibraryUi.rows(all, states, sizes), usedBytes = packs.usedBytes(), freeBytes = freeBytes(), remainingBytes = packs.remainingBytes(),
            catalogueKnown = all.any { !it.local } || fetched.value, desktop = desktop, canImport = picker != null, lottie = l, importNote = n,
        )
    }

    fun actions(onBack: () -> Unit, openUrl: (String) -> Unit) = LibraryActions(
        onBack = onBack,
        onDownload = packs::download,
        onPause = packs::pause,
        onDelete = packs::delete,
        onDownloadAll = packs::downloadAll,
        onPauseAll = { packs.all().forEach { packs.pause(it.id) } },
        onImport = ::importFiles,
        onSaveLottieKey = ::saveKey,
        onRemoveLottieKey = ::removeKey,
        onLottieSearch = ::search,
        onLottieToggle = { id -> lottie.update { l -> l.copy(results = l.results.map { if (it.id == id) it.copy(selected = !it.selected) else it }) } },
        onLottieDownload = ::downloadLottie,
        onOpenLottieSite = { openUrl(LOTTIE_SITE) },
    )

    /** «افزودن انیمیشن»: the chosen files, checked and tagged, added to the creator's local pack. */
    fun importFiles() {
        val p = picker ?: return
        viewModelScope.launch {
            val picked = p.pick() ?: return@launch
            val csv = picked.csv?.let(LottieImport::csvTags).orEmpty()
            val items = picked.files.map { (name, json) ->
                val tags = csv[name.substringAfterLast('/').substringAfterLast('\\')]
                LottieImport.Item(name, json, tags?.first.orEmpty(), tags?.second.orEmpty())
            }
            val report = addToLocal(MINE, "mine", "انیمیشن‌های من", "My animations", LottieImport.OWNER_LICENCE, items)
            note.value = reportText(report)
        }
    }

    private suspend fun addToLocal(id: String, source: String, titleFa: String, titleEn: String, licence: String, items: List<LottieImport.Item>): LottieImport.Report =
        withContext(Dispatchers.Default) {
            val dir = packs.active.value.firstOrNull { it.id == id }?.dir
            val (files, report) = LottieImport(validate, source, titleEn, licence).build(items, LottieImport.read(dir))
            val total = files.keys.count { it.startsWith(LottieImport.DOCS) }
            if (report.count > 0) packs.installLocal(id, PackKind.Art, titleFa, titleEn, files, total)
            report
        }

    private fun reportText(r: LottieImport.Report): Words {
        val why = r.refused.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(2).joinToString(", ") { it.key }
        return Words(
            "${r.count} انیمیشن اضافه شد" + if (r.refused.isNotEmpty()) "؛ ${r.refused.size} رد شد (⁦$why⁩)" else "",
            "${r.count} added" + if (r.refused.isNotEmpty()) "; ${r.refused.size} refused ($why)" else "",
        )
    }

    private fun saveKey(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch {
            secrets.write(LOTTIE_KEY, key)
            lottie.update { it.copy(hasKey = true, message = null) }
            onLottieKey(key)
        }
    }

    private fun removeKey() {
        viewModelScope.launch {
            secrets.delete(LOTTIE_KEY)
            found = emptyList()
            lottie.value = LottieUi()
            onLottieKey(null)
        }
    }

    private fun search(query: String) {
        viewModelScope.launch {
            val key = secrets.read(LOTTIE_KEY) ?: return@launch
            lottie.update { it.copy(query = query, searching = true, message = null) }
            val api = LottieFilesApi(http, key)
            val page = if (query.isBlank()) api.featured(PAGE) else api.search(query, PAGE)
            found = page?.items.orEmpty()
            lottie.update {
                it.copy(
                    searching = false, results = found.map { a -> LottieResult(a.id, a.name) }, total = page?.total ?: 0,
                    message = if (page == null) Words("LottieFiles پاسخ نداد؛ کلید یا اینترنت را بررسی کن", "LottieFiles did not answer; check the key or the connection") else null,
                )
            }
        }
    }

    /** Downloads the chosen (or all) results into the LottieFiles local pack, one request at a time (the API's pace). */
    private fun downloadLottie(all: Boolean) {
        val chosen = lottie.value.results.filter { all || it.selected }.map { it.id }.toSet()
        val picks = found.filter { it.id in chosen }.take(MAX_DOWNLOAD)
        if (picks.isEmpty()) return
        viewModelScope.launch {
            val key = secrets.read(LOTTIE_KEY) ?: return@launch
            val api = LottieFilesApi(http, key)
            val items = mutableListOf<LottieImport.Item>()
            picks.forEachIndexed { i, a ->
                lottie.update { it.copy(progress = i to picks.size) }
                api.document(a)?.let { doc -> items += LottieImport.Item("${a.name}-${a.id}.json", doc, listOf(a.name, lottie.value.query).filter { it.isNotBlank() }) }
            }
            val report = addToLocal(LOTTIEFILES, "lottiefiles", "انیمیشن‌های LottieFiles", "LottieFiles animations", LOTTIE_LICENCE, items)
            lottie.update { it.copy(progress = null, results = it.results.map { r -> r.copy(selected = false) }, message = reportText(report)) }
        }
    }

    companion object {
        const val LOTTIE_KEY = "lottiefiles"
        const val MINE = "art.mine"
        const val LOTTIEFILES = "art.lottiefiles"
        const val LOTTIE_LICENCE = "Lottie-Simple-License"
        const val LOTTIE_SITE = "https://lottiefiles.com/free-animations"
        private const val PAGE = 30
        private const val MAX_DOWNLOAD = 60
        private const val STOP_MS = 5_000L

        /** The packs suggested on home after setup: the recommended ones not yet installed. */
        fun suggested(catalog: List<PackManifest>, states: Map<String, PackState>): List<PackManifest> =
            catalog.filter { it.recommended && states[it.id] !is PackState.Installed }
    }
}
