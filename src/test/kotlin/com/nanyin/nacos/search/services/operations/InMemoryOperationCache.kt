package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosConfiguration

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
