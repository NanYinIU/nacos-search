package com.nanyin.nacos.search.services.operations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryTimestampsTest {

    @Test
    fun `parses Nacos lastModifiedTime with plus-0000 offset`() {
        val millis = HistoryTimestamps.parseWireTime("2020-12-05T01:48:03.380+0000")
        assertEquals(1607132883380L, millis)
    }

    @Test
    fun `prefers numeric lastModified over string times`() {
        val millis = HistoryTimestamps.resolveMillis(
            lastModified = 1700000000000L,
            lastModifiedTime = "2020-12-05T01:48:03.380+0000"
        )
        assertEquals(1700000000000L, millis)
    }

    @Test
    fun `falls back to lastModifiedTime when lastModified is missing`() {
        val millis = HistoryTimestamps.resolveMillis(
            lastModifiedTime = "2020-12-05T01:48:03.380+0000"
        )
        assertEquals(1607132883380L, millis)
    }

    @Test
    fun `falls back to modifyTime millis from console-style payloads`() {
        val millis = HistoryTimestamps.resolveMillis(modifyTime = 1607132883380L)
        assertEquals(1607132883380L, millis)
    }

    @Test
    fun `zero or missing timestamps stay at epoch zero`() {
        assertEquals(0L, HistoryTimestamps.resolveMillis())
        assertEquals(0L, HistoryTimestamps.resolveMillis(lastModified = 0L))
    }

    @Test
    fun `formats op types from padded Nacos codes`() {
        assertEquals("Update", HistoryTimestamps.formatOpType("U         "))
        assertEquals("Insert", HistoryTimestamps.formatOpType("I"))
        assertEquals("Delete", HistoryTimestamps.formatOpType("D"))
        assertEquals("Publish", HistoryTimestamps.formatOpType("PUBLISH"))
    }

    @Test
    fun `display formatter hides unknown timestamps`() {
        assertEquals("—", HistoryTimestamps.formatForDisplay(0L))
        assertTrue(HistoryTimestamps.formatForDisplay(1607132883380L).isNotBlank())
        assertTrue(!HistoryTimestamps.formatForDisplay(1607132883380L).startsWith("1970"))
    }
}
