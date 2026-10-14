package io.trimio.engine.llm

import io.trimio.engine.llm.local.AllowedCores
import kotlin.test.Test
import kotlin.test.assertEquals

class AllowedCoresTest {
    @Test
    fun countsTheCoresInTheKernelsList() {
        assertEquals(8, AllowedCores.parse("0-7"))
        assertEquals(6, AllowedCores.parse("0-3,6,7"))
        assertEquals(1, AllowedCores.parse("5"))
    }

    @Test
    fun neverAsksForMoreThreadsThanTheCoresAllowed() {
        val cores = AllowedCores.count() ?: return
        assertEquals(cores, AllowedCores.cap(cores + 4))
        assertEquals(1, AllowedCores.cap(1))
    }
}
