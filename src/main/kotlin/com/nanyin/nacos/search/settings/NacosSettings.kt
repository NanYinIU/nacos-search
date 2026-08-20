package com.nanyin.nacos.search.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.models.ProfileIntent
import com.nanyin.nacos.search.services.ResolvedGenerationLocator

/**
 * Credential-free read projection of one published environment.
 *
 * This is deliberately immutable and contains only fields needed to choose an
 * environment. Settings editors use [ProfileIntent], never this projection.
 */
data class PublishedEnvironment(
    val profileId: String,
    val displayName: String,
    val canonicalEndpoint: String,
    val suggestedNamespace: String
)

/**
 * Persistent settings for the Nacos plugin.
 *
 * Environment profiles are the sole runtime environment model (ADR-0049).
 * Legacy [servers], [activeServerId], and flat environment fields are mutable
 * only so XmlSerializer can deserialize previously shipped state. Versioned
 * migration consumes and discards them; runtime and Settings paths use profiles,
 * preferences, and intents only (ADR-0049 / issue #233).
 */
@Service(Service.Level.APP)
@State(
    name = "NacosSettings",
    storages = [Storage("nacos-search.xml")]
)
class NacosSettings : PersistentStateComponent<NacosSettings> {

    // ---- Legacy multi-server surface (deserialization input only) ----
    // Only versioned migration / legacy-shape detection may read these fields.
    // [getState] and successful migration clear them.
    var servers: MutableList<NacosServerConfig> = mutableListOf()
    var activeServerId: String = ""

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

    /** Seed-only default profile id written by migration. */
    var migratedDefaultProfileId: String = ""
    var migratedDefaultNamespaceId: String = "public"

    /**
     * Profile-associated preference-only records (ADR-0042 / issue #101).
     * Keyed by environment profile id; holds no connection target or revisions.
     */
    var environmentPreferences: MutableList<EnvironmentPreferences> = mutableListOf()

    // Flat active-server fields — deserialization inputs only after migration.
    var serverUrl: String = ""
    var username: String = ""
    var password: String = ""
    var namespace: String = "public"

    // Authentication configuration (flat legacy mirror).
    // Deserialization default is ANONYMOUS (XmlSerializer skip-defaults); product
    // seed / reset force NACOS_PASSWORD via [defaultPersistedShape].
    var authMode: AuthMode = AuthMode.ANONYMOUS
    /**
     * Legacy HYBRID normalization input retained for versioned migration only.
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
        // Environment configuration persists as profiles + preferences only.
        sanitized.servers = mutableListOf()
        sanitized.activeServerId = ""
        sanitized.serverUrl = ""
        sanitized.username = ""
        sanitized.password = ""
        sanitized.namespace = "public"
        sanitized.authMode = AuthMode.ANONYMOUS
        sanitized.enableTokenAuth = true
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

        runVersionedMigration(DefaultCredentialSlotStore) { key -> NacosCredentialStore.get(key) }
    }

    /** Clears every compatibility-only environment input after migration. */
    private fun discardLegacyEnvironmentInputs() {
        servers = mutableListOf()
        activeServerId = ""
        serverUrl = ""
        username = ""
        password = ""
        namespace = "public"
        authMode = AuthMode.ANONYMOUS
        enableTokenAuth = true
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
        val flatServer = if (
            effectiveSchema < SettingsSchema.CURRENT &&
            servers.isEmpty() &&
            profiles.none { it.id.isNotBlank() } &&
            hasLegacyFlatEnvironmentInput()
        ) {
            NacosServerConfig(
                id = activeServerId.ifBlank { "s_local" },
                displayName = "Local",
                serverUrl = serverUrl.ifBlank { "http://localhost:8848" },
                username = username,
                password = password,
                namespace = namespace,
                authMode = authMode
            )
        } else {
            null
        }
        val report = migrator.migrate(
            SettingsMigrationInput(
                schemaVersion = effectiveSchema,
                servers = servers.map { it.copy() },
                activeServerId = activeServerId,
                flatNamespace = namespace,
                profiles = profiles.map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) },
                preferences = environmentPreferences.map { it.copyPreferences() },
                defaultProfileId = migratedDefaultProfileId,
                defaultNamespaceId = migratedDefaultNamespaceId,
                legacyEnableTokenAuth = enableTokenAuth,
                flatServer = flatServer
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

        discardLegacyEnvironmentInputs()
        return report
    }

    private fun hasLegacyFlatEnvironmentInput(): Boolean =
        activeServerId.isNotBlank() ||
            serverUrl.isNotBlank() ||
            username.isNotBlank() ||
            password.isNotBlank() ||
            namespace.trim().let { it.isNotBlank() && it != "public" } ||
            authMode != AuthMode.ANONYMOUS ||
            !enableTokenAuth

    private fun installFactoryDefaults() {
        val shape = defaultPersistedShape()
        adoptPublishedState(shape)
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
    
    /**
     * Intent-native, in-memory Settings draft loaded from the published model.
     *
     * Each secret is read only from the immutable slot named by its published
     * profile. This method never migrates, stages, or writes settings.
     */
    fun loadIntentDraft(
        credentialSlots: CredentialSlotStore = DefaultCredentialSlotStore,
        activeProfileId: String = resolveDefaultProfileId(),
        openBaselineActiveId: String = activeProfileId
    ): SettingsIntentDraft {
        val intents = profiles.map { profile ->
            val preferences = preferencesFor(profile.id)
            val namespace = preferences.suggestedNamespace.trim().ifBlank { "public" }
            ProfileIntent(
                profileId = profile.id,
                displayName = profile.displayName,
                endpoint = profile.canonicalEndpoint,
                apiPolicy = profile.apiPolicy,
                authMode = profile.authMode,
                principal = profile.principal,
                secret = credentialSlots
                    .read(profile.id, profile.credentialSlotVersion)
                    .orEmpty(),
                writeIntent = profile.writeIntent,
                suggestedNamespace = namespace,
                preferences = preferences.copy(
                    profileId = profile.id,
                    suggestedNamespace = namespace
                )
            )
        }
        val ids = intents.map { it.profileId }.toSet()
        val resolvedActive = activeProfileId.takeIf { it in ids }
            ?: resolveDefaultProfileId().takeIf { it in ids }
            ?: intents.firstOrNull()?.profileId.orEmpty()
        val resolvedBaseline = openBaselineActiveId.takeIf { it in ids } ?: resolvedActive
        return SettingsIntentDraft.of(intents, resolvedActive, resolvedBaseline)
    }

    /**
     * Pure classification of an intent-native draft against published state.
     * The draft owns the active id captured when Settings opened, so callers
     * cannot accidentally compare against a later application selection.
     */
    fun classifyIntentDraft(
        draft: SettingsIntentDraft,
        credentialSlots: CredentialSlotStore = DefaultCredentialSlotStore,
        isEntombed: (String) -> Boolean = { isProfileEntombed(it) }
    ): ProfileIntentClassification {
        val previousNamespaces = environmentPreferences
            .filter { it.profileId.isNotBlank() }
            .associate { it.profileId to it.suggestedNamespace.trim().ifBlank { "public" } }
            .ifEmpty {
                profiles.associate { it.id to migratedDefaultNamespaceId.ifBlank { "public" } }
            }
        return EnvironmentProfileStore(
            credentialSlots = credentialSlots,
            isEntombed = isEntombed
        ).classifyIntents(
            intents = draft.snapshot(),
            activeProfileId = draft.activeProfileId,
            previousProfiles = publishedProfiles(),
            previousPreferences = environmentPreferences.map { it.copyPreferences() },
            previousActiveId = draft.openBaselineActiveId,
            previousSuggestedNamespaces = previousNamespaces
        )
    }

    /**
     * Product-default intent for one in-memory Settings row. The stable id and
     * optional display name are retained; no revision or credential coordinate
     * crosses this boundary.
     */
    fun defaultProfileIntent(profileId: String, displayName: String? = null): ProfileIntent {
        val shape = defaultPersistedShape()
        val profile = shape.profiles.first()
        val preferences = shape.preferencesFor(profile.id)
        val namespace = preferences.suggestedNamespace.trim().ifBlank { "public" }
        return ProfileIntent(
            profileId = profileId,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: profile.displayName,
            endpoint = profile.canonicalEndpoint,
            apiPolicy = profile.apiPolicy,
            authMode = profile.authMode,
            principal = profile.principal,
            secret = "",
            writeIntent = profile.writeIntent,
            suggestedNamespace = namespace,
            preferences = preferences.copy(
                profileId = profileId,
                suggestedNamespace = namespace
            )
        )
    }

    /**
     * Complete default persisted shape for new installs and Reset.
     */
    fun defaultPersistedShape(): NacosSettings = Companion.defaultPersistedShape()

    /**
     * Preference-only values for [profileId], never null. Missing records resolve
     * to product defaults without consulting the legacy server list.
     */
    fun preferencesFor(profileId: String): EnvironmentPreferences {
        val id = profileId.trim().ifBlank { resolveDefaultProfileId().ifBlank { "s_local" } }
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
     * Single write entry that turns profile intents into published environment
     * profiles (ADR-0049 / issue #103). The store owns revision derivation and
     * credential staging; this host runs the fail-closed
     * [ProfileDeletionLifecycle] for every classified removal **before** the
     * published set omits that environment (ADR-0025 / issue #105).
     */
    fun applyProfileIntents(
        intents: List<ProfileIntent>,
        newActiveId: String,
        previousActiveId: String = resolveDefaultProfileId(),
        credentialSlots: CredentialSlotStore = DefaultCredentialSlotStore,
        deletionLifecycle: ProfileDeletionLifecycle = defaultDeletionLifecycle(credentialSlots)
    ): ProfileStoreWriteOutcome {
        val previousNamespaces = environmentPreferences
            .filter { it.profileId.isNotBlank() }
            .associate { it.profileId to it.suggestedNamespace.trim().ifBlank { "public" } }
            .ifEmpty {
                profiles.associate { it.id to "public" }
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

        // Honour the caller's requested project-local selection when that
        // profile published.
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
        settingsSchemaVersion = SettingsSchema.CURRENT
        if (profiles.isNotEmpty()) {
            profileMigrationCompleted = true
            credentialSlotsPublished = true
        }

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
                // Legacy server-id PasswordSafe key retained as migration input.
                NacosCredentialStore.remove(profileId)
            }

            override fun invalidateAuthentication(profileId: String) {
                try {
                    ApplicationManager.getApplication()
                        ?.getService(com.nanyin.nacos.search.services.AuthenticationSessionRegistry::class.java)
                        ?.invalidateProfile(profileId)
                } catch (_: Exception) {
                    // Outside a live IDE the session registry may be unavailable.
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

    /** Compatibility read for the upgrade summary; never stages credentials. */
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
     * persisted migration default, then the first live profile.
     */
    fun resolveDefaultProfileId(): String {
        return migratedDefaultProfileId.takeIf { id -> id.isNotBlank() && profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id.orEmpty()
    }

    /**
     * Immutable snapshot of a published environment profile. Pure read — only
     * [applyProfileIntents] and load-time migration write profiles.
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
     * Immutable environment choices derived only from published profiles and
     * their profile-associated preferences. This read never consults legacy
     * server rows, flat active fields, or credential storage.
     */
    fun publishedEnvironments(): List<PublishedEnvironment> {
        val preferencesByProfile = environmentPreferences
            .filter { it.profileId.isNotBlank() }
            .associateBy { it.profileId }
        return profiles.map { profile ->
            PublishedEnvironment(
                profileId = profile.id,
                displayName = profile.displayName,
                canonicalEndpoint = profile.canonicalEndpoint,
                suggestedNamespace = NamespaceInfo.canonicalId(
                    preferencesByProfile[profile.id]?.suggestedNamespace
                )
            )
        }
    }

    fun publishedEnvironment(profileId: String): PublishedEnvironment? =
        publishedEnvironments().firstOrNull { it.profileId == profileId }

    /** Migration-owned default resolved to the published read projection. */
    fun defaultPublishedEnvironment(): PublishedEnvironment? =
        publishedEnvironment(resolveDefaultProfileId())

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
        // Never fall back to a predecessor slot or legacy secret.
        return OperationContextResolver.resolve(
            persistedProfile,
            DefaultCredentialSlotStore.read(
                persistedProfile.id,
                persistedProfile.credentialSlotVersion
            )
        )
    }

    /**
     * Derives the access identity for PSI/Swing hot paths WITHOUT reading PasswordSafe.
     * Reading a credential on the EDT triggers `SlowOperations` and is forbidden on
     * the hot path (design §11/§19.7). Identity fields come entirely from the profile,
     * so this resolves the selected profile and maps it through
     * [OperationContextResolver.identityFromProfile], which never touches the
     * credential store. A missing/invalid profile yields a stable sentinel identity.
     *
     * An AUTO profile has no generation in the profile to map, so [locator] supplies
     * the one the operation layer resolved — also without a credential — and the read
     * addresses the same key space the gateway writes under (issue #72).
     *
     * This is a member of [NacosSettings] rather than a top-level extension so
     * IDEA's JPS compiler indexes it. Cross-file top-level functions in this
     * module surface as `Unresolved reference` during `runIde`.
     */
    internal fun captureAccessIdentity(
        profileId: String? = null,
        locator: ResolvedGenerationLocator = ResolvedGenerationLocator.forSelectedProfile()
    ): AccessIdentity {
        val selectedProfileId = profileId?.trim()?.takeUnless { it.isNullOrBlank() }
            ?: resolveDefaultProfileId()
        val profile = getProfile(selectedProfileId)
            ?: return AccessIdentity.ofProfile(
                profileId = "<configuration-required>",
                accessRevision = -1,
                canonicalEndpoint = "<invalid>",
                resolvedGeneration = NacosApiGeneration.UNKNOWN,
                authMode = AuthMode.TOKEN,
                principal = ""
            )
        return locator.applyTo(
            OperationContextResolver.identityFromProfile(profile),
            profile.profileRevision
        )
    }

    /**
     * Validates the current settings.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (profiles.isEmpty()) {
            errors += "Server URL cannot be empty"
        }
        for (profile in profiles) {
            if (profile.canonicalEndpoint.isBlank()) {
                errors += "Server URL cannot be empty"
            } else if (!isValidServerUrl(profile.canonicalEndpoint)) {
                errors += "Invalid server URL format"
            }
        }

        if (cacheEnabled) {
            if (cacheTtlMinutes < 1) {
                errors.add("Cache TTL must be at least 1 minute")
            }
        }

        return errors.distinct()
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
     * with a seed local profile).
     *
     * This writes the live settings instance — the settings dialog's
     * "Reset to Defaults" edits only its in-memory draft until Apply.
     */
    fun resetToDefaults() {
        val shape = defaultPersistedShape()
        adoptPublishedState(shape)
        lastMigrationReport = null
    }

    /** Copies current-schema state without touching compatibility-only inputs. */
    private fun adoptPublishedState(shape: NacosSettings) {
        profiles = shape.profiles
            .map { it.copy(cacheTombstones = it.cacheTombstones.toMutableList()) }
            .toMutableList()
        settingsSchemaVersion = shape.settingsSchemaVersion
        profileMigrationCompleted = shape.profileMigrationCompleted
        credentialSlotsPublished = shape.credentialSlotsPublished
        migratedDefaultProfileId = shape.migratedDefaultProfileId
        migratedDefaultNamespaceId = shape.migratedDefaultNamespaceId
        environmentPreferences = shape.environmentPreferences
            .map { it.copyPreferences() }
            .toMutableList()
        cacheEnabled = shape.cacheEnabled
        cacheTtlMinutes = shape.cacheTtlMinutes
        language = shape.language
    }
    
    /**
     * Gets cache TTL in milliseconds
     */
    fun getCacheTtlMillis(): Long {
        return cacheTtlMinutes * 60 * 1000L
    }
    
    override fun toString(): String {
        return "NacosSettings(" +
                "profiles=${profiles.size}, " +
                "defaultProfileId='${resolveDefaultProfileId()}', " +
                "cacheEnabled=$cacheEnabled, " +
                "cacheTtlMinutes=$cacheTtlMinutes" +
                ")"
    }

    companion object {
        /**
         * Complete default persisted shape for a fresh install. Compatibility
         * inputs are cleared; the product default is published directly.
         */
        fun defaultPersistedShape(): NacosSettings {
            val shape = NacosSettings()
            shape.profiles = mutableListOf(
                EnvironmentProfile(
                    id = "s_local",
                    displayName = "本地 Local",
                    canonicalEndpoint = "http://localhost:8848",
                    authMode = AuthMode.NACOS_PASSWORD,
                    credentialSlotId = CredentialSlotStore.slotKey("s_local", 1),
                    credentialSlotVersion = 1
                )
            )
            shape.environmentPreferences = mutableListOf(
                EnvironmentPreferences.defaultsFor("s_local")
            )
            shape.settingsSchemaVersion = SettingsSchema.CURRENT
            shape.profileMigrationCompleted = true
            shape.credentialSlotsPublished = true
            shape.migratedDefaultProfileId = "s_local"
            shape.migratedDefaultNamespaceId = "public"
            shape.lastMigrationReport = null
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
