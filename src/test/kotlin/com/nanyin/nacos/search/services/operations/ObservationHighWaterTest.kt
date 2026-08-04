package com.nanyin.nacos.search.services.operations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The single observation high-water implementation (ADR-0044). The gateway's
 * per-key gate and the unused identity-scoped stamp registry were merged into
 * this one type: it keys on whatever scope the caller derives from the complete
 * access identity, and it raises its mark only when a mutation is accepted.
 */
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
    fun `accepts a newer sequence and rejects an older or equal one`() {
        val highWater = ObservationHighWater()

        assertTrue(highWater.accepts(listOf("k"), 1))
        highWater.raise("k", 1)
        assertTrue(highWater.accepts(listOf("k"), 5))
        highWater.raise("k", 5)

        assertFalse(highWater.accepts(listOf("k"), 5))
        assertFalse(highWater.accepts(listOf("k"), 3))
        assertTrue(highWater.accepts(listOf("k"), 6))
    }

    @Test
    fun `the mark only moves forward`() {
        val highWater = ObservationHighWater()

        highWater.raise("k", 3)
        highWater.raise("k", 7)
        highWater.raise("k", 2)

        assertEquals(7, highWater.markOf("k"))
    }

    @Test
    fun `distinct scopes do not gate each other`() {
        val highWater = ObservationHighWater()

        highWater.raise("dev", 10)

        assertTrue(highWater.accepts(listOf("prod"), 1))
        assertFalse(highWater.accepts(listOf("dev"), 1))
    }

    @Test
    fun `a scope chain is outranked by its broadest member`() {
        val highWater = ObservationHighWater()

        // A user's clear at sequence 5 raises the global scope; a write that
        // started at 3 must lose even though its own coordinate is untouched.
        highWater.raise("*", 5)

        assertFalse(highWater.accepts(listOf("*", "ns", "coordinate"), 3))
        assertTrue(highWater.accepts(listOf("*", "ns", "coordinate"), 6))
    }

    @Test
    fun `an unseen scope has a zero mark`() {
        assertEquals(0, ObservationHighWater().markOf("never-written"))
    }
}
