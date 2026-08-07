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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Graded capability declarations (issue #95 / ADR-0048): dialects declare
 * coverage; the gateway and upper layers consume the declaration rather than
 * switching on API generation; unavailable ops stay distinct from auth/network.
 */
class GradedCapabilitiesTest {

    @Test
    fun `gateway exposes V1 and V3 coverage from dialect declarations`() {
        val gateway = OperationGateway(
            mapOf(
                NacosApiGeneration.V1 to DeclaringAdapter(ProtocolCapabilities.V1),
                NacosApiGeneration.V3 to DeclaringAdapter(ProtocolCapabilities.V3)
            )
        )

        assertEquals(ProtocolCapabilities.V1, gateway.capabilities(NacosApiGeneration.V1))
        assertEquals(ProtocolCapabilities.V3, gateway.capabilities(NacosApiGeneration.V3))
        assertEquals(
            SearchCoverage.partial(10, 10, "content search is coverage-limited"),
            searchCoverageFromCapability(
                gateway.capabilities(NacosApiGeneration.V1)!!.contentSearch,
                10,
                10,
                "content search is coverage-limited"
            )
        )
        assertEquals(
            SearchCoverage.complete(10, 10),
            searchCoverageFromCapability(
                gateway.capabilities(NacosApiGeneration.V3)!!.contentSearch,
                10,
                10,
                "unused"
            )
        )
        assertEquals(
            CapabilityCoverage.COMPLETE,
            gateway.capabilities(NacosApiGeneration.V1)!!.listCarriesBodies
        )
        assertEquals(
            CapabilityCoverage.UNAVAILABLE,
            gateway.capabilities(NacosApiGeneration.V3)!!.listCarriesBodies
        )
    }

    @Test
    fun `gateway returns CapabilityUnsupported when dialect declares history unavailable`() = runBlocking {
        val adapter = DeclaringAdapter(
            ProtocolCapabilities.NONE.copy(history = CapabilityCoverage.UNAVAILABLE)
        )
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))

        val error = gateway.listHistory(
            lockedTarget(NacosApiGeneration.V1),
            HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))
        ).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.CapabilityUnsupported::class.java, error)
        assertEquals(0, adapter.historyCalls)
    }

    @Test
    fun `gateway returns CapabilityUnsupported when dialect declares discovery unavailable`() = runBlocking {
        val adapter = DeclaringAdapter(
            ProtocolCapabilities.NONE.copy(namespaceDiscovery = CapabilityCoverage.UNAVAILABLE)
        )
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))

        val error = gateway.discoverNamespaces(lockedTarget(NacosApiGeneration.V1)).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.CapabilityUnsupported::class.java, error)
        assertEquals(0, adapter.discoveryCalls)
    }

    @Test
    fun `manual Namespace remains readable when discovery coverage is limited`() = runBlocking {
        val adapter = DeclaringAdapter(
            ProtocolCapabilities.NONE.copy(
                namespaceDiscovery = CapabilityCoverage.LIMITED
            ),
            discoveryResult = Result.success(
                listOf(DiscoveredNamespace("public", "public"))
            )
        )
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val target = lockedTarget(NacosApiGeneration.V1, "team-manual")

        val page = gateway.listSummaries(target, SummaryQuery()).getOrThrow().value
        assertEquals("team-manual", page.items.single().tenantId)

        val namespaces = gateway.discoverNamespaces(target).getOrThrow()
        assertEquals(listOf("public"), namespaces.map { it.namespaceId })
        assertEquals(1, adapter.discoveryCalls)
    }

    @Test
    fun `manual Namespace remains readable when discovery is unavailable`() = runBlocking {
        val adapter = DeclaringAdapter(
            ProtocolCapabilities.NONE.copy(
                namespaceDiscovery = CapabilityCoverage.UNAVAILABLE
            )
        )
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to adapter))
        val target = lockedTarget(NacosApiGeneration.V3, "team-manual")

        val page = gateway.listSummaries(target, SummaryQuery()).getOrThrow().value
        assertEquals("team-manual", page.items.single().tenantId)

        val discoveryError = gateway.discoverNamespaces(target).exceptionOrNull()
        assertInstanceOf(RemoteOperationError.CapabilityUnsupported::class.java, discoveryError)
    }

    @Test
    fun `connection diagnostic keeps manual Namespace when discovery is unavailable`() = runBlocking {
        val adapter = DeclaringAdapter(
            ProtocolCapabilities.V3.copy(namespaceDiscovery = CapabilityCoverage.UNAVAILABLE)
        )
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to adapter))
        val diagnostic = ConnectionDiagnostic(
            resolver = GenerationResolver(adapter, DeclaringAdapter(ProtocolCapabilities.V1)),
            gateway = gateway
        )

        val report = diagnostic.diagnose(
            DiagnosticSnapshot(
                endpoint = "https://nacos.example",
                apiPolicy = "V3",
                authStrategy = "ANONYMOUS",
                principal = "",
                secret = "",
                namespaceId = "team-manual"
            )
        )

        assertTrue(report.connected)
        assertTrue(report.manualNamespaceRequired)
        assertEquals("Connected. Manual namespace. Discovery unavailable.", report.summary)
        assertEquals(0, adapter.discoveryCalls)
    }

    @Test
    fun `authentication failure is not converted to CapabilityUnsupported`() = runBlocking {
        val adapter = DeclaringAdapter(
            ProtocolCapabilities.V1,
            historyResult = Result.failure(RemoteOperationError.Authentication(401)),
            discoveryResult = Result.failure(RemoteOperationError.Authentication(401))
        )
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val target = lockedTarget(NacosApiGeneration.V1)

        assertInstanceOf(
            RemoteOperationError.Authentication::class.java,
            gateway.listHistory(target, HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))).exceptionOrNull()
        )
        assertInstanceOf(
            RemoteOperationError.Authentication::class.java,
            gateway.discoverNamespaces(target).exceptionOrNull()
        )
    }

    @Test
    fun `authorization failure is not converted to CapabilityUnsupported`() = runBlocking {
        val adapter = DeclaringAdapter(
            ProtocolCapabilities.V1,
            historyResult = Result.failure(RemoteOperationError.Authorization(403)),
            discoveryResult = Result.failure(RemoteOperationError.Authorization(403))
        )
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val target = lockedTarget(NacosApiGeneration.V1)

        assertInstanceOf(
            RemoteOperationError.Authorization::class.java,
            gateway.listHistory(target, HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))).exceptionOrNull()
        )
        assertInstanceOf(
            RemoteOperationError.Authorization::class.java,
            gateway.discoverNamespaces(target).exceptionOrNull()
        )
    }

    @Test
    fun `network failure is not converted to CapabilityUnsupported`() = runBlocking {
        val network = RemoteOperationError.Connection(RuntimeException("down"))
        val adapter = DeclaringAdapter(
            ProtocolCapabilities.V1,
            historyResult = Result.failure(network),
            discoveryResult = Result.failure(network)
        )
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val target = lockedTarget(NacosApiGeneration.V1)

        assertInstanceOf(
            RemoteOperationError.Connection::class.java,
            gateway.listHistory(target, HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))).exceptionOrNull()
        )
        assertInstanceOf(
            RemoteOperationError.Connection::class.java,
            gateway.discoverNamespaces(target).exceptionOrNull()
        )
    }

    @Test
    fun `production V1 and V3 dialects retain history and discovery`() = runBlocking {
        val v1 = V1ProtocolAdapter(RecordingTransport(ProtocolResponse(200, V1_HISTORY)))
        val v3 = V3ProtocolAdapter(RecordingTransport(ProtocolResponse(200, V3_HISTORY)))
        assertEquals(CapabilityCoverage.COMPLETE, v1.capabilities.history)
        assertEquals(CapabilityCoverage.COMPLETE, v1.capabilities.namespaceDiscovery)
        assertEquals(CapabilityCoverage.COMPLETE, v3.capabilities.history)
        assertEquals(CapabilityCoverage.COMPLETE, v3.capabilities.namespaceDiscovery)

        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to v1, NacosApiGeneration.V3 to v3)
        )
        assertFalse(gateway.listHistory(
            lockedTarget(NacosApiGeneration.V1),
            HistoryQuery(ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))
        ).isFailure)
    }

    // ---- helpers ----

    private fun lockedTarget(
        generation: NacosApiGeneration,
        namespaceId: String = "public"
    ): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "caps",
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

    private class DeclaringAdapter(
        override val capabilities: ProtocolCapabilities,
        private val historyResult: Result<HistoryPage> = Result.success(
            HistoryPage(1, 1, 1, listOf(HistoryEntry("1", "app.yaml", "G", null, "yaml", "m", 1L, "PUBLISH")))
        ),
        private val discoveryResult: Result<List<DiscoveredNamespace>> = Result.success(emptyList())
    ) : ProtocolAdapter {
        var historyCalls = 0
        var discoveryCalls = 0

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
            Result.success(NacosConfiguration(coordinate.dataId, coordinate.group, target.namespaceId, "c", "yaml"))

        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))

        override suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage> {
            historyCalls++
            return historyResult
        }

        override suspend fun readHistoryDetail(target: OperationTarget, historyId: String) =
            Result.success(HistoryDetail(historyId, "app.yaml", "G", null, "c", "yaml", "m", 1L, "PUBLISH"))

        override suspend fun discoverNamespaces(target: OperationTarget): Result<List<DiscoveredNamespace>> {
            discoveryCalls++
            return discoveryResult
        }
    }

    private class RecordingTransport(
        private val response: ProtocolResponse
    ) : ProtocolTransport {
        override suspend fun execute(request: ProtocolRequest): ProtocolResponse = response
    }

    private companion object {
        const val V1_HISTORY =
            """{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"1","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"","md5":"m","lastModified":1,"type":"yaml","opType":"PUBLISH"}]}"""
        const val V3_HISTORY =
            """{"code":0,"message":"success","data":{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"1","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"public","md5":"m","lastModified":1,"type":"yaml","opType":"PUBLISH"}]}}"""
    }
}
