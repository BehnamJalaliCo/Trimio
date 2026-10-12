package io.trimio.engine.models

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The content-pack channel: manifests signed by tools/packs verify here, payloads stream and
 * resume after a dropped connection (a fake server that honours Range), the index is parsed
 * leniently and cached, and the manager downloads, pauses, resumes, deletes and queues.
 */
class PackChannelTest {

    private val server: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun raw(kp: KeyPair) = kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }
    private fun sign(bytes: ByteArray): String = Signature.getInstance("Ed25519").run { initSign(server.private); update(bytes); sign() }
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private val dir = Path(Files.createTempDirectory("content-packs").toString())
    private val payloads = mutableMapOf<String, ByteArray>()
    private var dropAfter: Int? = null
    private var gate: (suspend () -> Unit)? = null
    private val ranges = mutableListOf<String?>()

    /** A file server: Range requests answered with 206 and the rest; [dropAfter] cuts a body short once. */
    private val client = HttpClient(MockEngine { req ->
        val name = req.url.encodedPath.substringAfterLast('/')
        val body = payloads[name] ?: return@MockEngine respond("missing", HttpStatusCode.NotFound)
        val range = req.headers[HttpHeaders.Range]
        ranges += range
        gate?.invoke()
        val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
        var part = body.copyOfRange(from, body.size)
        dropAfter?.let { n -> part = part.copyOf(minOf(n, part.size)); dropAfter = null }
        respond(part, if (from > 0) HttpStatusCode.PartialContent else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
    })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    private fun pack(id: String, version: Int, files: Map<String, ByteArray>, kind: PackKind = PackKind.Art): PackManifest {
        val payload = PackArchive.write(files)
        val name = "$id-v$version.tpk"
        payloads[name] = payload
        val m = PackManifest(id, kind, version, payload.size.toLong(), PackStore.sha256Hex(payload), listOf(name), "k1", "", license = "MIT", unpacked = payload.size.toLong())
        return m.copy(signature = sign(m.signedBytes()))
    }

    private fun index(vararg packs: PackManifest): String {
        val items = packs.joinToString(",") { kotlinx.serialization.json.Json.encodeToString(PackManifest.serializer(), it) }
        // An entry of a kind this build does not know, and a broken one: both skipped.
        val future = """{"id":"holo.x","kind":"Hologram","version":1,"size":1,"sha256":"00","urls":["x"],"keyId":"k1","signature":"00"}"""
        return """{"format":"trimio-packs/1","channel":"packs-v1","packs":[$items,$future,{"id":3}]}"""
    }

    private val channel = PackChannel(listOf("https://mirror-a.test/packs", "https://mirror-b.test/packs/"))

    private fun store(free: Long? = null) = PackStore(Path(dir, "packs"), client, mapOf("k1" to raw(server)), freeBytes = { free })

    private fun read(p: Path) = SystemFileSystem.source(p).buffered().use { it.readByteArray() }

    /** A manifest and payload written by tools/packs/packlib.py (seed 00..1f), verified with the same code the app runs. */
    @Test
    fun aPackBuiltByTheToolsVerifiesAndInstalls(): Unit = runBlocking {
        val pub = PackStore.hex("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8")!!
        val payload = PackStore.hex(
            "54504b310000000200097061636b2e6a736f6e00000000000000147b226964223a226172742e66697874757265227d000e6172742f646f63732f612e7376670000000000000010" +
                "3c7061746820643d274d302030272f3e",
        )!!
        val text = """{"format":"trimio-packs/1","packs":[{"id": "art.fixture", "kind": "Art", "version": 3, "size": 87,
            "sha256": "aa5b36e065f3ca831e8831841a826519082fb766c1bc7f7fb5bcbbde6c266082", "urls": ["art.fixture-v3.tpk"], "keyId": "packs-2026-1",
            "signature": "f015bdd8895bc9318ae117455dfb63c42b0750aa2699cec524f1df8f58176d9d16454d59ed5656a99ef561098b27693bb43e3e40cff9192a83a5799938f7a800",
            "minApp": 0, "license": "MIT", "unpacked": 36, "titleFa": "آزمون", "titleEn": "Fixture"}]}"""
        val m = PackIndexClient(client, channel, null).parse(text).single()
        assertEquals(listOf("https://mirror-a.test/packs/art.fixture-v3.tpk", "https://mirror-b.test/packs/art.fixture-v3.tpk"), m.urls)
        assertEquals("آزمون", m.titleFa)
        payloads["art.fixture-v3.tpk"] = payload
        val s = PackStore(Path(dir, "tools"), client, mapOf("packs-2026-1" to pub))
        val d = s.install(m)
        assertEquals("<path d='M0 0'/>", read(Path(d, "art/docs/a.svg")).decodeToString())
        // The same manifest with a changed licence no longer verifies.
        assertFailsWith<PackSignatureException> { s.verify(m.copy(license = "CC-BY-4.0")) }
    }

    @Test
    fun theAppTrustsTheChannelKey() {
        assertEquals(32, PackKeys.content.getValue("packs-2026-1").size)
    }

    @Test
    fun aDroppedConnectionResumesWhereItStopped(): Unit = runBlocking {
        // One host: a second mirror would simply resume the transfer itself.
        val m = PackChannel(listOf("https://one.test")).resolve(pack("art.big", 1, mapOf("a.bin" to ByteArray(300_000) { (it % 251).toByte() })))
        val s = store()
        dropAfter = 100_000
        val progress = mutableListOf<Long>()
        assertFailsWith<PackIncompleteException> { s.install(m) { b, _ -> progress += b } }
        assertEquals(100_000, s.partial(m))
        assertNull(s.activeDir("art.big"))
        val d = s.install(m) { b, _ -> progress += b }
        assertEquals("bytes=100000-", ranges.last(), "the second request asks only for the rest")
        assertEquals(300_000, read(Path(d, "a.bin")).size)
        assertEquals(m.size, progress.last())
        assertEquals(0, s.partial(m), "the partial file is gone once installed")
    }

    @Test
    fun aFailedMirrorFallsThroughToTheNext(): Unit = runBlocking {
        val m = channel.resolve(pack("art.m", 1, mapOf("a" to byteArrayOf(1))))
        val evil = m.copy(urls = listOf("https://evil.test/nothing.tpk") + m.urls)
        assertTrue(store().install(evil).toString().endsWith("1"))
    }

    @Test
    fun notEnoughRoomIsRefusedBeforeDownloading(): Unit = runBlocking {
        val m = channel.resolve(pack("art.room", 1, mapOf("a" to ByteArray(1000))))
        val e = assertFailsWith<PackSpaceException> { store(free = 10_000_000).install(m) }
        assertTrue(e.needed > e.free)
        assertTrue(ranges.isEmpty(), "nothing was fetched")
    }

    @Test
    fun indexIsLenientResolvedAndCachedForOffline(): Unit = runBlocking {
        val a = pack("art.a", 1, mapOf("x" to byteArrayOf(1)))
        val b = pack("sound.b", 2, mapOf("y" to byteArrayOf(2)), PackKind.Sound)
        payloads["index.json"] = index(a, b).encodeToByteArray()
        val cache = Path(dir, "index-cache.json")
        val c = PackIndexClient(client, channel, cache)
        val live = c.fetch()!!
        assertEquals(listOf("art.a", "sound.b"), live.map { it.id })
        assertEquals("https://mirror-a.test/packs/art.a-v1.tpk", live[0].urls[0])
        payloads.clear()
        assertNull(PackIndexClient(client, channel, cache).fetch(), "offline")
        assertEquals(listOf("art.a", "sound.b"), PackIndexClient(client, channel, cache).cached().map { it.id })
    }

    @Test
    fun localImportsInstallUnsignedAndUninstall() {
        val s = store()
        val d = s.installLocal("art.mine", PackKind.Art, "انیمیشن‌های من", "My animations", mapOf("art/index.json" to "{}".encodeToByteArray()))
        assertTrue(s.installed().getValue("art.mine").manifest.local)
        assertTrue(SystemFileSystem.exists(Path(d, "art/index.json")))
        val d2 = s.installLocal("art.mine", PackKind.Art, "", "", mapOf("art/index.json" to "{\"v\":2}".encodeToByteArray()))
        assertEquals(2, s.installed().getValue("art.mine").manifest.version)
        assertTrue(!SystemFileSystem.exists(d), "a local pack keeps only its newest import")
        assertTrue(SystemFileSystem.exists(d2))
        s.uninstall("art.mine")
        assertTrue(s.installed().isEmpty())
        assertFailsWith<IllegalArgumentException> { s.installLocal("art.evil", PackKind.Art, "", "", mapOf("../x" to byteArrayOf())) }
    }

    // ------------------------------------------------------------------------------- the manager

    private suspend fun PackManager.waitFor(id: String, check: (PackState?) -> Boolean): PackState? {
        val got = withTimeoutOrNull(10_000) {
            while (!check(states.value[id])) delay(10)
            states.value[id]
        }
        return got ?: error("$id stuck at ${states.value[id]}")
    }

    @Test
    fun managerDownloadsPausesResumesAndDeletes(): Unit = runBlocking {
        val a = pack("art.a", 1, mapOf("art/index.json" to ByteArray(200_000) { 1 }))
        val b = pack("sound.b", 1, mapOf("sound/index.json" to byteArrayOf(2)), PackKind.Sound)
        payloads["index.json"] = index(a, b).encodeToByteArray()
        val active = mutableListOf<Boolean>()
        val mgr = PackManager(store(), PackIndexClient(client, channel, Path(dir, "idx.json")), scope) { active += it }
        assertTrue(mgr.refresh())
        assertIs<PackState.Available>(mgr.states.value["art.a"])

        // Pause in the middle of the first transfer: the server answers slowly, then the connection drops.
        var release = false
        gate = { while (!release) delay(5) }
        dropAfter = 50_000
        mgr.download("art.a")
        mgr.waitFor("art.a") { it is PackState.Downloading }
        mgr.pause("art.a")
        release = true
        mgr.waitFor("art.a") { it is PackState.Available || it is PackState.Paused }
        gate = null

        mgr.downloadAll()
        mgr.waitFor("sound.b") { it is PackState.Installed }
        mgr.waitFor("art.a") { it is PackState.Installed }
        assertEquals(setOf("art.a", "sound.b"), mgr.active.value.map { it.id }.toSet())
        assertEquals(PackKind.Sound, mgr.active.value.first { it.id == "sound.b" }.kind)
        assertEquals(0, mgr.remainingBytes())
        assertTrue(mgr.usedBytes() >= 200_000)
        assertEquals(true, active.first())

        mgr.delete("art.a")
        mgr.waitFor("art.a") { it is PackState.Available }
        assertEquals(listOf("sound.b"), mgr.active.value.map { it.id })

        // A newer release in the channel shows as an update and installs over the old one.
        val b2 = pack("sound.b", 2, mapOf("sound/index.json" to byteArrayOf(3)), PackKind.Sound)
        payloads["index.json"] = index(a, b2).encodeToByteArray()
        mgr.refresh()
        assertEquals(2, (mgr.states.value["sound.b"] as PackState.Installed).update?.version)
        mgr.download("sound.b")
        mgr.waitFor("sound.b") { it is PackState.Installed && it.version == 2 }
        val dirB = Path(mgr.active.value.first { it.id == "sound.b" }.dir)
        assertContentEquals(byteArrayOf(3), read(Path(dirB, "sound/index.json")))
    }

    @Test
    fun managerReportsMissingRoom(): Unit = runBlocking {
        val a = pack("art.a", 1, mapOf("x" to byteArrayOf(1)))
        payloads["index.json"] = index(a).encodeToByteArray()
        val mgr = PackManager(store(free = 1_000), PackIndexClient(client, channel, null), scope)
        mgr.refresh()
        mgr.download("art.a")
        val s = mgr.waitFor("art.a") { it is PackState.NoSpace }
        assertTrue((s as PackState.NoSpace).needed > 1_000)
    }
}
