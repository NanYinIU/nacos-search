package com.nanyin.nacos.search.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosServerConfig

/**
 * Persistent settings for Nacos plugin
 */
@Service(Service.Level.APP)
@State(
    name = "NacosSettings",
    storages = [Storage("nacos-search.xml")]
)
class NacosSettings : PersistentStateComponent<NacosSettings> {

    // ---- Multi-server (master-detail) model ----
    // List of all configured Nacos server environments. The active one is
    // determined by `activeServerId`; the flat legacy fields below always
    // mirror the active server for backward compatibility with services.
    var servers: MutableList<NacosServerConfig> = mutableListOf(
        NacosServerConfig(
            id = "s_local",
            displayName = "本地 Local",
            serverUrl = "http://localhost:8848"
        )
    )
    var activeServerId: String = "s_local"

    // ---- Profile migration model ----
    // Profiles are application-wide and reusable by every project. Selection and
    // namespace are intentionally kept in NacosProjectSession instead.
    var profiles: MutableList<EnvironmentProfile> = mutableListOf()
    var profileMigrationCompleted: Boolean = false
    var migratedDefaultProfileId: String = ""
    var migratedDefaultNamespaceId: String = "public"

    // Server configuration (legacy flat fields — mirror of active server)
    var serverUrl: String = "http://localhost:8848"
    var username: String = ""
    var password: String = ""
    var namespace: String = "public"
    
    // Authentication configuration
    var authMode: AuthMode = AuthMode.TOKEN
    var enableTokenAuth: Boolean = true
    var tokenCacheDurationMinutes: Int = 30
    var autoTokenRefresh: Boolean = true
    
    // Cache configuration
    var cacheEnabled: Boolean = true
    var cacheTtlMinutes: Int = 5
    var maxCacheSize: Int = 1000
    // Legacy fields retained for backward-compatible deserialization of old
    // settings files. They are never read at runtime — periodic refresh was
    // removed; users trigger refreshes manually or via namespace-switch preheat.
    var autoRefreshEnabled: Boolean = false
    var autoRefreshIntervalMinutes: Int = 10
    
    // Aliases for test compatibility
    var enableCache: Boolean
        get() = cacheEnabled
        set(value) { cacheEnabled = value }
    
    var cacheTtlSeconds: Int
        get() = cacheTtlMinutes * 60
        set(value) { cacheTtlMinutes = value / 60 }
    
    var autoRefreshCache: Boolean
        get() = autoRefreshEnabled
        set(value) { autoRefreshEnabled = value }
    
    var autoRefreshIntervalSeconds: Int
        get() = autoRefreshIntervalMinutes * 60
        set(value) { autoRefreshIntervalMinutes = value / 60 }
    
    // Search configuration
    var searchResultLimit: Int = 100
    var enableRegexSearch: Boolean = true
    var caseSensitiveSearch: Boolean = false
    var highlightMatches: Boolean = true
    
    // UI configuration
    var showToolWindow: Boolean = true
    var toolWindowLocation: String = "right"
    var rememberLastSearch: Boolean = true
    var lastSearchQuery: String = ""
    var lastGroupFilter: String = ""
    var lastTenantFilter: String = ""
    
    // Language configuration
    var language: String = "en"
    
    // Connection configuration
    var connectionTimeoutSeconds: Int = 30
    var readTimeoutSeconds: Int = 60
    var retryAttempts: Int = 3
    var retryDelaySeconds: Int = 2
    
    override fun getState(): NacosSettings {
        // Never persist passwords as plaintext in the component-state XML.
        // The serializer omits String properties equal to their default (""),
        // so blanking them on the persisted snapshot drops the attribute
        // entirely (and silences the platform's sensitive-information error).
        // Real secrets live in PasswordSafe via NacosCredentialStore.
        val sanitized = NacosSettings()
        XmlSerializerUtil.copyBean(this, sanitized)
        sanitized.password = ""
        sanitized.servers = servers.map { it.copy(password = "") }.toMutableList()
        return sanitized
    }
    
    override fun loadState(state: NacosSettings) {
        XmlSerializerUtil.copyBean(state, this)
        // Ensure servers list is initialized for older persisted states
        if (servers.isEmpty()) {
            servers.add(NacosServerConfig(
                id = "default",
                displayName = "Local",
                serverUrl = serverUrl,
                username = username,
                password = password,
                namespace = namespace,
                authMode = authMode
            ))
            activeServerId = "default"
        }
        // Load passwords from PasswordSafe, migrating any legacy plaintext that
        // was read from the XML into the credential store on first run.
        loadAndMigrateCredentials()
        migrateLegacyProfiles()
        // Keep flat fields in sync with active server
        syncFromActiveServer()
    }

    /**
     * Populates each server's in-memory password from [NacosCredentialStore].
     * When no stored credential exists but the legacy XML still carried a
     * plaintext password, it is migrated into the credential store once.
     */
    private fun loadAndMigrateCredentials() {
        for (server in servers) {
            val stored = NacosCredentialStore.get(server.id)
            when {
                !stored.isNullOrEmpty() -> server.password = stored
                server.password.isNotEmpty() -> NacosCredentialStore.set(server.id, server.password)
            }
        }
    }
    
    // ---- Multi-server helpers ----

    /**
     * Returns the currently active server config, or the first server if
     * activeServerId doesn't match any entry.
     */
    fun getActiveServer(): NacosServerConfig {
        return servers.find { it.id == activeServerId } ?: servers.firstOrNull()
            ?: NacosServerConfig(id = "default", displayName = "Local")
    }

    /**
     * Synchronizes the flat legacy fields from the active server entry.
     */
    fun syncFromActiveServer() {
        val active = getActiveServer()
        serverUrl = active.serverUrl
        username = active.username
        password = active.password
        namespace = active.namespace
        authMode = active.authMode
        connectionTimeoutSeconds = (active.connectionTimeoutMs / 1000).coerceAtLeast(1)
        // autoRefreshEnabled intentionally not synced — legacy field only
    }

    /**
     * Pushes the current flat field values back into the active server entry.
     */
    fun syncToActiveServer() {
        val active = getActiveServer()
        active.serverUrl = serverUrl
        active.username = username
        active.password = password
        active.namespace = namespace
        active.authMode = authMode
        active.connectionTimeoutMs = getConnectionTimeoutMillis()
        // autoRefreshOnOpen intentionally not written from legacy field
    }

    fun setActiveServer(serverId: String) {
        if (servers.any { it.id == serverId }) {
            activeServerId = serverId
            syncFromActiveServer()
        }
    }

    fun addServer(config: NacosServerConfig, makeActive: Boolean = false) {
        if (config.id.isEmpty()) {
            config.id = NacosServerConfig.generateId()
        }
        servers.add(config)
        if (makeActive) {
            activeServerId = config.id
            syncFromActiveServer()
        }
    }

    fun removeServer(serverId: String): Boolean {
        if (servers.size <= 1) return false
        val removed = servers.removeIf { it.id == serverId }
        if (removed && activeServerId == serverId) {
            activeServerId = servers.firstOrNull()?.id ?: ""
            syncFromActiveServer()
        }
        return removed
    }

    fun updateServer(serverId: String, config: NacosServerConfig) {
        val idx = servers.indexOfFirst { it.id == serverId }
        if (idx >= 0) {
            config.id = serverId
            servers[idx] = config
            if (serverId == activeServerId) {
                syncFromActiveServer()
            }
        }
    }

    fun cloneServers(): MutableList<NacosServerConfig> {
        return servers.map { it.copy() }.toMutableList()
    }

    fun applyServers(newServers: List<NacosServerConfig>, newActiveId: String) {
        val previousIds = servers.map { it.id }.toSet()
        val previousPasswords = servers.associate { it.id to it.password }
        servers = newServers.map { it.copy() }.toMutableList()
        activeServerId = newActiveId
        syncFromActiveServer()
        persistCredentials(previousIds)
        updateProfilesFromServers(previousPasswords)
    }

    /** Returns an immutable profile-migration result for a newly opened project. */
    fun migrationDefaults(): LegacyMigrationResult {
        migrateLegacyProfiles()
        return LegacyMigrationResult(profiles.toList(), migratedDefaultProfileId, migratedDefaultNamespaceId)
    }

    fun getProfile(profileId: String): EnvironmentProfile? {
        migrateLegacyProfiles()
        return profiles.firstOrNull { it.id == profileId }
    }

    fun getActiveProfile(): EnvironmentProfile? = getProfile(activeServerId)

    /** Captures a complete immutable context before cache reads or network I/O. */
    fun captureOperationContext(profileId: String? = null): Result<NacosOperationContext> {
        migrateLegacyProfiles()
        val selectedProfileId = profileId?.trim().takeUnless { it.isNullOrBlank() } ?: activeServerId
        val configuredProfile = getProfile(selectedProfileId)
        val persistedProfile = configuredProfile ?: if (profileId == null) {
            // Compatibility boundary for callers that still use the historic
            // app-wide active server. Project-selected operations never take
            // this fallback.
            EnvironmentProfile.fromLegacy(getActiveServer())
        } else {
            return Result.failure(ConfigurationRequired(listOf("Select a Nacos environment profile")))
        }
        if (profileId != null) {
            // Project-owned selections must be resolved exclusively from the
            // global profile and its versioned credential slot. They must not
            // inherit the mutable app-wide legacy active-server fields.
            return OperationContextResolver.resolve(
                persistedProfile,
                NacosCredentialStore.get(persistedProfile.credentialSlotId)
            )
        }
        if (configuredProfile == null) {
            // An unmigrated legacy active server has no corresponding profile
            // yet, so its server entry (not unrelated flat fields) is the only
            // deterministic source for this one compatibility operation.
            return OperationContextResolver.resolve(persistedProfile, getActiveServer().password)
        }
        // Flat fields remain a compatibility bridge for callers that predate
        // profiles. Capture their values into the immutable operation context,
        // never by mutating the profile while an operation is in flight.
        val profile = persistedProfile.withUpdated(
            canonicalEndpoint = com.nanyin.nacos.search.models.CanonicalNacosEndpoint.parse(serverUrl)
                .getOrNull()?.value.orEmpty(),
            authMode = authMode,
            principal = username.trim()
        )
        val secret = password.ifEmpty {
            NacosCredentialStore.get(profile.credentialSlotId).orEmpty().ifEmpty { getActiveServer().password }
        }
        return OperationContextResolver.resolve(profile, secret)
    }

    private fun migrateLegacyProfiles() {
        if (profileMigrationCompleted && profiles.isNotEmpty()) return
        val result = LegacyProfileMigrator().migrate(servers, activeServerId, namespace)
        profiles = result.profiles.toMutableList()
        migratedDefaultProfileId = result.defaultProfileId
        migratedDefaultNamespaceId = result.defaultNamespaceId
        profileMigrationCompleted = true
    }

    private fun updateProfilesFromServers(previousPasswords: Map<String, String> = emptyMap()) {
        val existing = profiles.associateBy { it.id }
        profiles = servers.map { server ->
            val migrated = EnvironmentProfile.fromLegacy(server)
            val previous = existing[server.id]
            val credentialChanged = previous != null &&
                (previousPasswords[server.id] ?: NacosCredentialStore.get(previous.credentialSlotId).orEmpty()) != server.password
            val credentialVersion = if (credentialChanged) previous!!.credentialSlotVersion + 1 else previous?.credentialSlotVersion ?: 1
            val credentialSlotId = "${server.id}:v$credentialVersion"
            val updated = previous?.withUpdated(
                canonicalEndpoint = migrated.canonicalEndpoint,
                authMode = migrated.authMode,
                principal = migrated.principal,
                credentialSlotId = credentialSlotId,
                credentialSlotVersion = credentialVersion
            ) ?: migrated.copy(credentialSlotId = credentialSlotId, credentialSlotVersion = credentialVersion)
            NacosCredentialStore.set(updated.credentialSlotId, server.password)
            if (credentialChanged) NacosCredentialStore.remove(previous!!.credentialSlotId)
            updated.copy(displayName = server.displayName)
        }.toMutableList()
        migratedDefaultProfileId = activeServerId
        migratedDefaultNamespaceId = namespace.ifBlank { "public" }
        profileMigrationCompleted = true
    }

    /**
     * Writes each server's password to [NacosCredentialStore] and removes
     * credentials for servers that no longer exist.
     */
    private fun persistCredentials(previousIds: Set<String>) {
        val currentIds = servers.map { it.id }.toSet()
        (previousIds - currentIds).forEach { NacosCredentialStore.remove(it) }
        servers.forEach { NacosCredentialStore.set(it.id, it.password) }
    }

    /**
     * Validates the current settings
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (serverUrl.isBlank()) {
            errors.add("Server URL cannot be empty")
        } else if (!isValidServerUrl(serverUrl)) {
            errors.add("Invalid server URL format")
        }
        
        if (cacheEnabled) {
            if (cacheTtlMinutes < 1) {
                errors.add("Cache TTL must be at least 1 minute")
            }
            
            if (maxCacheSize < 1) {
                errors.add("Max cache size must be at least 1")
            }
        }
        
        if (searchResultLimit < 1) {
            errors.add("Search result limit must be at least 1")
        }
        
        if (connectionTimeoutSeconds < 1) {
            errors.add("Connection timeout must be at least 1 second")
        }
        
        if (readTimeoutSeconds < 1) {
            errors.add("Read timeout must be at least 1 second")
        }
        
        if (retryAttempts < 0) {
            errors.add("Retry attempts cannot be negative")
        }
        
        if (retryDelaySeconds < 0) {
            errors.add("Retry delay cannot be negative")
        }
        
        return errors
    }
    
    /**
     * Checks if the current settings are valid
     */
    fun isValid(): Boolean {
        return validate().isEmpty()
    }
    
    /**
     * Validates a server URL
     */
    fun isValidServerUrl(url: String): Boolean {
        return com.nanyin.nacos.search.models.CanonicalNacosEndpoint.parse(url).isSuccess
    }
    
    /**
     * Resets settings to default values
     */
    fun resetToDefaults() {
        serverUrl = "http://localhost:8848"
        username = ""
        password = ""
        namespace = "public"
        
        cacheEnabled = true
        cacheTtlMinutes = 5
        maxCacheSize = 1000
        autoRefreshEnabled = false
        autoRefreshIntervalMinutes = 10
        
        searchResultLimit = 100
        enableRegexSearch = true
        caseSensitiveSearch = false
        highlightMatches = true
        
        showToolWindow = true
        toolWindowLocation = "right"
        rememberLastSearch = true
        lastSearchQuery = ""
        lastGroupFilter = ""
        lastTenantFilter = ""
        
        language = "en"
        
        connectionTimeoutSeconds = 30
        readTimeoutSeconds = 60
        retryAttempts = 3
        retryDelaySeconds = 2

        // Reset multi-server model
        servers = mutableListOf(
            NacosServerConfig(
                id = "s_local",
                displayName = "本地 Local",
                serverUrl = "http://localhost:8848"
            )
        )
        activeServerId = "s_local"
        profiles = mutableListOf()
        profileMigrationCompleted = false
        migratedDefaultProfileId = ""
        migratedDefaultNamespaceId = "public"
    }
    
    /**
     * Creates a copy of current settings
     */
    fun copy(): NacosSettings {
        val copy = NacosSettings()
        XmlSerializerUtil.copyBean(this, copy)
        return copy
    }
    
    /**
     * Copies settings from another NacosSettings instance
     */
    fun copyFrom(other: NacosSettings) {
        XmlSerializerUtil.copyBean(other, this)
    }
    
    /**
     * Checks if authentication is configured
     */
    fun hasAuthentication(): Boolean {
        return username.isNotBlank() && password.isNotBlank()
    }
    
    /**
     * 检查是否配置了token认证
     */
    fun hasTokenAuthentication(): Boolean {
        return enableTokenAuth && username.isNotBlank() && password.isNotBlank()
    }
    
    /**
     * 获取token缓存时间（毫秒）
     */
    fun getTokenCacheDurationMillis(): Long {
        return tokenCacheDurationMinutes * 60 * 1000L
    }
    
    /**
     * Gets cache TTL in milliseconds
     */
    fun getCacheTtlMillis(): Long {
        return cacheTtlMinutes * 60 * 1000L
    }
    
    /**
     * Gets auto refresh interval in milliseconds
     */
    fun getAutoRefreshIntervalMillis(): Long {
        return autoRefreshIntervalMinutes * 60 * 1000L
    }
    
    /**
     * Gets connection timeout in milliseconds
     */
    fun getConnectionTimeoutMillis(): Int {
        return connectionTimeoutSeconds * 1000
    }
    
    /**
     * Gets read timeout in milliseconds
     */
    fun getReadTimeoutMillis(): Int {
        return readTimeoutSeconds * 1000
    }
    
    /**
     * Gets retry delay in milliseconds
     */
    fun getRetryDelayMillis(): Long {
        return retryDelaySeconds * 1000L
    }
    
    /**
     * Updates last search parameters
     */
    fun updateLastSearch(query: String, groupFilter: String = "", tenantFilter: String = "") {
        if (rememberLastSearch) {
            lastSearchQuery = query
            lastGroupFilter = groupFilter
            lastTenantFilter = tenantFilter
        }
    }
    
    /**
     * Clears last search parameters
     */
    fun clearLastSearch() {
        lastSearchQuery = ""
        lastGroupFilter = ""
        lastTenantFilter = ""
    }
    
    override fun toString(): String {
        return "NacosSettings(" +
                "serverUrl='$serverUrl', " +
                "username='${if (username.isNotBlank()) "***" else ""}', " +
                "namespace='$namespace', " +
                "authMode=$authMode, " +
                "enableTokenAuth=$enableTokenAuth, " +
                "tokenCacheDurationMinutes=$tokenCacheDurationMinutes, " +
                "autoTokenRefresh=$autoTokenRefresh, " +
                "cacheEnabled=$cacheEnabled, " +
                "cacheTtlMinutes=$cacheTtlMinutes, " +
                "maxCacheSize=$maxCacheSize" +
                ")"
    }
}

/**
 * 认证模式枚举
 */
enum class AuthMode {
    BASIC,      // 仅使用Basic Auth
    TOKEN,      // 仅使用Token Auth
    HYBRID      // 优先Token Auth，回退到Basic Auth
}
