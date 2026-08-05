package com.nanyin.nacos.search.services

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.openapi.application.ApplicationManager
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.operations.Observed
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

@TestApplication
class NacosSearchServiceCancelTest {

    @BeforeEach
    fun resetSharedSettingsToAnonymousDefaults() {
        // Product defaults are NACOS_PASSWORD; these mocked search paths still exercise
        // anonymous credential-less stubs, so force ANONYMOUS after reset.
        ApplicationManager.getApplication().getService(NacosSettings::class.java).apply {
            resetToDefaults()
            authMode = AuthMode.ANONYMOUS
            getActiveServer().authMode = AuthMode.ANONYMOUS
        }
    }

    private fun stubApi(): NacosApiService {
        val response = ConfigListResponse(
            totalCount = 1,
            pageNumber = 1,
            pagesAvailable = 1,
            pageItems = listOf(
                ConfigItem(
                    id = "1", dataId = "app.yaml", group = "DEFAULT_GROUP",
                    content = "feature=true", type = "yaml", tenant = null
                )
            )
        )
        val api = mock<NacosApiService>()
        runBlocking {
            whenever(
                api.listConfigurations(
                    any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), anyOrNull()
                )
            ).thenReturn(Result.success(Observed(response, 1L)))
        }
        return api
    }

    @Test
    fun `performSearch cancels a pending debounced search`() = runBlocking {
        val service = NacosSearchService()
        val api = stubApi()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val ns = NamespaceInfo.createPublicNamespace()

        val reqA = NacosSearchService.SearchRequest(dataId = "a.yaml", group = "DEFAULT_GROUP", namespace = ns)
        service.searchWithDebounce(reqA, api, scope)

        // Immediate search (e.g. page change) must cancel the pending debounce.
        val reqB = NacosSearchService.SearchRequest(dataId = "b.yaml", group = "DEFAULT_GROUP", namespace = ns)
        service.performSearch(reqB, api)

        delay(500)

        // Only the immediate (B) search reached the API; the cancelled debounced (A)
        // coroutine must not have issued a second listing call.
        verify(api, times(1)).listConfigurations(
            any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), anyOrNull()
        )

        scope.cancel()
    }

    @Test
    fun `debounced search completes to success without self-cancelling`() = runBlocking {
        // Guards searchJob?.takeIf { it != currentJob }?.cancel(): if performSearch
        // cancelled the debounce job that invoked it, the search would abort before
        // publishing Success and this test would fail.
        val service = NacosSearchService()
        val api = stubApi()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val ns = NamespaceInfo.createPublicNamespace()
        val req = NacosSearchService.SearchRequest(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespace = ns
        )

        service.searchWithDebounce(req, api, scope)

        withTimeout(2_000) {
            while (service.searchState.value !is NacosSearchService.SearchState.Success) {
                delay(50)
            }
        }
        assertTrue(service.searchState.value is NacosSearchService.SearchState.Success)
        verify(api, times(1)).listConfigurations(
            any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), anyOrNull()
        )
        scope.cancel()
    }

    @Test
    fun `mid-flight session epoch bump drops the stale search result`() = runBlocking {
        // Issue #53 AC: a captured operation must not publish after the session
        // epoch advances (environment switch). Full chain:
        // performSearch → captureSessionTicket → bump → publishIfCurrent drops.
        val project = mock<Project>()
        whenever(project.locationHash).thenReturn("epoch-test-project")
        whenever(project.isDisposed).thenReturn(false)
        val epochs = ProjectSessionEpochs(project)
        whenever(project.getService(ProjectSessionEpochs::class.java)).thenReturn(epochs)

        val response = ConfigListResponse(
            totalCount = 1,
            pageNumber = 1,
            pagesAvailable = 1,
            pageItems = listOf(
                ConfigItem(
                    id = "1", dataId = "stale.yaml", group = "DEFAULT_GROUP",
                    content = "stale=true", type = "yaml", tenant = null
                )
            )
        )
        val api = mock<NacosApiService>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(
            api.listConfigurations(
                any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), anyOrNull()
            )
        ).thenAnswer {
            started.complete(Unit)
            runBlocking { release.await() }
            Result.success(response)
        }

        val service = NacosSearchService(project = project)
        val ns = NamespaceInfo.createPublicNamespace()
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val search = async {
            service.performSearch(
                NacosSearchService.SearchRequest(
                    dataId = "stale.yaml",
                    group = "DEFAULT_GROUP",
                    namespace = ns,
                    serverId = settings.activeServerId,
                    operationContext = context
                ),
                api
            )
        }

        withTimeout(2_000) { started.await() }
        // Mid-flight environment switch advances the session epoch.
        epochs.bump()
        release.complete(Unit)
        search.await()

        val state = service.searchState.value
        assertFalse(
            state is NacosSearchService.SearchState.Success,
            "stale search result must not publish after epoch bump; was $state"
        )
    }
}
