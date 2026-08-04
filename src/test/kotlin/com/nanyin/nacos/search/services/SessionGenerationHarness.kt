package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.operations.GenerationProbeFlight
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared test harness for issues #49 / #50 / parent PRD #35.
 *
 * Enters at [NamespaceIndexCoordinator.requestIndex] — the real entry for all
 * four index triggers — and records every outgoing request at the
 * [NacosRequestExecutor.HttpTransport] seam. After issue #50 every index load
 * routes through the gateway; generation is observed by URL path segments.
 */
class SessionGenerationHarness(
    private val transport: RecordingTransport = RecordingTransport(),
    sessionEpoch: AtomicLong = AtomicLong(0),
    private val isEntombed: (String) -> Boolean = { false }
) {
    val epoch = sessionEpoch
    val session = SessionGenerationState(
        currentEpoch = { epoch.get() },
        isEntombed = isEntombed,
        onGenerationChanged = { epoch.incrementAndGet(); Unit }
    )
    val probeFlight = GenerationProbeFlight()
    val executor = NacosRequestExecutor(transport)
    val apiService = NacosApiService(
        executorOverride = executor,
        sessionGenerationOverride = session,
        probeFlightOverride = probeFlight
    )
    val cacheService = CacheService({ System.currentTimeMillis() }, ProfileTombstoneRegistry())
    val coordinator = NamespaceIndexCoordinator(apiService, cacheService)

    val recordedUrls: List<String> get() = transport.urls

    fun probeRequestCount(): Int =
        transport.urls.count { it.contains("/v3/admin/core/state") || it.contains("/v1/console/namespaces") }

    fun v3StateProbeCount(): Int =
        transport.urls.count { it.contains("/v3/admin/core/state") }

    fun v1ListCount(): Int =
        transport.urls.count { it.contains("/v1/cs/configs") && !it.contains("/v3/") }

    fun gatewayV3ListCount(): Int =
        transport.urls.count { it.contains("/v3/admin/cs/config/list") }

    /** Any URL path segment that is generation-one (hardcoded V1 Open API). */
    fun generationOneRequestCount(): Int =
        transport.urls.count { url ->
            url.contains("/nacos/v1/") || url.contains("/v1/")
        }

    fun clearRecorded() = transport.urls.clear()

    fun autoContext(
        profileId: String = "auto-profile",
        profileRevision: Long = 1,
        accessRevision: Long = 1,
        endpoint: String = "https://nacos.example"
    ): NacosOperationContext {
        val parsed = CanonicalNacosEndpoint.parse(endpoint).getOrThrow()
        return NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = profileId,
                accessRevision = accessRevision,
                canonicalEndpoint = parsed.value,
                resolvedGeneration = NacosApiGeneration.UNKNOWN,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = parsed,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = profileRevision,
            accessRevision = accessRevision,
            resolvedGeneration = NacosApiGeneration.UNKNOWN
        )
    }

    fun lockedContext(
        generation: NacosApiGeneration,
        profileId: String = "locked-profile",
        profileRevision: Long = 1,
        accessRevision: Long = 1,
        endpoint: String = "https://nacos.example"
    ): NacosOperationContext {
        val parsed = CanonicalNacosEndpoint.parse(endpoint).getOrThrow()
        return NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = profileId,
                accessRevision = accessRevision,
                canonicalEndpoint = parsed.value,
                resolvedGeneration = generation,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = parsed,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = profileRevision,
            accessRevision = accessRevision,
            resolvedGeneration = generation
        )
    }

    fun indexRequest(
        context: NacosOperationContext,
        namespaceId: String = "public"
    ): NamespaceIndexRequest {
        return NamespaceIndexRequest(
            key = NamespaceIndexKey(context.identity, namespaceId),
            cacheTtlMillis = 60_000L,
            operationContext = context
        )
    }

    /**
     * Recording transport that answers V3 state, V3 list, and V1 list
     * with deterministic empty payloads so index loads complete without detail
     * fan-out.
     */
    class RecordingTransport(
        private val onRequest: (String) -> Unit = {},
        private val stateDelayMs: Long = 0L,
        private val stateBody: String = """{"version":"3.2.3","status":"UP"}""",
        private val v3ListBody: String = """{"code":0,"message":"success","data":{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}}""",
        private val v1ListBody: String = """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}""",
        private val v1NamespacesBody: String = """[{"namespace":"","namespaceShowName":"public"}]"""
    ) : NacosRequestExecutor.HttpTransport {
        val urls = CopyOnWriteArrayList<String>()

        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            urls.add(request.url)
            onRequest(request.url)
            if (stateDelayMs > 0 && request.url.contains("/v3/admin/core/state")) {
                Thread.sleep(stateDelayMs)
            }
            return when {
                request.url.contains("/v3/admin/core/state") -> stateBody
                request.url.contains("/v3/admin/cs/config/list") -> v3ListBody
                request.url.contains("/v3/admin/cs/config") ->
                    """{"code":0,"message":"success","data":{"dataId":"x","group":"DEFAULT_GROUP","content":"k=v","type":"text"}}"""
                request.url.contains("/v1/console/namespaces") -> v1NamespacesBody
                request.url.contains("/v1/cs/configs") -> v1ListBody
                else -> throw NacosRequestError.Client(404, "not found: ${request.url}")
            }
        }

        override fun post(request: NacosRequestExecutor.TransportRequest): String {
            urls.add(request.url)
            onRequest(request.url)
            return "true"
        }
    }
}
