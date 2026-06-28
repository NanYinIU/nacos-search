package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NacosApiResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
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
    private val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
    
    companion object {
        private const val CONFIG_ENDPOINT = "/nacos/v1/cs/configs"
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
            val response = requestJson(url, buildAuthHeaders())
            
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
        useCache: Boolean = true,
        forceRefresh: Boolean = false
    ): Result<NacosConfiguration?> = withContext(Dispatchers.IO) {
        try {
            if (useCache && !forceRefresh && settings.cacheEnabled) {
                cacheService.getConfigDetail(settings.serverUrl, namespaceId, dataId, group)?.let { cachedConfig ->
                    logger.debug("Returning cached configuration for $dataId:$group")
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
            val response = requestJson(url, buildAuthHeaders())
            
            val apiResponse = gson.fromJson(response, object : TypeToken<NacosConfiguration>() {}.type) as NacosConfiguration
            
            if (apiResponse != null) {

                var config = apiResponse;
                // Cache the configuration if cache is enabled
                if (useCache && settings.cacheEnabled) {
                    cacheService.putConfigDetail(settings.serverUrl, namespaceId, config, settings.getCacheTtlMillis())
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
        useCache: Boolean = true,
        forceRefresh: Boolean = false
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
            val requestKey = normalizeRequestKey(params.filterKeys { it != "tenant" })

            if (useCache && !forceRefresh && settings.cacheEnabled) {
                cacheService.getListPage(settings.serverUrl, namespaceId, requestKey)?.let { cachedResponse ->
                    logger.debug("Returning cached list page for $requestKey")
                    return@withContext Result.success(cachedResponse)
                }
            }

            val url = buildUrl(CONFIG_LIST_ENDPOINT, params)
            logger.debug("Listing configurations: $url")
            val response = requestJson(url, buildAuthHeaders())
            
            val apiResponse = gson.fromJson(response, object : TypeToken<ConfigListResponse>() {}.type) as ConfigListResponse
            if (useCache && settings.cacheEnabled) {
                cacheService.putListPage(settings.serverUrl, namespaceId, requestKey, apiResponse, settings.getCacheTtlMillis())
            }
            
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
            val response = requestJson(url, buildAuthHeaders())
            
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
     * Clears the cache for a specific namespace or all namespaces
     */
    suspend fun clearCache(namespace: String? = null) {
        if (namespace == null) {
            cacheService.clearAll()
            logger.debug("Cleared all configuration cache")
        } else {
            cacheService.invalidateNamespace(settings.serverUrl, namespace)
            logger.debug("Cleared cache for namespace: $namespace")
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

    private suspend fun buildAuthHeaders(): Map<String, String> {
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
                    addBasicAuthHeader(authHeaders)
                    logger.debug("Using Basic authentication as fallback in HYBRID mode")
                } else {
                    logger.debug("Using token authentication in HYBRID mode")
                }
            }
            AuthMode.BASIC -> addBasicAuthHeader(authHeaders)
        }
        return authHeaders
    }

    private fun addBasicAuthHeader(headers: MutableMap<String, String>) {
        if (settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
            val credentials = "${settings.username}:${settings.password}"
            val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
            headers["Authorization"] = "Basic $encodedCredentials"
        } else {
            logger.warn("No basic auth credentials configured")
        }
    }

    /**
     * Publishes (creates or updates) a configuration to Nacos via POST.
     * Uses the Nacos Open API /nacos/v1/cs/configs endpoint.
     */
    suspend fun publishConfiguration(
        dataId: String,
        group: String,
        content: String,
        type: String = "text",
        namespaceId: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val params = buildMap {
                put("dataId", dataId)
                put("group", group)
                put("content", content)
                put("type", type)
                namespaceId?.let { put("tenant", it) }
            }

            val url = buildUrl(CONFIG_ENDPOINT, emptyMap())
            logger.info("Publishing configuration: $dataId:$group")

            val response = requestPost(url, params, buildAuthHeaders())
            // Nacos returns "true" on success
            val success = response.trim().equals("true", ignoreCase = true)
            if (success) {
                // Invalidate cache for this config
                cacheService.invalidateNamespace(settings.serverUrl, namespaceId)
                Result.success(true)
            } else {
                Result.failure(RuntimeException("Server responded: $response"))
            }
        } catch (e: Exception) {
            logger.warn("Error publishing configuration", e)
            Result.failure(e)
        }
    }

    private fun requestJson(url: String, authHeaders: Map<String, String>): String {
        return HttpRequests.request(url)
            .connectTimeout(settings.getConnectionTimeoutMillis())
            .readTimeout(settings.getReadTimeoutMillis())
            .tuner { connection ->
                connection.setRequestProperty("Accept", "application/json")
                authHeaders.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }
            }
            .readString()
    }

    private fun requestPost(url: String, params: Map<String, String>, authHeaders: Map<String, String>): String {
        val formData = encodeFormData(params)
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = settings.getConnectionTimeoutMillis()
        connection.readTimeout = settings.getReadTimeoutMillis()
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("Accept", "application/json")
        authHeaders.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }
        val os = connection.outputStream
        os.write(formData.toByteArray(StandardCharsets.UTF_8))
        os.flush()
        os.close()
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errStream = connection.errorStream
            val errText = errStream?.bufferedReader()?.readText() ?: ""
            throw RuntimeException("HTTP $responseCode: $errText")
        }
        val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream, StandardCharsets.UTF_8))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        reader.close()
        connection.disconnect()
        return sb.toString()
    }

    private fun encodeFormData(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
    }

    private fun normalizeRequestKey(params: Map<String, String>): String {
        return params.entries
            .sortedBy { it.key }
            .joinToString("|") { (key, value) -> "$key=$value" }
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
        if (!item.content.isNullOrEmpty()) {
            val configuration = NacosConfiguration(
                dataId = item.dataId,
                group = item.group,
                tenantId = item.tenant,
                content = item.content,
                type = item.type
            )
            if (useCache && settings.cacheEnabled) {
                cacheService.putConfigDetail(settings.serverUrl, item.tenant, configuration, settings.getCacheTtlMillis())
            }
            return configuration
        }

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
