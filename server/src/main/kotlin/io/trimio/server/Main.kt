package io.trimio.server

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.trimio.core.api.Api
import io.trimio.core.api.RemoteConfig
import io.trimio.core.api.StyleEntry
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.styles.StylePackException
import java.security.MessageDigest

fun main() {
    val settings = ServerSettings.fromEnv()
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) { trimio(settings) }.start(wait = true)
}

private val PACK_ID = Regex("[a-z0-9][a-z0-9-]{0,47}")

fun Application.trimio(settings: ServerSettings) {
    val store = CatalogStore(settings.dataDir, settings.trustedKeys)
    install(ContentNegotiation) { json(Api.json) }
    // The web app reads the public routes from the browser; nothing here is per-user, so any origin may.
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        exposeHeader(HttpHeaders.ETag)
        allowHeader(HttpHeaders.IfNoneMatch)
    }
    install(Authentication) {
        bearer(ADMIN) {
            authenticate { credential ->
                val expected = settings.adminToken ?: return@authenticate null
                UserIdPrincipal(ADMIN).takeIf { MessageDigest.isEqual(credential.token.encodeToByteArray(), expected.encodeToByteArray()) }
            }
        }
    }
    routing {
        get("/health") { call.respondText("ok") }

        get(Api.CONFIG) {
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = SHORT_CACHE))
            call.respond(store.config())
        }
        get(Api.CATALOG) {
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = SHORT_CACHE))
            call.respond(store.catalog())
        }
        get(Api.pack("{id}")) {
            val id = call.parameters["id"].orEmpty()
            val found = if (PACK_ID.matches(id)) store.envelope(id) else null
            if (found == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val (pack, envelope) = found
            val etag = "\"${pack.id}-${pack.version}\""
            if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
                call.respond(HttpStatusCode.NotModified)
                return@get
            }
            call.response.header(HttpHeaders.ETag, etag)
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = LONG_CACHE))
            call.respondText(envelope, ContentType.Application.Json)
        }

        authenticate(ADMIN) {
            post(Api.ADMIN_PACKS) {
                val pack = try {
                    store.putPack(call.receiveText())
                } catch (e: StylePackException) {
                    call.respondText(e.message.orEmpty(), status = HttpStatusCode.BadRequest)
                    return@post
                }
                call.respond(HttpStatusCode.Created, StyleEntry(pack.id, pack.version, pack.nameFa, pack.nameEn))
            }
            put(ADMIN_CONFIG) {
                store.saveConfig(call.receive<RemoteConfig>())
                call.respond(HttpStatusCode.NoContent)
            }
            put(ADMIN_MODELS) {
                store.saveModels(call.receive<List<ModelSpec>>())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private const val ADMIN = "admin"
const val ADMIN_CONFIG = "/${Api.VERSION}/admin/config"
const val ADMIN_MODELS = "/${Api.VERSION}/admin/models"
private const val SHORT_CACHE = 300
private const val LONG_CACHE = 3600
