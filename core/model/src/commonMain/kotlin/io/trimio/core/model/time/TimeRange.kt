package io.trimio.core.model.time

import kotlinx.serialization.Serializable

/** Half-open interval `[startMs, endMs)` on the output timeline, in milliseconds. */
@Serializable
data class TimeRange(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0) { "startMs must be >= 0, was $startMs" }
        require(endMs >= startMs) { "endMs ($endMs) must be >= startMs ($startMs)" }
    }

    val durationMs: Long get() = endMs - startMs

    operator fun contains(timeMs: Long): Boolean = timeMs in startMs until endMs

    fun overlaps(other: TimeRange): Boolean = startMs < other.endMs && other.startMs < endMs

    fun isWithin(totalMs: Long): Boolean = endMs <= totalMs

    fun shift(deltaMs: Long): TimeRange = TimeRange(startMs + deltaMs, endMs + deltaMs)

    companion object {
        fun ofDuration(startMs: Long, durationMs: Long) = TimeRange(startMs, startMs + durationMs)
    }
}
