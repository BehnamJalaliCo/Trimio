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
        DeviceSpeedTest({ director }, { whisper }).run { row -> rows += row.also { println("speed ${it.kind} | ${it.label} | ${it.value}${if (it.best) " ★" else ""}") } }
        assertTrue(rows.any { it.kind == SpeedRow.Kind.Memory }, "the memory row")
        assertTrue(rows.any { it.kind == SpeedRow.Kind.Director && it.best }, "the director's chosen way: $rows")
        assertTrue(File("$director.placement").isFile, "the choice is kept beside the model")
        if (whisper != null) assertTrue(rows.any { it.kind == SpeedRow.Kind.Speech && it.best }, "Whisper's chosen threads: $rows")
    }
}
