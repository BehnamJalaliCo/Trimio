package io.trimio.feature.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.text.Language
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Home after setup with the libraries suggestion card, Persian and English. */
class LibraryCardScreenshotTest {
    @Test
    fun homeSuggestsTheLibraries() {
        for ((name, language) in listOf("home-library-fa" to Language.Persian, "home-library-en" to Language.English)) {
            ImageComposeScene(780, 1688, Density(2f)) {
                SignatureTheme(language = language) {
                    Box(Modifier.fillMaxSize()) { StudioScreen(HomeState(readiness = HomeReadiness(online = true), now = 1_800_000_000_000L, library = LibraryHint(3, 58_000_000)), HomeActions()) }
                }
            }.use { scene ->
                for (t in 0L..2_500L step 250L) scene.render(t * 1_000_000)
                val png = scene.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File("build/screenshots").mkdirs()
                File("build/screenshots/$name.png").writeBytes(png)
                assertTrue(png.size > 30_000)
            }
        }
    }
}
