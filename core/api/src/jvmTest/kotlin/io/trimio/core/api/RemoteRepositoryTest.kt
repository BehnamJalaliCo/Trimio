package io.trimio.core.api

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.styles.StylePackCodec
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteRepositoryTest {

    private val keys = runBlocking {
        val pair = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P256).generateKey()
        pair.publicKey.encodeToByteArray(EC.PublicKey.Format.DER) to pair.privateKey.encodeToByteArray(EC.PrivateKey.Format.DER)
    }

    private fun server(routes: Map<String, String>) = RemoteService(
        HttpClient(MockEngine { request -> routes[request.url.encodedPath]?.let { respond(it) } ?: respondError(HttpStatusCode.NotFound) }),
        "https://api.test",
    )

    @Test
    fun refreshInstallsNewerPacksCachesAndGatesOldBuilds(): Unit = runBlocking {
        val dir = Files.createTempDirectory("remote")
        val trusted = mutableMapOf("k1" to keys.first)
        val packs = StylePackRepository(FilePackStore(Path(dir.resolve("packs").toString())), trusted)
        val newer = packs.get("liquid-glass")!!.copy(version = "3.0.0")
        val custom = ModelCatalog.whisperBaseQ8.copy(id = "whisper-new", sha256 = "a".repeat(64))
        val insecure = custom.copy(id = "insecure", urls = listOf("http://example.com/x.bin"))
        val config = RemoteConfig(minVersionCode = 5, flags = mapOf(RemoteConfig.PRO to true))
        val catalog = Catalog(
            models = listOf(custom, insecure),
            styles = listOf(StyleEntry("liquid-glass", "3.0.0", "", ""), StyleEntry("neobrutalism", "1.0.0", "", "")),
        )
        val service = server(
            mapOf(
                Api.CONFIG to Api.json.encodeToString(RemoteConfig.serializer(), config),
                Api.CATALOG to Api.json.encodeToString(Catalog.serializer(), catalog),
                Api.pack("liquid-glass") to StylePackCodec.sign(newer, "k1", keys.second),
            ),
        )
        val cache = Path(dir.resolve("remote.json").toString())
        val repo = RemoteRepository(service, cache, packs, versionCode = 3, trustedKeys = trusted)
        assertFalse(repo.state.value.updateRequired)

        assertTrue(repo.refresh())
        assertTrue(repo.state.value.updateRequired)
        assertTrue(repo.state.value.config.flag(RemoteConfig.PRO))
        assertEquals("3.0.0", packs.get("liquid-glass")!!.version)
        assertEquals("1.0.0", packs.get("neobrutalism")!!.version, "same version is not downloaded")
        val models = repo.models(ModelCatalog.all)
        assertTrue(models.any { it.id == "whisper-new" })
        assertFalse(models.any { it.id == "insecure" }, "plain-HTTP models are ignored")

        // Next start, offline: the cached snapshot applies immediately.
        val offline = RemoteRepository(server(emptyMap()), cache, packs, versionCode = 3, trustedKeys = trusted)
        assertTrue(offline.state.value.updateRequired)
        assertFalse(offline.refresh())
        assertTrue(offline.state.value.config.flag(RemoteConfig.PRO))
    }

    @Test
    fun revokedKeyStopsTrustingItsPacks(): Unit = runBlocking {
        val dir = Files.createTempDirectory("remote")
        val trusted = mutableMapOf("k1" to keys.first)
        val packs = StylePackRepository(FilePackStore(Path(dir.toString())), trusted)
        packs.install(StylePackCodec.sign(packs.get("liquid-glass")!!.copy(version = "2.0.0"), "k1", keys.second))
        assertEquals("2.0.0", packs.get("liquid-glass")!!.version)

        val service = server(
            mapOf(
                Api.CONFIG to Api.json.encodeToString(RemoteConfig.serializer(), RemoteConfig(revokedPackKeys = setOf("k1"))),
                Api.CATALOG to "{}",
            ),
        )
        assertTrue(RemoteRepository(service, null, packs, versionCode = 1, trustedKeys = trusted).refresh())
        assertTrue(trusted.isEmpty())
        assertEquals("1.0.0", packs.get("liquid-glass")!!.version, "back to the bundled pack")
    }
}
