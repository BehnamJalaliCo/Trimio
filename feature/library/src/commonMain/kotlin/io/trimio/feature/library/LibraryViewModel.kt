package io.trimio.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.engine.assets.packs.LottieImport
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
 * they take, the creator's imports (checked with the engine's reader, [validate]) and the size of
 * the folder of animations fetched on demand.
 */
class LibraryViewModel(
    private val packs: PackManager,
    /** Null when the engine draws the Lottie document, otherwise why not. */
    private val validate: (String) -> String?,
    private val picker: AnimationFilePicker? = null,
    private val freeBytes: () -> Long? = { null },
    private val desktop: Boolean = false,
    /** What the folder of animations fetched on demand holds, in bytes. */
    private val animationBytes: () -> Long = { 0L },
) : ViewModel() {
    private val note = MutableStateFlow<Words?>(null)
    private val fetched = MutableStateFlow(packs.catalog.value.isNotEmpty())

    val ui: StateFlow<LibraryUi> = combine(packs.catalog, packs.states, packs.active, note) { _, states, _, n ->
        snapshot(states, n)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), snapshot(packs.states.value, null))

    init {
        viewModelScope.launch { fetched.value = packs.refresh() || packs.catalog.value.isNotEmpty() }
    }

    private fun snapshot(states: Map<String, PackState>, n: Words?): LibraryUi {
        val all = packs.all()
        val sizes = packs.active.value.associate { it.id to (all.firstOrNull { m -> m.id == it.id }?.let { m -> maxOf(m.size, m.unpacked) } ?: 0L) }
        return LibraryUi(
            packs = LibraryUi.rows(all, states, sizes), usedBytes = packs.usedBytes(), freeBytes = freeBytes(), remainingBytes = packs.remainingBytes(),
            catalogueKnown = all.any { !it.local } || fetched.value, desktop = desktop, canImport = picker != null, importNote = n,
            animationBytes = animationBytes(),
        )
    }

    fun actions(onBack: () -> Unit) = LibraryActions(
        onBack = onBack,
        onDownload = packs::download,
        onPause = packs::pause,
        onDelete = packs::delete,
        onDownloadAll = packs::downloadAll,
        onPauseAll = { packs.all().forEach { packs.pause(it.id) } },
        onImport = ::importFiles,
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

    companion object {
        const val MINE = "art.mine"
        private const val STOP_MS = 5_000L

        /** The packs suggested on home after setup: the recommended ones not yet installed. */
        fun suggested(catalog: List<PackManifest>, states: Map<String, PackState>): List<PackManifest> =
            catalog.filter { it.recommended && states[it.id] !is PackState.Installed }
    }
}
