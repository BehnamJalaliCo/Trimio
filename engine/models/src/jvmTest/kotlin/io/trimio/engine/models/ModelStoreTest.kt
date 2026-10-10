package io.trimio.engine.models

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.kotlincrypto.hash.sha2.SHA256
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStoreTest {

    private val payload = Random(42).nextBytes(1_000_000)
    private val sha = SHA256().digest(payload).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private val dir = Path(Files.createTempDirectory("models").toString())

    private fun spec(hash: String = sha, urls: List<String> = listOf("https://cdn/a.bin")) = ModelSpec(
        id = "test", kind = ModelKind.Speech, titleFa = "آزمایش", titleEn = "Test", fileName = "a.bin",
        urls = urls, sizeBytes = payload.size.toLong(), sha256 = hash, tier = DeviceTier.Standard, license = "MIT",
    )

    /** Serves [payload], honouring Range; optionally fails the first request half-way. */
    private fun server(failFirstAfter: Int? = null, deadHosts: Set<String> = emptySet()): Pair<HttpClient, MutableList<String?>> {
        val ranges = mutableListOf<String?>()
        var first = true
        val engine = MockEngine { request ->
            if (request.url.host in deadHosts) return@MockEngine respond("down", HttpStatusCode.ServiceUnavailable)
            val range = request.headers[HttpHeaders.Range]
            ranges += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            var body = payload.copyOfRange(from, payload.size)
            if (first && failFirstAfter != null) {
                first = false
                body = body.copyOfRange(0, failFirstAfter) // connection drops mid-way
            }
            respond(body, if (from > 0) HttpStatusCode.PartialContent else HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        }
        return HttpClient(engine) to ranges
    }

    @Test
    fun downloadsVerifiesAndInstalls() = runBlocking {
        val (client, _) = server()
        val store = ModelStore(dir, client)
        val progress = mutableListOf<Float>()
        val path = store.install(spec()) { if (it is DownloadState.Progress) progress += it.fraction }
        assertContentEquals(payload, Files.readAllBytes(java.nio.file.Path.of(path.toString())))
        assertTrue(store.isInstalled(spec()))
        assertEquals(1f, progress.last())
    }

    @Test
    fun interruptedDownloadResumesWithRange() = runBlocking {
        val (client, ranges) = server(failFirstAfter = 400_000)
        val store = ModelStore(dir, client)
        // The connection drops after 400 KB: the install fails but keeps the partial file.
        assertFailsWith<IncompleteDownloadException> { store.install(spec()) }
        val (client2, ranges2) = server()
        ModelStore(dir, client2).install(spec())
        assertEquals(listOf<String?>(null), ranges)
        assertTrue(ranges2.single()?.startsWith("bytes=") == true, "second attempt must resume: $ranges2")
        assertTrue(ModelStore(dir, client2).isInstalled(spec()))
    }

    @Test
    fun fallsBackToNextMirror() = runBlocking {
        val (client, _) = server(deadHosts = setOf("cdn.trimio.app"))
        val store = ModelStore(dir, client)
        store.install(spec(urls = listOf("https://cdn.trimio.app/a.bin", "https://huggingface.co/a.bin")))
        assertTrue(store.isInstalled(spec()))
    }

    @Test
    fun tamperedDownloadIsRejectedAndRemoved() = runBlocking {
        val (client, _) = server()
        val store = ModelStore(dir, client)
        assertFailsWith<ModelIntegrityException> { store.install(spec(hash = "0".repeat(64))) }
        assertFalse(store.isInstalled(spec()))
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(dir.toString(), "a.bin.part")))
    }

    @Test
    fun newerReleaseIsOfferedAndReplacesTheOldOneOnlyWhenVerified() = runBlocking {
        val v2Payload = Random(7).nextBytes(600_000)
        val v2Sha = SHA256().digest(v2Payload).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("b.bin")) v2Payload else payload
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        }
        val store = ModelStore(dir, HttpClient(engine))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val v1 = spec()
        val manager = ModelManager(store, scope, listOf(v1))
        manager.download(v1)
        manager.awaitIdle()
        assertEquals(ModelState.Installed, manager.states.value[v1.id])
        assertTrue(manager.updates.value.isEmpty())

        // The server ships release 2 with new weights in a new file.
        val v2 = v1.copy(fileName = "b.bin", urls = listOf("https://cdn/b.bin"), sizeBytes = v2Payload.size.toLong(), sha256 = v2Sha, version = 2, notesFa = "دقیق‌تر")
        manager.updateCatalog(listOf(v2))
        assertEquals(listOf(ModelUpdate(v2, installedVersion = 1)), manager.updates.value)
        assertEquals(ModelState.UpdateAvailable(v2), manager.states.value[v1.id])
        assertTrue(store.isInstalled(v2), "the old release keeps working until the update lands")
        assertTrue(store.pathOf(v2).toString().endsWith("a.bin"))

        manager.applyAllUpdates()
        manager.awaitIdle()
        assertEquals(ModelState.Installed, manager.states.value[v1.id])
        assertTrue(manager.updates.value.isEmpty())
        assertTrue(store.pathOf(v2).toString().endsWith("b.bin"))
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(dir.toString(), "a.bin")), "old release removed after the new one verified")
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    @Test
    fun catalogPicksBestModelForRam() {
        assertEquals(ModelCatalog.whisperSmallQ8, ModelCatalog.bestFor(ModelKind.Speech, 8))
        assertEquals(ModelCatalog.whisperTurboQ5, ModelCatalog.bestFor(ModelKind.Speech, 12))
        // Ultra phones get the full large-v3: the best Persian spelling and punctuation.
        assertEquals(ModelCatalog.whisperLargeV3Q5, ModelCatalog.bestFor(ModelKind.Speech, 16))
        assertTrue(ModelCatalog.all.all { it.sha256.length == 64 && it.urls.first().startsWith(ModelCatalog.CDN) })

        // Director models: a 2-3 GB default on every supported phone, bigger/MoE tiers above it.
        assertEquals(ModelCatalog.qwen35_4b, ModelCatalog.bestFor(ModelKind.Language, 8))
        assertTrue(ModelCatalog.qwen35_4b.isDefault && ModelCatalog.qwen35_4b.sizeBytes in 2_000_000_000..3_000_000_000)
        assertEquals(ModelCatalog.qwen35_9b, ModelCatalog.bestFor(ModelKind.Language, 16))
        assertEquals(ModelCatalog.qwen35_9b, ModelCatalog.bestFor(ModelKind.Language, 24), "the experimental MoE is never picked for the user")
        assertEquals(DeviceTier.High, ModelCatalog.qwen35_9bLight.tier)
        assertTrue(ModelCatalog.qwen36_35bA3b.experimental && ModelCatalog.qwen36_35bA3b.tier == DeviceTier.Max)
        assertTrue(ModelCatalog.all.filter { it.kind == ModelKind.Language }.all { it.chatFormat != null })
        assertTrue(ModelCatalog.lfm25_8bA1b.activeParamsB != null, "the MoE tier is marked as such")

        // Sight: multimodal directors see with their own projector; text-only ones get the light looker.
        assertEquals(listOf(ModelCatalog.qwen35_4bEyes), ModelCatalog.visionFor(ModelCatalog.qwen35_4b))
        assertEquals(listOf(ModelCatalog.gemma4E4bEyes), ModelCatalog.visionFor(ModelCatalog.gemma4E4b))
        assertEquals(listOf(ModelCatalog.qwen35_08bLooker, ModelCatalog.qwen35_08bEyes), ModelCatalog.visionFor(ModelCatalog.lfm25_8bA1b))
        assertTrue(ModelCatalog.qwen35_4bEyes.isDefault, "the default director can see out of the box")
        assertTrue(ModelCatalog.all.map { it.fileName }.toSet().size == ModelCatalog.all.size, "no two models share a file")
    }
}
