package io.trimio.core.api

import io.trimio.engine.models.ModelSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The contract between the app and Trimio's catalogue server. The server only distributes data:
 * remote configuration, the model catalogue and signed style packs. It never sees user media,
 * prompts or transcripts.
 */
object Api {
    const val BASE_URL = "https://api.trimio.app"
    const val VERSION = "v1"
    const val CONFIG = "/$VERSION/config"
    const val CATALOG = "/$VERSION/catalog"
    fun pack(id: String) = "/$VERSION/packs/$id"
    const val ADMIN_PACKS = "/$VERSION/admin/packs"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

/** Feature flags and kill switches, fetched at start and cached; defaults apply offline. */
@Serializable
data class RemoteConfig(
    /** Builds older than this must update (security or format change); the app shows a blocking prompt. */
    val minVersionCode: Int = 0,
    val flags: Map<String, Boolean> = emptyMap(),
    /**
     * Pack signing key ids the app must stop trusting (a leaked key). The server can only revoke:
     * trusted keys ship inside the app, so a compromised server cannot sign shader code.
     */
    val revokedPackKeys: Set<String> = emptySet(),
    /** Store product of the paid tier; sold only while the [PRO] flag is on. */
    val proProductId: String? = null,
    /** Optional banner (both languages). */
    val announcementFa: String? = null,
    val announcementEn: String? = null,
) {
    fun flag(name: String, default: Boolean = false): Boolean = flags[name] ?: default

    /** The product to sell, or null while the paywall is off (then everything is free). */
    val paywallProduct: String? get() = proProductId?.takeIf { flag(PRO) }

    companion object {
        /** Known flags; unknown ones are ignored by older apps. */
        const val PRO = "pro"
    }
}

@Serializable
data class StyleEntry(val id: String, val version: String, val nameFa: String, val nameEn: String)

/** Everything downloadable. Models extend or replace the built-in catalogue by id. */
@Serializable
data class Catalog(
    val models: List<ModelSpec> = emptyList(),
    val styles: List<StyleEntry> = emptyList(),
)
