package com.nanyin.nacos.search.models

import com.nanyin.nacos.search.settings.AuthMode

/**
 * Legacy persisted server row retained only as a versioned migration input.
 * Settings and runtime code use environment profiles, preferences, and intents.
 */
data class NacosServerConfig(
    /** Stable id carried by the previously shipped server-list schema. */
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
    /**
     * Legacy preference migration input. Runtime consumers read
     * [EnvironmentPreferences] by profile id (issue #101 / ADR-0042).
     */
    var allowCrossNamespaceNavigation: Boolean = false,
    /**
     * Legacy preference migration input for navigation-detail prefetch.
     * Runtime source of truth is [EnvironmentPreferences] (ADR-0042).
     */
    var navigationDetailPrefetchEnabled: Boolean = true,
    /** Explicit publish intent; defaults false for new/migrated environments. */
    var writeIntent: Boolean = false
)
