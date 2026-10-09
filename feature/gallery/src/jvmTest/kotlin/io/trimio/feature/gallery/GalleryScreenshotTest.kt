package io.trimio.feature.gallery

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.designsystem.theme.TrimioTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.render.SampleTimelines
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class GalleryScreenshotTest {
    @Test
    fun galleryPersian() {
        val packs = runBlocking { StylePackRepository().all() }
        val density = 2.625f
        ImageComposeScene((412 * density).toInt(), (1400 * density).toInt(), Density(density)) {
            TrimioTheme(Language.Persian) { StyleGalleryScreen(packs, SampleTimelines.cryptoSignal(), {}, {}) }
        }.use { scene ->
            for (t in 0L..1_500L step 250L) scene.render(t * 1_000_000)
            val png = scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/gallery-fa.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
