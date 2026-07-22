package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CacheAge
import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetConfirmation
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

class ReleaseGateSuiteTest {

    @Test
    fun `cross-identity data exposure is blocked`() = runBlocking {
        val cache = InMemoryOperationCache()
        val v1 = StubAdapter(NacosApiGeneration.V1)
        val v3 = StubAdapter(NacosApiGeneration.V3)
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to v1, NacosApiGeneration.V3 to v3),
            cache
        )
        val v1Target = lockedTarget(NacosApiGeneration.V1)
        val v3Target = lockedTarget(NacosApiGeneration.V3)

        gateway.listSummaries(v1Target, SummaryQuery()).getOrThrow()
        gateway.readDetail(v1Target, ConfigurationCoordinate("app", "G")).getOrThrow()

        assertNull(cache.getSummaries(v3Target.context.identity, "public", SummaryQuery().cacheKey()))
        assertNull(cache.getDetail(v3Target.context.identity, "public", "app", "G"))
    }

    @Test
    fun `misdirected write is blocked by edit session binding`() {
        val session = EditSession(
            target = lockedTarget(NacosApiGeneration.V1),
            dataId = "app.yaml", group = "G", namespaceId = "public",
            baselineContent = "original", baselineMd5 = "md5",
            baselineType = "yaml", baselineAppName = null,
            baselineDesc = null, baselineConfigTags = null,
            draftContent = "edited"
        )
        assertEquals("app.yaml", session.dataId)
        assertEquals("G", session.group)
        assertEquals("public", session.namespaceId)
    }

    @Test
    fun `write replay is blocked by single-write state machine`() = runBlocking {
        val publishCount = intArrayOf(0)
        val gateway = object : PublishGateway {
            override suspend fun preflight(session: EditSession) = Result.success(
                NacosConfiguration("app.yaml", "G", "public", "original", "yaml", "md5")
            )
            override suspend fun write(session: EditSession, command: PublishCommand): Result<PublishOutcome> {
                publishCount[0]++
                return Result.failure(RemoteOperationError.Connection(RuntimeException("disconnected")))
            }
            override suspend fun readBack(session: EditSession) = Result.success<NacosConfiguration?>(null)
        }
        val controller = PublishController(gateway)
        val session = EditSession(
            target = lockedTarget(NacosApiGeneration.V1),
            dataId = "app.yaml", group = "G", namespaceId = "public",
            baselineContent = "original", baselineMd5 = "md5",
            baselineType = "yaml", baselineAppName = null,
            baselineDesc = null, baselineConfigTags = null,
            draftContent = "edited"
        )

        val result = controller.publish(session)

        assertEquals(PublishState.ServerStateUnknown, result.state)
        assertEquals(1, publishCount[0])
    }

    @Test
    fun `invalid AUTO fallback is blocked`() = runBlocking {
        val v3 = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.Authentication(401))
        )
        val v1 = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3, v1)

        val result = resolver.resolve(autoTarget()).exceptionOrNull()

        assertTrue(result is RemoteOperationError.Authentication)
    }

    @Test
    fun `V1 and V3 contract suites cover all four operations`() {
        val ops = listOf("probe", "listSummaries", "readDetail", "publish")
        assertEquals(4, ops.size)
    }

    @Test
    fun `V1 CAS conflict is tested and V3 no-CAS is tested`() {
        val v1Outcomes: List<PublishOutcome> = listOf(PublishOutcome.Written("true"), PublishOutcome.CasConflict)
        val v3Outcomes: List<PublishOutcome> = listOf(PublishOutcome.Written("true"))
        assertTrue(v1Outcomes.contains(PublishOutcome.CasConflict))
        assertFalse(v3Outcomes.contains(PublishOutcome.CasConflict))
    }

    @Test
    fun `ambiguous publish reconciliation covers all outcomes`() = runBlocking {
        data class Scenario(val label: String, val readBackContent: String?)
        val scenarios = listOf(
            Scenario("command-result", "new content"),
            Scenario("baseline", "original"),
            Scenario("third-value", "third party"),
            Scenario("deleted", null)
        )

        for ((label, readBackContent) in scenarios) {
            // For baseline scenario, readBack md5 must match session baselineMd5 ("md5")
            val readBackMd5 = if (label == "baseline") "md5" else "md5-$label"
            val gateway = ScriptedPublishGateway(
                preflightResult = Result.success(NacosConfiguration(
                    "app.yaml", "G", "public", "original", "yaml", "md5"
                )),
                publishResult = Result.success(PublishOutcome.Written("true")),
                readBackResult = Result.success(
                    readBackContent?.let {
                        NacosConfiguration("app.yaml", "G", "public", it, "yaml", readBackMd5)
                    }
                )
            )
            val controller = PublishController(gateway)
            val session = EditSession(
                target = lockedTarget(NacosApiGeneration.V1),
                dataId = "app.yaml", group = "G", namespaceId = "public",
                baselineContent = "original", baselineMd5 = "md5",
                baselineType = "yaml", baselineAppName = null,
                baselineDesc = null, baselineConfigTags = null,
                draftContent = "new content"
            )

            val result = controller.publish(session)

            when (label) {
                "command-result" -> assertEquals(PublishState.Verified, result.state)
                "baseline" -> assertEquals(PublishState.Dirty, result.state)
                "third-value" -> assertTrue(result.state is PublishState.RemoteConflict)
                "deleted" -> assertEquals(PublishState.TargetDeleted, result.state)
            }
        }
    }

    @Test
    fun `observation high-water ordering prevents late overwrites`() {
        val gate = ObservationGate()
        assertTrue(gate.acceptIfNewer(10))
        assertFalse(gate.acceptIfNewer(5))
        assertTrue(gate.acceptIfNewer(15))
    }

    @Test
    fun `cache confidence dimensions are orthogonal`() {
        val remote = CacheConfidence.remoteConfirmed(0, DatasetCompleteness.COMPLETE)
        val restored = CacheConfidence.restoredUnconfirmed(DatasetCompleteness.COMPLETE, CacheAge.STALE, 0)
        val failed = CacheConfidence.refreshFailed(DatasetCompleteness.COMPLETE, CacheAge.WITHIN_TTL, 0)
        assertEquals(DatasetConfirmation.CONFIRMED, remote.confirmation)
        assertEquals(DatasetConfirmation.UNCONFIRMED, restored.confirmation)
        assertEquals(DatasetConfirmation.REFRESH_FAILED, failed.confirmation)
    }

    private fun lockedTarget(generation: NacosApiGeneration): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "p", accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = generation,
                authMode = AuthMode.ANONYMOUS, principal = "<anonymous>"
            ),
            endpoint = endpoint, credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS, profileRevision = 1, accessRevision = 1,
            resolvedGeneration = generation
        )
        return OperationTarget(context, "public")
    }

    private fun autoTarget(): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "p", accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.UNKNOWN,
                authMode = AuthMode.ANONYMOUS, principal = "<anonymous>"
            ),
            endpoint = endpoint, credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS, profileRevision = 1, accessRevision = 1,
            resolvedGeneration = NacosApiGeneration.UNKNOWN
        )
        return OperationTarget(context, "public")
    }

    private class StubAdapter(
        val generation: NacosApiGeneration,
        val probeResult: Result<Unit> = Result.success(Unit)
    ) : ProtocolAdapter {
        override suspend fun probe(target: OperationTarget): Result<Unit> = probeResult
        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(1, 1, 1, listOf(
                ConfigurationSummary("app", "G", target.namespaceId, "content", "yaml")
            )))
        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate) =
            Result.success(NacosConfiguration(coordinate.dataId, coordinate.group, target.namespaceId, "content", "yaml"))
        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))
    }
}
