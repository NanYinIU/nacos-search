package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import java.util.LinkedHashMap

/**
 * Bounded in-memory cache for read-only configuration history. Entries are
 * scoped by complete access identity, namespace, and configuration coordinate.
 *
 * History writes go to this separate in-memory store rather than to the
 * persistent cache, but they take the same gate: a stale history page cannot
 * overwrite a newer one (ADR-0020). The gate is the one
 * [ObservationHighWater] implementation, keyed on the same complete access
 * identity the entries themselves are keyed on.
 *
 * History data is **never persisted** and never offered offline. P1 promises
 * no offline history. Eviction is LRU by access order.
 */
class HistoryMemoryCache(
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val maxDetails: Int = DEFAULT_MAX_DETAILS
) {
    private val highWater = ObservationHighWater()
    private val pageStore = object : LinkedHashMap<String, HistoryPage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HistoryPage>?): Boolean =
            size > maxPages
    }

    private val detailStore = object : LinkedHashMap<String, HistoryDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HistoryDetail>?): Boolean =
            size > maxDetails
    }

    @Synchronized
    fun getHistoryPage(identity: AccessIdentity, namespaceId: String, cacheKey: String): HistoryPage? =
        pageStore[pageKey(identity, namespaceId, cacheKey)]

    /** Returns false when a later-started history read already owns this key. */
    @Synchronized
    fun putHistoryPage(
        identity: AccessIdentity,
        namespaceId: String,
        cacheKey: String,
        page: HistoryPage,
        observation: Long
    ): Boolean {
        val key = pageKey(identity, namespaceId, cacheKey)
        val scope = "page|$key"
        if (!highWater.accepts(listOf(scope), observation)) return false
        highWater.raise(scope, observation)
        pageStore[key] = page
        return true
    }

    @Synchronized
    fun getHistoryDetail(
        identity: AccessIdentity,
        namespaceId: String,
        historyId: String
    ): HistoryDetail? = detailStore[detailKey(identity, namespaceId, historyId)]

    /** Returns false when a later-started history read already owns this key. */
    @Synchronized
    fun putHistoryDetail(
        identity: AccessIdentity,
        namespaceId: String,
        historyId: String,
        detail: HistoryDetail,
        observation: Long
    ): Boolean {
        val key = detailKey(identity, namespaceId, historyId)
        val scope = "detail|$key"
        if (!highWater.accepts(listOf(scope), observation)) return false
        highWater.raise(scope, observation)
        detailStore[key] = detail
        return true
    }

    @Synchronized
    fun clearForIdentity(identity: AccessIdentity) {
        val profilePrefix = "${identity.profileId}|${identity.accessRevision}|"
        pageStore.keys.removeIf { it.startsWith(profilePrefix) }
        detailStore.keys.removeIf { it.startsWith(profilePrefix) }
    }

    @Synchronized
    fun clearAll() {
        pageStore.clear()
        detailStore.clear()
    }

    private fun pageKey(identity: AccessIdentity, namespaceId: String, cacheKey: String): String =
        "${identity.profileId}|${identity.accessRevision}|${identity.canonicalEndpoint}|" +
            "${identity.resolvedGeneration}|${identity.authMode}|${identity.principal}|" +
            "${namespaceId}|$cacheKey"

    private fun detailKey(
        identity: AccessIdentity,
        namespaceId: String,
        historyId: String
    ): String =
        "${identity.profileId}|${identity.accessRevision}|${identity.canonicalEndpoint}|" +
            "${identity.resolvedGeneration}|${identity.authMode}|${identity.principal}|" +
            "${namespaceId}|$historyId"

    private companion object {
        const val DEFAULT_MAX_PAGES = 100
        const val DEFAULT_MAX_DETAILS = 200
    }
}
