package com.nanyin.nacos.search.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

class NamespaceInfoTest {
    
    @Test
    fun `test NamespaceInfo creation with all parameters`() {
        val namespaceInfo = NamespaceInfo(
            namespaceId = "test-namespace",
            namespaceName = "Test Namespace",
            namespaceDesc = "Test Description",
            configCount = 10,
            type = 1
        )
        
        assertEquals("test-namespace", namespaceInfo.namespaceId)
        assertEquals("Test Namespace", namespaceInfo.namespaceName)
        assertEquals("Test Description", namespaceInfo.namespaceDesc)
        assertEquals(10, namespaceInfo.configCount)
        assertEquals(1, namespaceInfo.type)
    }
    
    @Test
    fun `test NamespaceInfo creation with default parameters`() {
        val namespaceInfo = NamespaceInfo(
            namespaceId = "test-namespace",
            namespaceName = "Test Namespace"
        )
        
        assertEquals("test-namespace", namespaceInfo.namespaceId)
        assertEquals("Test Namespace", namespaceInfo.namespaceName)
        assertEquals("", namespaceInfo.namespaceDesc)
        assertEquals(0, namespaceInfo.configCount)
        assertEquals(1, namespaceInfo.type)
    }
    
    @Test
    fun `test isPublicNamespace with empty namespaceId`() {
        val namespaceInfo = NamespaceInfo(
            namespaceId = "",
            namespaceName = "Public"
        )
        
        assertTrue(namespaceInfo.isPublicNamespace())
    }
    
    @Test
    fun `test isPublicNamespace with public namespaceId`() {
        val namespaceInfo = NamespaceInfo(
            namespaceId = "public",
            namespaceName = "Public"
        )
        
        assertTrue(namespaceInfo.isPublicNamespace())
    }
    
    @Test
    fun `test isPublicNamespace with custom namespaceId`() {
        val namespaceInfo = NamespaceInfo(
            namespaceId = "custom-namespace",
            namespaceName = "Custom"
        )
        
        assertFalse(namespaceInfo.isPublicNamespace())
    }
    
    @Test
    fun `test getDisplayName for public namespace`() {
        val namespaceInfo = NamespaceInfo(
            namespaceId = "",
            namespaceName = "Public Namespace"
        )
        
        assertEquals("public", namespaceInfo.getDisplayName())
    }
    
    @Test
    fun `test getDisplayName for custom namespace with name`() {
        val namespaceInfo = NamespaceInfo(
            namespaceId = "custom-id",
            namespaceName = "Custom Name"
        )
        
        assertEquals("Custom Name", namespaceInfo.getDisplayName())
    }
    
    @Test
    fun `test getDisplayName for custom namespace without name`() {
        val namespaceInfo = NamespaceInfo(
            namespaceId = "custom-id",
            namespaceName = ""
        )
        
        assertEquals("custom-id", namespaceInfo.getDisplayName())
    }
    
    @Test
    fun `test getFullDescription with description`() {
        val namespaceInfo = NamespaceInfo(
            namespaceId = "test-id",
            namespaceName = "Test Name",
            namespaceDesc = "Test Description"
        )
        
        assertEquals("Test Name - Test Description", namespaceInfo.getFullDescription())
    }
    
    @Test
    fun `test getFullDescription without description`() {
        val namespaceInfo = NamespaceInfo(
            namespaceId = "test-id",
            namespaceName = "Test Name",
            namespaceDesc = ""
        )
        
        assertEquals("Test Name", namespaceInfo.getFullDescription())
    }
    
    @Test
    fun `test createPublicNamespace`() {
        val publicNamespace = NamespaceInfo.createPublicNamespace()
        
        assertEquals("", publicNamespace.namespaceId)
        assertEquals("public", publicNamespace.namespaceName)
        assertEquals("Public namespace", publicNamespace.namespaceDesc)
        assertEquals(0, publicNamespace.type)
        assertTrue(publicNamespace.isPublicNamespace())
    }
    
    @Test
    fun `test fromJsonMap with complete data`() {
        val jsonMap = mapOf(
            "namespace" to "test-namespace",
            "namespaceShowName" to "Test Namespace",
            "namespaceDesc" to "Test Description",
            "configCount" to 15,
            "type" to 1
        )
        
        val namespaceInfo = NamespaceInfo.fromJsonMap(jsonMap)
        
        assertEquals("test-namespace", namespaceInfo.namespaceId)
        assertEquals("Test Namespace", namespaceInfo.namespaceName)
        assertEquals("Test Description", namespaceInfo.namespaceDesc)
        assertEquals(15, namespaceInfo.configCount)
        assertEquals(1, namespaceInfo.type)
    }
    
    @Test
    fun `test fromJsonMap with minimal data`() {
        val jsonMap = mapOf(
            "namespace" to "test-namespace"
        )
        
        val namespaceInfo = NamespaceInfo.fromJsonMap(jsonMap)
        
        assertEquals("test-namespace", namespaceInfo.namespaceId)
        assertEquals("", namespaceInfo.namespaceName)
        assertEquals("", namespaceInfo.namespaceDesc)
        assertEquals(0, namespaceInfo.configCount)
        assertEquals(1, namespaceInfo.type)
    }
    
    @Test
    fun `test fromJsonMap with empty map`() {
        val jsonMap = emptyMap<String, Any?>()
        
        val namespaceInfo = NamespaceInfo.fromJsonMap(jsonMap)
        
        assertEquals("", namespaceInfo.namespaceId)
        assertEquals("", namespaceInfo.namespaceName)
        assertEquals("", namespaceInfo.namespaceDesc)
        assertEquals(0, namespaceInfo.configCount)
        assertEquals(1, namespaceInfo.type)
    }
    
    @Test
    fun `test fromJsonMap with null values`() {
        val jsonMap = mapOf(
            "namespace" to null,
            "namespaceShowName" to null,
            "namespaceDesc" to null,
            "configCount" to null,
            "type" to null
        )
        
        val namespaceInfo = NamespaceInfo.fromJsonMap(jsonMap)
        
        assertEquals("", namespaceInfo.namespaceId)
        assertEquals("", namespaceInfo.namespaceName)
        assertEquals("", namespaceInfo.namespaceDesc)
        assertEquals(0, namespaceInfo.configCount)
        assertEquals(1, namespaceInfo.type)
    }
}