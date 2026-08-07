package com.nanyin.nacos.search.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/**
 * Application-level gateway for Namespace discovery.
 *
 * Discovered options are returned to the invoking project's panel. Selection
 * and option presentation remain project-local; this service deliberately
 * owns no current Namespace, persistent state, or selection listeners.
 */
@Service(Service.Level.APP)
class NamespaceService(private val nacosApiService: NacosApiService? = null) : Disposable {
    private val logger = thisLogger()
    private val apiService = nacosApiService
        ?: ApplicationManager.getApplication()?.getService(NacosApiService::class.java)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Loads selectable Namespace options for the supplied project operation context. */
    fun loadNamespacesAsync(
        operationContext: NacosOperationContext? = null
    ): Deferred<Result<List<NamespaceInfo>>> = serviceScope.async {
        try {
            logger.debug("Loading namespaces from Nacos server")
            val context = operationContext ?: return@async Result.failure(
                ConfigurationRequired(
                    listOf("Project session connection configuration is incomplete")
                )
            )
            val result = apiService?.getNamespaces(context)
                ?: Result.failure(IllegalStateException("NacosApiService not available"))
            result.onSuccess { namespaces ->
                logger.info("Successfully loaded ${namespaces.size} namespaces")
            }.onFailure { error ->
                logger.warn("Failed to load namespaces: ${error.message}")
            }
            result
        } catch (error: Exception) {
            logger.error("Error loading namespaces", error)
            Result.failure(error)
        }
    }

    override fun dispose() {
        logger.debug("Disposing NamespaceService")
        serviceScope.cancel()
    }
}
