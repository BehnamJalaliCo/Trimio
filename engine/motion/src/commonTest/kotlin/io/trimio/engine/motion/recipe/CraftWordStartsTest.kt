package io.trimio.engine.motion.recipe

/**
 * Unit start times from speech when known (one per word), else an even stagger from [at].
 * A wordTimes list shorter than [count] still wins for the words it covers: the tail continues
 * at the stagger cadence after the last spoken word, rather than throwing the real rhythm away.
 */
fun wordStarts(cue: Cue, count: Int, stagger: Float): List<Float> {
    val spoken = cue.wordTimes.orEmpty()
    if (spoken.isEmpty()) return List(count) { cue.at - LEAD + it * stagger }
    return List(count) { i -> (spoken.getOrNull(i) ?: spoken.last() + (i - spoken.lastIndex) * stagger) - LEAD }
}