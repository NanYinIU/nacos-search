package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetState
import com.nanyin.nacos.search.models.DataSource
import com.nanyin.nacos.search.models.DataFreshness
import com.nanyin.nacos.search.settings.NacosSettings
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
    val enableTokenAuth: Boolean
) {
    override fun toString(): String =
        "NacosServerSnapshot(serverUrl=$serverUrl, username=$username, password=***, authMode=$authMode, enableTokenAuth=$enableTokenAuth)"
}

data class NamespaceIndexRequest(
    val key: NamespaceIndexKey,
    val server: NacosServerSnapshot,
    val cacheTtlMillis: Long
)

internal fun NacosSettings.captureNamespaceIndexRequest(namespaceId: String?): NamespaceIndexRequest =
    captureNamespaceIndexRequest(namespaceId, captureServerSnapshot())

internal fun NacosSettings.captureNamespaceIndexRequest(
    namespaceId: String?,
    snapshot: NacosServerSnapshot
): NamespaceIndexRequest {
    return NamespaceIndexRequest(
        NamespaceIndexKey(
            AccessIdentity.of(snapshot.serverUrl, snapshot.authMode, snapshot.username),
            namespaceId.orEmpty()
        ),
        snapshot,
        getCacheTtlMillis()
    )
}

internal fun NacosSettings.captureServerSnapshot(): NacosServerSnapshot {
    val server = getActiveServer()
    return NacosServerSnapshot(
        serverUrl = server.serverUrl.trim().trimEnd('/'),
        username = server.username,
        password = server.password,
        authMode = server.authMode,
        enableTokenAuth = enableTokenAuth
    )
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
        require(
            key.identity == AccessIdentity.of(
                request.server.serverUrl,
                request.server.authMode,
                request.server.username
            )
        ) { "Namespace index key does not match its captured server snapshot" }
        require(request.cacheTtlMillis > 0) { "Namespace index cache TTL must be positive" }
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
            val result = apiService.loadNamespace(key.namespaceId, useCache = false, server = request.server, policy = policy)
            if (result.isFailure) {
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
                        key.identity.serverId,
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
                    if (loadResult.configurations.isNotEmpty()) {
                        cacheService.cacheConfigurations(loadResult.configurations)
                    }
                    IndexOutcome.Partial(
                        loadResult.configurations.size,
                        loadResult.expectedCount,
                        DatasetState(DataSource.REMOTE, DataFreshness.FRESH, DatasetCompleteness.PARTIAL, now)
                    )
                }
                DatasetCompleteness.FAILED -> {
                    recordPsiFailure(key)
                    IndexOutcome.Failed(NacosRequestError.Connection(RuntimeException("Namespace list failed")))
                }
            }
        } catch (e: Exception) {
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
