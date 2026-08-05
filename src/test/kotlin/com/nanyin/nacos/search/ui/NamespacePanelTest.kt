package com.nanyin.nacos.search.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.operations.EditEnvironment
import com.nanyin.nacos.search.services.operations.EditSessionService
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import com.intellij.util.ui.UIUtil

@TestApplication
class NamespacePanelTest {

    private lateinit var mockProject: Project
    private lateinit var mockNamespaceService: NamespaceService
    private lateinit var namespacePanel: NamespacePanel

    private val testNamespaces = listOf(
        NamespaceInfo("public", "Public Namespace"),
        NamespaceInfo("dev", "Development"),
        NamespaceInfo("staging", "Staging Environment")
    )

    private fun standInEditSessions() = EditSessionService(
        { OperationGateway(emptyMap()) },
        object : EditEnvironment {
            override fun selectedProfile(): EnvironmentProfile? = null
            override fun profile(profileId: String): EnvironmentProfile? = null
            override suspend fun captureTarget(profileId: String, namespaceId: String) =
                Result.failure<OperationTarget>(IllegalStateException("no environment"))
        }
    )

    @BeforeEach
    fun setUp() {
        mockProject = mock<Project>()
        whenever(mockProject.getService(EditSessionService::class.java)).thenReturn(standInEditSessions())
        mockNamespaceService = mock<NamespaceService>()

        whenever(mockNamespaceService.loadNamespacesAsync()).thenReturn(CompletableDeferred(Result.success(testNamespaces)))
        whenever(mockNamespaceService.getCurrentNamespace()).thenReturn(null)
    }

    @AfterEach
    fun tearDown() {
        if (::namespacePanel.isInitialized) {
            // Disposer.dispose, not panel.dispose: the panel anchors a message-bus
            // connection, so it also has to leave the Disposer tree.
            Disposer.dispose(namespacePanel)
        }
        reset(mockNamespaceService, mockProject)
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

        var observed: NamespaceInfo? = null
        namespacePanel.onSelectionChanged = { observed = it }
        namespacePanel.setSelectedNamespace(targetNamespace)
        waitForUi()

        val selectedNamespace = namespacePanel.getSelectedNamespace()
        assertNotNull(selectedNamespace)
        assertEquals(targetNamespace.namespaceId, selectedNamespace?.namespaceId)
        assertEquals(targetNamespace, observed)
    }

    @Test
    fun `manual namespace selection is available when a server only exposes public`() {
        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForNamespaceLoad()

        var observed: NamespaceInfo? = null
        namespacePanel.onSelectionChanged = { observed = it }
        namespacePanel.selectManualNamespace("team-manual")
        waitForUi()

        assertEquals("team-manual", namespacePanel.getSelectedNamespace()?.namespaceId)
        assertEquals("team-manual", observed?.namespaceId)
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
    fun testRefreshAndWaitCompletesAfterNamespacesAreLoaded() = runBlocking {
        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForNamespaceLoad()

        val result = namespacePanel.refreshAndWait()
        waitForUi()

        assertEquals(testNamespaces, result.getOrNull())
        assertNotNull(namespacePanel.getSelectedNamespace())
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

        // Discovery failure keeps public available for manual Namespace ID entry.
        val selected = namespacePanel.getSelectedNamespace()
        assertNotNull(selected)
        assertTrue(selected!!.isPublicNamespace())
        verify(mockNamespaceService, timeout(1000)).loadNamespacesAsync()
    }

    @Test
    fun testProjectPanelDoesNotRestoreGlobalNamespaceState() {
        val currentNamespace = testNamespaces[1]
        whenever(mockNamespaceService.getCurrentNamespace()).thenReturn(currentNamespace)

        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForNamespaceLoad()

        val selectedNamespace = namespacePanel.getSelectedNamespace()
        assertNotNull(selectedNamespace)
        assertEquals("public", selectedNamespace?.namespaceId)
    }

    @Test
    fun testPanelIgnoresExternalNamespaceServiceChange() = runBlocking {
        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForNamespaceLoad()

        val targetNamespace = testNamespaces[2]
        namespacePanel.onNamespaceChanged(testNamespaces[0], targetNamespace)
        waitForUi()

        val selectedNamespace = namespacePanel.getSelectedNamespace()
        assertNotNull(selectedNamespace)
        assertEquals("public", selectedNamespace?.namespaceId)
    }

    @Test
    fun testDisposeCleansUpResources() {
        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)

        Disposer.dispose(namespacePanel)
    }

    @Test
    fun testNamespacePanelBasicFunctionality() {
        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForNamespaceLoad()

        assertNotNull(namespacePanel.getSelectedNamespace())
        namespacePanel.refresh()
        waitForUi()

        assertNotNull(namespacePanel.getSelectedNamespace())
        verify(mockNamespaceService, timeout(1000).atLeast(2)).loadNamespacesAsync()
    }

    private fun waitForUi() {
        UIUtil.invokeAndWaitIfNeeded {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
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
