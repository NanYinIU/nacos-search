package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.visibility.AccessVisibilityRecord

/**
 * The cache module's persistent store. One interface owns both halves of the
 * persistence — the entry payloads and the key list that names them — so the
 * two cannot drift apart and leave behind a payload no reclamation path can
 * ever see.
 *
 * Access-visibility records that gate those payloads live on the same contract
 * (issue #123 / ADR-0050): clear, profile deletion, and reclamation must
 * consider both.
 *
 * Production uses [FileCacheStore]. Tests inject an in-memory adapter from the
 * test source set. Payload kinds are configuration details, list pages, and
 * namespace indexes (issue #147).
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

    /** Every persisted namespace index, keyed by its storage key. */
    suspend fun loadNamespaceIndexes(): Map<String, CacheService.CacheEntry<List<NacosConfiguration>>>

    suspend fun putNamespaceIndex(
        key: String,
        entry: CacheService.CacheEntry<List<NacosConfiguration>>
    )

    suspend fun removeNamespaceIndex(key: String)

    /** Every persisted access-visibility record, keyed by its storage key. */
    suspend fun loadVisibilityRecords(): Map<String, AccessVisibilityRecord>

    suspend fun putVisibilityRecord(key: String, record: AccessVisibilityRecord)

    suspend fun removeVisibilityRecord(key: String)

    /**
     * Discards every payload and visibility record together with its key entry,
     * leaving nothing unreachable.
     */
    suspend fun clear()
}
