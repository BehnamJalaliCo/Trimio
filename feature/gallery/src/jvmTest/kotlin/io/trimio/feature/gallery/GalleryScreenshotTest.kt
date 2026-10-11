package io.trimio.feature.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.production.StyleSamples
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The style gallery (board 4, second phone) with every card playing its live sample: Persian and
 * English, noir and paper, phone and desktop. The samples are compiled first, as the app does on
 * opening (cards show their still until then).
 */
class GalleryScreenshotTest {
    private val engine: SampleEngine = runBlocking {
        val fonts = MotionFonts.load()
        val samples = StyleSamples(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), fonts, BrandLibrary.load())
        val shelf = SampleShelf { samples.sample(it, sampleWord(it)) }
        StyleBook.all.forEach { shelf.of(it.id) }
        SampleEngine(fonts, shelf, standInFootage())
    }

    @Test
    fun gallery() {
        shoot("gallery-fa", paper = false) { StyleGalleryScreen(GalleryUi(), GalleryActions(), engine) }
        shoot("gallery-fa-paper", paper = true) { StyleGalleryScreen(GalleryUi(), GalleryActions(), engine) }
        shoot("gallery-en", paper = false, language = Language.English) { StyleGalleryScreen(GalleryUi(GalleryFilter.Bold), GalleryActions(), engine) }
        shoot("gallery-fa-desktop", paper = false, width = 1280, height = 800, density = 1.5f) { StyleGalleryScreen(GalleryUi(), GalleryActions(), engine) }
        shoot("gallery-fa-loading", paper = false) { StyleGalleryScreen(GalleryUi(), GalleryActions(), null) }
    }

    @Test
    fun everyStyleHasALiveSampleAndAPlace() {
        assertEquals(28, StyleBook.all.size)
        for (s in StyleBook.all) {
            val c = engine.shelf.cached(s.id)?.composition
            assertTrue(c != null && c.duration > 3f && c.width == 1080, "a sample for ${s.id}")
        }
        // Every style answers at least one filter besides «همه», and every filter has styles.
        val unplaced = GalleryStyles.all.filter { it.groups.isEmpty() }.map { it.id }
        assertTrue(unplaced.isEmpty(), "unfiltered: $unplaced")
        GalleryFilter.entries.forEach { f -> assertTrue(GalleryStyles.filter(f).size >= 3, "$f has styles") }
        assertTrue(GalleryStyles.all.all { it.hintFa.isNotEmpty() }, "every card says what it is like")
    }

    private fun shoot(name: String, paper: Boolean, language: Language = Language.Persian, width: Int = 390, height: Int = 844, density: Float = 2f, content: @Composable () -> Unit) {
        ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
            SignatureTheme(language = language, paper = paper) { Box(Modifier.fillMaxSize()) { content() } }
        }.use { scene ->
            // Cards start where their headline has landed; 0.7 s later it is still on screen.
            for (t in 0L..600L step 100L) scene.render(t * 1_000_000)
            val png = scene.render(700_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
