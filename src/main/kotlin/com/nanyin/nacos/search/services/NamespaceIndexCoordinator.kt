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

internal fun NacosSettings.namespaceIndexKey(namespaceId: String?): NamespaceIndexKey {
    val server = getActiveServer()
    return NamespaceIndexKey(
        AccessIdentity.of(server.serverUrl, server.authMode, server.username),
        namespaceId.orEmpty()
    )
}

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
class NamespaceIndexCoordinator {

    private val logger = thisLogger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Single-flight: one deferred per identity+namespace key
    private val inFlight = ConcurrentHashMap<NamespaceIndexKey, Deferred<IndexOutcome>>()
    private val flightMutex = Mutex()

    // PSI cooldown: tracks the last failure time per key
    private val psiCooldownUntil = ConcurrentHashMap<NamespaceIndexKey, Long>()
    private val psiCooldownMs = 5L * 60 * 1000 // 5 minutes

    private val apiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    private val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)

    /**
     * Requests a full namespace index load for [key]. If an identical request
     * is already in flight, the caller joins it. The [trigger] determines the
     * request policy and whether PSI cooldown applies.
     */
    suspend fun requestIndex(key: NamespaceIndexKey, trigger: IndexTrigger): IndexOutcome {
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
                val job = scope.async { executeIndex(key, policy) }
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

    private suspend fun executeIndex(key: NamespaceIndexKey, policy: RequestPolicy): IndexOutcome {
        return try {
            val result = apiService.loadNamespace(key.namespaceId, useCache = false)
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
                        loadResult.configurations
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
