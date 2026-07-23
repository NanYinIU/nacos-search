package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NacosApiResponse
import com.nanyin.nacos.search.models.ConfigLoadFailure
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NamespaceLoadResult
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.network.RequestPolicy
import com.nanyin.nacos.search.services.operations.CacheServiceOperationCache
import com.nanyin.nacos.search.services.operations.ConfigurationCoordinate
import com.nanyin.nacos.search.services.operations.ConnectionDiagnostic
import com.nanyin.nacos.search.services.operations.DiagnosticReport
import com.nanyin.nacos.search.services.operations.DiagnosticSnapshot
import com.nanyin.nacos.search.services.operations.EditSession
import com.nanyin.nacos.search.services.operations.HistoryDetail
import com.nanyin.nacos.search.services.operations.HistoryPage
import com.nanyin.nacos.search.services.operations.HistoryQuery
import com.nanyin.nacos.search.services.operations.NacosRequestExecutorProtocolTransport
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationGatewayPublishGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.services.operations.PublishController
import com.nanyin.nacos.search.services.operations.PublishResult
import com.nanyin.nacos.search.services.operations.SummaryPage
import com.nanyin.nacos.search.services.operations.SummaryQuery
import com.nanyin.nacos.search.services.operations.V1ProtocolAdapter
import com.nanyin.nacos.search.services.operations.GenerationResolver
import com.nanyin.nacos.search.services.operations.V3ProtocolAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Service for interacting with Nacos Open API
 */
@Service(Service.Level.APP)
class NacosApiService(
    private val v1GatewayOverride: OperationGateway? = null
) {
    private val logger = thisLogger()
    private val gson = Gson()
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
  private val authService = ApplicationManager.getApplication().getService(NacosAuthService::class.java)
    private val executor = NacosRequestExecutor()

   private val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)

   /**
    * The V1 and V3 adapters are shared by the [v1Gateway] and the
    * [generationResolver] so AUTO resolution and formal operations use the
    * same transport, auth boundary, and error mapping.
    */
   private val v1Adapter by lazy {
       V1ProtocolAdapter(NacosRequestExecutorProtocolTransport(executor), authService)
   }
   private val v3Adapter by lazy {
       V3ProtocolAdapter(NacosRequestExecutorProtocolTransport(executor))
   }
   private val v1Gateway by lazy {
       v1GatewayOverride ?: OperationGateway(
           mapOf(
               NacosApiGeneration.V1 to v1Adapter,
               NacosApiGeneration.V3 to v3Adapter
           ),
           CacheServiceOperationCache(cacheService) { settings.getCacheTtlMillis() }
       )
   }
   /** Resolves AUTO to a concrete generation by probing V3 first, then V1. */
   private val generationResolver by lazy { GenerationResolver(v3Adapter, v1Adapter) }
  companion object {
       private const val CONFIG_ENDPOINT = "/nacos/v1/cs/configs"
       private const val CONFIG_LIST_ENDPOINT = "/nacos/v1/cs/configs"
       private const val NAMESPACE_ENDPOINT = "/nacos/v1/console/namespaces"
        private const val FETCH_CONCURRENCY = 8

        // Testable I/O body of requestPost. Guarantees the connection is
        // disconnected and streams closed on every path (success, error, exception).
        internal fun doRequestPost(connection: java.net.HttpURLConnection, formData: String): String {
            try {
                connection.outputStream.use { os ->
                    os.write(formData.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val errText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    throw RuntimeException("HTTP $responseCode: $errText")
                }
                return java.io.BufferedReader(
                    java.io.InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)
                ).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    sb.toString()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Tests connection to Nacos server
    */
   suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
       try {
           val context = operationContextOrFailure() ?: return@withContext Result.failure(
               ConfigurationRequired(listOf("Connection configuration is incomplete"))
           )
           if (usesLockedGeneration(context)) {
               return@withContext v1Gateway.probe(v1Target(context)).map { true }
           }
           // AUTO: resolve the generation by probing V3 first, then V1. A
           // successful resolution (which already probed the server) proves
           // reachability; the formal gateway probe confirms the adapter path.
           val target = resolvedReadTarget(context, null).getOrElse { error ->
               return@withContext Result.failure(error)
           }
           return@withContext v1Gateway.probe(target).map { true }
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
        forceRefresh: Boolean = false,
        operationContext: NacosOperationContext? = null
    ): Result<NacosConfiguration?> = withContext(Dispatchers.IO) {
        try {
            val context = operationContext ?: operationContextOrFailure() ?: return@withContext Result.failure(
                ConfigurationRequired(listOf("Connection configuration is incomplete"))
            )
            if (usesLockedGeneration(context)) {
                return@withContext v1Gateway.readDetail(
                    v1Target(context, namespaceId),
                    ConfigurationCoordinate(dataId, group),
                    forceRefresh = forceRefresh,
                    useCache = useCache
                )
            }
            // AUTO: resolve to a concrete generation and read through the gateway
            // so a standard 3.2 server (no legacy V1 API) is read via V3.
            val resolvedTarget = resolvedReadTarget(context, namespaceId).getOrElse { error ->
                return@withContext Result.failure(error)
            }
            if (resolvedTarget.context.resolvedGeneration != NacosApiGeneration.UNKNOWN) {
                return@withContext v1Gateway.readDetail(
                    resolvedTarget,
                    ConfigurationCoordinate(dataId, group),
                    forceRefresh = forceRefresh,
                    useCache = useCache
                )
            }
            if (useCache && !forceRefresh && settings.cacheEnabled) {
                cacheService.getConfigDetail(context.identity, namespaceId, dataId, group)?.let { cachedConfig ->
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
            
           logger.debug("Fetching configuration from captured endpoint: ${context.endpoint.value}")
           val response = requestJsonWithReplay(context, CONFIG_LIST_ENDPOINT, params, RequestPolicy.INTERACTIVE)

           val apiResponse = gson.fromJson(response, object : TypeToken<NacosConfiguration>() {}.type) as? NacosConfiguration
            
            if (apiResponse != null) {
                val config = apiResponse
                // Cache the configuration if cache is enabled
                if (useCache && settings.cacheEnabled) {
                    cacheService.putConfigDetail(
                        context.identity,
                        namespaceId,
                        config,
                        settings.getCacheTtlMillis()
                    )
                }
                
                Result.success(config)
            } else {
                logger.warn("Configuration not found for dataId=$dataId group=$group namespaceId=$namespaceId (empty server response)")
                Result.success(null)
            }
        } catch (e: Exception) {
            if (e is NacosRequestError.Client && e.status == 404) {
                logger.info("Configuration not found for dataId=$dataId group=$group namespaceId=$namespaceId")
                return@withContext Result.success(null)
            }
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
        forceRefresh: Boolean = false,
        operationContext: NacosOperationContext? = null
    ): Result<ConfigListResponse> = withContext(Dispatchers.IO) {
        try {
            val context = operationContext ?: operationContextOrFailure() ?: return@withContext Result.failure(
                ConfigurationRequired(listOf("Connection configuration is incomplete"))
            )
            if (usesLockedGeneration(context)) {
                return@withContext v1Gateway.listSummaries(
                    v1Target(context, namespaceId),
                    SummaryQuery(pageNo, pageSize, dataId, group, appName, configTags, searchMode),
                    forceRefresh = forceRefresh,
                    useCache = useCache
                ).map { it.toConfigListResponse() }
            }
            // AUTO: resolve to a concrete generation and list through the gateway
            // so a standard 3.2 server is browsed via V3.
            val resolvedTarget = resolvedReadTarget(context, namespaceId).getOrElse { error ->
                return@withContext Result.failure(error)
            }
            if (resolvedTarget.context.resolvedGeneration != NacosApiGeneration.UNKNOWN) {
                return@withContext v1Gateway.listSummaries(
                    resolvedTarget,
                    SummaryQuery(pageNo, pageSize, dataId, group, appName, configTags, searchMode),
                    forceRefresh = forceRefresh,
                    useCache = useCache
                ).map { it.toConfigListResponse() }
            }
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
                cacheService.getListPage(context.identity, namespaceId, requestKey)?.let { cachedResponse ->
                    logger.debug("Returning cached list page for $requestKey")
                    return@withContext Result.success(cachedResponse)
                }
            }

           logger.debug("Listing configurations from captured endpoint: ${context.endpoint.value}")
           val response = requestJsonWithReplay(context, CONFIG_LIST_ENDPOINT, params, RequestPolicy.INTERACTIVE)

           val apiResponse = gson.fromJson(response, object : TypeToken<ConfigListResponse>() {}.type) as ConfigListResponse
            if (useCache && settings.cacheEnabled) {
                cacheService.putListPage(context.identity, namespaceId, requestKey, apiResponse, settings.getCacheTtlMillis())
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
   suspend fun getAllConfigurations(
       namespaceId: String? = null,
       useCache: Boolean = true,
       operationContext: NacosOperationContext? = null
   ): Result<List<NacosConfiguration>> {
       val result = loadNamespace(namespaceId, useCache, operationContext)
       return result.map { it.configurations }
   }

   /**
    * Loads all configurations for a namespace, preserving completeness metadata.
    * Individual detail-fetch failures are collected instead of silently swallowed;
    * the result distinguishes COMPLETE (no failures), PARTIAL (some failures),
    * and FAILED (list-level failure).
    */
   suspend fun loadNamespace(
       namespaceId: String? = null,
       useCache: Boolean = true,
       operationContext: NacosOperationContext? = null
   ): Result<NamespaceLoadResult> =
       loadNamespace(namespaceId, useCache, null, RequestPolicy.INTERACTIVE, operationContext)

   internal suspend fun loadNamespace(
       namespaceId: String? = null,
       useCache: Boolean = true,
       server: NacosServerSnapshot?,
       policy: RequestPolicy,
       operationContext: NacosOperationContext? = null
   ): Result<NamespaceLoadResult> {
       return try {
           val capturedContext = operationContext ?: if (server == null) operationContextOrFailure() else null
           if (server == null && capturedContext == null) {
               return Result.failure(ConfigurationRequired(listOf("Connection configuration is incomplete")))
           }
           if (server != null && isIncomplete(server)) {
               return Result.failure(ConfigurationRequired(listOf("Captured connection configuration is incomplete")))
           }
           val allConfigs = mutableListOf<NacosConfiguration>()
           val failures = mutableListOf<ConfigLoadFailure>()
           var expectedCount = 0
           var pageNo = 1
           val pageSize = 100

           do {
               val result = if (server == null) {
                   listConfigurations(namespaceId, pageNo, pageSize, useCache = useCache, operationContext = capturedContext)
               } else {
                   listConfigurations(server, namespaceId, pageNo, pageSize, policy)
               }
               if (result.isFailure) {
                   return Result.success(
                       NamespaceLoadResult(DatasetCompleteness.FAILED, expectedCount, allConfigs, failures)
                   )
               }

               val response = result.getOrNull() ?: break
               expectedCount = response.totalCount

               // Bounded parallelism: fetch each item's content concurrently with
               // supervisor semantics so one detail failure doesn't cancel the batch.
               val pageResults = coroutineScope {
                   val semaphore = kotlinx.coroutines.sync.Semaphore(FETCH_CONCURRENCY)
                   response.pageItems.map { item ->
                       async {
                           semaphore.withPermit {
                               try {
                                   FetchResult.Success(
                                       if (server == null) getConfigurationFromItem(item, useCache, capturedContext)
                                       else getConfigurationFromItem(server, item, policy)
                                   )
                               } catch (ce: kotlinx.coroutines.CancellationException) {
                                   throw ce
                               } catch (e: Exception) {
                                   FetchResult.Failure(item.dataId, item.group, e)
                               }
                           }
                       }
                   }.awaitAll()
               }
               pageResults.forEach { r ->
                   when (r) {
                       is FetchResult.Success -> allConfigs.add(r.config)
                       is FetchResult.Failure -> failures.add(
                           ConfigLoadFailure(r.dataId, r.group, toRequestError(r.error))
                       )
                   }
               }
               pageNo++
           } while (response.pageItems.size == pageSize)

           val completeness = if (failures.isEmpty()) DatasetCompleteness.COMPLETE else DatasetCompleteness.PARTIAL
           Result.success(NamespaceLoadResult(completeness, expectedCount, allConfigs, failures))
       } catch (e: Exception) {
           logger.warn("Error loading namespace", e)
           Result.failure(e)
       }
   }

   private suspend fun listConfigurations(
       server: NacosServerSnapshot,
       namespaceId: String?,
       pageNo: Int,
       pageSize: Int,
       policy: RequestPolicy
   ): Result<ConfigListResponse> = try {
       val params = buildMap {
           put("pageNo", pageNo.toString())
           put("pageSize", pageSize.toString())
           put("dataId", "")
           put("group", "")
           put("appName", "")
           put("config_tags", "")
           put("search", "accurate")
           namespaceId?.let { put("tenant", it) }
       }
       val url = buildUrl(server, CONFIG_LIST_ENDPOINT, params)
       val response = requestJsonWithReplay(server, url, policy)
       Result.success(gson.fromJson(response, object : TypeToken<ConfigListResponse>() {}.type) as ConfigListResponse)
   } catch (e: Exception) {
       logger.warn("Error listing configurations for captured server", e)
       Result.failure(e)
   }

   private suspend fun getConfigurationFromItem(
       server: NacosServerSnapshot,
       item: ConfigItem,
       policy: RequestPolicy
   ): NacosConfiguration {
       if (!item.content.isNullOrEmpty()) {
           return NacosConfiguration(
               dataId = item.dataId,
               group = item.group,
               tenantId = item.tenant,
               content = item.content,
               type = item.type
           )
       }
       val params = buildMap {
           put("dataId", item.dataId)
           put("group", item.group)
           put("show", "all")
           item.tenant?.let { put("tenant", it) }
       }
       val response = requestJsonWithReplay(server, buildUrl(server, CONFIG_LIST_ENDPOINT, params), policy)
       return gson.fromJson(response, object : TypeToken<NacosConfiguration>() {}.type) as NacosConfiguration
   }

   private sealed class FetchResult {
       data class Success(val config: NacosConfiguration) : FetchResult()
       data class Failure(val dataId: String, val group: String, val error: Throwable) : FetchResult()
   }

   private fun toRequestError(e: Throwable): NacosRequestError = when (e) {
       is NacosRequestError -> e
       else -> NacosRequestError.Connection(e)
   }
    
    /**
     * Retrieves all namespaces from Nacos
     */
    suspend fun getNamespaces(operationContext: NacosOperationContext? = null): Result<List<NamespaceInfo>> = withContext(Dispatchers.IO) {
        try {
           val context = operationContext ?: operationContextOrFailure() ?: return@withContext Result.failure(
               ConfigurationRequired(listOf("Connection configuration is incomplete"))
           )
           val resolvedTarget = resolvedReadTarget(context, null).getOrElse { error ->
               logger.warn("Failed to resolve generation for namespace discovery", error)
               // Discovery failure must not hide a manually readable public namespace.
               return@withContext Result.success(listOf(NamespaceInfo.createPublicNamespace()))
           }
           logger.debug("Discovering namespaces via gateway for ${resolvedTarget.context.resolvedGeneration}")
           val discovered = v1Gateway.discoverNamespaces(resolvedTarget)
           discovered.fold(
               onSuccess = { namespaces ->
                   val mapped = namespaces.map { ns ->
                       NamespaceInfo(
                           namespaceId = if (ns.namespaceId == "public") "" else ns.namespaceId,
                           namespaceName = ns.displayName,
                           namespaceDesc = ns.description.orEmpty(),
                           configCount = ns.configCount?.toInt() ?: 0
                       )
                   }
                   val hasPublic = mapped.any { it.isPublicNamespace() }
                   Result.success(
                       if (hasPublic) mapped else listOf(NamespaceInfo.createPublicNamespace()) + mapped
                   )
               },
               onFailure = { error ->
                   logger.warn("Namespace discovery unavailable: ${error.message}")
                   // Capability/auth denial → public + manual entry UX; not a hard failure.
                   Result.success(listOf(NamespaceInfo.createPublicNamespace()))
               }
           )
        } catch (e: Exception) {
            logger.warn("Error getting namespaces", e)
            if (e is ConfigurationRequired) return@withContext Result.failure(e)
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
            cacheService.invalidateNamespace(settings.captureAccessIdentity(), namespace)
            logger.debug("Cleared cache for namespace: $namespace")
        }
    }
    
    private data class CapturedRequest(val url: String, val headers: Map<String, String>)

    /** Builds request material solely from an immutable operation context. */
    private suspend fun buildCapturedRequest(
        context: NacosOperationContext,
        endpoint: String,
        params: Map<String, String>
    ): CapturedRequest {
        val mutableParams = params.toMutableMap()
        val headers = mutableMapOf<String, String>()
        val token = when (context.authMode) {
            AuthMode.TOKEN, AuthMode.NACOS_PASSWORD, AuthMode.HYBRID -> authService.getValidAccessToken(context)
            AuthMode.BASIC -> null
            AuthMode.HTTP_BASIC, AuthMode.BEARER_TOKEN -> throw ConfigurationRequired(
                listOf("Explicit V1 authentication strategies require a V1-locked profile")
            )
            AuthMode.ANONYMOUS -> null
        }
        if (token != null) {
            mutableParams["accessToken"] = token
        } else if (context.authMode == AuthMode.BASIC || context.authMode == AuthMode.HYBRID) {
            addBasicAuthHeader(headers, context)
        }
        val queryParams = mutableParams.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
        val url = if (queryParams.isEmpty()) {
            "${context.endpoint.value}$endpoint"
        } else {
            "${context.endpoint.value}$endpoint?$queryParams"
        }
        return CapturedRequest(url, headers)
    }

    private suspend fun buildUrl(
        server: NacosServerSnapshot,
        endpoint: String,
        params: Map<String, String>
    ): String {
        val mutableParams = params.toMutableMap()
        val shouldUseToken = when (server.authMode) {
            AuthMode.TOKEN, AuthMode.NACOS_PASSWORD -> true
            AuthMode.BASIC -> false
            AuthMode.HTTP_BASIC, AuthMode.BEARER_TOKEN -> throw ConfigurationRequired(
                listOf("Explicit V1 authentication strategies require a V1-locked profile")
            )
            AuthMode.HYBRID -> server.enableTokenAuth
            AuthMode.ANONYMOUS -> false
        }
        if (shouldUseToken) {
            authService.getValidAccessToken(server)?.let { mutableParams["accessToken"] = it }
        }
        val queryParams = mutableParams.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
        return if (queryParams.isEmpty()) "${server.serverUrl}$endpoint" else "${server.serverUrl}$endpoint?$queryParams"
    }

    private suspend fun buildAuthHeaders(server: NacosServerSnapshot): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        when (server.authMode) {
            AuthMode.TOKEN, AuthMode.NACOS_PASSWORD -> if (server.enableTokenAuth) authService.getValidAccessToken(server)
            AuthMode.HYBRID -> {
                val token = if (server.enableTokenAuth) authService.getValidAccessToken(server) else null
                if (token == null) addBasicAuthHeader(headers, server)
            }
            AuthMode.BASIC -> addBasicAuthHeader(headers, server)
            AuthMode.HTTP_BASIC, AuthMode.BEARER_TOKEN -> throw ConfigurationRequired(
                listOf("Explicit V1 authentication strategies require a V1-locked profile")
            )
            AuthMode.ANONYMOUS -> Unit
        }
        return headers
    }

    private fun addBasicAuthHeader(headers: MutableMap<String, String>, server: NacosServerSnapshot) {
        if (server.username.isNotEmpty() && server.password.isNotEmpty()) {
            val credentials = "${server.username}:${server.password}"
            headers["Authorization"] = "Basic " + java.util.Base64.getEncoder()
                .encodeToString(credentials.toByteArray())
        }
    }

    private fun addBasicAuthHeader(headers: MutableMap<String, String>, context: NacosOperationContext) {
        if (context.identity.principal != "<anonymous>" && context.credential.secret.isNotEmpty()) {
            val credentials = "${context.identity.principal}:${context.credential.secret}"
            val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
            headers["Authorization"] = "Basic $encodedCredentials"
        } else {
            logger.warn("No basic auth credentials configured")
        }
    }

   /**
    * Executes a GET request through the executor with auth replay on 401.
    * If the first attempt returns [NacosRequestError.Authentication], the
    * auth service's token is invalidated and the request replays once within
    * the same budget. Malformed JSON is classified as [NacosRequestError.Protocol].
    */
   private suspend fun requestJsonWithReplay(
       context: NacosOperationContext,
       endpoint: String,
       params: Map<String, String>,
       policy: RequestPolicy
   ): String {
       val request = buildCapturedRequest(context, endpoint, params)
       try {
           return executor.get(request.url, policy, request.headers)
       } catch (e: NacosRequestError.Authentication) {
           // Token may be stale; invalidate and replay once.
           logger.info("Authentication failed (${e.status}), refreshing credentials")
           authService.invalidateToken(context)
           val replay = buildCapturedRequest(context, endpoint, params)
           return executor.get(replay.url, policy, replay.headers)
       }
   }

   private suspend fun requestJsonWithReplay(
       server: NacosServerSnapshot,
       url: String,
       policy: RequestPolicy
   ): String {
       try {
           return executor.get(url, policy, buildAuthHeaders(server))
       } catch (e: NacosRequestError.Authentication) {
           authService.invalidateToken()
           return executor.get(url, policy, buildAuthHeaders(server))
       }
   }

    /**
     * Resolves a locked [OperationTarget] for the given context/namespace,
     * including AUTO → V3-first generation resolution. UI layers must use this
     * (or an equivalent) so history/publish never dispatch against UNKNOWN.
     */
    suspend fun resolveOperationTarget(
        context: NacosOperationContext,
        namespaceId: String?
    ): Result<OperationTarget> = withContext(Dispatchers.IO) {
        resolvedReadTarget(context, namespaceId)
    }

    /** Identity-scoped gateway used by history browsing and controlled publish. */
    fun operationGateway(): OperationGateway = v1Gateway

    suspend fun listConfigurationHistory(
        target: OperationTarget,
        query: HistoryQuery
    ): Result<HistoryPage> = withContext(Dispatchers.IO) {
        v1Gateway.listHistory(target, query, forceRefresh = true, useCache = false)
    }

    suspend fun readConfigurationHistoryDetail(
        target: OperationTarget,
        historyId: String
    ): Result<HistoryDetail> = withContext(Dispatchers.IO) {
        v1Gateway.readHistoryDetail(target, historyId, forceRefresh = true, useCache = false)
    }

    /**
     * Controlled publish through [PublishController]. Prefer this over
     * [publishConfiguration] for locked V1/V3 paths.
     */
    suspend fun controlledPublish(session: EditSession): PublishResult = withContext(Dispatchers.IO) {
        PublishController(OperationGatewayPublishGateway(v1Gateway)).publish(session)
    }

    /**
     * Isolated connection diagnostic from an unapplied settings snapshot.
     * Never mutates persisted profiles, sessions, cache, or the auth registry.
     */
    suspend fun diagnoseConnection(snapshot: DiagnosticSnapshot): DiagnosticReport = withContext(Dispatchers.IO) {
        ConnectionDiagnostic(
            resolver = generationResolver,
            gateway = v1Gateway
        ).diagnose(snapshot)
    }

   /**
    * Publishes (creates or updates) a configuration to Nacos via POST.
     * Uses the Nacos Open API /nacos/v1/cs/configs endpoint.
     *
     * Locked V1/V3 generations reject this path — use [controlledPublish] instead.
     */
    suspend fun publishConfiguration(
        dataId: String,
        group: String,
        content: String,
        type: String = "text",
        namespaceId: String? = null,
        operationContext: NacosOperationContext? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val context = operationContext ?: operationContextOrFailure() ?: return@withContext Result.failure(
                ConfigurationRequired(listOf("Connection configuration is incomplete"))
            )
           if (usesLockedGeneration(context)) {
               return@withContext Result.failure(
                    ConfigurationRequired(listOf("The V1 path is read-only; use controlledPublish"))
                )
            }
            val params = buildMap {
                put("dataId", dataId)
                put("group", group)
                put("content", content)
                put("type", type)
                namespaceId?.let { put("tenant", it) }
            }

            val request = buildCapturedRequest(context, CONFIG_ENDPOINT, emptyMap())
            logger.info("Publishing configuration: $dataId:$group")

            val response = requestPost(request.url, params, request.headers)
            // Nacos returns "true" on success
            val success = response.trim().equals("true", ignoreCase = true)
            if (success) {
                // Invalidate cache for this config
                cacheService.invalidateNamespace(context.identity, namespaceId)
                Result.success(true)
            } else {
                Result.failure(RuntimeException("Server responded: $response"))
            }
        } catch (e: Exception) {
            logger.warn("Error publishing configuration", e)
            Result.failure(e)
        }
   }

   private fun requestPost(url: String, params: Map<String, String>, authHeaders: Map<String, String>): String {
       val formData = encodeFormData(params)
       val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
       connection.requestMethod = "POST"
       connection.instanceFollowRedirects = false
       connection.connectTimeout = settings.getConnectionTimeoutMillis()
       connection.readTimeout = settings.getReadTimeoutMillis()
       connection.doOutput = true
       connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
       connection.setRequestProperty("Accept", "application/json")
       authHeaders.forEach { (key, value) ->
           connection.setRequestProperty(key, value)
       }
        return doRequestPost(connection, formData)
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

    /** Guards every operation before cache reads, token lookup, or remote transport. */
    private fun operationContextOrFailure() = settings.captureOperationContext().getOrNull()

    private fun usesLockedGeneration(context: NacosOperationContext): Boolean =
        context.resolvedGeneration == NacosApiGeneration.V1 ||
        context.resolvedGeneration == NacosApiGeneration.V3

    private fun v1Target(context: NacosOperationContext, namespaceId: String? = null): OperationTarget =
        OperationTarget(context, namespaceId ?: "public")

    /**
     * Returns a target whose generation is locked to a concrete V1 or V3.
     * Locked profiles (V1/V3) keep their captured generation. AUTO is resolved
     * once by probing V3 first and falling back to V1 only on a typed
     * `GenerationUnsupported`; any other V3 failure propagates without
     * fallback (design §2.1). The resolved target is then routed through the
     * gateway, so AUTO never depends on the legacy V1 wire path.
     */
    private suspend fun resolvedReadTarget(
        context: NacosOperationContext,
        namespaceId: String?
    ): Result<OperationTarget> {
        if (usesLockedGeneration(context)) return Result.success(v1Target(context, namespaceId))
        val probeTarget = v1Target(context, namespaceId)
        val generation = generationResolver.resolve(probeTarget).getOrElse { error ->
            return Result.failure(error)
        }
        persistLastKnownGeneration(context, generation)
        return Result.success(v1Target(lockedContext(context, generation), namespaceId))
    }

    private fun persistLastKnownGeneration(context: NacosOperationContext, generation: NacosApiGeneration) {
        if (generation != NacosApiGeneration.V1 && generation != NacosApiGeneration.V3) return
        try {
            ApplicationManager.getApplication()
                .getService(LastKnownGenerationStore::class.java)
                ?.put(
                    LastKnownGenerationStore.Key(
                        profileId = context.identity.profileId,
                        accessRevision = context.accessRevision,
                        canonicalEndpoint = context.endpoint.value
                    ),
                    generation
                )
        } catch (_: Exception) {
            // Persistence is best-effort outside a fully initialised application.
        }
    }

    /** Offline bootstrap helper: last successful AUTO resolution for this access key. */
    fun lastKnownGeneration(context: NacosOperationContext): NacosApiGeneration? {
        return try {
            ApplicationManager.getApplication()
                .getService(LastKnownGenerationStore::class.java)
                ?.get(
                    LastKnownGenerationStore.Key(
                        profileId = context.identity.profileId,
                        accessRevision = context.accessRevision,
                        canonicalEndpoint = context.endpoint.value
                    )
                )
        } catch (_: Exception) {
            null
        }
    }

    private fun lockedContext(
        context: NacosOperationContext,
        generation: NacosApiGeneration
    ): NacosOperationContext {
        if (context.resolvedGeneration == generation) return context
        return context.copy(
            identity = context.identity.copy(resolvedGeneration = generation),
            resolvedGeneration = generation
        )
    }

    private fun SummaryPage.toConfigListResponse(): ConfigListResponse = ConfigListResponse(
        totalCount = totalCount,
        pageNumber = pageNumber,
        pagesAvailable = pagesAvailable,
        pageItems = items.mapIndexed { index, item ->
            ConfigItem(index.toString(), item.dataId, item.group, item.content, item.type, item.tenantId)
        }
    )

    private fun diagnosticHeaders(context: com.nanyin.nacos.search.settings.NacosOperationContext): Map<String, String> {
        if (context.identity.principal == "<anonymous>" || context.credential.secret.isBlank()) return emptyMap()
        val credentials = "${context.identity.principal}:${context.credential.secret}"
        return mapOf("Authorization" to "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.toByteArray()))
    }

    private fun isIncomplete(server: NacosServerSnapshot): Boolean {
        val endpointValid = com.nanyin.nacos.search.models.CanonicalNacosEndpoint.parse(server.serverUrl).isSuccess
        val credentialsComplete = server.username.isBlank() == server.password.isBlank()
        return !endpointValid || !credentialsComplete
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
        useCache: Boolean = true,
        operationContext: NacosOperationContext? = null
    ): NacosConfiguration {
        val context = operationContext ?: operationContextOrFailure()
            ?: throw ConfigurationRequired(listOf("Connection configuration is incomplete"))
        if (!item.content.isNullOrEmpty()) {
            val configuration = NacosConfiguration(
                dataId = item.dataId,
                group = item.group,
                tenantId = item.tenant,
                content = item.content,
                type = item.type
            )
            if (useCache && settings.cacheEnabled) {
                cacheService.putConfigDetail(
                    context.identity,
                    item.tenant,
                    configuration,
                    settings.getCacheTtlMillis()
                )
            }
            return configuration
        }

        val fullConfig = getConfiguration(item.dataId, item.group, item.tenant, useCache, operationContext = context)
        return fullConfig.getOrNull() ?: NacosConfiguration(
            dataId = item.dataId,
            group = item.group,
            tenantId = item.tenant,
            content = "", // Content will be empty if fetch fails
            type = item.type
        )
    }
}
