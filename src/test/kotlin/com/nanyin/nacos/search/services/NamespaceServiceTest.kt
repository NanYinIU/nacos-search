package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.listeners.NamespaceChangeListener
import com.nanyin.nacos.search.services.NamespaceServiceState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

class NamespaceServiceTest {
    
    private lateinit var namespaceService: NamespaceService
    private lateinit var mockNacosApiService: NacosApiService
    
    @BeforeEach
    fun setUp() {
        mockNacosApiService = mock(NacosApiService::class.java)
        namespaceService = NamespaceService(mockNacosApiService)
    }
    
    @Test
    fun `getCurrentNamespace should return null initially`() {
        assertNull(namespaceService.getCurrentNamespace())
    }
    
    @Test
    fun `setCurrentNamespace should update current namespace`() {
        val namespace = NamespaceInfo(
            namespaceId = "test-ns",
            namespaceName = "Test Namespace",
            namespaceDesc = "Test Description",
            configCount = 10,
            type = 0
        )
        
        namespaceService.setCurrentNamespace(namespace)
        
        assertEquals(namespace, namespaceService.getCurrentNamespace())
    }
    
    @Test
    fun `setCurrentNamespace should accept null value`() {
        val namespace = NamespaceInfo(
            namespaceId = "test-ns",
            namespaceName = "Test Namespace",
            namespaceDesc = "Test Description",
            configCount = 10,
            type = 0
        )
        
        namespaceService.setCurrentNamespace(namespace)
        assertNotNull(namespaceService.getCurrentNamespace())
        
        namespaceService.setCurrentNamespace(null)
        assertNull(namespaceService.getCurrentNamespace())
    }
    
    @Test
    fun `getAvailableNamespaces should return empty list initially`() {
        assertTrue(namespaceService.getAvailableNamespaces().isEmpty())
    }
    
    @Test
    fun `addNamespaceChangeListener should add listener successfully`() {
        val listener = object : NamespaceChangeListener {
            override suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
                // Test implementation
            }
        }
        
        // Should not throw exception
        assertDoesNotThrow {
            namespaceService.addNamespaceChangeListener(listener)
        }
    }
    
    @Test
    fun `removeNamespaceChangeListener should remove listener successfully`() {
        val listener = object : NamespaceChangeListener {
            override suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
                // Test implementation
            }
        }
        
        namespaceService.addNamespaceChangeListener(listener)
        
        // Should not throw exception
        assertDoesNotThrow {
            namespaceService.removeNamespaceChangeListener(listener)
        }
    }
    
    @Test
    fun `namespace change should notify listeners`() = runBlocking {
        var notificationReceived = false
        var oldNs: NamespaceInfo? = null
        var newNs: NamespaceInfo? = null
        
        val listener = object : NamespaceChangeListener {
            override suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
                notificationReceived = true
                oldNs = oldNamespace
                newNs = newNamespace
            }
        }
        
        namespaceService.addNamespaceChangeListener(listener)
        
        val namespace = NamespaceInfo(
            namespaceId = "test-ns",
            namespaceName = "Test Namespace",
            namespaceDesc = "Test Description",
            configCount = 10,
            type = 0
        )
        
        namespaceService.setCurrentNamespace(namespace)
        
        // Give some time for async notification
        delay(100)
        
        assertTrue(notificationReceived)
        assertNull(oldNs)
        assertEquals(namespace, newNs)
    }
    
    @Test
    fun `findNamespaceById should return null for null input`() {
        assertNull(namespaceService.findNamespaceById(null))
    }
    
    @Test
    fun `findNamespaceById should return null when namespace not found`() {
        assertNull(namespaceService.findNamespaceById("non-existent"))
    }
    
    @Test
    fun `namespaceExists should return false for null input`() {
        assertFalse(namespaceService.namespaceExists(null))
    }
    
    @Test
    fun `namespaceExists should return false when namespace not found`() {
        assertFalse(namespaceService.namespaceExists("non-existent"))
    }
    
    @Test
    fun `getPublicNamespace should return null when no namespaces available`() {
        assertNull(namespaceService.getPublicNamespace())
    }
    
    @Test
    fun `dispose should clean up resources`() {
        // Given
        val listener = mock(NamespaceChangeListener::class.java)
        namespaceService.addNamespaceChangeListener(listener)
        
        // When
        namespaceService.dispose()
        
        // Then
        assertNull(namespaceService.getCurrentNamespace())
        assertTrue(namespaceService.getAvailableNamespaces().isEmpty())
    }
    
    @Test
    fun `getState should return current state`() {
        // Given
        val namespace = NamespaceInfo(namespaceId = "test", namespaceName = "Test Namespace")
        namespaceService.setCurrentNamespace(namespace)
        
        // When
        val state = namespaceService.getState()
        
        // Then
        assertEquals("test", state.currentNamespaceId)
        assertTrue(state.lastRefreshTime >= 0)
    }
    
    @Test
    fun `loadState should restore state`() {
        // Given
        val savedState = NamespaceServiceState(
            currentNamespaceId = "saved-namespace",
            lastRefreshTime = 12345L
        )
        
        // When
        namespaceService.loadState(savedState)
        
        // Then
        val currentState = namespaceService.getState()
        assertEquals("saved-namespace", currentState.currentNamespaceId)
        assertEquals(12345L, currentState.lastRefreshTime)
    }
    
    @Test
    fun `getLastRefreshTime should return last refresh time`() {
        // Given
        val beforeTime = System.currentTimeMillis()
        
        // When
        runBlocking {
            namespaceService.loadNamespacesAsync().await()
        }
        
        val afterTime = System.currentTimeMillis()
        val refreshTime = namespaceService.getLastRefreshTime()
        
        // Then
        assertTrue(refreshTime >= beforeTime)
        assertTrue(refreshTime <= afterTime)
    }
    
    @Test
    fun `needsRefresh should return true when threshold exceeded`() {
        // Given - service with old refresh time
        val oldState = NamespaceServiceState(
            currentNamespaceId = null,
            lastRefreshTime = System.currentTimeMillis() - 10000 // 10 seconds ago
        )
        namespaceService.loadState(oldState)
        
        // When
        val needsRefresh = namespaceService.needsRefresh(5000) // 5 second threshold
        
        // Then
        assertTrue(needsRefresh)
    }
    
    @Test
    fun `needsRefresh should return false when threshold not exceeded`() {
        // Given - service with recent refresh time
        val recentState = NamespaceServiceState(
            currentNamespaceId = null,
            lastRefreshTime = System.currentTimeMillis() - 1000 // 1 second ago
        )
        namespaceService.loadState(recentState)
        
        // When
        val needsRefresh = namespaceService.needsRefresh(5000) // 5 second threshold
        
        // Then
        assertFalse(needsRefresh)
    }
    
    @Test
    fun `setCurrentNamespace should persist namespace id`() {
        // Given
        val namespace = NamespaceInfo(namespaceId = "persist-test", namespaceName = "Persist Test")
        
        // When
        namespaceService.setCurrentNamespace(namespace)
        
        // Then
        val state = namespaceService.getState()
        assertEquals("persist-test", state.currentNamespaceId)
    }
    
    @Test
    fun `loadNamespacesAsync should restore previously selected namespace`() = runBlocking {
        // Given
        val namespaces = listOf(
            NamespaceInfo(namespaceId = "ns1", namespaceName = "Namespace 1"),
            NamespaceInfo(namespaceId = "ns2", namespaceName = "Namespace 2"),
            NamespaceInfo(namespaceId = "ns3", namespaceName = "Namespace 3")
        )
        whenever(mockNacosApiService.getNamespaces()).thenReturn(Result.success(namespaces))
        
        // Set up saved state with previously selected namespace
        val savedState = NamespaceServiceState(currentNamespaceId = "ns2")
        namespaceService.loadState(savedState)
        
        // When
        val result = namespaceService.loadNamespacesAsync().await()
        
        // Then
        assertTrue(result.isSuccess)
        val currentNamespace = namespaceService.getCurrentNamespace()
        assertNotNull(currentNamespace)
        assertEquals("ns2", currentNamespace?.namespaceId)
        assertEquals("Namespace 2", currentNamespace?.namespaceName)
    }
    
    @Test
    fun `loadNamespacesAsync should fallback to first namespace when saved namespace not found`() = runBlocking {
        // Given
        val namespaces = listOf(
            NamespaceInfo(namespaceId = "ns1", namespaceName = "Namespace 1"),
            NamespaceInfo(namespaceId = "ns2", namespaceName = "Namespace 2")
        )
        whenever(mockNacosApiService.getNamespaces()).thenReturn(Result.success(namespaces))
        
        // Set up saved state with non-existent namespace
        val savedState = NamespaceServiceState(currentNamespaceId = "non-existent")
        namespaceService.loadState(savedState)
        
        // When
        val result = namespaceService.loadNamespacesAsync().await()
        
        // Then
        assertTrue(result.isSuccess)
        val currentNamespace = namespaceService.getCurrentNamespace()
        assertNotNull(currentNamespace)
        assertEquals("ns1", currentNamespace?.namespaceId) // Should fallback to first
    }
    
    @Test
    fun `loadNamespacesAsync should return deferred result`() {
        val deferred = namespaceService.loadNamespacesAsync()
        assertNotNull(deferred)
        assertFalse(deferred.isCompleted)
    }
    
    @Test
    fun `refreshNamespaces should return deferred result`() {
        val deferred = namespaceService.refreshNamespaces()
        assertNotNull(deferred)
        assertFalse(deferred.isCompleted)
    }
}