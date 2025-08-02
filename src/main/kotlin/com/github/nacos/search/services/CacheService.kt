package com.github.nacos.search.services

import com.github.nacos.search.models.CachedItem
import com.github.nacos.search.models.NacosConfiguration
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for caching Nacos configurations locally
 */
@Service(Service.Level.APP)
class CacheService {
    private val logger = thisLogger()
    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, CachedItem<NacosConfiguration>>()
    private val cacheMutex = Mutex()
    private val properties = PropertiesComponent.getInstance()
    
    companion object {
        private const val CACHE_KEY_PREFIX = "nacos.cache."
        private const val CACHE_KEYS_LIST = "nacos.cache.keys"
        private const val DEFAULT_TTL = 300_000L // 5 minutes
        private const val MAX_CACHE_SIZE = 1000
    }
    
    init {
        loadCacheFromPersistence()
    }
    
    /**
     * Caches a configuration
     */
    suspend fun cacheConfiguration(configuration: NacosConfiguration, ttl: Long = DEFAULT_TTL) {
        cacheMutex.withLock {
            val key = configuration.getKey()
            val cachedItem = CachedItem(configuration, System.currentTimeMillis(), ttl)
            
            cache[key] = cachedItem
            
            // Persist to IDE storage
            persistConfiguration(key, cachedItem)
            updateCacheKeysList()
            
            // Cleanup if cache is too large
            if (cache.size > MAX_CACHE_SIZE) {
                cleanupOldEntries()
            }
            
            logger.debug("Cached configuration: $key")
        }
    }
    
    /**
     * Retrieves a cached configuration
     */
    suspend fun getCachedConfiguration(
        dataId: String,
        group: String,
        tenantId: String? = null
    ): NacosConfiguration? {
        val key = "${dataId}:${group}:${tenantId ?: ""}"
        return getCachedConfiguration(key)
    }
    
    /**
     * Retrieves a cached configuration by key
     */
    suspend fun getCachedConfiguration(key: String): NacosConfiguration? {
        cacheMutex.withLock {
            val cachedItem = cache[key]
            
            return if (cachedItem != null && !cachedItem.isExpired()) {
                logger.debug("Cache hit for: $key")
                cachedItem.data
            } else {
                if (cachedItem?.isExpired() == true) {
                    logger.debug("Cache expired for: $key")
                    cache.remove(key)
                    removePersistentConfiguration(key)
                }
                null
            }
        }
    }
    
    /**
     * Gets all cached configurations that are not expired
     */
    suspend fun getAllCachedConfigurations(): List<NacosConfiguration> {
        cacheMutex.withLock {
            val validConfigurations = mutableListOf<NacosConfiguration>()
            val expiredKeys = mutableListOf<String>()
            
            cache.forEach { (key, cachedItem) ->
                if (cachedItem.isExpired()) {
                    expiredKeys.add(key)
                } else {
                    validConfigurations.add(cachedItem.data)
                }
            }
            
            // Remove expired entries
            expiredKeys.forEach { key ->
                cache.remove(key)
                removePersistentConfiguration(key)
            }
            
            if (expiredKeys.isNotEmpty()) {
                updateCacheKeysList()
                logger.debug("Removed ${expiredKeys.size} expired cache entries")
            }
            
            return validConfigurations
        }
    }
    
    /**
     * Caches multiple configurations
     */
    suspend fun cacheConfigurations(configurations: List<NacosConfiguration>, ttl: Long = DEFAULT_TTL) {
        configurations.forEach { config ->
            cacheConfiguration(config, ttl)
        }
        logger.info("Cached ${configurations.size} configurations")
    }
    
    /**
     * Checks if a configuration is cached and not expired
     */
    suspend fun isCached(dataId: String, group: String, tenantId: String? = null): Boolean {
        return getCachedConfiguration(dataId, group, tenantId) != null
    }
    
    /**
     * Gets cache statistics
     */
    suspend fun getCacheStats(): CacheStats {
        cacheMutex.withLock {
            var expiredCount = 0
            var totalAge = 0L
            
            cache.values.forEach { cachedItem ->
                if (cachedItem.isExpired()) {
                    expiredCount++
                } else {
                    totalAge += cachedItem.getAge()
                }
            }
            
            val validCount = cache.size - expiredCount
            val averageAge = if (validCount > 0) totalAge / validCount else 0L
            
            return CacheStats(
                totalEntries = cache.size,
                validEntries = validCount,
                expiredEntries = expiredCount,
                averageAge = averageAge
            )
        }
    }
    
    /**
     * Clears all cached configurations
     */
    suspend fun clearCache() {
        cacheMutex.withLock {
            cache.clear()
            
            // Clear persistent storage
            val keys = getCacheKeys()
            keys.forEach { key ->
                properties.unsetValue("$CACHE_KEY_PREFIX$key")
            }
            properties.unsetValue(CACHE_KEYS_LIST)
            
            logger.info("Cache cleared")
        }
    }
    
    /**
     * Removes expired entries from cache
     */
    suspend fun cleanupExpiredEntries() {
        cacheMutex.withLock {
            cleanupOldEntries()
        }
    }
    
    private fun loadCacheFromPersistence() {
        try {
            val keys = getCacheKeys()
            var loadedCount = 0
            
            keys.forEach { key ->
                val configJson = properties.getValue("$CACHE_KEY_PREFIX$key")
                if (configJson != null) {
                    try {
                        val cachedItem = gson.fromJson<CachedItem<NacosConfiguration>>(
                            configJson,
                            object : TypeToken<CachedItem<NacosConfiguration>>() {}.type
                        )
                        
                        if (!cachedItem.isExpired()) {
                            cache[key] = cachedItem
                            loadedCount++
                        } else {
                            // Remove expired persistent entry
                            properties.unsetValue("$CACHE_KEY_PREFIX$key")
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to load cached configuration: $key", e)
                        properties.unsetValue("$CACHE_KEY_PREFIX$key")
                    }
                }
            }
            
            if (loadedCount > 0) {
                logger.info("Loaded $loadedCount cached configurations from persistence")
            }
        } catch (e: Exception) {
            logger.warn("Failed to load cache from persistence", e)
        }
    }
    
    private fun persistConfiguration(key: String, cachedItem: CachedItem<NacosConfiguration>) {
        try {
            val json = gson.toJson(cachedItem)
            properties.setValue("$CACHE_KEY_PREFIX$key", json)
        } catch (e: Exception) {
            logger.warn("Failed to persist configuration: $key", e)
        }
    }
    
    private fun removePersistentConfiguration(key: String) {
        properties.unsetValue("$CACHE_KEY_PREFIX$key")
    }
    
    private fun getCacheKeys(): List<String> {
        val keysJson = properties.getValue(CACHE_KEYS_LIST, "[]")
        return try {
            gson.fromJson(keysJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to parse cache keys", e)
            emptyList()
        }
    }
    
    private fun updateCacheKeysList() {
        try {
            val keys = cache.keys.toList()
            val keysJson = gson.toJson(keys)
            properties.setValue(CACHE_KEYS_LIST, keysJson)
        } catch (e: Exception) {
            logger.warn("Failed to update cache keys list", e)
        }
    }
    
    private fun cleanupOldEntries() {
        val expiredKeys = mutableListOf<String>()
        val now = System.currentTimeMillis()
        
        // Find expired entries
        cache.forEach { (key, cachedItem) ->
            if (cachedItem.isExpired()) {
                expiredKeys.add(key)
            }
        }
        
        // If still too many entries, remove oldest ones
        if (cache.size - expiredKeys.size > MAX_CACHE_SIZE) {
            val sortedEntries = cache.entries
                .filter { !expiredKeys.contains(it.key) }
                .sortedBy { it.value.timestamp }
            
            val toRemove = sortedEntries.take(cache.size - MAX_CACHE_SIZE)
            expiredKeys.addAll(toRemove.map { it.key })
        }
        
        // Remove entries
        expiredKeys.forEach { key ->
            cache.remove(key)
            removePersistentConfiguration(key)
        }
        
        if (expiredKeys.isNotEmpty()) {
            updateCacheKeysList()
            logger.debug("Cleaned up ${expiredKeys.size} cache entries")
        }
    }
    
    /**
     * Cache statistics data class
     */
    data class CacheStats(
        val totalEntries: Int,
        val validEntries: Int,
        val expiredEntries: Int,
        val averageAge: Long
    )
}