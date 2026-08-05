package com.nanyin.nacos.search.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.models.SearchCriteria
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.operations.Observed
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The tool window's search orchestration, driven without the tool window.
 *
 * Everything here goes through the controller's public surface and the real
 * [NacosSearchService] over a stubbed API — no platform fixture, no event queue,
 * no reflection, and nothing about layout. What is asserted is what the issue
 * asks for: the service is given the session context, the window expresses
 * intent without naming a target, and no intent reaches the credential store.
 */
@TestApplication
class ToolWindowSearchControllerTest {

    private lateinit var settings: NacosSettings

    @BeforeEach
    fun resetSharedSettingsToAnonymousDefaults() {
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java).apply {
            resetToDefaults()
            authMode = AuthMode.ANONYMOUS
            getActiveServer().authMode = AuthMode.ANONYMOUS
        }
    }

    @Test
    fun `opening a namespace gives the service the environment to search under`() = runBlocking {
        val api = RecordingApi()
        val service = NacosSearchService(apiProvider = { api.service })
        val captures = mutableListOf<String>()
        val controller = controllerFor(service, captures)

        controller.openNamespace(namespace("ns-a"))

        val session = service.sessionContext()
        assertEquals(settings.activeServerId, session.profileId)
        assertEquals("ns-a", session.namespaceId)
        assertEquals(listOf(settings.activeServerId), captures)
    }

    @Test
    fun `intent names no target - the search runs against the held session`() = runBlocking {
        val api = RecordingApi()
        val service = NacosSearchService(apiProvider = { api.service })
        val controller = controllerFor(service)
        controller.openNamespace(namespace("ns-a"))

        controller.search(SearchCriteria(dataId = "app.yaml", query = "app", searchContent = false))

        val call = api.calls.last()
        assertEquals("ns-a", call.namespaceId)
        assertEquals("app.yaml", call.dataId)
        assertSame(service.sessionContext().operationContext, call.operationContext)
    }

    @Test
    fun `expressing intent reads no credential`() = runBlocking {
        // The capture is the only path to the credential store, and the window
        // runs it off the event dispatch thread. An intent that captured again
        // would be doing it from a Swing handler (ADR-0039).
        val api = RecordingApi()
        val service = NacosSearchService(apiProvider = { api.service })
        val captures = mutableListOf<String>()
        val controller = controllerFor(service, captures)
        controller.openNamespace(namespace("ns-a"))
        val afterAdoption = captures.size

        controller.search(SearchCriteria(dataId = "app.yaml", searchContent = false))
        controller.nextPage()
        controller.previousPage()
        controller.changePageSize(50)
        controller.refresh()
        controller.clearCriteria()

        assertEquals(afterAdoption, captures.size)
    }

    @Test
    fun `the page size intent holds for the searches that follow it`() = runBlocking {
        val api = RecordingApi()
        val service = NacosSearchService(apiProvider = { api.service })
        val controller = controllerFor(service)
        controller.openNamespace(namespace("ns-a"))

        controller.changePageSize(50)
        controller.search(SearchCriteria(dataId = "app.yaml", searchContent = false))

        assertEquals(50, service.paginationState.value.pageSize)
        assertEquals(50, api.calls.last().pageSize)
    }

    @Test
    fun `next page asks for the following page of the same search`() = runBlocking {
        val api = RecordingApi(pagesAvailable = 3, totalCount = 30)
        val service = NacosSearchService(apiProvider = { api.service })
        val controller = controllerFor(service)
        controller.openNamespace(namespace("ns-a"))
        controller.search(SearchCriteria(dataId = "app.yaml", searchContent = false))

        controller.nextPage()

        val call = api.calls.last()
        assertEquals(2, call.pageNo)
        assertEquals("app.yaml", call.dataId)
        assertEquals("ns-a", call.namespaceId)
    }

    @Test
    fun `a capture that finishes after the selection moved on is discarded`() = runBlocking {
        val api = RecordingApi()
        val service = NacosSearchService(apiProvider = { api.service })
        var selected = settings.activeServerId
        val controller = ToolWindowSearchController(
            searchService = service,
            selectedProfileId = { selected },
            captureOperationContext = { profileId ->
                // The user switches environment while the capture is running.
                selected = "another-environment"
                capturedContextFor(profileId)
            }
        )

        val adopted = controller.adoptSession(namespace("ns-a"))

        assertEquals(
            ToolWindowSearchController.Adoption.DISCARDED,
            adopted,
            "a capture for the environment the user left must not be adopted"
        )
        assertEquals("", service.sessionContext().profileId)
        assertNull(service.sessionContext().namespace)
    }

    @Test
    fun `a discarded adoption loads nothing`() = runBlocking {
        // The adoption that beat it owns the reload; loading here would search
        // the session that adoption installed while believing it opened another.
        val api = RecordingApi()
        val service = NacosSearchService(apiProvider = { api.service })
        var selected = settings.activeServerId
        val controller = ToolWindowSearchController(
            searchService = service,
            selectedProfileId = { selected },
            captureOperationContext = { profileId ->
                selected = "another-environment"
                capturedContextFor(profileId)
            }
        )

        controller.openNamespace(namespace("ns-a"))

        assertTrue(api.calls.isEmpty(), "a discarded adoption must not search: ${api.calls}")
        assertEquals(NacosSearchService.SearchState.Idle, service.searchState.value)
    }

    @Test
    fun `a slow capture cannot overwrite the environment adopted after it`() = runBlocking {
        val api = RecordingApi()
        val service = NacosSearchService(apiProvider = { api.service })
        val firstCaptureStarted = CompletableDeferred<Unit>()
        val releaseFirstCapture = CompletableDeferred<Unit>()
        var captureCount = 0
        val controller = ToolWindowSearchController(
            searchService = service,
            selectedProfileId = { settings.activeServerId },
            captureOperationContext = { profileId ->
                if (captureCount++ == 0) {
                    firstCaptureStarted.complete(Unit)
                    releaseFirstCapture.await()
                }
                capturedContextFor(profileId)
            }
        )

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val slow = scope.async { controller.adoptSession(namespace("ns-a")) }
        withTimeout(2_000) { firstCaptureStarted.await() }
        controller.adoptSession(namespace("ns-b"))
        releaseFirstCapture.complete(Unit)

        assertEquals(
            ToolWindowSearchController.Adoption.DISCARDED,
            slow.await(),
            "the older capture must not install itself"
        )
        assertEquals("ns-b", service.sessionContext().namespaceId)
        scope.cancel()
    }

    @Test
    fun `leaving the environment abandons the results it produced`() = runBlocking {
        val api = RecordingApi()
        val service = NacosSearchService(apiProvider = { api.service })
        val controller = controllerFor(service)
        controller.openNamespace(namespace("ns-a"))
        controller.search(SearchCriteria(dataId = "app.yaml", searchContent = false))
        assertTrue(service.searchState.value is NacosSearchService.SearchState.Success)

        controller.leaveEnvironment()

        assertEquals(NacosSearchService.SearchState.Idle, service.searchState.value)
        assertNull(service.sessionContext().namespace)
    }

    private fun controllerFor(
        service: NacosSearchService,
        captures: MutableList<String> = mutableListOf()
    ) = ToolWindowSearchController(
        searchService = service,
        selectedProfileId = { settings.activeServerId },
        captureOperationContext = { profileId ->
            captures += profileId
            capturedContextFor(profileId)
        }
    )

    private fun capturedContextFor(profileId: String): NacosOperationContext? =
        settings.captureOperationContext(profileId).getOrNull()

    private fun namespace(id: String) = NamespaceInfo(namespaceId = id, namespaceName = id)

    private data class ListCall(
        val namespaceId: String?,
        val pageNo: Int,
        val pageSize: Int,
        val dataId: String,
        val group: String,
        val operationContext: NacosOperationContext?
    )

    /** Records what each search asked the server for, and answers one page. */
    private class RecordingApi(pagesAvailable: Int = 1, totalCount: Int = 1) {
        val calls = mutableListOf<ListCall>()
        val service: NacosApiService = mock()

        init {
            val response = ConfigListResponse(
                totalCount = totalCount,
                pageNumber = 1,
                pagesAvailable = pagesAvailable,
                pageItems = listOf(
                    ConfigItem(
                        id = "1",
                        dataId = "app.yaml",
                        group = "DEFAULT_GROUP",
                        content = "feature=true",
                        type = "yaml",
                        tenant = null
                    )
                )
            )
            runBlocking {
                whenever(
                    service.listConfigurations(
                        anyOrNull(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), anyOrNull()
                    )
                ).thenAnswer { invocation ->
                    calls += ListCall(
                        namespaceId = invocation.getArgument(0),
                        pageNo = invocation.getArgument(1),
                        pageSize = invocation.getArgument(2),
                        dataId = invocation.getArgument(3),
                        group = invocation.getArgument(4),
                        operationContext = invocation.getArgument(10)
                    )
                    // `Result` is a value class: a suspend function returning one
                    // hands back the success value itself, not a boxed Result.
                    Observed(response, 1L)
                }
            }
        }
    }
}
