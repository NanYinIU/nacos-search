package com.nanyin.nacos.search.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified cache for Nacos list pages, configuration details, and namespace indexes.
 */
@Service(Service.Level.APP)
class CacheService internal constructor(
    private val currentTimeMillis: () -> Long,
    private val tombstones: ProfileTombstoneRegistry
) : Disposable {
    constructor() : this(System::currentTimeMillis, defaultTombstones())
    internal constructor(currentTimeMillis: () -> Long) : this(currentTimeMillis, ProfileTombstoneRegistry())

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
    @Volatile
    private var detailSnapshot: Map<String, CacheEntry<NacosConfiguration>> = emptyMap()
    private val listPageCache = ConcurrentHashMap<String, CacheEntry<NacosApiService.ConfigListResponse>>()
    private val namespaceIndexCache = ConcurrentHashMap<String, CacheEntry<List<NacosConfiguration>>>()
    private val namespaceIndexAuthority = ConcurrentHashMap<String, Boolean>()
    private val modificationCount = AtomicLong(0)

    /**
     * A deleted profile's tombstone rejects late in-flight cache mutations so
     * stale responses cannot resurrect the entombed profile's state (design
     * §13.1, §19.2). Identity-keyed writes silently drop when the identity's
     * profile has been entombed.
     */
    private fun isRejectedByTombstone(identity: AccessIdentity): Boolean =
        tombstones.isEntombed(identity)


    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)

    /** Runs memory-only derived-state work in this application service's lifecycle scope. */
    fun launchSnapshotRefresh(refresh: () -> Unit) {
        serviceScope.launch(Dispatchers.Default) { refresh() }
    }

    /** A non-suspending, immutable view for EDT/PSI callers. Never waits for persistence loading. */
    fun configurationSnapshot(serverUrl: String?): List<NacosConfiguration> {
        val identity = serverUrl?.let(::legacyIdentity)
        return configurationSnapshotForPrefix(identity?.let(::identityPrefix))
    }

    fun configurationSnapshot(identity: AccessIdentity): List<NacosConfiguration> =
        configurationSnapshotForPrefix(identityPrefix(identity))

    private fun configurationSnapshotForPrefix(keyPrefix: String?): List<NacosConfiguration> {
        val now = currentTimeMillis()
        return detailSnapshot.asSequence()
            .filter { (key, entry) ->
                (keyPrefix == null || key.startsWith("$keyPrefix|")) && !entry.isExpired(now)
            }
            .map { it.value.data }
            .distinctBy(::legacyKey)
            .toList()
    }

    /** Immutable detail view used by code navigation, including stale targets. */
    fun configurationNavigationSnapshot(serverUrl: String?): List<CachedConfiguration> {
        val identity = serverUrl?.let(::legacyIdentity)
        return configurationNavigationSnapshotForPrefix(identity?.let(::identityPrefix))
    }

    fun configurationNavigationSnapshot(identity: AccessIdentity): List<CachedConfiguration> =
        configurationNavigationSnapshotForPrefix(identityPrefix(identity))

    private fun configurationNavigationSnapshotForPrefix(keyPrefix: String?): List<CachedConfiguration> {
        val now = currentTimeMillis()
        return detailSnapshot.asSequence()
            .filter { (key, _) -> keyPrefix == null || key.startsWith("$keyPrefix|") }
            .map { (_, entry) -> entry.toCachedConfiguration(now) }
            .distinctBy { legacyKey(it.configuration) }
            .toList()
    }

    fun configDetailState(
        serverUrl: String,
        namespaceId: String?,
        dataId: String,
        group: String
    ): CachedConfiguration? = detailSnapshot[detailKey(serverUrl, namespaceId, dataId, group)]
        ?.toCachedConfiguration(currentTimeMillis())

    fun configDetailState(
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String
    ): CachedConfiguration? = detailSnapshot[detailKey(identity, namespaceId, dataId, group)]
        ?.toCachedConfiguration(currentTimeMillis())

    /** Non-blocking completeness/freshness view for gutter absence checks. */
    fun namespaceIndexState(serverUrl: String, namespaceId: String?): NamespaceIndexState? {
        val key = namespaceKey(serverUrl, namespaceId)
        val entry = namespaceIndexCache[key] ?: return null
        return entry.toNamespaceIndexState(namespaceIndexAuthority[key] == true)
    }

    fun namespaceIndexState(identity: AccessIdentity, namespaceId: String?): NamespaceIndexState? {
        val key = namespaceKey(identity, namespaceId)
        val entry = namespaceIndexCache[key] ?: return null
        return entry.toNamespaceIndexState(namespaceIndexAuthority[key] == true)
    }

    private fun CacheEntry<List<NacosConfiguration>>.toNamespaceIndexState(
        authoritativeForAbsence: Boolean
    ): NamespaceIndexState {
        return NamespaceIndexState(
            dataIds = data.asSequence().map { it.dataId }.filter { it.isNotBlank() }.toSet(),
            freshness = freshness(currentTimeMillis()),
            authoritativeForAbsence = authoritativeForAbsence
        )
    }

    companion object {
        private const val DETAIL_KEY_PREFIX = "nacos.cache.detail."
        private const val DETAIL_KEYS_LIST = "nacos.cache.detail.keys"
        private const val LIST_PAGE_KEY_PREFIX = "nacos.cache.list."
        private const val LIST_PAGE_KEYS_LIST = "nacos.cache.list.keys"
       private const val DEFAULT_TTL = 300_000L
       // Age at which a stale detail requires a forced single-detail refresh.
       // It remains navigable and is still bounded by capacity/manual cleanup.
       private const val DEEP_STALE_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
       private const val MAX_CACHE_SIZE = 1000
        // Trigger the oversized-caches sweep only once the cache grows past the hard
        // cap by this margin, so per-insert writes stay O(1) instead of O(n).
        private const val CLEANUP_BUFFER = 100
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

    override fun dispose() {
        serviceScope.cancel()
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
        if (!entry.isExpired(currentTimeMillis()) || allowStale) {
            cacheHits.incrementAndGet()
            return entry.data
        }
        // Expired: treat as miss. Reclamation is delegated to the background/ writer
        // cleanup path so the read stays lock-free.
        cacheMisses.incrementAndGet()
        return null
    }

    suspend fun getListPage(
        identity: AccessIdentity,
        namespaceId: String?,
        requestKey: String,
        allowStale: Boolean = false
    ): NacosApiService.ConfigListResponse? {
        loadCompleted.await()
        val key = listPageKey(identity, namespaceId, requestKey)
        val entry = listPageCache[key] ?: run { cacheMisses.incrementAndGet(); return null }
        if (!entry.isExpired(currentTimeMillis()) || allowStale) {
            cacheHits.incrementAndGet()
            return entry.data
        }
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
            listPageCache[key] = CacheEntry(CacheEntryType.LIST_PAGE, response, currentTimeMillis(), ttl, source)
            persistListPage(key, listPageCache[key]!!)
            updateListPageKeysList()
            cleanupOversizedCaches()
        }
    }

    suspend fun putListPage(
        identity: AccessIdentity,
        namespaceId: String?,
        requestKey: String,
        response: NacosApiService.ConfigListResponse,
        ttl: Long = DEFAULT_TTL,
        source: CacheSource = CacheSource.REMOTE
    ) {
        if (isRejectedByTombstone(identity)) return
        cacheMutex.withLock {
            val key = listPageKey(identity, namespaceId, requestKey)
            listPageCache[key] = CacheEntry(CacheEntryType.LIST_PAGE, response, currentTimeMillis(), ttl, source)
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
        return getConfigDetailByKey(key, allowStale)
    }

    suspend fun getConfigDetail(
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String,
        allowStale: Boolean = false
    ): NacosConfiguration? {
        val key = detailKey(identity, namespaceId, dataId, group)
        return getConfigDetailByKey(key, allowStale)
    }

    private suspend fun getConfigDetailByKey(key: String, allowStale: Boolean): NacosConfiguration? {
        val entry = detailCache[key]
        if (entry != null) {
            if (!entry.isExpired(currentTimeMillis()) || allowStale) {
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
            if (fromFile.isExpired(currentTimeMillis()) && !allowStale) {
                cacheMisses.incrementAndGet()
                return null
            }
            detailCache[key] = fromFile
            if (loadCompleted.isCompleted) publishDetailSnapshot()
            cacheHits.incrementAndGet()
            return fromFile.data
        }
        loadCompleted.await()
        val loadedLate = detailCache[key]
        if (loadedLate != null && (!loadedLate.isExpired(currentTimeMillis()) || allowStale)) {
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
        putConfigDetailByKey(
            detailKey(serverUrl, namespaceId, configuration.dataId, configuration.group),
            configuration,
            ttl,
            source
        )
    }

    suspend fun putConfigDetail(
        identity: AccessIdentity,
        namespaceId: String?,
        configuration: NacosConfiguration,
        ttl: Long = DEFAULT_TTL,
        source: CacheSource = CacheSource.REMOTE
    ) {
        if (isRejectedByTombstone(identity)) return
        putConfigDetailByKey(
            detailKey(identity, namespaceId, configuration.dataId, configuration.group),
            configuration,
            ttl,
            source
        )
    }

    private suspend fun putConfigDetailByKey(
        key: String,
        configuration: NacosConfiguration,
        ttl: Long,
        source: CacheSource
    ) {
        cacheMutex.withLock {
           detailCache[key] = CacheEntry(CacheEntryType.CONFIG_DETAIL, configuration, currentTimeMillis(), ttl, source)
           persistDetail(key, detailCache[key]!!)
           updateDetailKeysList()
           cleanupOversizedCaches()
           publishDetailSnapshot()
           markModified()
       }
   }

    suspend fun removeConfigDetail(
        serverUrl: String,
        namespaceId: String?,
        dataId: String,
        group: String
    ) {
        removeConfigDetailByKey(detailKey(serverUrl, namespaceId, dataId, group))
    }

    suspend fun removeConfigDetail(
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String
    ) {
        removeConfigDetailByKey(detailKey(identity, namespaceId, dataId, group))
    }

    private suspend fun removeConfigDetailByKey(key: String) {
        cacheMutex.withLock {
            if (detailCache.remove(key) == null) return@withLock
            cacheStorage.removeDetail(key)
            updateDetailKeysList()
            publishDetailSnapshot()
            markModified()
        }
    }

    /** Upserts a partial batch without claiming that the Namespace snapshot is complete. */
    suspend fun putNamespaceDetails(
        serverUrl: String,
        namespaceId: String?,
        configurations: List<NacosConfiguration>,
        ttl: Long = DEFAULT_TTL,
        source: CacheSource = CacheSource.REMOTE
    ) {
        putNamespaceDetailsByIdentity(null, serverUrl, namespaceId, configurations, ttl, source)
    }

    suspend fun putNamespaceDetails(
        identity: AccessIdentity,
        namespaceId: String?,
        configurations: List<NacosConfiguration>,
        ttl: Long = DEFAULT_TTL,
        source: CacheSource = CacheSource.REMOTE
    ) {
        putNamespaceDetailsByIdentity(identity, identity.serverId, namespaceId, configurations, ttl, source)
    }

    private suspend fun putNamespaceDetailsByIdentity(
        identity: AccessIdentity?,
        serverUrl: String,
        namespaceId: String?,
        configurations: List<NacosConfiguration>,
        ttl: Long,
        source: CacheSource
    ) {
        if (configurations.isEmpty()) return
        cacheMutex.withLock {
            val now = currentTimeMillis()
            configurations.forEach { config ->
                val key = identity?.let { detailKey(it, namespaceId, config.dataId, config.group) }
                    ?: detailKey(serverUrl, namespaceId, config.dataId, config.group)
                val entry = CacheEntry(CacheEntryType.CONFIG_DETAIL, config, now, ttl, source)
                detailCache[key] = entry
                persistDetail(key, entry)
            }
            updateDetailKeysList()
            cleanupOversizedCaches()
            publishDetailSnapshot()
            markModified()
        }
    }

    suspend fun getNamespaceIndex(
        serverUrl: String,
        namespaceId: String?,
        allowStale: Boolean = false
    ): List<NacosConfiguration>? {
        loadCompleted.await()
        val key = namespaceKey(serverUrl, namespaceId)
        return getNamespaceIndexByKey(key, allowStale)
    }

    suspend fun getNamespaceIndex(
        identity: AccessIdentity,
        namespaceId: String?,
        allowStale: Boolean = false
    ): List<NacosConfiguration>? {
        loadCompleted.await()
        return getNamespaceIndexByKey(namespaceKey(identity, namespaceId), allowStale)
    }

    private fun getNamespaceIndexByKey(key: String, allowStale: Boolean): List<NacosConfiguration>? {
        val entry = namespaceIndexCache[key] ?: run { cacheMisses.incrementAndGet(); return null }
        if (!entry.isExpired(currentTimeMillis()) || allowStale) {
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
        putNamespaceIndexByIdentity(null, serverUrl, namespaceId, configurations, ttl, source)
    }

    suspend fun putNamespaceIndex(
        identity: AccessIdentity,
        namespaceId: String?,
        configurations: List<NacosConfiguration>,
        ttl: Long = DEFAULT_TTL,
        source: CacheSource = CacheSource.REMOTE
    ) {
        if (isRejectedByTombstone(identity)) return
        putNamespaceIndexByIdentity(identity, identity.serverId, namespaceId, configurations, ttl, source)
    }

    private suspend fun putNamespaceIndexByIdentity(
        identity: AccessIdentity?,
        serverUrl: String,
        namespaceId: String?,
        configurations: List<NacosConfiguration>,
        ttl: Long,
        source: CacheSource
    ) {
        cacheMutex.withLock {
            val now = currentTimeMillis()
            val namespacePrefix = identity?.let { "${identityPrefix(it)}|${normalizeNamespace(namespaceId)}|" }
                ?: "${identityPrefix(legacyIdentity(serverUrl))}|${normalizeNamespace(namespaceId)}|"
            val replacementKeys = configurations
                .mapTo(mutableSetOf()) { config ->
                    identity?.let { detailKey(it, namespaceId, config.dataId, config.group) }
                        ?: detailKey(serverUrl, namespaceId, config.dataId, config.group)
                }
            detailCache.keys
                .filter { it.startsWith(namespacePrefix) && it !in replacementKeys }
                .forEach { key ->
                    detailCache.remove(key)
                    cacheStorage.removeDetail(key)
                }
            val indexKey = identity?.let { namespaceKey(it, namespaceId) }
                ?: namespaceKey(serverUrl, namespaceId)
            namespaceIndexCache[indexKey] =
                CacheEntry(CacheEntryType.NAMESPACE_INDEX, configurations, now, ttl, source)
            namespaceIndexAuthority[indexKey] = true
           configurations.forEach { config ->
               val key = identity?.let { detailKey(it, namespaceId, config.dataId, config.group) }
                   ?: detailKey(serverUrl, namespaceId, config.dataId, config.group)
               detailCache[key] = CacheEntry(CacheEntryType.CONFIG_DETAIL, config, now, ttl, source)
                persistDetail(key, detailCache[key]!!)
            }
           updateDetailKeysList()
           cleanupOversizedCaches()
           publishDetailSnapshot()
           markModified()
       }
   }

    suspend fun markNamespaceIndexNonAuthoritative(serverUrl: String, namespaceId: String?) {
        markNamespaceIndexNonAuthoritative(namespaceKey(serverUrl, namespaceId))
    }

    suspend fun markNamespaceIndexNonAuthoritative(identity: AccessIdentity, namespaceId: String?) {
        markNamespaceIndexNonAuthoritative(namespaceKey(identity, namespaceId))
    }

    private suspend fun markNamespaceIndexNonAuthoritative(indexKey: String) {
        cacheMutex.withLock {
            if (namespaceIndexCache.containsKey(indexKey)) {
                namespaceIndexAuthority[indexKey] = false
            }
        }
    }

    suspend fun invalidateNamespace(serverUrl: String, namespaceId: String?) {
        invalidateNamespaceByPrefix(
            "${identityPrefix(legacyIdentity(serverUrl))}|${normalizeNamespace(namespaceId)}|",
            namespaceKey(serverUrl, namespaceId)
        )
    }

    suspend fun invalidateNamespace(identity: AccessIdentity, namespaceId: String?) {
        invalidateNamespaceByPrefix(
            "${identityPrefix(identity)}|${normalizeNamespace(namespaceId)}|",
            namespaceKey(identity, namespaceId)
        )
    }

    private suspend fun invalidateNamespaceByPrefix(namespacePrefix: String, indexKey: String) {
        cacheMutex.withLock {
            detailCache.keys.filter { it.startsWith(namespacePrefix) }.forEach { key ->
                detailCache.remove(key)
                cacheStorage.removeDetail(key)
            }
            listPageCache.keys.filter { it.startsWith(namespacePrefix) }.forEach { key ->
                listPageCache.remove(key)
                cacheStorage.removeListPage(key)
            }
           namespaceIndexCache.remove(indexKey)
           namespaceIndexAuthority.remove(indexKey)
           updateDetailKeysList()
           updateListPageKeysList()
           publishDetailSnapshot()
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
        if (!found.value.isExpired(currentTimeMillis())) {
            cacheHits.incrementAndGet()
            return found.value.data
        }
        cacheMisses.incrementAndGet()
        return null
    }

    suspend fun getAllCachedConfigurations(serverUrl: String? = null): List<NacosConfiguration> {
        loadCompleted.await()
        return configurationSnapshot(serverUrl)
    }

    suspend fun getAllCachedConfigurations(identity: AccessIdentity): List<NacosConfiguration> {
        loadCompleted.await()
        return configurationSnapshot(identity)
    }

    suspend fun cacheConfigurations(configurations: List<NacosConfiguration>, ttl: Long = DEFAULT_TTL) {
        cacheMutex.withLock {
            val now = currentTimeMillis()
            configurations.forEach { config ->
                val key = detailKey("", config.tenantId, config.dataId, config.group)
                detailCache[key] = CacheEntry(CacheEntryType.CONFIG_DETAIL, config, now, ttl, CacheSource.REMOTE)
                persistDetail(key, detailCache[key]!!)
            }
           updateDetailKeysList()
           cleanupOversizedCaches()
           publishDetailSnapshot()
           logger.info("Cached ${configurations.size} configurations")
           markModified()
       }
   }

    fun getModificationCount(): Long = modificationCount.get()

    internal fun cacheTimeMillis(): Long = currentTimeMillis()

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
            namespaceIndexAuthority.clear()
            cacheStorage.clearAll()
            properties.unsetValue(DETAIL_KEYS_LIST)
            properties.unsetValue(LIST_PAGE_KEYS_LIST)
           cacheHits.set(0)
           cacheMisses.set(0)
           publishDetailSnapshot()
           markModified()
           logger.info("Cache cleared")
       }
    }

    suspend fun cleanupExpiredEntries() {
        cacheMutex.withLock {
            cleanupExpiredEntriesLocked()
            publishDetailSnapshot()
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
            loadIdentityScopedDetailsFromPersistence()
            loadIdentityScopedListPagesFromPersistence()
            discardLegacyBlobs()
            publishDetailSnapshot()
            markModified()
        }
    }

    /** Deletes cache records whose schema cannot establish a profile owner. */
    private suspend fun discardLegacyBlobs() {
        for (key in getDetailKeys()) {
            if (isIdentityScopedKey(key)) continue
            properties.unsetValue("$DETAIL_KEY_PREFIX$key")
            cacheStorage.removeDetail(key)
        }
        for (key in getListPageKeys()) {
            if (isIdentityScopedKey(key)) continue
            properties.unsetValue("$LIST_PAGE_KEY_PREFIX$key")
            cacheStorage.removeListPage(key)
        }
        updateDetailKeysList()
        updateListPageKeysList()
    }

    private suspend fun loadIdentityScopedDetailsFromPersistence() {
        getDetailKeys().forEach { key ->
            if (!isIdentityScopedKey(key)) return@forEach
            val entry = cacheStorage.loadDetail(key)
            if (entry == null) return@forEach
            detailCache[key] = entry
        }
    }

    private suspend fun loadIdentityScopedListPagesFromPersistence() {
        getListPageKeys().forEach { key ->
            if (!isIdentityScopedKey(key)) return@forEach
            val entry = cacheStorage.loadListPage(key)
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
        val now = currentTimeMillis()
        listPageCache.entries.filter { it.value.isExpired(now) }.forEach { (key, _) ->
            listPageCache.remove(key)
            cacheStorage.removeListPage(key)
        }
        namespaceIndexCache.entries.filter { it.value.isExpired(now) }.forEach { (key, _) ->
            namespaceIndexCache.remove(key)
            namespaceIndexAuthority.remove(key)
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

    private fun publishDetailSnapshot() {
        detailSnapshot = detailCache.toMap()
    }

    private fun CacheEntry<NacosConfiguration>.toCachedConfiguration(now: Long): CachedConfiguration =
        CachedConfiguration(
            data,
            freshness(now),
            freshUntilMillis = createdAt + ttlMs,
            deepStaleAtMillis = createdAt + DEEP_STALE_AGE_MILLIS
        )

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
        val now = currentTimeMillis()
        val createdAtValues = detailCache.values.map { it.createdAt } +
                listPageCache.values.map { it.createdAt } +
                namespaceIndexCache.values.map { it.createdAt }
        return if (createdAtValues.isEmpty()) 0L else createdAtValues.sumOf { now - it } / createdAtValues.size
    }

    private fun detailKey(serverUrl: String, namespaceId: String?, dataId: String, group: String): String =
        detailKey(legacyIdentity(serverUrl), namespaceId, dataId, group)

    private fun detailKey(identity: AccessIdentity, namespaceId: String?, dataId: String, group: String): String =
        CacheCoordinate.Detail(identity, identity.serverId, namespaceId.orEmpty(), dataId, group).storageKey()

    private fun listPageKey(serverUrl: String, namespaceId: String?, requestKey: String): String =
        listPageKey(legacyIdentity(serverUrl), namespaceId, requestKey)

    private fun listPageKey(identity: AccessIdentity, namespaceId: String?, requestKey: String): String =
        CacheCoordinate.ListPage(identity, identity.serverId, namespaceId.orEmpty(), requestKey).storageKey()

    private fun namespaceKey(serverUrl: String, namespaceId: String?): String =
        namespaceKey(legacyIdentity(serverUrl), namespaceId)

    private fun namespaceKey(identity: AccessIdentity, namespaceId: String?): String =
        CacheCoordinate.NamespaceIndex(identity, identity.serverId, namespaceId.orEmpty()).storageKey()

    private fun identityPrefix(identity: AccessIdentity): String = CacheCoordinate.identityPrefix(identity)

    private fun legacyIdentity(serverUrl: String): AccessIdentity =
        AccessIdentity.of(serverUrl, AuthMode.TOKEN, "")

    private fun isIdentityScopedKey(key: String): Boolean = key.startsWith("v2|")

    private fun normalizeNamespace(namespaceId: String?): String {
        return namespaceId?.takeIf { it.isNotBlank() && it != "public" } ?: "public"
    }

    private fun legacyKey(configuration: NacosConfiguration): String {
        return "${configuration.dataId}:${configuration.group}:${configuration.tenantId ?: ""}"
    }

   data class CacheEntry<T>(
       val type: CacheEntryType,
       val data: T,
       val createdAt: Long, // wall-clock epoch millis — persisted for stale-age checks
       val ttlMs: Long,
       val source: CacheSource,
       val stale: Boolean = false
   ) {
       fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now - createdAt > ttlMs
       fun freshness(now: Long = System.currentTimeMillis()): DetailFreshness {
           val age = now - createdAt
           return when {
               age <= ttlMs -> DetailFreshness.FRESH
               age <= DEEP_STALE_AGE_MILLIS -> DetailFreshness.STALE
               else -> DetailFreshness.DEEP_STALE
           }
       }
       fun isStale(now: Long = System.currentTimeMillis()): Boolean = freshness(now) == DetailFreshness.STALE
       fun isDeepStale(now: Long = System.currentTimeMillis()): Boolean = freshness(now) == DetailFreshness.DEEP_STALE
   }

    data class CachedConfiguration(
        val configuration: NacosConfiguration,
        val freshness: DetailFreshness,
        val freshUntilMillis: Long,
        val deepStaleAtMillis: Long
    )

    data class NamespaceIndexState(
        val dataIds: Set<String>,
        val freshness: DetailFreshness,
        val authoritativeForAbsence: Boolean
    )

    enum class DetailFreshness {
        FRESH,
        STALE,
        DEEP_STALE
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

private fun defaultTombstones(): ProfileTombstoneRegistry =
    try {
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(ProfileTombstoneRegistry::class.java) ?: ProfileTombstoneRegistry()
    } catch (e: Exception) {
        ProfileTombstoneRegistry()
    }
