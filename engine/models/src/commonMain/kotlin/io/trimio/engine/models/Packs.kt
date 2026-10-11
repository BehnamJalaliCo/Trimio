package io.trimio.engine.models

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
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

/** What a downloadable pack carries. */
enum class PackKind { Style, Sound, Music, Model }

/**
 * One release of a downloadable pack, as the server's index lists it. The server signs
 * [signedBytes] — identity, version, size, payload hash, minimum app and licence — with its
 * Ed25519 key [keyId]; the payload is then bound by [sha256]. Mirrors ([urls]) are not signed: any
 * mirror is fine, since a payload that does not hash to the signed value is discarded.
 */
@Serializable
data class PackManifest(
    /** `<kind>.<name>`, e.g. `sound.cinematic-hits`. */
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
    /** SPDX id of the content's licence (CC0-1.0 for third-party audio, LicenseRef-Trimio for our own). */
    val license: String = "LicenseRef-Trimio",
    val titleFa: String = "",
    val titleEn: String = "",
) {
    fun signedBytes(): ByteArray = listOf(FORMAT, id, kind.name, version, size, sha256.lowercase(), minApp, license).joinToString("\n").encodeToByteArray()

    companion object {
        const val FORMAT = "trimio-pack/1"
    }
}

/** The pack's signature or key is not trusted; nothing was downloaded. */
class PackSignatureException(message: String) : Exception(message)

/** The payload is not the signed one (size or hash), or its container is malformed; it was discarded. */
class PackIntegrityException(message: String) : Exception(message)

/**
 * The pack container (`.tpk`): `TPK1`, a file count, then per file a UTF-8 path (u16 length), a
 * u64 size and the bytes. Deliberately tiny: no compression (audio and models are already
 * compressed), no links, no absolute paths — so unpacking cannot write outside the pack's folder.
 */
object PackArchive {
    private val MAGIC = "TPK1".encodeToByteArray()
    private const val MAX_FILES = 10_000

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
        var p = 0
        fun need(n: Long) { if (n < 0 || p + n > data.size) throw PackIntegrityException("truncated pack") }
        fun int(n: Int): Long { need(n.toLong()); var v = 0L; repeat(n) { v = (v shl 8) or (data[p++].toLong() and 0xff) }; return v }
        need(4)
        if (!data.copyOfRange(0, 4).contentEquals(MAGIC)) throw PackIntegrityException("not a pack")
        p = 4
        val count = int(4)
        if (count > MAX_FILES) throw PackIntegrityException("too many files")
        val files = LinkedHashMap<String, ByteArray>()
        repeat(count.toInt()) {
            val len = int(2).toInt()
            need(len.toLong())
            val name = data.copyOfRange(p, p + len).decodeToString().also { p += len }
            if (!safe(name)) throw PackIntegrityException("unsafe path $name")
            val size = int(8)
            need(size)
            files[name] = data.copyOfRange(p, p + size.toInt()).also { p += size.toInt() }
        }
        return files
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
 * Installs signed packs into [directory], separately from the app's own releases: styles, sound
 * palettes, music and models update on their own schedule. Order of trust: the manifest's
 * signature is verified against [keys] (pinned in the app, by key id) before anything is
 * downloaded; the payload's size and SHA-256 must then match the signed values; it is unpacked into
 * a staging folder and moved into place atomically; only then does the state file switch to it.
 * A failure at any step leaves the active release untouched. The release before is kept, so an
 * update can be rolled back without a download.
 *
 * Layout: `<dir>/<id>/<version>/…files…`, `<dir>/packs.json`.
 */
class PackStore(
    private val directory: Path,
    private val client: HttpClient,
    /** Trusted Ed25519 public keys by key id (32 bytes each). */
    private val keys: Map<String, ByteArray>,
    private val fs: FileSystem = SystemFileSystem,
    /** This app's build: packs that need a newer one are refused. */
    private val appBuild: Int = Int.MAX_VALUE,
) {
    fun installed(): Map<String, InstalledPack> = state()

    /** The folder of [id]'s active release, or null when it is not installed. */
    fun activeDir(id: String): Path? = state()[id]?.let { Path(directory, id, it.manifest.version.toString()) }?.takeIf { fs.exists(it) }

    /** Verifies [manifest] without touching the network or the disk. */
    fun verify(manifest: PackManifest) {
        val key = keys[manifest.keyId] ?: throw PackSignatureException("${manifest.id}: unknown key ${manifest.keyId}")
        val sig = hex(manifest.signature) ?: throw PackSignatureException("${manifest.id}: malformed signature")
        if (!Ed25519.verify(key, manifest.signedBytes(), sig)) throw PackSignatureException("${manifest.id}: bad signature")
    }

    /** Installs [manifest] (a no-op when it is already active); returns the active folder. */
    suspend fun install(manifest: PackManifest): Path {
        verify(manifest)
        if (manifest.minApp > appBuild) throw PackSignatureException("${manifest.id}: needs app build ${manifest.minApp}")
        if (!PackArchive.safe(manifest.id) || '/' in manifest.id) throw PackIntegrityException("bad pack id ${manifest.id}")
        val current = state()[manifest.id]
        if (current != null) {
            if (current.manifest.version == manifest.version && current.manifest.sha256.equals(manifest.sha256, true)) return activeDir(manifest.id)!!
            if (manifest.version <= current.manifest.version) throw PackSignatureException("${manifest.id}: v${manifest.version} is not newer than v${current.manifest.version}")
        }
        val payload = fetch(manifest)
        val files = PackArchive.read(payload)
        val packDir = Path(directory, manifest.id)
        val staging = Path(packDir, "${manifest.version}.staging")
        val target = Path(packDir, manifest.version.toString())
        deleteTree(staging)
        for ((name, data) in files) {
            val path = Path(staging, name)
            path.parent?.let { fs.createDirectories(it) }
            fs.sink(path).buffered().use { it.write(data) }
        }
        fs.createDirectories(staging)
        deleteTree(target)
        fs.atomicMove(staging, target)
        // Switch, then forget the release two back.
        current?.previous?.let { old -> if (old.version != manifest.version) deleteTree(Path(packDir, old.version.toString())) }
        writeState(state() + (manifest.id to InstalledPack(manifest, previous = current?.manifest)))
        return target
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
        writeState(state() - id)
    }

    private suspend fun fetch(m: PackManifest): ByteArray {
        var last: Exception? = null
        for (url in m.urls) {
            try {
                val bytes = download(url, m.size)
                if (bytes.size.toLong() != m.size) throw PackIntegrityException("${m.id}: size ${bytes.size}, signed ${m.size}")
                if (!sha256Hex(bytes).equals(m.sha256, ignoreCase = true)) throw PackIntegrityException("${m.id}: checksum mismatch")
                return bytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: PackIntegrityException("${m.id}: no URL")
    }

    private suspend fun download(url: String, size: Long): ByteArray = client.prepareGet(url).execute { response ->
        if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value} from $url")
        val channel = response.bodyAsChannel()
        val out = ArrayList<ByteArray>()
        var have = 0L
        while (!channel.isClosedForRead) {
            val chunk = channel.readRemaining(CHUNK).readByteArray()
            have += chunk.size
            // A server sending more than the signed size is cut off, not buffered.
            if (have > size) throw PackIntegrityException("payload larger than signed ($size bytes)")
            out += chunk
        }
        val all = ByteArray(have.toInt())
        var p = 0
        for (c in out) { c.copyInto(all, p); p += c.size }
        all
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
        private val StateJson = Json { ignoreUnknownKeys = true }
        private val StateSerializer = MapSerializer(String.serializer(), InstalledPack.serializer())

        fun sha256Hex(bytes: ByteArray): String = SHA256().digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        fun hex(s: String): ByteArray? {
            if (s.length % 2 != 0) return null
            return runCatching { ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }.getOrNull()
        }
    }
}

/**
 * What ships inside the app (the offline core) and what is downloaded. The core is small and
 * complete: the procedural composer and SFX palettes are code, the bundled styles are resources;
 * a first edit needs no network. Everything heavier — models, recorded sound and music packs,
 * further styles — is a [PackManifest] in the server's signed index.
 */
object PackCatalog {
    /** Content always available offline, by kind (descriptive: these are code and app resources, not packs). */
    val core: Map<PackKind, List<String>> = mapOf(
        PackKind.Music to listOf("procedural composer: 6 moods × sections (intro, verse, build, drop, breakdown, outro)"),
        PackKind.Sound to listOf("procedural SFX: 15 cues × 6 palettes"),
        PackKind.Style to listOf("bundled style packs (engine/styles resources)"),
    )
}
