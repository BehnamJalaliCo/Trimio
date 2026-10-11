package io.trimio.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.trimio.core.data.AppSettings
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.ThemeMode
import io.trimio.core.designsystem.signature.FootagePlaceholder
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.llm.CloudProvider
import io.trimio.engine.models.ModelState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Settings (board 4, third phone) and the offline sheet (its last), Persian and English, phone and desktop. */
class SettingsScreenshotTest {
    private val ui = SettingsUi(
        AppSettings(language = Language.Persian, haptics = true, theme = ThemeMode.Dark),
        mapOf(CloudProvider.Anthropic to true, CloudProvider.OpenAI to false),
        DeviceInfo(12, "Android 16", "1.0.42"),
        DirectorLine("4B", ModelState.Installed, installed = true),
    )

    @Test
    fun settings() {
        shoot("settings-fa", paper = false) { SettingsScreen(ui, SettingsActions(onModels = {})) }
        shoot("settings-fa-paper", paper = true) { SettingsScreen(ui, SettingsActions(onModels = {})) }
        shoot("settings-en", paper = false, language = Language.English) { SettingsScreen(ui, SettingsActions(onModels = {})) }
        shoot("settings-fa-long", paper = false, height = 1500) { SettingsScreen(ui.copy(crashReports = 2, settings = ui.settings.copy(shareCrashReports = true)), SettingsActions(onModels = {})) }
        shoot("settings-fa-desktop", paper = false, width = 1280, height = 800, density = 1.5f) { SettingsScreen(ui, SettingsActions(onModels = {})) }
    }

    @Test
    fun offlineSheet() {
        val sheet: @Composable () -> Unit = {
            SigScreen {
                FootagePlaceholder(Modifier.fillMaxWidth().height(330.dp))
                OfflineSheet(OfflineDirector("4B", designSeconds(36f)), {}, {}, {})
            }
        }
        shoot("offline-fa", paper = false, content = sheet)
        shoot("offline-en", paper = false, language = Language.English, content = sheet)
        shoot("offline-fa-paper", paper = true, content = sheet)
        shoot("offline-fa-desktop", paper = false, width = 1280, height = 800, density = 1.5f, content = sheet)
    }

    private fun shoot(
        name: String,
        paper: Boolean,
        language: Language = Language.Persian,
        width: Int = 390,
        height: Int = 844,
        density: Float = 2f,
        content: @Composable () -> Unit,
    ) {
        ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
            SignatureTheme(language = language, paper = paper) { Box(Modifier.fillMaxSize()) { content() } }
        }.use { scene ->
            for (t in 0L..1_000L step 250L) scene.render(t * 1_000_000)
            val png = scene.render(1_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
