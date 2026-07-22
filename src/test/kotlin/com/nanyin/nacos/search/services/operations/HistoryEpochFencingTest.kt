package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.services.OperationFence
import com.nanyin.nacos.search.services.SessionEpochRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoryEpochFencingTest {

    @Test
    fun `history list result is dropped when session epoch advances before completion`() = runBlocking {
        val adapter = SlowHistoryAdapter(delayMillis = 50L)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val epochRegistry = SessionEpochRegistry()
        val fence = OperationFence(epochRegistry)
        val target = v1Target()

        val deferred = fence.launch(this, "project-1", target.context.identity) {
            gateway.listHistory(target, HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))).getOrThrow()
        }

        delay(10L)
        epochRegistry.bump("project-1")

        val outcome = deferred.await()
        assertFalse(outcome.published)
        assertNull(outcome.value)
    }

    @Test
    fun `history list result is published when session epoch is unchanged`() = runBlocking {
        val adapter = SlowHistoryAdapter(delayMillis = 10L)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val epochRegistry = SessionEpochRegistry()
        val fence = OperationFence(epochRegistry)
        val target = v1Target()

        val deferred = fence.launch(this, "project-1", target.context.identity) {
            gateway.listHistory(target, HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))).getOrThrow()
        }

        val outcome = deferred.await()
        assertTrue(outcome.published)
        assertNotNull(outcome.value)
    }

    @Test
    fun `history operations cannot merge across identities`() = runBlocking {
        val adapter = RecordingHistoryAdapter()
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to adapter),
            historyCache = HistoryMemoryCache(maxPages = 10, maxDetails = 10)
        )
        val query = HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))

        gateway.listHistory(v1Target("profile-a"), query).getOrThrow()
        gateway.listHistory(v1Target("profile-b"), query).getOrThrow()

        assertEquals(2, adapter.listCalls)
    }

    @Test
    fun `history operations cannot merge across namespaces`() = runBlocking {
        val adapter = RecordingHistoryAdapter()
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to adapter),
            historyCache = HistoryMemoryCache(maxPages = 10, maxDetails = 10)
        )
        val query = HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))
        val targetBase = v1Target()

        gateway.listHistory(OperationTarget(targetBase.context, "dev"), query).getOrThrow()
        gateway.listHistory(OperationTarget(targetBase.context, "prod"), query).getOrThrow()

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

    private class SlowHistoryAdapter(private val delayMillis: Long) : ProtocolAdapter, HistoryCapability {
        override suspend fun probe(target: OperationTarget) = Result.success(Unit)
        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))
        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<com.nanyin.nacos.search.models.NacosConfiguration?> =
            Result.success(null)
        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))

        override suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage> {
            delay(delayMillis)
            return Result.success(HistoryPage(1, 1, 1, listOf(
                HistoryEntry("1", "app.yaml", "G", null, "yaml", "m1", 1000L, "PUBLISH")
            )))
        }

        override suspend fun readHistoryDetail(target: OperationTarget, historyId: String): Result<HistoryDetail> {
            delay(delayMillis)
            return Result.success(HistoryDetail("1", "app.yaml", "G", null, "content", "yaml", "m1", 1000L, "PUBLISH"))
        }
    }

    private class RecordingHistoryAdapter : ProtocolAdapter, HistoryCapability {
        var listCalls = 0

        override suspend fun probe(target: OperationTarget) = Result.success(Unit)
        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))
        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<com.nanyin.nacos.search.models.NacosConfiguration?> =
            Result.success(null)
        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))

        override suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage> {
            listCalls++
            return Result.success(HistoryPage(1, 1, 1, listOf(
                HistoryEntry("1", "app.yaml", "G", null, "yaml", "m1", 1000L, "PUBLISH")
            )))
        }

        override suspend fun readHistoryDetail(target: OperationTarget, historyId: String): Result<HistoryDetail> {
            return Result.success(HistoryDetail("1", "app.yaml", "G", null, "content", "yaml", "m1", 1000L, "PUBLISH"))
        }
    }
}
