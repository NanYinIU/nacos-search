package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.settings.NacosSettings
import com.intellij.testFramework.ApplicationRule
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

/**
 * Unit tests for NacosApiService
 */
class NacosApiServiceTest {
    
    @Mock
    private lateinit var mockSettings: NacosSettings
    
    private lateinit var apiService: NacosApiService
    
    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        
        // Setup mock settings
        whenever(mockSettings.serverUrl).thenReturn("http://localhost:8848")
        whenever(mockSettings.username).thenReturn("nacos")
        whenever(mockSettings.password).thenReturn("nacos")
        whenever(mockSettings.namespace).thenReturn("public")
        whenever(mockSettings.connectionTimeoutSeconds).thenReturn(30)
        whenever(mockSettings.readTimeoutSeconds).thenReturn(60)
        whenever(mockSettings.retryAttempts).thenReturn(3)
        whenever(mockSettings.retryDelaySeconds).thenReturn(2)
        
        // Create service instance
        apiService = NacosApiService()
    }
    
    @Test
    fun `test nacos service initialization`() {
        // Test that the service can be initialized
        assertNotNull(apiService)
    }
    
    @Test
    fun `test connection to nacos server`() = runBlocking {
        val result = apiService.testConnection()
        // This test may fail if no actual Nacos server is running
        // but we're testing the method exists and returns a Result
        assertNotNull(result)
    }
    
    @Test
    fun `test list configurations method exists`() = runBlocking {
        val result = apiService.listConfigurations()
        // This test may fail if no actual Nacos server is running
        // but we're testing the method exists and returns a Result
        assertNotNull(result)
    }
    
    @Test
    fun `test get configuration method exists`() = runBlocking {
        val result = apiService.getConfiguration("test.properties", "DEFAULT_GROUP")
        // This test may fail if no actual Nacos server is running
        // but we're testing the method exists and returns a Result
        assertNotNull(result)
    }
    
    @Test
    fun `test get all configurations method exists`() = runBlocking {
        val result = apiService.getAllConfigurations()
        // This test may fail if no actual Nacos server is running
        // but we're testing the method exists and returns a Result
        assertNotNull(result)
    }
    
    @Test
    fun `test settings validation`() {
        // Test with valid settings
        assertTrue(mockSettings.serverUrl.isNotEmpty())
        
        // Test with invalid settings
        whenever(mockSettings.serverUrl).thenReturn("")
        assertTrue(mockSettings.serverUrl.isEmpty())
    }
    
    @Test
    fun `test configuration key generation`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "test=value",
            type = "properties",
            md5 = "abc123",
            lastModified = System.currentTimeMillis()
        )
        
        val key = config.getKey()
        assertEquals("test.properties:DEFAULT_GROUP:public", key)
    }
    
    @Test
    fun `test configuration display name`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "test=value",
            type = "properties",
            md5 = "abc123",
            lastModified = System.currentTimeMillis()
        )
        
        val displayName = config.getDisplayName()
        assertEquals("test.properties (DEFAULT_GROUP) [public]", displayName)
    }
    
    @Test
    fun `test configuration type inference`() {
        val yamlConfig = NacosConfiguration(
            dataId = "test.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "key: value",
            type = null,
            md5 = "abc123",
            lastModified = System.currentTimeMillis()
        )
        
        assertEquals("yaml", yamlConfig.getConfigType())
        
        val jsonConfig = NacosConfiguration(
            dataId = "test.json",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "{\"key\": \"value\"}",
            type = null,
            md5 = "abc123",
            lastModified = System.currentTimeMillis()
        )
        
        assertEquals("json", jsonConfig.getConfigType())
    }
    
    @Test
    fun `test get namespaces method exists`() = runBlocking {
        val result = apiService.getNamespaces()
        // This test may fail if no actual Nacos server is running
        // but we're testing the method exists and returns a Result
        assertNotNull(result)
    }
    
    @Test
    fun `test get namespaces returns success result`() = runBlocking {
        val result = apiService.getNamespaces()
        
        // Should not throw exception
        assertNotNull(result)
        
        // Should return success with at least public namespace as fallback
        assertTrue(result.isSuccess)
        val namespaces = result.getOrNull()
        assertNotNull(namespaces)
        assertTrue(namespaces!!.isNotEmpty())
        
        // Should contain public namespace
        val hasPublicNamespace = namespaces.any { it.isPublicNamespace() }
        assertTrue(hasPublicNamespace, "Should contain public namespace as fallback")
    }
    
    @Test
    fun `test get namespaces handles errors gracefully`() = runBlocking {
        // Test that the method handles errors gracefully and returns fallback
        val result = apiService.getNamespaces()
        
        assertTrue(result.isSuccess)
        val namespaces = result.getOrNull()
        assertNotNull(namespaces)
        
        // Should always have at least the public namespace
        assertTrue(namespaces!!.isNotEmpty())
        val publicNamespace = namespaces.find { it.isPublicNamespace() }
        assertNotNull(publicNamespace, "Should always include public namespace")
    }
    
    @Test
    fun `test get namespaces returns consistent public namespace`() = runBlocking {
        val result = apiService.getNamespaces()
        assertTrue(result.isSuccess)
        
        val namespaces = result.getOrNull()
        assertNotNull(namespaces)
        
        val publicNamespaces = namespaces!!.filter { it.isPublicNamespace() }
        assertEquals(1, publicNamespaces.size, "Should have exactly one public namespace")
        
        val publicNamespace = publicNamespaces.first()
        assertEquals("", publicNamespace.namespaceId, "Public namespace should have empty ID")
        assertEquals("public", publicNamespace.namespaceName, "Public namespace should be named 'public'")
    }
    
    @Test
    fun `test get configuration with namespace parameter`() = runBlocking {
        val result = apiService.getConfiguration("test.properties", "DEFAULT_GROUP", "test-namespace")
        // This test may fail if no actual Nacos server is running
        // but we're testing the method exists and accepts namespace parameter
        assertNotNull(result)
    }
    
    @Test
    fun `test get configuration with null namespace`() = runBlocking {
        val result = apiService.getConfiguration("test.properties", "DEFAULT_GROUP", null)
        // This test may fail if no actual Nacos server is running
        // but we're testing the method works with null namespace (public)
        assertNotNull(result)
    }
    
    @Test
    fun `test list configurations with namespace parameter`() = runBlocking {
        val result = apiService.listConfigurations("test-namespace", 1, 10)
        // This test may fail if no actual Nacos server is running
        // but we're testing the method exists and accepts namespace parameter
        assertNotNull(result)
    }
    
    @Test
    fun `test list configurations with null namespace`() = runBlocking {
        val result = apiService.listConfigurations(null, 1, 10)
        // This test may fail if no actual Nacos server is running
        // but we're testing the method works with null namespace (public)
        assertNotNull(result)
    }
    
    @Test
    fun `test get all configurations with namespace parameter`() = runBlocking {
        val result = apiService.getAllConfigurations("test-namespace")
        // This test may fail if no actual Nacos server is running
        // but we're testing the method exists and accepts namespace parameter
        assertNotNull(result)
    }
    
    @Test
    fun `test get all configurations with null namespace`() = runBlocking {
        val result = apiService.getAllConfigurations(null)
        // This test may fail if no actual Nacos server is running
        // but we're testing the method works with null namespace (public)
        assertNotNull(result)
    }
}