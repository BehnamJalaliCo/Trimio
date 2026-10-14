package io.trimio.engine.production

import kotlin.concurrent.Volatile

/**
 * How tight memory is while an edit is made: the system's own warning (Android's onTrimMemory at a running-low level)
 * and the memory free now (`MemAvailable`). When it is low, the optional parts give way (the eyes' look at rendered
 * frames, animations from the web, live previews): the edit is still made, and the app is not killed for it.
 */
object MemoryPressure {
    /** The system warned that memory runs low; cleared when a run starts. */
    @Volatile
    var warned: Boolean = false

    /** Memory free now, in bytes; set by the platform (null where it cannot be read). */
    var free: () -> Long? = { null }

    /** Memory is low now: the system warned, or less than [LOW_BYTES] is free. */
    fun low(): Boolean = warned || (runCatching { free() }.getOrNull()?.let { it < LOW_BYTES } == true)

    /** Below this much free memory, the optional parts give way (a director's next call and its buffers still fit). */
    const val LOW_BYTES = 1_200_000_000L
}
