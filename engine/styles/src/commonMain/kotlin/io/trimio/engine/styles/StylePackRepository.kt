package io.trimio.engine.styles

import io.trimio.core.model.style.DesignStyle
import io.trimio.core.model.style.StyleFamily
import io.trimio.engine.styles.resources.Res
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Where installed (downloaded) packs are persisted. Platform code stores them in app storage. */
interface InstalledPackStore {
    suspend fun readAll(): List<String>
    suspend fun write(id: String, envelope: String)
    suspend fun delete(id: String)
}

/**
 * All styles available to the app: packs bundled in the APK plus verified downloads. A downloaded
 * pack with the same id and a higher version overrides the bundled one, so styles can be refined
 * without an app update.
 */
class StylePackRepository(
    private val installed: InstalledPackStore? = null,
    private val trustedKeys: Map<String, ByteArray> = PackKeys.trusted,
) {
    private val mutex = Mutex()
    private var cache: Map<String, StylePack>? = null

    suspend fun all(): List<StylePack> = load().values.sortedBy { order(it.id) }

    suspend fun get(id: String): StylePack? = load()[id]

    /** Best available pack for a family, for prompts that name a mood rather than a style. */
    suspend fun forFamily(family: StyleFamily): StylePack? = all().firstOrNull { it.family == family }

    /** Verifies and stores a downloaded pack; returns it, or throws [StylePackException]. */
    suspend fun install(envelope: String): StylePack {
        val pack = StylePackCodec.decodeSigned(envelope, trustedKeys)
        installed?.write(pack.id, envelope) ?: throw StylePackException("No storage for installed packs")
        mutex.withLock { cache = null }
        return pack
    }

    /** Re-reads installed packs on next access (after the trusted keys changed). */
    suspend fun invalidate() = mutex.withLock { cache = null }

    private suspend fun load(): Map<String, StylePack> = mutex.withLock {
        cache ?: buildMap {
            for (pack in bundled()) put(pack.id, pack)
            installed?.readAll()?.forEach { text ->
                // A pack that no longer verifies (key rotated, file damaged) is skipped, not fatal.
                val pack = runCatching { StylePackCodec.decodeSigned(text, trustedKeys) }.getOrNull() ?: return@forEach
                val current = get(pack.id)
                if (current == null || newer(pack.version, current.version)) put(pack.id, pack)
            }
        }.also { cache = it }
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun bundled(): List<StylePack> = BUNDLED.map { id ->
        StylePackCodec.decodeBundled(Res.readBytes("files/styles/$id.json").decodeToString())
    }

    /** Gallery order follows the canonical 28-style list. */
    private fun order(id: String) = DesignStyle.fromId(id)?.ordinal ?: Int.MAX_VALUE

    companion object {
        /** Packs shipped in the APK (resources cannot be listed at runtime, so they are named here): all 28 styles. */
        val BUNDLED: List<String> = DesignStyle.entries.map { it.id }

        /** True when semantic version [a] is higher than [b]. */
        fun newer(a: String, b: String): Boolean {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}
