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
    /** Explicit protocol choice persisted with the profile-compatible server draft. */
    var apiPolicy: NacosApiPolicy = NacosApiPolicy.AUTO,
    /**
     * XmlSerializer deserialization default is ANONYMOUS so omitted `authMode` in
     * existing `nacos-search.xml` keeps meaning ANONYMOUS (skip-defaults). New /
     * seed / reset construction paths set [AuthMode.NACOS_PASSWORD] explicitly
     * (ADR 0040) — do not flip this field default to product default.
     */
    var authMode: AuthMode = AuthMode.ANONYMOUS,
    var defaultGroup: String = "DEFAULT_GROUP",
    var connectionTimeoutMs: Int = 30000,
    var autoRefreshOnOpen: Boolean = true,
    /**
     * Dual-write preference field for the settings dialog. Runtime consumers
     * must read [EnvironmentPreferences] by profile id (issue #101 / ADR-0042),
     * not this field. [com.nanyin.nacos.search.settings.NacosSettings.applyServers]
     * publishes this value into the preference record.
     */
    var allowCrossNamespaceNavigation: Boolean = false,
    /**
     * Dual-write preference field for the settings dialog. When true, the
     * navigation detail prefetch loads configuration bodies for the project's
     * declared configuration sources so gutter markers resolve without browsing.
     * Preference-only — must not live on [EnvironmentProfile] (ADR-0042).
     * Runtime source of truth is [EnvironmentPreferences].
     */
    var navigationDetailPrefetchEnabled: Boolean = true,
    /** Explicit publish intent; defaults false for new/migrated environments. */
    var writeIntent: Boolean = false
) {
    fun copyConfig(): NacosServerConfig {
        return NacosServerConfig(
            id = if (id.isNotEmpty()) "${id}_copy_${System.currentTimeMillis()}" else "",
            displayName = "$displayName (copy)",
            serverUrl = serverUrl,
            username = username,
            password = password,
            namespace = namespace,
            apiPolicy = apiPolicy,
            authMode = authMode,
            defaultGroup = defaultGroup,
            connectionTimeoutMs = connectionTimeoutMs,
            autoRefreshOnOpen = autoRefreshOnOpen,
            allowCrossNamespaceNavigation = allowCrossNamespaceNavigation,
            navigationDetailPrefetchEnabled = navigationDetailPrefetchEnabled,
            writeIntent = writeIntent
        )
    }

    fun isValidUrl(): Boolean {
        return CanonicalNacosEndpoint.parse(serverUrl).isSuccess
    }

    companion object {
        /** Product default for a newly added environment (not the XML deserialize default). */
        fun createDefault(id: String = generateId()): NacosServerConfig {
            return NacosServerConfig(id = id, authMode = AuthMode.NACOS_PASSWORD)
        }

        fun generateId(): String {
            return "srv_" + System.currentTimeMillis().toString(36) + "_" + (0..9999).random().toString(36)
        }
    }
}
