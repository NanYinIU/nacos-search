package com.nanyin.nacos.search.models

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SearchCriteriaTest {

    @Test
    fun `default values match session-owned search defaults`() {
        val criteria = SearchCriteria()

        assertEquals("", criteria.dataId)
        assertEquals("", criteria.group)
    }

    @Test
    fun `custom values are retained`() {
        val criteria = SearchCriteria(
            dataId = "test-data-id",
            group = "test-group"
        )

        assertEquals("test-data-id", criteria.dataId)
        assertEquals("test-group", criteria.group)
    }
}
