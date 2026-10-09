package io.trimio.engine.styles

import io.trimio.core.model.style.RenderCost
import io.trimio.core.model.style.StyleFamily
import io.trimio.core.model.style.StyleSpec
import kotlinx.serialization.Serializable

/**
 * A design style as data: everything needed to render it plus how to present it in the gallery.
 * Packs contain no executable code (Google Play policy); shaders are GPU programs carried as text
 * inside [StyleSpec.shaders].
 */
@Serializable
data class StylePack(
    /** Manifest schema version; packs newer than [SUPPORTED_SCHEMA] are rejected, not guessed at. */
    val schema: Int = SUPPORTED_SCHEMA,
    val id: String,
    /** Semantic version of the pack; a higher version replaces an installed one. */
    val version: String,
    val nameFa: String,
    val nameEn: String,
    val descriptionFa: String = "",
    val descriptionEn: String = "",
    val family: StyleFamily,
    val cost: RenderCost,
    /** Words the Director matches against prompts ("energetic", "مینیمال", "crypto"...). */
    val tags: List<String> = emptyList(),
    val spec: StyleSpec,
) {
    companion object {
        const val SUPPORTED_SCHEMA = 1
    }
}

/**
 * How downloaded packs travel: the manifest bytes are signed as-is and carried base64-encoded,
 * so verification never depends on JSON formatting or key order.
 */
@Serializable
data class SignedPackEnvelope(
    val format: String = FORMAT,
    val keyId: String,
    /** Base64 of the UTF-8 [StylePack] JSON. */
    val payload: String,
    /** Base64 DER ECDSA P-256 / SHA-256 signature over the decoded payload bytes. */
    val signature: String,
) {
    companion object {
        const val FORMAT = "trimio-style-pack/1"
    }
}
