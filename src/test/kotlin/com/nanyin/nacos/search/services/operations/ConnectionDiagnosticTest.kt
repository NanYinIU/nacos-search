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
            discoveryProbe = { Result.failure<Unit>(RemoteOperationError.Authorization(403)) }
        )

        val report = diagnostic.diagnose(validAnonymousSnapshot())

        assertTrue(report.connected)
        assertTrue(report.manualNamespaceRequired)
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
            discoveryProbe = { Result.failure<Unit>(RemoteOperationError.Authorization(403)) }
        )

        val report = diagnostic.diagnose(validAnonymousSnapshot())

        assertEquals("Connected. Manual namespace. Discovery unavailable.", report.summary)
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
        val probeResult: Result<Unit> = Result.success(Unit)
    ) : ProtocolAdapter {
        var probeCount = 0

        override suspend fun probe(target: OperationTarget): Result<Unit> {
            probeCount++
            return probeResult
        }

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, query.pageNo, 0, emptyList()))

        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate) =
            Result.success(null)
        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))
    }

    private class CountingProbeAdapter(
        val generation: NacosApiGeneration
    ) : ProtocolAdapter {
        var probeCount = 0

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
    }
}
