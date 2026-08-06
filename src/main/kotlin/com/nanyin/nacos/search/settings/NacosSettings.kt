package com.nanyin.nacos.search.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.ProfileIntent

/**
 * Persistent settings for the Nacos plugin.
 *
 * Environment profiles are the sole runtime environment model (ADR-0049).
 * Legacy [servers] and flat active-server fields are deserialization inputs:
 * accepted only during versioned migration on [loadState], never consulted by
 * profile/default/identity reads after migration (issue #104).
 */
@Service(Service.Level.APP)
@State(
    name = "NacosSettings",
    storages = [Storage("nacos-search.xml")]
)
class NacosSettings : PersistentStateComponent<NacosSettings> {

    // ---- Legacy multi-server surface (deserialization + settings dual-write) ----
    // Runtime profile/default/identity paths must not read this list after
    // migration. Apply dual-writes it so the settings dialog can still edit
    // drafts until it holds intents alone (#106).
    var servers: MutableList<NacosServerConfig> = mutableListOf(
        NacosServerConfig(
            id = "s_local",
            displayName = "本地 Local",
            serverUrl = "http://localhost:8848"
        )
    )
    var activeServerId: String = "s_local"

    // ---- Environment profiles (sole runtime environment model) ----
    // Profiles are application-wide and reusable by every project. Selection and
    // namespace live in NacosProjectSession.
    var profiles: MutableList<EnvironmentProfile> = mutableListOf()

    /**
     * Persisted settings schema version. Missing attribute in old XML deserializes
     * as 0 and triggers [SettingsMigrator] once on load. A shared boolean no longer
     * decides whether migration runs (issue #104).
     */
    var settingsSchemaVersion: Int = 0

    /**
     * Legacy boolean flags retained only so older XML deserializes. They are not
     * the migration gate — [settingsSchemaVersion] is. Written true after a
     * successful CURRENT migration for one-release compatibility with older builds
     * that still read them.
     */
    var profileMigrationCompleted: Boolean = false
    var credentialSlotsPublished: Boolean = false

    /** Seed-only default profile id: written by migration (and Apply when blank). */
    var migratedDefaultProfileId: String = ""
    var migratedDefaultNamespaceId: String = "public"

    /**
     * Profile-associated preference-only records (ADR-0042 / issue #101).
     * Keyed by environment profile id; holds no connection target or revisions.
     */
    var environmentPreferences: MutableList<EnvironmentPreferences> = mutableListOf(
        EnvironmentPreferences.defaultsFor("s_local")
    )

    // Flat active-server fields — deserialization inputs only after migration.
    var serverUrl: String = "http://localhost:8848"
    var username: String = ""
    var password: String = ""
    var namespace: String = "public"

    // Authentication configuration (flat legacy mirror)
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

    /**
     * In-memory report from the most recent load-time migration. Not persisted.
     * Empty actions when already at [SettingsSchema.CURRENT].
     */
    @com.intellij.util.xmlb.annotations.Transient
    var lastMigrationReport: SettingsMigrationReport? = null
        internal set

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
        sanitized.environmentPreferences = environmentPreferences
            .map { it.copyPreferences() }
            .toMutableList()
        sanitized.profiles = profiles
            .map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) }
            .toMutableList()
        // Do not persist the in-memory migration report.
        sanitized.lastMigrationReport = null
        return sanitized
    }

    override fun noStateLoaded() {
        // Brand-new install: no XML. Seed CURRENT schema without treating the
        // factory default as a legacy upgrade (no upgrade summary, no credential
        // staging beyond empty ANONYMOUS slots).
        installFactoryDefaults()
    }

    override fun loadState(state: NacosSettings) {
        XmlSerializerUtil.copyBean(state, this)
        lastMigrationReport = null

        // Older single-server XML may only have flat fields.
        if (servers.isEmpty() && (serverUrl.isNotBlank() || username.isNotBlank() || password.isNotBlank())) {
            servers.add(
                NacosServerConfig(
                    id = activeServerId.ifBlank { "default" },
                    displayName = "Local",
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    namespace = namespace,
                    authMode = authMode
                )
            )
            if (activeServerId.isBlank()) activeServerId = servers.first().id
        }

        // Hydrate in-memory dual-write secrets from the credential store without
        // writing — migration stages revision-pinned slots itself.
        for (server in servers) {
            val stored = NacosCredentialStore.get(server.id)
            if (!stored.isNullOrEmpty()) {
                server.password = stored
            }
        }

        runVersionedMigration(DefaultCredentialSlotStore) { key -> NacosCredentialStore.get(key) }

        // Dual-write surface for the settings dialog: mirror active profile
        // fields onto the flat legacy attributes without treating them as
        // runtime sources of truth.
        syncFlatFieldsFromProfiles()
    }

    /**
     * Runs [SettingsMigrator] when [settingsSchemaVersion] is below CURRENT.
     * Always adopts the report's profiles/preferences/seed so a second pass over
     * already-migrated state is a pure re-read with zero credential writes.
     */
    internal fun runVersionedMigration(
        credentialSlots: CredentialSlotStore,
        legacySecretByKey: (String) -> String? = { null }
    ): SettingsMigrationReport {
        // Promote pre-schema boolean-era state: completed migration with live
        // profiles is treated as already-current so we preserve ids/revisions
        // without re-deriving from servers.
        val effectiveSchema = when {
            settingsSchemaVersion >= SettingsSchema.CURRENT -> settingsSchemaVersion
            profileMigrationCompleted && profiles.any { it.id.isNotBlank() } -> {
                // Boolean-era upgrade that already published profiles: adopt at
                // CURRENT without a second derivation, still allowing missing
                // slot backfill inside the migrator when schema is bumped once.
                0
            }
            else -> settingsSchemaVersion.coerceAtLeast(0)
        }

        val migrator = SettingsMigrator(credentialSlots, legacySecretByKey)
        val report = migrator.migrate(
            SettingsMigrationInput(
                schemaVersion = effectiveSchema,
                servers = servers.map { it.copy() },
                activeServerId = activeServerId,
                flatNamespace = namespace,
                profiles = profiles.map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) },
                preferences = environmentPreferences.map { it.copyPreferences() },
                defaultProfileId = migratedDefaultProfileId,
                defaultNamespaceId = migratedDefaultNamespaceId
            )
        )

        profiles = report.profiles
            .map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) }
            .toMutableList()
        environmentPreferences = report.preferences
            .map { it.copyPreferences() }
            .toMutableList()
        // Seed is written by migration (or preserved). Do not retarget from
        // whichever project most recently opened Settings.
        if (migratedDefaultProfileId.isBlank() ||
            profiles.none { it.id == migratedDefaultProfileId }
        ) {
            migratedDefaultProfileId = report.defaultProfileId
        }
        if (migratedDefaultNamespaceId.isBlank()) {
            migratedDefaultNamespaceId = report.defaultNamespaceId
        }
        settingsSchemaVersion = report.toSchemaVersion
        profileMigrationCompleted = profiles.isNotEmpty()
        credentialSlotsPublished = profiles.isNotEmpty()
        lastMigrationReport = report

        // Keep dual-write server rows aligned with published profiles for the
        // settings dialog without inventing new profile identities.
        if (servers.isEmpty() && profiles.isNotEmpty()) {
            servers = profiles.map { profile ->
                profileToLegacyServer(
                    profile,
                    report.suggestedNamespaces[profile.id] ?: "public",
                    report.preferences.firstOrNull { it.profileId == profile.id }
                        ?: EnvironmentPreferences.defaultsFor(profile.id)
                )
            }.toMutableList()
        }
        if (activeServerId.isBlank() || profiles.none { it.id == activeServerId }) {
            activeServerId = migratedDefaultProfileId.ifBlank {
                profiles.firstOrNull()?.id.orEmpty()
            }
        }
        return report
    }

    private fun installFactoryDefaults() {
        settingsSchemaVersion = SettingsSchema.CURRENT
        profileMigrationCompleted = true
        credentialSlotsPublished = true
        if (profiles.isEmpty() && servers.isNotEmpty()) {
            profiles = servers.map { EnvironmentProfile.fromLegacy(it) }.toMutableList()
        }
        if (migratedDefaultProfileId.isBlank()) {
            migratedDefaultProfileId = activeServerId.ifBlank { profiles.firstOrNull()?.id.orEmpty() }
        }
        if (migratedDefaultNamespaceId.isBlank()) {
            migratedDefaultNamespaceId = "public"
        }
        if (environmentPreferences.none { it.profileId.isNotBlank() }) {
            environmentPreferences = profiles
                .map { EnvironmentPreferences.defaultsFor(it.id) }
                .ifEmpty { listOf(EnvironmentPreferences.defaultsFor("s_local")) }
                .toMutableList()
        }
        lastMigrationReport = SettingsMigrationReport(
            fromSchemaVersion = SettingsSchema.CURRENT,
            toSchemaVersion = SettingsSchema.CURRENT,
            actions = emptyList(),
            profiles = profiles.toList(),
            preferences = environmentPreferences.map { it.copyPreferences() },
            defaultProfileId = migratedDefaultProfileId,
            defaultNamespaceId = migratedDefaultNamespaceId,
            suggestedNamespaces = profiles.associate { it.id to migratedDefaultNamespaceId },
            credentialSlotWrites = 0
        )
    }

    private fun syncFlatFieldsFromProfiles() {
        // Prefer the dual-write server row when present (settings dialog surface),
        // otherwise mirror the published profile. Never reverse-migrate profiles
        // from flat fields on read.
        val server = servers.firstOrNull { it.id == activeServerId }
            ?: servers.firstOrNull()
        if (server != null) {
            serverUrl = server.serverUrl
            username = server.username
            password = server.password
            namespace = server.namespace.ifBlank { "public" }
            authMode = server.authMode
            connectionTimeoutSeconds = (server.connectionTimeoutMs / 1000).coerceAtLeast(1)
            return
        }
        val profile = profiles.firstOrNull { it.id == activeServerId }
            ?: profiles.firstOrNull { it.id == migratedDefaultProfileId }
            ?: profiles.firstOrNull()
            ?: return
        serverUrl = profile.canonicalEndpoint.ifBlank { serverUrl }
        username = profile.principal
        authMode = profile.authMode
        namespace = migratedDefaultNamespaceId.ifBlank { "public" }
    }
    
    // ---- Multi-server helpers (dual-write / settings dialog surface) ----

    /**
     * Returns the currently active dual-write server row, or synthesizes one
     * from the published profile when the dual-write list is empty.
     * Prefer [getProfile] / [publishedProfiles] for runtime paths.
     */
    fun getActiveServer(): NacosServerConfig {
        servers.find { it.id == activeServerId }?.let { return it }
        servers.firstOrNull()?.let { return it }
        val profile = getProfile(activeServerId) ?: getActiveProfile()
        if (profile != null) {
            val prefs = preferencesFor(profile.id)
            return profileToLegacyServer(profile, migratedDefaultNamespaceId, prefs)
        }
        return NacosServerConfig(id = "default", displayName = "Local")
    }

    /**
     * Synchronizes the flat legacy fields from the active dual-write server entry.
     * Write/settings paths only — not used by pure profile reads.
     */
    fun syncFromActiveServer() {
        val active = getActiveServer()
        serverUrl = active.serverUrl
        username = active.username
        password = active.password
        namespace = active.namespace
        authMode = active.authMode
        connectionTimeoutSeconds = (active.connectionTimeoutMs / 1000).coerceAtLeast(1)
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
    }

    /**
     * Updates the dual-write active selection. Does **not** rewrite the
     * migration seed ([migratedDefaultProfileId]) — that seed is written once
     * at migration so one project's switch cannot retarget another (issue #104).
     */
    fun setActiveServer(serverId: String) {
        val known = servers.any { it.id == serverId } || profiles.any { it.id == serverId }
        if (!known) return
        activeServerId = serverId
        syncFlatFieldsFromProfiles()
        // Keep dual-write password hydrated for the dialog surface.
        servers.find { it.id == serverId }?.let { server ->
            if (server.password.isEmpty()) {
                val profile = profiles.find { it.id == serverId } ?: return@let
                val secret = DefaultCredentialSlotStore
                    .read(profile.id, profile.credentialSlotVersion)
                    .orEmpty()
                if (secret.isNotEmpty()) server.password = secret
            }
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

    /**
     * Draft rows for the settings dialog. Preference records overlay the dual-write
     * list; when dual-write is empty, synthesizes from published profiles.
     */
    fun cloneServers(): MutableList<NacosServerConfig> {
        val source = if (servers.isNotEmpty()) {
            servers
        } else {
            profiles.map { profile ->
                profileToLegacyServer(
                    profile,
                    migratedDefaultNamespaceId,
                    preferencesFor(profile.id)
                )
            }
        }
        return source.map { server ->
            val prefs = preferencesFor(server.id)
            server.copy(
                allowCrossNamespaceNavigation = prefs.allowCrossNamespaceNavigation,
                navigationDetailPrefetchEnabled = prefs.navigationDetailPrefetchEnabled
            )
        }.toMutableList()
    }

    /**
     * Preference-only values for [profileId], never null. Missing records resolve
     * to product defaults without consulting the legacy server list.
     */
    fun preferencesFor(profileId: String): EnvironmentPreferences {
        val id = profileId.trim().ifBlank { activeServerId.ifBlank { "s_local" } }
        environmentPreferences.firstOrNull { it.profileId == id }?.let { return it.copyPreferences() }
        return EnvironmentPreferences.defaultsFor(id)
    }

    /**
     * All persisted preference records as immutable snapshots, keyed by profile id.
     */
    fun allEnvironmentPreferences(): Map<String, EnvironmentPreferences> =
        environmentPreferences
            .filter { it.profileId.isNotBlank() }
            .associate { it.profileId to it.copyPreferences() }

    /**
     * Compatibility entry that converts the legacy server list into
     * [ProfileIntent]s and publishes through [applyProfileIntents]. Prefer the
     * intent API for new call sites; this bridge keeps the settings dialog and
     * existing tests compiling until they edit intents directly (#47 / #106).
     */
    fun applyServers(newServers: List<NacosServerConfig>, newActiveId: String): ProfileStoreWriteOutcome {
        val intents = newServers.map { ProfileIntent.fromServerConfig(it) }
        return applyProfileIntents(intents, newActiveId, dualWriteServers = newServers)
    }

    /**
     * Single write entry that turns profile intents into published environment
     * profiles (ADR-0049 / issue #103). The store owns revision derivation and
     * credential staging; this host applies the outcome, entombs removals, and
     * dual-writes the legacy server list **only for successfully published
     * profiles** so stage failure cannot surface a new secret on the dual-write
     * surface (ADR-0035).
     */
    fun applyProfileIntents(
        intents: List<ProfileIntent>,
        newActiveId: String,
        dualWriteServers: List<NacosServerConfig>? = null,
        credentialSlots: CredentialSlotStore = DefaultCredentialSlotStore
    ): ProfileStoreWriteOutcome {
        // If this instance was never loadState'd (unit tests constructing a bare
        // NacosSettings), promote to CURRENT before the store diffs so orphan
        // reclaim cannot reset live history against an empty pre-migration list.
        if (settingsSchemaVersion < SettingsSchema.CURRENT && profiles.isEmpty()) {
            runVersionedMigration(credentialSlots) { key ->
                dualWriteServers?.find { it.id == key }?.password
                    ?: servers.find { it.id == key }?.password
                    ?: NacosCredentialStore.get(key)
            }
        }

        val previousServersById = servers.associateBy { it.id }
        val previousIds = profiles.map { it.id }.filter { it.isNotBlank() }.toSet()
            .ifEmpty { servers.map { it.id }.toSet() }
        val previousActiveId = activeServerId
        val previousNamespaces = servers.associate { it.id to it.namespace }
            .ifEmpty {
                profiles.associate { it.id to migratedDefaultNamespaceId.ifBlank { "public" } }
            }
        val previousPreferences = environmentPreferences.map { it.copyPreferences() }
        // Defensive copies so the store never mutates live beans while deciding.
        val previousProfiles = profiles.map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) }

        val store = EnvironmentProfileStore(credentialSlots)
        val rawOutcome = store.applyIntents(
            intents = intents,
            activeProfileId = newActiveId,
            previousProfiles = previousProfiles,
            previousPreferences = previousPreferences,
            previousActiveId = previousActiveId,
            previousSuggestedNamespaces = previousNamespaces
        )

        // Honour the caller's requested active id when that profile published;
        // the store already prefers it, but a blank previous selection must not
        // hide a real switch the host asked for after a dual-write realign.
        val resolvedActive = newActiveId.takeIf { id ->
            id.isNotBlank() && rawOutcome.publishedProfiles.any { it.id == id }
        } ?: rawOutcome.activeProfileId
        val activeChanged = previousActiveId.isNotBlank() &&
            resolvedActive.isNotBlank() &&
            previousActiveId != resolvedActive
        val outcome = if (activeChanged != rawOutcome.activeProfileIdChanged ||
            resolvedActive != rawOutcome.activeProfileId
        ) {
            rawOutcome.copy(
                activeProfileId = resolvedActive,
                activeProfileIdChanged = activeChanged
            )
        } else {
            rawOutcome
        }

        // Entomb and drop credential slots for removed profiles before the new
        // set becomes visible, so late in-flight responses cannot resurrect
        // cache, token, or session state (design §19.2 / ADR-0025).
        outcome.removedProfileIds.forEach { profileId ->
            entombDeletedProfile(profileId)
            credentialSlots.removeAllForProfile(profileId)
        }

        profiles = outcome.publishedProfiles
            .map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) }
            .toMutableList()
        environmentPreferences = outcome.publishedPreferences
            .map { it.copyPreferences() }
            .toMutableList()
        activeServerId = outcome.activeProfileId.ifBlank { newActiveId }
        // Seed is migration-owned. Only fill a blank/stale seed on Apply so a
        // brand-new install gets a default without retargeting every Apply.
        if (migratedDefaultProfileId.isBlank() ||
            profiles.none { it.id == migratedDefaultProfileId }
        ) {
            migratedDefaultProfileId = activeServerId
        }
        val suggestedForActive = outcome.suggestedNamespaces[activeServerId]
            ?.ifBlank { "public" }
            ?: "public"
        if (migratedDefaultNamespaceId.isBlank()) {
            migratedDefaultNamespaceId = suggestedForActive
        }
        settingsSchemaVersion = SettingsSchema.CURRENT
        if (profiles.isNotEmpty()) {
            profileMigrationCompleted = true
            credentialSlotsPublished = true
        }

        // Dual-write only published ids. Failed Adds are omitted. Failed Keeps
        // retain the previous server snapshot with the secret from the still-
        // published slot — never the draft password/endpoint (ADR-0035).
        val draftById = (dualWriteServers ?: intents.map { intentToDraftServer(it) })
            .associateBy { it.id }
        val failedIds = outcome.failedStageProfileIds
        servers = outcome.publishedProfiles.map { profile ->
            dualWriteServerForPublished(
                profile = profile,
                outcome = outcome,
                failed = profile.id in failedIds,
                draft = draftById[profile.id],
                previousServer = previousServersById[profile.id],
                credentialSlots = credentialSlots
            )
        }.toMutableList()

        syncFromActiveServer()
        persistCredentials(previousIds)

        // Preference-only / display-only / pure-reorder writes must not advance
        // the session epoch (ADR-0042). The store outcome is authoritative.
        if (outcome.isOperationalChange()) {
            com.nanyin.nacos.search.services.ProjectSessionEpochs.bumpAllOpenProjects()
        }
        return outcome
    }

    /**
     * Builds one legacy server row for a **published** profile. On stage failure
     * the draft is ignored: connection fields and secret come from the previous
     * dual-write row / published slot so the dual-write surface cannot show a
     * pending pair.
     */
    private fun dualWriteServerForPublished(
        profile: EnvironmentProfile,
        outcome: ProfileStoreWriteOutcome,
        failed: Boolean,
        draft: NacosServerConfig?,
        previousServer: NacosServerConfig?,
        credentialSlots: CredentialSlotStore
    ): NacosServerConfig {
        val prefs = outcome.publishedPreferences
            .firstOrNull { it.profileId == profile.id }
            ?: EnvironmentPreferences.defaultsFor(profile.id)
        val ns = outcome.suggestedNamespaces[profile.id] ?: previousServer?.namespace ?: "public"
        val publishedSecret = credentialSlots
            .read(profile.id, profile.credentialSlotVersion)
            .orEmpty()
        if (failed) {
            val base = previousServer ?: profileToLegacyServer(profile, ns, prefs)
            return base.copy(
                // Published profile fields are the connection source of truth.
                displayName = profile.displayName,
                serverUrl = profile.canonicalEndpoint.ifBlank { base.serverUrl },
                username = profile.principal,
                password = publishedSecret.ifEmpty { base.password },
                namespace = ns,
                apiPolicy = profile.apiPolicy,
                authMode = profile.authMode,
                writeIntent = profile.writeIntent,
                allowCrossNamespaceNavigation = prefs.allowCrossNamespaceNavigation,
                navigationDetailPrefetchEnabled = prefs.navigationDetailPrefetchEnabled
            )
        }
        val base = draft ?: profileToLegacyServer(profile, ns, prefs)
        return base.copy(
            displayName = profile.displayName,
            serverUrl = base.serverUrl.ifBlank { profile.canonicalEndpoint },
            username = profile.principal.ifBlank { base.username },
            password = base.password.ifEmpty { publishedSecret },
            namespace = ns,
            apiPolicy = profile.apiPolicy,
            authMode = profile.authMode,
            writeIntent = profile.writeIntent,
            allowCrossNamespaceNavigation = prefs.allowCrossNamespaceNavigation,
            navigationDetailPrefetchEnabled = prefs.navigationDetailPrefetchEnabled
        )
    }

    private fun intentToDraftServer(intent: ProfileIntent): NacosServerConfig =
        NacosServerConfig(
            id = intent.profileId,
            displayName = intent.displayName,
            serverUrl = intent.endpoint,
            username = intent.principal,
            password = intent.secret,
            namespace = intent.suggestedNamespace,
            apiPolicy = intent.apiPolicy,
            authMode = intent.authMode,
            writeIntent = intent.writeIntent,
            allowCrossNamespaceNavigation = intent.preferences.allowCrossNamespaceNavigation,
            navigationDetailPrefetchEnabled = intent.preferences.navigationDetailPrefetchEnabled
        )

    private fun profileToLegacyServer(
        profile: EnvironmentProfile,
        namespace: String,
        prefs: EnvironmentPreferences
    ): NacosServerConfig =
        NacosServerConfig(
            id = profile.id,
            displayName = profile.displayName,
            serverUrl = profile.canonicalEndpoint,
            username = profile.principal,
            password = "",
            namespace = namespace,
            apiPolicy = profile.apiPolicy,
            authMode = profile.authMode,
            writeIntent = profile.writeIntent,
            allowCrossNamespaceNavigation = prefs.allowCrossNamespaceNavigation,
            navigationDetailPrefetchEnabled = prefs.navigationDetailPrefetchEnabled
        )

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

    /**
     * Pure read of the migration seed and published profiles for a newly opened
     * project. Never stages credentials or rewrites settings (issue #104).
     */
    fun migrationDefaults(): LegacyMigrationResult {
        val report = lastMigrationReport
        return LegacyMigrationResult(
            profiles = profiles.map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) },
            defaultProfileId = resolveDefaultProfileId(),
            defaultNamespaceId = migratedDefaultNamespaceId.ifBlank { "public" },
            migrationActions = report?.actions.orEmpty(),
            fromSchemaVersion = report?.fromSchemaVersion ?: settingsSchemaVersion,
            toSchemaVersion = report?.toSchemaVersion ?: settingsSchemaVersion
        )
    }

    /**
     * Prefer the persisted migration default, then the active dual-write id, then
     * the first live profile. Pure read — never rewrites the seed.
     */
    fun resolveDefaultProfileId(): String {
        return migratedDefaultProfileId.takeIf { id -> profiles.any { it.id == id } }
            ?: activeServerId.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
            ?: activeServerId
    }

    /**
     * Immutable snapshot of a published environment profile. Pure read — only
     * [applyProfileIntents] / [applyServers] / load-time migration write profiles.
     */
    fun getProfile(profileId: String): EnvironmentProfile? {
        val live = profiles.firstOrNull { it.id == profileId } ?: return null
        return live.copy(cacheTombstones = live.cacheTombstones.toMutableList())
    }

    /** Immutable snapshot of the active environment profile, if any. */
    fun getActiveProfile(): EnvironmentProfile? = getProfile(resolveDefaultProfileId())

    /** Immutable snapshots of every published environment profile, in store order. */
    fun publishedProfiles(): List<EnvironmentProfile> =
        profiles.map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) }

    /**
     * Captures a complete immutable context before network I/O.
     *
     * Pure with respect to settings and credential storage: reads only the
     * credential slot named by the published profile revision, off the caller's
     * thread responsibility (never stages or rewrites). Missing credentials or
     * an invalid endpoint yield [ConfigurationRequired] rather than a fallback
     * slot, another strategy, or an empty result (issue #104 / ADR-0035).
     */
    fun captureOperationContext(profileId: String? = null): Result<NacosOperationContext> {
        val requested = profileId?.trim().takeUnless { it.isNullOrBlank() }
        // Explicit project selections must fail closed when the profile is
        // missing — never silently retarget to the app-wide default (Local).
        val selectedProfileId = when {
            requested != null -> getProfile(requested)?.id
                ?: return Result.failure(ConfigurationRequired(listOf("Select a Nacos environment profile")))
            else -> resolveDefaultProfileId()
        }
        val persistedProfile = getProfile(selectedProfileId)
            ?: return Result.failure(
                ConfigurationRequired(listOf("Select a Nacos environment profile"))
            )
        // Read only the slot named by this profile's published revision.
        // Never fall back to a predecessor slot, dual-write password, or flat field.
        return OperationContextResolver.resolve(
            persistedProfile,
            DefaultCredentialSlotStore.read(
                persistedProfile.id,
                persistedProfile.credentialSlotVersion
            )
        )
    }

    /**
     * Writes each server's password to [NacosCredentialStore] and removes
     * credentials for servers that no longer exist.
     *
     * Revision-pinned profile slots are owned by [EnvironmentProfileStore]
     * via [CredentialSlotStore]; this only maintains the legacy server-id keys
     * used by migration and the settings dual-write surface.
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
     * Resets settings to default values (CURRENT schema with a seed local profile).
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

        servers = mutableListOf(
            NacosServerConfig(
                id = "s_local",
                displayName = "本地 Local",
                serverUrl = "http://localhost:8848"
            )
        )
        activeServerId = "s_local"
        profiles = servers.map { EnvironmentProfile.fromLegacy(it) }.toMutableList()
        environmentPreferences = mutableListOf(EnvironmentPreferences.defaultsFor("s_local"))
        settingsSchemaVersion = SettingsSchema.CURRENT
        profileMigrationCompleted = true
        credentialSlotsPublished = true
        migratedDefaultProfileId = "s_local"
        migratedDefaultNamespaceId = "public"
        lastMigrationReport = null
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
