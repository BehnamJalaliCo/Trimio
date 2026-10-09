package io.trimio.core.model

import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.EditMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditMapTest {

    // 10 s of source; cut 2-3 s and 6-8 s (overlapping input ranges are merged).
    private val map = EditMap(10_000, listOf(TimeRange(6_000, 7_000), TimeRange(2_000, 3_000), TimeRange(6_500, 8_000)))

    @Test
    fun mergesCutsAndComputesKeptSegments() {
        assertEquals(listOf(TimeRange(2_000, 3_000), TimeRange(6_000, 8_000)), map.cuts)
        assertEquals(listOf(TimeRange(0, 2_000), TimeRange(3_000, 6_000), TimeRange(8_000, 10_000)), map.kept)
        assertEquals(7_000, map.outputDurationMs)
        assertEquals(listOf(2_000L, 5_000L), map.joinPointsMs)
    }

    @Test
    fun mapsBothWays() {
        assertEquals(1_000, map.toSource(1_000))
        assertEquals(3_000, map.toSource(2_000))
        assertEquals(8_500, map.toSource(5_500))
        assertEquals(5_500, map.toOutput(8_500))
        assertNull(map.toOutput(2_500))
        assertEquals(7_000, map.toOutput(10_000))
    }

    @Test
    fun wordsAcrossCutsAreClippedOrDropped() {
        assertEquals(TimeRange(1_500, 2_000), map.toOutput(TimeRange(1_500, 2_500)))
        assertEquals(TimeRange(2_000, 2_500), map.toOutput(TimeRange(2_500, 3_500)))
        assertNull(map.toOutput(TimeRange(6_200, 7_800)))
    }

    @Test
    fun noCutsIsIdentity() {
        val identity = EditMap(5_000, emptyList())
        assertEquals(5_000, identity.outputDurationMs)
        assertEquals(1_234, identity.toSource(1_234))
        assertEquals(1_234, identity.toOutput(1_234L))
    }
}
