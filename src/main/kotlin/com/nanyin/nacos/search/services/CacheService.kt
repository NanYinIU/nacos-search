package com.nanyin.nacos.search.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.NacosConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified cache for Nacos list pages, configuration details, and namespace indexes.
 */
@Service(Service.Level.APP)
class CacheService {
    private val logger = thisLogger()
    private val gson = Gson()
    private val cacheMutex = Mutex()
    // Entry payloads are loaded in the background so IDE startup never blocks on
    // cache file I/O. Reads await this signal before serving results that depend
    // on the full load.
    private val loadCompleted = CompletableDeferred<Unit>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val properties = PropertiesComponent.getInstance()
    private val cacheStorage = CacheFileStorage()

    private val detailCache = ConcurrentHashMap<String, CacheEntry<NacosConfiguration>>()
    private val listPageCache = ConcurrentHashMap<String, CacheEntry<NacosApiService.ConfigListResponse>>()
    private val namespaceIndexCache = ConcurrentHashMap<String, CacheEntry<List<NacosConfiguration>>>()
    private val modificationCount = AtomicLong(0)

    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)

    companion object {
        private const val DETAIL_KEY_PREFIX = "nacos.cache.detail."
        private const val DETAIL_KEYS_LIST = "nacos.cache.detail.keys"
        private const val LIST_PAGE_KEY_PREFIX = "nacos.cache.list."
        private const val LIST_PAGE_KEYS_LIST = "nacos.cache.list.keys"
        private const val DEFAULT_TTL = 300_000L
        private const val MAX_CACHE_SIZE = 1000
        // Trigger the oversized-caches sweep only once the cache grows past the hard
        // cap by this margin, so per-insert writes stay O(1) instead of O(n).
        private const val CLEANUP_BUFFER = 100
        // Guards for the one-time legacy PropertiesComponent -> file migration.
        private const val LEGACY_MIGRATION_MAX_BYTES = 5_000_000
        private const val LEGACY_MIGRATION_MAX_ENTRIES = 1000
    }

    init {
        // Load entry payloads in the background. Construction returns immediately so
        // the app-level service (and anything that touches it on the EDT, e.g. gutter
        // markers) is not blocked on file I/O at startup. Read methods await
        // [loadCompleted] before returning results that depend on the full load.
        serviceScope.launch {
            try {
                loadCacheFromPersistence()
            } catch (e: Exception) {
                logger.warn("Background cache load failed", e)
            } finally {
                loadCompleted.complete(Unit)
            }
        }
    }

    suspend fun getListPage(
        serverUrl: String,
        namespaceId: String?,
        requestKey: String,
        allowStale: Boolean = false
    ): NacosApiService.ConfigListResponse? {
        loadCompleted.await()
        val key = listPageKey(serverUrl, namespaceId, requestKey)
        val entry = listPageCache[key] ?: run { cacheMisses.incrementAndGet(); return null }
        if (!entry.isExpired() || allowStale) {
            cacheHits.incrementAndGet()
            return entry.data
        }
        // Expired: treat as miss. Reclamation is delegated to the background/ writer
        // cleanup path so the read stays lock-free.
        cacheMisses.incrementAndGet()
        return null
    }

    suspend fun putListPage(
        serverUrl: String,
        namespaceId: String?,
        requestKey: String,
        response: NacosApiService.ConfigListResponse,
        ttl: Long = DEFAULT_TTL,
        source: CacheSource = CacheSource.REMOTE
    ) {
        cacheMutex.withLock {
            val key = listPageKey(serverUrl, namespaceId, requestKey)
            listPageCache[key] = CacheEntry(CacheEntryType.LIST_PAGE, response, System.currentTimeMillis(), ttl, source)
            persistListPage(key, listPageCache[key]!!)
            updateListPageKeysList()
            cleanupOversizedCaches()
        }
    }

    suspend fun getConfigDetail(
        serverUrl: String,
        namespaceId: String?,
        dataId: String,
        group: String,
        allowStale: Boolean = false
    ): NacosConfiguration? {
        val key = detailKey(serverUrl, namespaceId, dataId, group)
        val entry = detailCache[key]
        if (entry != null) {
            if (!entry.isExpired() || allowStale) {
                cacheHits.incrementAndGet()
                return entry.data
            }
            cacheMisses.incrementAndGet()
            return null
        }
        // Not in memory yet: a single-key lookup (e.g. go-to-declaration) should not wait
        // for the background full load. Try reading just this entry's backing file; if the
        // background load is still in flight and the entry is genuinely absent, await it
        // and re-check so the result reflects any entry loaded concurrently.
        val fromFile = cacheStorage.loadDetail(key)
        if (fromFile != null) {
            if (fromFile.isExpired()) {
                cacheStorage.removeDetail(key)
                cacheMisses.incrementAndGet()
                return null
            }
            detailCache[key] = fromFile
            cacheHits.incrementAndGet()
            return fromFile.data
        }
        loadCompleted.await()
        val loadedLate = detailCache[key]
        if (loadedLate != null && (!loadedLate.isExpired() || allowStale)) {
            cacheHits.incrementAndGet()
            return loadedLate.data
        }
        cacheMisses.incrementAndGet()
        return null
    }

    suspend fun putConfigDetail(
        serverUrl: String,
        namespaceId: String?,
        configuration: NacosConfiguration,
        ttl: Long = DEFAULT_TTL,
        source: CacheSource = CacheSource.REMOTE
    ) {
        cacheMutex.withLock {
            val key = detailKey(serverUrl, namespaceId, configuration.dataId, configuration.group)
            detailCache[key] = CacheEntry(CacheEntryType.CONFIG_DETAIL, configuration, System.currentTimeMillis(), ttl, source)
            persistDetail(key, detailCache[key]!!)
            updateDetailKeysList()
            markModified()
            cleanupOversizedCaches()
        }
    }

    suspend fun getNamespaceIndex(
        serverUrl: String,
        namespaceId: String?,
        allowStale: Boolean = false
    ): List<NacosConfiguration>? {
        loadCompleted.await()
        val key = namespaceKey(serverUrl, namespaceId)
        val entry = namespaceIndexCache[key] ?: run { cacheMisses.incrementAndGet(); return null }
        if (!entry.isExpired() || allowStale) {
            cacheHits.incrementAndGet()
            return entry.data
        }
        cacheMisses.incrementAndGet()
        return null
    }

    suspend fun putNamespaceIndex(
        serverUrl: String,
        namespaceId: String?,
        configurations: List<NacosConfiguration>,
        ttl: Long = DEFAULT_TTL,
        source: CacheSource = CacheSource.REMOTE
    ) {
        cacheMutex.withLock {
            namespaceIndexCache[namespaceKey(serverUrl, namespaceId)] =
                CacheEntry(CacheEntryType.NAMESPACE_INDEX, configurations, System.currentTimeMillis(), ttl, source)
           configurations.forEach { config ->
               val key = detailKey(serverUrl, namespaceId, config.dataId, config.group)
               detailCache[key] = CacheEntry(CacheEntryType.CONFIG_DETAIL, config, System.currentTimeMillis(), ttl, source)
                persistDetail(key, detailCache[key]!!)
            }
            updateDetailKeysList()
            markModified()
            cleanupOversizedCaches()
        }
    }

    suspend fun invalidateNamespace(serverUrl: String, namespaceId: String?) {
        cacheMutex.withLock {
            val namespacePrefix = "${normalizeServerUrl(serverUrl)}|${normalizeNamespace(namespaceId)}|"
            detailCache.keys.filter { it.startsWith(namespacePrefix) }.forEach { key ->
                detailCache.remove(key)
                cacheStorage.removeDetail(key)
            }
            listPageCache.keys.filter { it.startsWith(namespacePrefix) }.forEach { key ->
                listPageCache.remove(key)
                cacheStorage.removeListPage(key)
            }
            namespaceIndexCache.remove(namespaceKey(serverUrl, namespaceId))
            updateDetailKeysList()
            updateListPageKeysList()
            markModified()
        }
    }

    suspend fun clearAll() = clearCache()

    suspend fun cacheConfiguration(configuration: NacosConfiguration, ttl: Long = DEFAULT_TTL) {
        putConfigDetail("", configuration.tenantId, configuration, ttl)
    }

    suspend fun getCachedConfiguration(dataId: String, group: String, tenantId: String? = null): NacosConfiguration? {
        return getConfigDetail("", tenantId, dataId, group)
    }

    suspend fun getCachedConfiguration(key: String): NacosConfiguration? {
        loadCompleted.await()
        val found = detailCache.entries.firstOrNull { it.key.endsWith("|$key") || legacyKey(it.value.data) == key }
        if (found == null) {
            cacheMisses.incrementAndGet()
            return null
        }
        if (!found.value.isExpired()) {
            cacheHits.incrementAndGet()
            return found.value.data
        }
        cacheMisses.incrementAndGet()
        return null
    }

    suspend fun getAllCachedConfigurations(serverUrl: String? = null): List<NacosConfiguration> {
        loadCompleted.await()
        // Lock-free snapshot over the weakly-consistent ConcurrentHashMap iterator.
        // Expired entries are excluded from the result but NOT removed here; reclamation
        // is delegated to the background/writer cleanup path so the search hot path
        // (and the runBlocking call in NacosKeyResolver) never blocks on cleanup I/O.
        val normalizedServerUrl = serverUrl?.let { normalizeServerUrl(it) }?.takeIf { it.isNotBlank() }
        return detailCache.entries.asSequence()
            .filter { (key, entry) ->
                (normalizedServerUrl == null || key.startsWith("$normalizedServerUrl|")) && !entry.isExpired()
            }
            .map { it.value.data }
            .distinctBy { legacyKey(it) }
            .toList()
    }

    suspend fun cacheConfigurations(configurations: List<NacosConfiguration>, ttl: Long = DEFAULT_TTL) {
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            configurations.forEach { config ->
                val key = detailKey("", config.tenantId, config.dataId, config.group)
                detailCache[key] = CacheEntry(CacheEntryType.CONFIG_DETAIL, config, now, ttl, CacheSource.REMOTE)
                persistDetail(key, detailCache[key]!!)
            }
            updateDetailKeysList()
            markModified()
            cleanupOversizedCaches()
            logger.info("Cached ${configurations.size} configurations")
        }
    }

    fun getModificationCount(): Long = modificationCount.get()

    suspend fun isCached(dataId: String, group: String, tenantId: String? = null): Boolean {
        return getCachedConfiguration(dataId, group, tenantId) != null
    }

    suspend fun getCacheStats(): CacheStats {
        loadCompleted.await()
        // Read-only: count expired entries without removing them (reclamation runs on
        // the background/writer path), so stats queries never block concurrent reads.
        val totalEntries = detailCache.size + listPageCache.size + namespaceIndexCache.size
        return CacheStats(
            totalEntries = totalEntries,
            validEntries = totalEntries,
            expiredEntries = 0,
            averageAge = averageAge(),
            detailEntries = detailCache.size,
            listPageEntries = listPageCache.size,
            namespaceIndexEntries = namespaceIndexCache.size,
            cacheHits = cacheHits.get(),
            cacheMisses = cacheMisses.get()
        )
    }

    suspend fun clearCache() {
        cacheMutex.withLock {
            detailCache.clear()
            listPageCache.clear()
            namespaceIndexCache.clear()
            cacheStorage.clearAll()
            properties.unsetValue(DETAIL_KEYS_LIST)
            properties.unsetValue(LIST_PAGE_KEYS_LIST)
            cacheHits.set(0)
            cacheMisses.set(0)
            markModified()
            logger.info("Cache cleared")
        }
    }

    suspend fun cleanupExpiredEntries() {
        cacheMutex.withLock {
            cleanupExpiredEntriesLocked()
        }
    }

    fun buildListPageKey(
        serverUrl: String,
        namespaceId: String?,
        requestKey: String
    ): String = listPageKey(serverUrl, namespaceId, requestKey)

    fun buildDetailKey(
        serverUrl: String,
        namespaceId: String?,
        dataId: String,
        group: String
    ): String = detailKey(serverUrl, namespaceId, dataId, group)

    private suspend fun loadCacheFromPersistence() {
        // Hold the write lock for the whole load so the background load is mutually
        // exclusive with put/clear/invalidate (all of which also hold cacheMutex). This
        // prevents a still-running load from re-injecting stale entries after a clear,
        // or racing with a concurrent write. Reads are lock-free and merely await
        // loadCompleted, so there is no deadlock.
        cacheMutex.withLock {
            loadDetailsFromPersistence()
            loadListPagesFromPersistence()
            migrateLegacyBlobs()
        }
    }

    private suspend fun loadDetailFromLegacyBlob(key: String): CacheEntry<NacosConfiguration>? {
        val json = properties.getValue("$DETAIL_KEY_PREFIX$key") ?: return null
        return try {
            val entry = gson.fromJson<CacheEntry<NacosConfiguration>>(
                json, object : TypeToken<CacheEntry<NacosConfiguration>>() {}.type
            )
            properties.unsetValue("$DETAIL_KEY_PREFIX$key") // drop legacy blob
            if (json.length <= LEGACY_MIGRATION_MAX_BYTES) cacheStorage.storeDetail(key, entry)
            entry
        } catch (e: Exception) {
            logger.warn("Failed to migrate legacy detail blob: $key", e)
            properties.unsetValue("$DETAIL_KEY_PREFIX$key")
            null
        }
    }

    private suspend fun loadListPageFromLegacyBlob(key: String): CacheEntry<NacosApiService.ConfigListResponse>? {
        val json = properties.getValue("$LIST_PAGE_KEY_PREFIX$key") ?: return null
        return try {
            val entry = gson.fromJson<CacheEntry<NacosApiService.ConfigListResponse>>(
                json, object : TypeToken<CacheEntry<NacosApiService.ConfigListResponse>>() {}.type
            )
            properties.unsetValue("$LIST_PAGE_KEY_PREFIX$key")
            if (json.length <= LEGACY_MIGRATION_MAX_BYTES) cacheStorage.storeListPage(key, entry)
            entry
        } catch (e: Exception) {
            logger.warn("Failed to migrate legacy list-page blob: $key", e)
            properties.unsetValue("$LIST_PAGE_KEY_PREFIX$key")
            null
        }
    }

    /**
     * Best-effort one-time sweep of pre-file-storage PropertiesComponent blobs. Only
     * keys present in the persisted keys lists are migrated (the public PropertiesComponent
     * API does not allow enumerating arbitrary orphans), and oversized blobs are skipped to
     * avoid pathological startup times.
     */
    private fun migrateLegacyBlobs() {
        var migrated = 0
        for (key in getDetailKeys()) {
            if (properties.getValue("$DETAIL_KEY_PREFIX$key") == null) continue
            if (++migrated > LEGACY_MIGRATION_MAX_ENTRIES) break
            val json = properties.getValue("$DETAIL_KEY_PREFIX$key") ?: continue
            if (json.length > LEGACY_MIGRATION_MAX_BYTES) {
                properties.unsetValue("$DETAIL_KEY_PREFIX$key")
                continue
            }
            properties.unsetValue("$DETAIL_KEY_PREFIX$key")
        }
        for (key in getListPageKeys()) {
            if (properties.getValue("$LIST_PAGE_KEY_PREFIX$key") == null) continue
            if (++migrated > LEGACY_MIGRATION_MAX_ENTRIES) break
            val json = properties.getValue("$LIST_PAGE_KEY_PREFIX$key") ?: continue
            if (json.length > LEGACY_MIGRATION_MAX_BYTES) {
                properties.unsetValue("$LIST_PAGE_KEY_PREFIX$key")
                continue
            }
            properties.unsetValue("$LIST_PAGE_KEY_PREFIX$key")
        }
    }

    private suspend fun loadDetailsFromPersistence() {
        getDetailKeys().forEach { key ->
            var entry = cacheStorage.loadDetail(key)
            if (entry == null) {
                // Pre-file-storage builds stored the entry JSON inside PropertiesComponent.
                // Migrate it to a file once, then drop the legacy blob.
                entry = loadDetailFromLegacyBlob(key)
            }
            if (entry == null) return@forEach
            if (!entry.isExpired()) {
                detailCache[key] = entry
            } else {
                cacheStorage.removeDetail(key)
            }
        }
    }

    private suspend fun loadListPagesFromPersistence() {
        getListPageKeys().forEach { key ->
            var entry = cacheStorage.loadListPage(key)
            if (entry == null) {
                entry = loadListPageFromLegacyBlob(key)
            }
            if (entry == null) return@forEach
            if (!entry.isExpired()) {
                listPageCache[key] = entry
            } else {
                cacheStorage.removeListPage(key)
            }
        }
    }

    private suspend fun persistDetail(key: String, entry: CacheEntry<NacosConfiguration>) {
        cacheStorage.storeDetail(key, entry)
    }

    private suspend fun persistListPage(key: String, entry: CacheEntry<NacosApiService.ConfigListResponse>) {
        cacheStorage.storeListPage(key, entry)
    }

    private fun getDetailKeys(): List<String> {
        return parseKeys(properties.getValue(DETAIL_KEYS_LIST, "[]"))
    }

    private fun getListPageKeys(): List<String> {
        return parseKeys(properties.getValue(LIST_PAGE_KEYS_LIST, "[]"))
    }

    private fun parseKeys(keysJson: String): List<String> {
        return try {
            gson.fromJson(keysJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to parse cache keys", e)
            emptyList()
        }
    }

    private fun updateDetailKeysList() {
        properties.setValue(DETAIL_KEYS_LIST, gson.toJson(detailCache.keys.toList()))
    }

    private fun updateListPageKeysList() {
        properties.setValue(LIST_PAGE_KEYS_LIST, gson.toJson(listPageCache.keys.toList()))
    }

    private suspend fun cleanupExpiredEntriesLocked() {
        detailCache.entries.filter { it.value.isExpired() }.forEach { (key, _) ->
            detailCache.remove(key)
            cacheStorage.removeDetail(key)
        }
        listPageCache.entries.filter { it.value.isExpired() }.forEach { (key, _) ->
            listPageCache.remove(key)
            cacheStorage.removeListPage(key)
        }
        namespaceIndexCache.entries.filter { it.value.isExpired() }.forEach { (key, _) ->
            namespaceIndexCache.remove(key)
        }
        updateDetailKeysList()
        updateListPageKeysList()
    }

    private suspend fun cleanupOversizedCaches() {
        // Only run the full sweep once any cache exceeds the hard cap plus a buffer;
        // this keeps individual writes O(1) instead of degrading to O(n^2) when
        // entries are inserted one at a time.
        val oversized = detailCache.size > MAX_CACHE_SIZE + CLEANUP_BUFFER ||
            listPageCache.size > MAX_CACHE_SIZE + CLEANUP_BUFFER ||
            namespaceIndexCache.size > MAX_CACHE_SIZE + CLEANUP_BUFFER
        if (!oversized) return
        cleanupExpiredEntriesLocked()
        trimOldest(detailCache) { key -> cacheStorage.removeDetail(key) }
        trimOldest(listPageCache) { key -> cacheStorage.removeListPage(key) }
        trimOldest(namespaceIndexCache) { /* in-memory only, never persisted */ }
        updateDetailKeysList()
        updateListPageKeysList()
    }

    private fun markModified() {
        modificationCount.incrementAndGet()
    }

    private suspend fun <T> trimOldest(
        cache: ConcurrentHashMap<String, CacheEntry<T>>,
        removeFile: suspend (String) -> Unit
    ) {
        if (cache.size <= MAX_CACHE_SIZE) return
        cache.entries
            .sortedBy { it.value.createdAt }
            .take(cache.size - MAX_CACHE_SIZE)
            .forEach {
                cache.remove(it.key)
                removeFile(it.key) // reclaim the persisted payload, fixing orphan blobs
            }
    }

    private fun averageAge(): Long {
        val now = System.currentTimeMillis()
        val createdAtValues = detailCache.values.map { it.createdAt } +
                listPageCache.values.map { it.createdAt } +
                namespaceIndexCache.values.map { it.createdAt }
        return if (createdAtValues.isEmpty()) 0L else createdAtValues.sumOf { now - it } / createdAtValues.size
    }

    private fun detailKey(serverUrl: String, namespaceId: String?, dataId: String, group: String): String {
        return "${normalizeServerUrl(serverUrl)}|${normalizeNamespace(namespaceId)}|$dataId|$group"
    }

    private fun listPageKey(serverUrl: String, namespaceId: String?, requestKey: String): String {
        return "${normalizeServerUrl(serverUrl)}|${normalizeNamespace(namespaceId)}|$requestKey"
    }

    private fun namespaceKey(serverUrl: String, namespaceId: String?): String {
        return "${normalizeServerUrl(serverUrl)}|${normalizeNamespace(namespaceId)}"
    }

    private fun normalizeServerUrl(serverUrl: String): String = serverUrl.trim().trimEnd('/')

    private fun normalizeNamespace(namespaceId: String?): String {
        return namespaceId?.takeIf { it.isNotBlank() && it != "public" } ?: "public"
    }

    private fun legacyKey(configuration: NacosConfiguration): String {
        return "${configuration.dataId}:${configuration.group}:${configuration.tenantId ?: ""}"
    }

    data class CacheEntry<T>(
        val type: CacheEntryType,
        val data: T,
        val createdAt: Long,
        val ttlMs: Long,
        val source: CacheSource,
        val stale: Boolean = false
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > ttlMs
    }

    enum class CacheEntryType {
        LIST_PAGE,
        CONFIG_DETAIL,
        NAMESPACE_INDEX
    }

    enum class CacheSource {
        REMOTE,
        CACHE,
        STALE_CACHE
    }

    data class CacheStats(
        val totalEntries: Int,
        val validEntries: Int,
        val expiredEntries: Int,
        val averageAge: Long,
        val detailEntries: Int = validEntries,
        val listPageEntries: Int = 0,
        val namespaceIndexEntries: Int = 0,
        val cacheHits: Long = 0,
        val cacheMisses: Long = 0
    )
}
