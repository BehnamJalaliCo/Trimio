package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Style packs may only move within craft: exits shorter than standards shorter than emphasis,
 * control points inside the unit square, and an export that still names all three composites —
 * a pack that drifts past [MotionSpec.Time.FastMoveMin] without meaning to is how pieces start
 * reading generated.
 */
class MotionTokensTest {

    @Test
    fun durationsSitInTheCraftBands() {
        val exit = MotionTokens.exit.duration.ms
        val standard = MotionTokens.standard.duration.ms
        val emphasized = MotionTokens.emphasized.duration.ms
        assertTrue(exit < standard, "exit leaves faster than the default move")
        assertTrue(standard < emphasized, "emphasis is the slowest of the three")
        // Scene-scale floor from the rulebook: only the emphasized token may touch a fast move,
        // and it sits exactly on MotionSpec.Time.FastMoveMin (0.3 s).
        assertTrue(emphasized / 1000f >= MotionSpec.Time.FastMoveMin)
        assertTrue(emphasized / 1000f <= MotionSpec.Time.FastMoveMax)
        assertTrue(standard / 1000f < MotionSpec.Time.FastMoveMin, "UI default is not a scene move")
        assertTrue(exit / 1000f < MotionSpec.Time.FastMoveMin, "UI exit is not a scene move")
        // Exits run well under their entrances (MotionSpec.Time.ExitDurationScale is 0.45);
        // 140/300 is the UI neighbour of that same rule.
        assertTrue(exit.toFloat() / emphasized <= MotionSpec.Time.ExitDurationScale + 0.05f)
    }

    @Test
    fun toJsonNamesAllThreeCompositesAndBalancesBraces() {
        val json = MotionTokens.toJson()
        assertTrue("standard" in json, json)
        assertTrue("emphasized" in json, json)
        assertTrue("exit" in json, json)
        assertTrue(json.startsWith("{") && json.endsWith("}"), json)
        assertEquals(json.count { it == '{' }, json.count { it == '}' }, json)
        assertEquals(json.count { it == '[' }, json.count { it == ']' }, json)
    }

    @Test
    fun easingPointsStayInTheUnitRange() {
        for (token in listOf(MotionTokens.standard, MotionTokens.emphasized, MotionTokens.exit)) {
            val e = token.easing
            for (p in listOf(e.x1, e.y1, e.x2, e.y2)) {
                assertTrue(p in 0f..1f, "cubic-bezier point $p")
            }
        }
        // The standard token is Easing.Standard's curve — one house "normal", two places.
        assertEquals(
            Easing.Bezier(0.2f, 0f, 0f, 1f),
            Easing.Bezier(
                MotionTokens.standard.easing.x1,
                MotionTokens.standard.easing.y1,
                MotionTokens.standard.easing.x2,
                MotionTokens.standard.easing.y2,
            ),
        )
    }
}
