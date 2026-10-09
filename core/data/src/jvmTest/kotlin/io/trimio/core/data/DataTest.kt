package io.trimio.core.data

import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.input.Resolution
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataTest {
    private val dir = Files.createTempDirectory("trimio-data").toString()

    @Test
    fun projectsSurviveARestart(): Unit = runBlocking {
        val store = FileProjectStore(Path(dir, "projects"))
        val repo = ProjectRepository(store)
        repo.load()
        val p = Project("a", "عنوان", 1, 1, InputSource.AudioOnly(MediaUri("content://x"), 1_000, CanvasSpec()), "پرامپت")
        repo.save(p)
        repo.update("a") { it.copy(status = ProjectStatus.Ready) }
        val reopened = ProjectRepository(FileProjectStore(Path(dir, "projects"))).apply { load() }
        assertEquals(ProjectStatus.Ready, reopened.get("a")?.status)
        assertEquals("عنوان", reopened.get("a")?.title)
    }

    @Test
    fun settingsPersist(): Unit = runBlocking {
        val file = Path(dir, "settings.json")
        SettingsRepository(file).update { it.copy(onboardingDone = true, exportResolution = Resolution.Uhd4k) }
        val again = SettingsRepository(file).apply { load() }
        assertTrue(again.settings.value.onboardingDone)
        assertEquals(Resolution.Uhd4k, again.settings.value.exportResolution)
    }

    @Test
    fun deviceProfilesAndThermalThrottling() {
        val phone = DeviceProfile.of(ramGb = 8, cores = 8)
        assertEquals(DeviceProfile.Tier.Standard, phone.tier)
        assertEquals(4, phone.threads)
        assertEquals(2, phone.threadsFor(ThermalLevel.Hot))
        assertEquals(1, phone.threadsFor(ThermalLevel.Critical))
        assertEquals(Resolution.Uhd4k, DeviceProfile.of(16, 8).defaultResolution)
    }

    @Test
    fun crashLogKeepsTheNewestReports() {
        val log = CrashLog(Path(dir, "crashes"), keep = 3)
        repeat(5) { log.record(IllegalStateException("boom $it"), "Pixel", "1.0", 1_000L + it) }
        val reports = log.reports()
        assertEquals(3, reports.size)
        assertTrue(log.read(reports.first()).contains("boom 4"))
    }
}
