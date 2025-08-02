package com.github.nacos.search.services

import com.github.nacos.search.models.NacosApiResponse
import com.github.nacos.search.models.NacosConfiguration
import com.github.nacos.search.models.NamespaceInfo
import com.github.nacos.search.settings.AuthMode
import com.github.nacos.search.settings.NacosSettings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Service for interacting with Nacos Open API
 */
@Service(Service.Level.APP)
class NacosApiService {
    private val logger = thisLogger()
    private val gson = Gson()
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val authService = ApplicationManager.getApplication().getService(NacosAuthService::class.java)
    
    // Configuration cache by namespace
    private val configCache = mutableMapOf<String, MutableMap<String, NacosConfiguration>>()
    private val cacheTimestamps = mutableMapOf<String, Long>()
    private val cacheExpirationMs = 5 * 60 * 1000L // 5 minutes
    
    companion object {
        private const val CONFIG_ENDPOINT = "/nacos/v1/cs/config"
        private const val CONFIG_LIST_ENDPOINT = "/nacos/v1/cs/configs"
        // 查询命名空间，第一步，
        private const val NAMESPACE_ENDPOINT = "/nacos/v1/console/namespaces"
        private const val CONNECTION_TIMEOUT = 10000
        private const val READ_TIMEOUT = 30000
    }
    
    /**
     * Tests connection to Nacos server
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "${settings.serverUrl}$NAMESPACE_ENDPOINT"
            logger.info("Testing connection to: $url")
            
            // Pre-configure auth headers to avoid suspend function call in tuner
            val authHeaders = mutableMapOf<String, String>()
            when (settings.authMode) {
                AuthMode.TOKEN -> {
                    // TOKEN模式：accessToken已经通过buildUrl添加到查询参数中，不需要设置Authorization头
                    // 如果没有有效的token，则不设置任何认证头
                    val token = if (settings.enableTokenAuth) authService.getValidAccessToken() else null
                    if (token == null) {
                        logger.warn("No valid access token available in TOKEN mode")
                    }
                }
                AuthMode.HYBRID -> {
                    // HYBRID模式：优先使用token，如果没有token则回退到Basic认证
                    val token = if (settings.enableTokenAuth) authService.getValidAccessToken() else null
                    if (token == null) {
                        // 回退到Basic认证
                        if (settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
                            val credentials = "${settings.username}:${settings.password}"
                            val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                            authHeaders["Authorization"] = "Basic $encodedCredentials"
                            logger.debug("Using Basic authentication as fallback in HYBRID mode")
                        } else {
                            logger.warn("No valid token and no basic auth credentials in HYBRID mode")
                        }
                    } else {
                        logger.debug("Using token authentication in HYBRID mode")
                    }
                }
                AuthMode.BASIC -> {
                    // BASIC模式：仅使用Basic认证
                    if (settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
                        val credentials = "${settings.username}:${settings.password}"
                        val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                        authHeaders["Authorization"] = "Basic $encodedCredentials"
                    } else {
                        logger.warn("No basic auth credentials configured in BASIC mode")
                    }
                }
            }
            
            val response = HttpRequests.request(url)
                .connectTimeout(CONNECTION_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .tuner { connection ->
                    connection.setRequestProperty("Accept", "application/json")
                    // Apply pre-configured auth headers
                    authHeaders.forEach { (key, value) ->
                        connection.setRequestProperty(key, value)
                    }
                }
                .readString()
            
            val apiResponse = gson.fromJson(response, NacosApiResponse::class.java)
            Result.success(apiResponse.isSuccess())
        } catch (e: Exception) {
            logger.warn("Connection test failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Retrieves a specific configuration from Nacos
     * @param dataId Configuration data ID
     * @param group Configuration group
     * @param namespaceId Namespace ID (tenant), null for public namespace
     * @param useCache Whether to use cache (default: true)
     */
    suspend fun getConfiguration(
        dataId: String,
        group: String,
        namespaceId: String? = null,
        useCache: Boolean = true
    ): Result<NacosConfiguration?> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "${namespaceId ?: "public"}:$dataId:$group"
            val namespace = namespaceId ?: "public"
            
            // Check cache first if enabled
            if (useCache && isCacheValid(namespace)) {
                configCache[namespace]?.get(cacheKey)?.let { cachedConfig ->
                    logger.debug("Returning cached configuration for $cacheKey")
                    return@withContext Result.success(cachedConfig)
                }
            }
            val params = buildMap {
                put("dataId", dataId)
                put("group", group)
                put("show","all")
                namespaceId?.let { put("tenant", it) }
            }
            
            val url = buildUrl(CONFIG_LIST_ENDPOINT, params)
            logger.debug("Fetching configuration: $url")
            
            // Pre-configure auth headers to avoid suspend function call in tuner
            val authHeaders = mutableMapOf<String, String>()
            when (settings.authMode) {
                AuthMode.TOKEN -> {
                    // TOKEN模式：accessToken已经通过buildUrl添加到查询参数中，不需要设置Authorization头
                    // 如果没有有效的token，则不设置任何认证头
                    val token = if (settings.enableTokenAuth) authService.getValidAccessToken() else null
                    if (token == null) {
                        logger.warn("No valid access token available in TOKEN mode")
                    }
                }
                AuthMode.HYBRID -> {
                    // HYBRID模式：优先使用token，如果没有token则回退到Basic认证
                    val token = if (settings.enableTokenAuth) authService.getValidAccessToken() else null
                    if (token == null) {
                        // 回退到Basic认证
                        if (settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
                            val credentials = "${settings.username}:${settings.password}"
                            val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                            authHeaders["Authorization"] = "Basic $encodedCredentials"
                            logger.debug("Using Basic authentication as fallback in HYBRID mode")
                        } else {
                            logger.warn("No valid token and no basic auth credentials in HYBRID mode")
                        }
                    } else {
                        logger.debug("Using token authentication in HYBRID mode")
                    }
                }
                AuthMode.BASIC -> {
                    // BASIC模式：仅使用Basic认证
                    if (settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
                        val credentials = "${settings.username}:${settings.password}"
                        val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                        authHeaders["Authorization"] = "Basic $encodedCredentials"
                    } else {
                        logger.warn("No basic auth credentials configured in BASIC mode")
                    }
                }
            }
            
            val response = HttpRequests.request(url)
                .connectTimeout(CONNECTION_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .tuner { connection ->
                    connection.setRequestProperty("Accept", "application/json")
                    // Apply pre-configured auth headers
                    authHeaders.forEach { (key, value) ->
                        connection.setRequestProperty(key, value)
                    }
                }
                .readString()
            
            val apiResponse = gson.fromJson(response, object : TypeToken<NacosConfiguration>() {}.type) as NacosConfiguration
            
            if (apiResponse != null) {

                var config = apiResponse;
                // Cache the configuration if cache is enabled
                if (useCache) {
                    cacheConfiguration(namespace, cacheKey, config)
                }
                
                Result.success(config)
            } else {
                //logger.warn("Failed to get configuration: ${apiResponse.getErrorMessage()}")
                Result.success(null)
            }
        } catch (e: Exception) {
            logger.warn("Error getting configuration", e)
            Result.failure(e)
        }
    }
    
    /**
     * Lists all configurations from Nacos
     * @param namespaceId Namespace ID (tenant), null for public namespace
     * @param pageNo Page number for pagination
     * @param pageSize Number of items per page
     * @param dataId Data ID for search (supports wildcards for fuzzy search)
     * @param group Group for search
     * @param appName Application name for search
     * @param configTags Configuration tags for search
     * @param searchMode Search mode ("accurate" for exact match, "blur" for fuzzy search)
     * @param useCache Whether to use cache for individual configurations (default: true)
     */
    suspend fun listConfigurations(
        namespaceId: String? = null,
        pageNo: Int = 1,
        pageSize: Int = 200,
        dataId: String = "",
        group: String = "",
        appName: String = "",
        configTags: String = "",
        searchMode: String = "accurate",
        useCache: Boolean = true
    ): Result<ConfigListResponse> = withContext(Dispatchers.IO) {
        try {
            val params = buildMap {
                put("pageNo", pageNo.toString())
                put("pageSize", pageSize.toString())
                put("dataId", dataId)
                put("group", group)
                put("appName", appName)
                put("config_tags", configTags)
                put("search", searchMode)
                namespaceId?.let { put("tenant", it) }
            }
            
            val url = buildUrl(CONFIG_LIST_ENDPOINT, params)
            logger.debug("Listing configurations: $url")
            
            // Pre-configure auth headers to avoid suspend function call in tuner
        val authHeaders = mutableMapOf<String, String>()
        when (settings.authMode) {
            AuthMode.TOKEN -> {
                // TOKEN模式：accessToken已经通过buildUrl添加到查询参数中，不需要设置Authorization头
                // 如果没有有效的token，则不设置任何认证头
                val token = if (settings.enableTokenAuth) authService.getValidAccessToken() else null
                if (token == null) {
                    logger.warn("No valid access token available in TOKEN mode")
                }
            }
            AuthMode.HYBRID -> {
                // HYBRID模式：优先使用token，如果没有token则回退到Basic认证
                val token = if (settings.enableTokenAuth) authService.getValidAccessToken() else null
                if (token == null) {
                    // 回退到Basic认证
                    if (settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
                        val credentials = "${settings.username}:${settings.password}"
                        val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                        authHeaders["Authorization"] = "Basic $encodedCredentials"
                        logger.debug("Using Basic authentication as fallback in HYBRID mode")
                    } else {
                        logger.warn("No valid token and no basic auth credentials in HYBRID mode")
                    }
                } else {
                    logger.debug("Using token authentication in HYBRID mode")
                }
            }
            AuthMode.BASIC -> {
                // BASIC模式：仅使用Basic认证
                if (settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
                    val credentials = "${settings.username}:${settings.password}"
                    val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                    authHeaders["Authorization"] = "Basic $encodedCredentials"
                } else {
                    logger.warn("No basic auth credentials configured in BASIC mode")
                }
            }
        }
            
            val response = HttpRequests.request(url)
                .connectTimeout(CONNECTION_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .tuner { connection ->
                    connection.setRequestProperty("Accept", "application/json")
                    // Apply pre-configured auth headers
                    authHeaders.forEach { (key, value) ->
                        connection.setRequestProperty(key, value)
                    }
                }
                .readString()
            
            val apiResponse = gson.fromJson(response, object : TypeToken<ConfigListResponse>() {}.type) as ConfigListResponse
            
            // Return the full response with pagination info
            Result.success(apiResponse)
        } catch (e: Exception) {
            logger.warn("Error listing configurations", e)
            Result.failure(e)
        }
    }
    
    /**
     * Retrieves all configurations with their content
     * @param namespaceId Namespace ID (tenant), null for public namespace
     * @param useCache Whether to use cache for configurations (default: true)
     */
    suspend fun getAllConfigurations(namespaceId: String? = null, useCache: Boolean = true): Result<List<NacosConfiguration>> {
        return try {
            val allConfigs = mutableListOf<NacosConfiguration>()
            var pageNo = 1
            val pageSize = 100
            
            do {
                val result = listConfigurations(namespaceId, pageNo, pageSize, useCache = useCache)
                if (result.isFailure) {
                    return Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
                }
                
                val response = result.getOrNull() ?: break
                val configs = response.pageItems.map { item ->
                    getConfigurationFromItem(item, useCache)
                }
                allConfigs.addAll(configs)
                pageNo++
                
                // Break if we got fewer results than page size (last page)
            } while (response.pageItems.size == pageSize)
            
            Result.success(allConfigs)
        } catch (e: Exception) {
            logger.warn("Error getting all configurations", e)
            Result.failure(e)
        }
    }
    
    /**
     * Retrieves all namespaces from Nacos
     */
    suspend fun getNamespaces(): Result<List<NamespaceInfo>> = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(NAMESPACE_ENDPOINT, emptyMap())
            logger.debug("Fetching namespaces: $url")
            
            // Pre-configure auth headers to avoid suspend function call in tuner
            val authHeaders = mutableMapOf<String, String>()
            when (settings.authMode) {
                AuthMode.TOKEN -> {
                    val token = if (settings.enableTokenAuth) authService.getValidAccessToken() else null
                    if (token == null) {
                        logger.warn("No valid access token available in TOKEN mode")
                    }
                }
                AuthMode.HYBRID -> {
                    val token = if (settings.enableTokenAuth) authService.getValidAccessToken() else null
                    if (token == null) {
                        if (settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
                            val credentials = "${settings.username}:${settings.password}"
                            val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                            authHeaders["Authorization"] = "Basic $encodedCredentials"
                            logger.debug("Using Basic authentication as fallback in HYBRID mode")
                        } else {
                            logger.warn("No valid token and no basic auth credentials in HYBRID mode")
                        }
                    } else {
                        logger.debug("Using token authentication in HYBRID mode")
                    }
                }
                AuthMode.BASIC -> {
                    if (settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
                        val credentials = "${settings.username}:${settings.password}"
                        val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                        authHeaders["Authorization"] = "Basic $encodedCredentials"
                    } else {
                        logger.warn("No basic auth credentials configured in BASIC mode")
                    }
                }
            }
            
            val response = HttpRequests.request(url)
                .connectTimeout(CONNECTION_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .tuner { connection ->
                    connection.setRequestProperty("Accept", "application/json")
                    authHeaders.forEach { (key, value) ->
                        connection.setRequestProperty(key, value)
                    }
                }
                .readString()
            
            val apiResponse = gson.fromJson(response, object : TypeToken<NacosApiResponse<List<Map<String, Any>>>>() {}.type) as NacosApiResponse<List<Map<String, Any>>>
            
            if (apiResponse.data != null) {
                val namespaces = apiResponse.data.mapNotNull { namespaceMap ->
                    try {
                        NamespaceInfo.fromJsonMap(namespaceMap)
                    } catch (e: Exception) {
                        logger.warn("Failed to parse namespace: $namespaceMap", e)
                        null
                    }
                }
                
                // Always include public namespace if not present
                val hasPublicNamespace = namespaces.any { it.isPublicNamespace() }
                val finalNamespaces = if (!hasPublicNamespace) {
                    listOf(NamespaceInfo.createPublicNamespace()) + namespaces
                } else {
                    namespaces
                }
                
                Result.success(finalNamespaces)
            } else {
                logger.warn("Failed to get namespaces: ${apiResponse.getErrorMessage()}")
                // Return public namespace as fallback
                Result.success(listOf(NamespaceInfo.createPublicNamespace()))
            }
        } catch (e: Exception) {
            logger.warn("Error getting namespaces", e)
            // Return public namespace as fallback
            Result.success(listOf(NamespaceInfo.createPublicNamespace()))
        }
    }
    
    /**
     * Checks if the cache for a namespace is still valid
     */
    private fun isCacheValid(namespace: String): Boolean {
        val timestamp = cacheTimestamps[namespace] ?: return false
        return System.currentTimeMillis() - timestamp < cacheExpirationMs
    }
    
    /**
     * Caches a configuration for a specific namespace
     */
    private fun cacheConfiguration(namespace: String, cacheKey: String, config: NacosConfiguration) {
        configCache.getOrPut(namespace) { mutableMapOf() }[cacheKey] = config
        cacheTimestamps[namespace] = System.currentTimeMillis()
        logger.debug("Cached configuration: $cacheKey")
    }
    
    /**
     * Clears the cache for a specific namespace or all namespaces
     */
    fun clearCache(namespace: String? = null) {
        if (namespace != null) {
            configCache.remove(namespace)
            cacheTimestamps.remove(namespace)
            logger.debug("Cleared cache for namespace: $namespace")
        } else {
            configCache.clear()
            cacheTimestamps.clear()
            logger.debug("Cleared all configuration cache")
        }
    }
    
    private suspend fun buildUrl(endpoint: String, params: Map<String, String>): String {
        val baseUrl = settings.serverUrl.trimEnd('/')
        val mutableParams = params.toMutableMap()
        
        // 根据认证模式决定是否添加accessToken
        val shouldUseToken = when (settings.authMode) {
            AuthMode.TOKEN -> true
            AuthMode.BASIC -> false
            AuthMode.HYBRID -> settings.enableTokenAuth
        }
        
        if (shouldUseToken) {
            val accessToken = authService.getValidAccessToken()
            if (accessToken != null) {
                mutableParams["accessToken"] = accessToken
                logger.debug("Added accessToken to request parameters")
            } else {
                logger.debug("No valid accessToken available")
            }
        }
        
        val queryParams = mutableParams.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
        return if (queryParams.isNotEmpty()) {
            "$baseUrl$endpoint?$queryParams"
        } else {
            "$baseUrl$endpoint"
        }
    }
    

    
    /**
     * Data class for configuration list response
     */
    data class ConfigListResponse(
        val totalCount: Int,
        val pageNumber: Int,
        val pagesAvailable: Int,
        val pageItems: List<ConfigItem>
    )
    
    data class ConfigItem(
        val id:String,
        val dataId: String,
        val group: String,
        val content: String?,
        val type: String?,
        val tenant: String?
    )
    
    /**
     * Converts ConfigItem to NacosConfiguration with full content
     */
    suspend fun getConfigurationFromItem(
        item: ConfigItem,
        useCache: Boolean = true
    ): NacosConfiguration {
        val fullConfig = getConfiguration(item.dataId, item.group, item.tenant, useCache)
        return fullConfig.getOrNull() ?: NacosConfiguration(
            dataId = item.dataId,
            group = item.group,
            tenantId = item.tenant,
            content = "", // Content will be empty if fetch fails
            type = item.type
        )
    }
}