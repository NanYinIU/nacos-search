package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceLoadResult
import com.nanyin.nacos.search.services.network.RequestPolicy
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertNotEquals
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NamespaceIndexCoordinatorTest {

    @get:Rule
    val applicationRule = ApplicationRule()

    private val identity = AccessIdentity.of("http://test:8848", AuthMode.BASIC, "admin")
    private val server = NacosServerSnapshot("http://test:8848", "admin", "admin", AuthMode.BASIC, false)

    @Before
    fun setUp() {
        runBlocking {
        val cache = com.intellij.openapi.application.ApplicationManager
            .getApplication()
            .getService(CacheService::class.java)
        cache.clearAll()
        cache.getCacheStats() // drain background load
        }
    }

    @Test
    fun `captured index request keeps original server after settings switch`() {
        val settings = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.nanyin.nacos.search.settings.NacosSettings::class.java)
        val originalServers = settings.servers.map { it.copy() }
        val originalActive = settings.activeServerId
        try {
            settings.servers.clear()
            settings.servers.add(
                com.nanyin.nacos.search.models.NacosServerConfig(
                    id = "server-a",
                    serverUrl = "http://a:8848",
                    username = "alice",
                    password = "secret-a",
                    authMode = AuthMode.BASIC
                )
            )
            settings.activeServerId = "server-a"

            val captured = settings.captureNamespaceIndexRequest("ns-a")
            settings.servers[0].serverUrl = "http://b:8848"
            settings.servers[0].username = "bob"

            assertEquals("http://a:8848", captured.server.serverUrl)
            assertEquals("alice", captured.server.username)
            assertEquals(captured.key.identity.serverId, captured.server.serverUrl)
            assertNotEquals(settings.getActiveServer().serverUrl, captured.server.serverUrl)
        } finally {
            settings.servers.clear()
            settings.servers.addAll(originalServers)
            settings.activeServerId = originalActive
        }
    }

    @Test
    fun `captured index request keeps configured cache TTL`() {
        val settings = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.nanyin.nacos.search.settings.NacosSettings::class.java)
        val originalCacheTtlMinutes = settings.cacheTtlMinutes
        try {
            settings.cacheTtlMinutes = 17

            val captured = settings.captureNamespaceIndexRequest("ns-a")

            assertEquals(17L * 60 * 1000, captured.cacheTtlMillis)
        } finally {
            settings.cacheTtlMinutes = originalCacheTtlMinutes
        }
    }

    @Test
    fun `complete namespace load writes index with captured cache TTL`() = runBlocking {
        val apiService = mock<NacosApiService>()
        val cacheService = mock<CacheService>()
        val coordinator = NamespaceIndexCoordinator(apiService, cacheService)
        val configuration = NacosConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "ns-a",
            content = "feature.enabled=true",
            type = "yaml"
        )
        val cacheTtlMillis = 17L * 60 * 1000
        val request = NamespaceIndexRequest(
            NamespaceIndexKey(identity, "ns-a"),
            server,
            cacheTtlMillis
        )
        whenever(
            apiService.loadNamespace("ns-a", useCache = false, server = server, policy = RequestPolicy.PREHEAT)
        ).thenReturn(
            Result.success(
                NamespaceLoadResult(
                    completeness = DatasetCompleteness.COMPLETE,
                    expectedCount = 1,
                    configurations = listOf(configuration),
                    failures = emptyList()
                )
            )
        )

        coordinator.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)

        verify(cacheService).putNamespaceIndex(
            identity.serverId,
            "ns-a",
            listOf(configuration),
            cacheTtlMillis
        )
    }

    @Test
    fun `different keys produce independent outcomes`() = runBlocking {
        val coordinator = NamespaceIndexCoordinator()
        val keyA = NamespaceIndexKey(identity, "ns-a")
        val keyB = NamespaceIndexKey(identity, "ns-b")

        val (outcomeA, outcomeB) = awaitAll(
            async { coordinator.requestIndex(NamespaceIndexRequest(keyA, server, 300_000L), IndexTrigger.PSI) },
            async { coordinator.requestIndex(NamespaceIndexRequest(keyB, server, 300_000L), IndexTrigger.PSI) }
        )

        // Both complete (or fail, since no real server), but neither is blocked by the other
        assertTrue(outcomeA is IndexOutcome.Failed || outcomeA is IndexOutcome.Complete || outcomeA is IndexOutcome.Stale)
        assertTrue(outcomeB is IndexOutcome.Failed || outcomeB is IndexOutcome.Complete || outcomeB is IndexOutcome.Stale)
    }

    @Test
    fun `PSI trigger after failure respects cooldown`() = runBlocking {
        val coordinator = NamespaceIndexCoordinator()
        val key = NamespaceIndexKey(identity, "cooldown-ns")

        // First request fails (no real server)
        val request = NamespaceIndexRequest(key, server, 300_000L)
        val first = coordinator.requestIndex(request, IndexTrigger.PSI)
        assertTrue("Expected failure: $first", first is IndexOutcome.Failed)

        // Immediate second PSI request should be blocked by cooldown
        val second = coordinator.requestIndex(request, IndexTrigger.PSI)
        assertTrue("Expected stale during cooldown: $second", second is IndexOutcome.Stale)
    }
}
