package io.trimio.engine.illustration.generate

import io.trimio.engine.illustration.IllustrationRequest
import io.trimio.engine.illustration.Raster
import io.trimio.engine.illustration.library.Library
import io.trimio.engine.illustration.style.FilmLook
import io.trimio.engine.models.IllustrationAddOns
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The generator add-ons through the app's own engine (stable-diffusion.cpp on its own ggml, JNI `trimio_sd`): each draws
 * the scenes the library has no picture for, styled into the film's look. Run by hand (no models in CI):
 *
 * ./gradlew :engine:production:jvmTest --tests '*SdEngineTest*' -Pauto.native=<host build of trimio_sd> [-Psd.out=dir]
 *   -Psd.model=<sdxs gguf>                     the fast illustrator (SDXS-512)
 *   -Psd.klein=<dir with the three klein files> the studio illustrator (FLUX.2 klein 4B, Qwen3-4B, small decoder)
 *   -Psd.only=queue,camel                       only these scenes
 */
class SdEngineTest {
    private val scenes = mapOf(
        "camel" to IllustrationRequest("a camel", place = "desert"),
        "rocket" to IllustrationRequest("a rocket", action = "taking off"),
        "chef" to IllustrationRequest("a chef", action = "cooking rice", objects = listOf("pot")),
        "trader" to IllustrationRequest("a man", action = "looking at a falling price chart on his phone", mood = "worried"),
        // The news film's queue: a sign the model must leave blank (it once wrote «CUNPIA EXOAPNCONFE» on it).
        "queue" to IllustrationRequest("group of people", action = "standing in a long queue in front of a building", objects = listOf("office building", "currency exchange office")),
    )
    private val only = System.getProperty("sd.only")?.split(',')?.map { it.trim() }?.toSet()

    @Test
    fun theFastIllustratorDraws() = runBlocking {
        val model = System.getProperty("sd.model")?.takeIf { File(it).isFile } ?: return@runBlocking
        draw(SdEngine(IllustrationAddOns.sdxs.id, SdEngine.Files(model), threads()), "sdxs")
    }

    @Test
    fun theStudioIllustratorDraws() = runBlocking {
        val dir = System.getProperty("sd.klein")?.let(::File)?.takeIf { it.isDirectory } ?: return@runBlocking
        val a = IllustrationAddOns
        val files = SdEngine.Files(File(dir, a.klein.fileName).path, File(dir, a.kleinText.fileName).path, File(dir, a.kleinDecoder.fileName).path)
        draw(SdEngine(a.klein.id, files, threads()), "klein")
    }

    private suspend fun draw(engine: SdEngine, name: String) {
        val out = File(System.getProperty("sd.out") ?: "build/sd", name).apply { mkdirs() }
        // The engine loads (a missing library fails here, with its reason).
        assertTrue(SdNative.available, "libtrimio_sd is not in trimio.native.dir")
        val source = GeneratorSource(engine, Library.bundled())
        val look = FilmLook(seed = 1402)
        try {
            for ((scene, request) in scenes.filterKeys { only == null || it in only }) {
                val started = System.nanoTime()
                val picture = source.illustrate(request, look, SIZE)
                println("$name $scene: ${(System.nanoTime() - started) / 1_000_000} ms, ${picture?.items}")
                assertNotNull(picture, "no picture for $scene")
                write(picture.image, File(out, "$scene.png"))
                val inked = picture.image.argb.count { (it ushr ALPHA_SHIFT) > HALF && (it and RGB) != look.paper and RGB }
                assertTrue(inked > picture.image.argb.size / INKED_SHARE, "$scene is almost empty ($inked inked pixels)")
            }
        } finally {
            source.release()
        }
    }

    private fun threads() = Runtime.getRuntime().availableProcessors()

    private fun write(r: Raster, file: File) {
        val img = BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, r.width, r.height, r.argb, 0, r.width)
        ImageIO.write(img, "png", file)
    }

    private companion object {
        const val SIZE = 512
        const val ALPHA_SHIFT = 24
        const val HALF = 128
        const val RGB = 0xFFFFFF
        const val INKED_SHARE = 50
    }
}
