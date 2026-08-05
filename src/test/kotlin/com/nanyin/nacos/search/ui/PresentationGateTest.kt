package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.operations.Observed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one judgement of whether an asynchronous result may still update what a
 * view presents (ADR-0047). These tests drive it directly: no platform fixture,
 * no event-dispatch pumping, no reflection, no geometry.
 */
class PresentationGateTest {

    private val app = PresentedCoordinate("dev", "app.properties", "DEFAULT_GROUP")
    private val other = PresentedCoordinate("dev", "other.properties", "DEFAULT_GROUP")

    @Test
    fun `admits a result whose epoch and coordinate match the current selection`() {
        val gate = PresentationGate({ 3L }, { app })

        assertTrue(gate.admitAndRecord(PresentedResult(3L, app, observation = 7L)))
    }

    @Test
    fun `discards a result whose session epoch has been superseded`() {
        var epoch = 3L
        val gate = PresentationGate({ epoch }, { app })

        epoch = 4L

        assertFalse(gate.admitAndRecord(PresentedResult(3L, app, observation = 7L)))
    }

    @Test
    fun `discards a result for a coordinate other than the currently selected one`() {
        val gate = PresentationGate({ 0L }, { app })

        assertFalse(gate.admitAndRecord(PresentedResult(0L, other, observation = 7L)))
    }

    @Test
    fun `discards a configuration result while nothing is selected`() {
        val gate = PresentationGate({ 0L }, { null })

        assertFalse(gate.admitAndRecord(PresentedResult(0L, app, observation = 7L)))
    }

    @Test
    fun `admits a result with no coordinate when the view presents no single configuration`() {
        val gate = PresentationGate({ 0L })

        assertTrue(gate.admitAndRecord(PresentedResult(0L, coordinate = null, observation = 7L)))
    }

    @Test
    fun `discards a result whose observation sequence is older than the last painted one`() {
        val gate = PresentationGate({ 0L }, { app })

        assertTrue(gate.admitAndRecord(PresentedResult(0L, app, observation = 9L)))
        assertFalse(gate.admitAndRecord(PresentedResult(0L, app, observation = 5L)))
    }

    @Test
    fun `two refreshes in quick succession converge on the later one`() {
        val gate = PresentationGate({ 0L }, { app })
        val first = PresentedResult(0L, app, observation = 11L)
        val second = PresentedResult(0L, app, observation = 12L)

        // The later-started read returns first and paints; the earlier one lands
        // afterwards and must not flicker the view back to what it read.
        assertTrue(gate.admitAndRecord(second))
        assertFalse(gate.admitAndRecord(first))
    }

    @Test
    fun `re-asking with the same observation still admits, so one paint can checkpoint twice`() {
        val gate = PresentationGate({ 0L }, { app })
        val result = PresentedResult(0L, app, observation = 4L)

        assertTrue(gate.admitAndRecord(result))
        assertTrue(gate.admitAndRecord(result))
    }

    @Test
    fun `a result served from cache carries no observation and still paints`() {
        val gate = PresentationGate({ 0L }, { app })

        assertTrue(gate.admitAndRecord(PresentedResult(0L, app, observation = 30L)))

        // The sequence rule orders remote observations against each other; it is
        // not a freshness gate, so a cache hit is not ranked against them.
        assertTrue(gate.admitAndRecord(PresentedResult(0L, app, Observed.NO_OBSERVATION)))
    }

    @Test
    fun `a cache-served paint does not raise the mark`() {
        val gate = PresentationGate({ 0L }, { app })

        assertTrue(gate.admitAndRecord(PresentedResult(0L, app, Observed.NO_OBSERVATION)))
        assertTrue(gate.admitAndRecord(PresentedResult(0L, app, observation = 1L)))
    }

    @Test
    fun `a discarded result leaves the mark alone`() {
        var epoch = 0L
        var selected: PresentedCoordinate? = app
        val gate = PresentationGate({ epoch }, { selected })

        // Rejected on the epoch rule…
        epoch = 1L
        assertFalse(gate.admitAndRecord(PresentedResult(0L, app, observation = 40L)))
        epoch = 0L

        // …and on the coordinate rule. Neither may lock out a genuine result.
        selected = other
        assertFalse(
            gate.admitAndRecord(PresentedResult(0L, other.copy(dataId = "third"), observation = 41L))
        )
        selected = app

        assertTrue(gate.admitAndRecord(PresentedResult(0L, app, observation = 2L)))
    }

    @Test
    fun `selecting another coordinate starts that coordinate at no mark`() {
        var selected = app
        val gate = PresentationGate({ 0L }, { selected })

        assertTrue(gate.admitAndRecord(PresentedResult(0L, app, observation = 20L)))

        // A read for the newly selected coordinate that started earlier is not
        // ranked against the previous coordinate's paint.
        selected = other
        assertTrue(gate.admitAndRecord(PresentedResult(0L, other, observation = 8L)))
    }

    @Test
    fun `one gate governs one presentation slot`() {
        // Two regions painted from separate reads hold separate gates, so a read
        // of one region never outranks an earlier read of the other.
        val listGate = PresentationGate({ 0L }, { app })
        val diffGate = PresentationGate({ 0L }, { app })

        assertTrue(diffGate.admitAndRecord(PresentedResult(0L, app, observation = 50L)))
        assertTrue(listGate.admitAndRecord(PresentedResult(0L, app, observation = 49L)))
    }

    @Test
    fun `a coordinate is derived from a configuration the same way each time`() {
        val configuration = NacosConfiguration("app.properties", "DEFAULT_GROUP", "dev", "", "properties")

        assertEquals(app, PresentedCoordinate.of(configuration))
        assertEquals(app, PresentedCoordinate.of("dev", "app.properties", "DEFAULT_GROUP"))
    }

    @Test
    fun `the public namespace has one canonical coordinate`() {
        val blank = PresentedCoordinate.of("", "app.properties", "DEFAULT_GROUP")

        assertEquals(blank, PresentedCoordinate.of(null, "app.properties", "DEFAULT_GROUP"))
        assertEquals(blank, PresentedCoordinate.of("public", "app.properties", "DEFAULT_GROUP"))
        assertEquals(
            blank,
            PresentedCoordinate.of(NacosConfiguration("app.properties", "DEFAULT_GROUP", null, ""))
        )
    }
}
