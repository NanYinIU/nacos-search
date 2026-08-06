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
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.OperationContextResolver
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
 */
internal fun NacosSettings.captureNamespaceIndexRequest(
    namespaceId: String?,
    operationContext: NacosOperationContext
): NamespaceIndexRequest {
    return NamespaceIndexRequest(
        key = NamespaceIndexKey(operationContext.identity, namespaceId.orEmpty()),
        cacheTtlMillis = getCacheTtlMillis(),
        operationContext = operationContext
    )
}

/**
 * Derives the access identity for PSI/Swing hot paths WITHOUT reading PasswordSafe.
 * Reading a credential on the EDT triggers `SlowOperations` and is forbidden on
 * the hot path (design §11/§19.7). Identity fields come entirely from the profile,
 * so this resolves the selected profile and maps it through
 * [OperationContextResolver.identityFromProfile], which never touches the
 * credential store. A missing/invalid profile yields a stable sentinel identity.
 *
 * An AUTO profile has no generation in the profile to map, so [locator] supplies
 * the one the operation layer resolved — also without a credential — and the read
 * addresses the same key space the gateway writes under (issue #72).
 */
internal fun NacosSettings.captureAccessIdentity(
    profileId: String? = null,
    locator: ResolvedGenerationLocator = ResolvedGenerationLocator.forSelectedProfile()
): AccessIdentity {
    val selectedProfileId = profileId?.trim()?.takeUnless { it.isNullOrBlank() }
        ?: resolveDefaultProfileId()
    val profile = getProfile(selectedProfileId)
        ?: return AccessIdentity.ofProfile(
            profileId = "<configuration-required>",
            accessRevision = -1,
            canonicalEndpoint = "<invalid>",
            resolvedGeneration = NacosApiGeneration.UNKNOWN,
            authMode = AuthMode.TOKEN,
            principal = ""
        )
    return OperationContextResolver.identityFromProfile(profile)
        .locatedUnder(locator, profile.profileRevision)
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
 * (single-flight), that PSI triggers respect a five-minute cooldown after
 * failure, and that the latest foreground request wins.
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
     * Requests a full namespace index load for [request]. If an identical request
     * is already in flight, the caller joins it. The [trigger] determines the
     * front-end cutoff and whether PSI cooldown applies.
     */
    override suspend fun requestIndex(request: NamespaceIndexRequest, trigger: IndexTrigger): IndexOutcome {
        val key = request.key
        require(request.cacheTtlMillis > 0) { "Namespace index cache TTL must be positive" }
        require(request.operationContext.identity == key.identity) {
            "Namespace index key does not match its captured operation context"
        }
        // PSI cooldown: skip if recently failed
        if (trigger == IndexTrigger.PSI) {
            val cooldownUntil = psiCooldownUntil[key]
            if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) {
                return IndexOutcome.Stale(0, DatasetState(DataSource.CACHE, DataFreshness.UNKNOWN, DatasetCompleteness.FAILED, null))
            }
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
                recordPsiFailure(key)
                return IndexOutcome.Failed(
                    result.exceptionOrNull() ?: RuntimeException("Unknown namespace index failure")
                )
            }

            val loadResult = result.getOrNull()!!
            val now = System.currentTimeMillis()

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
                    clearPsiCooldown(key)
                    IndexOutcome.Complete(
                        loadResult.configurations.size,
                        DatasetState(DataSource.REMOTE, DataFreshness.FRESH, DatasetCompleteness.COMPLETE, now)
                    )
                }
                DatasetCompleteness.PARTIAL -> {
                    // Partial summary pagination: do not publish an authoritative
                    // index and do not seed the detail cache (issue #52). Details
                    // arrive only via navigation prefetch or explicit reads.
                    // Keep the typed stopping cause so search stale-fallback
                    // policy and presentation can refuse refused-access (issue #122).
                    markNonAuthoritative(key, observation)
                    IndexOutcome.Partial(
                        loaded = loadResult.configurations.size,
                        expected = loadResult.expectedCount,
                        state = DatasetState(DataSource.REMOTE, DataFreshness.FRESH, DatasetCompleteness.PARTIAL, now),
                        stoppingCause = loadResult.stoppingCause
                    )
                }
                DatasetCompleteness.FAILED -> {
                    markNonAuthoritative(key, observation)
                    recordPsiFailure(key)
                    IndexOutcome.Failed(
                        loadResult.stoppingCause
                            ?: RuntimeException("Namespace list failed")
                    )
                }
            }
        } catch (e: Exception) {
            markNonAuthoritative(key, observation)
            recordPsiFailure(key)
            IndexOutcome.Failed(e)
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
