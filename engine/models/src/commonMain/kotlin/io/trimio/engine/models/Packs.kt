package io.trimio.engine.models

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.kotlincrypto.hash.sha2.SHA256

/**
 * What a downloadable pack carries. [Art]: animated emoji, animated icons, stickers and
 * illustrations (Lottie / animated SVG); [Overlay]: textures, grain, light leaks; [Look]: colour
 * looks (3D LUTs).
 */
enum class PackKind { Style, Sound, Music, Model, Art, Overlay, Look }

/**
 * One release of a downloadable pack, as the channel's index lists it. The server signs
 * [signedBytes] — identity, version, size, payload hash, minimum app and licence — with its
 * Ed25519 key [keyId]; the payload is then bound by [sha256]. Mirrors ([urls]) are not signed: any
 * mirror is fine, since a payload that does not hash to the signed value is discarded. A relative
 * URL is resolved against the channel's base ([PackChannel]), so the host can move without a
 * re-signing. The display fields (titles, summaries, counts) are not signed: they only describe.
 */
@Serializable
data class PackManifest(
    /** `<kind>.<name>`, e.g. `sound.cinematic-hits`, `art.noto-emoji`. */
    val id: String,
    val kind: PackKind,
    /** Increases with every release; installs never go backwards (a replayed old release is refused). */
    val version: Int,
    val size: Long,
    val sha256: String,
    val urls: List<String>,
    val keyId: String,
    /** Hex Ed25519 signature of [signedBytes]. */
    val signature: String,
    /** The oldest app build that understands this pack. */
    val minApp: Int = 0,
    /** SPDX id of the content's licence (CC0-1.0, MIT, …; LicenseRef-Trimio for our own). */
    val license: String = "LicenseRef-Trimio",
    val titleFa: String = "",
    val titleEn: String = "",
    /** What it improves in an edit, one line each. */
    val summaryFa: String = "",
    val summaryEn: String = "",
    /** Pictures, sounds or looks inside. */
    val items: Int = 0,
    /** Bytes on disk once unpacked (≈ [size]: the container is not compressed). */
    val unpacked: Long = 0,
    /** Suggested on home after setup (a small, broadly useful pack). */
    val recommended: Boolean = false,
) {
    fun signedBytes(): ByteArray = listOf(FORMAT, id, kind.name, version, size, sha256.lowercase(), minApp, license).joinToString("\n").encodeToByteArray()

    /** Imported on this device by its owner (not signed; never offered by a channel). */
    val local: Boolean get() = keyId == LOCAL_KEY

    companion object {
        const val FORMAT = "trimio-pack/1"
        const val LOCAL_KEY = "local"
    }
}

/** The pack's signature or key is not trusted; nothing was downloaded. */
class PackSignatureException(message: String) : Exception(message)

/** The payload is not the signed one (size or hash), or its container is malformed; it was discarded. */
class PackIntegrityException(message: String) : Exception(message)

/** Not enough free space for the download and its unpacked files; nothing was written. */
class PackSpaceException(val needed: Long, val free: Long) : Exception("needs $needed bytes, $free free")

/** The transfer stopped early; the partial file is kept, and the next install resumes it. */
class PackIncompleteException(message: String) : Exception(message)

private fun untrusted(message: String): Nothing = throw PackSignatureException(message)

private fun broken(message: String): Nothing = throw PackIntegrityException(message)

/**
 * The pack container (`.tpk`): `TPK1`, a file count, then per file a UTF-8 path (u16 length), a
 * u64 size and the bytes. Deliberately tiny: no compression (audio and models are already
 * compressed), no links, no absolute paths — so unpacking cannot write outside the pack's folder.
 */
object PackArchive {
    private val MAGIC = "TPK1".encodeToByteArray()
    private const val MAX_FILES = 50_000

    fun write(files: Map<String, ByteArray>): ByteArray {
        val out = kotlinx.io.Buffer()
        out.write(MAGIC)
        out.writeInt(files.size)
        for ((name, data) in files) {
            require(safe(name)) { "unsafe path $name" }
            val n = name.encodeToByteArray()
            out.writeShort(n.size.toShort()); out.write(n); out.writeLong(data.size.toLong()); out.write(data)
        }
        return out.readByteArray()
    }

    fun read(data: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        val source = kotlinx.io.Buffer().apply { write(data) }
        entries(source, data.size.toLong()) { name, size, src -> files[name] = src.readByteArray(size.toInt()) }
        return files
    }

    /** Unpacks the container in [source] ([length] bytes) file by file into [into]; returns the files written. */
    fun unpack(source: Source, length: Long, into: Path, fs: FileSystem): Int = entries(source, length) { name, size, src ->
        val path = Path(into, name)
        path.parent?.let { fs.createDirectories(it) }
        fs.sink(path).buffered().use { sink -> if (size > 0) src.readTo(sink, size) }
    }

    /** Walks the container's entries, checking every path and size against what is left of [length]. */
    private fun entries(source: Source, length: Long, onFile: (String, Long, Source) -> Unit): Int {
        var left = length
        fun need(n: Long) { if (n < 0 || n > left) broken("truncated pack"); left -= n }
        need(MAGIC.size.toLong() + 4)
        if (!source.readByteArray(MAGIC.size).contentEquals(MAGIC)) broken("not a pack")
        val count = source.readInt().toLong() and 0xffffffffL
        if (count > MAX_FILES) broken("too many files")
        repeat(count.toInt()) {
            need(2)
            val len = source.readShort().toInt() and 0xffff
            need(len.toLong())
            val name = source.readByteArray(len).decodeToString()
            if (!safe(name)) broken("unsafe path $name")
            need(8)
            val size = source.readLong()
            need(size)
            onFile(name, size, source)
        }
        return count.toInt()
    }

    /** A relative path inside the pack: no root, no `..`, no backslashes, no empty segments. */
    fun safe(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && ':' !in name && name.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

/**
 * What is installed per pack: the active release and the one before it (kept for [PackStore.rollback]).
 */
@Serializable
data class InstalledPack(val manifest: PackManifest, val previous: PackManifest? = null)

/**
 * Installs signed packs into [directory], separately from the app's own releases: styles, art,
 * sound, music, looks and models update on their own schedule. Order of trust: the manifest's
 * signature is verified against [keys] (pinned in the app, by key id) before anything is
 * downloaded; the payload streams into a `.part` file (resumed with HTTP Range after a pause or a
 * dropped connection); its size and SHA-256 must then match the signed values; it is unpacked into
 * a staging folder and moved into place atomically; only then does the state file switch to it.
 * A failure at any step leaves the active release untouched. The release before is kept, so an
 * update can be rolled back without a download.
 *
 * Layout: `<dir>/<id>/<version>/…files…`, `<dir>/packs.json`, partial downloads in `<dir>/.downloads/`.
 */
class PackStore(
    private val directory: Path,
    private val client: HttpClient,
    /** Trusted Ed25519 public keys by key id (32 bytes each). */
    private val keys: Map<String, ByteArray>,
    private val fs: FileSystem = SystemFileSystem,
    /** This app's build: packs that need a newer one are refused. */
    private val appBuild: Int = Int.MAX_VALUE,
    /** Free bytes where packs are kept; null when the platform cannot tell (then no check). */
    private val freeBytes: () -> Long? = { null },
) {
    fun installed(): Map<String, InstalledPack> = state()

    /** The folder of [id]'s active release, or null when it is not installed. */
    fun activeDir(id: String): Path? = state()[id]?.let { Path(directory, id, it.manifest.version.toString()) }?.takeIf { fs.exists(it) }

    /** Verifies [manifest] without touching the network or the disk. */
    fun verify(manifest: PackManifest) {
        val key = keys[manifest.keyId] ?: untrusted("${manifest.id}: unknown key ${manifest.keyId}")
        val sig = hex(manifest.signature) ?: untrusted("${manifest.id}: malformed signature")
        if (!Ed25519.verify(key, manifest.signedBytes(), sig)) untrusted("${manifest.id}: bad signature")
    }

    /** Bytes of [manifest] already downloaded (a paused or interrupted transfer). */
    fun partial(manifest: PackManifest): Long = fs.metadataOrNull(partOf(manifest))?.size ?: 0L

    /** Room the install of [manifest] still needs: the rest of the download, then its unpacked files. */
    fun needed(manifest: PackManifest): Long = (manifest.size - partial(manifest)).coerceAtLeast(0) + maxOf(manifest.unpacked, manifest.size) + SPACE_MARGIN

    /**
     * Installs [manifest] (a no-op when it is already active); returns the active folder.
     * [onProgress] gets the bytes downloaded so far and the total. Cancelling keeps the partial
     * download, so the next call resumes it.
     */
    suspend fun install(manifest: PackManifest, onProgress: (Long, Long) -> Unit = { _, _ -> }): Path {
        verify(manifest)
        if (manifest.minApp > appBuild) untrusted("${manifest.id}: needs app build ${manifest.minApp}")
        checkId(manifest.id)
        val current = state()[manifest.id]
        if (current != null && sameRelease(current.manifest, manifest)) {
            activeDir(manifest.id)?.let { return it }
        } else if (current != null && !current.manifest.local && manifest.version <= current.manifest.version) {
            untrusted("${manifest.id}: v${manifest.version} is not newer than v${current.manifest.version}")
        }
        checkSpace(manifest)
        val part = fetch(manifest, onProgress)
        val staging = Path(directory, manifest.id, "${manifest.version}.staging")
        unpack(manifest, part, staging)
        return commit(manifest, current, staging).also { fs.delete(part, mustExist = false) }
    }

    private fun checkSpace(manifest: PackManifest) {
        val free = freeBytes() ?: return
        if (free < needed(manifest)) throw PackSpaceException(needed(manifest), free)
    }

    /** Unpacks the verified payload; a malformed container is discarded with its download. */
    private fun unpack(manifest: PackManifest, part: Path, staging: Path) {
        val failure = try {
            deleteTree(staging)
            fs.createDirectories(staging)
            fs.source(part).buffered().use { PackArchive.unpack(it, manifest.size, staging, fs) }
            null
        } catch (e: PackIntegrityException) {
            e
        } catch (e: kotlinx.io.EOFException) {
            PackIntegrityException("${manifest.id}: truncated pack (${e.message})")
        }
        if (failure != null) {
            deleteTree(staging)
            fs.delete(part, mustExist = false)
            throw failure
        }
    }

    /**
     * Installs files imported on this device (the creator's own animations) as the local pack
     * [id]: no signature (the owner chose the files), never replaced by a channel's release.
     */
    fun installLocal(id: String, kind: PackKind, titleFa: String, titleEn: String, files: Map<String, ByteArray>, items: Int = files.size): Path {
        checkId(id)
        val current = state()[id]
        val version = (current?.manifest?.version ?: 0) + 1
        val bytes = files.values.sumOf { it.size.toLong() }
        val manifest = PackManifest(
            id, kind, version, bytes, "", emptyList(), PackManifest.LOCAL_KEY, "",
            license = "LicenseRef-Owner", titleFa = titleFa, titleEn = titleEn, items = items, unpacked = bytes,
        )
        val staging = Path(directory, id, "$version.staging")
        deleteTree(staging)
        fs.createDirectories(staging)
        for ((name, data) in files) {
            require(PackArchive.safe(name)) { "unsafe path $name" }
            val path = Path(staging, name)
            path.parent?.let { fs.createDirectories(it) }
            fs.sink(path).buffered().use { it.write(data) }
        }
        return commit(manifest, current, staging)
    }

    /** Back to the release before the active one; false when there is none. */
    fun rollback(id: String): Boolean {
        val entry = state()[id] ?: return false
        val previous = entry.previous ?: return false
        if (!fs.exists(Path(directory, id, previous.version.toString()))) return false
        writeState(state() + (id to InstalledPack(previous, previous = null)))
        deleteTree(Path(directory, id, entry.manifest.version.toString()))
        return true
    }

    fun uninstall(id: String) {
        deleteTree(Path(directory, id))
        discardPartial(id)
        writeState(state() - id)
    }

    /** Forgets every partial download of [id]. */
    fun discardPartial(id: String) {
        val downloads = Path(directory, DOWNLOADS)
        if (!fs.exists(downloads)) return
        fs.list(downloads).filter { it.name.startsWith("$id-v") }.forEach { fs.delete(it, mustExist = false) }
    }

    /** Bytes [id]'s active release takes on disk. */
    fun diskSize(id: String): Long = activeDir(id)?.let(::treeSize) ?: 0L

    private fun sameRelease(a: PackManifest, b: PackManifest) = a.version == b.version && a.sha256.equals(b.sha256, true) && a.keyId == b.keyId

    private fun checkId(id: String) {
        if (!PackArchive.safe(id) || '/' in id || id == DOWNLOADS || id.startsWith(".")) broken("bad pack id $id")
    }

    private fun commit(manifest: PackManifest, current: InstalledPack?, staging: Path): Path {
        val packDir = Path(directory, manifest.id)
        val target = Path(packDir, manifest.version.toString())
        deleteTree(target)
        fs.atomicMove(staging, target)
        // Switch, then forget the release two back (a local pack keeps only the newest).
        val keep = current?.manifest?.takeIf { !manifest.local && it.version != manifest.version }
        listOfNotNull(current?.previous, current?.manifest?.takeIf { keep == null }).map { it.version }.filter { it != manifest.version }
            .forEach { deleteTree(Path(packDir, it.toString())) }
        writeState(state() + (manifest.id to InstalledPack(manifest, previous = keep)))
        return target
    }

    /** Downloads (or resumes) the payload into its `.part` file and checks it against the signed size and hash. */
    private suspend fun fetch(m: PackManifest, onProgress: (Long, Long) -> Unit): Path {
        val part = partOf(m)
        fs.createDirectories(Path(directory, DOWNLOADS))
        var last: Exception = PackIntegrityException("${m.id}: no URL")
        for (url in m.urls) {
            try {
                download(url, part, m.size, onProgress)
                checkPayload(m, part)
                return part
            } catch (e: CancellationException) {
                throw e
            } catch (e: PackIntegrityException) {
                fs.delete(part, mustExist = false)
                last = e
            } catch (e: IllegalStateException) {
                last = e
            } catch (e: kotlinx.io.IOException) {
                last = e
            } catch (e: PackIncompleteException) {
                last = e
            }
        }
        throw last
    }

    private suspend fun download(url: String, part: Path, size: Long, onProgress: (Long, Long) -> Unit) {
        var have = fs.metadataOrNull(part)?.size ?: 0L
        if (have > size) { fs.delete(part, mustExist = false); have = 0 }
        if (have == size) return
        onProgress(have, size)
        client.prepareGet(url) { if (have > 0) header(HttpHeaders.Range, "bytes=$have-") }.execute { response ->
            check(response.status.isSuccess()) { "HTTP ${response.status.value} from $url" }
            // A server that ignores Range sends everything again: start over.
            val append = have > 0 && response.status == HttpStatusCode.PartialContent
            if (!append) have = 0
            val channel = response.bodyAsChannel()
            fs.sink(part, append = append).buffered().use { sink ->
                while (!channel.isClosedForRead) {
                    val chunk = channel.readRemaining(CHUNK).readByteArray()
                    if (chunk.isEmpty()) continue
                    // A server sending more than the signed size is cut off, not stored.
                    if (have + chunk.size > size) broken("payload larger than signed ($size bytes)")
                    sink.write(chunk)
                    have += chunk.size
                    onProgress(have, size)
                }
            }
        }
        if (have < size) throw PackIncompleteException("received $have of $size bytes")
    }

    private fun checkPayload(m: PackManifest, part: Path) {
        val size = fs.metadataOrNull(part)?.size ?: 0L
        if (size != m.size) broken("${m.id}: size $size, signed ${m.size}")
        val digest = SHA256()
        fs.source(part).buffered().use { source ->
            val buffer = ByteArray(CHUNK.toInt())
            while (true) {
                val n = source.readAtMostTo(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        if (!hexOf(digest.digest()).equals(m.sha256, ignoreCase = true)) broken("${m.id}: checksum mismatch")
    }

    private fun partOf(m: PackManifest) = Path(directory, DOWNLOADS, "${m.id}-v${m.version}-${m.sha256.take(HASH_TAG)}.part")

    private fun treeSize(path: Path): Long {
        val meta = fs.metadataOrNull(path) ?: return 0L
        return if (meta.isDirectory) fs.list(path).sumOf(::treeSize) else meta.size
    }

    private fun deleteTree(path: Path) {
        if (!fs.exists(path)) return
        if (fs.metadataOrNull(path)?.isDirectory == true) fs.list(path).forEach(::deleteTree)
        fs.delete(path, mustExist = false)
    }

    private fun state(): Map<String, InstalledPack> {
        val file = Path(directory, STATE)
        if (!fs.exists(file)) return emptyMap()
        return runCatching { StateJson.decodeFromString(StateSerializer, fs.source(file).buffered().use { it.readByteArray().decodeToString() }) }.getOrDefault(emptyMap())
    }

    private fun writeState(entries: Map<String, InstalledPack>) {
        fs.createDirectories(directory)
        val tmp = Path(directory, "$STATE.tmp")
        fs.sink(tmp).buffered().use { it.write(StateJson.encodeToString(StateSerializer, entries).encodeToByteArray()) }
        fs.atomicMove(tmp, Path(directory, STATE))
    }

    companion object {
        private const val CHUNK = 256L * 1024
        private const val STATE = "packs.json"
        private const val DOWNLOADS = ".downloads"
        private const val HASH_TAG = 12

        /** Kept free beyond what a pack takes, so an install never fills the disk. */
        const val SPACE_MARGIN = 64L * 1024 * 1024
        private val StateJson = Json { ignoreUnknownKeys = true }
        private val StateSerializer = MapSerializer(String.serializer(), InstalledPack.serializer())

        fun sha256Hex(bytes: ByteArray): String = hexOf(SHA256().digest(bytes))

        private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        fun hex(s: String): ByteArray? {
            if (s.length % 2 != 0) return null
            return runCatching { ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }.getOrNull()
        }
    }
}

/**
 * What ships inside the app (the offline core) and what is downloaded. The core is small and
 * complete: the procedural composer and SFX palettes are code, the bundled styles, the art index,
 * its starter pictures and the core art set are resources; a first edit needs no network.
 * Everything heavier — models, art, recorded sound and music, overlays, looks, further styles — is
 * a [PackManifest] in a channel's signed index ([PackChannel]).
 */
object PackCatalog {
    /** Content always available offline, by kind (descriptive: these are code and app resources, not packs). */
    val core: Map<PackKind, List<String>> = mapOf(
        PackKind.Music to listOf("procedural composer: 6 moods × sections (intro, verse, build, drop, breakdown, outro)"),
        PackKind.Sound to listOf("procedural SFX: 15 cues × 6 palettes"),
        PackKind.Style to listOf("bundled style packs (engine/styles resources)"),
        PackKind.Art to listOf("art index (noto, line-md and the pack sets), starter pictures, core set"),
    )
}
