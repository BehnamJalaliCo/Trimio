package io.trimio.core.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

class RemoteException(message: String) : Exception(message)

/** Read-only client for the catalogue server ([Api]). Nothing about the user is sent. */
class RemoteService(private val http: HttpClient, private val baseUrl: String = Api.BASE_URL) {

    suspend fun config(): RemoteConfig = Api.json.decodeFromString(RemoteConfig.serializer(), text(Api.CONFIG))

    suspend fun catalog(): Catalog = Api.json.decodeFromString(Catalog.serializer(), text(Api.CATALOG))

    /** A signed pack envelope, verified by the caller before use. */
    suspend fun pack(id: String): String = text(Api.pack(id))

    private suspend fun text(path: String): String {
        val response: HttpResponse = http.get(baseUrl.trimEnd('/') + path)
        if (!response.status.isSuccess()) throw RemoteException("$path: HTTP ${response.status.value}")
        return response.bodyAsText()
    }
}
