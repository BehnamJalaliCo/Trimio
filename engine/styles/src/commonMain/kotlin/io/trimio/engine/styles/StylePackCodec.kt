package io.trimio.engine.styles

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class StylePackException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Trusted public keys for pack signatures, by key id. Empty until the project's signing key is
 * generated (see docs/STYLE_PACKS.md); until then only bundled packs load, which is the safe default.
 */
object PackKeys {
    val trusted: MutableMap<String, ByteArray> = mutableMapOf()
}

@OptIn(ExperimentalEncodingApi::class)
object StylePackCodec {

    val json = Json {
        ignoreUnknownKeys = true // newer optional fields must not break older apps
        encodeDefaults = true
        prettyPrint = true
    }

    /** Bundled packs: plain manifests shipped inside the app, trusted by construction. */
    fun decodeBundled(text: String): StylePack = check(json.decodeFromString(StylePack.serializer(), text))

    fun encode(pack: StylePack): String = json.encodeToString(StylePack.serializer(), pack)

    /** Downloaded packs: verify the signature first, then decode and validate the payload. */
    suspend fun decodeSigned(text: String, keys: Map<String, ByteArray> = PackKeys.trusted): StylePack {
        val envelope = try {
            json.decodeFromString(SignedPackEnvelope.serializer(), text)
        } catch (e: Exception) {
            throw StylePackException("Not a style pack envelope", e)
        }
        if (envelope.format != SignedPackEnvelope.FORMAT) throw StylePackException("Unsupported pack format ${envelope.format}")
        val key = keys[envelope.keyId] ?: throw StylePackException("Unknown signing key ${envelope.keyId}")
        val payload = Base64.decode(envelope.payload)
        if (!verify(key, payload, Base64.decode(envelope.signature))) throw StylePackException("Signature check failed")
        return check(json.decodeFromString(StylePack.serializer(), payload.decodeToString()))
    }

    /** Builds a signed envelope; used by the signing tool and tests (servers sign in CI). */
    suspend fun sign(pack: StylePack, keyId: String, privateKeyDer: ByteArray): String {
        val payload = encode(pack).encodeToByteArray()
        val privateKey = ecdsa().privateKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PrivateKey.Format.DER, privateKeyDer)
        val signature = privateKey.signatureGenerator(SHA256, ECDSA.SignatureFormat.DER).generateSignature(payload)
        return json.encodeToString(
            SignedPackEnvelope.serializer(),
            SignedPackEnvelope(keyId = keyId, payload = Base64.encode(payload), signature = Base64.encode(signature)),
        )
    }

    private suspend fun verify(publicKeyDer: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val key = ecdsa().publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.DER, publicKeyDer)
        key.signatureVerifier(SHA256, ECDSA.SignatureFormat.DER).tryVerifySignature(data, signature)
    } catch (_: Exception) {
        false
    }

    private fun ecdsa() = CryptographyProvider.Default.get(ECDSA)

    private fun check(pack: StylePack): StylePack {
        val issues = StylePackValidator.validate(pack)
        if (issues.isNotEmpty()) throw StylePackException("Invalid pack ${pack.id}: " + issues.joinToString { "${it.field}: ${it.message}" })
        return pack
    }
}
