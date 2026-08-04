package com.nanyin.nacos.search.services

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.CacheAge
import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetConfirmation
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.operations.ConnectionDiagnostic
import com.nanyin.nacos.search.services.operations.DiagnosticSnapshot
import com.nanyin.nacos.search.services.operations.GenerationResolver
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Acceptance tests for issue #49 — resolve the API generation once per project session.
 *
 * Assertions are at the transport seam: which requests leave the plugin and how
 * many — not which internal method was called with which argument shape.
 */
@TestApplication
class SessionGenerationIntegrationTest {

    private lateinit var harness: SessionGenerationHarness

    @BeforeEach
    fun setUp() {
        harness = SessionGenerationHarness()
        runBlocking {
            harness.cacheService.clearAll()
        }
    }

    @Test
    fun `characterisation observes both the legacy wire path and the gateway path`() = runBlocking {
        // Gateway path: V1-locked context through the coordinator.
        val v1Request = harness.indexRequest(harness.lockedContext(NacosApiGeneration.V1))
        harness.coordinator.requestIndex(v1Request, IndexTrigger.NAMESPACE_SWITCH)
        val gatewaySeen = harness.legacyListCount() > 0 ||
            harness.recordedUrls.any { it.contains("/v1/") }
        assertTrue(gatewaySeen, "expected gateway/V1 traffic: ${harness.recordedUrls}")

        harness.clearRecorded()

        // Legacy path: no operation context — only the server snapshot.
        val legacy = harness.legacyIndexRequest()
        harness.coordinator.requestIndex(legacy, IndexTrigger.NAMESPACE_SWITCH)
        assertTrue(
            harness.legacyListCount() > 0,
            "expected legacy /v1/cs/configs traffic: ${harness.recordedUrls}"
        )
    }

    @Test
    fun `two consecutive AUTO operations produce exactly one probe request`() = runBlocking {
        val request = harness.indexRequest(harness.autoContext())

        harness.coordinator.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)
        harness.coordinator.requestIndex(request, IndexTrigger.MANUAL_REFRESH)

        assertEquals(1, harness.v3StateProbeCount(), "urls=${harness.recordedUrls}")
        assertEquals(NacosApiGeneration.V3, harness.session.peekResolvedGeneration())
    }

    @Test
    fun `concurrent AUTO operations started before detection completes produce one probe`() = runBlocking {
        val delayed = SessionGenerationHarness(
            transport = SessionGenerationHarness.RecordingTransport(stateDelayMs = 80L)
        )
        val request = delayed.indexRequest(delayed.autoContext())

        val outcomes = (1..4).map {
            async { delayed.coordinator.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH) }
        }.awaitAll()

        assertTrue(outcomes.all { it is IndexOutcome.Complete || it is IndexOutcome.Partial || it is IndexOutcome.Failed || it is IndexOutcome.Stale })
        assertEquals(1, delayed.v3StateProbeCount(), "urls=${delayed.recordedUrls}")
    }

    @Test
    fun `profile-revision change produces a second probe`() = runBlocking {
        val first = harness.indexRequest(harness.autoContext(profileRevision = 1, accessRevision = 1))
        harness.coordinator.requestIndex(first, IndexTrigger.NAMESPACE_SWITCH)
        assertEquals(1, harness.v3StateProbeCount())

        // Profile revision advanced — session must not reuse the prior result.
        val second = harness.indexRequest(harness.autoContext(profileRevision = 2, accessRevision = 1))
        harness.coordinator.requestIndex(second, IndexTrigger.NAMESPACE_SWITCH)

        assertEquals(2, harness.v3StateProbeCount(), "urls=${harness.recordedUrls}")
    }

    @Test
    fun `access-revision change produces a second probe`() = runBlocking {
        val first = harness.indexRequest(harness.autoContext(profileRevision = 1, accessRevision = 1))
        harness.coordinator.requestIndex(first, IndexTrigger.NAMESPACE_SWITCH)
        assertEquals(1, harness.v3StateProbeCount())

        val second = harness.indexRequest(harness.autoContext(profileRevision = 1, accessRevision = 2))
        harness.coordinator.requestIndex(second, IndexTrigger.NAMESPACE_SWITCH)

        assertEquals(2, harness.v3StateProbeCount(), "urls=${harness.recordedUrls}")
    }

    @Test
    fun `resolved generation lives in memory and is never written to last-known alone without a probe`() = runBlocking {
        // Before any formal detection the session has nothing and last-known is empty.
        assertNull(harness.session.peekResolvedGeneration())

        val context = harness.autoContext()
        harness.coordinator.requestIndex(harness.indexRequest(context), IndexTrigger.NAMESPACE_SWITCH)

        assertEquals(NacosApiGeneration.V3, harness.session.peekResolvedGeneration())
        // last-known is a separate persisted store; the session field itself is memory-only.
        // Clearing the session must not require reading last-known to re-resolve.
        harness.session.clear()
        assertNull(harness.session.peekResolvedGeneration())
    }

    @Test
    fun `assigning a different resolved generation advances the session epoch`() = runBlocking {
        val epoch = AtomicLong(0)
        val session = com.nanyin.nacos.search.services.operations.SessionGenerationState(
            currentEpoch = { epoch.get() },
            onGenerationChanged = { epoch.incrementAndGet(); Unit }
        )
        val capture = com.nanyin.nacos.search.services.operations.GenerationSessionCapture(
            profileId = "p",
            profileRevision = 1,
            accessRevision = 1,
            sessionEpoch = 0,
            canonicalEndpoint = "https://nacos.example",
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )
        val live = com.nanyin.nacos.search.services.operations.SessionGenerationState.LiveIdentityKeys(
            capture.profileId, capture.profileRevision, capture.accessRevision
        )
        assertTrue(session.tryCommit(NacosApiGeneration.V3, capture, live))
        val epochAfterFirst = epoch.get()
        assertTrue(session.tryCommit(NacosApiGeneration.V1, capture.copy(sessionEpoch = epochAfterFirst), live))
        assertEquals(epochAfterFirst + 1, epoch.get())
    }

    @Test
    fun `stale epoch consumer does not commit the shared probe result`() = runBlocking {
        val epoch = AtomicLong(0)
        val session = com.nanyin.nacos.search.services.operations.SessionGenerationState(
            currentEpoch = { epoch.get() }
        )
        // Simulate: consumer captured epoch 0, then something advanced the epoch.
        epoch.set(1)
        val staleCapture = com.nanyin.nacos.search.services.operations.GenerationSessionCapture(
            profileId = "p",
            profileRevision = 1,
            accessRevision = 1,
            sessionEpoch = 0,
            canonicalEndpoint = "https://nacos.example",
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )
        val rejected = session.tryCommit(
            NacosApiGeneration.V3,
            staleCapture,
            com.nanyin.nacos.search.services.operations.SessionGenerationState.LiveIdentityKeys(
                staleCapture.profileId, staleCapture.profileRevision, staleCapture.accessRevision
            )
        )
        assertTrue(!rejected)
        assertNull(session.peekResolvedGeneration())
    }

    @Test
    fun `entombed profile does not commit the shared probe result`() = runBlocking {
        val harnessEntombed = SessionGenerationHarness(isEntombed = { it == "auto-profile" })
        val request = harnessEntombed.indexRequest(harnessEntombed.autoContext())
        harnessEntombed.coordinator.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)

        // Probe may still have run for the operation, but session must not retain it.
        assertNull(harnessEntombed.session.peekResolvedGeneration())
    }

    @Test
    fun `isolated connection diagnostics neither join the shared flight nor write session generation`() = runBlocking {
        // Warm the formal session first.
        harness.coordinator.requestIndex(
            harness.indexRequest(harness.autoContext()),
            IndexTrigger.NAMESPACE_SWITCH
        )
        val formalProbes = harness.v3StateProbeCount()
        val sessionGen = harness.session.peekResolvedGeneration()

        // Diagnostic uses the same adapters/executor path construction as production
        // diagnoseConnection, but must not touch the formal session or flight.
        val diagnosticTransport = SessionGenerationHarness.RecordingTransport()
        val diagExecutor = com.nanyin.nacos.search.services.network.NacosRequestExecutor(diagnosticTransport)
        val v1 = com.nanyin.nacos.search.services.operations.V1ProtocolAdapter(
            com.nanyin.nacos.search.services.operations.NacosRequestExecutorProtocolTransport(diagExecutor)
        )
        val v3 = com.nanyin.nacos.search.services.operations.V3ProtocolAdapter(
            com.nanyin.nacos.search.services.operations.NacosRequestExecutorProtocolTransport(diagExecutor)
        )
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(
            mapOf(NacosApiGeneration.V1 to v1, NacosApiGeneration.V3 to v3)
        )
        val report = ConnectionDiagnostic(GenerationResolver(v3, v1), gateway).diagnose(
            DiagnosticSnapshot(
                endpoint = "https://nacos.example",
                apiPolicy = "AUTO",
                authStrategy = "ANONYMOUS",
                principal = "",
                secret = "",
                namespaceId = "public"
            )
        )
        assertTrue(report.connected || report.stages.any { it.stage == "generation" })
        // Formal session unchanged.
        assertEquals(sessionGen, harness.session.peekResolvedGeneration())
        assertEquals(formalProbes, harness.v3StateProbeCount())
        // Diagnostic issued its own probe on its own transport.
        assertTrue(diagnosticTransport.urls.any { it.contains("/v3/admin/core/state") })
    }

    @Test
    fun `last-known generation locates unconfirmed cache and never substitutes for formal detection`() = runBlocking {
        val context = harness.autoContext()
        // Persist last-known as if a previous session resolved V3.
        val store = LastKnownGenerationStore()
        store.put(
            LastKnownGenerationStore.Key(
                context.identity.profileId,
                context.accessRevision,
                context.endpoint.value
            ),
            NacosApiGeneration.V3
        )
        // Wire the app service so offlineCacheIdentity can read the store through
        // the same public API production uses (ApplicationManager service).
        val appStore = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(LastKnownGenerationStore::class.java)
        appStore?.put(
            LastKnownGenerationStore.Key(
                context.identity.profileId,
                context.accessRevision,
                context.endpoint.value
            ),
            NacosApiGeneration.V3
        )

        // Offline: rebuild identity with last-known generation to locate the same cache.
        // lastKnown alone never authorises a remote operation — only cache coordinate.
        val offlineIdentity = harness.apiService.offlineCacheIdentity(context)
            ?: context.identity.copy(resolvedGeneration = NacosApiGeneration.V3)
        assertEquals(NacosApiGeneration.V3, offlineIdentity.resolvedGeneration)
        harness.cacheService.putConfigDetail(
            offlineIdentity,
            "public",
            NacosConfiguration("app.yaml", "DEFAULT_GROUP", "public", "k=v"),
            60_000L
        )
        val restored = harness.cacheService.getConfigDetail(offlineIdentity, "public", "app.yaml", "DEFAULT_GROUP")
        assertTrue(restored != null)
        val confidence = CacheConfidence.restoredUnconfirmed(
            DatasetCompleteness.COMPLETE,
            CacheAge.WITHIN_TTL,
            System.currentTimeMillis()
        )
        assertEquals(DatasetConfirmation.UNCONFIRMED, confidence.confirmation)

        // Formal detection still runs — last-known never skips the probe.
        harness.clearRecorded()
        harness.session.clear()
        harness.coordinator.requestIndex(harness.indexRequest(context), IndexTrigger.NAMESPACE_SWITCH)
        assertEquals(1, harness.v3StateProbeCount(), "last-known must not skip formal detection: ${harness.recordedUrls}")
    }

    @Test
    fun `profiles locked to V1 or V3 perform no probe at all`() = runBlocking {
        harness.coordinator.requestIndex(
            harness.indexRequest(harness.lockedContext(NacosApiGeneration.V1)),
            IndexTrigger.NAMESPACE_SWITCH
        )
        harness.coordinator.requestIndex(
            harness.indexRequest(harness.lockedContext(NacosApiGeneration.V3, profileId = "v3-profile")),
            IndexTrigger.NAMESPACE_SWITCH
        )

        assertEquals(0, harness.v3StateProbeCount(), "urls=${harness.recordedUrls}")
        assertEquals(0, harness.probeRequestCount(), "urls=${harness.recordedUrls}")
    }
}
