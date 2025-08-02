package com.nanyin.nacos.search.managers

import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.ui.NamespacePanel
import com.nanyin.nacos.search.ui.PaginationPanel
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
     * Initialize all components and load initial data
     */
    fun initialize(
        namespacePanel: NamespacePanel,
        paginationPanel: PaginationPanel,
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
                    loadInitialConfigurations(currentNamespace)
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
            // Refresh namespace panel to load all namespaces
            namespacePanel.refresh()
            
            // Get current namespace from service
            val currentNamespace = namespaceService.getCurrentNamespace()
            
            if (currentNamespace != null) {
                logger.debug("Current namespace: ${currentNamespace.namespaceName} (${currentNamespace.namespaceId})")
            } else {
                logger.warn("No current namespace found")
            }
            
            return currentNamespace
            
        } catch (e: Exception) {
            logger.error("Failed to initialize namespaces", e)
            throw InitializationException("Failed to load namespaces: ${e.message}", e)
        }
    }
    
    /**
     * Load initial configurations for the given namespace
     */
    private suspend fun loadInitialConfigurations(namespace: NamespaceInfo) {
        logger.debug("Loading initial configurations for namespace: ${namespace.namespaceName}")
        
        try {
            // Create search request for loading all configurations
            val searchRequest = NacosSearchService.SearchRequest(
                namespace = namespace,
                pageNo = 1,
                pageSize = 10
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
        onReinitializationComplete: (InitializationState) -> Unit
    ) {
        coroutineScope.launch {
            try {
                logger.info("Reinitializing with namespace: ${namespace.namespaceName}")
                
                // Reset pagination
                initializePaginationPanel(paginationPanel)
                
                // Load configurations for new namespace
                loadInitialConfigurations(namespace)
                
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