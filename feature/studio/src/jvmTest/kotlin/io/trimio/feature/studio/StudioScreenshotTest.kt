package io.trimio.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.trimio.core.data.MediaKind
import io.trimio.core.data.ProjectStatus
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelState
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Onboarding and home (board 1) off-screen: Persian RTL and English, noir and paper, phone and a
 * desktop window, into build/screenshots for review against docs/design/screens/1-onboarding-home.png.
 * Phone shots keep a 50dp status bar at the top, as the board does.
 */
class StudioScreenshotTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    /** A synthetic poster: a warm graded plate with a headline bar and a lime block. */
    private fun poster(warm: Boolean): ImageBitmap {
        val img = ImageBitmap(270, 480)
        val canvas = Canvas(img)
        val colors = if (warm) listOf(Color(0xFF3A1D0E), Color(0xFF0C0B0A), Color(0xFF1A120C)) else listOf(Color(0xFF0D1830), Color(0xFF0A0F1A), Color(0xFF1B1430))
        canvas.drawRect(Rect(0f, 0f, 270f, 480f), Paint().apply { shader = LinearGradientShader(Offset(0f, 0f), Offset(270f, 480f), colors) })
        canvas.drawCircle(Offset(170f, 150f), 110f, Paint().apply { color = if (warm) Color(0x66FF965A) else Color(0x667AAAFF) })
        canvas.drawRect(Rect(40f, 300f, 230f, 336f), Paint().apply { color = Color(0xFFF2EDE4) })
        canvas.drawRect(Rect(40f, 350f, 150f, 366f), Paint().apply { color = Color(0xFFD7FF3A) })
        return img
    }

    private val projectsFa = listOf(
        HomeProject("a", "سیگنال امروز بیت‌کوین", "نوآر لوکس", durationMs = 42_000, updatedAt = now - day, status = ProjectStatus.Ready, fresh = true, poster = poster(true)),
        HomeProject("b", "پادکست قسمت ۱۲", "پوستر کینتیک", audioOnly = true, updatedAt = now - hour, status = ProjectStatus.Building, progress = 0.68f),
        HomeProject("c", "معرفی محصول", "شیشهٔ لومن", durationMs = 28_000, updatedAt = now - 3 * day, status = ProjectStatus.Ready, poster = poster(false)),
        HomeProject("d", "تحلیل طلا هفتگی", "ترمینال مالی", durationMs = 70_000, updatedAt = now - 8 * day, status = ProjectStatus.Ready),
        HomeProject("e", "آموزش فارکس", "مستند", updatedAt = now - 9 * day, status = ProjectStatus.Failed, interrupted = true),
    )

    private val projectsEn = listOf(
        HomeProject("a", "Today's Bitcoin signal", "Luxury Noir", durationMs = 42_000, updatedAt = now - day, status = ProjectStatus.Ready, fresh = true, poster = poster(true)),
        HomeProject("b", "Podcast episode 12", "Kinetic Poster", audioOnly = true, updatedAt = now - hour, status = ProjectStatus.Building, progress = 0.68f),
        HomeProject("c", "Product intro", "Lumen Glass", durationMs = 28_000, updatedAt = now - 3 * day, status = ProjectStatus.Ready, poster = poster(false)),
        HomeProject("d", "Weekly gold analysis", "Finance Terminal", durationMs = 70_000, updatedAt = now - 8 * day, status = ProjectStatus.Failed),
    )

    private val ready = HomeReadiness(HomeReadiness.Director.Ready, online = false)

    private fun home(projects: List<HomeProject>, readiness: HomeReadiness = ready) = HomeState(projects, readiness, now = now)

    // The defaults as they look mid-download: speech installed, the director at 1.15 GB.
    private val defaults = ModelCatalog.defaults()
    private val states = defaults.associate { spec ->
        spec.id to when (spec.kind) {
            ModelKind.Speech -> ModelState.Installed
            ModelKind.Language -> ModelState.Downloading(0.42f, 1_150_000_000)
            else -> ModelState.NotInstalled
        }
    }
    private val stronger = ModelCatalog.all.filter { it.kind == ModelKind.Language && !it.isDefault && !it.experimental && it.tier.minRamGb <= 12 }
    private val device = OnboardingDevice(ramGb = 12, cores = 8, freeBytes = 41_300_000_000)

    @Composable
    private fun Onboarding(page: Int) = OnboardingScreen(Language.Persian, defaults, states, device, {}, {}, {}, {}, stronger, startPage = page)

    @Test
    fun onboardingPersian() {
        shoot("onboarding-welcome-fa") { Onboarding(PAGE_WELCOME) }
        shoot("onboarding-models-fa") { Onboarding(PAGE_MODELS) }
        shoot("onboarding-privacy-fa") { Onboarding(PAGE_PRIVACY) }
        shoot("onboarding-models-fa-paper", paper = true) { Onboarding(PAGE_MODELS) }
    }

    @Test
    fun onboardingEnglish() {
        shoot("onboarding-welcome-en", Language.English) { OnboardingScreen(null, defaults, states, device, {}, {}, {}, {}, stronger) }
        shoot("onboarding-models-en", Language.English) { OnboardingScreen(Language.English, defaults, states, device, {}, {}, {}, {}, stronger, startPage = PAGE_MODELS) }
        shoot("onboarding-privacy-en", Language.English) { OnboardingScreen(Language.English, defaults, states, device, {}, {}, {}, {}, startPage = PAGE_PRIVACY) }
    }

    @Test
    fun homePersian() {
        shoot("home-empty-fa") { StudioScreen(home(emptyList()), HomeActions()) }
        shoot("home-projects-fa") { StudioScreen(home(projectsFa), HomeActions()) }
        shoot("home-projects-fa-paper", paper = true) { StudioScreen(home(projectsFa, HomeReadiness(HomeReadiness.Director.Downloading, online = true, progress = 0.42f)), HomeActions()) }
        shoot("home-projects-fa-wide", width = 1280, height = 800, density = 1.5f) { StudioScreen(home(projectsFa), HomeActions()) }
        shoot("home-empty-fa-wide", width = 1280, height = 800, density = 1.5f, paper = true) { StudioScreen(home(emptyList()), HomeActions()) }
    }

    @Test
    fun homeEnglish() {
        shoot("home-empty-en", Language.English) { StudioScreen(home(emptyList(), HomeReadiness(HomeReadiness.Director.Missing)), HomeActions()) }
        shoot("home-projects-en", Language.English) { StudioScreen(home(projectsEn, HomeReadiness(HomeReadiness.Director.Ready, online = true)), HomeActions()) }
        shoot("home-projects-en-wide", Language.English, width = 1280, height = 800, density = 1.5f) { StudioScreen(home(projectsEn), HomeActions()) }
    }

    /** Accessibility: the largest common system text size must not break the layout. */
    @Test
    fun homeLargeFont() = shoot("home-projects-fa-font160", fontScale = 1.6f) { StudioScreen(home(projectsFa), HomeActions()) }

    @Test
    fun solarHijriDates() {
        assertEquals(Triple(1403, 1, 1), HomeDate.jalali(LocalDate(2024, 3, 20)))
        assertEquals(Triple(1405, 7, 19), HomeDate.jalali(LocalDate(2026, 10, 11)))
        assertEquals(Triple(1399, 12, 30), HomeDate.jalali(LocalDate(2021, 3, 20)))
        assertEquals("یکشنبه، 19 مهر", HomeDate.line(LocalDate(2026, 10, 11), persian = true))
        assertEquals("Sunday, 11 October", HomeDate.line(LocalDate(2026, 10, 11), persian = false))
        assertEquals(MediaKind.Audio, Idea.kindFor("یک پادکست بساز"))
        assertEquals(MediaKind.Video, Idea.kindFor("ریلز پرانرژی"))
    }

    private fun shoot(
        name: String,
        language: Language = Language.Persian,
        paper: Boolean = false,
        width: Int = 390,
        height: Int = 844,
        density: Float = 2f,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        val phone = width < 600
        ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density, fontScale)) {
            SignatureTheme(language = language, paper = paper) {
                Box(Modifier.fillMaxSize().background(Sig.colors.canvas).padding(top = if (phone) 50.dp else 0.dp)) { content() }
            }
        }.use { scene ->
            // The entrance runs 2.3 s; frames every 100 ms let every spring and tween settle.
            for (t in 0L..3_000L step 100L) scene.render(t * 1_000_000)
            val png = scene.render(3_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
