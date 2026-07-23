package com.nanyin.nacos.search.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.application.ApplicationManager
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
    /** True once profile updates publish revision-pinned credential slots. */
    var credentialSlotsPublished: Boolean = false
    var migratedDefaultProfileId: String = ""
    var migratedDefaultNamespaceId: String = "public"

    // Server configuration (legacy flat fields — mirror of active server)
    var serverUrl: String = "http://localhost:8848"
    var username: String = ""
    var password: String = ""
    var namespace: String = "public"
    
    // Authentication configuration
    var authMode: AuthMode = AuthMode.ANONYMOUS
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
        sanitizeLoadedProfiles()
        // Load passwords from PasswordSafe, migrating any legacy plaintext that
        // was read from the XML into the credential store on first run.
        loadAndMigrateCredentials()
        migrateLegacyProfiles()
        // Keep flat fields in sync with active server
        syncFromActiveServer()
    }

    /**
     * XmlSerializer Instantiator builds [EnvironmentProfile] with defaults then
     * applies setters. Drop blank-id rows (corrupt / pre-migration debris) and
     * backfill credential slots that were omitted from older XML.
     */
    private fun sanitizeLoadedProfiles() {
        if (profiles.isEmpty()) return
        val repaired = profiles.mapNotNull { profile ->
            if (profile.id.isBlank()) return@mapNotNull null
            if (profile.credentialSlotId.isBlank()) {
                profile.copy(credentialSlotId = "${profile.id}:v1")
            } else {
                profile
            }
        }.toMutableList()
        if (repaired.size != profiles.size || repaired.zip(profiles).any { it.first !== it.second }) {
            profiles = repaired
            // Force migration from servers when every profile row was unusable.
            if (profiles.isEmpty()) {
                profileMigrationCompleted = false
            }
        }
    }

    /**
     * Populates each server's in-memory password from [NacosCredentialStore].
     * When no stored credential exists but the legacy XML still carried a
     * plaintext password, it is migrated into the credential store once.
     *
     * Also backfills revision-pinned profile slots (`id:v1`) from the legacy
     * server-id keys when those slots are missing — otherwise a settings reload
     * that skipped [migrateLegacyProfiles] leaves search with an empty secret.
     */
    private fun loadAndMigrateCredentials() {
        for (server in servers) {
            val stored = NacosCredentialStore.get(server.id)
            when {
                !stored.isNullOrEmpty() -> server.password = stored
                server.password.isNotEmpty() -> NacosCredentialStore.set(server.id, server.password)
            }
        }
        for (profile in profiles) {
            if (profile.id.isBlank()) continue
            val slotId = profile.credentialSlotId.ifBlank { "${profile.id}:v1" }
            if (slotId != profile.credentialSlotId) {
                profile.credentialSlotId = slotId
            }
            if (!NacosCredentialStore.get(slotId).isNullOrEmpty()) continue
            val legacy = servers.find { it.id == profile.id }?.password
                ?: NacosCredentialStore.get(profile.id)
                ?: ""
            if (legacy.isNotEmpty()) {
                NacosCredentialStore.set(slotId, legacy)
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
        val currentIds = newServers.map { it.id }.toSet()
        // Entomb profiles that are about to disappear before publishing the new
        // set, so late in-flight responses for the deleted identity cannot
        // resurrect their cache, token, or session state (design §19.2).
        (previousIds - currentIds).forEach { entombDeletedProfile(it) }
        servers = newServers.map { it.copy() }.toMutableList()
        activeServerId = newActiveId
        syncFromActiveServer()
        persistCredentials(previousIds)
        updateProfilesFromServers(previousPasswords)
        // Profile / credential / policy changes invalidate in-flight browse results.
        com.nanyin.nacos.search.services.ProjectSessionEpochs.bumpAllOpenProjects()
    }

    /** Persists a deletion tombstone for a profile removed from the settings. */
    private fun entombDeletedProfile(profileId: String) {
        try {
            val app = ApplicationManager.getApplication() ?: return
            // Only the live application settings component may write the shared
            // tombstone registry. Throwaway NacosSettings() copies used in tests
            // must not poison CacheService for the rest of the suite.
            if (app.getService(NacosSettings::class.java) !== this) return
            app.getService(com.nanyin.nacos.search.services.ProfileTombstoneRegistry::class.java)
                ?.entomb(profileId, 0)
            app.getService(com.nanyin.nacos.search.services.LastKnownGenerationStore::class.java)
                ?.clearProfile(profileId)
        } catch (e: Exception) {
            // Settings can be applied outside a fully initialised application
            // (e.g. tests); the tombstone is best-effort within a live IDE.
        }
    }

    /** Returns an immutable profile-migration result for a newly opened project. */
    fun migrationDefaults(): LegacyMigrationResult {
        migrateLegacyProfiles()
        ensureProfilesForServers()
        val defaultId = resolveDefaultProfileId()
        if (migratedDefaultProfileId != defaultId) {
            migratedDefaultProfileId = defaultId
        }
        if (migratedDefaultNamespaceId.isBlank()) {
            migratedDefaultNamespaceId = "public"
        }
        return LegacyMigrationResult(profiles.toList(), defaultId, migratedDefaultNamespaceId)
    }

    /**
     * Prefer the persisted migration default, then the active server, then the
     * first live profile. Never returns a stale id that is absent from [profiles].
     */
    fun resolveDefaultProfileId(): String {
        migrateLegacyProfiles()
        ensureProfilesForServers()
        return migratedDefaultProfileId.takeIf { id -> profiles.any { it.id == id } }
            ?: activeServerId.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
            ?: activeServerId
    }

    fun getProfile(profileId: String): EnvironmentProfile? {
        migrateLegacyProfiles()
        ensureProfilesForServers()
        return profiles.firstOrNull { it.id == profileId }
    }

    fun getActiveProfile(): EnvironmentProfile? = getProfile(resolveDefaultProfileId())

    /** Captures a complete immutable context before cache reads or network I/O. */
    fun captureOperationContext(profileId: String? = null): Result<NacosOperationContext> {
        migrateLegacyProfiles()
        ensureProfilesForServers()
        val requested = profileId?.trim().takeUnless { it.isNullOrBlank() }
        // Explicit project selections must fail closed when the profile is
        // missing — never silently retarget to the app-wide default (Local).
        // That made History/Diff/publish hit the wrong server while the tool
        // window still showed QA. Null profileId keeps legacy default capture.
        val selectedProfileId = when {
            requested != null -> getProfile(requested)?.id
                ?: return Result.failure(ConfigurationRequired(listOf("Select a Nacos environment profile")))
            else -> resolveDefaultProfileId()
        }
        val configuredProfile = getProfile(selectedProfileId)
        val persistedProfile = configuredProfile ?: if (requested == null) {
            // Compatibility boundary for callers that still use the historic
            // app-wide active server. Project-selected operations never take
            // this fallback once a profile id was requested.
            EnvironmentProfile.fromLegacy(getActiveServer())
        } else {
            return Result.failure(ConfigurationRequired(listOf("Select a Nacos environment profile")))
        }
        if (requested != null) {
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
        if (!credentialSlotsPublished) {
            // Compatibility captures remain available while legacy flat fields
            // are first being migrated. Each writes the active slot before resolving
            // it, so no request can combine a new endpoint/principal with an
            // older or missing profile credential. Flat mirrors are intentional
            // here: legacy callers/tests update them without syncing servers.
            val legacyProfile = persistedProfile.withUpdated(
                canonicalEndpoint = com.nanyin.nacos.search.models.CanonicalNacosEndpoint.parse(serverUrl)
                    .getOrNull()?.value.orEmpty(),
                authMode = authMode,
                principal = username.trim()
            )
            PasswordSafeCredentialSlots.put(legacyProfile.credentialSlotId, password)
            return OperationContextResolver.resolve(
                legacyProfile,
                PasswordSafeCredentialSlots[legacyProfile.credentialSlotId]
            )
        }
        // A profile is published with a revision-pinned credential slot. Once
        // present, that slot is the sole secret source; falling back to flat
        // fields or a predecessor slot could pair a new identity with an old
        // credential after a partial update.
        return OperationContextResolver.resolve(
            persistedProfile,
            NacosCredentialStore.get(persistedProfile.credentialSlotId)
        )
    }

    private fun migrateLegacyProfiles() {
        if (profileMigrationCompleted && profiles.isNotEmpty()) return
        val result = LegacyProfileMigrator().migrate(servers, activeServerId, namespace)
        profiles = result.profiles.toMutableList()
        migratedDefaultProfileId = result.defaultProfileId
        migratedDefaultNamespaceId = result.defaultNamespaceId
        profileMigrationCompleted = true
    }

    /**
     * Adds any missing profile rows for current [servers]. Additive only: never
     * deletes orphans and never restages an existing credential slot (published
     * slots must fail closed when empty — see AccessSafetyTest).
     */
    private fun ensureProfilesForServers() {
        if (servers.isEmpty()) return
        val existingIds = profiles.map { it.id }.toHashSet()
        var added = false
        for (server in servers) {
            val id = server.id.ifBlank { "default" }
            if (!existingIds.add(id)) continue
            val created = EnvironmentProfile.fromLegacy(server.copy(id = id))
            val secret = server.password.ifEmpty { NacosCredentialStore.get(id).orEmpty() }
            if (secret.isNotEmpty()) {
                NacosCredentialStore.set(created.credentialSlotId, secret)
            }
            profiles.add(created)
            added = true
        }
        if (added) {
            profileMigrationCompleted = true
            if (migratedDefaultProfileId.isBlank() || profiles.none { it.id == migratedDefaultProfileId }) {
                migratedDefaultProfileId = activeServerId.ifBlank { profiles.firstOrNull()?.id.orEmpty() }
            }
        }
    }

    private fun updateProfilesFromServers(previousPasswords: Map<String, String> = emptyMap()) {
        val existing = profiles.associateBy { it.id }
        val credentialSlots = CredentialSlotStager(PasswordSafeCredentialSlots)
        profiles = servers.map { server ->
            val migrated = EnvironmentProfile.fromLegacy(server)
            val previous = existing[server.id]
            val credentialChanged = previous != null &&
                (previousPasswords[server.id] ?: NacosCredentialStore.get(previous.credentialSlotId).orEmpty()) != server.password
            val credentialVersion = if (credentialChanged) previous!!.credentialSlotVersion + 1 else previous?.credentialSlotVersion ?: 1
            val credentialSlotId = credentialSlotId(server.id, credentialVersion)
            val updated = previous?.withUpdated(
                canonicalEndpoint = migrated.canonicalEndpoint,
                apiPolicy = migrated.apiPolicy,
                authMode = migrated.authMode,
                principal = migrated.principal,
                writeIntent = server.writeIntent,
                credentialSlotId = credentialSlotId,
                credentialSlotVersion = credentialVersion
            ) ?: migrated.copy(
                credentialSlotId = credentialSlotId,
                credentialSlotVersion = credentialVersion,
                writeIntent = server.writeIntent
            )
            // The fresh slot is durable before this map is assigned to
            // [profiles], which is the single publication point for readers.
            credentialSlots.stage(updated, server.password)
            updated.copy(displayName = server.displayName)
        }.toMutableList()
        migratedDefaultProfileId = activeServerId
        migratedDefaultNamespaceId = namespace.ifBlank { "public" }
        profileMigrationCompleted = true
        credentialSlotsPublished = true
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
        authMode = AuthMode.ANONYMOUS
        
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
        credentialSlotsPublished = false
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
    HYBRID,     // 优先Token Auth，回退到Basic Auth
    ANONYMOUS,  // Never sends credentials; only supported by a locked read path
    NACOS_PASSWORD,
    HTTP_BASIC,
    BEARER_TOKEN
}
