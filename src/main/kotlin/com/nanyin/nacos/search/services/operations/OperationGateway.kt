package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.CacheMutation
import com.nanyin.nacos.search.services.CacheWriteAccess
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.network.RequestPolicy
import com.nanyin.nacos.search.services.visibility.CompletedObservation
import com.nanyin.nacos.search.services.visibility.NoOpVisibilityReporter
import com.nanyin.nacos.search.services.visibility.ObservationOutcome
import com.nanyin.nacos.search.services.visibility.VisibilityOperationClass
import com.nanyin.nacos.search.services.visibility.VisibilityReporter

/**
 * Generation-neutral boundary for read operations. Callers pass an immutable
 * [OperationTarget] captured before UI state can change; neither cache lookup
 * nor protocol dispatch reads current settings or project selection.
 *
 * A read that goes to the server takes its observation sequence when that
 * operation starts and hands the same number to both the caller and the cache
 * mutation derived from it, so painting a result and writing its cache entry
 * are ordered by one number (ADR-0020 / ADR-0047). A read served from cache
 * observed nothing and returns [Observed.NO_OBSERVATION].
 *
 * Every completed formal remote operation is reported to [visibility] with its
 * start-time sequence and operation class — successes as well as typed
 * failures — so identity-wide authentication blocks can form and clear
 * (ADR-0050 / issue #123). Cache hits do not report and cannot clear a block.
 *
 * The gateway keeps no gates of its own: the cache-entry gate lives inside the
 * cache module, where its scope equals the mutation's coordinate by
 * construction (ADR-0044).
 */
class OperationGateway(
    private val adapters: Map<NacosApiGeneration, ProtocolAdapter>,
    private val cache: OperationCache = NoOperationCache,
    private val historyCache: HistoryMemoryCache = HistoryMemoryCache(),
    private val observationSequence: ObservationSequence = ObservationSequence.process,
    private val visibility: VisibilityReporter = NoOpVisibilityReporter
) {
    suspend fun probe(target: OperationTarget): Result<Unit> {
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        val observation = observationSequence.next()
        val result = adapter.probe(target)
        report(target, VisibilityOperationClass.AUTHENTICATED_CONTACT, observation, result)
        return result
    }

    suspend fun listSummaries(
        target: OperationTarget,
        query: SummaryQuery,
        forceRefresh: Boolean = false,
        useCache: Boolean = true
    ): Result<Observed<SummaryPage>> {
        if (useCache && !forceRefresh) {
            cache.getSummaries(target.context.identity, target.namespaceId, query.cacheKey())?.let {
                // Cache hit: NO_OBSERVATION, never reports, cannot clear a block.
                return Result.success(Observed(it, Observed.NO_OBSERVATION))
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        val observation = observationSequence.next()
        val result = adapter.listSummaries(target, query)
        report(target, VisibilityOperationClass.CONFIGURATION_READ, observation, result)
        return result.map { page ->
            if (useCache) {
                cache.putSummaries(target.context.identity, target.namespaceId, query.cacheKey(), page, observation)
            }
            Observed(page, observation)
        }
    }

    suspend fun readDetail(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate,
        forceRefresh: Boolean = false,
        useCache: Boolean = true
    ): Result<Observed<NacosConfiguration?>> {
        if (useCache && !forceRefresh) {
            cache.getDetail(target.context.identity, target.namespaceId, coordinate.dataId, coordinate.group)?.let {
                return Result.success(Observed(it, Observed.NO_OBSERVATION))
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        val observation = observationSequence.next()
        val result = adapter.readDetail(target, coordinate)
        report(target, VisibilityOperationClass.CONFIGURATION_READ, observation, result)
        return result.map { detail ->
            if (useCache && detail != null) {
                cache.putDetail(target.context.identity, target.namespaceId, detail, observation)
            }
            Observed(detail, observation)
        }
    }


    suspend fun listHistory(
        target: OperationTarget,
        query: HistoryQuery,
        forceRefresh: Boolean = false,
        useCache: Boolean = true
    ): Result<Observed<HistoryPage>> {
        if (useCache && !forceRefresh && !visibility.isIdentityAuthBlocked(target.context.identity)) {
            // Identity-wide auth block hides history cache hits the same way
            // CacheService hides configuration payloads (issue #123).
            historyCache.getHistoryPage(target.context.identity, target.namespaceId, query.cacheKey())?.let {
                return Result.success(Observed(it, Observed.NO_OBSERVATION))
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        if (adapter.capabilities.history == CapabilityCoverage.UNAVAILABLE) {
            // Capability grade, not a remote observation — no visibility mutation.
            return Result.failure(
                RemoteOperationError.CapabilityUnsupported("Protocol adapter does not support configuration history")
            )
        }
        val observation = observationSequence.next()
        val result = adapter.listHistory(target, query)
        report(target, VisibilityOperationClass.HISTORY, observation, result)
        return result.map { page ->
            if (useCache) {
                historyCache.putHistoryPage(
                    target.context.identity, target.namespaceId, query.cacheKey(), page, observation
                )
            }
            Observed(page, observation)
        }
    }

    suspend fun readHistoryDetail(
        target: OperationTarget,
        historyId: String,
        forceRefresh: Boolean = false,
        useCache: Boolean = true
    ): Result<Observed<HistoryDetail>> {
        if (useCache && !forceRefresh && !visibility.isIdentityAuthBlocked(target.context.identity)) {
            historyCache.getHistoryDetail(target.context.identity, target.namespaceId, historyId)?.let {
                return Result.success(Observed(it, Observed.NO_OBSERVATION))
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        if (adapter.capabilities.history == CapabilityCoverage.UNAVAILABLE) {
            return Result.failure(
                RemoteOperationError.CapabilityUnsupported("Protocol adapter does not support configuration history")
            )
        }
        val observation = observationSequence.next()
        val result = adapter.readHistoryDetail(target, historyId)
        report(target, VisibilityOperationClass.HISTORY, observation, result)
        return result.map { detail ->
            if (useCache) {
                historyCache.putHistoryDetail(
                    target.context.identity, target.namespaceId, historyId, detail, observation
                )
            }
            Observed(detail, observation)
        }
    }

    /**
     * Dispatches a single publish write to the adapter for [target]'s generation.
     * Callers that need the full controlled state machine should use
     * [PublishController] with [OperationGatewayPublishGateway], not this method alone.
     */
    suspend fun publish(
        target: OperationTarget,
        command: PublishCommand
    ): Result<PublishOutcome> {
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        val observation = observationSequence.next()
        val result = adapter.publish(target, command)
        report(target, VisibilityOperationClass.PUBLISH, observation, result)
        return result
    }

    suspend fun discoverNamespaces(target: OperationTarget): Result<List<DiscoveredNamespace>> {
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        if (adapter.capabilities.namespaceDiscovery == CapabilityCoverage.UNAVAILABLE) {
            return Result.failure(
                RemoteOperationError.CapabilityUnsupported("Protocol adapter does not support namespace discovery")
            )
        }
        val observation = observationSequence.next()
        val result = adapter.discoverNamespaces(target)
        report(target, VisibilityOperationClass.NAMESPACE_DISCOVERY, observation, result)
        return result
    }

    /**
     * Returns the selected dialect's capability declaration for [generation],
     * or null when no adapter is registered. Upper layers must use this instead
     * of switching on API generation for coverage (ADR-0048).
     */
    fun capabilities(generation: NacosApiGeneration): ProtocolCapabilities? =
        adapters[generation]?.capabilities

    /**
     * Takes the observation sequence for an operation this gateway does not
     * dispatch itself — today, a full namespace-index load. ADR-0020 requires
     * the sequence to be obtained **when the operation starts**, not when it
     * writes, so that a later-started load wins even if an earlier one
     * completes after it. The caller passes the same number to
     * [com.nanyin.nacos.search.services.CacheService.apply], which decides
     * whether the mutation still lands.
     *
     * Visibility for multi-page index loads depends on **per-page** gateway
     * reports from [listSummaries] (via [com.nanyin.nacos.search.services.NacosApiService.loadNamespace]);
     * [beginObservation] is for the cache index write only. Prefer that path
     * over a single aggregate report so each formal page still carries its own
     * start-time sequence (issue #123).
     */
    fun beginObservation(): Long = observationSequence.next()

    /**
     * Reports a completed formal observation that this gateway did not dispatch
     * itself. Available for orchestration that batches outside per-page gateway
     * calls; the production namespace-index path does not need it because each
     * page already reports through [listSummaries].
     */
    suspend fun reportExternalObservation(
        target: OperationTarget,
        operationClass: VisibilityOperationClass,
        observation: Long,
        outcome: ObservationOutcome
    ): Boolean = visibility.reportCompleted(
        CompletedObservation(
            observation = observation,
            identity = target.context.identity,
            operationClass = operationClass,
            namespaceId = target.namespaceId,
            outcome = outcome
        )
    )

    /** Test/inspection helper: last issued observation sequence. */
    fun currentObservationSequence(): Long = observationSequence.current()

    private fun adapterFor(target: OperationTarget): ProtocolAdapter? = adapters[target.context.resolvedGeneration]

    private fun <T> unsupportedGeneration(target: OperationTarget): Result<T> = Result.failure(
        RemoteOperationError.Unsupported("No adapter is available for ${target.context.resolvedGeneration}")
    )

    private suspend fun <T> report(
        target: OperationTarget,
        operationClass: VisibilityOperationClass,
        observation: Long,
        result: Result<T>
    ) {
        val outcome = if (result.isSuccess) {
            ObservationOutcome.Success
        } else {
            when (val error = result.exceptionOrNull()) {
                is RemoteOperationError -> ObservationOutcome.RemoteFailure(error)
                // Non-remote throwables do not mutate visibility.
                else -> return
            }
        }
        visibility.reportCompleted(
            CompletedObservation(
                observation = observation,
                identity = target.context.identity,
                operationClass = operationClass,
                namespaceId = target.namespaceId,
                outcome = outcome
            )
        )
    }
}

/**
 * Cache seam for gateway tests and the identity-scoped production cache bridge.
 * Writes carry the observation sequence of the read that produced them; whether
 * the write lands is the cache's decision, not the gateway's.
 */
interface OperationCache {
    suspend fun getSummaries(identity: AccessIdentity, namespaceId: String, requestKey: String): SummaryPage?
    suspend fun putSummaries(
        identity: AccessIdentity,
        namespaceId: String,
        requestKey: String,
        page: SummaryPage,
        observation: Long
    )
    suspend fun getDetail(identity: AccessIdentity, namespaceId: String, dataId: String, group: String): NacosConfiguration?
    suspend fun putDetail(
        identity: AccessIdentity,
        namespaceId: String,
        detail: NacosConfiguration,
        observation: Long
    )
}

/** Test double that keeps the ordering rule so gateway tests still see it. */
class InMemoryOperationCache : OperationCache {
    private val summaries = mutableMapOf<Triple<AccessIdentity, String, String>, SummaryPage>()
    private val details = mutableMapOf<DetailCacheKey, NacosConfiguration>()
    private val highWater = ObservationHighWater()

    override suspend fun getSummaries(identity: AccessIdentity, namespaceId: String, requestKey: String): SummaryPage? =
        summaries[Triple(identity, namespaceId, requestKey)]

    override suspend fun putSummaries(
        identity: AccessIdentity,
        namespaceId: String,
        requestKey: String,
        page: SummaryPage,
        observation: Long
    ) {
        if (!highWater.acceptAndRaise(listOf("summary|$identity|$namespaceId|$requestKey"), observation)) return
        summaries[Triple(identity, namespaceId, requestKey)] = page
    }

    override suspend fun getDetail(
        identity: AccessIdentity,
        namespaceId: String,
        dataId: String,
        group: String
    ): NacosConfiguration? = details[DetailCacheKey(identity, namespaceId, dataId, group)]

    override suspend fun putDetail(
        identity: AccessIdentity,
        namespaceId: String,
        detail: NacosConfiguration,
        observation: Long
    ) {
        val key = DetailCacheKey(identity, namespaceId, detail.dataId, detail.group)
        if (!highWater.acceptAndRaise(listOf("detail|$key"), observation)) return
        details[key] = detail
    }

    private data class DetailCacheKey(
        val identity: AccessIdentity,
        val namespaceId: String,
        val dataId: String,
        val group: String
    )
}

/**
 * Adapts the persistent cache's one gated write entry point to this seam.
 *
 * This is the gateway's cache-facing adapter and therefore one of the few
 * places entitled to [CacheWriteAccess]: every write it makes carries the
 * observation sequence of the read that produced it (ADR-0052).
 */
@OptIn(CacheWriteAccess::class)
class CacheServiceOperationCache(
    private val cacheService: CacheService,
    private val ttlMillis: () -> Long
) : OperationCache {
    override suspend fun getSummaries(
        identity: AccessIdentity,
        namespaceId: String,
        requestKey: String
    ): SummaryPage? = cacheService.getListPage(identity, namespaceId, requestKey)?.toSummaryPage()

    override suspend fun putSummaries(
        identity: AccessIdentity,
        namespaceId: String,
        requestKey: String,
        page: SummaryPage,
        observation: Long
    ) {
        cacheService.applyMutation(
            CacheMutation.WriteListPage(
                identity, namespaceId, requestKey, page.toConfigListResponse(), ttlMillis()
            ),
            observation
        )
    }

    override suspend fun getDetail(
        identity: AccessIdentity,
        namespaceId: String,
        dataId: String,
        group: String
    ): NacosConfiguration? = cacheService.getConfigDetail(identity, namespaceId, dataId, group)

    override suspend fun putDetail(
        identity: AccessIdentity,
        namespaceId: String,
        detail: NacosConfiguration,
        observation: Long
    ) {
        cacheService.applyMutation(
            CacheMutation.WriteDetail(identity, namespaceId, detail, ttlMillis()),
            observation
        )
    }

    private fun SummaryPage.toConfigListResponse(): ConfigListResponse = ConfigListResponse(
        totalCount = totalCount,
        pageNumber = pageNumber,
        pagesAvailable = pagesAvailable,
        pageItems = items.mapIndexed { index, item ->
            ConfigItem(
                id = index.toString(),
                dataId = item.dataId,
                group = item.group,
                content = item.content,
                type = item.type,
                tenant = item.tenantId
            )
        }
    )

    private fun ConfigListResponse.toSummaryPage(): SummaryPage = SummaryPage(
        totalCount = totalCount,
        pageNumber = pageNumber,
        pagesAvailable = pagesAvailable,
        items = pageItems.map { item ->
            ConfigurationSummary(item.dataId, item.group, item.tenant, item.content, item.type)
        }
    )
}

/** Production transport for both V1 and V3 read and write operations.
 *
 * The adapter contract is transport-agnostic: adapters inspect
 * [ProtocolResponse.status] and the JSON envelope to classify failures. The
 * underlying [NacosRequestExecutor] throws [NacosRequestError] for every
 * non-2xx response, so this seam re-materialises the real HTTP status and body
 * into a [ProtocolResponse] so the adapter's status/envelope mapping runs in
 * production exactly as it does under the fake transports used in contract
 * tests. Genuine transport failures (connect/read timeout, DNS) carry no HTTP
 * status and are surfaced as [RemoteOperationError.Connection]. */
class NacosRequestExecutorProtocolTransport(
    private val executor: NacosRequestExecutor,
    private val policy: RequestPolicy = RequestPolicy.INTERACTIVE
) : ProtocolTransport {
    override suspend fun execute(request: ProtocolRequest): ProtocolResponse = when (request.method) {
        "GET" -> try {
            ProtocolResponse(200, executor.get(request.url, policy, request.headers))
        } catch (error: Throwable) {
            toResponseOrThrow(error)
        }
        "POST" -> try {
            ProtocolResponse(200, executor.post(request.url, request.body ?: "", policy, request.headers))
        } catch (error: Throwable) {
            toResponseOrThrow(error)
        }
        else -> throw RemoteOperationError.Unsupported("Unsupported HTTP method: ${'$'}{request.method}")
    }

    private fun toResponseOrThrow(error: Throwable): ProtocolResponse = when (error) {
        is NacosRequestError.Client -> ProtocolResponse(error.status, error.body)
        is NacosRequestError.Server -> ProtocolResponse(error.status, error.body)
        // Pass the sanitized body through so V1/V3 recovery predicates can classify
        // refused-or-expired tokens. Previously the body was discarded and V1
        // recovery was unreachable in production (issue #39).
        is NacosRequestError.Authentication -> ProtocolResponse(error.status, error.body)
        is NacosRequestError.RateLimited -> ProtocolResponse(429, "")
        is NacosRequestError.ConnectTimeout,
        is NacosRequestError.ReadTimeout,
        is NacosRequestError.Connection -> throw RemoteOperationError.Connection(error)
        is NacosRequestError.Protocol -> throw RemoteOperationError.Protocol(error.message ?: "Protocol failure", error)
        is RemoteOperationError -> throw error
        else -> throw RemoteOperationError.Connection(error)
    }
}

private object NoOperationCache : OperationCache {
    override suspend fun getSummaries(identity: AccessIdentity, namespaceId: String, requestKey: String): SummaryPage? = null
    override suspend fun putSummaries(
        identity: AccessIdentity,
        namespaceId: String,
        requestKey: String,
        page: SummaryPage,
        observation: Long
    ) = Unit
    override suspend fun getDetail(identity: AccessIdentity, namespaceId: String, dataId: String, group: String): NacosConfiguration? = null
    override suspend fun putDetail(
        identity: AccessIdentity,
        namespaceId: String,
        detail: NacosConfiguration,
        observation: Long
    ) = Unit
}
