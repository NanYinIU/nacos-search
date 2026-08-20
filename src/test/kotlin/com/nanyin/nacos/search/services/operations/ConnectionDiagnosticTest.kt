package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionDiagnosticTest {

    @Test
    fun `locked V1 apiPolicy skips AUTO probing and uses V1`() = runBlocking {
        val v3 = CountingProbeAdapter(NacosApiGeneration.V3)
        val v1 = StubAdapter(NacosApiGeneration.V1, probeResult = Result.success(Unit))
        val resolver = GenerationResolver(v3, v1)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to v3, NacosApiGeneration.V1 to v1))
        val diagnostic = ConnectionDiagnostic(resolver, gateway)

        val report = diagnostic.diagnose(validAnonymousSnapshot().copy(apiPolicy = "V1"))

        assertTrue(report.connected)
        assertEquals(NacosApiGeneration.V1, report.stages[1].resolvedGeneration)
        assertEquals(0, v3.probeCount)
    }

    @Test
    fun `valid unapplied settings report connection success`() = runBlocking {
        val v3 = StubAdapter(NacosApiGeneration.V3, probeResult = Result.success(Unit))
        val v1 = StubAdapter(NacosApiGeneration.V1, probeResult = Result.success(Unit))
        val resolver = GenerationResolver(v3, v1)
    val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to v3, NacosApiGeneration.V1 to v1))
        val diagnostic = ConnectionDiagnostic(resolver, gateway)

        val report = diagnostic.diagnose(validAnonymousSnapshot())

        assertTrue(report.connected)
        assertFalse(report.manualNamespaceRequired)
        assertEquals(4, report.stages.size)
        assertTrue(report.stages.all { it.success })
        assertEquals(NacosApiGeneration.V3, report.stages[1].resolvedGeneration)
        assertEquals(emptyList<DiscoveredNamespace>(), report.discoveredNamespaces)
    }

    @Test
    fun `invalid local fields fail before any remote call`() = runBlocking {
        val v3 = CountingProbeAdapter(NacosApiGeneration.V3)
        val v1 = CountingProbeAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3, v1)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
        val diagnostic = ConnectionDiagnostic(resolver, gateway)

        val report = diagnostic.diagnose(DiagnosticSnapshot(
            endpoint = "",
            apiPolicy = "AUTO",
            authStrategy = "ANONYMOUS",
            principal = "",
            secret = "",
            namespaceId = "public"
        ))

        assertFalse(report.connected)
        assertEquals(1, report.stages.size)
        assertEquals("local_validation", report.stages[0].stage)
        assertEquals(0, v3.probeCount)
    }

    @Test
    fun `discovery denial reports connected with manual namespace`() = runBlocking {
        val v3 = StubAdapter(NacosApiGeneration.V3, probeResult = Result.success(Unit))
        val v1 = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3, v1)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
        val diagnostic = ConnectionDiagnostic(
            resolver, gateway,
            discoveryProbe = { Result.failure(RemoteOperationError.Authorization(403)) }
        )

        val report = diagnostic.diagnose(validAnonymousSnapshot())

        assertTrue(report.connected)
        assertTrue(report.manualNamespaceRequired)
        assertEquals(emptyList<DiscoveredNamespace>(), report.discoveredNamespaces)
    }

    @Test
    fun `configured namespace permission denial still discovers namespaces`() = runBlocking {
        val discovered = listOf(DiscoveredNamespace("ns-1", "Team One"))
        val v3 = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.success(Unit),
            summariesResult = Result.failure(RemoteOperationError.Authorization(403)),
            discovered = discovered
        )
        val diagnostic = ConnectionDiagnostic(
            GenerationResolver(v3, StubAdapter(NacosApiGeneration.V1)),
            OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
        )

        val report = diagnostic.diagnose(
            validAnonymousSnapshot().copy(apiPolicy = "V3", namespaceId = "secret-ns")
        )

        assertFalse(report.connected)
        assertEquals("discovery", report.stages.last().stage)
        assertTrue(report.stages.single { it.stage == "discovery" }.success)
        assertEquals(discovered, report.discoveredNamespaces)
        assertEquals("Permission denied for namespace secret-ns", report.summary)
    }

    @Test
    fun `configured namespace permission denial without options is not connection failed`() = runBlocking {
        val v3 = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.success(Unit),
            summariesResult = Result.failure(RemoteOperationError.Authorization(403))
        )
        val diagnostic = ConnectionDiagnostic(
            GenerationResolver(v3, StubAdapter(NacosApiGeneration.V1)),
            OperationGateway(mapOf(NacosApiGeneration.V3 to v3)),
            discoveryProbe = { Result.failure(RemoteOperationError.Authorization(403)) }
        )

        val report = diagnostic.diagnose(
            validAnonymousSnapshot().copy(apiPolicy = "V3", namespaceId = "secret-ns")
        )

        assertFalse(report.connected)
        assertEquals("Permission denied for namespace secret-ns", report.summary)
        assertEquals(emptyList<DiscoveredNamespace>(), report.discoveredNamespaces)
    }

    @Test
    fun `configured namespace transport failure stays connection failed after discovery`() = runBlocking {
        val v3 = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.success(Unit),
            summariesResult = Result.failure(RemoteOperationError.Connection(RuntimeException("down")))
        )
        val diagnostic = ConnectionDiagnostic(
            GenerationResolver(v3, StubAdapter(NacosApiGeneration.V1)),
            OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
        )

        val report = diagnostic.diagnose(validAnonymousSnapshot().copy(apiPolicy = "V3"))

        assertFalse(report.connected)
        assertEquals("Connection failed", report.summary)
        assertEquals("discovery", report.stages.last().stage)
    }

    @Test
    fun `authentication failure during generation resolution is reported sanitized`() = runBlocking {
        val v3 = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.Authentication(401))
        )
        val v1 = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3, v1)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
        val diagnostic = ConnectionDiagnostic(resolver, gateway)

        val report = diagnostic.diagnose(validAnonymousSnapshot())

        assertFalse(report.connected)
        assertEquals("Authentication failed", report.stages[1].sanitizedFailure)
        assertEquals("Authentication failed", report.summary)
        assertTrue(report.stages.none { it.stage == "discovery" })
    }

    @Test
    fun `equivalent classified errors produce the same sanitized diagnostic finding`() = runBlocking {
        data class Case(val error: RemoteOperationError, val finding: String)
        val cases = listOf(
            Case(RemoteOperationError.Authentication(401), "Authentication failed"),
            Case(RemoteOperationError.InvalidOrExpiredNacosPasswordToken(403), "Authentication failed"),
            Case(RemoteOperationError.Authorization(403), "Permission denied"),
            Case(RemoteOperationError.Protocol("x"), "Protocol error"),
            Case(RemoteOperationError.Connection(RuntimeException("n")), "Connection failed"),
            Case(RemoteOperationError.GenerationUnsupported("gone"), "Generation not supported"),
            Case(RemoteOperationError.Cancelled(), "Cancelled")
        )
        for (case in cases) {
            // Locked V3 skips AUTO probing so GenerationUnsupported is not
            // consumed as a fallback signal; the finding is observed on the
            // namespace_read stage for every classified error type.
            val v3 = StubAdapter(
                NacosApiGeneration.V3,
                summariesResult = Result.failure(case.error)
            )
            val diagnostic = ConnectionDiagnostic(
                GenerationResolver(v3, StubAdapter(NacosApiGeneration.V1)),
                OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
            )
            val report = diagnostic.diagnose(validAnonymousSnapshot().copy(apiPolicy = "V3"))
            val readStage = report.stages.single { it.stage == "namespace_read" }
            assertEquals(case.finding, readStage.sanitizedFailure, case.error::class.simpleName)
        }
    }

    @Test
    fun `cancellation during generation resolution is reported as Cancelled not connection failure`() = runBlocking {
        val v3 = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.Cancelled("probe cancelled"))
        )
        val diagnostic = ConnectionDiagnostic(
            GenerationResolver(v3, StubAdapter(NacosApiGeneration.V1)),
            OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
        )

        val report = diagnostic.diagnose(validAnonymousSnapshot())

        assertFalse(report.connected)
        assertEquals("Cancelled", report.stages[1].sanitizedFailure)
    }

    @Test
    fun `CancellationException from probe propagates and is not remapped to connection failure`() = runBlocking {
        val v3 = object : ProtocolAdapter {
            override val capabilities = ProtocolCapabilities.NONE
            override suspend fun probe(target: OperationTarget): Result<Unit> {
                throw kotlinx.coroutines.CancellationException("diagnostic cancelled")
            }
            override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
                Result.success(SummaryPage(0, 1, 0, emptyList()))
            override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate) =
                Result.success(null)
            override suspend fun publish(target: OperationTarget, command: PublishCommand) =
                Result.success(PublishOutcome.Written("true"))
        }
        val diagnostic = ConnectionDiagnostic(
            GenerationResolver(v3, StubAdapter(NacosApiGeneration.V1)),
            OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
        )

        try {
            diagnostic.diagnose(validAnonymousSnapshot())
            org.junit.jupiter.api.Assertions.fail("expected CancellationException")
        } catch (error: kotlinx.coroutines.CancellationException) {
            assertEquals("diagnostic cancelled", error.message)
        }
    }

    @Test
    fun `diagnostic never mutates shared state`() = runBlocking {
        val cache = InMemoryOperationCache()
        val v3 = StubAdapter(NacosApiGeneration.V3)
        val v1 = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3, v1)
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V3 to v3, NacosApiGeneration.V1 to v1),
            cache
        )
        val diagnostic = ConnectionDiagnostic(resolver, gateway)

        diagnostic.diagnose(validAnonymousSnapshot())

        // The diagnostic uses useCache = false, so nothing should be cached.
        assertNull(cache.getSummaries(v3.let { it.generation }.let { gen ->
            com.nanyin.nacos.search.models.AccessIdentity.ofProfile(
                "diagnostic", 0, "https://nacos.example", gen,
                com.nanyin.nacos.search.settings.AuthMode.ANONYMOUS, "<anonymous>"
            )
        }, "public", SummaryQuery(pageSize = 1).cacheKey()))
    }

    @Test
    fun `report summary describes connected manual namespace state`() = runBlocking {
        val v3 = StubAdapter(NacosApiGeneration.V3, probeResult = Result.success(Unit))
        val resolver = GenerationResolver(v3, StubAdapter(NacosApiGeneration.V1))
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
        val diagnostic = ConnectionDiagnostic(
            resolver, gateway,
            discoveryProbe = { Result.failure(RemoteOperationError.Authorization(403)) }
        )

        val report = diagnostic.diagnose(validAnonymousSnapshot())

        assertEquals("Connected. Manual namespace. Discovery unavailable.", report.summary)
    }

    @Test
    fun `chooser discovery does not read the configured namespace`() = runBlocking {
        val discovered = listOf(DiscoveredNamespace("ns-1", "Team One"))
        val v3 = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.success(Unit),
            discovered = discovered
        )
        val diagnostic = ConnectionDiagnostic(
            GenerationResolver(v3, StubAdapter(NacosApiGeneration.V1)),
            OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
        )

        val result = diagnostic.discover(validAnonymousSnapshot().copy(apiPolicy = "V3"))

        assertEquals(discovered, result.getOrThrow())
        assertEquals(0, v3.listSummariesCount)
        assertEquals(0, v3.probeCount)
    }

    @Test
    fun `chooser discovery fails locally on a blank endpoint without remote calls`() = runBlocking {
        val v3 = CountingProbeAdapter(NacosApiGeneration.V3)
        val diagnostic = ConnectionDiagnostic(
            GenerationResolver(v3, CountingProbeAdapter(NacosApiGeneration.V1)),
            OperationGateway(mapOf(NacosApiGeneration.V3 to v3))
        )

        val result = diagnostic.discover(
            DiagnosticSnapshot(
                endpoint = "",
                apiPolicy = "V3",
                authStrategy = "ANONYMOUS",
                principal = "",
                secret = "",
                namespaceId = "public"
            )
        )

        assertTrue(result.isFailure)
        assertEquals(0, v3.probeCount)
    }

    @Test
    fun `chooser discovery failure is a result failure not a connection diagnostic`() = runBlocking {
        val v3 = StubAdapter(NacosApiGeneration.V3, probeResult = Result.success(Unit))
        val diagnostic = ConnectionDiagnostic(
            GenerationResolver(v3, StubAdapter(NacosApiGeneration.V1)),
            OperationGateway(mapOf(NacosApiGeneration.V3 to v3)),
            discoveryProbe = { Result.failure(RemoteOperationError.Connection(RuntimeException("down"))) }
        )

        val result = diagnostic.discover(validAnonymousSnapshot().copy(apiPolicy = "V3"))

        assertTrue(result.isFailure)
        assertEquals(0, v3.listSummariesCount)
    }

    // ---- helpers ----

    private fun validAnonymousSnapshot() = DiagnosticSnapshot(
        endpoint = "https://nacos.example",
        apiPolicy = "AUTO",
        authStrategy = "ANONYMOUS",
        principal = "",
        secret = "",
        namespaceId = "public"
    )

    private class StubAdapter(
        val generation: NacosApiGeneration,
        val probeResult: Result<Unit> = Result.success(Unit),
        val summariesResult: Result<SummaryPage> = Result.success(SummaryPage(0, 1, 0, emptyList())),
        val discovered: List<DiscoveredNamespace> = emptyList()
    ) : ProtocolAdapter {
        var probeCount = 0
        var listSummariesCount = 0

        override val capabilities = ProtocolCapabilities.NONE.copy(
            namespaceDiscovery = CapabilityCoverage.COMPLETE
        )

        override suspend fun probe(target: OperationTarget): Result<Unit> {
            probeCount++
            return probeResult
        }

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage> {
            listSummariesCount++
            return summariesResult.fold(
                onSuccess = { Result.success(it.copy(pageNumber = query.pageNo)) },
                onFailure = { Result.failure(it) }
            )
        }

        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate) =
            Result.success(null)
        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))

        override suspend fun discoverNamespaces(target: OperationTarget) =
            Result.success(discovered)
    }

    private class CountingProbeAdapter(
        val generation: NacosApiGeneration
    ) : ProtocolAdapter {
        var probeCount = 0

        override val capabilities = ProtocolCapabilities.NONE.copy(
            namespaceDiscovery = CapabilityCoverage.COMPLETE
        )

        override suspend fun probe(target: OperationTarget): Result<Unit> {
            probeCount++
            return Result.success(Unit)
        }

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, query.pageNo, 0, emptyList()))

        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate) =
            Result.success(null)
        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))

        override suspend fun discoverNamespaces(target: OperationTarget) =
            Result.success(emptyList<DiscoveredNamespace>())
    }
}
