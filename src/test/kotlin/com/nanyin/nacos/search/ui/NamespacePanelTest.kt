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
import com.nanyin.nacos.search.settings.NacosProjectSession
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
import org.mockito.kotlin.anyOrNull
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
        // Selection is applied synchronously before refreshAndWait returns so
        // environment-switch restart can open the Namespace without waiting
        // for the deferred Swing paint.
        assertEquals(testNamespaces, result.getOrNull())
        assertNotNull(namespacePanel.getSelectedNamespace())
        waitForUi()
        verify(mockNamespaceService, timeout(1000).atLeast(2)).loadNamespacesAsync()
    }

    @Test
    fun `refreshAndWait prefers the project session Namespace over a stale panel selection`() = runBlocking {
        val session = NacosProjectSession()
        session.select("qa", "staging")
        whenever(mockProject.getService(NacosProjectSession::class.java)).thenReturn(session)
        whenever(mockNamespaceService.loadNamespacesAsync(anyOrNull())).thenReturn(
            CompletableDeferred(Result.success(testNamespaces))
        )

        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForNamespaceLoad()
        assertEquals("staging", namespacePanel.getSelectedNamespace()?.namespaceId)

        // Env switch adopts the new environment's suggested Namespace first.
        session.adoptEnvironment("prod", "dev")
        val result = namespacePanel.refreshAndWait()

        assertTrue(result.isSuccess)
        assertEquals("dev", namespacePanel.getSelectedNamespace()?.namespaceId)
    }

    @Test
    fun `refreshAndWait resolves a stored display name to the Namespace with a real id`() = runBlocking {
        val qaNamespaces = listOf(
            NamespaceInfo.createPublicNamespace(),
            NamespaceInfo(
                namespaceId = "427adcc2-dbf8-4086-8433-8be647a86b62",
                namespaceName = "uxinlive",
                configCount = 224
            ),
            NamespaceInfo(
                namespaceId = "c7cd86df-aaaa-bbbb-cccc-ddddeeeeffff",
                namespaceName = "lalala"
            )
        )
        val session = NacosProjectSession()
        // Settings "Namespace" field often stores the display name, not the UUID.
        session.select("qa", "uxinlive")
        whenever(mockProject.getService(NacosProjectSession::class.java)).thenReturn(session)
        whenever(mockNamespaceService.loadNamespacesAsync(anyOrNull())).thenReturn(
            CompletableDeferred(Result.success(qaNamespaces))
        )

        namespacePanel = NamespacePanel(mockProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        waitForNamespaceLoad()

        val selected = namespacePanel.getSelectedNamespace()
        assertEquals("427adcc2-dbf8-4086-8433-8be647a86b62", selected?.namespaceId)
        assertEquals("uxinlive", selected?.namespaceName)
        assertEquals("427adcc2-dbf8-4086-8433-8be647a86b62", session.sessionState.namespaceId)
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
    fun `two project panels keep different selections while discovery refreshes their options`() = runBlocking {
        val firstProject = mock<Project>()
        val secondProject = mock<Project>()
        val firstSession = NacosProjectSession().apply { select("shared", "dev") }
        val secondSession = NacosProjectSession().apply { select("shared", "staging") }
        whenever(firstProject.getService(EditSessionService::class.java)).thenReturn(standInEditSessions())
        whenever(secondProject.getService(EditSessionService::class.java)).thenReturn(standInEditSessions())
        whenever(firstProject.getService(NacosProjectSession::class.java)).thenReturn(firstSession)
        whenever(secondProject.getService(NacosProjectSession::class.java)).thenReturn(secondSession)
        whenever(mockNamespaceService.loadNamespacesAsync(anyOrNull())).thenReturn(
            CompletableDeferred(Result.success(testNamespaces))
        )

        val firstPanel = NamespacePanel(firstProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        val secondPanel = NamespacePanel(secondProject, mockNamespaceService, dispatcher = Dispatchers.Unconfined)
        try {
            firstPanel.refreshAndWait()
            secondPanel.refreshAndWait()
            waitForUi()

            assertEquals("dev", firstPanel.getSelectedNamespace()?.namespaceId)
            assertEquals("staging", secondPanel.getSelectedNamespace()?.namespaceId)
            assertEquals("dev", firstSession.sessionState.namespaceId)
            assertEquals("staging", secondSession.sessionState.namespaceId)
        } finally {
            Disposer.dispose(firstPanel)
            Disposer.dispose(secondPanel)
        }
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
