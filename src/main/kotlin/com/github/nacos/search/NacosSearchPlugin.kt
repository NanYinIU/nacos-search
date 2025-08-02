package com.github.nacos.search

import com.github.nacos.search.services.CacheService
import com.github.nacos.search.services.NacosApiService
import com.github.nacos.search.services.SearchService
import com.github.nacos.search.settings.NacosSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.delay

/**
 * Main plugin class that manages the Nacos Search plugin lifecycle
 */
@Service(Service.Level.APP)
class NacosSearchPlugin : StartupActivity {
    
    private val logger = thisLogger()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var autoRefreshJob: Job? = null
    
    // Services
    private val settings by lazy { ApplicationManager.getApplication().getService(NacosSettings::class.java) }
    private val apiService by lazy { ApplicationManager.getApplication().getService(NacosApiService::class.java) }
    private val cacheService by lazy { ApplicationManager.getApplication().getService(CacheService::class.java) }
    private val searchService by lazy { ApplicationManager.getApplication().getService(SearchService::class.java) }
    
    override fun runActivity(project: Project) {
        logger.info("Initializing Nacos Search Plugin")
        
        try {
            initializePlugin()
            setupAutoRefresh()
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
                    val cachedConfigs = cacheService.getAllCachedConfigurations()
                    logger.info("Loaded ${cachedConfigs.size} configurations from cache")
                } catch (e: Exception) {
                    logger.error("Error loading cached configurations", e)
                }
            }
        }
        
        // Test connection in background
        coroutineScope.launch {
            try {
                val connectionResult = apiService.testConnection()
                if (connectionResult.isSuccess) {
                    logger.info("Successfully connected to Nacos server: ${settings.serverUrl}")
                    
                    // Load initial data if cache is empty or disabled
                    if (!settings.cacheEnabled) {
                        loadInitialData()
                    } else {
                        try {
                            val cachedConfigs = cacheService.getAllCachedConfigurations()
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
            logger.info("Loading initial configuration data from Nacos server")
            
            val result = apiService.getAllConfigurations()
            if (result.isSuccess) {
                val configurations = result.getOrThrow()
                
                if (settings.cacheEnabled) {
                    cacheService.cacheConfigurations(configurations)
                    logger.info("Cached ${configurations.size} configurations")
                }
                
                logger.info("Successfully loaded ${configurations.size} configurations")
            } else {
                logger.error("Failed to load initial data: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            logger.error("Error loading initial data", e)
        }
    }
    
    /**
     * Setup automatic cache refresh if enabled
     */
    private fun setupAutoRefresh() {
        if (!settings.autoRefreshEnabled || !settings.cacheEnabled) {
            logger.info("Auto refresh is disabled")
            return
        }
        
        val intervalMinutes = settings.autoRefreshIntervalMinutes
        if (intervalMinutes <= 0) {
            logger.warn("Invalid auto refresh interval: $intervalMinutes minutes")
            return
        }
        
        // Stop existing job if running
        stopAutoRefresh()
        
        autoRefreshJob = coroutineScope.launch {
            while (isActive) {
                try {
                    delay(intervalMinutes * 60 * 1000L) // Convert minutes to milliseconds
                    logger.debug("Running auto refresh")
                    refreshCache()
                } catch (e: CancellationException) {
                    logger.info("Auto refresh cancelled")
                    break
                } catch (e: Exception) {
                    logger.error("Error during auto refresh", e)
                }
            }
        }
        
        logger.info("Auto refresh scheduled every $intervalMinutes minutes")
    }
    
    /**
     * Stop automatic cache refresh
     */
    private fun stopAutoRefresh() {
        autoRefreshJob?.let { job ->
            job.cancel()
            autoRefreshJob = null
            logger.info("Auto refresh stopped")
        }
    }
    
    /**
     * Refresh cache from Nacos server
     */
    suspend fun refreshCache(): Result<Int> {
        return try {
            logger.info("Refreshing cache from Nacos server")
            
            val result = apiService.getAllConfigurations()
            if (result.isSuccess) {
                val configurations = result.getOrThrow()
                
                if (settings.cacheEnabled) {
                    cacheService.cacheConfigurations(configurations)
                }
                
                logger.info("Cache refreshed with ${configurations.size} configurations")
                Result.success(configurations.size)
            } else {
                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                logger.error("Failed to refresh cache: ${error.message}")
                Result.failure(error)
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
            cacheService.clearCache()
            logger.info("Cache cleared")
        } catch (e: Exception) {
            logger.error("Error clearing cache", e)
        }
    }
    
    /**
     * Get plugin statistics
     */
    suspend fun getStatistics(): PluginStatistics {
        val cachedCount = if (settings.cacheEnabled) {
            cacheService.getAllCachedConfigurations().size
        } else {
            0
        }
        
        return PluginStatistics(
            cachedConfigurationsCount = cachedCount,
            cacheEnabled = settings.cacheEnabled,
            autoRefreshEnabled = settings.autoRefreshEnabled,
            serverUrl = settings.serverUrl,
            lastRefreshTime = null // TODO: Implement getLastRefreshTime in CacheService
        )
    }
    
    /**
     * Restart auto refresh with new settings
     */
    fun restartAutoRefresh() {
        logger.info("Restarting auto refresh with updated settings")
        setupAutoRefresh()
    }
    
    /**
     * Dispose plugin resources
     */
    fun dispose() {
        logger.info("Disposing Nacos Search Plugin")
        
        try {
            stopAutoRefresh()
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
        fun getInstance(): NacosSearchPlugin {
            return ApplicationManager.getApplication().getService(NacosSearchPlugin::class.java)
        }
    }
}

/**
 * Plugin statistics data class
 */
data class PluginStatistics(
    val cachedConfigurationsCount: Int,
    val cacheEnabled: Boolean,
    val autoRefreshEnabled: Boolean,
    val serverUrl: String,
    val lastRefreshTime: Long?
)