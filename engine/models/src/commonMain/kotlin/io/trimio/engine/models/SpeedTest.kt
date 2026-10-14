package io.trimio.engine.models

/**
 * «سنجش سرعت این دستگاه»: the installed director and speech model timed every way this device can run them (one
 * core, half, all of them, the GPU) and the memory's speed. The fastest way for each model is kept and every build
 * after uses it; the rows say what was measured, for the screen and the report.
 */
interface SpeedTest {
    /** Every measurement: [onRow] each result as it comes, [onProgress] 0..1 as the work goes (never backwards). */
    suspend fun run(onRow: (SpeedRow) -> Unit, onProgress: (Float) -> Unit = {})

    /** The ways every build uses now (the last test's choices, kept on the device); empty before any test. */
    fun current(): List<SpeedRow> = emptyList()
}

/** One measurement: [label] what ran («CPU · ۴ هسته»), [value] how fast, [best] the way the builds now use. */
data class SpeedRow(val kind: Kind, val label: String, val value: String, val best: Boolean = false) {
    enum class Kind { Director, Speech, Memory, Note }
}
