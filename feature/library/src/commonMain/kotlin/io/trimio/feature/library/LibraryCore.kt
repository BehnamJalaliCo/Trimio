package io.trimio.feature.library

import io.trimio.feature.library.resources.Res

/**
 * What ships inside the app (files/core, built by tools/packs/build_art_packs.py --core): the core
 * set of animated pictures with their documents (the most used emoji and every line icon, drawn
 * with no download), the catalogue of the pack-only icon sets (fetched online until their pack is
 * installed), and the channel's index as it was at build time (the library screen's list before
 * the first fetch).
 */
object LibraryCore {
    data class Core(val catalogues: List<String>, val index: String?)

    /** The core files; null when this build has none (a missing file is skipped, never fatal). */
    suspend fun load(): Core? {
        val catalogues = listOf(ART, ONLINE).mapNotNull { read(it) }
        val index = read(INDEX)
        return if (catalogues.isEmpty() && index == null) null else Core(catalogues, index)
    }

    private suspend fun read(name: String): String? = runCatching { Res.readBytes("files/core/$name").decodeToString() }.getOrNull()

    const val ART = "art.json"
    const val ONLINE = "online.json"
    const val INDEX = "index.json"
}
