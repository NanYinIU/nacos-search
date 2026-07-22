package com.nanyin.nacos.search.services

import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.mockito.kotlin.whenever
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class NacosSearchServiceTest {

    @Test
    fun `search request normalizes wildcard and prefix fuzzy dataId`() {
        val wildcard = NacosSearchService.SearchRequest(dataId = "*")
        assertEquals("", wildcard.getProcessedDataId())
        assertEquals("blur", wildcard.getSearchMode())
        assertTrue(wildcard.requiresLocalIndex())

        val prefix = NacosSearchService.SearchRequest(dataId = "*config")
        assertEquals("config", prefix.getProcessedDataId())
        assertEquals("blur", prefix.getSearchMode())
        assertTrue(prefix.requiresLocalIndex())

        val exact = NacosSearchService.SearchRequest(dataId = "app.yaml", group = "DEFAULT_GROUP")
        assertEquals("app.yaml", exact.getProcessedDataId())
        assertEquals("accurate", exact.getSearchMode())
        assertFalse(exact.requiresLocalIndex())
    }

    @Test
    fun `only searches requiring a local index route to coordinator`() {
        assertEquals(
            IndexTrigger.SEARCH,
            NacosSearchService.SearchRequest(searchContent = true).fullNamespaceTrigger()
        )
        assertEquals(
            IndexTrigger.SEARCH,
            NacosSearchService.SearchRequest(dataId = "*config").fullNamespaceTrigger()
        )
        assertEquals(
            null,
            NacosSearchService.SearchRequest(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                pageNo = 3
            ).fullNamespaceTrigger()
        )
    }

    @Test
    fun `regex and content-paged searches route to coordinator`() {
        assertEquals(
            IndexTrigger.SEARCH,
            NacosSearchService.SearchRequest(useRegex = true).fullNamespaceTrigger()
        )
        // Content search takes priority over paging — still routes to coordinator
        assertEquals(
            IndexTrigger.SEARCH,
            NacosSearchService.SearchRequest(
                searchContent = true,
                pageNo = 3,
                pageSize = 20
            ).fullNamespaceTrigger()
        )
        // Plain wildcard-only search without content or regex also routes
        assertEquals(
            IndexTrigger.SEARCH,
            NacosSearchService.SearchRequest(dataId = "*").fullNamespaceTrigger()
        )
    }

    @Test
    fun `ordinary paged search does not request a namespace index`() = runBlocking {
        val coordinator = mock<NamespaceIndexRequester>()
        val service = NacosSearchService(indexRequester = coordinator)
        val api = stubApi()

        service.performSearch(
            NacosSearchService.SearchRequest(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                pageNo = 2,
                pageSize = 20
            ),
            api
        )

        verify(coordinator, never()).requestIndex(any(), any())
        verify(api, times(1)).listConfigurations(
            anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull()
        )
        Unit
    }

    @Test
    fun `invalid configuration fails closed in the search UI path before cache or API`() = runBlocking {
        val settings = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(NacosSettings::class.java)
        val original = settings.copy()
        try {
            settings.resetToDefaults()
            settings.serverUrl = "https://nacos.example/not-an-origin"
            val api = mock<NacosApiService>()
            val service = NacosSearchService()

            service.performSearch(
                NacosSearchService.SearchRequest(namespace = NamespaceInfo.createPublicNamespace()),
                api
            )

            val state = service.searchState.value
            assertTrue(state is NacosSearchService.SearchState.Error)
            assertTrue((state as NacosSearchService.SearchState.Error).throwable is ConfigurationRequired)
            verify(api, never()).listConfigurations(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull()
            )
            Unit
        } finally {
            settings.copyFrom(original)
        }
    }

    @Test
    fun `search request cache key includes filters paging and content options`() = runBlocking {
        val first = NacosSearchService.SearchRequest(
            dataId = "app",
            group = "DEFAULT_GROUP",
            query = "timeout",
            searchContent = true,
            caseSensitive = false,
            useRegex = true,
            pageNo = 2,
            pageSize = 50
        )
        val second = first.copy(pageNo = 3)

        assertEquals(
            "namespace=|dataId=app|group=DEFAULT_GROUP|appName=|configTags=|query=timeout|searchContent=true|caseSensitive=false|useRegex=true|search=accurate|pageNo=2|pageSize=50",
            first.toCacheKey()
        )
        assertTrue(first.toCacheKey() != second.toCacheKey())
    }

    @Test
    fun `search request cache key separates namespaces`() {
        val publicNamespace = NamespaceInfo.createPublicNamespace()
        val testNamespace = NamespaceInfo(namespaceId = "test-01", namespaceName = "test-01")
        val request = NacosSearchService.SearchRequest(
            dataId = "application.properties",
            group = "DEFAULT_GROUP",
            namespace = publicNamespace
        )

        assertTrue(request.toCacheKey() != request.copy(namespace = testNamespace).toCacheKey())
    }

    private fun stubApi(): NacosApiService {
        val response = NacosApiService.ConfigListResponse(
            totalCount = 1,
            pageNumber = 1,
            pagesAvailable = 1,
            pageItems = listOf(
                NacosApiService.ConfigItem(
                    id = "1",
                    dataId = "app.yaml",
                    group = "DEFAULT_GROUP",
                    content = "feature=true",
                    type = "yaml",
                    tenant = null
                )
            )
        )
        val api = mock<NacosApiService>()
        runBlocking {
            whenever(
                api.listConfigurations(
                    anyOrNull(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), anyOrNull()
                )
            ).thenReturn(Result.success(response))
        }
        return api
    }

    @Test
    fun `searchWithDebounce delays execution until debounce window elapses`() = runBlocking {
        val service = NacosSearchService()
        val api = stubApi()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val request = NacosSearchService.SearchRequest(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespace = NamespaceInfo.createPublicNamespace()
        )

        service.searchWithDebounce(request, api, scope)

        // Immediately after scheduling: the 300ms debounce delay has not
        // elapsed, so performSearch has not run yet.
        assertEquals(NacosSearchService.SearchState.Idle, service.searchState.value)

        // Wait past the 300ms debounce window for the search to run.
        val deadline = System.currentTimeMillis() + 3000
        while (service.searchState.value !is NacosSearchService.SearchState.Success &&
            System.currentTimeMillis() < deadline) {
            delay(50)
        }

        assertTrue(service.searchState.value is NacosSearchService.SearchState.Success)
        scope.cancel()
    }

    @Test
    fun `searchWithDebounce cancels the previous in-flight search`() = runBlocking {
        val service = NacosSearchService()
        val api = stubApi()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val namespace = NamespaceInfo.createPublicNamespace()

        // Two rapid calls with different dataIds — only the second should execute.
        service.searchWithDebounce(
            NacosSearchService.SearchRequest(dataId = "first.yaml", namespace = namespace),
            api, scope
        )
        service.searchWithDebounce(
            NacosSearchService.SearchRequest(dataId = "second.yaml", namespace = namespace),
            api, scope
        )

        // Wait for the surviving (second) debounced search to complete.
        val deadline = System.currentTimeMillis() + 3000
        while (service.searchState.value !is NacosSearchService.SearchState.Success &&
            System.currentTimeMillis() < deadline) {
            delay(50)
        }

        // The cancelled (first) request must not have triggered a remote call;
        // only the second survives.
        verify(api, times(1)).listConfigurations(
            any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), anyOrNull()
        )
        scope.cancel()
    }

}
