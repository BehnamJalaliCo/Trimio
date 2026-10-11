package io.trimio.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.data.DeviceInfo
import io.trimio.core.model.text.Language
import io.trimio.core.model.tools.MediaTools
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.production.AutoEditor
import io.trimio.engine.production.ProductionPlatform
import io.trimio.feature.create.AttachmentPicker
import io.trimio.shared.IncomingMedia
import io.trimio.shared.di.PipelineFactory
import io.trimio.shared.initTrimio
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The desktop app headless: its DI graph, its platform rules, and the main window rendered off-screen. */
class DesktopAppTest {
    private val home = File(System.getProperty("trimio.home") ?: "build/test-home")
    private val hardware = DesktopHardware(ramGb = 32, cores = 16)
    private val incoming = IncomingMedia()

    @BeforeTest
    fun start() {
        home.deleteRecursively()
        val dirs = DesktopDirs.of(override = home.path).create()
        initTrimio(desktopModule(dirs, hardware, DesktopFilePicker { null }, incoming))
    }

    @AfterTest
    fun stop() = stopKoin()

    @Test
    fun mainWindowRenders() = javax.swing.SwingUtilities.invokeAndWait {
        val dirs = DesktopDirs.of(override = home.path)
        val context = DesktopContext(dirs, hardware, DesktopFilePicker { null }, DesktopSharer(dirs.output), incoming) {
            listOf(Gpu("NVIDIA GeForce RTX 4070", 12_282))
        }
        val (width, height) = 1360 to 880
        ImageComposeScene(width, height, Density(1f)) { DesktopShell(context, Language.English) }.use { scene ->
            // Settings load off the main thread: give real time between frames (as AppFlowTest does).
            for (t in 0L..3_000L step 100L) { scene.render(t * 1_000_000); Thread.sleep(15) }
            val image = scene.render(4_000_000_000L)
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/desktop-main-window.png").writeBytes(png)
            assertTrue(png.size > 50_000, "a rendered window, not a blank frame (${png.size} bytes)")
            // The rail (left) and the app (right) are both drawn, in different surfaces.
            val decoded = Image.makeFromEncoded(png)
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(decoded)
            val rail = bitmap.getColor(150, height - 20)
            val app = bitmap.getColor(width - 40, height / 2)
            assertTrue(rail != 0 && app != 0, "opaque window")
            assertTrue(rail != app, "rail and app content differ")
        }
    }

    @Test
    fun desktopGraphResolves() {
        val koin = GlobalContext.get()
        assertNotNull(koin.get<AutoEditor>())
        assertNotNull(koin.get<ProductionPlatform>())
        assertNotNull(koin.get<PipelineFactory>())
        assertNotNull(koin.get<AttachmentPicker>())
        assertEquals(32, koin.get<DeviceInfo>().ramGb)
    }

    @Test
    fun windowsDataAndOutputFolders() {
        val env = mapOf("APPDATA" to "C:\\Users\\sara\\AppData\\Roaming", "USERPROFILE" to "C:\\Users\\sara")
        val dirs = DesktopDirs.of(DesktopOs.Windows, env::get, File("/home/x"), override = null, videos = { null })
        assertEquals(File("C:\\Users\\sara\\AppData\\Roaming", "Trimio"), dirs.data)
        assertEquals(File(File(dirs.data, "models").path), dirs.models)
        assertEquals(File(File("C:\\Users\\sara", "Videos"), "Trimio"), dirs.output)
        val moved = DesktopDirs.of(DesktopOs.Windows, env::get, File("/home/x"), override = null, videos = { File("D:\\Media\\Videos") })
        assertEquals(File(File("D:\\Media\\Videos"), "Trimio"), moved.output)
    }

    @Test
    fun bigDirectorOnlyFromTwentyFourGigabytes() {
        val big = ModelCatalog.qwen36_35bA3b
        assertTrue(big in DesktopDirectors.offered(24))
        assertTrue(big in DesktopDirectors.offered(64))
        assertEquals(listOf(ModelCatalog.qwen35_9b, ModelCatalog.qwen35_4b), DesktopDirectors.offered(16))
        assertTrue(DesktopDirectors.catalogFor(16).none { it.id == big.id })
        // Speech models and eyes pass through untouched.
        assertTrue(DesktopDirectors.catalogFor(16).any { it.id == ModelCatalog.qwen35_9bEyes.id })
        // The experimental MoE is offered, never picked for the user.
        assertEquals(ModelCatalog.qwen35_9b, DesktopDirectors.recommended(DesktopHardware(32, 16)))
        assertEquals(ModelCatalog.qwen35_4b, DesktopDirectors.recommended(DesktopHardware(8, 8)))
    }

    @Test
    fun readsGpusAndRam() {
        assertEquals(listOf(Gpu("NVIDIA GeForce RTX 4070", 12_282)), DesktopHardware.parseNvidiaSmi("NVIDIA GeForce RTX 4070, 12282\n"))
        assertEquals(32, DesktopHardware.ramGb(33_400_000_000L))
        assertEquals(24, DesktopHardware.ramGb(25_300_000_000L))
        assertEquals(16, DesktopHardware.ramGb(17_179_869_184L))
    }

    /** Windows CI only (-Ptrimio.nativeSmoke): the DLLs it built load through the app's own loader. */
    @Test
    fun packagedNativeEnginesLoad() {
        if (System.getProperty("trimio.native.smoke") != "true") return
        val variant = io.trimio.engine.asr.whisper.NativeLibraries.load("trimio_whisper")
        assertTrue(variant in setOf("vulkan", "cpu"), "loaded from a packaged variant, not '$variant'")
        val dir = File(System.getProperty("trimio.native.dir"), variant)
        System.load(File(dir, System.mapLibraryName("trimio_llama")).absolutePath)
    }

    @Test
    fun bundledToolsPointTheEnginesAtTheInstall() {
        val keys = listOf("trimio.ffmpeg.dir", "trimio.h264.encoder", "trimio.native.dir", "trimio.native.variants")
        val saved = keys.associateWith { System.getProperty(it) }
        try {
            keys.forEach(System::clearProperty)
            val resources = File(home, "install/resources").apply { File(this, "ffmpeg").mkdirs(); File(this, "native/cpu").mkdirs() }
            File(resources, "ffmpeg/ffmpeg.exe").writeText("")
            BundledTools.configure(resources, DesktopOs.Windows)
            assertEquals(File(resources, "ffmpeg/ffmpeg.exe").absolutePath, MediaTools.ffmpeg)
            assertEquals("ffprobe", MediaTools.ffprobe, "a tool missing from the bundle falls back to the PATH")
            assertEquals("vulkan,cpu", System.getProperty("trimio.native.variants"))
            val args = MediaTools.h264(listOf("-c:v", "libx264"), maxrateMbps = 20, gop = 60)
            assertEquals(MediaTools.MEDIA_FOUNDATION, args[args.indexOf("-c:v") + 1])
            assertTrue("libx264" !in args)
        } finally {
            saved.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
        }
        assertEquals(listOf("-c:v", "libx264"), MediaTools.h264(listOf("-c:v", "libx264"), maxrateMbps = 20, gop = 60))
    }
}
