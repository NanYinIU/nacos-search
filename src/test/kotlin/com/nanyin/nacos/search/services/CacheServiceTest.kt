package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class CacheServiceTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `configuration detail cache uses server namespace dataId and group`() = runBlocking {
        val cacheService = CacheService()
        cacheService.clearAll()

        val config = NacosConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "dev",
            content = "feature=true",
            type = "yaml"
        )

        cacheService.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = config,
            ttl = 60_000L
        )

        val cached = cacheService.getConfigDetail(
            serverUrl = "http://nacos:8848/",
            namespaceId = "dev",
            dataId = "app.yaml",
            group = "DEFAULT_GROUP"
        )

        assertEquals(config, cached)
        assertNull(
            cacheService.getConfigDetail(
                serverUrl = "http://other:8848",
                namespaceId = "dev",
                dataId = "app.yaml",
                group = "DEFAULT_GROUP"
            )
        )
    }

    @Test
    fun `all cached configurations can be scoped to one server`() = runBlocking {
        val cacheService = CacheService()
        cacheService.clearAll()

        cacheService.putConfigDetail(
            serverUrl = "http://dev-nacos:8848",
            namespaceId = "public",
            configuration = NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=dev", "properties"),
            ttl = 60_000L
        )
        cacheService.putConfigDetail(
            serverUrl = "http://prod-nacos:8848",
            namespaceId = "public",
            configuration = NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=prod", "properties"),
            ttl = 60_000L
        )

        val devConfigs = cacheService.getAllCachedConfigurations("http://dev-nacos:8848/")

        assertEquals(1, devConfigs.size)
        assertEquals("app.name=dev", devConfigs.single().content)
    }

    @Test
    fun `list page cache expires independently from detail cache`() = runBlocking {
        val cacheService = CacheService()
        cacheService.clearAll()

        val page = NacosApiService.ConfigListResponse(
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
                    tenant = "dev"
                )
            )
        )
        val detail = NacosConfiguration("app.yaml", "DEFAULT_GROUP", "dev", "feature=true")

        cacheService.putListPage(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            requestKey = "page=1",
            response = page,
            ttl = 1L
        )
        cacheService.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = detail,
            ttl = 60_000L
        )

        Thread.sleep(5L)

        assertNull(cacheService.getListPage("http://nacos:8848", "dev", "page=1"))
        assertEquals(
            detail,
            cacheService.getConfigDetail("http://nacos:8848", "dev", "app.yaml", "DEFAULT_GROUP")
        )

        val stats = cacheService.getCacheStats()
        assertEquals(1, stats.detailEntries)
        assertEquals(0, stats.listPageEntries)
        assertEquals(1, stats.cacheHits)
        assertEquals(1, stats.cacheMisses)
    }

    @Test
    fun `cache modification count changes when detail cache changes`() = runBlocking {
        val cacheService = CacheService()
        cacheService.clearAll()
        val initial = cacheService.getModificationCount()

        cacheService.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = NacosConfiguration("app.yaml", "DEFAULT_GROUP", "dev", "feature=true"),
            ttl = 60_000L
        )

        val afterPut = cacheService.getModificationCount()
        assertEquals(initial + 1, afterPut)

        cacheService.clearAll()

        assertEquals(afterPut + 1, cacheService.getModificationCount())
    }

    @Test
    fun `namespace index preheat round-trips and is scoped by server and namespace`() = runBlocking {
        val cacheService = CacheService()
        cacheService.clearAll()

        val configs = listOf(
            NacosConfiguration("app.yaml", "DEFAULT_GROUP", "dev", "feature=true", "yaml"),
            NacosConfiguration("db.properties", "DEFAULT_GROUP", "dev", "db.url=jdbc:test", "properties")
        )

        // Preheat: store the full namespace index (as NacosSearchPlugin does).
        cacheService.putNamespaceIndex(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configurations = configs,
            ttl = 60_000L
        )

        // searchWithLocalIndex reads it back as a single hit — no remote pull.
        val cached = cacheService.getNamespaceIndex("http://nacos:8848", "dev")
        assertEquals(2, cached?.size)

        // A different server or namespace must not leak the preheated index.
        assertNull(cacheService.getNamespaceIndex("http://other:8848", "dev"))
        assertNull(cacheService.getNamespaceIndex("http://nacos:8848", "prod"))
    }

    @Test
    fun `putNamespaceIndex also seeds individual config details for the resolver`() = runBlocking {
        val cacheService = CacheService()
        cacheService.clearAll()

        cacheService.putNamespaceIndex(
            serverUrl = "http://nacos:8848",
            namespaceId = null,
            configurations = listOf(
                NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "timeout=30", "properties")
            ),
            ttl = 60_000L
        )

        // Preheating the namespace index makes individual configs resolvable
        // by detail key, which is what NacosKeyResolver scans.
        val detail = cacheService.getConfigDetail("http://nacos:8848", null, "app.properties", "DEFAULT_GROUP")
        assertEquals("timeout=30", detail?.content)
    }
}
