package io.trimio.server

import io.trimio.core.api.Api
import io.trimio.core.api.Catalog
import io.trimio.core.api.RemoteConfig
import io.trimio.core.api.StyleEntry
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.styles.StylePack
import io.trimio.engine.styles.StylePackCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Everything the server distributes, kept as plain files so a deploy is a directory copy:
 *  - `config.json`: [RemoteConfig],
 *  - `models.json`: extra or updated [ModelSpec]s,
 *  - `packs/<id>.json`: signed style-pack envelopes, served byte for byte.
 * Packs are verified on upload and again on load, so a file edited by hand is never served.
 */
class CatalogStore(private val dir: Path, private val trustedKeys: Map<String, ByteArray>) {

    private val mutex = Mutex()
    private val packsDir = dir.resolve("packs")
    private var packs: Map<String, Pair<StylePack, String>>? = null

    init {
        packsDir.createDirectories()
    }

    fun config(): RemoteConfig {
        val file = dir.resolve(CONFIG_FILE)
        return if (file.exists()) Api.json.decodeFromString(RemoteConfig.serializer(), file.readText()) else RemoteConfig()
    }

    fun saveConfig(config: RemoteConfig) = atomicWrite(dir.resolve(CONFIG_FILE), Api.json.encodeToString(RemoteConfig.serializer(), config))

    suspend fun catalog(): Catalog = Catalog(
        models = models(),
        styles = loadPacks().values.map { (pack, _) -> StyleEntry(pack.id, pack.version, pack.nameFa, pack.nameEn) }.sortedBy { it.id },
    )

    fun saveModels(models: List<ModelSpec>) = atomicWrite(dir.resolve(MODELS_FILE), Api.json.encodeToString(ListSerializer(ModelSpec.serializer()), models))

    suspend fun envelope(id: String): Pair<StylePack, String>? = loadPacks()[id]

    /** Verifies [envelope] with the trusted keys and stores it; throws [io.trimio.engine.styles.StylePackException]. */
    suspend fun putPack(envelope: String): StylePack {
        val pack = StylePackCodec.decodeSigned(envelope, trustedKeys)
        mutex.withLock {
            atomicWrite(packsDir.resolve("${pack.id}.json"), envelope)
            packs = null
        }
        return pack
    }

    private fun models(): List<ModelSpec> {
        val file = dir.resolve(MODELS_FILE)
        return if (file.exists()) Api.json.decodeFromString(ListSerializer(ModelSpec.serializer()), file.readText()) else emptyList()
    }

    private suspend fun loadPacks(): Map<String, Pair<StylePack, String>> = mutex.withLock {
        packs ?: packsDir.listDirectoryEntries("*.json").mapNotNull { file ->
            val text = file.readText()
            runCatching { StylePackCodec.decodeSigned(text, trustedKeys) }.getOrNull()?.let { it.id to (it to text) }
        }.toMap().also { packs = it }
    }

    private fun atomicWrite(target: Path, text: String) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        tmp.writeText(text)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val CONFIG_FILE = "config.json"
        const val MODELS_FILE = "models.json"
    }
}
