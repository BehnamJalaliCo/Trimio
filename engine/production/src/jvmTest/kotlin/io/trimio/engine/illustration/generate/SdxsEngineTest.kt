package io.trimio.engine.illustration.generate

import io.trimio.engine.illustration.IllustrationRequest
import io.trimio.engine.illustration.Raster
import io.trimio.engine.illustration.library.Library
import io.trimio.engine.illustration.style.FilmLook
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The generator add-on through the app's own engine (stable-diffusion.cpp on our ggml, JNI `trimio_sd`): SDXS-512
 * draws the scenes the library has no picture for, styled into the film's look. Run by hand (no model in CI):
 *
 * ./gradlew :engine:production:jvmTest --tests '*SdxsEngineTest*' -Psd.model=<sdxs gguf> -Pauto.native=<host build> [-Psd.out=dir]
 */
class SdxsEngineTest {
    @Test
    fun drawsThroughTheAppsOwnEngine() = runBlocking {
        val model = System.getProperty("sd.model")?.takeIf { File(it).isFile } ?: return@runBlocking
        val out = File(System.getProperty("sd.out") ?: "build/sdxs").apply { mkdirs() }
        // The engine loads (a missing library or a model it cannot read fails here, with its reason).
        assertTrue(SdNative.available, "libtrimio_sd is not in trimio.native.dir")
        val handle = SdNative.init(model, 2)
        assertTrue(handle != 0L, "stable-diffusion.cpp did not load $model")
        SdNative.free(handle)
        val source = GeneratorSource(SdxsEngine(model, threads = Runtime.getRuntime().availableProcessors()), Library.bundled())
        val look = FilmLook(seed = 1402)
        val scenes = mapOf(
            "camel" to IllustrationRequest("a camel", place = "desert"),
            "rocket" to IllustrationRequest("a rocket", action = "taking off"),
            "chef" to IllustrationRequest("a chef", action = "cooking rice", objects = listOf("pot")),
        )
        try {
            for ((name, request) in scenes) {
                val started = System.nanoTime()
                val picture = source.illustrate(request, look, SIZE)
                println("sdxs $name: ${(System.nanoTime() - started) / 1_000_000} ms, ${picture?.items}")
                assertNotNull(picture, "no picture for $name")
                write(picture.image, File(out, "$name.png"))
                val inked = picture.image.argb.count { (it ushr ALPHA_SHIFT) > HALF && (it and RGB) != look.paper and RGB }
                assertTrue(inked > picture.image.argb.size / INKED_SHARE, "$name is almost empty ($inked inked pixels)")
            }
        } finally {
            source.release()
        }
    }

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
