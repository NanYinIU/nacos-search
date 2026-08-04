package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NacosApiResponse
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
import com.nanyin.nacos.search.services.operations.EphemeralV1Authenticator
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
import com.nanyin.nacos.search.services.operations.GenerationProbeFlight
import com.nanyin.nacos.search.services.operations.GenerationProbeKey
import com.nanyin.nacos.search.services.operations.GenerationResolver
import com.nanyin.nacos.search.services.operations.GenerationSessionCapture
import com.nanyin.nacos.search.services.operations.SessionGenerationState
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
    private val v1GatewayOverride: OperationGateway? = null,
    /**
     * Test seam: override the request executor so tests can observe every
     * outgoing HTTP request (legacy wire path and gateway path both funnel
     * through this executor). Production uses the default transport.
     */
    private val executorOverride: NacosRequestExecutor? = null,
    /**
     * Test seam: inject a project-session generation owner. Production looks
     * up [ProjectSessionEpochs] for the active profile, or falls back to an
     * in-memory default session for app-level callers without an open project.
     */
    private val sessionGenerationOverride: SessionGenerationState? = null,
    /** Test seam: shared formal probe flight. Isolated diagnostics never use it. */
    private val probeFlightOverride: GenerationProbeFlight? = null
) {
    private val logger = thisLogger()
    private val gson = Gson()
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
  private val authService = ApplicationManager.getApplication().getService(NacosAuthService::class.java)
    private val executor = executorOverride ?: NacosRequestExecutor()

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

   /**
    * Builds a throwaway adapter stack for one diagnostic run.
    *
    * Budget: [RequestPolicy.DIAGNOSTIC] raises only the per-request total budget
    * (30s vs 15s) — attempt count and connect/read timeouts are identical to
    * [RequestPolicy.INTERACTIVE], so this selects no retry behaviour that
    * ADR-0021 reserves for the transport seam. A diagnostic is worth the longer
    * budget because ADR-0022 makes it up to four sequential stages whose job is
    * to characterise the connection and report per-stage timing; reporting a
    * slow-but-working server as a connection failure sends the user to debug the
    * wrong thing. The settings UI runs it in a cancellable background task, so
    * nothing blocks on the wider ceiling.
    *
    * Isolation: ADR-0022 forbids diagnostics from touching the cache, the shared
    * probe flight, the session, or the authentication registry. Building the
    * stack here rather than sharing the formal one gets all four structurally —
    * a fresh [OperationGateway] defaults to a no-op cache and its own
    * observation sequence, a fresh [GenerationResolver] cannot join the formal
    * probe flight, and [EphemeralV1Authenticator] holds any Nacos-password token
    * in a field that dies with this object.
    *
    * Per call, not cached: a token acquired for one unapplied draft must never
    * serve the next one, because the user may have edited the credentials
    * between two clicks of Test Connection.
    */
   private fun newDiagnosticStack(): Pair<GenerationResolver, OperationGateway> {
       val transport = NacosRequestExecutorProtocolTransport(executor, RequestPolicy.DIAGNOSTIC)
       val v1 = V1ProtocolAdapter(
           transport,
           EphemeralV1Authenticator(login = { context -> authService.loginWithoutRecording(context) })
       )
       val v3 = V3ProtocolAdapter(transport)
       return GenerationResolver(v3, v1) to OperationGateway(
           mapOf(
               NacosApiGeneration.V1 to v1,
               NacosApiGeneration.V3 to v3
           )
       )
   }

   /** Formal AUTO probes share this flight; diagnostics do not. */
   private val probeFlight = probeFlightOverride ?: sharedProbeFlight
   private val fallbackEpoch = java.util.concurrent.atomic.AtomicLong(0)
   /**
    * Fallback session used when no open project owns the profile. Still
    * in-memory only and never persisted — satisfies sequential reuse for
    * app-level callers (plugin init, unit tests without a Project).
    */
   private val fallbackSessionGeneration = SessionGenerationState(
       currentEpoch = { fallbackEpoch.get() },
       onGenerationChanged = { fallbackEpoch.incrementAndGet(); Unit }
   )
  companion object {
       private const val CONFIG_ENDPOINT = "/nacos/v1/cs/configs"
       private const val CONFIG_LIST_ENDPOINT = "/nacos/v1/cs/configs"
       private const val NAMESPACE_ENDPOINT = "/nacos/v1/console/namespaces"
        /** Process-wide formal probe flight shared by all NacosApiService instances. */
        private val sharedProbeFlight = GenerationProbeFlight()

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
     * Tests connection using an already-captured [operationContext].
     * Never reads current settings, the credential store, or the project session
     * (issue #50 / ADR-0010) — the caller must supply a valid context.
     */
   suspend fun testConnection(operationContext: NacosOperationContext): Result<Boolean> = withContext(Dispatchers.IO) {
       try {
           if (usesLockedGeneration(operationContext)) {
               return@withContext v1Gateway.probe(v1Target(operationContext)).map { true }
           }
           // AUTO: resolve the generation by probing V3 first, then V1. A
           // successful resolution (which already probed the server) proves
           // reachability; the formal gateway probe confirms the adapter path.
           val target = resolvedReadTarget(operationContext, null).getOrElse { error ->
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
       val context = operationContext ?: operationContextOrFailure()
           ?: return Result.failure(ConfigurationRequired(listOf("Connection configuration is incomplete")))
       val result = loadNamespace(namespaceId, useCache, operationContext = context)
       return result.map { it.configurations }
   }

   /**
    * Loads configuration **summaries** for a namespace (ADR-0016 / ADR-0041).
    * Issues one list request per summary page and never fetches configuration
    * bodies — completeness depends only on summary pagination. Detail content
    * for code navigation is supplied by the independent navigation detail
    * prefetch (issue #51), not by this path.
    *
    * Requires an already-captured [operationContext]; never reads settings or the
    * credential store (issue #50). Every generation is resolved and dispatched
    * through the operation gateway — there is no legacy server-snapshot path.
    *
    * Retry budget is not a parameter: per ADR-0021 every request crosses one
    * transport seam that classifies retries by operation kind (idempotent read /
    * write / login), not by who asked. A background preheat and a foreground
    * search of the same namespace therefore get the same bounded budget.
    */
   suspend fun loadNamespace(
       namespaceId: String? = null,
       useCache: Boolean = true,
       operationContext: NacosOperationContext
   ): Result<NamespaceLoadResult> {
       return try {
           val allSummaries = mutableListOf<NacosConfiguration>()
           var expectedCount = 0
           var pageNo = 1
           val pageSize = 100

           do {
               val result = listConfigurations(
                   namespaceId, pageNo, pageSize,
                   useCache = useCache,
                   operationContext = operationContext
               )
               if (result.isFailure) {
                   // First page failure with nothing loaded → FAILED; a later
                   // page failure with some rows → PARTIAL (summary pagination
                   // incomplete). Detail failures cannot occur here.
                   val completeness = if (allSummaries.isEmpty()) {
                       DatasetCompleteness.FAILED
                   } else {
                       DatasetCompleteness.PARTIAL
                   }
                   return Result.success(
                       NamespaceLoadResult(completeness, expectedCount, allSummaries, emptyList())
                   )
               }

               val response = result.getOrNull() ?: break
               expectedCount = response.totalCount

               // Summaries only — empty content is intentional; the namespace
               // index never promises bodies (ADR-0041 / issue #52).
               response.pageItems.forEach { item ->
                   allSummaries.add(
                       NacosConfiguration(
                           dataId = item.dataId,
                           group = item.group,
                           tenantId = item.tenant,
                           content = item.content.orEmpty(),
                           type = item.type
                       )
                   )
               }
               pageNo++
               if (response.pageItems.size < pageSize) break
           } while (true)

           // Completeness from summary pagination alone: we got every expected
           // summary row (or the server reported zero). A detail failure can no
           // longer mark the index partial because we never request details.
           val completeness = when {
               expectedCount > 0 && allSummaries.size < expectedCount -> DatasetCompleteness.PARTIAL
               else -> DatasetCompleteness.COMPLETE
           }
           Result.success(NamespaceLoadResult(completeness, expectedCount, allSummaries, emptyList()))
       } catch (e: Exception) {
           logger.warn("Error loading namespace", e)
           Result.failure(e)
       }
   }
    
    /**
     * Retrieves all namespaces from Nacos using an already-captured
     * [operationContext]. Never reads current settings, the credential store,
     * or the project session (issue #50 / ADR-0010).
     */
    suspend fun getNamespaces(operationContext: NacosOperationContext): Result<List<NamespaceInfo>> = withContext(Dispatchers.IO) {
        try {
           val resolvedTarget = resolvedReadTarget(operationContext, null).getOrElse { error ->
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
        val (resolver, gateway) = newDiagnosticStack()
        ConnectionDiagnostic(resolver = resolver, gateway = gateway).diagnose(snapshot)
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
     * Locked profiles (V1/V3) keep their captured generation and never probe.
     * AUTO is resolved **once per project session** (ADR-0006 / ADR-0034): the
     * in-memory session result is reused; concurrent formal detections share a
     * single flight keyed by the complete non-secret probe inputs; a consumer
     * whose capture no longer matches does not commit the shared result.
     * Isolated diagnostics never enter this path.
     */
    private suspend fun resolvedReadTarget(
        context: NacosOperationContext,
        namespaceId: String?
    ): Result<OperationTarget> {
        if (usesLockedGeneration(context)) return Result.success(v1Target(context, namespaceId))

        val session = sessionOwner(context)
        session.resolvedIfCompatible(
            profileId = context.identity.profileId,
            profileRevision = context.profileRevision,
            accessRevision = context.accessRevision
        )?.let { cached ->
            return Result.success(v1Target(lockedContext(context, cached), namespaceId))
        }

        val capture = GenerationSessionCapture(
            profileId = context.identity.profileId,
            profileRevision = context.profileRevision,
            accessRevision = context.accessRevision,
            sessionEpoch = session.snapshotEpoch(),
            canonicalEndpoint = context.endpoint.value,
            authMode = context.authMode,
            principal = context.identity.principal
        )
        val probeKey = GenerationProbeKey.from(capture)
        val generation = probeFlight.joinOrProbe(probeKey) {
            generationResolver.resolve(v1Target(context, namespaceId))
        }.getOrElse { error ->
            return Result.failure(error)
        }

        // Commit only when the consumer's capture still matches live identity
        // keys, epoch, and tombstone (ADR-0034). A rejected commit still lets
        // this operation use the probed generation, but neither the session nor
        // last-known generation is updated.
        val live = liveIdentityKeys(context)
        if (live != null && session.tryCommit(generation, capture, live)) {
            persistLastKnownGeneration(context, generation)
        }
        return Result.success(v1Target(lockedContext(context, generation), namespaceId))
    }

    private fun sessionOwner(context: NacosOperationContext): SessionGenerationState {
        sessionGenerationOverride?.let { return it }
        ProjectSessionEpochs.findForProfile(context.identity.profileId)
            ?.generationState()
            ?.let { return it }
        return fallbackSessionGeneration
    }

    /**
     * Re-reads the live profile's non-secret identity keys for the ADR-0034
     * commit recheck. Returns null when the profile is gone in production —
     * commit is refused. Injected test sessions fall back to the capture keys.
     */
    private fun liveIdentityKeys(context: NacosOperationContext): SessionGenerationState.LiveIdentityKeys? {
        val fromCapture = SessionGenerationState.LiveIdentityKeys(
            profileId = context.identity.profileId,
            profileRevision = context.profileRevision,
            accessRevision = context.accessRevision
        )
        return try {
            val profile = settings.getProfile(context.identity.profileId)
            if (profile != null) {
                SessionGenerationState.LiveIdentityKeys(
                    profileId = profile.id,
                    profileRevision = profile.profileRevision,
                    accessRevision = profile.accessRevision
                )
            } else if (sessionGenerationOverride != null) {
                // Harness profiles are not registered in NacosSettings.
                fromCapture
            } else {
                null
            }
        } catch (_: Exception) {
            if (sessionGenerationOverride != null) fromCapture else null
        }
    }

    /**
     * Offline bootstrap: rebuild the access identity using the persisted
     * last-known generation so the same identity's cache can be located.
     * Marks nothing as remote-authorised — callers must treat restored data as
     * unconfirmed and must still run formal detection before any remote op
     * (ADR-0034). Returns null when no last-known value exists.
     */
    fun offlineCacheIdentity(context: NacosOperationContext): com.nanyin.nacos.search.models.AccessIdentity? {
        val lastKnown = lastKnownGeneration(context) ?: return null
        if (lastKnown != NacosApiGeneration.V1 && lastKnown != NacosApiGeneration.V3) return null
        return context.identity.copy(resolvedGeneration = lastKnown)
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
     * Converts ConfigItem to NacosConfiguration with full content using an
     * already-captured [operationContext]. Never reads current settings, the
     * credential store, or the project session (issue #50 / ADR-0010).
     */
    suspend fun getConfigurationFromItem(
        item: ConfigItem,
        useCache: Boolean = true,
        operationContext: NacosOperationContext
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
                cacheService.putConfigDetail(
                    operationContext.identity,
                    item.tenant,
                    configuration,
                    settings.getCacheTtlMillis()
                )
            }
            return configuration
        }

        val fullConfig = getConfiguration(item.dataId, item.group, item.tenant, useCache, operationContext = operationContext)
        return fullConfig.getOrNull() ?: NacosConfiguration(
            dataId = item.dataId,
            group = item.group,
            tenantId = item.tenant,
            content = "", // Content will be empty if fetch fails
            type = item.type
        )
    }
}
