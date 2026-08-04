package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import java.util.concurrent.ConcurrentHashMap

/**
 * The cache module's persistent store. One interface owns both halves of the
 * persistence — the entry payloads and the key list that names them — so the
 * two cannot drift apart and leave behind a payload no reclamation path can
 * ever see.
 *
 * Two adapters implement it: [FileCacheStore] in production and
 * [InMemoryCacheStore] in tests.
 */
internal interface CacheStore {
    /** Every persisted configuration detail, keyed by its storage key. */
    suspend fun loadDetails(): Map<String, CacheService.CacheEntry<NacosConfiguration>>

    /** One detail by key, without enumerating the rest (the go-to-declaration path). */
    suspend fun loadDetail(key: String): CacheService.CacheEntry<NacosConfiguration>?

    suspend fun putDetail(key: String, entry: CacheService.CacheEntry<NacosConfiguration>)

    suspend fun removeDetail(key: String)

    /** Every persisted list page, keyed by its storage key. */
    suspend fun loadListPages(): Map<String, CacheService.CacheEntry<ConfigListResponse>>

    suspend fun putListPage(key: String, entry: CacheService.CacheEntry<ConfigListResponse>)

    suspend fun removeListPage(key: String)

    /** Discards every payload together with its key entry, leaving nothing unreachable. */
    suspend fun clear()
}

/** Store adapter for tests: no disk, no application-level state. */
internal class InMemoryCacheStore : CacheStore {
    private val details = ConcurrentHashMap<String, CacheService.CacheEntry<NacosConfiguration>>()
    private val listPages = ConcurrentHashMap<String, CacheService.CacheEntry<ConfigListResponse>>()

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

    override suspend fun clear() {
        details.clear()
        listPages.clear()
    }
}
