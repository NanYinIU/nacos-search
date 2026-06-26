package com.nanyin.nacos.search.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NacosConfigurationTest {

    @Test
    fun `test NacosConfiguration creation with all parameters`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "key=value",
            type = "properties",
            md5 = "abc123",
            lastModified = 1234567890L
        )

        assertEquals("test.properties", config.dataId)
        assertEquals("DEFAULT_GROUP", config.group)
        assertEquals("public", config.tenantId)
        assertEquals("key=value", config.content)
        assertEquals("properties", config.type)
        assertEquals("abc123", config.md5)
        assertEquals(1234567890L, config.lastModified)
    }

    @Test
    fun `test NacosConfiguration creation with default parameters`() {
        val config = NacosConfiguration(
            dataId = "test.txt",
            group = "DEFAULT_GROUP",
            content = "hello"
        )

        assertNull(config.tenantId)
        assertNull(config.type)
        assertNull(config.md5)
        assertTrue(config.lastModified > 0)
    }

    @Test
    fun `test getKey with tenant`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "key=value"
        )

        assertEquals("test.properties:DEFAULT_GROUP:public", config.getKey())
    }

    @Test
    fun `test getKey without tenant`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            content = "key=value"
        )

        assertEquals("test.properties:DEFAULT_GROUP:", config.getKey())
    }

    @Test
    fun `test matches with blank query`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            content = "key=value"
        )

        assertTrue(config.matches(""))
        assertTrue(config.matches("   "))
    }

    @Test
    fun `test matches dataId`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            content = "key=value"
        )

        assertTrue(config.matches("test"))
        assertTrue(config.matches("TEST"))
        assertFalse(config.matches("missing"))
    }

    @Test
    fun `test matches group`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            content = "key=value"
        )

        assertTrue(config.matches("group"))
        assertFalse(config.matches("other"))
    }

    @Test
    fun `test matches content`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            content = "key=value"
        )

        assertTrue(config.matches("value"))
        assertFalse(config.matches("missing"))
    }

    @Test
    fun `test matches tenant`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "key=value"
        )

        assertTrue(config.matches("public"))
        assertFalse(config.matches("private"))
    }

    @Test
    fun `test matches case sensitive`() {
        val config = NacosConfiguration(
            dataId = "Test.Properties",
            group = "DEFAULT_GROUP",
            content = "key=value"
        )

        assertTrue(config.matches("test", ignoreCase = true))
        assertFalse(config.matches("test", ignoreCase = false))
    }

    @Test
    fun `test getDisplayName with tenant`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "key=value"
        )

        assertEquals("test.properties (DEFAULT_GROUP) [public]", config.getDisplayName())
    }

    @Test
    fun `test getDisplayName without tenant`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            content = "key=value"
        )

        assertEquals("test.properties (DEFAULT_GROUP)", config.getDisplayName())
    }

    @Test
    fun `test getConfigType explicit type`() {
        val config = NacosConfiguration(
            dataId = "test.txt",
            group = "DEFAULT_GROUP",
            content = "hello",
            type = "custom"
        )

        assertEquals("custom", config.getConfigType())
    }

    @Test
    fun `test getConfigType inferred properties`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            content = "key=value"
        )

        assertEquals("properties", config.getConfigType())
    }

    @Test
    fun `test getConfigType inferred yaml`() {
        val configYml = NacosConfiguration(
            dataId = "test.yml",
            group = "DEFAULT_GROUP",
            content = "key: value"
        )
        val configYaml = NacosConfiguration(
            dataId = "test.yaml",
            group = "DEFAULT_GROUP",
            content = "key: value"
        )

        assertEquals("yaml", configYml.getConfigType())
        assertEquals("yaml", configYaml.getConfigType())
    }

    @Test
    fun `test getConfigType inferred json`() {
        val config = NacosConfiguration(
            dataId = "test.json",
            group = "DEFAULT_GROUP",
            content = "{}"
        )

        assertEquals("json", config.getConfigType())
    }

    @Test
    fun `test getConfigType inferred xml`() {
        val config = NacosConfiguration(
            dataId = "test.xml",
            group = "DEFAULT_GROUP",
            content = "<root/>"
        )

        assertEquals("xml", config.getConfigType())
    }

    @Test
    fun `test getConfigType inferred text`() {
        val config = NacosConfiguration(
            dataId = "test.unknown",
            group = "DEFAULT_GROUP",
            content = "hello"
        )

        assertEquals("text", config.getConfigType())
    }
}
