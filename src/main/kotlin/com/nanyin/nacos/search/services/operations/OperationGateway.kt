package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.CacheMutation
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.network.RequestPolicy

/**
 * Generation-neutral boundary for read operations. Callers pass an immutable
 * [OperationTarget] captured before UI state can change; neither cache lookup
 * nor protocol dispatch reads current settings or project selection.
 *
 * Every read takes its observation sequence when the operation starts and hands
 * the same number to both the caller and the cache mutation derived from it, so
 * painting a result and writing its cache entry are ordered by one number
 * (ADR-0020 / ADR-0047). The gateway no longer keeps gates of its own: the
 * cache-entry gate lives inside the cache module, where its scope equals the
 * mutation's coordinate by construction (ADR-0044).
 */
class OperationGateway(
    private val adapters: Map<NacosApiGeneration, ProtocolAdapter>,
    private val cache: OperationCache = NoOperationCache,
    private val historyCache: HistoryMemoryCache = HistoryMemoryCache(),
    private val observationSequence: ObservationSequence = ObservationSequence.process
) {
    suspend fun probe(target: OperationTarget): Result<Unit> =
        adapterFor(target)?.probe(target) ?: unsupportedGeneration(target)

    suspend fun listSummaries(
        target: OperationTarget,
        query: SummaryQuery,
        forceRefresh: Boolean = false,
        useCache: Boolean = true
    ): Result<Observed<SummaryPage>> {
        val observation = observationSequence.next()
        if (useCache && !forceRefresh) {
            cache.getSummaries(target.context.identity, target.namespaceId, query.cacheKey())?.let {
                return Result.success(Observed(it, observation))
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        return adapter.listSummaries(target, query).map { page ->
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
        val observation = observationSequence.next()
        if (useCache && !forceRefresh) {
            cache.getDetail(target.context.identity, target.namespaceId, coordinate.dataId, coordinate.group)?.let {
                return Result.success(Observed(it, observation))
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        return adapter.readDetail(target, coordinate).map { detail ->
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
        val observation = observationSequence.next()
        if (useCache && !forceRefresh) {
            historyCache.getHistoryPage(target.context.identity, target.namespaceId, query.cacheKey())?.let {
                return Result.success(Observed(it, observation))
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        if (adapter !is HistoryCapability) {
            return Result.failure(
                RemoteOperationError.CapabilityUnsupported("Protocol adapter does not support configuration history")
            )
        }
        return adapter.listHistory(target, query).map { page ->
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
        val observation = observationSequence.next()
        if (useCache && !forceRefresh) {
            historyCache.getHistoryDetail(target.context.identity, target.namespaceId, historyId)?.let {
                return Result.success(Observed(it, observation))
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        if (adapter !is HistoryCapability) {
            return Result.failure(
                RemoteOperationError.CapabilityUnsupported("Protocol adapter does not support configuration history")
            )
        }
        return adapter.readHistoryDetail(target, historyId).map { detail ->
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
        return adapter.publish(target, command)
    }

    suspend fun discoverNamespaces(target: OperationTarget): Result<List<DiscoveredNamespace>> {
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        if (adapter !is NamespaceDiscoveryCapability) {
            return Result.failure(
                RemoteOperationError.CapabilityUnsupported("Protocol adapter does not support namespace discovery")
            )
        }
        return adapter.discoverNamespaces(target)
    }

    /**
     * Takes the observation sequence for an operation this gateway does not
     * dispatch itself — today, a full namespace-index load. ADR-0020 requires
     * the sequence to be obtained **when the operation starts**, not when it
     * writes, so that a later-started load wins even if an earlier one
     * completes after it. The caller passes the same number to
     * [com.nanyin.nacos.search.services.CacheService.apply], which decides
     * whether the mutation still lands.
     */
    fun beginObservation(): Long = observationSequence.next()

    /** Test/inspection helper: last issued observation sequence. */
    fun currentObservationSequence(): Long = observationSequence.current()

    private fun adapterFor(target: OperationTarget): ProtocolAdapter? = adapters[target.context.resolvedGeneration]

    private fun <T> unsupportedGeneration(target: OperationTarget): Result<T> = Result.failure(
        RemoteOperationError.Unsupported("No adapter is available for ${target.context.resolvedGeneration}")
    )
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
        val scope = "summary|$identity|$namespaceId|$requestKey"
        if (!highWater.accepts(listOf(scope), observation)) return
        highWater.raise(scope, observation)
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
        val scope = "detail|$key"
        if (!highWater.accepts(listOf(scope), observation)) return
        highWater.raise(scope, observation)
        details[key] = detail
    }

    private data class DetailCacheKey(
        val identity: AccessIdentity,
        val namespaceId: String,
        val dataId: String,
        val group: String
    )
}

/** Adapts the persistent cache's one gated write entry point to this seam. */
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
        cacheService.apply(
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
        cacheService.apply(
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
