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
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.operations.CapabilityCoverage
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

enum class IndexTrigger { NAMESPACE_SWITCH, SEARCH, MANUAL_REFRESH, PSI }

data class NamespaceIndexKey(val identity: AccessIdentity, val namespaceId: String)

/**
 * Immutable index request. Carries a mandatory operation context — every
 * generation routes through the operation gateway (issue #50 / ADR-0010).
 * The redundant captured-server snapshot type was deleted in issue #53;
 * callers pass a prepared [NacosOperationContext] only.
 */
data class NamespaceIndexRequest(
    val key: NamespaceIndexKey,
    val cacheTtlMillis: Long,
    val operationContext: NacosOperationContext
)

/**
 * Captures a namespace-index request from the active profile. Fails closed with
 * [ConfigurationRequired] when no valid operation context can be built, so
 * callers surface a fix-my-settings entry point instead of an empty list.
 */
internal fun NacosSettings.captureNamespaceIndexRequest(namespaceId: String?): NamespaceIndexRequest {
    val context = captureOperationContext().getOrElse { throw it }
    return captureNamespaceIndexRequest(namespaceId, context)
}

/**
 * Builds an index request from an already-captured operation context (UI and
 * search paths that prepared the context off the EDT).
 *
 * An AUTO profile's captured context still carries an unresolved generation;
 * [locator] supplies the one the hot-path freshness check already named
 * (ADR-0053) so the index write and every locator-applied reader share one
 * key space (issue #144). Locked V1/V3 contexts are returned untouched.
 */
internal fun NacosSettings.captureNamespaceIndexRequest(
    namespaceId: String?,
    operationContext: NacosOperationContext,
    locator: ResolvedGenerationLocator = ResolvedGenerationLocator.forSelectedProfile()
): NamespaceIndexRequest {
    val aligned = operationContext.locatedUnder(locator)
    return NamespaceIndexRequest(
        key = NamespaceIndexKey(aligned.identity, namespaceId.orEmpty()),
        cacheTtlMillis = getCacheTtlMillis(),
        operationContext = aligned
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
    /**
     * Summary pagination stopped after loading some rows. [stoppingCause] is
     * the typed remote error that stopped the load when known (issue #122);
     * null when incompleteness is inferred without a distinct page failure
     * (for example fewer rows than totalCount with a clean last page).
     */
    data class Partial(
        val loaded: Int,
        val expected: Int,
        val state: DatasetState,
        val stoppingCause: Throwable? = null
    ) : IndexOutcome
    data class Stale(val count: Int, val state: DatasetState) : IndexOutcome
    /**
     * Index load failed before any usable complete dataset was published.
     * [error] keeps the original typed cause (RemoteOperationError,
     * ConfigurationRequired, …) rather than a collapsed connection failure
     * (issue #122).
     */
    data class Failed(val error: Throwable) : IndexOutcome
}

/**
 * The sole owner of full-namespace index work. Ensures that concurrent
 * requests for the same identity+namespace join a single in-flight job
 * (single-flight), that PSI, SEARCH, and NAMESPACE_SWITCH share one per-key
 * exponential failure backoff after Failed **or Partial** outcomes (neither
 * leaves a FRESH index, so without a backoff every gesture would re-page), that
 * MANUAL_REFRESH bypasses the backoff, and that the latest foreground request
 * wins.
 *
 * Every generation is dispatched through [NacosApiService.loadNamespace] with
 * the captured operation context — there is no generation branch and no
 * legacy server-snapshot path here (issue #50).
 *
 * The trigger shapes how long a caller is willing to wait, not how the wire
 * behaves: SEARCH and MANUAL_REFRESH are bounded by a 15-second front-end
 * cutoff, while NAMESPACE_SWITCH and PSI await the load. Retry belongs to the
 * centralized transport seam and is classified by operation kind (ADR-0021), so
 * a background preheat and a foreground search retry identically and there is
 * no per-trigger request policy to carry.
 */
@Service(Service.Level.APP)
class NamespaceIndexCoordinator internal constructor(
    private val apiService: NacosApiService,
    private val cacheService: CacheService,
    private val clock: () -> Long = { System.currentTimeMillis() }
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

    // Shared failure backoff: consecutive Failed/Partial outcomes space out
    // retries for every background/derived trigger (issue #145).
    private val failureBackoff = ConcurrentHashMap<NamespaceIndexKey, FailureBackoff>()

    /**
     * Requests a full namespace index load for [request]. If an identical request
     * is already in flight, the caller joins it. The [trigger] determines the
     * front-end cutoff and whether the shared failure backoff applies.
     */
    override suspend fun requestIndex(request: NamespaceIndexRequest, trigger: IndexTrigger): IndexOutcome {
        val key = request.key
        require(request.cacheTtlMillis > 0) { "Namespace index cache TTL must be positive" }
        require(request.operationContext.identity == key.identity) {
            "Namespace index key does not match its captured operation context"
        }
        // Shared backoff: skip PSI / SEARCH / NAMESPACE_SWITCH while cooling
        // down. MANUAL_REFRESH always proceeds (issue #145).
        if (trigger != IndexTrigger.MANUAL_REFRESH && isInFailureBackoff(key)) {
            return IndexOutcome.Stale(
                0,
                DatasetState(DataSource.CACHE, DataFreshness.UNKNOWN, DatasetCompleteness.FAILED, null)
            )
        }

        // Single-flight: join or start
        val deferred = flightMutex.withLock {
            inFlight[key] ?: run {
                val job = scope.async { executeIndex(request) }
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

    @OptIn(CacheWriteAccess::class)
    private suspend fun executeIndex(request: NamespaceIndexRequest): IndexOutcome {
        val key = request.key
        val context = request.operationContext
        // Namespace-index writes delete data, so they are ordered by observation
        // sequence (ADR-0020 / issue #50). The sequence is taken *before* the
        // load starts — a load that started earlier must lose to one that
        // started later, even when it happens to finish afterwards. Whether it
        // still lands is the cache's decision, not this coordinator's.
        //
        // Access visibility (issue #123) is driven by per-page gateway reports
        // inside loadNamespace → listSummaries, not by this index-write sequence.
        val observation = apiService.operationGateway().beginObservation()
        return try {
            // Mandatory operation context — every generation (V1, V3, AUTO)
            // resolves through the gateway. No server snapshot, no generation
            // branch above the adapters (issue #50).
            val result = apiService.loadNamespace(
                namespaceId = key.namespaceId,
                useCache = false,
                operationContext = context
            )
            if (result.isFailure) {
                markNonAuthoritative(key, observation)
                recordFailure(key)
                return IndexOutcome.Failed(
                    result.exceptionOrNull() ?: RuntimeException("Unknown namespace index failure")
                )
            }

            val loadResult = result.getOrNull()!!
            val now = clock()

            when (loadResult.completeness) {
                DatasetCompleteness.COMPLETE -> {
                    // A rejected mutation means a later-started observation
                    // already owns this identity+namespace — the older view is
                    // discarded rather than allowed to delete newer entries.
                    val landed = cacheService.applyMutation(
                        CacheMutation.ReplaceNamespaceIndex(
                            key.identity,
                            key.namespaceId,
                            loadResult.configurations,
                            request.cacheTtlMillis
                        ),
                        observation
                    )
                    if (!landed) {
                        logger.info(
                            "Discarded obsolete namespace index write for ${key.namespaceId} " +
                                "(observation $observation lost to a later-started load)"
                        )
                        return IndexOutcome.Stale(
                            loadResult.configurations.size,
                            DatasetState(DataSource.REMOTE, DataFreshness.UNKNOWN, DatasetCompleteness.COMPLETE, now)
                        )
                    }
                    clearFailureBackoff(key)
                    // When the dialect's list pages already carried bodies, seed
                    // ordinary detail coordinates under this same observation so
                    // navigation prefetch and local content search do not refetch
                    // (issue #146 / ADR-0048). The index entry itself stays
                    // summaries-only (issue #52); blank bodies are skipped.
                    seedListCarriedBodies(
                        identity = key.identity,
                        namespaceId = key.namespaceId,
                        configurations = loadResult.configurations,
                        cacheTtlMillis = request.cacheTtlMillis,
                        observation = observation,
                        generation = context.resolvedGeneration
                    )
                    IndexOutcome.Complete(
                        loadResult.configurations.size,
                        DatasetState(DataSource.REMOTE, DataFreshness.FRESH, DatasetCompleteness.COMPLETE, now)
                    )
                }
                DatasetCompleteness.PARTIAL -> {
                    // Partial summary pagination: do not publish an authoritative
                    // index and do not seed the detail cache (issue #52 / #146).
                    // Details arrive only via a COMPLETE list-carried seed,
                    // navigation prefetch, or explicit reads.
                    // Keep the typed stopping cause so search stale-fallback
                    // policy and presentation can refuse refused-access (issue #122).
                    // Cool down like Failed: Partial never leaves FRESH, so
                    // without this every gesture would re-page the namespace.
                    markNonAuthoritative(key, observation)
                    recordFailure(key)
                    IndexOutcome.Partial(
                        loaded = loadResult.configurations.size,
                        expected = loadResult.expectedCount,
                        state = DatasetState(DataSource.REMOTE, DataFreshness.FRESH, DatasetCompleteness.PARTIAL, now),
                        stoppingCause = loadResult.stoppingCause
                    )
                }
                DatasetCompleteness.FAILED -> {
                    markNonAuthoritative(key, observation)
                    recordFailure(key)
                    IndexOutcome.Failed(
                        loadResult.stoppingCause
                            ?: RuntimeException("Namespace list failed")
                    )
                }
            }
        } catch (e: Exception) {
            markNonAuthoritative(key, observation)
            recordFailure(key)
            IndexOutcome.Failed(e)
        }
    }

    /**
     * Writes non-blank list-carried bodies into ordinary detail coordinates when
     * the dialect declares [ProtocolCapabilities.listCarriesBodies]. Uses the
     * same [observation] the index write used so seeded details lose to any
     * later-started write exactly like gateway detail writes (ADR-0020).
     */
    @OptIn(CacheWriteAccess::class)
    private suspend fun seedListCarriedBodies(
        identity: AccessIdentity,
        namespaceId: String,
        configurations: List<NacosConfiguration>,
        cacheTtlMillis: Long,
        observation: Long,
        generation: NacosApiGeneration
    ) {
        val coverage = apiService.protocolCapabilities(generation)?.listCarriesBodies
            ?: return
        if (coverage != CapabilityCoverage.COMPLETE) return
        for (configuration in configurations) {
            if (configuration.content.isBlank()) continue
            cacheService.applyMutation(
                CacheMutation.WriteDetail(
                    identity,
                    namespaceId,
                    configuration,
                    cacheTtlMillis
                ),
                observation
            )
        }
    }

    /**
     * A partial or failed index never deletes; it only records that absence has
     * become undecidable — and even that is ordered, so a late failure cannot
     * demote an index a later-started load already completed.
     */
    @OptIn(CacheWriteAccess::class)
    private suspend fun markNonAuthoritative(key: NamespaceIndexKey, observation: Long) {
        cacheService.applyMutation(
            CacheMutation.MarkNamespaceIndexNonAuthoritative(key.identity, key.namespaceId),
            observation
        )
    }

    private fun isInFailureBackoff(key: NamespaceIndexKey): Boolean {
        val until = failureBackoff[key]?.cooldownUntilMillis ?: return false
        return clock() < until
    }

    private fun recordFailure(key: NamespaceIndexKey) {
        val previous = failureBackoff[key]
        val consecutive = (previous?.consecutiveFailures ?: 0) + 1
        failureBackoff[key] = FailureBackoff(
            cooldownUntilMillis = clock() + backoffDelayMillis(consecutive),
            consecutiveFailures = consecutive
        )
    }

    private fun clearFailureBackoff(key: NamespaceIndexKey) {
        failureBackoff.remove(key)
    }

    fun dispose() {
        scope.cancel()
    }

    private data class FailureBackoff(
        val cooldownUntilMillis: Long,
        val consecutiveFailures: Int
    )

    companion object {
        /** First failure: 30s; second: 2m; third and later: 5m cap (issue #145). */
        internal fun backoffDelayMillis(consecutiveFailures: Int): Long = when {
            consecutiveFailures <= 1 -> 30_000L
            consecutiveFailures == 2 -> 120_000L
            else -> 300_000L
        }
    }
}
