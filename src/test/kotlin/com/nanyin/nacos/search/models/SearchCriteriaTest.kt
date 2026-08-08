package com.nanyin.nacos.search.models

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SearchCriteriaTest {

    @Test
    fun `default values match session-owned search defaults`() {
        val criteria = SearchCriteria()

        assertEquals("", criteria.query)
        assertEquals("", criteria.group)
        assertEquals("", criteria.dataId)
        assertFalse(criteria.useRegex)
        assertFalse(criteria.caseSensitive)
        assertTrue(criteria.searchContent)
    }

    @Test
    fun `custom values are retained`() {
        val criteria = SearchCriteria(
            query = "test query",
            group = "test-group",
            dataId = "test-data-id",
            useRegex = true,
            caseSensitive = true,
            searchContent = false
        )

        assertEquals("test query", criteria.query)
        assertEquals("test-group", criteria.group)
        assertEquals("test-data-id", criteria.dataId)
        assertTrue(criteria.useRegex)
        assertTrue(criteria.caseSensitive)
        assertFalse(criteria.searchContent)
    }
}
