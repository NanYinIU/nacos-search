package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosServerConfig
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Persisted settings schema version (issue #104 / ADR-0049).
 *
 * A version, not a shared boolean, decides whether deserialization migration
 * must run. Bump [CURRENT] when a new one-shot upgrade step is added.
 */
object SettingsSchema {
    const val CURRENT: Int = 1
}

/**
 * Inputs accepted only as upgrade sources: legacy server rows, flat active-server
 * fields, and any partially migrated profile list from earlier releases.
 */
data class SettingsMigrationInput(
    val schemaVersion: Int,
    val servers: List<NacosServerConfig>,
    val activeServerId: String,
    val flatNamespace: String,
    val profiles: List<EnvironmentProfile>,
    val preferences: List<EnvironmentPreferences>,
    val defaultProfileId: String,
    val defaultNamespaceId: String
)

/**
 * Explicit report of a versioned settings migration. Feeds the dismissible
 * per-project upgrade summary (ADR-0037).
 */
data class SettingsMigrationReport(
    val fromSchemaVersion: Int,
    val toSchemaVersion: Int,
    val actions: List<String>,
    val profiles: List<EnvironmentProfile>,
    val preferences: List<EnvironmentPreferences>,
    val defaultProfileId: String,
    val defaultNamespaceId: String,
    val suggestedNamespaces: Map<String, String>,
    /** Credential-slot stage calls that actually wrote during this pass. */
    val credentialSlotWrites: Int
) {
    /** True when the schema advanced or user-visible work was recorded. */
    val completedUpgrade: Boolean
        get() = fromSchemaVersion < toSchemaVersion || actions.isNotEmpty()
}

/**
 * Compatibility view used by project session seeding and the upgrade banner.
 * Derived from a [SettingsMigrationReport] or the current pure settings read.
 */
data class LegacyMigrationResult(
    val profiles: List<EnvironmentProfile>,
    val defaultProfileId: String,
    val defaultNamespaceId: String,
    val migrationActions: List<String> = emptyList(),
    val fromSchemaVersion: Int = SettingsSchema.CURRENT,
    val toSchemaVersion: Int = SettingsSchema.CURRENT
)

/**
 * One versioned deserialization migration into environment profiles.
 *
 * - Accepts legacy servers and flat active-server fields only as upgrade inputs.
 * - Preserves stable profile IDs, published revisions, and valid credential slots
 *   when the legacy data makes that possible.
 * - Idempotent: re-running over already-migrated state is byte-equivalent and
 *   performs zero credential-store writes.
 */
class SettingsMigrator(
    private val credentialSlots: CredentialSlotStore,
    /**
     * Read a legacy secret by opaque storage key (server id or already-known
     * slot id) without writing. Injected so tests never touch PasswordSafe.
     */
    private val legacySecretByKey: (String) -> String? = { null }
) {

    fun migrate(input: SettingsMigrationInput): SettingsMigrationReport {
        val from = input.schemaVersion.coerceAtLeast(0)
        if (from >= SettingsSchema.CURRENT) {
            return adoptAlreadyCurrent(input, from)
        }
        return migrateToCurrent(input, from)
    }

    /**
     * Already at [SettingsSchema.CURRENT]: sanitize, seed missing preference
     * records in-memory only, never touch credential storage.
     */
    private fun adoptAlreadyCurrent(
        input: SettingsMigrationInput,
        from: Int
    ): SettingsMigrationReport {
        val profiles = sanitizeProfiles(input.profiles)
        val namespaces = suggestedNamespaces(input, profiles)
        val preferences = reconcilePreferences(input, profiles, writeActions = null)
        val defaultId = resolveDefaultId(input, profiles)
        val defaultNs = resolveDefaultNamespace(input, defaultId, namespaces)
        return SettingsMigrationReport(
            fromSchemaVersion = from,
            toSchemaVersion = SettingsSchema.CURRENT,
            actions = emptyList(),
            profiles = profiles,
            preferences = preferences,
            defaultProfileId = defaultId,
            defaultNamespaceId = defaultNs,
            suggestedNamespaces = namespaces,
            credentialSlotWrites = 0
        )
    }

    private fun migrateToCurrent(
        input: SettingsMigrationInput,
        from: Int
    ): SettingsMigrationReport {
        val actions = mutableListOf<String>()
        var writes = 0
        val sanitizedExisting = sanitizeProfiles(input.profiles)

        val profiles: List<EnvironmentProfile>
        if (sanitizedExisting.isNotEmpty()) {
            profiles = sanitizedExisting
            actions += "Adopted ${profiles.size} existing environment profile(s) with stable ids and revisions"
            writes += stageMissingSlots(profiles, input.servers, actions)
        } else {
            val built = buildProfilesFromLegacy(input.servers, input, actions)
            profiles = built.profiles
            writes += built.writes
        }

        val namespaces = suggestedNamespaces(input, profiles)
        val preferences = reconcilePreferences(input, profiles, actions)
        val defaultId = resolveDefaultId(input, profiles)
        val defaultNs = resolveDefaultNamespace(input, defaultId, namespaces)

        if (from < SettingsSchema.CURRENT) {
            actions += "Advanced settings schema from v$from to v${SettingsSchema.CURRENT}"
        }
        if (defaultId.isNotBlank()) {
            actions += "Seeded default environment profile $defaultId / namespace $defaultNs"
        }
        if (preferences.isNotEmpty()) {
            actions += "Published ${preferences.size} profile-associated preference record(s)"
        }

        return SettingsMigrationReport(
            fromSchemaVersion = from,
            toSchemaVersion = SettingsSchema.CURRENT,
            actions = actions.distinct(),
            profiles = profiles,
            preferences = preferences,
            defaultProfileId = defaultId,
            defaultNamespaceId = defaultNs,
            suggestedNamespaces = namespaces,
            credentialSlotWrites = writes
        )
    }

    private data class BuiltProfiles(val profiles: List<EnvironmentProfile>, val writes: Int)

    private fun buildProfilesFromLegacy(
        servers: List<NacosServerConfig>,
        input: SettingsMigrationInput,
        actions: MutableList<String>
    ): BuiltProfiles {
        val source = servers.ifEmpty {
            // Flat active-server fields only (very old single-server XML).
            listOf(
                NacosServerConfig(
                    id = input.activeServerId.ifBlank { "default" },
                    displayName = "Local",
                    serverUrl = "http://localhost:8848",
                    namespace = input.flatNamespace.ifBlank { "public" }
                )
            )
        }
        val usedIds = mutableSetOf<String>()
        var writes = 0
        val profiles = source.mapIndexed { index, server ->
            val profileId = uniqueProfileId(server, index, usedIds)
            val normalized = server.copy(id = profileId)
            val profile = EnvironmentProfile.fromLegacy(normalized)
            val secret = resolveLegacySecret(normalized, profile)
            if (stageIfAbsent(profile, secret)) {
                writes++
            }
            profile
        }
        actions += "Migrated ${profiles.size} environment profile(s) from legacy server configuration"
        if (writes > 0) {
            actions += "Staged $writes credential slot(s) for migrated profiles"
        }
        return BuiltProfiles(profiles, writes)
    }

    /**
     * Stage only when the named slot is empty and a secret is available.
     * Never overwrites a valid published slot (ADR-0035).
     */
    private fun stageMissingSlots(
        profiles: List<EnvironmentProfile>,
        servers: List<NacosServerConfig>,
        actions: MutableList<String>
    ): Int {
        var writes = 0
        for (profile in profiles) {
            val secret = resolveLegacySecret(
                servers.find { it.id == profile.id },
                profile
            )
            if (stageIfAbsent(profile, secret)) {
                writes++
            }
        }
        if (writes > 0) {
            actions += "Staged $writes missing credential slot(s) from legacy secrets"
        }
        return writes
    }

    private fun stageIfAbsent(profile: EnvironmentProfile, secret: String): Boolean {
        if (secret.isEmpty()) return false
        val existing = credentialSlots.read(profile.id, profile.credentialSlotVersion)
        if (!existing.isNullOrEmpty()) return false
        return when (credentialSlots.stage(profile.id, profile.credentialSlotVersion, secret)) {
            is CredentialStageResult.Success -> true
            is CredentialStageResult.Failure -> false
        }
    }

    private fun resolveLegacySecret(server: NacosServerConfig?, profile: EnvironmentProfile): String {
        val fromServer = server?.password.orEmpty()
        if (fromServer.isNotEmpty()) return fromServer
        val fromSlot = legacySecretByKey(profile.credentialSlotId)
            ?: legacySecretByKey(CredentialSlotStore.slotKey(profile.id, profile.credentialSlotVersion))
        if (!fromSlot.isNullOrEmpty()) return fromSlot
        val fromLegacyId = server?.id?.let { legacySecretByKey(it) }
            ?: legacySecretByKey(profile.id)
        return fromLegacyId.orEmpty()
    }

    private fun sanitizeProfiles(profiles: List<EnvironmentProfile>): List<EnvironmentProfile> =
        profiles.mapNotNull { profile ->
            if (profile.id.isBlank()) return@mapNotNull null
            val slotId = profile.credentialSlotId.ifBlank {
                CredentialSlotStore.slotKey(profile.id, profile.credentialSlotVersion.coerceAtLeast(1))
            }
            val version = profile.credentialSlotVersion.coerceAtLeast(1)
            profile.copy(
                credentialSlotId = slotId,
                credentialSlotVersion = version,
                cacheTombstones = profile.cacheTombstones.toMutableList()
            )
        }

    private fun reconcilePreferences(
        input: SettingsMigrationInput,
        profiles: List<EnvironmentProfile>,
        writeActions: MutableList<String>?
    ): List<EnvironmentPreferences> {
        val byId = input.preferences
            .filter { it.profileId.isNotBlank() }
            .associateBy { it.profileId }
            .mapValues { (_, p) -> p.copyPreferences() }
            .toMutableMap()
        val serversById = input.servers.associateBy { it.id }
        for (profile in profiles) {
            if (byId.containsKey(profile.id)) continue
            val server = serversById[profile.id]
            byId[profile.id] = if (server != null) {
                EnvironmentPreferences.fromLegacyServer(server.copy(id = profile.id))
            } else {
                EnvironmentPreferences.defaultsFor(profile.id)
            }
        }
        return byId.values.toList()
    }

    private fun suggestedNamespaces(
        input: SettingsMigrationInput,
        profiles: List<EnvironmentProfile>
    ): Map<String, String> {
        val fromServers = input.servers
            .filter { it.id.isNotBlank() }
            .associate { it.id to it.namespace.trim().ifBlank { "public" } }
        return profiles.associate { profile ->
            val ns = fromServers[profile.id]
                ?: input.flatNamespace.trim().ifBlank { "public" }
            profile.id to ns
        }
    }

    private fun resolveDefaultId(
        input: SettingsMigrationInput,
        profiles: List<EnvironmentProfile>
    ): String {
        if (profiles.isEmpty()) return ""
        val candidates = listOf(
            input.defaultProfileId,
            input.activeServerId
        )
        for (id in candidates) {
            if (id.isNotBlank() && profiles.any { it.id == id }) return id
        }
        return profiles.first().id
    }

    private fun resolveDefaultNamespace(
        input: SettingsMigrationInput,
        defaultProfileId: String,
        namespaces: Map<String, String>
    ): String {
        val fromSeed = input.defaultNamespaceId.trim()
        if (fromSeed.isNotBlank()) return fromSeed
        namespaces[defaultProfileId]?.let { return it }
        return input.flatNamespace.trim().ifBlank { "public" }
    }

    private fun uniqueProfileId(
        server: NacosServerConfig,
        index: Int,
        usedIds: MutableSet<String>
    ): String {
        val requested = server.id.trim()
        val source = listOf(index, server.displayName, server.serverUrl, server.username, server.authMode.name)
            .joinToString("\u0000")
        val deterministicSuffix = UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8)).toString()
        val base = requested.ifBlank { "legacy-$deterministicSuffix" }
        val unique = if (usedIds.add(base)) base else "$base-$deterministicSuffix"
        usedIds.add(unique)
        return unique
    }
}
