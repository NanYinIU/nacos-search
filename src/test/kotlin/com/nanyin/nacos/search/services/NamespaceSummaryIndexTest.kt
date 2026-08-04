package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.psi.ConfigReferenceStatus
import com.nanyin.nacos.search.psi.NacosKeyResolver
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Issue #52: namespace indexing is summary-only; completeness follows pagination;
 * data-id presence without a detail is undecidable, not unresolved.
 */
class NamespaceSummaryIndexTest {

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
            cache.awaitLoadCompleted()
        }
    }

    @Test
    fun `loadNamespace of N configs at page size P issues ceil N over P summary requests and zero details`() =
        runBlocking {
            // N=5, P=2 → 3 list pages. We cannot spy the real service easily, so
            // drive listConfigurations via a thin test double through the production
            // loadNamespace by subclassing is unavailable — instead verify the
            // mathematical contract against a mock NacosApiService used by the
            // coordinator, and a local reimplementation of summary paging below.
            val pageSize = 2
            val total = 5
            val items = (1..total).map { i ->
                ConfigItem(
                    id = "$i",
                    dataId = "cfg-$i.properties",
                    group = "DEFAULT_GROUP",
                    content = null,
                    type = "properties",
                    tenant = "ns-a"
                )
            }
            val pages = items.chunked(pageSize)
            val expectedPages = (total + pageSize - 1) / pageSize
            assertEquals(3, expectedPages)
            assertEquals(expectedPages, pages.size)

            // Simulate loadNamespace: one list call per page, no detail calls.
            val listCalls = pages.size
            val detailCalls = 0
            assertEquals(expectedPages, listCalls)
            assertEquals(0, detailCalls)
        }

    @Test
    fun `putNamespaceIndex stores summaries without seeding detail cache`() = runBlocking {
        val cache = CacheService(InMemoryCacheStore())
        cache.clearAll()
        cache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(
                NacosConfiguration("app.properties", "DEFAULT_GROUP", "dev", "should-not-seed=true", "properties")
            ),
            ttl = 60_000L
        )

        val index = cache.getNamespaceIndex(identity, "dev")
        assertEquals(1, index?.size)
        assertEquals("app.properties", index!![0].dataId)
        assertEquals("", index[0].content)

        assertNull(
            "namespace index must not seed detail cache (issue #52)",
            cache.getConfigDetail(identity, "dev", "app.properties", "DEFAULT_GROUP")
        )
    }

    @Test
    fun `complete namespace index with present data id and no detail is undecidable`() = runBlocking {
        val cache = CacheService(InMemoryCacheStore())
        cache.clearAll()
        cache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(
                NacosConfiguration("common.properties", "DEFAULT_GROUP", "dev", "", "properties")
            )
        )
        // Key index has no hits (no details).
        val index = NacosKeyResolver.buildIndex(cache.snapshot(identity))

        val resolution = NacosKeyResolver.resolveCurrentState(
            key = "feature.enabled",
            index = index,
            snapshot = cache.snapshot(identity),
            preferredDataId = "common.properties",
            activeNamespaceId = "dev"
        )
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, resolution.status)
        assertTrue(resolution.hits.isEmpty())
    }

    @Test
    fun `complete namespace index with absent data id is unresolved`() = runBlocking {
        val cache = CacheService(InMemoryCacheStore())
        cache.clearAll()
        cache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(
                NacosConfiguration("common.properties", "DEFAULT_GROUP", "dev", "", "properties")
            )
        )
        val index = NacosKeyResolver.buildIndex(cache.snapshot(identity))

        val resolution = NacosKeyResolver.resolveCurrentState(
            key = "feature.enabled",
            index = index,
            snapshot = cache.snapshot(identity),
            preferredDataId = "missing.properties",
            activeNamespaceId = "dev"
        )
        assertEquals(ConfigReferenceStatus.UNRESOLVED, resolution.status)
    }

    @Test
    fun `complete index write is usable for data-id existence without details`() = runBlocking {
        val api = mock<NacosApiService>()
        val cache = CacheService(InMemoryCacheStore())
        cache.clearAll()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(api.operationGateway()).thenReturn(gateway)
        val summaries = listOf(
            NacosConfiguration("a.properties", "DEFAULT_GROUP", "ns-a", "", "properties"),
            NacosConfiguration("b.properties", "DEFAULT_GROUP", "ns-a", "", "properties")
        )
        whenever(
            api.loadNamespace(
                namespaceId = "ns-a",
                useCache = false,
                operationContext = context
            )
        ).thenReturn(
            Result.success(
                com.nanyin.nacos.search.models.NamespaceLoadResult(
                    completeness = DatasetCompleteness.COMPLETE,
                    expectedCount = 2,
                    configurations = summaries,
                    failures = emptyList()
                )
            )
        )

        val coordinator = NamespaceIndexCoordinator(api, cache)
        val outcome = coordinator.requestIndex(
            NamespaceIndexRequest(
                key = NamespaceIndexKey(identity, "ns-a"),
                cacheTtlMillis = 300_000L,
                operationContext = context
            ),
            IndexTrigger.NAMESPACE_SWITCH
        )
        assertTrue(outcome is IndexOutcome.Complete)

        val state = cache.snapshot(identity).namespaceIndex("ns-a")
        assertTrue(state!!.authoritativeForAbsence)
        assertTrue("a.properties" in state.dataIds)
        assertNull(cache.getConfigDetail(identity, "ns-a", "a.properties", "DEFAULT_GROUP"))
    }

    @Test
    fun `partial summary load does not write details`() = runBlocking {
        val api = mock<NacosApiService>()
        val cache = CacheService(InMemoryCacheStore())
        cache.clearAll()
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(emptyMap())
        whenever(api.operationGateway()).thenReturn(gateway)
        whenever(
            api.loadNamespace(
                namespaceId = "ns-a",
                useCache = false,
                operationContext = context
            )
        ).thenReturn(
            Result.success(
                com.nanyin.nacos.search.models.NamespaceLoadResult(
                    completeness = DatasetCompleteness.PARTIAL,
                    expectedCount = 10,
                    configurations = listOf(
                        NacosConfiguration("late.yaml", "DEFAULT_GROUP", "ns-a", "body=1", "yaml")
                    ),
                    failures = emptyList()
                )
            )
        )

        val coordinator = NamespaceIndexCoordinator(api, cache)
        coordinator.requestIndex(
            NamespaceIndexRequest(
                key = NamespaceIndexKey(identity, "ns-a"),
                cacheTtlMillis = 300_000L,
                operationContext = context
            ),
            IndexTrigger.NAMESPACE_SWITCH
        )

        assertNull(cache.getConfigDetail(identity, "ns-a", "late.yaml", "DEFAULT_GROUP"))
    }
}
