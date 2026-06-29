package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Rule
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import com.nanyin.nacos.search.models.NamespaceInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NacosSearchServiceTest {

    @get:Rule
    val applicationRule = ApplicationRule()

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
                    any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any()
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
            any(), any(), any(), any(), any(), any()
        )
        scope.cancel()
    }
}
