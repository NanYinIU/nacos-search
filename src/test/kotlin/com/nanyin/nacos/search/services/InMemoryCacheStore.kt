package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.visibility.AccessVisibilityRecord
import java.util.concurrent.ConcurrentHashMap

/** Store adapter for tests: no disk, no application-level state. */
internal class InMemoryCacheStore : CacheStore {
    private val details = ConcurrentHashMap<String, CacheService.CacheEntry<NacosConfiguration>>()
    private val listPages = ConcurrentHashMap<String, CacheService.CacheEntry<ConfigListResponse>>()
    private val namespaceIndexes =
        ConcurrentHashMap<String, CacheService.CacheEntry<List<NacosConfiguration>>>()
    private val visibility = ConcurrentHashMap<String, AccessVisibilityRecord>()

    override suspend fun loadDetails(): Map<String, CacheService.CacheEntry<NacosConfiguration>> =
        details.toMap()

    override suspend fun loadDetail(key: String): CacheService.CacheEntry<NacosConfiguration>? =
        details[key]

    override suspend fun putDetail(key: String, entry: CacheService.CacheEntry<NacosConfiguration>) {
        details[key] = entry
    }

    override suspend fun removeDetail(key: String) {
        details.remove(key)
    }

    override suspend fun loadListPages(): Map<String, CacheService.CacheEntry<ConfigListResponse>> =
        listPages.toMap()

    override suspend fun putListPage(key: String, entry: CacheService.CacheEntry<ConfigListResponse>) {
        listPages[key] = entry
    }

    override suspend fun removeListPage(key: String) {
        listPages.remove(key)
    }

    override suspend fun loadNamespaceIndexes():
        Map<String, CacheService.CacheEntry<List<NacosConfiguration>>> =
        namespaceIndexes.toMap()

    override suspend fun putNamespaceIndex(
        key: String,
        entry: CacheService.CacheEntry<List<NacosConfiguration>>
    ) {
        namespaceIndexes[key] = entry
    }

    override suspend fun removeNamespaceIndex(key: String) {
        namespaceIndexes.remove(key)
    }

    override suspend fun loadVisibilityRecords(): Map<String, AccessVisibilityRecord> =
        visibility.toMap()

    override suspend fun putVisibilityRecord(key: String, record: AccessVisibilityRecord) {
        visibility[key] = record
    }

    override suspend fun removeVisibilityRecord(key: String) {
        visibility.remove(key)
    }

    override suspend fun clear() {
        details.clear()
        listPages.clear()
        namespaceIndexes.clear()
        visibility.clear()
    }
}
