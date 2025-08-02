package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.listeners.NamespaceChangeListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.xmlb.XmlSerializerUtil
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * State class for persisting namespace service data
 */
data class NamespaceServiceState(
    var currentNamespaceId: String? = null,
    var lastRefreshTime: Long = 0L
)

/**
 * Service for managing namespace operations and state with persistence
 */
@Service(Service.Level.APP)
@State(
    name = "NamespaceService",
    storages = [Storage("nacos-namespace-service.xml")]
)
class NamespaceService(private val nacosApiService: NacosApiService? = null) : PersistentStateComponent<NamespaceServiceState> {
    private val logger = thisLogger()
    private val apiService = nacosApiService ?: ApplicationManager.getApplication()?.getService(NacosApiService::class.java)
    
    // Persistent state
    private var state = NamespaceServiceState()
    
    // Current selected namespace
    @Volatile
    private var currentNamespace: NamespaceInfo? = null
    
    // List of available namespaces
    @Volatile
    private var availableNamespaces: List<NamespaceInfo> = emptyList()
    
    // Listeners for namespace changes
    private val changeListeners = CopyOnWriteArrayList<NamespaceChangeListener>()
    
    // Coroutine scope for async operations
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Get the currently selected namespace
     * @return Current namespace or null if none selected
     */
    fun getCurrentNamespace(): NamespaceInfo? {
        return currentNamespace
    }
    
    /**
     * Set the current namespace and notify listeners
     * @param namespace The namespace to set as current
     */
    fun setCurrentNamespace(namespace: NamespaceInfo?) {
        val oldNamespace = currentNamespace
        currentNamespace = namespace
        
        // Persist the namespace selection
        state.currentNamespaceId = namespace?.namespaceId
        
        logger.info("Namespace changed from ${oldNamespace?.namespaceName} to ${namespace?.namespaceName}")
        
        // Clear cache when namespace changes
        clearNamespaceCache()
        
        // Notify all listeners of the change
        notifyNamespaceChanged(oldNamespace, namespace)
    }
    
    /**
     * Get all available namespaces
     * @return List of available namespaces
     */
    fun getAvailableNamespaces(): List<NamespaceInfo> {
        return availableNamespaces.toList()
    }
    
    /**
     * Load namespaces from Nacos server asynchronously
     * @return Deferred result containing list of namespaces
     */
    fun loadNamespacesAsync(): Deferred<Result<List<NamespaceInfo>>> {
        return serviceScope.async {
            try {
                logger.debug("Loading namespaces from Nacos server")
                val result = apiService?.getNamespaces() ?: Result.failure(IllegalStateException("NacosApiService not available"))
                
                if (result.isSuccess) {
                    val namespaces = result.getOrNull() ?: emptyList()
                    availableNamespaces = namespaces
                    state.lastRefreshTime = System.currentTimeMillis()
                    logger.info("Successfully loaded ${namespaces.size} namespaces")
                    
                    // Restore previously selected namespace if available
                    restoreCurrentNamespace(namespaces)
                } else {
                    logger.warn("Failed to load namespaces: ${result.exceptionOrNull()?.message}")
                }
                
                result
            } catch (e: Exception) {
                logger.error("Error loading namespaces", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Refresh namespaces from server
     * @return Deferred result of the refresh operation
     */
    fun refreshNamespaces(): Deferred<Result<List<NamespaceInfo>>> {
        logger.debug("Refreshing namespaces")
        return loadNamespacesAsync()
    }
    
    /**
     * Add a namespace change listener
     * @param listener The listener to add
     */
    fun addNamespaceChangeListener(listener: NamespaceChangeListener) {
        changeListeners.add(listener)
        logger.debug("Added namespace change listener: ${listener.javaClass.simpleName}")
    }
    
    /**
     * Remove a namespace change listener
     * @param listener The listener to remove
     */
    fun removeNamespaceChangeListener(listener: NamespaceChangeListener) {
        changeListeners.remove(listener)
        logger.debug("Removed namespace change listener: ${listener.javaClass.simpleName}")
    }
    
    /**
     * Find namespace by ID
     * @param namespaceId The namespace ID to search for
     * @return The namespace if found, null otherwise
     */
    fun findNamespaceById(namespaceId: String?): NamespaceInfo? {
        if (namespaceId == null) return null
        return availableNamespaces.find { it.namespaceId == namespaceId }
    }
    
    /**
     * Check if a namespace exists
     * @param namespaceId The namespace ID to check
     * @return True if the namespace exists, false otherwise
     */
    fun namespaceExists(namespaceId: String?): Boolean {
        return findNamespaceById(namespaceId) != null
    }
    
    /**
     * Get the public namespace (default namespace)
     * @return The public namespace if available
     */
    fun getPublicNamespace(): NamespaceInfo? {
        return availableNamespaces.find { it.namespaceId.isEmpty() || it.namespaceId == "public" }
    }
    
    /**
     * Notify all listeners of namespace change
     * @param oldNamespace The previous namespace
     * @param newNamespace The new namespace
     */
    private fun notifyNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
        serviceScope.launch {
            changeListeners.forEach { listener ->
                try {
                    listener.onNamespaceChanged(oldNamespace, newNamespace)
                } catch (e: Exception) {
                    logger.warn("Error notifying namespace change listener", e)
                }
            }
        }
    }
    
    /**
     * Restore the current namespace from persistent state
     * @param namespaces Available namespaces to restore from
     */
    private fun restoreCurrentNamespace(namespaces: List<NamespaceInfo>) {
        if (currentNamespace != null) return // Already set
        
        val savedNamespaceId = state.currentNamespaceId
        if (savedNamespaceId != null) {
            val savedNamespace = namespaces.find { it.namespaceId == savedNamespaceId }
            if (savedNamespace != null) {
                currentNamespace = savedNamespace
                logger.info("Restored namespace: ${savedNamespace.namespaceName}")
                return
            } else {
                logger.warn("Saved namespace ID '$savedNamespaceId' not found in available namespaces")
            }
        }
        
        // Fallback: set the first namespace if available
        if (namespaces.isNotEmpty()) {
            setCurrentNamespace(namespaces.first())
        }
    }
    
    /**
     * Clear namespace-related cache
     */
    private fun clearNamespaceCache() {
        try {
            // Clear any cached configuration data when namespace changes
            // This ensures fresh data is loaded for the new namespace
            logger.debug("Clearing namespace cache")
            
            // If there's a cache service, clear it here
            // For now, we just log the action
        } catch (e: Exception) {
            logger.warn("Error clearing namespace cache", e)
        }
    }
    
    /**
     * Get the last refresh time
     * @return Last refresh timestamp in milliseconds
     */
    fun getLastRefreshTime(): Long {
        return state.lastRefreshTime
    }
    
    /**
     * Check if namespaces need refresh based on time threshold
     * @param thresholdMs Time threshold in milliseconds (default: 5 minutes)
     * @return True if refresh is needed
     */
    fun needsRefresh(thresholdMs: Long = 5 * 60 * 1000): Boolean {
        return System.currentTimeMillis() - state.lastRefreshTime > thresholdMs
    }
    
    // PersistentStateComponent implementation
    override fun getState(): NamespaceServiceState {
        return state
    }
    
    override fun loadState(state: NamespaceServiceState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }
    
    /**
     * Dispose the service and clean up resources
     */
    fun dispose() {
        logger.debug("Disposing NamespaceService")
        serviceScope.cancel()
        changeListeners.clear()
        currentNamespace = null
        availableNamespaces = emptyList()
        clearNamespaceCache()
    }
}