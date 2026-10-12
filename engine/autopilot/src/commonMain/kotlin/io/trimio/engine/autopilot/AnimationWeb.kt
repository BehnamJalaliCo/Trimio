package io.trimio.engine.autopilot

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.trimio.engine.assets.art.LottieFilesSource
import io.trimio.engine.assets.art.WebPort
import io.trimio.engine.assets.art.WebReply
import io.trimio.engine.motion.art.MotionArt
import io.trimio.engine.motion.art.MotionArts
import kotlinx.coroutines.CancellationException

/**
 * The web as the on-demand animation source ([LottieFilesSource]) asks it: plain GETs and JSON
 * POSTs with Trimio's own User-Agent, the status and Retry-After passed back so the source can
 * back off, and documents larger than asked refused by their Content-Length before they are read.
 * Any failure is no answer (null); time limits are the source's.
 */
class KtorWebPort(private val http: HttpClient) : WebPort {
    override suspend fun get(url: String, maxBytes: Long): WebReply? = answer {
        val r = http.get(url) {
            header(HttpHeaders.UserAgent, LottieFilesSource.USER_AGENT)
            header(HttpHeaders.Accept, "application/json")
        }
        val length = r.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > maxBytes) WebReply(r.status.value, "") else reply(r)
    }

    override suspend fun postJson(url: String, body: String): WebReply? = answer {
        reply(
            http.post(url) {
                header(HttpHeaders.UserAgent, LottieFilesSource.USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(body)
            },
        )
    }

    private suspend fun reply(r: HttpResponse) = WebReply(r.status.value, r.bodyAsText(), r.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull())

    private suspend fun answer(block: suspend () -> WebReply): WebReply? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        null
    }

    companion object {
        /**
         * The on-demand animation source over [http], kept in the «LottieFiles» folder [dir], each
         * document checked by the engine's own Lottie reader; [online] is the app's setting.
         */
        fun animations(http: HttpClient, dir: String, online: () -> Boolean = { true }): LottieFilesSource =
            LottieFilesSource(dir, KtorWebPort(http), validate = { doc -> MotionArts.readOrWhy(MotionArt.FORMAT_LOTTIE, doc).second }, online = online)
    }
}
