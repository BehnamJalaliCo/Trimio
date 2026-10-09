package io.trimio.core.api

import io.trimio.engine.models.ModelSpec
import io.trimio.engine.styles.PackKeys
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable

/** The last good answer from the server, cached so the app starts with it offline. */
@Serializable
data class RemoteSnapshot(val config: RemoteConfig = RemoteConfig(), val catalog: Catalog = Catalog())

data class RemoteState(
    val snapshot: RemoteSnapshot = RemoteSnapshot(),
    /** This build is older than [RemoteConfig.minVersionCode]; the app blocks with an update prompt. */
    val updateRequired: Boolean = false,
) {
    val config get() = snapshot.config
}

/**
 * Remote configuration, model catalogue and style-pack updates. Starts from the cached snapshot
 * (synchronously, so revocations and model overrides apply before anything loads), then [refresh]
 * fetches a new one in the background. Every failure leaves the previous state in place: the app
 * never depends on the server to work.
 */
class RemoteRepository(
    private val service: RemoteService,
    private val cacheFile: Path?,
    private val packs: StylePackRepository,
    private val versionCode: Int,
    private val trustedKeys: MutableMap<String, ByteArray> = PackKeys.trusted,
    private val fs: FileSystem = SystemFileSystem,
) {
    private val _state = MutableStateFlow(stateOf(readCache() ?: RemoteSnapshot()))
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    init {
        revoke(_state.value.config)
    }

    /** Fetches config and catalogue, installs newer style packs; returns false when offline or rejected. */
    suspend fun refresh(): Boolean {
        val snapshot = try {
            RemoteSnapshot(service.config(), service.catalog())
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return false
        }
        val revokedNow = revoke(snapshot.config)
        writeCache(snapshot)
        _state.value = stateOf(snapshot)
        if (revokedNow) packs.invalidate()
        syncPacks(snapshot.catalog)
        return true
    }

    /** The built-in catalogue with the server's additions and corrections (same id wins), for the next start. */
    fun models(builtIn: List<ModelSpec>): List<ModelSpec> {
        val remote = _state.value.snapshot.catalog.models.filter(::acceptable).associateBy { it.id }
        return builtIn.map { remote[it.id] ?: it } + remote.values.filter { r -> builtIn.none { it.id == r.id } }
    }

    private suspend fun syncPacks(catalog: Catalog) {
        for (entry in catalog.styles) {
            val current = packs.get(entry.id)
            if (current != null && !StylePackRepository.newer(entry.version, current.version)) continue
            // A pack that fails to download or verify is skipped; the bundled one stays.
            runCatching { packs.install(service.pack(entry.id)) }
        }
    }

    private fun revoke(config: RemoteConfig): Boolean {
        val hit = config.revokedPackKeys.filter { it in trustedKeys }
        hit.forEach(trustedKeys::remove)
        return hit.isNotEmpty()
    }

    /** Remote models must download over HTTPS and carry a full hash; anything else is ignored. */
    private fun acceptable(spec: ModelSpec): Boolean =
        spec.urls.isNotEmpty() && spec.urls.all { it.startsWith("https://") } &&
            spec.sha256.length == HASH_LENGTH && spec.sha256.all { it in '0'..'9' || it in 'a'..'f' } && spec.sizeBytes > 0

    private fun stateOf(snapshot: RemoteSnapshot) = RemoteState(snapshot, updateRequired = versionCode < snapshot.config.minVersionCode)

    private fun readCache(): RemoteSnapshot? = cacheFile?.takeIf(fs::exists)?.let { file ->
        runCatching { Api.json.decodeFromString(RemoteSnapshot.serializer(), fs.source(file).buffered().use { it.readString() }) }.getOrNull()
    }

    private fun writeCache(snapshot: RemoteSnapshot) {
        val file = cacheFile ?: return
        runCatching {
            file.parent?.let(fs::createDirectories)
            val tmp = Path("$file.tmp")
            fs.sink(tmp).buffered().use { it.writeString(Api.json.encodeToString(RemoteSnapshot.serializer(), snapshot)) }
            fs.atomicMove(tmp, file)
        }
    }

    private companion object {
        const val HASH_LENGTH = 64
    }
}
