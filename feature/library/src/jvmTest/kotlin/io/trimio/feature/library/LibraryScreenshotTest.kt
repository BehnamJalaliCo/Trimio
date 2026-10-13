package io.trimio.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.models.PackChannel
import io.trimio.engine.models.PackIndexClient
import io.trimio.engine.models.PackKind
import io.trimio.engine.models.PackManifest
import io.trimio.engine.models.PackState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «کتابخانه‌ها» off-screen from the shipped catalogue: Persian and English, noir and paper, phone and desktop. */
class LibraryScreenshotTest {
    /** The catalogue this build ships (files/core/index.json, written by tools/packs). */
    private val shipped: List<PackManifest> =
        PackIndexClient.parseIndex(File("src/commonMain/composeResources/files/core/index.json").readText(), PackChannel())

    private val mine = PackManifest(
        "art.mine", PackKind.Art, 2, 1_400_000, "", emptyList(), PackManifest.LOCAL_KEY, "", titleFa = "انیمیشن‌های من", titleEn = "My animations", items = 14,
    )

    private fun states(): Map<String, PackState> = mapOf(
        "art.noto-emoji" to PackState.Installed(1),
        "art.line-md" to PackState.Installed(1),
        "art.noto-emoji-tones" to PackState.Downloading(7_300_000, 17_451_020),
        "sound.kenney-sfx" to PackState.Paused(5_000_000, 16_500_000),
        "look.cinematic-luts" to PackState.Queued,
        "art.mine" to PackState.Installed(2),
    )

    private fun ui(desktop: Boolean, s: Map<String, PackState> = states()) = LibraryUi(
        packs = LibraryUi.rows(shipped + mine, s), usedBytes = 44_000_000, freeBytes = 23_400_000_000, remainingBytes = 62_000_000,
        desktop = desktop, canImport = desktop, importNote = Words("۱۴ انیمیشن اضافه شد؛ ۲ رد شد (⁦text layer⁩)", "14 added; 2 refused (text layer)"),
    )

    @Test
    fun theShippedCatalogueListsEveryBuiltPack() {
        val ids = shipped.map { it.id }.toSet()
        assertTrue(listOf("art.noto-emoji", "art.line-md", "sound.kenney-sfx", "overlay.film-textures", "look.cinematic-luts").all { it in ids }, "$ids")
        assertTrue(shipped.all { it.urls.single().startsWith(PackChannel.DEFAULT_BASE + "/") })
        val rows = LibraryUi.rows(shipped + mine, emptyMap())
        assertEquals(PackGroup.Mine, rows.first { it.id == "art.mine" }.group)
        assertEquals(PackGroup.Sound, rows.first { it.id == "sound.kenney-sfx" }.group)
        assertEquals(PackGroup.Visual, rows.first { it.id == "look.cinematic-luts" }.group)
    }

    @Test
    fun phone() {
        shoot("library-fa-noir", Language.Persian, paper = false) { LibraryScreen(ui(false), LibraryActions()) }
        shoot("library-fa-paper", Language.Persian, paper = true, height = 1700) { LibraryScreen(ui(false), LibraryActions()) }
        shoot("library-en-noir", Language.English, paper = false) { LibraryScreen(ui(false, s = emptyMap()), LibraryActions()) }
    }

    @Test
    fun desktop() {
        shoot("library-fa-desktop", Language.Persian, paper = false, width = 1280, height = 900, density = 1.5f) { LibraryScreen(ui(true), LibraryActions()) }
        shoot("library-en-desktop", Language.English, paper = true, width = 1280, height = 900, density = 1.5f) { LibraryScreen(ui(true), LibraryActions()) }
    }

    private fun shoot(name: String, language: Language, paper: Boolean, width: Int = 390, height: Int = 844, density: Float = 2f, content: @Composable () -> Unit) {
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
