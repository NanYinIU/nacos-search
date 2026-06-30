package com.nanyin.nacos.search.models

import com.nanyin.nacos.search.settings.AuthMode

/**
 * Represents a single Nacos server (environment) entry in the multi-server
 * master-detail settings model. Each entry maps to one connection target.
 */
data class NacosServerConfig(
    /** Stable unique id used for active-server tracking and draft diffing. */
    var id: String = "",
    /** Display name shown in the environment list (e.g. "Local", "Dev", "Prod"). */
    var displayName: String = "",
    var serverUrl: String = "http://localhost:8848",
    var username: String = "",
    var password: String = "",
    var namespace: String = "public",
    var authMode: AuthMode = AuthMode.TOKEN,
    var defaultGroup: String = "DEFAULT_GROUP",
    var connectionTimeoutMs: Int = 30000,
    var autoRefreshOnOpen: Boolean = true,
    var allowCrossNamespaceNavigation: Boolean = false
) {
    fun copyConfig(): NacosServerConfig {
        return NacosServerConfig(
            id = if (id.isNotEmpty()) "${id}_copy_${System.currentTimeMillis()}" else "",
            displayName = "$displayName (copy)",
            serverUrl = serverUrl,
            username = username,
            password = password,
            namespace = namespace,
            authMode = authMode,
            defaultGroup = defaultGroup,
            connectionTimeoutMs = connectionTimeoutMs,
            autoRefreshOnOpen = autoRefreshOnOpen,
            allowCrossNamespaceNavigation = allowCrossNamespaceNavigation
        )
    }

    fun isValidUrl(): Boolean {
        if (serverUrl.isBlank()) return false
        return try {
            val url = java.net.URL(serverUrl)
            url.protocol in listOf("http", "https")
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun createDefault(id: String = generateId()): NacosServerConfig {
            return NacosServerConfig(id = id)
        }

        fun generateId(): String {
            return "srv_" + System.currentTimeMillis().toString(36) + "_" + (0..9999).random().toString(36)
        }
    }
}
