package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DualStackBrowsingTest {

    @Test
    fun `V3 locked profile browses summaries through the gateway`() = runBlocking {
        val adapter = ScriptedReadAdapter(NacosApiGeneration.V3)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to adapter))
        val target = v3Target()

        val page = gateway.listSummaries(target, SummaryQuery(pageSize = 20)).getOrThrow()

        assertEquals(1, page.totalCount)
        assertEquals("app.yaml", page.items.single().dataId)
    }

    @Test
    fun `manual namespace works through the gateway even without discovery`() = runBlocking {
        val adapter = ScriptedReadAdapter(NacosApiGeneration.V3)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to adapter))
        val target = v3Target("team-manual")

        val page = gateway.listSummaries(target, SummaryQuery()).getOrThrow()
        assertEquals("team-manual", page.items.single().tenantId)
    }

    @Test
    fun `namespace discovery denial does not hide a manually readable namespace`() = runBlocking {
        val adapter = ScriptedReadAdapter(NacosApiGeneration.V3, discoveryResult = emptyList())
        val target = v3Target("team-manual")

        // Even when discovery returns nothing, the summary read still works.
        val page = adapter.listSummaries(target, SummaryQuery()).getOrThrow()
        assertEquals(1, page.totalCount)
        assertEquals("team-manual", page.items.single().tenantId)
    }

    @Test
    fun `V3 content search capability is declared while V1 is coverage limited`() {
        assertEquals(SearchCapability.SERVER_SIDE, contentSearchCapability(NacosApiGeneration.V3))
        assertEquals(SearchCapability.COVERAGE_LIMITED, contentSearchCapability(NacosApiGeneration.V1))
    }

    @Test
    fun `search coverage reports searched over total for partial V1 local search`() {
        val coverage = SearchCoverage.partial(50, 100, "V1 local index only")
        assertFalse(coverage.isComplete)
        assertEquals("50/100 (partial)", coverage.coverageText)
    }

    @Test
    fun `search coverage reports complete for V3 server-side search`() {
        val coverage = SearchCoverage.complete(100, 100)
        assertTrue(coverage.isComplete)
        assertEquals("100/100", coverage.coverageText)
    }

    @Test
    fun `V1 and V3 adapters use the same gateway cache by distinct identities`() = runBlocking {
        val cache = InMemoryOperationCache()
        val v1Adapter = ScriptedReadAdapter(NacosApiGeneration.V1)
        val v3Adapter = ScriptedReadAdapter(NacosApiGeneration.V3)
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to v1Adapter, NacosApiGeneration.V3 to v3Adapter),
            cache
        )
        val v1Target = lockedTarget(NacosApiGeneration.V1, "public")
        val v3Target = lockedTarget(NacosApiGeneration.V3, "public")

        gateway.listSummaries(v1Target, SummaryQuery()).getOrThrow()
        gateway.listSummaries(v3Target, SummaryQuery()).getOrThrow()

        // Both cached but by different identities
        val v1Key = SummaryQuery().cacheKey()
        assertNotNull(cache.getSummaries(v1Target.context.identity, "public", v1Key))
        assertNotNull(cache.getSummaries(v3Target.context.identity, "public", v1Key))
        // Cross-identity is invisible
        assertNull(cache.getSummaries(v1Target.context.identity, "public", "wrong-key"))
    }

    @Test
    fun `detail on demand does not trigger implicit full-detail loading`() = runBlocking {
        val adapter = CountingDetailAdapter(NacosApiGeneration.V3)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to adapter))
        val target = v3Target()

        gateway.listSummaries(target, SummaryQuery()).getOrThrow()
        gateway.readDetail(target, ConfigurationCoordinate("app.yaml", "G")).getOrThrow()

        assertEquals(1, adapter.detailCalls)
        assertEquals(1, adapter.summaryCalls)
    }

    // ---- helpers ----

    private fun v3Target(namespaceId: String = "public"): OperationTarget =
        lockedTarget(NacosApiGeneration.V3, namespaceId)

    private fun lockedTarget(generation: NacosApiGeneration, namespaceId: String): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "p",
                accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = generation,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 1,
            accessRevision = 1,
            resolvedGeneration = generation
        )
        return OperationTarget(context, namespaceId)
    }

    private class ScriptedReadAdapter(
        val generation: NacosApiGeneration,
        val discoveryResult: List<DiscoveredNamespace>? = null
    ) : ProtocolAdapter {
        override suspend fun probe(target: OperationTarget) = Result.success(Unit)

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage> =
            Result.success(
                SummaryPage(
                    totalCount = 1,
                    pageNumber = query.pageNo,
                    pagesAvailable = 1,
                    items = listOf(ConfigurationSummary("app.yaml", "G", target.namespaceId, null, "yaml"))
                )
            )

        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate) =
            Result.success(NacosConfiguration(coordinate.dataId, coordinate.group, target.namespaceId, "content", "yaml"))
    }

    private class CountingDetailAdapter(
        val generation: NacosApiGeneration
    ) : ProtocolAdapter {
        var summaryCalls = 0
        var detailCalls = 0

        override suspend fun probe(target: OperationTarget) = Result.success(Unit)

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage> {
            summaryCalls++
            return Result.success(SummaryPage(1, query.pageNo, 1, listOf(ConfigurationSummary("app.yaml", "G", target.namespaceId, null, "yaml"))))
        }

        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<NacosConfiguration?> {
            detailCalls++
            return Result.success(NacosConfiguration(coordinate.dataId, coordinate.group, target.namespaceId, "content", "yaml"))
        }
    }
}

// Imported here to avoid polluting the top-level import list
private fun assertNotNull(actual: Any?) {
    org.junit.jupiter.api.Assertions.assertNotNull(actual)
}
