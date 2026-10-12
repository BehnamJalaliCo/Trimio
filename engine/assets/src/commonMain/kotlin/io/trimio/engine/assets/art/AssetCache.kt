package io.trimio.engine.assets.art

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/**
 * Text documents fetched from the web (animation documents, icon bodies, look-up answers), kept
 * so an edit made again — or the next edit that wants the same picture — needs no network.
 */
interface AssetCache {
    fun get(key: String): String?
    fun put(key: String, value: String)

    class Memory : AssetCache {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }
}

/**
 * An [AssetCache] on disk: one file per key under [dir], named by a readable stem and a hash of
 * the key. When the folder grows past [maxBytes], files are dropped (in a stable order) until it
 * fits again. Any file-system error only means "not cached".
 */
class DiskAssetCache internal constructor(private val dir: Path, private val maxBytes: Long, private val fs: FileSystem) : AssetCache {

    /** A cache in the folder [dir] (created when first written). */
    constructor(dir: String, maxBytes: Long = DEFAULT_MAX) : this(Path(dir), maxBytes, SystemFileSystem)

    private val memory = AssetCache.Memory()

    override fun get(key: String): String? = memory.get(key) ?: runCatching {
        val p = file(key)
        if (!fs.exists(p)) return null
        fs.source(p).buffered().use { it.readByteArray().decodeToString() }
    }.getOrNull()?.also { memory.put(key, it) }

    override fun put(key: String, value: String) {
        memory.put(key, value)
        runCatching {
            if (!fs.exists(dir)) fs.createDirectories(dir)
            val tmp = Path(dir, name(key) + ".part")
            fs.sink(tmp).buffered().use { it.writeString(value) }
            fs.atomicMove(tmp, file(key))
            trim()
        }
    }

    private fun file(key: String) = Path(dir, name(key))

    /** Keeps the folder under [maxBytes]. */
    private fun trim() {
        val files = fs.list(dir).filter { !it.name.endsWith(".part") }
        val sizes = files.associateWith { fs.metadataOrNull(it)?.size ?: 0L }
        var total = sizes.values.sum()
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.name }) {
            if (total <= maxBytes) break
            total -= sizes[f] ?: 0L
            runCatching { fs.delete(f) }
        }
    }

    companion object {
        const val DEFAULT_MAX = 64L * 1024 * 1024

        /** A file name for [key]: its hash first (stable), then a readable tail. */
        fun name(key: String): String {
            var h = 1125899906842597L
            for (c in key) h = 31 * h + c.code
            val tail = key.substringAfterLast('/').filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(TAIL)
            return "${h.toULong().toString(HEX)}-$tail.txt"
        }

        private const val HEX = 16
        private const val TAIL = 40
    }
}
