package io.trimio.engine.production.diagnostics

/**
 * Process deaths, read from the marker a run leaves while it works ([Reports.mark]): before a
 * native-heavy step it writes `entering <step>@<session>`, after it `left <step>@<session>`, and
 * between them `in <step>@<session>`; a clean end removes the marker. A marker left by another
 * process ([session] differs; `#…` after it names the run within the process) means that process
 * ended mid-run: killed for memory, crashed in native code, or closed. The same process restarting
 * a run it replaced is no death.
 */
object Deaths {
    /** Deaths in one step before the run gives up and says so (instead of dying in a loop). */
    const val LIMIT = 2

    /** What the marker says, or null when it is absent or this process's own. */
    fun died(marker: String?, session: String): String? {
        val line = marker?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val owner = line.substringAfterLast('@', "").substringBefore('#')
        if (owner == session.substringBefore('#')) return null
        val body = line.substringBeforeLast('@')
        return when {
            body.startsWith(ENTERING) -> body.removePrefix(ENTERING)
            body.startsWith(LEFT) -> "after " + body.removePrefix(LEFT)
            body.startsWith(IN) -> body.removePrefix(IN)
            else -> body
        }.trim().ifEmpty { "unknown" }
    }

    /** "llm understand 12/48" and "llm understand 15/48" are one place: numbers and counters dropped. */
    fun place(step: String): String =
        step.split(' ').filter { w -> w.none { it.isDigit() } }.joinToString(" ").trim().ifEmpty { step }

    /** The place the run should give up on: [LIMIT] counted deaths there. */
    fun giveUp(deaths: List<Death>): String? =
        deaths.filter { it.counted }.groupBy { place(it.step) }.entries.firstOrNull { it.value.size >= LIMIT }?.key

    fun entering(step: String, session: String) = "$ENTERING$step@$session"

    fun left(step: String, session: String) = "$LEFT$step@$session"

    fun inside(step: String, session: String) = "$IN$step@$session"

    private const val ENTERING = "entering "
    private const val LEFT = "left "
    private const val IN = "in "
}

/**
 * No progress for too long. Progress is anything that changes what the run reports (stage, step,
 * the bar, frames) or a model streaming text ([saw] with a new signature). [limitMs] is how long a
 * silent step may take on this device ([limitFor]); [keepWaiting] gives it as long again.
 */
class Watchdog(private val limitMs: Long, private val now: () -> Long) {
    private var signature: Any? = null
    private var last = now()
    private var limit = limitMs

    /** Milliseconds without progress when over the limit (the run looks stalled), else null. */
    fun stalled(): Long? = (now() - last).takeIf { it >= limit }

    /** A report from the run; true when it moved (and any stall is over). */
    fun saw(next: Any): Boolean {
        if (next == signature) return false
        signature = next
        last = now()
        limit = limitMs
        return true
    }

    /** The creator chose to wait: another full limit from now, twice as long as before. */
    fun keepWaiting() {
        last = now()
        limit *= 2
    }

    companion object {
        private const val MINUTE = 60_000L

        /**
         * How long a step may go without any progress: a model's prompt processing streams nothing
         * for minutes on a phone's CPU, so phones wait longer, smaller phones longest.
         */
        fun limitFor(tier: io.trimio.engine.models.DeviceTier?): Long = when (tier) {
            null -> 4 * MINUTE
            io.trimio.engine.models.DeviceTier.Standard -> 8 * MINUTE
            io.trimio.engine.models.DeviceTier.High -> 6 * MINUTE
            io.trimio.engine.models.DeviceTier.Ultra, io.trimio.engine.models.DeviceTier.Max -> 5 * MINUTE
        }
    }
}
