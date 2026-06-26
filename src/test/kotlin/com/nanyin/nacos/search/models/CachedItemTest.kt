package com.nanyin.nacos.search.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CachedItemTest {

    @Test
    fun `test CachedItem creation`() {
        val item = CachedItem(
            data = "test data",
            timestamp = System.currentTimeMillis(),
            ttl = 300_000L
        )

        assertEquals("test data", item.data)
        assertTrue(item.ttl > 0)
    }

    @Test
    fun `test CachedItem not expired`() {
        val item = CachedItem(
            data = "test data",
            timestamp = System.currentTimeMillis(),
            ttl = 300_000L
        )

        assertFalse(item.isExpired())
    }

    @Test
    fun `test CachedItem expired`() {
        val item = CachedItem(
            data = "test data",
            timestamp = System.currentTimeMillis() - 400_000L,
            ttl = 300_000L
        )

        assertTrue(item.isExpired())
    }

    @Test
    fun `test CachedItem default ttl`() {
        val item = CachedItem(
            data = "test data",
            timestamp = System.currentTimeMillis()
        )

        assertEquals(300_000L, item.ttl)
        assertFalse(item.isExpired())
    }

    @Test
    fun `test getAge`() {
        val timestamp = System.currentTimeMillis() - 1000L
        val item = CachedItem(
            data = "test data",
            timestamp = timestamp,
            ttl = 300_000L
        )

        val age = item.getAge()
        assertTrue(age >= 1000L)
        assertTrue(age < 2000L)
    }

    @Test
    fun `test CachedItem with custom data type`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            content = "key=value"
        )
        val item = CachedItem(
            data = config,
            timestamp = System.currentTimeMillis(),
            ttl = 300_000L
        )

        assertEquals(config, item.data)
        assertFalse(item.isExpired())
    }
}
