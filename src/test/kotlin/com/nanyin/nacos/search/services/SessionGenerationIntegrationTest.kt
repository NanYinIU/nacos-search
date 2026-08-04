package com.nanyin.nacos.search.services

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.CacheAge
import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetConfirmation
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.operations.DiagnosticSnapshot
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `V1 index load uses generation-one paths and V3 index load does not`() = runBlocking {
        // V1-locked profile: generation-one requests only.
        val v1Request = harness.indexRequest(harness.lockedContext(NacosApiGeneration.V1))
        harness.coordinator.requestIndex(v1Request, IndexTrigger.NAMESPACE_SWITCH)
        assertTrue(
            harness.v1ListCount() > 0,
            "expected V1 gateway list traffic: ${harness.recordedUrls}"
        )
        assertEquals(
            0,
            harness.gatewayV3ListCount(),
            "V1 profile must not hit V3 list: ${harness.recordedUrls}"
        )

        harness.clearRecorded()

        // V3-locked profile: no generation-one path segments on index load.
        val v3Request = harness.indexRequest(
            harness.lockedContext(NacosApiGeneration.V3, profileId = "v3-profile")
        )
        harness.coordinator.requestIndex(v3Request, IndexTrigger.NAMESPACE_SWITCH)
        assertTrue(
            harness.gatewayV3ListCount() > 0,
            "expected V3 gateway list traffic: ${harness.recordedUrls}"
        )
        assertEquals(
            0,
            harness.generationOneRequestCount(),
            "V3 index load must not contain generation-one paths: ${harness.recordedUrls}"
        )
    }

    @Test
    fun `AUTO profile that resolves to V3 issues no generation-one index requests`() = runBlocking {
        val request = harness.indexRequest(harness.autoContext())
        harness.coordinator.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)

        assertEquals(NacosApiGeneration.V3, harness.session.peekResolvedGeneration())
        assertTrue(
            harness.gatewayV3ListCount() > 0,
            "AUTO→V3 must list via V3: ${harness.recordedUrls}"
        )
        // Probe uses /v3/admin/core/state (not generation-one). Index lists use V3.
        // No /v1/ path segment may appear for the index load itself.
        val nonProbeGenerationOne = harness.recordedUrls.filter {
            (it.contains("/v1/") || it.contains("/nacos/v1/")) &&
                !it.contains("/v3/")
        }
        assertTrue(
            nonProbeGenerationOne.isEmpty(),
            "AUTO→V3 index load must not contain generation-one paths: $nonProbeGenerationOne"
        )
    }

    @Test
    fun `complete namespace index write carries an observation sequence`() = runBlocking {
        val before = harness.apiService.operationGateway().currentObservationSequence()
        val request = harness.indexRequest(harness.lockedContext(NacosApiGeneration.V1))
        val outcome = harness.coordinator.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)
        assertTrue(
            outcome is IndexOutcome.Complete,
            "expected a complete index load, got $outcome (urls=${harness.recordedUrls})"
        )
        val after = harness.apiService.operationGateway().currentObservationSequence()
        assertTrue(
            after > before,
            "namespace index write must carry an observation sequence (before=$before after=$after)"
        )
    }

    /**
     * ADR-0020: the sequence is taken when the load *starts*, so a load that
     * started earlier must not overwrite one that started later, even when it
     * finishes afterwards. Two loads for the same identity+namespace can overlap
     * once a SEARCH trigger hits its 15-second cutoff and drops its in-flight
     * entry while the job keeps running.
     *
     * A competing load is simulated at the transport seam — it starts, and wins
     * the high-water mark, while this load is still fetching pages. This load's
     * write must then be discarded rather than delete the newer index.
     *
     * Regression guard: taking the sequence at write time instead would hand
     * this load the higher number, and the older view would win.
     */
    @Test
    fun `an index load that started earlier does not overwrite a newer index`() = runBlocking {
        lateinit var competing: SessionGenerationHarness
        val transport = SessionGenerationHarness.RecordingTransport(
            onRequest = { url ->
                if (url.contains("/cs/config")) {
                    val gateway = competing.apiService.operationGateway()
                    val newer = gateway.beginNamespaceIndexObservation()
                    gateway.commitNamespaceIndexWrite(
                        competing.indexTarget(competing.lockedContext(NacosApiGeneration.V1)),
                        newer
                    )
                }
            }
        )
        competing = SessionGenerationHarness(transport)
        competing.cacheService.clearAll()

        val context = competing.lockedContext(NacosApiGeneration.V1)
        val outcome = competing.coordinator.requestIndex(
            competing.indexRequest(context),
            IndexTrigger.NAMESPACE_SWITCH
        )

        assertTrue(
            outcome is IndexOutcome.Stale,
            "expected the obsolete write to be discarded, got $outcome"
        )
        assertNull(
            competing.cacheService.getNamespaceIndex(context.identity, "public"),
            "an older observation must not land in the namespace index"
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

    /**
     * Entered through production `diagnoseConnection` rather than a hand-built
     * adapter stack: ADR-0022 constrains the wiring, so a test that assembles
     * its own isolated gateway proves nothing about the one that ships.
     */
    @Test
    fun `production connection diagnostics neither join the shared flight nor write session generation`() = runBlocking {
        // Warm the formal session first.
        harness.coordinator.requestIndex(
            harness.indexRequest(harness.autoContext()),
            IndexTrigger.NAMESPACE_SWITCH
        )
        val formalProbes = harness.v3StateProbeCount()
        val sessionGen = harness.session.peekResolvedGeneration()

        val report = harness.apiService.diagnoseConnection(
            DiagnosticSnapshot(
                endpoint = "https://nacos.example",
                apiPolicy = "AUTO",
                authStrategy = "ANONYMOUS",
                principal = "",
                secret = "",
                namespaceId = "public"
            )
        )

        assertTrue(report.connected, "stages=${report.stages} urls=${harness.recordedUrls}")
        // Formal session unchanged — the diagnostic resolved a generation for itself alone.
        assertEquals(sessionGen, harness.session.peekResolvedGeneration())
        // It did not join the shared probe flight: joining would have reused the
        // formal result and issued no probe of its own.
        assertTrue(
            harness.v3StateProbeCount() > formalProbes,
            "diagnostic must probe independently: urls=${harness.recordedUrls}"
        )
    }

    /**
     * ADR-0022: a diagnostic runs on temporary authentication state and modifies
     * no authentication registry.
     *
     * A NACOS_PASSWORD diagnostic used to authenticate through
     * `NacosAuthService.getValidAccessToken` and therefore through
     * `AuthenticationSessionRegistry`, which records a flight lock and an
     * invalidation epoch under the synthetic "diagnostic" identity — and does so
     * even when the login itself fails, since the lock is taken before the
     * attempt. Tracked identities are the assertion because that is exactly the
     * residue the registry keeps.
     *
     * The credential must be non-blank: `V1ProtocolAdapter.validate` rejects a
     * NACOS_PASSWORD target with an empty secret before authentication is ever
     * reached, which would make this pass no matter which authenticator is
     * wired. The endpoint is a closed local port so the login attempt is
     * refused immediately instead of reaching any network.
     */
    @Test
    fun `NACOS_PASSWORD diagnostics leave no state in the shared authentication registry`() = runBlocking {
        val authService = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(NacosAuthService::class.java)
        val before = authService.v1SessionIdentities()

        val report = harness.apiService.diagnoseConnection(
            DiagnosticSnapshot(
                endpoint = "http://127.0.0.1:1",
                apiPolicy = "V1",
                authStrategy = "NACOS_PASSWORD",
                principal = "admin",
                secret = "unapplied-draft-secret",
                namespaceId = "public"
            )
        )

        assertEquals(
            before,
            authService.v1SessionIdentities(),
            "a diagnostic must leave no authentication state behind"
        )
        // It did reach V1 authentication rather than stopping earlier: the read
        // stage is the one that failed, and it failed for want of a token.
        assertFalse(report.connected, "stages=${report.stages}")
        assertEquals("namespace_read", report.stages.last().stage)
    }

    /**
     * `diagnoseConnection` runs on `RequestPolicy.DIAGNOSTIC`, not the ordinary
     * interactive budget. The discriminator is wall-clock budget alone: the
     * first probe burns 20s of the injected clock before failing retriably, so
     * only a budget wider than INTERACTIVE's 15s leaves room for the retry that
     * the attempt count (identical across both profiles) already permits.
     */
    @Test
    fun `connection diagnostics run on the longer diagnostic budget`() = runBlocking {
        var now = 0L
        var stateProbes = 0
        val transport = SessionGenerationHarness.RecordingTransport(
            onRequest = { url ->
                if (url.contains("/v3/admin/core/state")) {
                    stateProbes++
                    if (stateProbes == 1) {
                        now += 20_000
                        throw NacosRequestError.Server(503, "")
                    }
                }
            }
        )
        val apiService = NacosApiService(
            executorOverride = NacosRequestExecutor(transport, clock = { now }, jitterProvider = { 0L })
        )

        val report = apiService.diagnoseConnection(
            DiagnosticSnapshot(
                endpoint = "https://nacos.example",
                apiPolicy = "AUTO",
                authStrategy = "ANONYMOUS",
                principal = "",
                secret = "",
                namespaceId = "public"
            )
        )

        // The failed probe was retried immediately on the same seam, not surfaced
        // and re-issued by a later stage: the first two requests are both probes.
        assertEquals(
            listOf(STATE_PROBE_URL, STATE_PROBE_URL),
            transport.urls.take(2),
            "diagnostic must still have budget to retry: urls=${transport.urls}"
        )
        // Under the 15s interactive budget the retry is out of time, the
        // generation stage fails, and the diagnostic reports a dead connection.
        assertTrue(report.connected, "stages=${report.stages}")
        assertEquals(NacosApiGeneration.V3, report.stages[1].resolvedGeneration)
    }

    private companion object {
        const val STATE_PROBE_URL = "https://nacos.example/nacos/v3/admin/core/state"
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

    /**
     * ADR-0021: retry budget is classified by operation kind at the transport
     * seam, never by which trigger asked. A background preheat therefore retries
     * a retriable list failure exactly like a foreground search does — the index
     * coordinator has no preheat-vs-interactive knob to withhold it.
     *
     * This matters beyond symmetry: a namespace index load that fails marks the
     * index non-authoritative and puts PSI into a five-minute cooldown, so
     * surrendering a transient 503 on the preheat path degrades gutter markers
     * far longer than the retry it saved.
     */
    @Test
    fun `preheat retries a retriable list failure exactly like an interactive trigger`() = runBlocking {
        val attemptsByTrigger = mutableMapOf<IndexTrigger, Int>()

        for (trigger in listOf(IndexTrigger.NAMESPACE_SWITCH, IndexTrigger.PSI, IndexTrigger.MANUAL_REFRESH)) {
            var listAttempts = 0
            val flaky = SessionGenerationHarness(
                transport = SessionGenerationHarness.RecordingTransport(
                    onRequest = { url ->
                        if (url.contains("/v1/cs/configs")) {
                            listAttempts++
                            // Fail the first attempt only; a retry must reach the server.
                            if (listAttempts == 1) throw NacosRequestError.Server(503, "")
                        }
                    }
                )
            )
            flaky.cacheService.clearAll()

            val outcome = flaky.coordinator.requestIndex(
                flaky.indexRequest(flaky.lockedContext(NacosApiGeneration.V1), namespaceId = "retry-ns"),
                trigger
            )

            assertTrue(
                outcome is IndexOutcome.Complete,
                "$trigger should recover from a transient 503: outcome=$outcome urls=${flaky.recordedUrls}"
            )
            attemptsByTrigger[trigger] = listAttempts
        }

        assertEquals(
            mapOf(
                IndexTrigger.NAMESPACE_SWITCH to 2,
                IndexTrigger.PSI to 2,
                IndexTrigger.MANUAL_REFRESH to 2
            ),
            attemptsByTrigger,
            "every trigger gets the same transport retry budget"
        )
    }

}
