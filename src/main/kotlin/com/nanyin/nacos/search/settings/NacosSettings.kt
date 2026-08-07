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
 * profile/default/identity reads after migration, and never re-persisted
 * (issue #104 / #153).
 */
@Service(Service.Level.APP)
@State(
    name = "NacosSettings",
    storages = [Storage("nacos-search.xml")]
)
class NacosSettings : PersistentStateComponent<NacosSettings> {

    // ---- Legacy multi-server surface (deserialization input only) ----
    // Runtime profile/default/identity paths must not read this list after
    // migration. [getState] clears it; [loadSettingsDraft] synthesizes dialog
    // rows from published profiles + preferences (issue #153).
    var servers: MutableList<NacosServerConfig> = mutableListOf(
        NacosServerConfig(
            id = "s_local",
            displayName = "本地 Local",
            serverUrl = "http://localhost:8848",
            // Product default for new installs; field default on the type stays
            // ANONYMOUS so XmlSerializer-omitted authMode does not migrate.
            authMode = AuthMode.NACOS_PASSWORD
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

    // Authentication configuration (flat legacy mirror).
    // Deserialization default is ANONYMOUS (XmlSerializer skip-defaults); product
    // seed / reset force NACOS_PASSWORD via [defaultPersistedShape].
    var authMode: AuthMode = AuthMode.ANONYMOUS
    /**
     * Legacy HYBRID/TOKEN/BASIC normalization input for [AuthStrategyFormPolicy].
     * Not a live toggle — retained so older XML and dual-write drafts still
     * map correctly (issue #156).
     */
    var enableTokenAuth: Boolean = true

    // Cache configuration
    var cacheEnabled: Boolean = true
    var cacheTtlMinutes: Int = 5

    // Language configuration
    var language: String = "en"

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
        // Environment configuration persists as profiles + preferences only
        // (ADR-0049 / issue #153). Legacy servers and flat active-server mirrors
        // remain deserialization inputs and are never rewritten.
        sanitized.servers = mutableListOf()
        sanitized.serverUrl = ""
        sanitized.username = ""
        sanitized.password = ""
        sanitized.namespace = "public"
        sanitized.authMode = AuthMode.ANONYMOUS
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
        // staging beyond empty slots for the default NACOS_PASSWORD strategy).
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
        // writing — only when migration may still need them as upgrade input.
        // Already-migrated installs never touch PasswordSafe here (issue #153).
        if (settingsSchemaVersion < SettingsSchema.CURRENT || profiles.none { it.id.isNotBlank() }) {
            for (server in servers) {
                val stored = NacosCredentialStore.get(server.id)
                if (!stored.isNullOrEmpty()) {
                    server.password = stored
                }
            }
        }

        runVersionedMigration(DefaultCredentialSlotStore) { key -> NacosCredentialStore.get(key) }

        // Migrated installs do not keep a live server list. Rebuild an in-memory
        // draft surface from published profiles for the settings dialog only.
        discardPersistedServerList()
        syncFlatFieldsFromProfiles()
    }

    /**
     * Drops legacy server rows after migration so they cannot be re-persisted.
     * An in-memory draft for the settings dialog is rebuilt from profiles +
     * preferences + credential slots when needed ([loadSettingsDraft]).
     */
    private fun discardPersistedServerList() {
        if (settingsSchemaVersion < SettingsSchema.CURRENT) return
        if (profiles.isEmpty()) return
        servers = mutableListOf()
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

        // Do not regenerate the legacy server list from profiles (ADR-0049).
        // In-memory draft rows are synthesized on demand for the settings dialog.
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

    /**
     * Draft rows for the settings dialog. Preference records overlay the dual-write
     * list; when dual-write is empty, synthesizes from published profiles.
     *
     * Pure read of published state plus credential-slot secrets for the form.
     * Does not migrate, stage, or rewrite active selection / seed (issue #106).
     */
    fun cloneServers(
        credentialSlots: CredentialSlotStore = DefaultCredentialSlotStore
    ): MutableList<NacosServerConfig> {
        return loadSettingsDraft(credentialSlots).toMutableList()
    }

    /**
     * Builds an in-memory settings draft from published profiles and preference
     * records. Secrets come from the published credential slot. Never writes
     * settings or credentials.
     */
    fun loadSettingsDraft(
        credentialSlots: CredentialSlotStore = DefaultCredentialSlotStore
    ): List<NacosServerConfig> {
        val dualById = servers.associateBy { it.id }
        val sourceProfiles = if (profiles.isNotEmpty()) {
            profiles
        } else {
            // Pre-migration throwaway instance: dual-write list only.
            return servers.map { server ->
                val prefs = preferencesFor(server.id)
                server.copy(
                    allowCrossNamespaceNavigation = prefs.allowCrossNamespaceNavigation,
                    navigationDetailPrefetchEnabled = prefs.navigationDetailPrefetchEnabled,
                    namespace = prefs.suggestedNamespace,
                    defaultGroup = prefs.defaultGroup
                )
            }
        }
        return sourceProfiles.map { profile ->
            val prefs = preferencesFor(profile.id)
            val dual = dualById[profile.id]
            val secret = credentialSlots
                .read(profile.id, profile.credentialSlotVersion)
                .orEmpty()
                .ifEmpty { dual?.password.orEmpty() }
            val ns = prefs.suggestedNamespace.trim().ifBlank {
                dual?.namespace?.takeIf { it.isNotBlank() }
                    ?: migratedDefaultNamespaceId.ifBlank { "public" }
            }
            NacosServerConfig(
                id = profile.id,
                displayName = profile.displayName,
                serverUrl = dual?.serverUrl?.takeIf { it.isNotBlank() }
                    ?: profile.canonicalEndpoint,
                username = profile.principal,
                password = secret,
                namespace = ns,
                apiPolicy = profile.apiPolicy,
                authMode = profile.authMode,
                defaultGroup = prefs.defaultGroup.trim().ifBlank {
                    dual?.defaultGroup ?: "DEFAULT_GROUP"
                },
                allowCrossNamespaceNavigation = prefs.allowCrossNamespaceNavigation,
                navigationDetailPrefetchEnabled = prefs.navigationDetailPrefetchEnabled,
                writeIntent = profile.writeIntent
            )
        }
    }

    /**
     * Profile intents derived from a dual-write draft surface (settings dialog).
     */
    fun intentsFromDraft(draft: List<NacosServerConfig>): List<ProfileIntent> =
        draft.map { ProfileIntent.fromServerConfig(it) }

    /**
     * Pure store-owned classification of a draft against published state.
     * Reads credential slots for secret comparison; never stages or deletes.
     */
    fun classifyDraft(
        draft: List<NacosServerConfig>,
        draftActiveId: String,
        previousActiveId: String = activeServerId,
        credentialSlots: CredentialSlotStore = DefaultCredentialSlotStore,
        isEntombed: (String) -> Boolean = { isProfileEntombed(it) }
    ): ProfileIntentClassification {
        val previousNamespaces = environmentPreferences
            .filter { it.profileId.isNotBlank() }
            .associate { it.profileId to it.suggestedNamespace.trim().ifBlank { "public" } }
            .ifEmpty {
                profiles.associate { it.id to migratedDefaultNamespaceId.ifBlank { "public" } }
            }
        val store = EnvironmentProfileStore(
            credentialSlots = credentialSlots,
            isEntombed = isEntombed
        )
        return store.classifyIntents(
            intents = intentsFromDraft(draft),
            activeProfileId = draftActiveId,
            previousProfiles = publishedProfiles(),
            previousPreferences = environmentPreferences.map { it.copyPreferences() },
            previousActiveId = previousActiveId,
            previousSuggestedNamespaces = previousNamespaces
        )
    }

    /**
     * One default draft row for [profileId], derived from the complete default
     * persisted shape rather than a hand-maintained field list (issue #106).
     * Keeps [displayName] when provided so Reset to Defaults does not rename.
     */
    fun defaultDraftRow(profileId: String, displayName: String? = null): NacosServerConfig {
        val shape = defaultPersistedShape()
        val seed = shape.servers.firstOrNull() ?: NacosServerConfig.createDefault()
        return seed.copy(
            id = profileId,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: seed.displayName
        )
    }

    /**
     * Complete default persisted shape. Field initializers are the source of
     * truth so Reset cannot miss a newly added persisted field (issue #106).
     */
    fun defaultPersistedShape(): NacosSettings = Companion.defaultPersistedShape()

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
     * credential staging; this host runs the fail-closed
     * [ProfileDeletionLifecycle] for every classified removal **before** the
     * published set omits that environment (ADR-0025 / issue #105), then
     * dual-writes the legacy server list **only for successfully published
     * profiles** so stage failure cannot surface a new secret on the dual-write
     * surface (ADR-0035).
     */
    fun applyProfileIntents(
        intents: List<ProfileIntent>,
        newActiveId: String,
        dualWriteServers: List<NacosServerConfig>? = null,
        credentialSlots: CredentialSlotStore = DefaultCredentialSlotStore,
        deletionLifecycle: ProfileDeletionLifecycle = defaultDeletionLifecycle(credentialSlots)
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
        val previousNamespaces = environmentPreferences
            .filter { it.profileId.isNotBlank() }
            .associate { it.profileId to it.suggestedNamespace.trim().ifBlank { "public" } }
            .ifEmpty {
                servers.associate { it.id to it.namespace.trim().ifBlank { "public" } }
                    .ifEmpty {
                        profiles.associate { it.id to migratedDefaultNamespaceId.ifBlank { "public" } }
                    }
            }
        val previousPreferences = environmentPreferences.map { it.copyPreferences() }
        // Defensive copies so the store never mutates live beans while deciding.
        val previousProfiles = profiles.map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) }
        val previousById = previousProfiles.associateBy { it.id }
        val previousPrefsById = previousPreferences
            .filter { it.profileId.isNotBlank() }
            .associateBy { it.profileId }

        val store = EnvironmentProfileStore(
            credentialSlots = credentialSlots,
            isEntombed = deletionLifecycle::isEntombed
        )
        val rawOutcome = store.applyIntents(
            intents = intents,
            activeProfileId = newActiveId,
            previousProfiles = previousProfiles,
            previousPreferences = previousPreferences,
            previousActiveId = previousActiveId,
            previousSuggestedNamespaces = previousNamespaces
        )

        // Confirmed removals run the one deletion lifecycle at this write
        // boundary. Tombstone first; on failure withhold that id from the
        // published omission set (design §13.6 / issue #105).
        val deletedIds = linkedSetOf<String>()
        val withheldDeletions = linkedSetOf<String>()
        for (profileId in rawOutcome.removedProfileIds) {
            when (deletionLifecycle.delete(profileId)) {
                is ProfileDeletionResult.Deleted -> deletedIds += profileId
                is ProfileDeletionResult.TombstoneFailed -> withheldDeletions += profileId
            }
        }

        val publishedById = rawOutcome.publishedProfiles
            .associateBy { it.id }
            .toMutableMap()
        val publishedPreferencesById = rawOutcome.publishedPreferences
            .associateBy { it.profileId }
            .toMutableMap()
        val suggestedNamespaces = rawOutcome.suggestedNamespaces.toMutableMap()
        for (profileId in withheldDeletions) {
            val previous = previousById[profileId] ?: continue
            publishedById.putIfAbsent(
                profileId,
                previous.copy(cacheTombstones = previous.cacheTombstones.toMutableList())
            )
            publishedPreferencesById.putIfAbsent(
                profileId,
                (previousPrefsById[profileId] ?: EnvironmentPreferences.defaultsFor(profileId))
                    .copyPreferences()
            )
            suggestedNamespaces.putIfAbsent(
                profileId,
                previousNamespaces[profileId]?.trim()?.ifBlank { "public" } ?: "public"
            )
        }
        // Preserve previous list order for survivors + withheld; append genuine adds.
        val orderedPublished = linkedSetOf<String>()
        previousProfiles.map { it.id }.filter { it in publishedById }.forEach { orderedPublished += it }
        rawOutcome.publishedProfiles.map { it.id }.filter { it !in orderedPublished }.forEach {
            orderedPublished += it
        }
        val publishedProfiles = orderedPublished.mapNotNull { publishedById[it] }
        val publishedPreferences = orderedPublished.mapNotNull { publishedPreferencesById[it] } +
            publishedPreferencesById.values.filter { it.profileId !in orderedPublished }

        // Residual cleanup for entombed ids already absent from the published
        // set (prior apply entombed successfully but cleanup partially failed).
        // Skip ids that just ran delete() on this write — retryCleanup is for
        // a later Apply when removedProfileIds is empty (issue #105 AC7).
        val publishedIdSet = publishedProfiles.map { it.id }.toSet()
        for (entombedId in deletionLifecycle.entombedProfileIds()) {
            if (entombedId !in publishedIdSet && entombedId !in deletedIds) {
                deletionLifecycle.retryCleanup(entombedId)
            }
        }

        // Honour the caller's requested active id when that profile published;
        // the store already prefers it, but a blank previous selection must not
        // hide a real switch the host asked for after a dual-write realign.
        // Withheld deletions re-enter the published set, so a requested active
        // that was only "removed" on a failed tombstone stays eligible.
        val resolvedActive = newActiveId.takeIf { id ->
            id.isNotBlank() && publishedProfiles.any { it.id == id }
        } ?: when {
            previousActiveId.isNotBlank() && publishedProfiles.any { it.id == previousActiveId } ->
                previousActiveId
            else -> publishedProfiles.firstOrNull()?.id.orEmpty()
        }
        val activeChanged = previousActiveId.isNotBlank() &&
            resolvedActive.isNotBlank() &&
            previousActiveId != resolvedActive
        val outcome = rawOutcome.copy(
            removedProfileIds = deletedIds.toSet(),
            withheldDeletionProfileIds = withheldDeletions.toSet(),
            publishedProfiles = publishedProfiles.toList(),
            publishedPreferences = publishedPreferences.toList(),
            suggestedNamespaces = suggestedNamespaces.toMap(),
            activeProfileId = resolvedActive,
            activeProfileIdChanged = activeChanged
        )

        profiles = outcome.publishedProfiles
            .map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) }
            .toMutableList()
        environmentPreferences = outcome.publishedPreferences
            .map { it.copyPreferences() }
            .toMutableList()
        activeServerId = outcome.activeProfileId.ifBlank { newActiveId }
        // Seed is migration-owned. Only fill a blank/stale seed on Apply so a
        // brand-new install gets a default without retargeting every Apply
        // (issue #104). When the deleted profile was the default, replace only
        // that stale seed with the resolved surviving active (or blank).
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
        // Withheld deletions stay dual-written from their previous snapshot.
        val draftById = (dualWriteServers ?: intents.map { intentToDraftServer(it) })
            .associateBy { it.id }
        val failedIds = outcome.failedStageProfileIds
        servers = outcome.publishedProfiles.map { profile ->
            dualWriteServerForPublished(
                profile = profile,
                outcome = outcome,
                failed = profile.id in failedIds || profile.id in withheldDeletions,
                draft = draftById[profile.id],
                previousServer = previousServersById[profile.id],
                credentialSlots = credentialSlots
            )
        }.toMutableList()

        syncFromActiveServer()
        // Legacy server-id PasswordSafe keys are migration inputs only. Secrets
        // live in revision-pinned credential slots (issue #153 / ADR-0035).

        // Preference-only / display-only / pure-reorder writes must not advance
        // the session epoch (ADR-0042). The store outcome is authoritative.
        // Lifecycle already bumped referring sessions for deleted ids; this
        // covers surviving-profile operational changes for every open project.
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
        val ns = prefs.suggestedNamespace.trim().ifBlank {
            outcome.suggestedNamespaces[profile.id] ?: previousServer?.namespace ?: "public"
        }
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
                defaultGroup = prefs.defaultGroup,
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
            defaultGroup = prefs.defaultGroup.trim().ifBlank {
                base.defaultGroup.ifBlank { "DEFAULT_GROUP" }
            },
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
            defaultGroup = intent.preferences.defaultGroup,
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
            namespace = namespace.ifBlank { prefs.suggestedNamespace }.ifBlank { "public" },
            apiPolicy = profile.apiPolicy,
            authMode = profile.authMode,
            writeIntent = profile.writeIntent,
            defaultGroup = prefs.defaultGroup,
            allowCrossNamespaceNavigation = prefs.allowCrossNamespaceNavigation,
            navigationDetailPrefetchEnabled = prefs.navigationDetailPrefetchEnabled
        )

/**
     * Default production lifecycle used when the caller does not inject one.
     * Live [NacosSettings] service instances write the shared tombstone registry
     * and clean real app/project state; throwaway copies used in unit tests
     * treat entomb as a local success so they never poison the suite registry
     * (inject [ProfileDeletionLifecycle] explicitly to assert fail-closed
     * behaviour).
     */
    private fun defaultDeletionLifecycle(
        credentialSlots: CredentialSlotStore
    ): ProfileDeletionLifecycle {
        val writer = object : ProfileTombstoneWriter {
            override fun tryEntomb(profileId: String): Boolean {
                try {
                    val app = ApplicationManager.getApplication() ?: return true
                    // Throwaway NacosSettings() copies must not write the shared
                    // registry. Treat as success so host unit tests that do not
                    // inject a lifecycle still publish removals locally.
                    if (app.getService(NacosSettings::class.java) !== this@NacosSettings) {
                        return true
                    }
                    val registry = app.getService(
                        com.nanyin.nacos.search.services.ProfileTombstoneRegistry::class.java
                    ) ?: return false
                    return registry.tryEntomb(profileId)
                } catch (_: Exception) {
                    // Live path is fail-closed: cannot prove durability.
                    return isLiveApplicationSettings().not()
                }
            }

            override fun isEntombed(profileId: String): Boolean {
                // Throwaway copies must not consult the shared registry either:
                // a live-settings deletion in another test would otherwise block
                // credential staging / publication on pure unit tests that reuse
                // common profile ids such as "dev".
                if (!isLiveApplicationSettings()) return false
                return isProfileEntombed(profileId)
            }

            override fun entombedProfileIds(): Set<String> {
                if (!isLiveApplicationSettings()) return emptySet()
                return try {
                    ApplicationManager.getApplication()
                        ?.getService(com.nanyin.nacos.search.services.ProfileTombstoneRegistry::class.java)
                        ?.entombedProfileIds()
                        .orEmpty()
                } catch (_: Exception) {
                    emptySet()
                }
            }
        }
        val cleanup = object : ProfileDeletionCleanup {
            override fun markReferringSessionsUnavailable(profileId: String) {
                markProjectSessionsProfileUnavailable(profileId)
            }

            override fun removeCredentialSlots(profileId: String) {
                credentialSlots.removeAllForProfile(profileId)
                // Legacy server-id key used by migration dual-write.
                NacosCredentialStore.remove(profileId)
            }

            override fun invalidateAuthentication(profileId: String) {
                try {
                    ApplicationManager.getApplication()
                        ?.getService(com.nanyin.nacos.search.services.NacosAuthService::class.java)
                        ?.invalidateProfile(profileId)
                } catch (_: Exception) {
                    // Outside a live IDE the auth service may be unavailable.
                }
            }

            override fun clearLastKnownGeneration(profileId: String) {
                try {
                    ApplicationManager.getApplication()
                        ?.getService(com.nanyin.nacos.search.services.LastKnownGenerationStore::class.java)
                        ?.clearProfile(profileId)
                } catch (_: Exception) {
                    // Same throwaway/headless bound as the tombstone writer.
                }
            }

            override fun purgeCache(profileId: String) {
                try {
                    ApplicationManager.getApplication()
                        ?.getService(com.nanyin.nacos.search.services.CacheService::class.java)
                        ?.purgeProfile(profileId)
                } catch (_: Exception) {
                    // Cache purge is residual cleanup; the tombstone remains.
                }
            }
        }
        return ProfileDeletionLifecycle(writer, cleanup)
    }

    private fun isLiveApplicationSettings(): Boolean {
        return try {
            val app = ApplicationManager.getApplication() ?: return false
            app.getService(NacosSettings::class.java) === this
        } catch (_: Exception) {
            false
        }
    }

    /** True when the shared (or injected) tombstone registry holds [profileId]. */
    private fun isProfileEntombed(profileId: String): Boolean {
        val id = profileId.trim()
        if (id.isBlank()) return false
        return try {
            ApplicationManager.getApplication()
                ?.getService(com.nanyin.nacos.search.services.ProfileTombstoneRegistry::class.java)
                ?.isEntombed(id) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Move open projects that still select [profileId] to the profile-unavailable
     * state: keep the stale selection, force [NacosProjectSessionState.selectionWasExplicit]
     * so [healSelection] cannot auto-retarget (ADR-0025), clear resolved
     * generation, and advance the session epoch so in-flight work detaches.
     */
    private fun markProjectSessionsProfileUnavailable(profileId: String) {
        val id = profileId.trim()
        if (id.isBlank()) return
        try {
            com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
                if (project.isDisposed) return@forEach
                val session = project.getService(NacosProjectSession::class.java) ?: return@forEach
                if (!session.markSelectedProfileUnavailable(id)) return@forEach
                project.getService(
                    com.nanyin.nacos.search.services.ProjectSessionEpochs::class.java
                )?.let { epochs ->
                    epochs.clearResolvedGeneration()
                    epochs.bump()
                }
            }
        } catch (_: Exception) {
            // ProjectManager may be unavailable outside a live IDE.
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
     * Seed-only default for newly initialized projects (issue #107). Prefer the
     * persisted migration default, then the first live profile. Does **not**
     * consult [activeServerId] — that dual-write field is not a live selection
     * and must not retarget a project that has not yet seeded.
     */
    fun resolveDefaultProfileId(): String {
        return migratedDefaultProfileId.takeIf { id -> id.isNotBlank() && profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id.orEmpty()
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
        // Tombstone is the absolute lifecycle guard: an entombed profile cannot
        // produce a new operation context even if a row briefly reappears
        // (ADR-0025 / issue #105). Pure read — no migration or staging (#104).
        if (selectedProfileId.isNotBlank() && isProfileEntombed(selectedProfileId)) {
            return Result.failure(ConfigurationRequired(listOf("Select a Nacos environment profile")))
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
     * Validates the current settings.
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
     * Resets settings to the complete default persisted shape (CURRENT schema
     * with a seed local profile). Derived from field initializers so a new
     * persisted field cannot be left at a non-default value (issue #106).
     *
     * This writes the live settings instance — the settings dialog's
     * "Reset to Defaults" edits only its in-memory draft until Apply.
     */
    fun resetToDefaults() {
        val shape = defaultPersistedShape()
        XmlSerializerUtil.copyBean(shape, this)
        // Defensive deep copies for mutable lists (XmlSerializer shares refs).
        servers = shape.servers.map { it.copy() }.toMutableList()
        profiles = shape.profiles
            .map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) }
            .toMutableList()
        environmentPreferences = shape.environmentPreferences
            .map { it.copyPreferences() }
            .toMutableList()
        lastMigrationReport = null
        password = ""
        servers.forEach { it.password = "" }
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
     * Gets cache TTL in milliseconds
     */
    fun getCacheTtlMillis(): Long {
        return cacheTtlMinutes * 60 * 1000L
    }
    
    override fun toString(): String {
        return "NacosSettings(" +
                "serverUrl='$serverUrl', " +
                "username='${if (username.isNotBlank()) "***" else ""}', " +
                "namespace='$namespace', " +
                "authMode=$authMode, " +
                "enableTokenAuth=$enableTokenAuth, " +
                "cacheEnabled=$cacheEnabled, " +
                "cacheTtlMinutes=$cacheTtlMinutes" +
                ")"
    }

    companion object {
        /**
         * Complete default persisted shape for a fresh install. Constructing a
         * blank [NacosSettings] and promoting the seed dual-write row into
         * published profiles/preferences so every persisted field comes from
         * the class's field initializers (issue #106).
         */
        fun defaultPersistedShape(): NacosSettings {
            val shape = NacosSettings()
            // Product auth default for new installs / Reset / draft rows.
            // Persisted type field defaults stay ANONYMOUS so XmlSerializer
            // skip-defaults does not migrate omitted authMode (ADR 0040).
            shape.authMode = AuthMode.NACOS_PASSWORD
            shape.servers.forEach { it.authMode = AuthMode.NACOS_PASSWORD }
            // Field initializers already populated dual-write seed + prefs defaults.
            // Promote seed rows (now NACOS_PASSWORD) into profiles — do not rewrite
            // any non-empty profiles list that might appear on a future shape change.
            if (shape.profiles.isEmpty() && shape.servers.isNotEmpty()) {
                shape.profiles = shape.servers
                    .map { EnvironmentProfile.fromLegacy(it) }
                    .toMutableList()
            }
            if (shape.environmentPreferences.isEmpty() && shape.profiles.isNotEmpty()) {
                shape.environmentPreferences = shape.profiles
                    .map { EnvironmentPreferences.defaultsFor(it.id) }
                    .toMutableList()
            }
            shape.settingsSchemaVersion = SettingsSchema.CURRENT
            shape.profileMigrationCompleted = shape.profiles.isNotEmpty()
            shape.credentialSlotsPublished = shape.profiles.isNotEmpty()
            shape.migratedDefaultProfileId = shape.activeServerId
            shape.migratedDefaultNamespaceId = "public"
            shape.lastMigrationReport = null
            shape.password = ""
            shape.servers.forEach { it.password = "" }
            return shape
        }
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
