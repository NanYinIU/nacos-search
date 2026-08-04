package com.nanyin.nacos.search.psi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class NacosDeclaredSourceIndexTest {

    @Test
    fun `marker serialization writes data and round trips`() {
        val externalizer = NacosDeclaredSourceIndex().valueExternalizer
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                externalizer.save(output, DeclaredSourceMarker)
            }
        }.toByteArray()

        assertTrue(bytes.isNotEmpty(), "empty index values are normalized to null by the platform")

        val restored = DataInputStream(ByteArrayInputStream(bytes)).use(externalizer::read)
        assertSame(DeclaredSourceMarker, restored)
    }

    @Test
    fun `index version starts at one for the first full reindex`() {
        assertEquals(1, NacosDeclaredSourceIndex().version)
    }

    @Test
    fun `extracts dataId from NacosPropertySource`() {
        val ids = DeclaredSourceIndexer.extractDeclaredDataIds(
            """@NacosPropertySource(dataId = "common.properties", groupId = "APP_GROUP")"""
        )
        assertTrue(ids.contains("common.properties"))
        assertFalse(ids.contains("APP_GROUP"))
    }

    @Test
    fun `extracts dataId with fully qualified annotation name`() {
        val ids = DeclaredSourceIndexer.extractDeclaredDataIds(
            """@com.alibaba.nacos.api.config.annotation.NacosPropertySource(dataId = "app.yaml")"""
        )
        assertTrue(ids.contains("app.yaml"))
    }

    @Test
    fun `extracts multiple dataIds from NacosPropertySources array`() {
        val ids = DeclaredSourceIndexer.extractDeclaredDataIds(
            """
            @NacosPropertySources({
                @NacosPropertySource(dataId = "a.properties"),
                @NacosPropertySource(dataId = "b.properties", groupId = "G")
            })
            """.trimIndent()
        )
        assertTrue(ids.contains("a.properties"))
        assertTrue(ids.contains("b.properties"))
    }

    @Test
    fun `ignores groupId and namespaceId alone`() {
        val ids = DeclaredSourceIndexer.extractDeclaredDataIds(
            """@NacosPropertySource(groupId = "APP_GROUP", namespaceId = "dev")"""
        )
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `empty content returns empty`() {
        assertTrue(DeclaredSourceIndexer.extractDeclaredDataIds("").isEmpty())
    }
}
