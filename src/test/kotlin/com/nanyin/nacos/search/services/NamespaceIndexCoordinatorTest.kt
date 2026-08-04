package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NamespaceLoadResult
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NamespaceIndexCoordinatorTest {

    @get:Rule
    val applicationRule = ApplicationRule()

    private val identity = AccessIdentity.ofProfile(
        profileId = "test-profile",
        accessRevision = 1,
        canonicalEndpoint = "http://test:8848",
        resolvedGeneration = NacosApiGeneration.V1,
        authMode = AuthMode.BASIC,
        principal = "admin"
    )
    private val context = NacosOperationContext(
        identity = identity,
        endpoint = CanonicalNacosEndpoint.parse("http://test:8848").getOrThrow(),
        credential = CredentialSnapshot("admin"),
        authMode = AuthMode.BASIC,
        profileRevision = 1,
        accessRevision = 1,
        resolvedGeneration = NacosApiGeneration.V1
    )

    @Before
    fun setUp() {
        runBlocking {
        val cache = com.intellij.openapi.application.ApplicationManager
            .getApplication()
            .getService(CacheService::class.java)
        cache.clearAll()
        cache.awaitLoadCompleted() // drain background load
        }
    }

    private fun indexRequest(
        namespaceId: String = "ns-a",
        ctx: NacosOperationContext = context,
        cacheTtlMillis: Long = 300_000L
    ) = NamespaceIndexRequest(
        key = NamespaceIndexKey(ctx.identity, namespaceId),
        cacheTtlMillis = cacheTtlMillis,
        operationContext = ctx
    )

    @Test
    fun `captured index request keeps original identity after settings switch`() {
        val settings = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.nanyin.nacos.search.settings.NacosSettings::class.java)
        val originalServers = settings.servers.map { it.copy() }
        val originalActive = settings.activeServerId
        try {
            settings.applyServers(
                listOf(
                    com.nanyin.nacos.search.models.NacosServerConfig(
                        id = "server-a",
                        serverUrl = "http://a:8848",
                        username = "alice",
                        password = "secret-a",
                        authMode = AuthMode.BASIC
                    )
                ),
                "server-a"
            )

            val captured = settings.captureNamespaceIndexRequest("ns-a")
            settings.servers[0].serverUrl = "http://b:8848"
            settings.servers[0].username = "bob"

            assertEquals("http://a:8848", captured.operationContext.endpoint.value)
            assertEquals("alice", captured.operationContext.identity.principal)
            assertEquals("server-a", captured.key.identity.profileId)
            assertEquals(captured.operationContext.endpoint.value, captured.key.identity.canonicalEndpoint)
            assertNotEquals(settings.getActiveServer().serverUrl, captured.operationContext.endpoint.value)
        } finally {
            settings.applyServers(originalServers, originalActive)
            // applyServers entombs removed ids and never lifts them on restore
            // (by design for production). Clear the restored profiles so later
            // tests can write the shared CacheService again.
            val tombstones = com.intellij.openapi.application.ApplicationManager
                .getApplication()
                .getService(ProfileTombstoneRegistry::class.java)
            originalServers.forEach { tombstones.clear(it.id) }
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
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        val coordinator = NamespaceIndexCoordinator(apiService, cacheService)
        val configuration = NacosConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "ns-a",
            content = "feature.enabled=true",
            type = "yaml"
        )
        val cacheTtlMillis = 17L * 60 * 1000
        val request = indexRequest(cacheTtlMillis = cacheTtlMillis)
        whenever(
            apiService.loadNamespace(
                namespaceId = "ns-a",
                useCache = false,
                operationContext = context
            )
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
            identity,
            "ns-a",
            listOf(configuration),
            cacheTtlMillis
        )
    }

    @Test
    fun `partial namespace load for an entombed profile writes nothing to the detail cache`() = runBlocking {
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        val tombstones = ProfileTombstoneRegistry()
        val cacheService = CacheService({ 2_000_000L }, tombstones)
        cacheService.clearAll()
        tombstones.entomb(identity.profileId, identity.accessRevision)

        val late = NacosConfiguration("late.yaml", "DEFAULT_GROUP", "ns-a", "should-not-land=true")
        val request = indexRequest()
        whenever(
            apiService.loadNamespace(
                namespaceId = "ns-a",
                useCache = false,
                operationContext = context
            )
        ).thenReturn(
            Result.success(
                NamespaceLoadResult(
                    completeness = DatasetCompleteness.PARTIAL,
                    expectedCount = 2,
                    configurations = listOf(late),
                    failures = emptyList()
                )
            )
        )

        val outcome = NamespaceIndexCoordinator(apiService, cacheService)
            .requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)

        assertTrue(outcome is IndexOutcome.Partial)
        assertNull(
            cacheService.getConfigDetail(
                identity,
                "ns-a",
                "late.yaml",
                "DEFAULT_GROUP",
                allowStale = true
            )
        )
        assertTrue(
            "entombed partial load must not resurrect configuration details",
            cacheService.configurationNavigationSnapshot(identity).isEmpty()
        )
    }

    @Test
    fun `partial namespace load preserves existing details and does not seed new ones`() = runBlocking {
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        val cacheService = CacheService { 2_000_000L }
        cacheService.clearAll()
        cacheService.putConfigDetail(
            identity,
            "ns-a",
            NacosConfiguration("stale.yaml", "DEFAULT_GROUP", "ns-a", "old=true"),
            ttl = -1L
        )
        val fresh = NacosConfiguration("fresh.yaml", "DEFAULT_GROUP", "ns-a", "new=true")
        val request = indexRequest()
        whenever(
            apiService.loadNamespace(
                namespaceId = "ns-a",
                useCache = false,
                operationContext = context
            )
        ).thenReturn(
            Result.success(
                NamespaceLoadResult(
                    completeness = DatasetCompleteness.PARTIAL,
                    expectedCount = 2,
                    configurations = listOf(fresh),
                    failures = emptyList()
                )
            )
        )

        val outcome = NamespaceIndexCoordinator(apiService, cacheService)
            .requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)

        assertTrue(outcome is IndexOutcome.Partial)
        // Issue #52: partial summary index never seeds the detail cache.
        assertNull(
            cacheService.getConfigDetail(identity, "ns-a", "fresh.yaml", "DEFAULT_GROUP")
        )
        // Pre-existing stale details must survive the partial index attempt.
        assertEquals(
            "old=true",
            cacheService.getConfigDetail(
                identity,
                "ns-a",
                "stale.yaml",
                "DEFAULT_GROUP",
                allowStale = true
            )?.content
        )
    }

    @Test
    fun `different keys produce independent outcomes`() = runBlocking {
        val coordinator = NamespaceIndexCoordinator()
        val keyA = NamespaceIndexKey(identity, "ns-a")
        val keyB = NamespaceIndexKey(identity, "ns-b")

        val (outcomeA, outcomeB) = awaitAll(
            async { coordinator.requestIndex(NamespaceIndexRequest(keyA, 300_000L, context), IndexTrigger.PSI) },
            async { coordinator.requestIndex(NamespaceIndexRequest(keyB, 300_000L, context), IndexTrigger.PSI) }
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
        val request = NamespaceIndexRequest(key, 300_000L, context)
        val first = coordinator.requestIndex(request, IndexTrigger.PSI)
        assertTrue("Expected failure: $first", first is IndexOutcome.Failed)

        // Immediate second PSI request should be blocked by cooldown
        val second = coordinator.requestIndex(request, IndexTrigger.PSI)
        assertTrue("Expected stale during cooldown: $second", second is IndexOutcome.Stale)
    }
}
