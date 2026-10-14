package io.trimio.engine.models

/**
 * «سنجش سرعت این دستگاه»: the installed director and speech model timed every way this device can run them (one
 * core, half, all of them, the GPU) and the memory's speed. The fastest way for each model is kept and every build
 * after uses it; the rows say what was measured, for the screen and the report.
 */
fun interface SpeedTest {
    suspend fun run(onRow: (SpeedRow) -> Unit)
}

/** One measurement: [label] what ran («CPU · ۴ هسته»), [value] how fast, [best] the way the builds now use. */
data class SpeedRow(val kind: Kind, val label: String, val value: String, val best: Boolean = false) {
    enum class Kind { Director, Speech, Memory, Note }
}
