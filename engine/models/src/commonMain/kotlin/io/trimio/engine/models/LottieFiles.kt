package io.trimio.engine.models

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/** One public animation as LottieFiles' developer API lists it. */
data class LottieFilesAnimation(val id: Long, val name: String, val jsonUrl: String, val previewUrl: String? = null)

/** A page of results and the cursor of the next one (null: the last page). */
data class LottieFilesPage(val items: List<LottieFilesAnimation>, val total: Int, val next: String?)

/**
 * LottieFiles' official developer API (GraphQL, public animations), called only at the creator's
 * request and only with their own key (kept on the device, never in the app or the repository).
 * No scraping: search and the featured list come from the API, documents from the `jsonUrl` it
 * returns. Requests are spaced at least [minIntervalMs] apart and each waits at most [timeoutMs].
 * Public animations are under the Lottie Simple License (commercial use, no attribution).
 */
class LottieFilesApi(
    private val client: HttpClient,
    private val key: String,
    private val endpoint: String = ENDPOINT,
    private val minIntervalMs: Long = 1_000,
    private val timeoutMs: Long = 8_000,
) {
    private val gate = Mutex()
    private var last: TimeSource.Monotonic.ValueTimeMark? = null

    /** Public animations matching [query]; [after] continues a previous page. Null when the API does not answer. */
    suspend fun search(query: String, first: Int = PAGE, after: String? = null): LottieFilesPage? =
        page("searchPublicAnimations", "query: ${quote(query)}, ", first, after)

    /** The featured public animations (browsing without a query). */
    suspend fun featured(first: Int = PAGE, after: String? = null): LottieFilesPage? = page("featuredPublicAnimations", "", first, after)

    /** The Lottie JSON of [item], or null. Only LottieFiles' asset hosts are fetched. */
    suspend fun document(item: LottieFilesAnimation): String? {
        if (!item.jsonUrl.startsWith("https://") || ASSET_HOSTS.none { item.jsonUrl.substringAfter("https://").startsWith(it) }) return null
        return paced { client.get(item.jsonUrl).takeIf { it.status.isSuccess() }?.bodyAsText() }
    }

    private suspend fun page(field: String, args: String, first: Int, after: String?): LottieFilesPage? {
        val cursor = after?.let { ", after: ${quote(it)}" }.orEmpty()
        val query = "{ $field($args first: ${first.coerceIn(1, PAGE_MAX)}$cursor) { totalCount pageInfo { endCursor hasNextPage } " +
            "edges { node { id name jsonUrl gifUrl } } } }"
        val body = buildJsonObject { put("query", query) }.toString()
        val text = paced {
            client.post(endpoint) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $key")
                setBody(body)
            }.takeIf { it.status.isSuccess() }?.bodyAsText()
        } ?: return null
        return runCatching { parse(text, field) }.getOrNull()
    }

    /** One request at a time, [minIntervalMs] after the previous one, [timeoutMs] at most. */
    private suspend fun <T> paced(block: suspend () -> T?): T? = gate.withLock {
        last?.elapsedNow()?.inWholeMilliseconds?.let { since -> if (since < minIntervalMs) delay(minIntervalMs - since) }
        try {
            withTimeoutOrNull(timeoutMs) { runCatching { block() }.getOrNull() }
        } finally {
            last = TimeSource.Monotonic.markNow()
        }
    }

    companion object {
        const val ENDPOINT = "https://graphql.lottiefiles.com/2022-08"
        const val PAGE = 20
        private const val PAGE_MAX = 50
        private val ASSET_HOSTS = listOf("assets-v2.lottiefiles.com/", "assets.lottiefiles.com/", "lottie.host/")
        private val json = Json { ignoreUnknownKeys = true }

        internal fun parse(text: String, field: String): LottieFilesPage {
            val data = json.parseToJsonElement(text).jsonObject.getValue("data").jsonObject.getValue(field).jsonObject
            val items = data.getValue("edges").jsonArray.mapNotNull { e ->
                val n = (e as? JsonObject)?.get("node")?.jsonObject ?: return@mapNotNull null
                val url = n["jsonUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                LottieFilesAnimation(n["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0, n["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), url, n["gifUrl"]?.jsonPrimitive?.contentOrNull)
            }
            val info = data["pageInfo"]?.jsonObject
            val next = info?.get("endCursor")?.jsonPrimitive?.contentOrNull?.takeIf { info["hasNextPage"]?.jsonPrimitive?.booleanOrNull == true }
            return LottieFilesPage(items, data["totalCount"]?.jsonPrimitive?.intOrNull ?: items.size, next)
        }

        private fun quote(s: String): String = JsonPrimitive(s).toString()
    }
}
