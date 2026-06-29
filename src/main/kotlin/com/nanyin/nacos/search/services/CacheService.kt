package com.nanyin.nacos.search.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.NacosConfiguration
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
    private val properties = PropertiesComponent.getInstance()

    private val detailCache = ConcurrentHashMap<String, CacheEntry<NacosConfiguration>>()
    private val listPageCache = ConcurrentHashMap<String, CacheEntry<NacosApiService.ConfigListResponse>>()
    private val namespaceIndexCache = ConcurrentHashMap<String, CacheEntry<List<NacosConfiguration>>>()
    private val modificationCount = AtomicLong(0)

    private var cacheHits = 0L
    private var cacheMisses = 0L

    companion object {
        private const val DETAIL_KEY_PREFIX = "nacos.cache.detail."
        private const val DETAIL_KEYS_LIST = "nacos.cache.detail.keys"
        private const val LIST_PAGE_KEY_PREFIX = "nacos.cache.list."
        private const val LIST_PAGE_KEYS_LIST = "nacos.cache.list.keys"
        private const val DEFAULT_TTL = 300_000L
        private const val MAX_CACHE_SIZE = 1000
    }

    init {
        loadCacheFromPersistence()
    }

    suspend fun getListPage(
        serverUrl: String,
        namespaceId: String?,
        requestKey: String,
        allowStale: Boolean = false
    ): NacosApiService.ConfigListResponse? = cacheMutex.withLock {
        val key = listPageKey(serverUrl, namespaceId, requestKey)
        val entry = listPageCache[key]
        if (entry == null) {
            cacheMisses++
            return@withLock null
        }
        if (!entry.isExpired() || allowStale) {
            cacheHits++
            entry.data
        } else {
            cacheMisses++
            listPageCache.remove(key)
            properties.unsetValue("$LIST_PAGE_KEY_PREFIX$key")
            updateListPageKeysList()
            null
        }
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
    ): NacosConfiguration? = cacheMutex.withLock {
        val key = detailKey(serverUrl, namespaceId, dataId, group)
        val entry = detailCache[key]
        if (entry == null) {
            cacheMisses++
            return@withLock null
        }
        if (!entry.isExpired() || allowStale) {
            cacheHits++
            entry.data
        } else {
            cacheMisses++
            detailCache.remove(key)
            properties.unsetValue("$DETAIL_KEY_PREFIX$key")
            updateDetailKeysList()
            null
        }
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
    ): List<NacosConfiguration>? = cacheMutex.withLock {
        val key = namespaceKey(serverUrl, namespaceId)
        val entry = namespaceIndexCache[key]
        if (entry == null) {
            cacheMisses++
            return@withLock null
        }
        if (!entry.isExpired() || allowStale) {
            cacheHits++
            entry.data
        } else {
            cacheMisses++
            namespaceIndexCache.remove(key)
            null
        }
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
                properties.unsetValue("$DETAIL_KEY_PREFIX$key")
            }
            listPageCache.keys.filter { it.startsWith(namespacePrefix) }.forEach { key ->
                listPageCache.remove(key)
                properties.unsetValue("$LIST_PAGE_KEY_PREFIX$key")
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

    suspend fun getCachedConfiguration(key: String): NacosConfiguration? = cacheMutex.withLock {
        val found = detailCache.entries.firstOrNull { it.key.endsWith("|$key") || legacyKey(it.value.data) == key }
        if (found == null) {
            cacheMisses++
            return@withLock null
        }
        if (!found.value.isExpired()) {
            cacheHits++
            found.value.data
        } else {
            cacheMisses++
            detailCache.remove(found.key)
            null
        }
    }

    suspend fun getAllCachedConfigurations(serverUrl: String? = null): List<NacosConfiguration> = cacheMutex.withLock {
        cleanupExpiredEntriesLocked()
        val normalizedServerUrl = serverUrl?.let { normalizeServerUrl(it) }?.takeIf { it.isNotBlank() }
        detailCache.entries.asSequence()
            .filter { (key, _) ->
                normalizedServerUrl == null || key.startsWith("$normalizedServerUrl|")
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

    suspend fun getCacheStats(): CacheStats = cacheMutex.withLock {
        cleanupExpiredEntriesLocked()
        CacheStats(
            totalEntries = detailCache.size + listPageCache.size + namespaceIndexCache.size,
            validEntries = detailCache.size + listPageCache.size + namespaceIndexCache.size,
            expiredEntries = 0,
            averageAge = averageAge(),
            detailEntries = detailCache.size,
            listPageEntries = listPageCache.size,
            namespaceIndexEntries = namespaceIndexCache.size,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses
        )
    }

    suspend fun clearCache() {
        cacheMutex.withLock {
            detailCache.clear()
            listPageCache.clear()
            namespaceIndexCache.clear()
            getDetailKeys().forEach { properties.unsetValue("$DETAIL_KEY_PREFIX$it") }
            getListPageKeys().forEach { properties.unsetValue("$LIST_PAGE_KEY_PREFIX$it") }
            properties.unsetValue(DETAIL_KEYS_LIST)
            properties.unsetValue(LIST_PAGE_KEYS_LIST)
            cacheHits = 0
            cacheMisses = 0
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

    private fun loadCacheFromPersistence() {
        loadDetailsFromPersistence()
        loadListPagesFromPersistence()
    }

    private fun loadDetailsFromPersistence() {
        getDetailKeys().forEach { key ->
            val json = properties.getValue("$DETAIL_KEY_PREFIX$key") ?: return@forEach
            try {
                val entry = gson.fromJson<CacheEntry<NacosConfiguration>>(
                    json,
                    object : TypeToken<CacheEntry<NacosConfiguration>>() {}.type
                )
                if (!entry.isExpired()) {
                    detailCache[key] = entry
                } else {
                    properties.unsetValue("$DETAIL_KEY_PREFIX$key")
                }
            } catch (e: Exception) {
                logger.warn("Failed to load cached configuration detail: $key", e)
                properties.unsetValue("$DETAIL_KEY_PREFIX$key")
            }
        }
    }

    private fun loadListPagesFromPersistence() {
        getListPageKeys().forEach { key ->
            val json = properties.getValue("$LIST_PAGE_KEY_PREFIX$key") ?: return@forEach
            try {
                val entry = gson.fromJson<CacheEntry<NacosApiService.ConfigListResponse>>(
                    json,
                    object : TypeToken<CacheEntry<NacosApiService.ConfigListResponse>>() {}.type
                )
                if (!entry.isExpired()) {
                    listPageCache[key] = entry
                } else {
                    properties.unsetValue("$LIST_PAGE_KEY_PREFIX$key")
                }
            } catch (e: Exception) {
                logger.warn("Failed to load cached list page: $key", e)
                properties.unsetValue("$LIST_PAGE_KEY_PREFIX$key")
            }
        }
    }

    private fun persistDetail(key: String, entry: CacheEntry<NacosConfiguration>) {
        try {
            properties.setValue("$DETAIL_KEY_PREFIX$key", gson.toJson(entry))
        } catch (e: Exception) {
            logger.warn("Failed to persist configuration detail: $key", e)
        }
    }

    private fun persistListPage(key: String, entry: CacheEntry<NacosApiService.ConfigListResponse>) {
        try {
            properties.setValue("$LIST_PAGE_KEY_PREFIX$key", gson.toJson(entry))
        } catch (e: Exception) {
            logger.warn("Failed to persist list page: $key", e)
        }
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

    private fun cleanupExpiredEntriesLocked() {
        detailCache.entries.filter { it.value.isExpired() }.forEach { (key, _) ->
            detailCache.remove(key)
            properties.unsetValue("$DETAIL_KEY_PREFIX$key")
        }
        listPageCache.entries.filter { it.value.isExpired() }.forEach { (key, _) ->
            listPageCache.remove(key)
            properties.unsetValue("$LIST_PAGE_KEY_PREFIX$key")
        }
        namespaceIndexCache.entries.filter { it.value.isExpired() }.forEach { (key, _) ->
            namespaceIndexCache.remove(key)
        }
        updateDetailKeysList()
        updateListPageKeysList()
    }

    private fun cleanupOversizedCaches() {
        cleanupExpiredEntriesLocked()
        trimOldest(detailCache)
        trimOldest(listPageCache)
        trimOldest(namespaceIndexCache)
        updateDetailKeysList()
        updateListPageKeysList()
    }

    private fun markModified() {
        modificationCount.incrementAndGet()
    }

    private fun <T> trimOldest(cache: ConcurrentHashMap<String, CacheEntry<T>>) {
        if (cache.size <= MAX_CACHE_SIZE) return
        cache.entries
            .sortedBy { it.value.createdAt }
            .take(cache.size - MAX_CACHE_SIZE)
            .forEach { cache.remove(it.key) }
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
