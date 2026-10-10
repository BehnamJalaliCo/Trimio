package io.trimio.engine.autopilot

import kotlin.math.roundToInt

/**
 * How close one model's [Understanding] is to a reference reading of the same transcript, on the
 * decisions that change the edit: how each line is shown, which line opens the piece, the call to
 * action's keyword and the names that get chips. Lets models be compared from saved readings
 * without running them, and calibrates a new director family against a gold reading.
 *
 * Shows that the planner renders alike count as agreement ([EQUIVALENT_SHOWS]): the floor of the
 * edit is set by the deterministic planner and critic, so only differences that change what the
 * viewer sees are penalised.
 */
data class UnderstandingScore(
    /** Lines in the gold reading. */
    val lines: Int,
    /** Share of lines whose show matches the gold one or an equivalent. */
    val show: Float,
    /** Share of lines whose show is exactly the gold one. */
    val showExact: Float,
    val hook: Boolean,
    val ctaKeyword: Boolean,
    /** Share of the gold entities the candidate named. */
    val entityRecall: Float,
    val domain: Boolean,
) {
    /** 0–100, weighted by how much each decision changes the edit. */
    val total: Int
        get() = (
            show * W_SHOW + (if (hook) W_HOOK else 0f) + (if (ctaKeyword) W_CTA else 0f) +
                entityRecall * W_ENTITIES + (if (domain) W_DOMAIN else 0f)
            ).roundToInt()

    /** One line for logs and the calibration harness. */
    fun summary(): String =
        "show=${two(show)} (exact ${two(showExact)}) hook=${bit(hook)} cta=${bit(ctaKeyword)} entities=${two(entityRecall)} domain=${bit(domain)} total=$total"

    companion object {
        private const val W_SHOW = 40f
        private const val W_HOOK = 20f
        private const val W_CTA = 20f
        private const val W_ENTITIES = 15f
        private const val W_DOMAIN = 5f
        private const val MIN_CONTAINED = 3

        /** Shows the planner renders as the same kind of beat. */
        val EQUIVALENT_SHOWS: List<Set<String>> = listOf(
            setOf("headline", "stamp"),
            // A number brought to life: which data beat carries it is the planner's taste.
            setOf("counter", "meter", "chart", "stats", "progress", "countdown"),
            setOf("object", "objects"),
        )

        /** Domains that pick the same look, sounds and pictures. */
        val EQUIVALENT_DOMAINS: List<Set<String>> = listOf(
            setOf("tech", "software", "ai"),
            setOf("crypto", "finance", "business"),
            setOf("medical", "health", "fitness"),
            setOf("beauty", "fashion", "lifestyle"),
        )

        fun of(candidate: Understanding, gold: Understanding): UnderstandingScore {
            val n = gold.lines.size
            val shows = gold.lines.mapIndexed { k, g -> g.show to (candidate.lines.getOrNull(k)?.show ?: "") }
            val names = candidate.entities.map { normalise(it.name) }
            val found = gold.entities.count { g -> normalise(g.name).let { want -> names.any { sameName(it, want) } } }
            return UnderstandingScore(
                lines = n,
                show = if (n == 0) 1f else shows.count { (g, c) -> alike(g, c, EQUIVALENT_SHOWS) } / n.toFloat(),
                showExact = if (n == 0) 1f else shows.count { (g, c) -> g == c } / n.toFloat(),
                hook = gold.hook?.line == candidate.hook?.line,
                ctaKeyword = normalise(gold.cta?.keyword.orEmpty()) == normalise(candidate.cta?.keyword.orEmpty()),
                entityRecall = if (gold.entities.isEmpty()) 1f else found / gold.entities.size.toFloat(),
                domain = alike(gold.domain, candidate.domain, EQUIVALENT_DOMAINS),
            )
        }

        fun alike(a: String, b: String, groups: List<Set<String>>): Boolean = a == b || groups.any { a in it && b in it }

        /** "Claude" names "Claude Code"; very short names must match exactly. */
        private fun sameName(a: String, b: String): Boolean =
            a == b || (minOf(a.length, b.length) >= MIN_CONTAINED && (a in b || b in a))

        /**
         * Compares what a viewer would type: quotes, spaces and half-spaces dropped, Arabic letter
         * forms folded into Persian ones, digits made ASCII, case ignored.
         */
        fun normalise(text: String): String = buildString {
            for (c in text.lowercase()) {
                when {
                    c.isWhitespace() || c in DROPPED -> Unit
                    c == 'ي' || c == 'ى' -> append('ی')
                    c == 'ك' -> append('ک')
                    c in '۰'..'۹' -> append('0' + (c - '۰'))
                    c in '٠'..'٩' -> append('0' + (c - '٠'))
                    else -> append(c)
                }
            }
        }

        private const val DROPPED = "«»\"'“”‘’`.,،!?؟:;‌‍‏‎#"

        private fun two(f: Float): String {
            val n = (f * 100).roundToInt()
            return "${n / 100}.${(n % 100).toString().padStart(2, '0')}"
        }

        private fun bit(b: Boolean) = if (b) "1" else "0"
    }
}
