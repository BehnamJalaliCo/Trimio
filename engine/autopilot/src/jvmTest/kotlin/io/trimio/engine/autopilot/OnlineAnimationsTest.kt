package io.trimio.engine.autopilot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.trimio.engine.assets.art.LottieFilesSource
import io.trimio.engine.assets.packs.ContentPacks
import io.trimio.engine.motion.art.ArtFlavour
import io.trimio.engine.motion.art.ArtPaint
import io.trimio.engine.motion.art.MotionArt
import io.trimio.engine.motion.art.MotionArts
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Animations fetched on demand through the app's own path — the picker, the art shelf, the
 * online source and its «LottieFiles» folder, the engine's reader — against a fake website; and,
 * with -Plottie.live=<dir>, the real one (a few free animations, drawn to a contact sheet).
 */
class OnlineAnimationsTest {

    private val lottie = """{"v":"5.8.1","fr":30,"ip":0,"op":30,"w":100,"h":100,"layers":[{"ty":4,"ind":1,"nm":"wheel","ip":0,"op":30,"st":0,"ks":{},""" +
        """"shapes":[{"ty":"el","p":{"k":[50,50]},"s":{"k":[60,60]}},{"ty":"fl","c":{"k":[0.2,0.6,1,1]},"o":{"k":100}}]}]}"""

    /** Expressions: the engine's reader refuses them. */
    private val expression = """{"v":"5.8.1","fr":30,"ip":0,"op":30,"w":100,"h":100,"layers":[{"ty":4,"ind":1,"ip":0,"op":30,"st":0,"ks":{"o":{"k":100,"x":"var a=1;"}},""" +
        """"shapes":[{"ty":"el","p":{"k":[50,50]},"s":{"k":[60,60]}},{"ty":"fl","c":{"k":[1,0,0,1]},"o":{"k":100}}]}]}"""

    private fun node(id: Int, name: String) =
        """{"node":{"id":$id,"name":"$name","slug":"$name","description":"","lottieFileSize":900,"likesCount":1,"jsonUrl":"https://assets-v2.lottiefiles.com/a/$id/a.json","metadata":{"layers":1,"duration":1,"contentLength":900}}}"""

    @Test
    fun thePickerFetchesAKeepsAndReusesAFreeAnimation() = runBlocking {
        val requests = AtomicInteger()
        val agents = mutableSetOf<String>()
        val engine = MockEngine { request ->
            requests.incrementAndGet()
            agents += request.headers[HttpHeaders.UserAgent].orEmpty()
            when {
                request.method == HttpMethod.Post && request.url.host == "graphql.lottiefiles.com" ->
                    respond("""{"data":{"searchPublicAnimations":{"edges":[${node(8, "zorbulator")},${node(9, "zorbulator")}]}}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                request.url.encodedPath == "/a/8/a.json" -> respond(expression, HttpStatusCode.OK)
                request.url.encodedPath == "/a/9/a.json" -> respond(lottie, HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val dir = Files.createTempDirectory("anim").toFile().resolve(LottieFilesSource.FOLDER)
        val http = HttpClient(engine)
        val before = ContentPacks.online
        try {
            ContentPacks.online = KtorWebPort.animations(http, dir.path)
            val result = VisualPicker(VisualLibrary.Empty, WebKnowledge(http)).pick(mapOf("zorbulator" to "این دستگاه عجیب"))
            assertEquals("lf:9", result.picks["zorbulator"], "the one with expressions is refused by the engine's reader")
            assertEquals(1f, result.relevance["zorbulator"])
            assertTrue(result.credits.isEmpty(), "nothing to credit")
            assertNotNull(result.library.find("lf:9")?.motion, "drawn by the engine")
            assertEquals(3, requests.get())
            assertEquals(setOf(LottieFilesSource.USER_AGENT), agents)
            assertTrue(File(dir, "9.json").isFile && File(dir, "index.json").isFile && !File(dir, "8.json").exists())
            // The next edit, offline: the folder answers.
            ContentPacks.online = KtorWebPort.animations(HttpClient(MockEngine { error("offline") }), dir.path)
            val offline = BundledArt().animations("zorbulator", "", ArtFlavour.Emoji, 2)
            assertEquals("lf:9", offline.single().icon.id)
            assertEquals(3, requests.get())
        } finally {
            ContentPacks.online = before
        }
    }

    /**
     * The real website, by hand: ./gradlew :engine:autopilot:jvmTest --tests '*OnlineAnimationsTest*' -Plottie.live=<dir>.
     * The app's path for queries the bundled pictures do not show well, then a few direct look-ups;
     * every found animation drawn at four moments into <dir>/sheet.png, the folder in <dir>/LottieFiles.
     */
    @Test
    fun live() = runBlocking {
        val out = System.getProperty("lottie.live")?.let(::File) ?: return@runBlocking
        out.mkdirs()
        val source = KtorWebPort.animations(HttpClient(OkHttp), File(out, LottieFilesSource.FOLDER).path)
        val report = StringBuilder()
        val drawn = mutableListOf<Pair<String, MotionArt>>()
        val before = ContentPacks.online
        try {
            ContentPacks.online = source
            val t0 = System.nanoTime()
            val queries = mapOf("rocket launch" to "بیت‌کوین مثل موشک رفت بالا", "money stack" to "با این روش سود گرفتم", "warning sign" to "حواستون باشه خطرناکه")
            val picked = VisualPicker(VisualLibrary.Empty, WebKnowledge(HttpClient(OkHttp)), look = "street-hype").pick(queries)
            report.append("picker (app path): ${(System.nanoTime() - t0) / 1_000_000} ms, ${source.requests} requests\n")
            queries.keys.forEach { q -> report.append("  \"$q\" → ${picked.picks[q]} (relevance ${picked.relevance[q]})\n") }
            picked.found.filter { it.id.startsWith("lf:") }.forEach { icon -> icon.motion?.let { drawn += "${icon.id} (picker)" to it } }
            for (q in listOf("rocket", "money", "warning")) {
                val t = System.nanoTime()
                val hits = source.search(q, 2) { null }
                report.append("direct \"$q\": ${hits.map { "${it.entry.id} ${it.entry.name} ${it.document.length} B" }} in ${(System.nanoTime() - t) / 1_000_000} ms\n")
                hits.forEach { h -> MotionArts.read(MotionArt.FORMAT_LOTTIE, h.document)?.let { drawn += "${h.entry.id} \"$q\"" to it } }
            }
            val n = source.requests
            listOf("rocket", "money", "warning").forEach { source.search(it, 2) { null } }
            report.append("again: ${source.requests - n} new requests; folder ${source.bytes()} bytes, ${source.items().size} animations\n")
        } finally {
            ContentPacks.online = before
        }
        sheet(drawn.distinctBy { it.first.substringBefore(' ') }, File(out, "sheet.png"))
        drawn.distinctBy { it.first.substringBefore(' ') }.forEachIndexed { i, (label, art) -> report.append("row $i: $label ${art.width}x${art.height} ${art.duration}s\n") }
        File(out, "report.txt").writeText(report.toString())
        println(report)
    }

    /** One row per animation, four moments of its clock, on a light grey ground. */
    private fun sheet(arts: List<Pair<String, MotionArt>>, file: File) {
        if (arts.isEmpty()) return
        val cell = 220
        val surface = Surface.makeRasterN32Premul(cell * MOMENTS, cell * arts.size)
        surface.canvas.clear(0xFFE9E9EE.toInt())
        arts.forEachIndexed { row, (_, art) ->
            for (k in 0 until MOMENTS) {
                val image = ImageBitmap(cell, cell)
                val scale = cell / maxOf(art.width, art.height)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(cell.toFloat(), cell.toFloat())) {
                    drawContext.transform.scale(scale, scale, Offset.Zero)
                    art.draw(this, art.duration * (k + 0.5f) / MOMENTS, ArtPaint(Color(0xFF222222)))
                }
                val skia = Image.makeFromBitmap(image.asSkiaBitmap())
                surface.canvas.drawImageRect(skia, Rect.makeXYWH((k * cell).toFloat(), (row * cell).toFloat(), cell.toFloat(), cell.toFloat()))
                surface.canvas.drawRect(Rect.makeXYWH((k * cell).toFloat(), (row * cell).toFloat(), cell.toFloat(), 1f), Paint().apply { color = 0xFFBBBBBB.toInt() })
            }
        }
        file.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private companion object {
        const val MOMENTS = 4
    }
}
