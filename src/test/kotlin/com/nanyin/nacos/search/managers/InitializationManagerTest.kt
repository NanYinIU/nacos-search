package com.nanyin.nacos.search.managers

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.ui.NamespacePanel
import com.nanyin.nacos.search.ui.PaginationPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@TestApplication
class InitializationManagerTest {

    private lateinit var namespaceService: NamespaceService
    private lateinit var nacosApiService: NacosApiService
    private lateinit var nacosSearchService: NacosSearchService
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var initializationManager: InitializationManager

    @BeforeEach
    fun setUp() {
        namespaceService = mock()
        nacosApiService = mock()
        nacosSearchService = mock()
        coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        initializationManager = InitializationManager(namespaceService, nacosApiService, nacosSearchService, coroutineScope)
    }

    @Test
    fun `test initial state is not started`() {
        assertTrue(initializationManager.getCurrentState() is InitializationManager.InitializationState.NotStarted)
        assertFalse(initializationManager.isInitialized())
        assertFalse(initializationManager.hasError())
    }

    @Test
    fun `test initialize updates state to success`() = runBlocking {
        val namespacePanel = mock<NamespacePanel>()
        val paginationPanel = mock<PaginationPanel>()
        val namespace = NamespaceInfo(namespaceId = "test-ns", namespaceName = "Test Namespace")

        whenever(namespaceService.getCurrentNamespace()).thenReturn(namespace)

        var completedState: InitializationManager.InitializationState? = null
        initializationManager.initialize(namespacePanel, paginationPanel) { state ->
            completedState = state
        }

        delay(100)

        assertTrue(initializationManager.isInitialized())
        assertTrue(completedState is InitializationManager.InitializationState.Success)
        verify(paginationPanel).setInitialState()
        verify(namespacePanel).refresh()
    }

    @Test
    fun `test initialize handles missing namespace`() = runBlocking {
        val namespacePanel = mock<NamespacePanel>()
        val paginationPanel = mock<PaginationPanel>()

        whenever(namespaceService.getCurrentNamespace()).thenReturn(null)

        var completedState: InitializationManager.InitializationState? = null
        initializationManager.initialize(namespacePanel, paginationPanel) { state ->
            completedState = state
        }

        delay(100)

        assertTrue(initializationManager.isInitialized())
        assertTrue(completedState is InitializationManager.InitializationState.Success)
    }

    @Test
    fun `test initialize prevents concurrent initialization`() = runBlocking {
        val namespacePanel = mock<NamespacePanel>()
        val paginationPanel = mock<PaginationPanel>()
        val namespace = NamespaceInfo(namespaceId = "test-ns", namespaceName = "Test Namespace")

        whenever(namespaceService.getCurrentNamespace()).thenReturn(namespace)

        var completedCount = 0
        initializationManager.initialize(namespacePanel, paginationPanel) { _ ->
            completedCount++
        }
        initializationManager.initialize(namespacePanel, paginationPanel) { _ ->
            completedCount++
        }

        delay(100)

        assertEquals(1, completedCount)
    }

    @Test
    fun `test reinitializeWithNamespace`() = runBlocking {
        val paginationPanel = mock<PaginationPanel>()
        val namespace = NamespaceInfo(namespaceId = "test-ns", namespaceName = "Test Namespace")

        var completedState: InitializationManager.InitializationState? = null
        initializationManager.reinitializeWithNamespace(namespace, paginationPanel) { state ->
            completedState = state
        }

        delay(100)

        assertTrue(initializationManager.isInitialized())
        assertTrue(completedState is InitializationManager.InitializationState.Success)
        verify(paginationPanel).setInitialState()
    }

    @Test
    fun `test reset returns to not started`() {
        initializationManager.reset()
        assertTrue(initializationManager.getCurrentState() is InitializationManager.InitializationState.NotStarted)
    }

    @Test
    fun `test hasError returns false initially`() {
        assertFalse(initializationManager.hasError())
    }
}
