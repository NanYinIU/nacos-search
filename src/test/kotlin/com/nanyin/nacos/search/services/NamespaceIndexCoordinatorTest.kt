@file:OptIn(CacheWriteAccess::class)

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
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
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
        val testProfileId = "server-a"
        try {
            // Lift any residual tombstone so this test can publish under a stable id
            // even after a prior class removed the same profile id (ADR-0025).
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
            settings.applyServers(
                listOf(
                    com.nanyin.nacos.search.models.NacosServerConfig(
                        id = testProfileId,
                        serverUrl = "http://a:8848",
                        username = "alice",
                        password = "secret-a",
                        authMode = AuthMode.BASIC
                    )
                ),
                testProfileId
            )

            val captured = settings.captureNamespaceIndexRequest("ns-a")
            settings.servers[0].serverUrl = "http://b:8848"
            settings.servers[0].username = "bob"

            assertEquals("http://a:8848", captured.operationContext.endpoint.value)
            assertEquals("alice", captured.operationContext.identity.principal)
            assertEquals(testProfileId, captured.key.identity.profileId)
            assertEquals(captured.operationContext.endpoint.value, captured.key.identity.canonicalEndpoint)
            assertNotEquals(settings.getActiveServer().serverUrl, captured.operationContext.endpoint.value)
        } finally {
            restorePublishedServers(settings, originalServers, originalActive, extraIds = listOf(testProfileId))
        }
    }

    @Test
    fun `captured index request keeps configured cache TTL`() {
        val settings = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.nanyin.nacos.search.settings.NacosSettings::class.java)
        val originalServers = settings.servers.map { it.copy() }
        val originalActive = settings.activeServerId
        val originalCacheTtlMinutes = settings.cacheTtlMinutes
        val testProfileId = "server-ttl"
        try {
            // Flat fields are not a runtime source after migration (#104): publish
            // a profile so captureOperationContext can succeed, and lift tombstones
            // left by other ApplicationRule tests that removed the same ids.
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
            settings.cacheTtlMinutes = 17
            settings.applyServers(
                listOf(
                    com.nanyin.nacos.search.models.NacosServerConfig(
                        id = testProfileId,
                        serverUrl = "http://ttl:8848",
                        authMode = AuthMode.ANONYMOUS
                    )
                ),
                testProfileId
            )

            val captured = settings.captureNamespaceIndexRequest("ns-a")

            assertEquals(17L * 60 * 1000, captured.cacheTtlMillis)
        } finally {
            settings.cacheTtlMinutes = originalCacheTtlMinutes
            restorePublishedServers(settings, originalServers, originalActive, extraIds = listOf(testProfileId))
        }
    }

    /** Lift tombstones then republish so later tests can capture again. */
    private fun restorePublishedServers(
        settings: com.nanyin.nacos.search.settings.NacosSettings,
        originalServers: List<com.nanyin.nacos.search.models.NacosServerConfig>,
        originalActive: String,
        extraIds: List<String> = emptyList()
    ) {
        // applyServers entombs removed ids and never lifts them on restore
        // (by design for production). Clear first so the restored rows can
        // re-enter the published set; clear again so the temporary test ids
        // do not block a later class that reuses them.
        clearProfileTombstones(originalServers.map { it.id } + extraIds)
        settings.applyServers(originalServers, originalActive)
        clearProfileTombstones(originalServers.map { it.id } + extraIds)
    }

    private fun clearProfileTombstones(profileIds: List<String>) {
        val tombstones = com.intellij.openapi.application.ApplicationManager
            .getApplication()
            .getService(ProfileTombstoneRegistry::class.java)
        profileIds.forEach { tombstones.clear(it) }
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

        whenever(cacheService.applyMutation(any(), any())).thenReturn(true)

        val outcome = coordinator.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)

        assertTrue(outcome is IndexOutcome.Complete)
        verify(cacheService).applyMutation(
            eq(
                CacheMutation.ReplaceNamespaceIndex(
                    identity,
                    "ns-a",
                    listOf(configuration),
                    cacheTtlMillis
                )
            ),
            any()
        )
        Unit
    }

    @Test
    fun `complete V1 load seeds non-blank bodies into detail cache under the index observation`() = runBlocking {
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        whenever(apiService.protocolCapabilities(NacosApiGeneration.V1))
            .thenReturn(com.nanyin.nacos.search.services.operations.ProtocolCapabilities.V1)
        val cacheService = CacheService({ 2_000_000L }, InMemoryCacheStore())
        cacheService.clearAll()

        val withBody = NacosConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "ns-a",
            content = "feature.enabled=true",
            type = "yaml"
        )
        val blankBody = NacosConfiguration(
            dataId = "empty.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "ns-a",
            content = "",
            type = "yaml"
        )
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
                    expectedCount = 2,
                    configurations = listOf(withBody, blankBody),
                    failures = emptyList()
                )
            )
        )

        val outcome = NamespaceIndexCoordinator(apiService, cacheService)
            .requestIndex(indexRequest(), IndexTrigger.NAMESPACE_SWITCH)

        assertTrue(outcome is IndexOutcome.Complete)
        // Index still stores summaries only (issue #52).
        val index = cacheService.getNamespaceIndex(identity, "ns-a")
        assertEquals(2, index?.size)
        assertTrue(index!!.all { it.content.isEmpty() })
        // Non-blank list-carried body lands as an ordinary detail.
        assertEquals(
            "feature.enabled=true",
            cacheService.getConfigDetail(identity, "ns-a", "app.yaml", "DEFAULT_GROUP")?.content
        )
        // Blank bodies are not seeded.
        assertNull(
            cacheService.getConfigDetail(identity, "ns-a", "empty.yaml", "DEFAULT_GROUP")
        )
    }

    @Test
    fun `complete V3 load does not seed details even when list rows carry content`() = runBlocking {
        val v3Identity = identity.copy(resolvedGeneration = NacosApiGeneration.V3)
        val v3Context = context.copy(
            identity = v3Identity,
            resolvedGeneration = NacosApiGeneration.V3
        )
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        whenever(apiService.protocolCapabilities(NacosApiGeneration.V3))
            .thenReturn(com.nanyin.nacos.search.services.operations.ProtocolCapabilities.V3)
        val cacheService = CacheService({ 2_000_000L }, InMemoryCacheStore())
        cacheService.clearAll()

        val withBody = NacosConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "ns-a",
            content = "should-not-seed=true",
            type = "yaml"
        )
        whenever(
            apiService.loadNamespace(
                namespaceId = "ns-a",
                useCache = false,
                operationContext = v3Context
            )
        ).thenReturn(
            Result.success(
                NamespaceLoadResult(
                    completeness = DatasetCompleteness.COMPLETE,
                    expectedCount = 1,
                    configurations = listOf(withBody),
                    failures = emptyList()
                )
            )
        )

        val outcome = NamespaceIndexCoordinator(apiService, cacheService)
            .requestIndex(indexRequest(ctx = v3Context), IndexTrigger.NAMESPACE_SWITCH)

        assertTrue(outcome is IndexOutcome.Complete)
        assertNull(
            "V3 lists do not declare listCarriesBodies; prefetch remains the body source",
            cacheService.getConfigDetail(v3Identity, "ns-a", "app.yaml", "DEFAULT_GROUP")
        )
    }

    @Test
    fun `seeded detail loses to a later-started detail write`() = runBlocking {
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        whenever(apiService.protocolCapabilities(NacosApiGeneration.V1))
            .thenReturn(com.nanyin.nacos.search.services.operations.ProtocolCapabilities.V1)
        val cacheService = CacheService({ 2_000_000L }, InMemoryCacheStore())
        cacheService.clearAll()

        val withBody = NacosConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "ns-a",
            content = "from-list=true",
            type = "yaml"
        )
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
                    configurations = listOf(withBody),
                    failures = emptyList()
                )
            )
        )

        NamespaceIndexCoordinator(apiService, cacheService)
            .requestIndex(indexRequest(), IndexTrigger.NAMESPACE_SWITCH)

        assertEquals(
            "from-list=true",
            cacheService.getConfigDetail(identity, "ns-a", "app.yaml", "DEFAULT_GROUP")?.content
        )

        val newer = NacosConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "ns-a",
            content = "from-detail=true",
            type = "yaml"
        )
        val laterObservation = gateway.beginObservation()
        assertTrue(
            cacheService.applyMutation(
                CacheMutation.WriteDetail(identity, "ns-a", newer, 300_000L),
                laterObservation
            )
        )
        assertEquals(
            "from-detail=true",
            cacheService.getConfigDetail(identity, "ns-a", "app.yaml", "DEFAULT_GROUP")?.content
        )
    }

    @Test
    fun `partial namespace load for an entombed profile writes nothing to the detail cache`() = runBlocking {
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        val tombstones = ProfileTombstoneRegistry()
        val cacheService = CacheService({ 2_000_000L }, tombstones, InMemoryCacheStore())
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
            cacheService.snapshot(identity).configurations.isEmpty()
        )
    }

    @Test
    fun `partial namespace load preserves existing details and does not seed new ones`() = runBlocking {
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        val cacheService = CacheService({ 2_000_000L }, InMemoryCacheStore())
        cacheService.clearAll()
        cacheService.writeDetail(
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

    @Test
    fun `PSI trigger after Partial respects cooldown`() = runBlocking {
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        val cacheService = CacheService({ 2_000_000L }, InMemoryCacheStore())
        cacheService.clearAll()
        val request = indexRequest("partial-cooldown-ns")
        whenever(
            apiService.loadNamespace(
                namespaceId = "partial-cooldown-ns",
                useCache = false,
                operationContext = context
            )
        ).thenReturn(
            Result.success(
                NamespaceLoadResult(
                    completeness = DatasetCompleteness.PARTIAL,
                    expectedCount = 2,
                    configurations = listOf(
                        NacosConfiguration("a.yaml", "DEFAULT_GROUP", "partial-cooldown-ns", "x=1")
                    ),
                    failures = emptyList()
                )
            )
        )

        val coordinator = NamespaceIndexCoordinator(apiService, cacheService)
        val first = coordinator.requestIndex(request, IndexTrigger.PSI)
        assertTrue("Expected Partial: $first", first is IndexOutcome.Partial)

        val second = coordinator.requestIndex(request, IndexTrigger.PSI)
        assertTrue("Expected stale during Partial cooldown: $second", second is IndexOutcome.Stale)
    }

    @Test
    fun `SEARCH and NAMESPACE_SWITCH share failure backoff with PSI`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        val cacheService = CacheService({ 2_000_000L }, InMemoryCacheStore())
        cacheService.clearAll()
        val request = indexRequest("shared-backoff-ns")
        whenever(
            apiService.loadNamespace(
                namespaceId = "shared-backoff-ns",
                useCache = false,
                operationContext = context
            )
        ).thenReturn(Result.failure(RuntimeException("offline")))

        val coordinator = NamespaceIndexCoordinator(apiService, cacheService, clock = { now.get() })
        val first = coordinator.requestIndex(request, IndexTrigger.PSI)
        assertTrue("Expected Failed: $first", first is IndexOutcome.Failed)

        val searchDuringBackoff = coordinator.requestIndex(request, IndexTrigger.SEARCH)
        assertTrue(
            "SEARCH must share the failure backoff (issue #145): $searchDuringBackoff",
            searchDuringBackoff is IndexOutcome.Stale
        )
        val switchDuringBackoff = coordinator.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)
        assertTrue(
            "NAMESPACE_SWITCH must share the failure backoff (issue #145): $switchDuringBackoff",
            switchDuringBackoff is IndexOutcome.Stale
        )
    }

    @Test
    fun `MANUAL_REFRESH bypasses failure backoff`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        val cacheService = CacheService({ 2_000_000L }, InMemoryCacheStore())
        cacheService.clearAll()
        val request = indexRequest("manual-bypass-ns")
        whenever(
            apiService.loadNamespace(
                namespaceId = "manual-bypass-ns",
                useCache = false,
                operationContext = context
            )
        ).thenReturn(Result.failure(RuntimeException("offline")))

        val coordinator = NamespaceIndexCoordinator(apiService, cacheService, clock = { now.get() })
        assertTrue(coordinator.requestIndex(request, IndexTrigger.PSI) is IndexOutcome.Failed)

        // Still failing, but manual must not be gated by the backoff.
        val manual = coordinator.requestIndex(request, IndexTrigger.MANUAL_REFRESH)
        assertTrue("MANUAL_REFRESH must bypass backoff: $manual", manual is IndexOutcome.Failed)
    }

    @Test
    fun `failure backoff grows 30s then 2m then 5m and resets on Complete`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val apiService = mock<NacosApiService>()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(apiService.operationGateway()).thenReturn(gateway)
        val cacheService = CacheService({ 2_000_000L }, InMemoryCacheStore())
        cacheService.clearAll()
        val request = indexRequest("exp-backoff-ns")
        // Avoid thenAnswer + Result: mockito double-wraps the value type for
        // suspend Result returns. Re-stub with thenReturn before each call.
        suspend fun stubFailure() {
            whenever(
                apiService.loadNamespace(
                    namespaceId = "exp-backoff-ns",
                    useCache = false,
                    operationContext = context
                )
            ).thenReturn(Result.failure(RuntimeException("offline")))
        }
        suspend fun stubComplete() {
            whenever(
                apiService.loadNamespace(
                    namespaceId = "exp-backoff-ns",
                    useCache = false,
                    operationContext = context
                )
            ).thenReturn(
                Result.success(
                    NamespaceLoadResult(
                        completeness = DatasetCompleteness.COMPLETE,
                        expectedCount = 0,
                        configurations = emptyList(),
                        failures = emptyList()
                    )
                )
            )
        }

        val coordinator = NamespaceIndexCoordinator(apiService, cacheService, clock = { now.get() })

        stubFailure()
        assertTrue(coordinator.requestIndex(request, IndexTrigger.PSI) is IndexOutcome.Failed)
        // First backoff: 30s — still blocked at +29s, open at +30s.
        now.addAndGet(29_000L)
        assertTrue(coordinator.requestIndex(request, IndexTrigger.SEARCH) is IndexOutcome.Stale)
        now.addAndGet(1_000L)
        stubFailure()
        assertTrue(coordinator.requestIndex(request, IndexTrigger.SEARCH) is IndexOutcome.Failed)

        // Second backoff: 2m — blocked at +119s, open at +120s.
        now.addAndGet(119_000L)
        assertTrue(coordinator.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH) is IndexOutcome.Stale)
        now.addAndGet(1_000L)
        stubFailure()
        assertTrue(coordinator.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH) is IndexOutcome.Failed)

        // Third backoff: 5m cap — blocked at +299s, open at +300s.
        now.addAndGet(299_000L)
        assertTrue(coordinator.requestIndex(request, IndexTrigger.PSI) is IndexOutcome.Stale)
        now.addAndGet(1_000L)
        stubComplete()
        val completeOutcome = coordinator.requestIndex(request, IndexTrigger.PSI)
        assertTrue("Expected Complete after 5m backoff, got: $completeOutcome", completeOutcome is IndexOutcome.Complete)

        // Success resets: a fresh failure starts again at 30s, not 5m.
        stubFailure()
        assertTrue(coordinator.requestIndex(request, IndexTrigger.PSI) is IndexOutcome.Failed)
        now.addAndGet(30_000L)
        stubComplete()
        assertTrue(
            "Backoff must reset after Complete so the next window is 30s",
            coordinator.requestIndex(request, IndexTrigger.PSI) is IndexOutcome.Complete
        )
    }
}
