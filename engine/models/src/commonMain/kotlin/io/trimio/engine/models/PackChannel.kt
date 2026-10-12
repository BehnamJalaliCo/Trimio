package io.trimio.engine.models

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The public keys content packs are signed with (the private key never enters the app or the repository). */
object PackKeys {
    /** Key id → raw 32-byte Ed25519 public key. Signed by tools/packs (secret TRIMIO_PACK_KEY in CI). */
    val content: Map<String, ByteArray> = mapOf(
        "packs-2026-1" to PackStore.hex("d8b33fcc8ecf76fbbdf5dc6ce454642a54540948ac109fce2fbe142f80770810")!!,
    )
}

/**
 * Where packs are published: a base URL holding `index.json` (every pack's signed manifest) and
 * the `.tpk` payloads next to it. For now a GitHub release of this repository (tag `packs-v1`,
 * assets of at most 2 GB each); later the team's own server — only [bases] changes, since the
 * manifests carry relative URLs and are trusted by signature, not by host. No secret is needed to
 * read a channel.
 */
data class PackChannel(val bases: List<String> = listOf(DEFAULT_BASE)) {
    init {
        require(bases.isNotEmpty()) { "a channel needs a base URL" }
    }

    /** [url] as the client fetches it: absolute URLs as they are, relative ones under each base (mirrors in order). */
    fun resolve(url: String): List<String> = if ("://" in url) listOf(url) else bases.map { it.trimEnd('/') + "/" + url.trimStart('/') }

    /** [m] with every URL resolved against the bases. */
    fun resolve(m: PackManifest): PackManifest = m.copy(urls = m.urls.flatMap(::resolve).distinct())

    companion object {
        const val DEFAULT_BASE = "https://github.com/BehnamJalaliCo/Trimio/releases/download/packs-v1"
        const val INDEX = "index.json"
        const val FORMAT = "trimio-packs/1"
    }
}

/**
 * The channel's catalogue: fetched from the first base that answers, cached on disk so the
 * library screen works offline, and parsed leniently — a pack of a kind this build does not know
 * (or a malformed entry) is skipped, not fatal. Manifests are not trusted here; [PackStore]
 * verifies each signature before it downloads anything.
 */
class PackIndexClient(
    private val client: HttpClient,
    private val channel: PackChannel,
    /** Last good index (null: none kept). */
    private val cache: Path?,
    private val fs: FileSystem = SystemFileSystem,
    private val timeoutMs: Long = 15_000,
) {
    /** The cached catalogue, or empty. */
    fun cached(): List<PackManifest> = cache?.takeIf { fs.exists(it) }?.let { path ->
        runCatching { parse(fs.source(path).buffered().use { it.readByteArray().decodeToString() }) }.getOrNull()
    }.orEmpty()

    /** The live catalogue (also cached), or null when no base answers. */
    suspend fun fetch(): List<PackManifest>? {
        for (base in channel.bases) {
            val text = withTimeoutOrNull(timeoutMs) {
                runCatching { client.get(base.trimEnd('/') + "/" + PackChannel.INDEX).takeIf { it.status.isSuccess() }?.bodyAsText() }.getOrNull()
            } ?: continue
            val packs = runCatching { parse(text) }.getOrNull() ?: continue
            cache?.let { path -> runCatching { save(path, text) } }
            return packs
        }
        return null
    }

    private fun save(path: Path, text: String) {
        path.parent?.let { fs.createDirectories(it) }
        val tmp = Path(path.toString() + ".tmp")
        fs.sink(tmp).buffered().use { it.write(text.encodeToByteArray()) }
        fs.atomicMove(tmp, path)
    }

    /** The manifests of an index document, URLs resolved against the channel. */
    fun parse(text: String): List<PackManifest> {
        val root = json.parseToJsonElement(text).jsonObject
        val format = root["format"]?.jsonPrimitive?.content
        require(format == null || format == PackChannel.FORMAT) { "unknown index format $format" }
        val packs = root["packs"] as? JsonArray ?: return emptyList()
        return packs.mapNotNull { runCatching { json.decodeFromJsonElement(PackManifest.serializer(), it) }.getOrNull() }
            .filter { !it.local }.map(channel::resolve)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
