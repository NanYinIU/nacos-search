package com.nanyin.nacos.search

import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.IndexOutcome
import com.nanyin.nacos.search.services.NamespaceIndexCoordinator
import com.nanyin.nacos.search.services.captureNamespaceIndexRequest
import com.nanyin.nacos.search.services.captureAccessIdentity
import com.nanyin.nacos.search.services.NavigationIndexRefreshService
import com.nanyin.nacos.search.services.NavigationDetailPrefetchService
import com.nanyin.nacos.search.services.requestManualNamespaceRefresh
import com.nanyin.nacos.search.services.requestStartupNamespaceIndex
import com.nanyin.nacos.search.psi.NacosKeyIndexService
import com.nanyin.nacos.search.settings.NacosSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.*

/**
 * Main plugin class that manages the Nacos Search plugin lifecycle
 */
@Service(Service.Level.APP)
class NacosSearchPlugin : ProjectActivity, com.intellij.openapi.Disposable {

   private val logger = thisLogger()
  private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

   /** Test/inspection helper: whether the plugin coroutine scope is still active. */
   internal fun isScopeActive(): Boolean = coroutineScope.isActive

    // Services
    private val settings by lazy { ApplicationManager.getApplication().getService(NacosSettings::class.java) }
    private val apiService by lazy { ApplicationManager.getApplication().getService(NacosApiService::class.java) }
    private val cacheService by lazy { ApplicationManager.getApplication().getService(CacheService::class.java) }
    private val indexCoordinator by lazy { ApplicationManager.getApplication().getService(NamespaceIndexCoordinator::class.java) }

    private fun keyIndexService(): NacosKeyIndexService =
        ApplicationManager.getApplication().getService(NacosKeyIndexService::class.java)

    override suspend fun execute(project: Project) {
        logger.info("Initializing Nacos Search Plugin")

       try {
           initializePlugin()
           logger.info("Nacos Search Plugin initialized successfully")
        } catch (e: Exception) {
            logger.error("Failed to initialize Nacos Search Plugin", e)
        }
    }
    
    /**
     * Initialize the plugin components
     */
    private fun initializePlugin() {
        // Validate settings
        val validationErrors = settings.validate()
        if (validationErrors.isNotEmpty()) {
            logger.warn("Plugin settings validation failed: ${validationErrors.joinToString(", ")}")
            return
        }
        
        // Initialize services
        logger.info("Initializing plugin services")
        
        // Load cached configurations if available
        if (settings.cacheEnabled) {
            coroutineScope.launch {
                try {
                    val cachedConfigs = cacheService.getAllCachedConfigurations(settings.captureAccessIdentity())
                    logger.info("Loaded ${cachedConfigs.size} configurations from cache")
                } catch (e: Exception) {
                    logger.error("Error loading cached configurations", e)
                }
            }
        }
        
        // Test connection in background — capture context first so the read
        // module never re-reads settings mid-flight (issue #50).
        coroutineScope.launch {
            try {
                val context = settings.captureOperationContext().getOrElse { error ->
                    logger.warn("Connection test skipped: ${error.message}")
                    return@launch
                }
                val connectionResult = apiService.testConnection(context)
                if (connectionResult.isSuccess) {
                    logger.info("Successfully connected to Nacos server: ${context.endpoint.value}")
                    
                    // Load initial data if cache is empty or disabled
                    if (!settings.cacheEnabled) {
                        loadInitialData()
                    } else {
                        try {
                            val cachedConfigs = cacheService.getAllCachedConfigurations(settings.captureAccessIdentity())
                            if (cachedConfigs.isEmpty()) {
                                loadInitialData()
                            }
                        } catch (e: Exception) {
                            logger.error("Error checking cached configurations", e)
                            loadInitialData()
                        }
                    }
                } else {
                    logger.warn("Failed to connect to Nacos server: ${connectionResult.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                logger.error("Error testing connection to Nacos server", e)
            }
        }
    }
    
    /**
     * Load initial configuration data from Nacos server
     */
    private suspend fun loadInitialData() {
        try {
            logger.info("Loading initial configuration metadata from Nacos server")
            
            val result = apiService.listConfigurations(pageNo = 1, pageSize = 200, useCache = true)
            if (result.isSuccess) {
                val response = result.getOrThrow().value
                logger.info("Successfully loaded metadata for ${response.pageItems.size}/${response.totalCount} configurations")
               // Warm the @NacosValue key index from persisted/opened configs so
               // code gutter markers appear without blocking the highlighter.
               val identity = settings.captureAccessIdentity()
               keyIndexService().ensureIndexBuilt(cacheService.snapshot(identity))

                // Preheat the full namespace index in the background so the
                // first content/regex search does not have to pull every page
                // on demand. Best-effort: failures are logged and silently
                // fall back to the existing on-demand pull path.
                preheatNamespaceIndex(namespaceId = null)
            } else {
                logger.error("Failed to load initial data: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            logger.error("Error loading initial data", e)
        }
    }

    /**
     * Preheat the namespace index for [namespaceId] by fetching all
     * configurations and storing them as a single index entry. Runs in the
     * background and never blocks startup or UI. On failure the caller's
     * on-demand pull (searchWithLocalIndex) still works.
     */
    private fun preheatNamespaceIndex(namespaceId: String?) {
        if (!settings.cacheEnabled) return
        // Prefetch is project-scoped and independent of index completion
        // (ADR-0043). Kick it for every open project up front — do not wait
        // for the namespace index flight, which carries no project at startup.
        val identityForPrefetch = try {
            settings.captureAccessIdentity()
        } catch (_: Exception) {
            null
        }
        if (identityForPrefetch != null) {
            com.intellij.openapi.project.ProjectManager.getInstance().openProjects
                .filter { !it.isDefault && !it.isDisposed }
                .forEach { openProject ->
                    ApplicationManager.getApplication()
                        .getService(NavigationDetailPrefetchService::class.java)
                        .requestIfNeeded(openProject, identityForPrefetch, namespaceId)
                }
        }
        coroutineScope.launch {
            try {
                val indexRequest = settings.captureNamespaceIndexRequest(namespaceId)
                val existing = cacheService.getNamespaceIndex(indexRequest.key.identity, namespaceId)
                if (existing != null) {
                    keyIndexService().ensureIndexBuilt(cacheService.snapshot(indexRequest.key.identity))
                    return@launch
                }

                val outcome = indexCoordinator.requestStartupNamespaceIndex(indexRequest)
                if (outcome is IndexOutcome.Complete || outcome is IndexOutcome.Partial) {
                    ApplicationManager.getApplication()
                        .getService(NavigationIndexRefreshService::class.java)
                        .refresh(indexRequest.key.identity, null)
                }
                if (outcome is IndexOutcome.Complete) {
                    logger.info("Preheated namespace index for '${namespaceId ?: "public"}' with ${outcome.count} configurations")
                } else {
                    logger.warn("Namespace index preheat did not complete for '${namespaceId ?: "public"}': $outcome")
                }
            } catch (e: Exception) {
                logger.warn("Namespace index preheat error for '${namespaceId ?: "public"}'", e)
            }
        }
    }
    
   /**
    * Refresh cache from Nacos server
     */
    suspend fun refreshCache(namespaceId: String): Result<Int> {
        return try {
            logger.info("Refreshing full namespace cache for '${namespaceId.ifBlank { "public" }}'")
            // captureNamespaceIndexRequest fails closed with ConfigurationRequired;
            // the surrounding catch turns it into Result.failure (issue #50).
            val indexRequest = settings.captureNamespaceIndexRequest(namespaceId)
            // Prefetch is independent of index completion (ADR-0043). Manual
            // refresh carries no project, so each open project self-triggers
            // before the index flight returns.
            com.intellij.openapi.project.ProjectManager.getInstance().openProjects
                .filter { !it.isDefault && !it.isDisposed }
                .forEach { openProject ->
                    ApplicationManager.getApplication()
                        .getService(NavigationDetailPrefetchService::class.java)
                        .requestIfNeeded(openProject, indexRequest.key.identity, namespaceId)
                }
            when (val outcome = indexCoordinator.requestManualNamespaceRefresh(indexRequest)) {
                is IndexOutcome.Complete -> {
                    ApplicationManager.getApplication()
                        .getService(NavigationIndexRefreshService::class.java)
                        .refresh(indexRequest.key.identity, null)
                    Result.success(outcome.count)
                }
                is IndexOutcome.Partial -> {
                    ApplicationManager.getApplication()
                        .getService(NavigationIndexRefreshService::class.java)
                        .refresh(indexRequest.key.identity, null)
                    // Prefer the typed stopping cause (authn/authz/connection/…)
                    // so Tools → refresh surfaces the same remote error search
                    // does (issue #122), not only a generic partial-count message.
                    Result.failure(
                        outcome.stoppingCause
                            ?: IllegalStateException(
                                "Namespace refresh loaded ${outcome.loaded}/${outcome.expected} configurations"
                            )
                    )
                }
                is IndexOutcome.Failed -> Result.failure(outcome.error)
                else -> Result.failure(IllegalStateException("Namespace refresh did not produce a complete dataset: $outcome"))
            }
        } catch (e: Exception) {
            logger.error("Error refreshing cache", e)
            Result.failure(e)
        }
    }
    
    /**
     * Clear all cached data
     */
    suspend fun clearCache() {
        try {
            // A user's clear is a cache mutation and takes an observation
            // sequence, so a read already in flight cannot write its result
            // back afterwards (ADR-0045). Issuing it is the operation layer's
            // job — this class asks for the gesture, it does not perform the
            // mutation (ADR-0052).
            apiService.clearCache()
            logger.info("Cache cleared")
        } catch (e: Exception) {
            logger.error("Error clearing cache", e)
        }
    }
    
   /**
    * Dispose plugin resources
     */
    override fun dispose() {
        logger.info("Disposing Nacos Search Plugin")
        
       try {
           coroutineScope.cancel()
            logger.info("Plugin disposed successfully")
        } catch (e: Exception) {
            logger.error("Error disposing plugin", e)
        }
    }
    
    companion object {
        /**
         * Get the plugin instance
         */
        fun getInstance(): com.nanyin.nacos.search.NacosSearchPlugin {
            return ApplicationManager.getApplication().getService(com.nanyin.nacos.search.NacosSearchPlugin::class.java)
        }
    }
}
