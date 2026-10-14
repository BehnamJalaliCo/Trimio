package io.trimio.engine.production

import io.trimio.engine.models.SpeedRow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * «سنجش سرعت این دستگاه» on this machine with real models, run by hand (no models in CI):
 *
 * ./gradlew :engine:production:jvmTest --tests '*DeviceSpeedTestRun*' -Pspeed.director=<gguf> [-Pspeed.whisper=<bin>] -Pauto.native=<host build>
 *
 * Every row the settings screen shows is printed; the director's best way and Whisper's best threads are kept
 * beside the models as the app keeps them.
 */
class DeviceSpeedTestRun {
    @Test
    fun measuresAndKeepsTheFastestWay() = runBlocking {
        val director = System.getProperty("speed.director")?.takeIf { File(it).isFile } ?: return@runBlocking
        val whisper = System.getProperty("speed.whisper")?.takeIf { File(it).isFile }
        val rows = mutableListOf<SpeedRow>()
        val progress = mutableListOf<Float>()
        val profile = File.createTempFile("speed-profile", ".json").apply { delete() }
        val test = DeviceSpeedTest({ director }, { whisper }, profile)
        val started = System.nanoTime()
        test.run(
            onRow = { row -> rows += row.also { println("speed ${it.kind} | ${it.label} | ${it.value}${if (it.best) " ★" else ""}") } },
            onProgress = { p -> progress += p.also { println("speed %5.1f%% at %.1f s".format(p * 100, (System.nanoTime() - started) / 1e9)) } },
        )
        assertTrue(rows.any { it.kind == SpeedRow.Kind.Memory }, "the memory row")
        assertTrue(rows.any { it.kind == SpeedRow.Kind.Director && it.best }, "the director's chosen way: $rows")
        assertTrue(File("$director.placement").isFile, "the choice is kept beside the model")
        if (whisper != null) assertTrue(rows.any { it.kind == SpeedRow.Kind.Speech && it.best }, "Whisper's chosen threads: $rows")
        // The percentage only moves forward, often (not in four jumps), and ends at 100.
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a } && progress.last() == 1f, "$progress")
        assertTrue(progress.size > 50, "a stream, not a few jumps: ${progress.size}")
        // What the builds use now is kept: the settings show it, the picture engine takes its thread count.
        val kept = requireNotNull(SpeedProfile.load(profile)) { "the profile is kept" }
        assertTrue(kept.cpuThreads != null && test.current().any { it.kind == SpeedRow.Kind.Director }, "$kept")
    }
}
