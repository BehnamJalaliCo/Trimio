package io.trimio.server

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.trimio.core.api.Api
import io.trimio.core.api.Catalog
import io.trimio.core.api.RemoteConfig
import io.trimio.engine.styles.StylePackCodec
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {

    private val token = "t".repeat(ServerSettings.MIN_TOKEN)

    private val keys = runBlocking {
        val pair = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P256).generateKey()
        pair.publicKey.encodeToByteArray(EC.PublicKey.Format.DER) to pair.privateKey.encodeToByteArray(EC.PrivateKey.Format.DER)
    }

    private fun serve(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val settings = ServerSettings(Files.createTempDirectory("trimio-server"), token, mapOf("k1" to keys.first))
        application { trimio(settings) }
        block()
    }

    private suspend fun signedPack(version: String): String {
        val pack = StylePackRepository().get("liquid-glass")!!.copy(version = version)
        return StylePackCodec.sign(pack, "k1", keys.second)
    }

    @Test
    fun adminCanUpdateConfig() = serve {
        val initial = Api.json.decodeFromString(RemoteConfig.serializer(), client.get(Api.CONFIG).bodyAsText())
        assertEquals(RemoteConfig(), initial)

        val update = client.put(ADMIN_CONFIG) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"minVersionCode":7,"flags":{"pro":true},"revokedPackKeys":["old"]}""")
        }
        assertEquals(HttpStatusCode.NoContent, update.status)
        val config = Api.json.decodeFromString(RemoteConfig.serializer(), client.get(Api.CONFIG).bodyAsText())
        assertEquals(7, config.minVersionCode)
        assertTrue(config.flag(RemoteConfig.PRO))
        assertEquals(setOf("old"), config.revokedPackKeys)
    }

    @Test
    fun browsersMayReadButNeverAuthenticate() = serve {
        val read = client.get(Api.CATALOG) { header(HttpHeaders.Origin, "https://trimio.app") }
        assertEquals("*", read.headers[HttpHeaders.AccessControlAllowOrigin])
        // A page can never send the admin token: the Authorization header is not allowed cross-origin.
        val preflight = client.options(Api.ADMIN_PACKS) {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "authorization")
        }
        assertEquals(HttpStatusCode.Forbidden, preflight.status)
    }

    @Test
    fun adminRoutesNeedTheToken() = serve {
        assertEquals(HttpStatusCode.Unauthorized, client.post(Api.ADMIN_PACKS) { setBody(signedPack("2.0.0")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(Api.ADMIN_PACKS) { bearerAuth("x".repeat(32)); setBody(signedPack("2.0.0")) }.status)
    }

    @Test
    fun signedPackIsPublishedAndServedVerbatim() = serve {
        val envelope = signedPack("2.0.0")
        assertEquals(HttpStatusCode.Created, client.post(Api.ADMIN_PACKS) { bearerAuth(token); setBody(envelope) }.status)

        val catalog = Api.json.decodeFromString(Catalog.serializer(), client.get(Api.CATALOG).bodyAsText())
        assertEquals(listOf("liquid-glass" to "2.0.0"), catalog.styles.map { it.id to it.version })

        val served = client.get(Api.pack("liquid-glass"))
        assertEquals(envelope, served.bodyAsText())
        val etag = assertNotNull(served.headers[HttpHeaders.ETag])
        assertEquals(HttpStatusCode.NotModified, client.get(Api.pack("liquid-glass")) { header(HttpHeaders.IfNoneMatch, etag) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(Api.pack("missing")).status)
        assertEquals(HttpStatusCode.NotFound, client.get(Api.pack("..%2Fconfig")).status)
    }

    @Test
    fun unsignedOrForeignPacksAreRejected() = serve {
        val plain = StylePackCodec.encode(StylePackRepository().get("liquid-glass")!!)
        assertEquals(HttpStatusCode.BadRequest, client.post(Api.ADMIN_PACKS) { bearerAuth(token); setBody(plain) }.status)

        val pair = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P256).generateKey()
        val foreign = StylePackCodec.sign(
            StylePackRepository().get("liquid-glass")!!, "k1",
            pair.privateKey.encodeToByteArray(EC.PrivateKey.Format.DER),
        )
        assertEquals(HttpStatusCode.BadRequest, client.post(Api.ADMIN_PACKS) { bearerAuth(token); setBody(foreign) }.status)
        assertTrue(Api.json.decodeFromString(Catalog.serializer(), client.get(Api.CATALOG).bodyAsText()).styles.isEmpty())
    }
}
