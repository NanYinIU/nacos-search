package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NamespaceInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
