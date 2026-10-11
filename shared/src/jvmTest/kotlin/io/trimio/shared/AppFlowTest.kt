package io.trimio.shared

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.model.text.Language
import io.trimio.shared.di.previewPlatformModule
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.context.stopKoin
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** The whole app wired through DI: the first run renders onboarding. */
class AppFlowTest {

    @BeforeTest
    fun start() = initTrimio(previewPlatformModule("test"))

    @AfterTest
    fun stop() = stopKoin()

    @Test
    fun firstRunShowsOnboarding() = javax.swing.SwingUtilities.invokeAndWait {
        // Navigation entries own lifecycles, which must be driven from the main (UI) thread.
        val density = 2.625f
        ImageComposeScene((412 * density).toInt(), (915 * density).toInt(), Density(density)) {
            TrimioApp(Language.Persian)
        }.use { scene ->
            // Settings load off the main thread: give real time between frames.
            for (t in 0L..3_000L step 100L) { scene.render(t * 1_000_000); Thread.sleep(15) }
            val png = scene.render(4_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/app-onboarding-fa.png").writeBytes(png)
            assertTrue(png.size > 50_000)
        }
    }
}
