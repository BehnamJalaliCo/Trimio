package io.trimio.engine.models

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signed packs: Ed25519 against RFC 8032 and the JDK's own signer, and the store's install,
 * refusal and rollback. The keypair is generated here, per run: no private key exists in the repo.
 */
class PackStoreTest {

    private val server: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val stranger: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** The raw 32-byte key: the last 32 bytes of the X.509 encoding. */
    private fun raw(kp: KeyPair) = kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }

    private fun sign(kp: KeyPair, bytes: ByteArray): ByteArray = Signature.getInstance("Ed25519").run { initSign(kp.private); update(bytes); sign() }

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun unhex(s: String) = PackStore.hex(s)!!

    @Test
    fun rfc8032Vectors() {
        // Test 1 (empty message) and test 2 (one byte), RFC 8032 §7.1.
        val pk1 = unhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig1 = unhex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(Ed25519.verify(pk1, ByteArray(0), sig1))
        val pk2 = unhex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val sig2 = unhex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")
        assertTrue(Ed25519.verify(pk2, byteArrayOf(0x72), sig2))
        assertFalse(Ed25519.verify(pk2, byteArrayOf(0x73), sig2), "another message")
        assertFalse(Ed25519.verify(pk1, byteArrayOf(0x72), sig2), "another key")
        assertFalse(Ed25519.verify(pk2, byteArrayOf(0x72), sig2.copyOf().also { it[63] = (it[63] + 0x10).toByte() }), "S ≥ L is not canonical")
    }

    @Test
    fun signaturesVerifyOnlyForTheSignedBytesAndKey() {
        val rnd = java.util.Random(7)
        repeat(20) { k ->
            val msg = ByteArray(k * 13).also(rnd::nextBytes)
            val sig = sign(server, msg)
            assertTrue(Ed25519.verify(raw(server), msg, sig), "good #$k")
            assertFalse(Ed25519.verify(raw(stranger), msg, sig), "wrong key #$k")
            if (msg.isNotEmpty()) assertFalse(Ed25519.verify(raw(server), msg.copyOf().also { it[0] = (it[0] + 1).toByte() }, sig), "tampered #$k")
            assertFalse(Ed25519.verify(raw(server), msg, sig.copyOf().also { it[5] = (it[5] + 1).toByte() }), "tampered signature #$k")
        }
    }

    // ------------------------------------------------------------------------------- the store

    private val dir = Path(Files.createTempDirectory("packs").toString())
    private val payloads = mutableMapOf<String, ByteArray>()
    private var requests = 0
    private val client = HttpClient(MockEngine { req ->
        requests++
        val body = payloads[req.url.encodedPath.trimStart('/')] ?: return@MockEngine respond("missing", HttpStatusCode.NotFound)
        respond(body, HttpStatusCode.OK)
    })
    private val store = PackStore(dir, client, keys = mapOf("test-1" to raw(server)), appBuild = 100)

    private fun release(version: Int, files: Map<String, ByteArray>, signer: KeyPair = server, served: ByteArray? = null): PackManifest {
        val payload = PackArchive.write(files)
        val name = "sound.cinematic-v$version.tpk"
        payloads[name] = served ?: payload
        val unsigned = PackManifest(
            id = "sound.cinematic", kind = PackKind.Sound, version = version, size = payload.size.toLong(), sha256 = PackStore.sha256Hex(payload),
            urls = listOf("https://cdn.test/$name"), keyId = "test-1", signature = "", license = "CC0-1.0",
        )
        return unsigned.copy(signature = hex(sign(signer, unsigned.signedBytes())))
    }

    private fun read(p: Path) = SystemFileSystem.source(p).buffered().use { it.readByteArray() }

    @Test
    fun installVerifiesThenSwitchesAndRollsBack(): Unit = runBlocking {
        val v1 = release(1, mapOf("sfx/impact.wav" to byteArrayOf(1, 2, 3), "pack.json" to "{}".encodeToByteArray()))
        val d1 = store.install(v1)
        assertContentEquals(byteArrayOf(1, 2, 3), read(Path(d1, "sfx/impact.wav")))
        assertEquals(1, store.installed().getValue("sound.cinematic").manifest.version)

        val v2 = release(2, mapOf("sfx/impact.wav" to byteArrayOf(9, 9)))
        val d2 = store.install(v2)
        assertContentEquals(byteArrayOf(9, 9), read(Path(d2, "sfx/impact.wav")))
        assertEquals(1, store.installed().getValue("sound.cinematic").previous?.version)

        // Rollback without a download: v1's folder was kept.
        val before = requests
        assertTrue(store.rollback("sound.cinematic"))
        assertEquals(before, requests)
        assertEquals(1, store.installed().getValue("sound.cinematic").manifest.version)
        assertContentEquals(byteArrayOf(1, 2, 3), read(Path(store.activeDir("sound.cinematic")!!, "sfx/impact.wav")))
        assertFalse(store.rollback("sound.cinematic"), "only one step back")

        // Forward again; a replayed older release is refused.
        store.install(v2)
        assertFailsWith<PackSignatureException> { store.install(v1) }
        assertEquals(2, store.installed().getValue("sound.cinematic").manifest.version)
    }

    @Test
    fun aBadSignatureIsRefusedBeforeAnyDownload(): Unit = runBlocking {
        val forged = release(1, mapOf("a" to byteArrayOf(1)), signer = stranger)
        assertFailsWith<PackSignatureException> { store.install(forged) }
        val good = release(1, mapOf("a" to byteArrayOf(1)))
        assertFailsWith<PackSignatureException> { store.install(good.copy(version = 5)) }
        assertFailsWith<PackSignatureException> { store.install(good.copy(keyId = "unknown")) }
        assertFailsWith<PackSignatureException> { store.install(good.copy(signature = "zz")) }
        assertEquals(0, requests, "nothing was fetched for an untrusted manifest")
        assertNull(store.activeDir("sound.cinematic"))
    }

    @Test
    fun aTamperedPayloadNeverReplacesTheActiveRelease(): Unit = runBlocking {
        store.install(release(1, mapOf("a" to byteArrayOf(1))))
        val good = PackArchive.write(mapOf("a" to byteArrayOf(2)))
        val evil = good.copyOf().also { it[it.size - 1] = 7 }
        assertFailsWith<PackIntegrityException> { store.install(release(2, mapOf("a" to byteArrayOf(2)), served = evil)) }
        assertFailsWith<PackIntegrityException> { store.install(release(3, mapOf("a" to byteArrayOf(2)), served = good + byteArrayOf(0))) }
        assertEquals(1, store.installed().getValue("sound.cinematic").manifest.version)
        assertContentEquals(byteArrayOf(1), read(Path(store.activeDir("sound.cinematic")!!, "a")))
    }

    @Test
    fun theContainerRejectsPathsOutsideThePack() {
        assertFailsWith<IllegalArgumentException> { PackArchive.write(mapOf("../evil" to byteArrayOf())) }
        val forged = PackArchive.write(mapOf("ab/evil" to byteArrayOf(1))).also { b ->
            // "ab/evil" becomes "../evil" after signing-free tampering of the container.
            val i = String(b, Charsets.ISO_8859_1).indexOf("ab/evil")
            b[i] = '.'.code.toByte(); b[i + 1] = '.'.code.toByte()
        }
        assertFailsWith<PackIntegrityException> { PackArchive.read(forged) }
        assertFailsWith<PackIntegrityException> { PackArchive.read(byteArrayOf(1, 2)) }
        val files = mapOf("a/b.wav" to ByteArray(1000) { it.toByte() }, "c.json" to "{}".encodeToByteArray())
        val back = PackArchive.read(PackArchive.write(files))
        assertEquals(files.keys, back.keys)
        files.forEach { (k, v) -> assertContentEquals(v, back[k]) }
    }

    @Test
    fun aPackForANewerAppIsRefused(): Unit = runBlocking {
        val payload = PackArchive.write(mapOf("a" to byteArrayOf(1)))
        payloads["new.tpk"] = payload
        val unsigned = PackManifest("style.neo", PackKind.Style, 1, payload.size.toLong(), PackStore.sha256Hex(payload), listOf("https://cdn.test/new.tpk"), "test-1", "", minApp = 200)
        val m = unsigned.copy(signature = hex(sign(server, unsigned.signedBytes())))
        assertFailsWith<PackSignatureException> { store.install(m) }
        assertTrue(store.installed().isEmpty())
    }
}
