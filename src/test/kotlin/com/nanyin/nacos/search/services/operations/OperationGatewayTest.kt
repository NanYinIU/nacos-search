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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OperationGatewayTest {

    @Test
    fun `captured manual namespace target controls adapter calls results and identity scoped cache`() = runBlocking {
        val cache = InMemoryOperationCache()
        val adapter = ScriptedAdapter()
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter), cache)
        val startedTarget = anonymousTarget("dev", "https://dev.nacos.example", "team-manual")
        val laterUiSelection = anonymousTarget("prod", "https://prod.nacos.example", "other-team")

        val page = gateway.listSummaries(startedTarget, SummaryQuery(pageSize = 20), useCache = true).getOrThrow()
        val detail = gateway.readDetail(
            startedTarget,
            ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"),
            useCache = true
        ).getOrThrow()

        assertEquals(startedTarget, adapter.summaryTargets.single())
        assertEquals(startedTarget, adapter.detailTargets.single())
        assertEquals("team-manual", page.items.single().tenantId)
        assertEquals("from-dev", detail?.content)
        assertEquals(page, cache.getSummaries(startedTarget.context.identity, "team-manual", SummaryQuery(pageSize = 20).cacheKey()))
        assertEquals(detail, cache.getDetail(startedTarget.context.identity, "team-manual", "app.yaml", "DEFAULT_GROUP"))
        assertNull(cache.getSummaries(laterUiSelection.context.identity, "other-team", SummaryQuery(pageSize = 20).cacheKey()))
        assertNull(cache.getDetail(laterUiSelection.context.identity, "other-team", "app.yaml", "DEFAULT_GROUP"))
    }

    private fun anonymousTarget(profileId: String, endpointValue: String, namespaceId: String): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse(endpointValue).getOrThrow()
        val identity = AccessIdentity.ofProfile(
            profileId = profileId,
            accessRevision = 9,
            canonicalEndpoint = endpoint.value,
            resolvedGeneration = NacosApiGeneration.V1,
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )
        return OperationTarget(
            NacosOperationContext(
                identity = identity,
                endpoint = endpoint,
                credential = CredentialSnapshot(""),
                authMode = AuthMode.ANONYMOUS,
                profileRevision = 9,
                accessRevision = 9,
                resolvedGeneration = NacosApiGeneration.V1
            ),
            namespaceId
        )
    }

    private class ScriptedAdapter : ProtocolAdapter {
        val summaryTargets = mutableListOf<OperationTarget>()
        val detailTargets = mutableListOf<OperationTarget>()

        override suspend fun probe(target: OperationTarget): Result<Unit> = Result.success(Unit)

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage> {
            summaryTargets += target
            return Result.success(
                SummaryPage(
                    totalCount = 1,
                    pageNumber = query.pageNo,
                    pagesAvailable = 1,
                    items = listOf(ConfigurationSummary("app.yaml", "DEFAULT_GROUP", target.namespaceId, null, "yaml"))
                )
            )
        }

        override suspend fun readDetail(
            target: OperationTarget,
            coordinate: ConfigurationCoordinate
        ): Result<NacosConfiguration?> {
            detailTargets += target
            return Result.success(
                NacosConfiguration(coordinate.dataId, coordinate.group, target.namespaceId, "from-dev", "yaml")
            )
        }
    }
}
