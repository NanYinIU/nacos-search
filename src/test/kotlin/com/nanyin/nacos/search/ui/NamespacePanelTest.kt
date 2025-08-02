package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.mockito.kotlin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull

class NamespacePanelTest : BasePlatformTestCase() {
    
    private lateinit var mockProject: Project
    private lateinit var mockNamespaceService: NamespaceService
    private lateinit var namespacePanel: NamespacePanel
    
    private val testNamespaces = listOf(
        NamespaceInfo("public", "Public Namespace"),
        NamespaceInfo("dev", "Development"),
        NamespaceInfo("staging", "Staging Environment")
    )
    
    @BeforeEach
    override fun setUp() {
        super.setUp()
        
        mockProject = mock<Project>()
        mockNamespaceService = mock<NamespaceService>()
        
        // Setup default mock behaviors
        val mockDeferred = mock<kotlinx.coroutines.Deferred<Result<List<NamespaceInfo>>>>()
        runBlocking {
            whenever(mockDeferred.await()).thenReturn(Result.success(testNamespaces))
        }
        whenever(mockNamespaceService.loadNamespacesAsync()).thenReturn(mockDeferred)
        whenever(mockNamespaceService.getCurrentNamespace()).thenReturn(null)
        doNothing().`when`(mockNamespaceService).setCurrentNamespace(any())
    }
    
    @AfterEach
    override fun tearDown() {
        if (::namespacePanel.isInitialized) {
            namespacePanel.dispose()
        }
        reset(mockNamespaceService, mockProject)
        super.tearDown()
    }
    
    @Test
    fun `test namespace panel initialization`() {
        // When
        namespacePanel = NamespacePanel(mockProject)
        
        // Then
        assertDoesNotThrow {
            namespacePanel.getSelectedNamespace()
        }
    }
    
    @Test
    fun `test namespace panel loads namespaces on initialization`() {
        // Given
        val mockDeferred = mock<kotlinx.coroutines.Deferred<Result<List<NamespaceInfo>>>>()
        runBlocking {
            whenever(mockDeferred.await()).thenReturn(Result.success(testNamespaces))
        }
        whenever(mockNamespaceService.loadNamespacesAsync()).thenReturn(mockDeferred)
        
        // When
        namespacePanel = NamespacePanel(mockProject)
        
        // Wait for UI updates
        SwingUtilities.invokeAndWait { }
        
        // Then
        verify(mockNamespaceService).loadNamespacesAsync()
    }
    
    @Test
    fun `test namespace selection`() {
        // Given
        namespacePanel = NamespacePanel(mockProject)
        val targetNamespace = testNamespaces[1]
        
        // When
        namespacePanel.setSelectedNamespace(targetNamespace)
        
        // Wait for UI updates
        SwingUtilities.invokeAndWait { }
        
        // Then
        val selectedNamespace = namespacePanel.getSelectedNamespace()
        assertEquals(targetNamespace.namespaceId, selectedNamespace?.namespaceId)
    }
    
    @Test
    fun `test refresh functionality`() {
        // Given
        namespacePanel = NamespacePanel(mockProject)
        
        // When
        namespacePanel.refresh()
        
        // Wait for UI updates
        SwingUtilities.invokeAndWait { }
        
        // Then
        verify(mockNamespaceService, atLeast(2)).loadNamespacesAsync()
    }
    
    @Test
    fun `test error handling when loading namespaces fails`() {
        // Given
        val errorMessage = "Network error"
        val mockDeferred = mock<kotlinx.coroutines.Deferred<Result<List<NamespaceInfo>>>>()
        runBlocking {
            whenever(mockDeferred.await()).thenReturn(Result.failure(RuntimeException(errorMessage)))
        }
        whenever(mockNamespaceService.loadNamespacesAsync()).thenReturn(mockDeferred)
        
        // When
        namespacePanel = NamespacePanel(mockProject)
        
        // Wait for UI updates
        SwingUtilities.invokeAndWait { }
        
        // Then
        assertNull(namespacePanel.getSelectedNamespace())
        verify(mockNamespaceService).loadNamespacesAsync()
    }
    
    @Test
    fun `test current namespace restoration`() {
        // Given
        val currentNamespace = testNamespaces[1]
        whenever(mockNamespaceService.getCurrentNamespace()).thenReturn(currentNamespace)
        
        // When
        namespacePanel = NamespacePanel(mockProject)
        
        // Wait for UI updates
        SwingUtilities.invokeAndWait { }
        
        // Then
        val selectedNamespace = namespacePanel.getSelectedNamespace()
        assertEquals(currentNamespace.namespaceId, selectedNamespace?.namespaceId)
    }
    
    @Test
    fun `test dispose cleans up resources`() {
        // Given
        namespacePanel = NamespacePanel(mockProject)
        
        // When
        assertDoesNotThrow {
            namespacePanel.dispose()
        }
        
        // Then - no exceptions should be thrown
    }
    
    @Test
    fun `test namespace panel basic functionality`() {
        // Given
        namespacePanel = NamespacePanel(mockProject)
        
        // When
        val selectedNamespace = namespacePanel.getSelectedNamespace()
        
        // Then - should not throw exception
        assertDoesNotThrow {
            namespacePanel.refresh()
        }
    }
}