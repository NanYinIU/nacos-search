package com.nanyin.nacos.search.managers

import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.settings.NacosSettings
import com.intellij.openapi.application.ApplicationManager
import com.nanyin.nacos.search.ui.NamespacePanel
import com.nanyin.nacos.search.ui.PaginationPanel
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages initialization of UI components and data loading
 * Handles namespace initialization, pagination setup, and initial configuration loading
 */
class InitializationManager(
    private val namespaceService: NamespaceService,
    private val nacosApiService: NacosApiService,
    private val nacosSearchService: NacosSearchService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = thisLogger()
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    
    /**
     * Initialization state
     */
    sealed class InitializationState {
        object NotStarted : InitializationState()
        object InProgress : InitializationState()
        data class Success(val namespace: NamespaceInfo?) : InitializationState()
        data class Error(val message: String, val throwable: Throwable? = null) : InitializationState()
    }
    
    private var currentState: InitializationState = InitializationState.NotStarted
    
    /**
     * Initialize all components and load initial data.
     *
     * @param profileId project-selected environment profile. When null, falls
     *   back to the app-wide active server (legacy). Capture always runs on
     *   [Dispatchers.IO] so PasswordSafe is never read on the EDT (#53).
     */
    fun initialize(
        namespacePanel: NamespacePanel,
        paginationPanel: PaginationPanel,
        profileId: String? = null,
        onInitializationComplete: (InitializationState) -> Unit
    ) {
        if (currentState is InitializationState.InProgress) {
            logger.warn("Initialization already in progress")
            return
        }
        
        currentState = InitializationState.InProgress
        
        coroutineScope.launch {
            try {
                logger.info("Starting initialization...")
                
                // Step 1: Initialize pagination panel immediately
                initializePaginationPanel(paginationPanel)
                
                // Step 2: Load and initialize namespaces
                val currentNamespace = initializeNamespaces(namespacePanel)
                
                // Step 3: Load initial configurations if namespace is available
                if (currentNamespace != null) {
                    loadInitialConfigurations(currentNamespace, profileId)
                }
                
                currentState = InitializationState.Success(currentNamespace)
                onInitializationComplete(currentState)
                
                logger.info("Initialization completed successfully")
                
            } catch (e: Exception) {
                logger.error("Initialization failed", e)
                currentState = InitializationState.Error("Initialization failed: ${e.message}", e)
                onInitializationComplete(currentState)
            }
        }
    }
    
    /**
     * Initialize pagination panel with default state
     */
    private fun initializePaginationPanel(paginationPanel: PaginationPanel) {
        logger.debug("Initializing pagination panel")
        
        // Set initial state to show pagination controls immediately
        paginationPanel.setInitialState()
        
        // Update with default pagination state
        val defaultPaginationState = NacosSearchService.PaginationState(
            currentPage = 1,
            pageSize = 10,
            totalCount = 0,
            totalPages = 0
        )
        paginationPanel.updatePagination(defaultPaginationState)
    }
    
    /**
     * Initialize namespaces and return current namespace
     */
    private suspend fun initializeNamespaces(namespacePanel: NamespacePanel): NamespaceInfo? {
        logger.debug("Initializing namespaces")
        
        try {
            // Load namespaces from server and await completion
            val result = namespaceService.loadNamespacesAsync().await()
            result.onSuccess { namespaces ->
                logger.info("Loaded ${namespaces.size} namespaces from server")
            }.onFailure { error ->
                logger.warn("Failed to load namespaces from server: ${error.message}")
            }
            
            // Refresh namespace panel UI after data is loaded
            namespacePanel.refresh()
            
            // Get current namespace from service (now populated by loadNamespacesAsync)
            val currentNamespace = namespaceService.getCurrentNamespace()
            
            if (currentNamespace != null) {
                logger.debug("Current namespace: ${currentNamespace.namespaceName} (${currentNamespace.namespaceId})")
            } else {
                logger.warn("No current namespace found after loading")
            }
            
            return currentNamespace
            
        } catch (e: Exception) {
            logger.error("Failed to initialize namespaces", e)
            throw InitializationException("Failed to load namespaces: ${e.message}", e)
        }
    }
    
    /**
     * Load initial configurations for the given namespace.
     *
     * PasswordSafe is always read on [Dispatchers.IO], even when the caller's
     * [coroutineScope] is bound to the EDT (Main). The project-selected
     * [profileId] is preferred over the app-wide active server.
     */
    private suspend fun loadInitialConfigurations(
        namespace: NamespaceInfo,
        profileId: String? = null
    ) {
        logger.debug("Loading initial configurations for namespace: ${namespace.namespaceName}")
        
        try {
            val resolvedProfileId = profileId?.takeIf { it.isNotBlank() } ?: settings.activeServerId
            val operationContext = withContext(Dispatchers.IO) {
                settings.captureOperationContext(resolvedProfileId).getOrNull()
            }
            val searchRequest = NacosSearchService.SearchRequest(
                namespace = namespace,
                pageNo = 1,
                pageSize = 10,
                serverId = resolvedProfileId,
                operationContext = operationContext
            )
            
            // Perform search to load configurations
            nacosSearchService.performSearch(searchRequest, nacosApiService)
            
        } catch (e: Exception) {
            logger.error("Failed to load initial configurations", e)
            throw InitializationException("Failed to load configurations: ${e.message}", e)
        }
    }
    
    /**
     * Reinitialize with a specific namespace
     */
    fun reinitializeWithNamespace(
        namespace: NamespaceInfo,
        paginationPanel: PaginationPanel,
        profileId: String? = null,
        onReinitializationComplete: (InitializationState) -> Unit
    ) {
        coroutineScope.launch {
            try {
                logger.info("Reinitializing with namespace: ${namespace.namespaceName}")
                
                // Reset pagination
                initializePaginationPanel(paginationPanel)
                
                // Load configurations for new namespace
                loadInitialConfigurations(namespace, profileId)
                
                currentState = InitializationState.Success(namespace)
                onReinitializationComplete(currentState)
                
            } catch (e: Exception) {
                logger.error("Reinitialization failed", e)
                currentState = InitializationState.Error("Reinitialization failed: ${e.message}", e)
                onReinitializationComplete(currentState)
            }
        }
    }
    
    /**
     * Get current initialization state
     */
    fun getCurrentState(): InitializationState = currentState
    
    /**
     * Check if initialization is complete
     */
    fun isInitialized(): Boolean = currentState is InitializationState.Success
    
    /**
     * Check if initialization failed
     */
    fun hasError(): Boolean = currentState is InitializationState.Error
    
    /**
     * Reset initialization state
     */
    fun reset() {
        currentState = InitializationState.NotStarted
    }
}

/**
 * Exception thrown during initialization
 */
class InitializationException(message: String, cause: Throwable? = null) : Exception(message, cause)
