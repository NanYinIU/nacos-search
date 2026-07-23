package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.network.RequestPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * Generation-neutral boundary for read operations. Callers pass an immutable
 * [OperationTarget] captured before UI state can change; neither cache lookup
 * nor protocol dispatch reads current settings or project selection.
 */
class OperationGateway(
    private val adapters: Map<NacosApiGeneration, ProtocolAdapter>,
    private val cache: OperationCache = NoOperationCache,
    private val historyCache: HistoryMemoryCache = HistoryMemoryCache(),
    private val observationSequence: ObservationSequence = ObservationSequence(),
    private val observationGates: ConcurrentHashMap<String, ObservationGate> = ConcurrentHashMap()
) {
    suspend fun probe(target: OperationTarget): Result<Unit> =
        adapterFor(target)?.probe(target) ?: unsupportedGeneration(target)

    suspend fun listSummaries(
        target: OperationTarget,
        query: SummaryQuery,
        forceRefresh: Boolean = false,
        useCache: Boolean = true
    ): Result<SummaryPage> {
        if (useCache && !forceRefresh) {
            cache.getSummaries(target.context.identity, target.namespaceId, query.cacheKey())?.let {
                return Result.success(it)
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        val seq = observationSequence.next()
        return adapter.listSummaries(target, query).onSuccess { page ->
            if (useCache && acceptObservation(summaryGateKey(target, query), seq)) {
                cache.putSummaries(target.context.identity, target.namespaceId, query.cacheKey(), page)
            }
        }
    }

    suspend fun readDetail(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate,
        forceRefresh: Boolean = false,
        useCache: Boolean = true
    ): Result<NacosConfiguration?> {
        if (useCache && !forceRefresh) {
            cache.getDetail(target.context.identity, target.namespaceId, coordinate.dataId, coordinate.group)?.let {
                return Result.success(it)
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        val seq = observationSequence.next()
        return adapter.readDetail(target, coordinate).onSuccess { detail ->
            if (useCache && detail != null && acceptObservation(detailGateKey(target, coordinate), seq)) {
                cache.putDetail(target.context.identity, target.namespaceId, detail)
            }
        }
    }


    suspend fun listHistory(
        target: OperationTarget,
        query: HistoryQuery,
        forceRefresh: Boolean = false,
        useCache: Boolean = true
    ): Result<HistoryPage> {
        if (useCache && !forceRefresh) {
            historyCache.getHistoryPage(target.context.identity, target.namespaceId, query.cacheKey())?.let {
                return Result.success(it)
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        if (adapter !is HistoryCapability) {
            return Result.failure(
                RemoteOperationError.CapabilityUnsupported("Protocol adapter does not support configuration history")
            )
        }
        return adapter.listHistory(target, query).onSuccess { page ->
            if (useCache) {
                historyCache.putHistoryPage(target.context.identity, target.namespaceId, query.cacheKey(), page)
            }
        }
    }

    suspend fun readHistoryDetail(
        target: OperationTarget,
        historyId: String,
        forceRefresh: Boolean = false,
        useCache: Boolean = true
    ): Result<HistoryDetail> {
        if (useCache && !forceRefresh) {
            historyCache.getHistoryDetail(target.context.identity, target.namespaceId, historyId)?.let {
                return Result.success(it)
            }
        }
        val adapter = adapterFor(target) ?: return unsupportedGeneration(target)
        if (adapter !is HistoryCapability) {
            return Result.failure(
                RemoteOperationError.CapabilityUnsupported("Protocol adapter does not support configuration history")
            )
        }
        return adapter.readHistoryDetail(target, historyId).onSuccess { detail ->
            if (useCache) {
                historyCache.putHistoryDetail(
                    target.context.identity, target.namespaceId, historyId, detail
                )
            }
        }
    }

    private fun acceptObservation(key: String, seq: Long): Boolean =
        observationGates.computeIfAbsent(key) { ObservationGate() }.acceptIfNewer(seq)

    private fun summaryGateKey(target: OperationTarget, query: SummaryQuery): String =
        "summary|" + target.context.identity.profileId + "|" +
            target.context.identity.accessRevision + "|" +
            target.namespaceId + "|" + query.cacheKey()

    private fun detailGateKey(target: OperationTarget, coordinate: ConfigurationCoordinate): String =
        "detail|" + target.context.identity.profileId + "|" +
            target.context.identity.accessRevision + "|" +
            target.namespaceId + "|" + coordinate.dataId + "|" + coordinate.group

    private fun adapterFor(target: OperationTarget): ProtocolAdapter? = adapters[target.context.resolvedGeneration]

    private fun <T> unsupportedGeneration(target: OperationTarget): Result<T> = Result.failure(
        RemoteOperationError.Unsupported("No adapter is available for ${target.context.resolvedGeneration}")
    )
}

/** Cache seam for gateway tests and the identity-scoped production cache bridge. */
interface OperationCache {
    suspend fun getSummaries(identity: AccessIdentity, namespaceId: String, requestKey: String): SummaryPage?
    suspend fun putSummaries(identity: AccessIdentity, namespaceId: String, requestKey: String, page: SummaryPage)
    suspend fun getDetail(identity: AccessIdentity, namespaceId: String, dataId: String, group: String): NacosConfiguration?
    suspend fun putDetail(identity: AccessIdentity, namespaceId: String, detail: NacosConfiguration)
}

class InMemoryOperationCache : OperationCache {
    private val summaries = mutableMapOf<Triple<AccessIdentity, String, String>, SummaryPage>()
    private val details = mutableMapOf<DetailCacheKey, NacosConfiguration>()

    override suspend fun getSummaries(identity: AccessIdentity, namespaceId: String, requestKey: String): SummaryPage? =
        summaries[Triple(identity, namespaceId, requestKey)]

    override suspend fun putSummaries(identity: AccessIdentity, namespaceId: String, requestKey: String, page: SummaryPage) {
        summaries[Triple(identity, namespaceId, requestKey)] = page
    }

    override suspend fun getDetail(
        identity: AccessIdentity,
        namespaceId: String,
        dataId: String,
        group: String
    ): NacosConfiguration? = details[DetailCacheKey(identity, namespaceId, dataId, group)]

    override suspend fun putDetail(identity: AccessIdentity, namespaceId: String, detail: NacosConfiguration) {
        details[DetailCacheKey(identity, namespaceId, detail.dataId, detail.group)] = detail
    }

    private data class DetailCacheKey(
        val identity: AccessIdentity,
        val namespaceId: String,
        val dataId: String,
        val group: String
    )
}

/** Adapts the existing persistent cache without weakening its identity-scoped keys. */
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
        page: SummaryPage
    ) {
        cacheService.putListPage(identity, namespaceId, requestKey, page.toLegacyResponse(), ttlMillis())
    }

    override suspend fun getDetail(
        identity: AccessIdentity,
        namespaceId: String,
        dataId: String,
        group: String
    ): NacosConfiguration? = cacheService.getConfigDetail(identity, namespaceId, dataId, group)

    override suspend fun putDetail(identity: AccessIdentity, namespaceId: String, detail: NacosConfiguration) {
        cacheService.putConfigDetail(identity, namespaceId, detail, ttlMillis())
    }

    private fun SummaryPage.toLegacyResponse(): NacosApiService.ConfigListResponse = NacosApiService.ConfigListResponse(
        totalCount = totalCount,
        pageNumber = pageNumber,
        pagesAvailable = pagesAvailable,
        pageItems = items.mapIndexed { index, item ->
            NacosApiService.ConfigItem(
                id = index.toString(),
                dataId = item.dataId,
                group = item.group,
                content = item.content,
                type = item.type,
                tenant = item.tenantId
            )
        }
    )

    private fun NacosApiService.ConfigListResponse.toSummaryPage(): SummaryPage = SummaryPage(
        totalCount = totalCount,
        pageNumber = pageNumber,
        pagesAvailable = pagesAvailable,
        items = pageItems.map { item ->
            ConfigurationSummary(item.dataId, item.group, item.tenant, item.content, item.type)
        }
    )
}

/** Production transport for both V1 and V3 read and write operations. */
class NacosRequestExecutorProtocolTransport(
    private val executor: NacosRequestExecutor,
    private val policy: RequestPolicy = RequestPolicy.INTERACTIVE
) : ProtocolTransport {
    override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
        return when (request.method) {
            "GET" -> ProtocolResponse(200, executor.get(request.url, policy, request.headers))
            "POST" -> {
                val body = request.body ?: ""
                val url = if (request.query.isEmpty()) request.url else request.url
                ProtocolResponse(200, executor.post(url, body, policy, request.headers))
            }
            else -> throw RemoteOperationError.Unsupported("Unsupported HTTP method: ${'$'}{request.method}")
        }
    }
}

private object NoOperationCache : OperationCache {
    override suspend fun getSummaries(identity: AccessIdentity, namespaceId: String, requestKey: String): SummaryPage? = null
    override suspend fun putSummaries(identity: AccessIdentity, namespaceId: String, requestKey: String, page: SummaryPage) = Unit
    override suspend fun getDetail(identity: AccessIdentity, namespaceId: String, dataId: String, group: String): NacosConfiguration? = null
    override suspend fun putDetail(identity: AccessIdentity, namespaceId: String, detail: NacosConfiguration) = Unit
}
