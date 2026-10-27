package io.trimio.engine.motion

/**
 * Motion tokens as code: the few numbers a style pack may change without re-opening the craft
 * rulebook. Duration and cubic-bezier only — a living settle is a spring, not a curve, so the
 * pack writes [springPremiumNote] instead of inventing a fourth composite. Export is one way:
 * [toJson] for the pack file; nothing here parses a pack back.
 */
object MotionTokens {

    /** How long a token composite runs, in milliseconds — packs think in ms, the engine in seconds. */
    data class TokenDuration(val ms: Int)

    /** CSS-style control points; these packs keep every point in 0..1 and never overshoot. */
    data class TokenCubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    /** One transition composite: how long, and on which curve. */
    data class TokenTransition(val duration: TokenDuration, val easing: TokenCubicBezier)

    /**
     * The default cut and UI move: 200 ms on Material's standard curve. Matches [Easing.Standard]
     * so a pack and the engine agree on "normal".
     */
    val standard: TokenTransition = TokenTransition(TokenDuration(200), TokenCubicBezier(0.2f, 0f, 0f, 1f))

    /**
     * Emphasis that must land: 300 ms, a longer start than [standard] so the move reads as chosen,
     * not default. The duration sits on [MotionSpec.Time.FastMoveMin] — the shortest a scene-scale
     * move may run.
     */
    val emphasized: TokenTransition = TokenTransition(TokenDuration(300), TokenCubicBezier(0.3f, 0f, 0f, 1f))

    /**
     * Exits leave faster than they arrived: 140 ms, accelerating out. Shorter than [standard] and
     * [emphasized], the UI neighbour of the rule that exits run well under entrances * ([MotionSpec.Time.ExitDurationScale]).
     */
    val exit: TokenTransition = TokenTransition(TokenDuration(140), TokenCubicBezier(0.4f, 0f, 1f, 1f))

    /**
     * When a pack wants a settle with a whisper of life instead of a curve, it takes the house
     * spring. A cubic-bezier cannot ring; [MotionSpec.Springs.Premium] can, just barely.
     */
    val springPremiumNote: String =
        "Living settle is MotionSpec.Springs.Premium (damping 0.8, frequency 2.0), not a cubic-bezier."

    /**
     * Stable export for style packs: fixed key order (standard, emphasized, exit), fixed nesting,
     * no whitespace tricks — two exports of the same tokens are byte-identical so a pack diff is
     * a real change.
     */
    fun toJson(): String = buildString {
        append('{')
        append(transitionJson("standard", standard))
        append(',')
        append(transitionJson("emphasized", emphasized))
        append(',')
        append(transitionJson("exit", exit))
        append('}')
    }

    private fun transitionJson(name: String, t: TokenTransition): String =
        "\"$name\":{\"duration\":{\"ms\":${t.duration.ms}},\"easing\":{" +
            "\"x1\":${num(t.easing.x1)},\"y1\":${num(t.easing.y1)}," +
            "\"x2\":${num(t.easing.x2)},\"y2\":${num(t.easing.y2)}}}"

    /** Whole numbers stay whole so 0 and 1 never drift into platform-specific float tails. */
    private fun num(v: Float): String {
        val whole = v.toInt()
        return if (v == whole.toFloat()) whole.toString() else v.toString()
    }
}
