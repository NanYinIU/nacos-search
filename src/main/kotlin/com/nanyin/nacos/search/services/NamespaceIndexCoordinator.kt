package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetState
import com.nanyin.nacos.search.models.DataSource
import com.nanyin.nacos.search.models.DataFreshness
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.RequestPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class IndexTrigger { NAMESPACE_SWITCH, SEARCH, MANUAL_REFRESH, PSI }

data class NamespaceIndexKey(val identity: AccessIdentity, val namespaceId: String)

data class NacosServerSnapshot(
    val serverUrl: String,
    val username: String,
    val password: String,
    val authMode: com.nanyin.nacos.search.settings.AuthMode,
    val enableTokenAuth: Boolean,
    val identity: AccessIdentity = AccessIdentity.of("", AuthMode.TOKEN, "")
) {
    override fun toString(): String =
        "NacosServerSnapshot(serverUrl=$serverUrl, username=$username, password=***, authMode=$authMode, enableTokenAuth=$enableTokenAuth)"
}

data class NamespaceIndexRequest(
    val key: NamespaceIndexKey,
    val server: NacosServerSnapshot,
    val cacheTtlMillis: Long,
    /** V1 uses this immutable operation snapshot instead of the legacy wire path. */
    val operationContext: NacosOperationContext? = null
)

internal fun NacosSettings.captureNamespaceIndexRequest(namespaceId: String?): NamespaceIndexRequest {
    val context = captureOperationContext().getOrNull()
    return captureNamespaceIndexRequest(namespaceId, captureServerSnapshot(operationContext = context), context)
}

internal fun NacosSettings.captureAccessIdentity(profileId: String? = null): AccessIdentity {
    return captureOperationContext(profileId)
        .getOrNull()?.identity ?: AccessIdentity.ofProfile(
        profileId = "<configuration-required>",
        accessRevision = -1,
        canonicalEndpoint = "<invalid>",
        resolvedGeneration = NacosApiGeneration.UNKNOWN,
        authMode = AuthMode.TOKEN,
        principal = ""
    )
}

internal fun NacosSettings.captureNamespaceIndexRequest(
    namespaceId: String?,
    snapshot: NacosServerSnapshot,
    operationContext: NacosOperationContext? = null
): NamespaceIndexRequest {
    require(operationContext == null || operationContext.identity == snapshot.identity) {
        "Namespace index operation context does not match its captured server snapshot"
    }
    return NamespaceIndexRequest(
        NamespaceIndexKey(
            snapshot.identity,
            namespaceId.orEmpty()
        ),
        snapshot,
        getCacheTtlMillis(),
        operationContext
    )
}

internal fun NacosSettings.captureServerSnapshot(
    profileId: String? = null,
    operationContext: NacosOperationContext? = null
): NacosServerSnapshot {
    val context = operationContext ?: captureOperationContext(profileId).getOrNull()
    return if (context == null) {
        NacosServerSnapshot("", "", "", AuthMode.TOKEN, false, captureAccessIdentity(profileId))
    } else {
        NacosServerSnapshot(
            serverUrl = context.endpoint.value,
            username = context.identity.principal.takeUnless { it == "<anonymous>" }.orEmpty(),
            password = context.credential.secret,
            authMode = context.authMode,
            enableTokenAuth = context.authMode != AuthMode.BASIC,
            identity = context.identity
        )
    }
}

interface NamespaceIndexRequester {
    suspend fun requestIndex(request: NamespaceIndexRequest, trigger: IndexTrigger): IndexOutcome
}

internal suspend fun NamespaceIndexRequester.requestStartupNamespaceIndex(request: NamespaceIndexRequest): IndexOutcome =
    requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)

internal suspend fun NamespaceIndexRequester.requestSwitchedNamespaceIndex(request: NamespaceIndexRequest): IndexOutcome =
    requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)

internal suspend fun NamespaceIndexRequester.requestManualNamespaceRefresh(request: NamespaceIndexRequest): IndexOutcome =
    requestIndex(request, IndexTrigger.MANUAL_REFRESH)

sealed interface IndexOutcome {
    data class Complete(val count: Int, val state: DatasetState) : IndexOutcome
    data class Partial(val loaded: Int, val expected: Int, val state: DatasetState) : IndexOutcome
    data class Stale(val count: Int, val state: DatasetState) : IndexOutcome
    data class Failed(val error: NacosRequestError) : IndexOutcome
}

/**
 * The sole owner of full-namespace index work. Ensures that concurrent
 * requests for the same identity+namespace join a single in-flight job
 * (single-flight), that PSI triggers respect a five-minute cooldown after
 * failure, and that the latest foreground request wins.
 *
 * Search requests are bounded by a 15-second front-end cutoff; preheat
 * requests use PREHEAT policy (no retry).
 */
@Service(Service.Level.APP)
class NamespaceIndexCoordinator internal constructor(
    private val apiService: NacosApiService,
    private val cacheService: CacheService
) : NamespaceIndexRequester {

    constructor() : this(
        ApplicationManager.getApplication().getService(NacosApiService::class.java),
        ApplicationManager.getApplication().getService(CacheService::class.java)
    )

    private val logger = thisLogger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Single-flight: one deferred per identity+namespace key
    private val inFlight = ConcurrentHashMap<NamespaceIndexKey, Deferred<IndexOutcome>>()
    private val flightMutex = Mutex()

    // PSI cooldown: tracks the last failure time per key
    private val psiCooldownUntil = ConcurrentHashMap<NamespaceIndexKey, Long>()
    private val psiCooldownMs = 5L * 60 * 1000 // 5 minutes

    /**
     * Requests a full namespace index load for [key]. If an identical request
     * is already in flight, the caller joins it. The [trigger] determines the
     * request policy and whether PSI cooldown applies.
     */
    override suspend fun requestIndex(request: NamespaceIndexRequest, trigger: IndexTrigger): IndexOutcome {
        val key = request.key
        val snapshotIdentity = request.server.identity
        require(
            if (snapshotIdentity.canonicalEndpoint != "<default>") {
                key.identity == snapshotIdentity
            } else {
                key.identity.canonicalEndpoint == com.nanyin.nacos.search.models.CanonicalNacosEndpoint
                    .parse(request.server.serverUrl).getOrNull()?.value &&
                    key.identity.authMode == request.server.authMode &&
                    key.identity.principal == request.server.username.trim().ifBlank { "<anonymous>" }
            }
        ) { "Namespace index key does not match its captured server snapshot" }
        require(request.cacheTtlMillis > 0) { "Namespace index cache TTL must be positive" }
        require(request.operationContext == null || request.operationContext.identity == key.identity) {
            "Namespace index key does not match its captured operation context"
        }
        // PSI cooldown: skip if recently failed
        if (trigger == IndexTrigger.PSI) {
            val cooldownUntil = psiCooldownUntil[key]
            if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) {
                return IndexOutcome.Stale(0, DatasetState(DataSource.CACHE, DataFreshness.UNKNOWN, DatasetCompleteness.FAILED, null))
            }
        }

        val policy = policyFor(trigger)

        // Single-flight: join or start
        val deferred = flightMutex.withLock {
            inFlight[key] ?: run {
                val job = scope.async { executeIndex(request, policy) }
                inFlight[key] = job
                job
            }
        }

        return try {
            if (trigger == IndexTrigger.SEARCH || trigger == IndexTrigger.MANUAL_REFRESH) {
                // 15-second front-end cutoff for interactive triggers
                withTimeoutOrNull(15_000) { deferred.await() }
                    ?: IndexOutcome.Stale(0, DatasetState(DataSource.CACHE, DataFreshness.UNKNOWN, DatasetCompleteness.FAILED, null))
            } else {
                deferred.await()
            }
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private suspend fun executeIndex(request: NamespaceIndexRequest, policy: RequestPolicy): IndexOutcome {
        val key = request.key
        return try {
            val v1Context = request.operationContext?.takeIf {
                it.resolvedGeneration == NacosApiGeneration.V1
            }
            val result = if (v1Context != null) {
                apiService.loadNamespace(
                    key.namespaceId,
                    useCache = false,
                    server = null,
                    policy = policy,
                    operationContext = v1Context
                )
            } else {
                apiService.loadNamespace(key.namespaceId, useCache = false, server = request.server, policy = policy)
            }
            if (result.isFailure) {
                cacheService.markNamespaceIndexNonAuthoritative(key.identity, key.namespaceId)
                recordPsiFailure(key)
                val error = result.exceptionOrNull()
                return IndexOutcome.Failed(
                    if (error is NacosRequestError) error else NacosRequestError.Connection(error ?: RuntimeException("Unknown"))
                )
            }

            val loadResult = result.getOrNull()!!
            val now = System.currentTimeMillis()

            when (loadResult.completeness) {
                DatasetCompleteness.COMPLETE -> {
                    cacheService.putNamespaceIndex(
                        key.identity,
                        key.namespaceId,
                        loadResult.configurations,
                        request.cacheTtlMillis
                    )
                    clearPsiCooldown(key)
                    IndexOutcome.Complete(
                        loadResult.configurations.size,
                        DatasetState(DataSource.REMOTE, DataFreshness.FRESH, DatasetCompleteness.COMPLETE, now)
                    )
                }
                DatasetCompleteness.PARTIAL -> {
                    // Partial: write successful details but do not refresh index timestamps
                    cacheService.markNamespaceIndexNonAuthoritative(key.identity, key.namespaceId)
                    if (loadResult.configurations.isNotEmpty()) {
                        cacheService.putNamespaceDetails(
                            key.identity,
                            key.namespaceId,
                            loadResult.configurations,
                            request.cacheTtlMillis
                        )
                    }
                    IndexOutcome.Partial(
                        loadResult.configurations.size,
                        loadResult.expectedCount,
                        DatasetState(DataSource.REMOTE, DataFreshness.FRESH, DatasetCompleteness.PARTIAL, now)
                    )
                }
                DatasetCompleteness.FAILED -> {
                    cacheService.markNamespaceIndexNonAuthoritative(key.identity, key.namespaceId)
                    recordPsiFailure(key)
                    IndexOutcome.Failed(NacosRequestError.Connection(RuntimeException("Namespace list failed")))
                }
            }
        } catch (e: Exception) {
            cacheService.markNamespaceIndexNonAuthoritative(key.identity, key.namespaceId)
            recordPsiFailure(key)
            val error = if (e is NacosRequestError) e else NacosRequestError.Connection(e)
            IndexOutcome.Failed(error)
        }
    }

    private fun policyFor(trigger: IndexTrigger): RequestPolicy = when (trigger) {
        IndexTrigger.SEARCH, IndexTrigger.MANUAL_REFRESH -> RequestPolicy.INTERACTIVE
        IndexTrigger.NAMESPACE_SWITCH, IndexTrigger.PSI -> RequestPolicy.PREHEAT
    }

    private fun recordPsiFailure(key: NamespaceIndexKey) {
        psiCooldownUntil[key] = System.currentTimeMillis() + psiCooldownMs
    }

    private fun clearPsiCooldown(key: NamespaceIndexKey) {
        psiCooldownUntil.remove(key)
    }

    fun dispose() {
        scope.cancel()
    }
}
