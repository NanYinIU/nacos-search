package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OperationGatewayHistoryTest {

    @Test
    fun `gateway dispatches listHistory to the V1 adapter implementing HistoryCapability`() = runBlocking {
        val adapter = StubHistoryAdapter()
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val target = v1Target()

        val page = gateway.listHistory(
            target,
            HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))
        ).getOrThrow()

        assertEquals(1, page.totalCount)
        assertEquals(1, adapter.listCalls)
    }

    @Test
    fun `gateway dispatches readHistoryDetail to the V1 adapter implementing HistoryCapability`() = runBlocking {
        val adapter = StubHistoryAdapter()
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val target = v1Target()

        val detail = gateway.readHistoryDetail(target, "123").getOrThrow()

        assertEquals("123", detail.id)
        assertEquals(1, adapter.detailCalls)
    }

    @Test
    fun `gateway returns CapabilityUnsupported when adapter does not implement HistoryCapability`() = runBlocking {
        val adapter = NonHistoryAdapter()
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val target = v1Target()

        val error = gateway.listHistory(
            target,
            HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))
        ).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.CapabilityUnsupported::class.java, error)
        Unit
    }

    @Test
    fun `gateway returns Unsupported when no adapter exists for the generation`() = runBlocking {
        val gateway = OperationGateway(emptyMap())
        val target = v1Target()

        val error = gateway.listHistory(
            target,
            HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))
        ).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Unsupported::class.java, error)
        Unit
    }

    @Test
    fun `gateway caches history pages in the HistoryMemoryCache`() = runBlocking {
        val adapter = StubHistoryAdapter()
        val historyCache = HistoryMemoryCache(maxPages = 10, maxDetails = 10)
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to adapter),
            historyCache = historyCache
        )
        val target = v1Target()
        val query = HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))

        gateway.listHistory(target, query).getOrThrow()
        // Second call should hit the cache, not the adapter
        gateway.listHistory(target, query).getOrThrow()

        assertEquals(1, adapter.listCalls)
    }

    @Test
    fun `gateway caches history details in the HistoryMemoryCache`() = runBlocking {
        val adapter = StubHistoryAdapter()
        val historyCache = HistoryMemoryCache(maxPages = 10, maxDetails = 10)
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to adapter),
            historyCache = historyCache
        )
        val target = v1Target()

        gateway.readHistoryDetail(target, "123").getOrThrow()
        gateway.readHistoryDetail(target, "123").getOrThrow()

        assertEquals(1, adapter.detailCalls)
    }

    @Test
    fun `gateway cache isolates history pages by identity`() = runBlocking {
        val adapter = StubHistoryAdapter()
        val historyCache = HistoryMemoryCache(maxPages = 10, maxDetails = 10)
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to adapter),
            historyCache = historyCache
        )
        val query = HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))

        gateway.listHistory(v1Target("profile-a"), query).getOrThrow()
        // Different identity — must not hit cache
        gateway.listHistory(v1Target("profile-b"), query).getOrThrow()

        assertEquals(2, adapter.listCalls)
    }

    @Test
    fun `gateway forceRefresh bypasses the history cache`() = runBlocking {
        val adapter = StubHistoryAdapter()
        val historyCache = HistoryMemoryCache(maxPages = 10, maxDetails = 10)
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to adapter),
            historyCache = historyCache
        )
        val target = v1Target()
        val query = HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))

        gateway.listHistory(target, query).getOrThrow()
        gateway.listHistory(target, query, forceRefresh = true).getOrThrow()

        assertEquals(2, adapter.listCalls)
    }

    // ---- helpers ----

    private fun v1Target(profileId: String = "p1"): OperationTarget {
        val endpoint = com.nanyin.nacos.search.models.CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = com.nanyin.nacos.search.settings.NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = profileId,
                accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = com.nanyin.nacos.search.settings.CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 1,
            accessRevision = 1,
            resolvedGeneration = NacosApiGeneration.V1
        )
        return OperationTarget(context, "public")
    }

    private class StubHistoryAdapter : ProtocolAdapter, HistoryCapability {
        var listCalls = 0
        var detailCalls = 0

        override suspend fun probe(target: OperationTarget) = Result.success(Unit)

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))

        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<NacosConfiguration?> =
            Result.success(null)

        override suspend fun publish(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> =
            Result.success(PublishOutcome.Written("true"))

        override suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage> {
            listCalls++
            return Result.success(
                HistoryPage(1, 1, 1, listOf(HistoryEntry("1", "app.yaml", "G", null, "yaml", "m1", 1000L, "PUBLISH")))
            )
        }

        override suspend fun readHistoryDetail(target: OperationTarget, historyId: String): Result<HistoryDetail> {
            detailCalls++
            return Result.success(HistoryDetail("123", "app.yaml", "G", null, "content", "yaml", "md5", 1000L, "PUBLISH"))
        }
    }

    private class NonHistoryAdapter : ProtocolAdapter {
        override suspend fun probe(target: OperationTarget) = Result.success(Unit)
        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))
        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<NacosConfiguration?> =
            Result.success(null)
        override suspend fun publish(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> =
            Result.success(PublishOutcome.Written("true"))
    }
}
