package com.nanyin.nacos.search.services.operations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObservationHighWaterTest {

    @Test
    fun `observation sequence is monotonically increasing`() {
        val seq = ObservationSequence()
        val a = seq.next()
        val b = seq.next()
        val c = seq.next()
        assertTrue(a < b)
        assertTrue(b < c)
    }

    @Test
    fun `gate accepts newer sequence and rejects older or equal`() {
        val gate = ObservationGate()

        assertTrue(gate.acceptIfNewer(1))
        assertTrue(gate.acceptIfNewer(5))
        assertFalse(gate.acceptIfNewer(5))
        assertFalse(gate.acceptIfNewer(3))
        assertTrue(gate.acceptIfNewer(6))
    }

    @Test
    fun `late failure does not overwrite newer success`() {
        val gate = ObservationGate()
        val successSeq = 10L
        val lateFailureSeq = 5L

        assertTrue(gate.acceptIfNewer(successSeq))
        assertFalse(gate.acceptIfNewer(lateFailureSeq))
    }

    @Test
    fun `late success does not clear newer permission block`() {
        val gate = ObservationGate()
        val blockSeq = 10L
        val lateSuccessSeq = 5L

        assertTrue(gate.acceptIfNewer(blockSeq))
        assertFalse(gate.acceptIfNewer(lateSuccessSeq))
    }

    @Test
    fun `current high water tracks the last accepted sequence`() {
        val gate = ObservationGate()
        gate.acceptIfNewer(3)
        gate.acceptIfNewer(7)
        gate.acceptIfNewer(2) // rejected

        assertEquals(7, gate.currentHighWater())
    }
}
