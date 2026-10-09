package io.trimio.feature.settings

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.data.AppSettings
import io.trimio.core.data.DeviceInfo
import io.trimio.core.designsystem.theme.TrimioTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.llm.CloudProvider
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SettingsScreenshotTest {
    @Test
    fun settingsPersian() {
        val states = mapOf(
            ModelCatalog.whisperSmallQ8.id to ModelState.Installed,
            ModelCatalog.qwen35_4b.id to ModelState.Downloading(0.42f, 1_150_000_000),
            ModelCatalog.whisperTurboQ8.id to ModelState.Failed("x"),
        )
        val density = 2.625f
        ImageComposeScene((412 * density).toInt(), (1900 * density).toInt(), Density(density)) {
            TrimioTheme(Language.Persian) {
                SettingsScreen(
                    AppSettings(speechModelId = ModelCatalog.whisperSmallQ8.id), ModelCatalog.all, states,
                    mapOf(CloudProvider.Anthropic to true, CloudProvider.OpenAI to false), DeviceInfo(12, "Android 16", "0.1.0"),
                    {}, {}, {}, {}, {}, { _, _ -> }, {},
                )
            }
        }.use { scene ->
            for (t in 0L..1_500L step 250L) scene.render(t * 1_000_000)
            val png = scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/settings-fa.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
