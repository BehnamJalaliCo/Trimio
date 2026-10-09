package io.trimio.engine.styles

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import io.trimio.core.designsystem.shader.TrimioShader
import io.trimio.core.model.style.DesignStyle
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class StylePackTest {

    private val repo = StylePackRepository()

    @Test
    fun bundledPacksLoadValidateAndMatchTheCatalogue(): Unit = runBlocking {
        val packs = repo.all()
        assertEquals(listOf("neobrutalism", "liquid-glass", "kinetic-typography"), packs.map { it.id }, "gallery order follows the 28-style list")
        for (pack in packs) {
            assertEquals(emptyList(), StylePackValidator.validate(pack), pack.id)
            val canonical = assertNotNull(DesignStyle.fromId(pack.id), "${pack.id} must be one of the 28 styles")
            assertEquals(canonical.family, pack.family)
            assertTrue(pack.tags.any { t -> t.any { it in '؀'..'ۿ' } }, "${pack.id} needs Persian tags for prompt matching")
        }
    }

    @Test
    fun packShadersCompileWithSkia(): Unit = runBlocking {
        for (pack in repo.all()) for ((name, src) in pack.spec.shaders) {
            assertTrue(runCatching { TrimioShader(src) }.isSuccess, "${pack.id}/$name failed to compile")
        }
    }

    private suspend fun keyPair(): Pair<ByteArray, ByteArray> {
        val pair = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P256).generateKey()
        return pair.publicKey.encodeToByteArray(EC.PublicKey.Format.DER) to pair.privateKey.encodeToByteArray(EC.PrivateKey.Format.DER)
    }

    @Test
    fun signedPackVerifiesAndTamperingIsRejected(): Unit = runBlocking {
        val (public, private) = keyPair()
        val keys = mapOf("test-key" to public)
        val pack = repo.get("neobrutalism")!!.copy(version = "1.1.0")
        val envelope = StylePackCodec.sign(pack, "test-key", private)

        assertEquals(pack, StylePackCodec.decodeSigned(envelope, keys))

        // Flip one byte of the payload: the signature no longer matches.
        val parsed = StylePackCodec.json.decodeFromString(SignedPackEnvelope.serializer(), envelope)
        val bytes = Base64.decode(parsed.payload).also { it[40] = (it[40] + 1).toByte() }
        val tampered = StylePackCodec.json.encodeToString(SignedPackEnvelope.serializer(), parsed.copy(payload = Base64.encode(bytes)))
        assertFailsWith<StylePackException> { StylePackCodec.decodeSigned(tampered, keys) }

        assertFailsWith<StylePackException> { StylePackCodec.decodeSigned(envelope, mapOf("other" to public)) }
        val (otherPublic, _) = keyPair()
        assertFailsWith<StylePackException> { StylePackCodec.decodeSigned(envelope, mapOf("test-key" to otherPublic)) }
        Unit
    }

    @Test
    fun signedButInvalidPackIsRejected(): Unit = runBlocking {
        val (public, private) = keyPair()
        val bad = repo.get("liquid-glass")!!.let { it.copy(spec = it.spec.copy(palette = it.spec.palette.copy(text = "white"))) }
        val envelope = StylePackCodec.sign(bad, "k", private)
        val e = assertFailsWith<StylePackException> { StylePackCodec.decodeSigned(envelope, mapOf("k" to public)) }
        assertTrue("palette.text" in e.message.orEmpty())
        Unit
    }

    @Test
    fun newerDownloadedVersionOverridesBundled(): Unit = runBlocking {
        val (public, private) = keyPair()
        val store = object : InstalledPackStore {
            val files = mutableMapOf<String, String>()
            override suspend fun readAll() = files.values.toList()
            override suspend fun write(id: String, envelope: String) { files[id] = envelope }
            override suspend fun delete(id: String) { files.remove(id) }
        }
        val repo = StylePackRepository(store, mapOf("k" to public))
        val updated = repo.get("liquid-glass")!!.let { it.copy(version = "1.2.0", spec = it.spec.copy(motion = it.spec.motion.copy(energy = 0.3f))) }
        repo.install(StylePackCodec.sign(updated, "k", private))
        assertEquals("1.2.0", repo.get("liquid-glass")!!.version)
        assertEquals(0.3f, repo.get("liquid-glass")!!.spec.motion.energy)

        // An older download never replaces the bundled pack.
        store.files.clear()
        repo.install(StylePackCodec.sign(updated.copy(version = "0.9.0"), "k", private))
        assertEquals("1.0.0", repo.get("liquid-glass")!!.version)
    }
}
