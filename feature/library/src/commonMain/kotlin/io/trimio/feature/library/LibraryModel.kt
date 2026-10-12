package io.trimio.feature.library

import io.trimio.engine.models.PackKind
import io.trimio.engine.models.PackManifest
import io.trimio.engine.models.PackState

/** The library's sections, in the order the screen shows them. */
enum class PackGroup { Art, Sound, Visual, Mine }

/** One library on the screen: what it is, what it improves, how big, and where it stands. */
data class PackRow(
    val id: String,
    val group: PackGroup,
    val titleFa: String,
    val titleEn: String,
    val summaryFa: String,
    val summaryEn: String,
    val items: Int,
    val sizeBytes: Long,
    val state: PackState,
    val local: Boolean = false,
    val recommended: Boolean = false,
) {
    val installed: Boolean get() = state is PackState.Installed
}

/** A message in both languages; the screen shows the reader's. */
data class Words(val fa: String, val en: String)

/** One LottieFiles result in the search sheet. */
data class LottieResult(val id: Long, val name: String, val selected: Boolean = false)

/** The LottieFiles card: the creator's key, the search and its results, and the last download's outcome. */
data class LottieUi(
    val hasKey: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<LottieResult> = emptyList(),
    val total: Int = 0,
    /** Downloading animations into the local pack: done of all. */
    val progress: Pair<Int, Int>? = null,
    val message: Words? = null,
)

/** Everything the library screen shows; a pure model so it renders the same in tests and on every platform. */
data class LibraryUi(
    val packs: List<PackRow> = emptyList(),
    val usedBytes: Long = 0,
    val freeBytes: Long? = null,
    /** What «download all» still has to fetch. */
    val remainingBytes: Long = 0,
    /** The channel answered (or a cached catalogue exists). */
    val catalogueKnown: Boolean = true,
    val desktop: Boolean = false,
    /** The platform can pick files or a folder of animations (desktop). */
    val canImport: Boolean = false,
    val lottie: LottieUi = LottieUi(),
    /** The last import's outcome («۱۲ انیمیشن اضافه شد، ۲ رد شد: لایهٔ متن»). */
    val importNote: Words? = null,
    /** What the folder of animations fetched on demand holds, in bytes. */
    val animationBytes: Long = 0,
) {
    val downloading: Boolean get() = packs.any { it.state is PackState.Downloading || it.state == PackState.Queued || it.state == PackState.Installing }
    val allInstalled: Boolean get() = packs.filter { !it.local }.all { it.installed && (it.state as PackState.Installed).update == null }

    fun group(g: PackGroup): List<PackRow> = packs.filter { it.group == g }

    companion object {
        /** The rows for [manifests] in [states]; installed sizes from [sizes] where known (bytes on disk). */
        fun rows(manifests: List<PackManifest>, states: Map<String, PackState>, sizes: Map<String, Long> = emptyMap()): List<PackRow> = manifests.map { m ->
            PackRow(
                id = m.id, group = groupOf(m), titleFa = m.titleFa.ifEmpty { m.id }, titleEn = m.titleEn.ifEmpty { m.id },
                summaryFa = m.summaryFa, summaryEn = m.summaryEn, items = m.items, sizeBytes = sizes[m.id]?.takeIf { it > 0 } ?: maxOf(m.size, m.unpacked),
                state = states[m.id] ?: PackState.Available(), local = m.local, recommended = m.recommended,
            )
        }.sortedWith(compareBy<PackRow> { it.group.ordinal }.thenByDescending { it.recommended }.thenBy { it.titleEn })

        fun groupOf(m: PackManifest): PackGroup = when {
            m.local -> PackGroup.Mine
            m.kind == PackKind.Sound || m.kind == PackKind.Music -> PackGroup.Sound
            m.kind == PackKind.Overlay || m.kind == PackKind.Look || m.kind == PackKind.Style -> PackGroup.Visual
            else -> PackGroup.Art
        }
    }
}

data class LibraryActions(
    val onBack: () -> Unit = {},
    val onDownload: (String) -> Unit = {},
    val onPause: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onDownloadAll: () -> Unit = {},
    val onPauseAll: () -> Unit = {},
    /** Desktop: choose animation files or a folder («افزودن انیمیشن»). */
    val onImport: () -> Unit = {},
    val onSaveLottieKey: (String) -> Unit = {},
    val onRemoveLottieKey: () -> Unit = {},
    val onLottieSearch: (String) -> Unit = {},
    val onLottieToggle: (Long) -> Unit = {},
    val onLottieDownload: (allResults: Boolean) -> Unit = {},
    val onOpenLottieSite: () -> Unit = {},
)
