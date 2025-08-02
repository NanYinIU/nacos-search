package com.github.nacos.search.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persistent settings for Nacos plugin
 */
@Service(Service.Level.APP)
@State(
    name = "NacosSettings",
    storages = [Storage("nacos-search.xml")]
)
class NacosSettings : PersistentStateComponent<NacosSettings> {
    
    // Server configuration
    var serverUrl: String = "http://localhost:8848"
    var username: String = ""
    var password: String = ""
    var namespace: String = "public"
    
    // Authentication configuration
    var authMode: AuthMode = AuthMode.TOKEN
    var enableTokenAuth: Boolean = true
    var tokenCacheDurationMinutes: Int = 30
    var autoTokenRefresh: Boolean = true
    
    // Cache configuration
    var cacheEnabled: Boolean = true
    var cacheTtlMinutes: Int = 5
    var maxCacheSize: Int = 1000
    var autoRefreshEnabled: Boolean = true
    var autoRefreshIntervalMinutes: Int = 10
    
    // Aliases for test compatibility
    var enableCache: Boolean
        get() = cacheEnabled
        set(value) { cacheEnabled = value }
    
    var cacheTtlSeconds: Int
        get() = cacheTtlMinutes * 60
        set(value) { cacheTtlMinutes = value / 60 }
    
    var autoRefreshCache: Boolean
        get() = autoRefreshEnabled
        set(value) { autoRefreshEnabled = value }
    
    var autoRefreshIntervalSeconds: Int
        get() = autoRefreshIntervalMinutes * 60
        set(value) { autoRefreshIntervalMinutes = value / 60 }
    
    // Search configuration
    var searchResultLimit: Int = 100
    var enableRegexSearch: Boolean = true
    var caseSensitiveSearch: Boolean = false
    var highlightMatches: Boolean = true
    
    // UI configuration
    var showToolWindow: Boolean = true
    var toolWindowLocation: String = "right"
    var rememberLastSearch: Boolean = true
    var lastSearchQuery: String = ""
    var lastGroupFilter: String = ""
    var lastTenantFilter: String = ""
    
    // Connection configuration
    var connectionTimeoutSeconds: Int = 30
    var readTimeoutSeconds: Int = 60
    var retryAttempts: Int = 3
    var retryDelaySeconds: Int = 2
    
    override fun getState(): NacosSettings = this
    
    override fun loadState(state: NacosSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
    
    /**
     * Validates the current settings
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (serverUrl.isBlank()) {
            errors.add("Server URL cannot be empty")
        } else {
            try {
                java.net.URL(serverUrl)
            } catch (e: Exception) {
                errors.add("Invalid server URL format")
            }
        }
        
        if (cacheEnabled) {
            if (cacheTtlMinutes < 1) {
                errors.add("Cache TTL must be at least 1 minute")
            }
            
            if (maxCacheSize < 1) {
                errors.add("Max cache size must be at least 1")
            }
            
            if (autoRefreshEnabled && autoRefreshIntervalMinutes < 1) {
                errors.add("Auto refresh interval must be at least 1 minute")
            }
        }
        
        if (searchResultLimit < 1) {
            errors.add("Search result limit must be at least 1")
        }
        
        if (connectionTimeoutSeconds < 1) {
            errors.add("Connection timeout must be at least 1 second")
        }
        
        if (readTimeoutSeconds < 1) {
            errors.add("Read timeout must be at least 1 second")
        }
        
        if (retryAttempts < 0) {
            errors.add("Retry attempts cannot be negative")
        }
        
        if (retryDelaySeconds < 0) {
            errors.add("Retry delay cannot be negative")
        }
        
        return errors
    }
    
    /**
     * Checks if the current settings are valid
     */
    fun isValid(): Boolean {
        return validate().isEmpty()
    }
    
    /**
     * Validates a server URL
     */
    fun isValidServerUrl(url: String): Boolean {
        if (url.isBlank()) {
            return false
        }
        
        return try {
            val urlObj = java.net.URL(url)
            urlObj.protocol in listOf("http", "https")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Resets settings to default values
     */
    fun resetToDefaults() {
        serverUrl = "http://localhost:8848"
        username = ""
        password = ""
        namespace = "public"
        
        cacheEnabled = true
        cacheTtlMinutes = 5
        maxCacheSize = 1000
        autoRefreshEnabled = true
        autoRefreshIntervalMinutes = 10
        
        searchResultLimit = 100
        enableRegexSearch = true
        caseSensitiveSearch = false
        highlightMatches = true
        
        showToolWindow = true
        toolWindowLocation = "right"
        rememberLastSearch = true
        lastSearchQuery = ""
        lastGroupFilter = ""
        lastTenantFilter = ""
        
        connectionTimeoutSeconds = 30
        readTimeoutSeconds = 60
        retryAttempts = 3
        retryDelaySeconds = 2
    }
    
    /**
     * Creates a copy of current settings
     */
    fun copy(): NacosSettings {
        val copy = NacosSettings()
        XmlSerializerUtil.copyBean(this, copy)
        return copy
    }
    
    /**
     * Copies settings from another NacosSettings instance
     */
    fun copyFrom(other: NacosSettings) {
        XmlSerializerUtil.copyBean(other, this)
    }
    
    /**
     * Checks if authentication is configured
     */
    fun hasAuthentication(): Boolean {
        return username.isNotBlank() && password.isNotBlank()
    }
    
    /**
     * 检查是否配置了token认证
     */
    fun hasTokenAuthentication(): Boolean {
        return enableTokenAuth && username.isNotBlank() && password.isNotBlank()
    }
    
    /**
     * 获取token缓存时间（毫秒）
     */
    fun getTokenCacheDurationMillis(): Long {
        return tokenCacheDurationMinutes * 60 * 1000L
    }
    
    /**
     * Gets cache TTL in milliseconds
     */
    fun getCacheTtlMillis(): Long {
        return cacheTtlMinutes * 60 * 1000L
    }
    
    /**
     * Gets auto refresh interval in milliseconds
     */
    fun getAutoRefreshIntervalMillis(): Long {
        return autoRefreshIntervalMinutes * 60 * 1000L
    }
    
    /**
     * Gets connection timeout in milliseconds
     */
    fun getConnectionTimeoutMillis(): Int {
        return connectionTimeoutSeconds * 1000
    }
    
    /**
     * Gets read timeout in milliseconds
     */
    fun getReadTimeoutMillis(): Int {
        return readTimeoutSeconds * 1000
    }
    
    /**
     * Gets retry delay in milliseconds
     */
    fun getRetryDelayMillis(): Long {
        return retryDelaySeconds * 1000L
    }
    
    /**
     * Updates last search parameters
     */
    fun updateLastSearch(query: String, groupFilter: String = "", tenantFilter: String = "") {
        if (rememberLastSearch) {
            lastSearchQuery = query
            lastGroupFilter = groupFilter
            lastTenantFilter = tenantFilter
        }
    }
    
    /**
     * Clears last search parameters
     */
    fun clearLastSearch() {
        lastSearchQuery = ""
        lastGroupFilter = ""
        lastTenantFilter = ""
    }
    
    override fun toString(): String {
        return "NacosSettings(" +
                "serverUrl='$serverUrl', " +
                "username='${if (username.isNotBlank()) "***" else ""}', " +
                "namespace='$namespace', " +
                "authMode=$authMode, " +
                "enableTokenAuth=$enableTokenAuth, " +
                "tokenCacheDurationMinutes=$tokenCacheDurationMinutes, " +
                "autoTokenRefresh=$autoTokenRefresh, " +
                "cacheEnabled=$cacheEnabled, " +
                "cacheTtlMinutes=$cacheTtlMinutes, " +
                "maxCacheSize=$maxCacheSize" +
                ")"
    }
}

/**
 * 认证模式枚举
 */
enum class AuthMode {
    BASIC,      // 仅使用Basic Auth
    TOKEN,      // 仅使用Token Auth
    HYBRID      // 优先Token Auth，回退到Basic Auth
}