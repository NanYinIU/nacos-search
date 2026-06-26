package com.nanyin.nacos.search.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.swing.SwingUtilities

class NamespacePanelTest : BasePlatformTestCase() {

    private lateinit var mockProject: Project
    private lateinit var mockNamespaceService: NamespaceService
    private lateinit var namespacePanel: NamespacePanel

    private val testNamespaces = listOf(
        NamespaceInfo("public", "Public Namespace"),
        NamespaceInfo("dev", "Development"),
        NamespaceInfo("staging", "Staging Environment")
    )

    @Before
    override fun setUp() {
        super.setUp()

        mockProject = mock<Project>()
        mockNamespaceService = mock<NamespaceService>()

        whenever(mockNamespaceService.loadNamespacesAsync()).thenReturn(CompletableDeferred(Result.success(testNamespaces)))
        whenever(mockNamespaceService.getCurrentNamespace()).thenReturn(null)
        doNothing().`when`(mockNamespaceService).setCurrentNamespace(any())
    }

    @After
    override fun tearDown() {
        if (::namespacePanel.isInitialized) {
            namespacePanel.dispose()
        }
        reset(mockNamespaceService, mockProject)
        super.tearDown()
    }

    @Test
    fun testNamespacePanelInitialization() {
        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)

        namespacePanel.getSelectedNamespace()
    }

    @Test
    fun testNamespacePanelLoadsNamespacesOnInitialization() {
        whenever(mockNamespaceService.loadNamespacesAsync()).thenReturn(CompletableDeferred(Result.success(testNamespaces)))

        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForUi()

        verify(mockNamespaceService, timeout(1000)).loadNamespacesAsync()
    }

    @Test
    fun testNamespaceSelection() {
        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        val targetNamespace = testNamespaces[1]
        waitForNamespaceLoad()

        namespacePanel.setSelectedNamespace(targetNamespace)
        waitForUi()

        val selectedNamespace = namespacePanel.getSelectedNamespace()
        assertNotNull(selectedNamespace)
        assertEquals(targetNamespace.namespaceId, selectedNamespace?.namespaceId)
    }

    @Test
    fun testRefreshFunctionality() {
        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForUi()

        namespacePanel.refresh()
        waitForUi()

        verify(mockNamespaceService, timeout(1000).atLeast(2)).loadNamespacesAsync()
    }

    @Test
    fun testErrorHandlingWhenLoadingNamespacesFails() {
        val errorMessage = "Network error"
        whenever(mockNamespaceService.loadNamespacesAsync()).thenReturn(
            CompletableDeferred(Result.failure(RuntimeException(errorMessage)))
        )

        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForUi()

        assertNull(namespacePanel.getSelectedNamespace())
        verify(mockNamespaceService, timeout(1000)).loadNamespacesAsync()
    }

    @Test
    fun testCurrentNamespaceRestoration() {
        val currentNamespace = testNamespaces[1]
        whenever(mockNamespaceService.getCurrentNamespace()).thenReturn(currentNamespace)

        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForNamespaceLoad()

        val selectedNamespace = namespacePanel.getSelectedNamespace()
        assertNotNull(selectedNamespace)
        assertEquals(currentNamespace.namespaceId, selectedNamespace?.namespaceId)
    }

    @Test
    fun testDisposeCleansUpResources() {
        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)

        namespacePanel.dispose()
    }

    @Test
    fun testNamespacePanelBasicFunctionality() {
        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)

        val selectedNamespace = namespacePanel.getSelectedNamespace()

        namespacePanel.refresh()
        assertNull(selectedNamespace)
    }

    private fun waitForUi() {
        if (ApplicationManager.getApplication().isDispatchThread) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } else {
            SwingUtilities.invokeAndWait {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
        }
    }

    private fun waitForNamespaceLoad() {
        waitForUi()
        verify(mockNamespaceService, timeout(1000)).loadNamespacesAsync()
        repeat(5) {
            waitForUi()
        }
    }
}
