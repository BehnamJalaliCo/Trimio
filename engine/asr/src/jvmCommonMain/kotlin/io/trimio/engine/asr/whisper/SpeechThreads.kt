package io.trimio.engine.asr.whisper

import java.io.File

/**
 * The thread count the speed test found fastest for a speech model on this device, kept beside the model
 * (`<model>.threads`); builds use it instead of the device's default. Absent until the test has run.
 */
object SpeechThreads {
    fun cached(model: String): Int? = runCatching { File("$model.threads").takeIf(File::isFile)?.readText()?.trim()?.toInt() }.getOrNull()?.takeIf { it > 0 }

    fun keep(model: String, threads: Int) {
        runCatching { File("$model.threads").writeText("$threads\n") }
    }
}
