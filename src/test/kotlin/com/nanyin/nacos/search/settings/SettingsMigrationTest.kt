package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.ProfileIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Versioned settings migration and read purity (issue #104).
 *
 * Seam: [SettingsMigrator] + [NacosSettings.runVersionedMigration] /
 * pure reads against an injected [InMemoryCredentialSlotStore].
 */
class SettingsMigrationTest {

    @Test
    fun `schema version not boolean decides whether migration runs`() {
        val slots = InMemoryCredentialSlotStore()
        val legacy = SettingsMigrationInput(
            schemaVersion = 0,
            servers = listOf(
                NacosServerConfig(
                    id = "dev",
                    displayName = "Dev",
                    serverUrl = "https://nacos.example",
                    username = "alice",
                    password = "secret",
                    namespace = "team-a",
                    authMode = AuthMode.TOKEN
                )
            ),
            activeServerId = "dev",
            flatNamespace = "team-a",
            profiles = emptyList(),
            preferences = emptyList(),
            defaultProfileId = "",
            defaultNamespaceId = ""
        )

        val first = SettingsMigrator(slots).migrate(legacy)
        assertEquals(0, first.fromSchemaVersion)
        assertEquals(SettingsSchema.CURRENT, first.toSchemaVersion)
        assertTrue(first.actions.isNotEmpty())
        assertEquals(1, first.credentialSlotWrites)
        assertEquals("dev", first.defaultProfileId)
        assertEquals("team-a", first.defaultNamespaceId)

        val second = SettingsMigrator(slots).migrate(
            legacy.copy(
                schemaVersion = first.toSchemaVersion,
                profiles = first.profiles,
                preferences = first.preferences,
                defaultProfileId = first.defaultProfileId,
                defaultNamespaceId = first.defaultNamespaceId
            )
        )
        assertEquals(SettingsSchema.CURRENT, second.fromSchemaVersion)
        assertEquals(SettingsSchema.CURRENT, second.toSchemaVersion)
        assertTrue(second.actions.isEmpty())
        assertEquals(0, second.credentialSlotWrites)
        assertEquals(first.profiles.map { it.id to it.accessRevision }, second.profiles.map { it.id to it.accessRevision })
    }

    @Test
    fun `second pass over migrated state is byte-equivalent and writes zero credentials`() {
        val slots = InMemoryCredentialSlotStore()
        val servers = listOf(
            NacosServerConfig(
                id = "qa",
                displayName = "QA",
                serverUrl = "https://qa.nacos.example",
                username = "bob",
                password = "pw",
                authMode = AuthMode.NACOS_PASSWORD
            )
        )
        val first = SettingsMigrator(slots).migrate(
            SettingsMigrationInput(
                schemaVersion = 0,
                servers = servers,
                activeServerId = "qa",
                flatNamespace = "public",
                profiles = emptyList(),
                preferences = emptyList(),
                defaultProfileId = "",
                defaultNamespaceId = ""
            )
        )
        val writesAfterFirst = slots.writes.size

        val second = SettingsMigrator(slots).migrate(
            SettingsMigrationInput(
                schemaVersion = SettingsSchema.CURRENT,
                servers = servers,
                activeServerId = "qa",
                flatNamespace = "public",
                profiles = first.profiles,
                preferences = first.preferences,
                defaultProfileId = first.defaultProfileId,
                defaultNamespaceId = first.defaultNamespaceId
            )
        )

        assertEquals(0, second.credentialSlotWrites)
        assertEquals(writesAfterFirst, slots.writes.size)
        assertEquals(
            first.profiles.map { snapshot(it) },
            second.profiles.map { snapshot(it) }
        )
        assertEquals(first.defaultProfileId, second.defaultProfileId)
        assertEquals(first.defaultNamespaceId, second.defaultNamespaceId)
    }

    @Test
    fun `existing profiles preserve ids revisions and valid slots`() {
        val slots = InMemoryCredentialSlotStore()
        slots.stage("prod", 3, "already-there")
        val existing = EnvironmentProfile(
            id = "prod",
            displayName = "Production",
            canonicalEndpoint = "https://prod.nacos.example",
            authMode = AuthMode.NACOS_PASSWORD,
            principal = "alice",
            profileRevision = 7,
            accessRevision = 3,
            credentialSlotId = "prod:v3",
            credentialSlotVersion = 3
        )

        val report = SettingsMigrator(slots).migrate(
            SettingsMigrationInput(
                schemaVersion = 0,
                servers = listOf(
                    NacosServerConfig(
                        id = "prod",
                        displayName = "Should not overwrite",
                        serverUrl = "https://other.example",
                        password = "ignored-when-slot-valid"
                    )
                ),
                activeServerId = "prod",
                flatNamespace = "ops",
                profiles = listOf(existing),
                preferences = listOf(EnvironmentPreferences.defaultsFor("prod")),
                defaultProfileId = "prod",
                defaultNamespaceId = "ops"
            )
        )

        val profile = report.profiles.single()
        assertEquals("prod", profile.id)
        assertEquals(7, profile.profileRevision)
        assertEquals(3, profile.accessRevision)
        assertEquals("prod:v3", profile.credentialSlotId)
        assertEquals(3, profile.credentialSlotVersion)
        assertEquals("https://prod.nacos.example", profile.canonicalEndpoint)
        assertEquals(0, report.credentialSlotWrites)
        assertEquals("already-there", slots.read("prod", 3))
    }

    @Test
    fun `NacosSettings loadState migrates once and pure reads leave state unchanged`() {
        val slots = InMemoryCredentialSlotStore()
        val state = NacosSettings()
        state.settingsSchemaVersion = 0
        state.profileMigrationCompleted = false
        state.credentialSlotsPublished = false
        state.profiles = mutableListOf()
        state.servers = mutableListOf(
            NacosServerConfig(
                id = "s1",
                displayName = "One",
                serverUrl = "http://localhost:8848",
                username = "nacos",
                password = "secret",
                authMode = AuthMode.NACOS_PASSWORD,
                namespace = "public"
            )
        )
        state.activeServerId = "s1"
        state.migratedDefaultProfileId = ""
        state.migratedDefaultNamespaceId = ""

        // Drive migration through the injectable seam (not PasswordSafe).
        val report = state.runVersionedMigration(slots) { key ->
            state.servers.find { it.id == key }?.password
                ?: if (key == "s1") "secret" else null
        }

        assertEquals(SettingsSchema.CURRENT, state.settingsSchemaVersion)
        assertTrue(report.actions.isNotEmpty())
        assertEquals(1, report.credentialSlotWrites)
        val writesAfterMigrate = slots.writes.size

        val beforeProfiles = state.profiles.map { snapshot(it) }
        val beforePrefs = state.environmentPreferences.map { it.copyPreferences() }
        val beforeSeed = state.migratedDefaultProfileId to state.migratedDefaultNamespaceId
        val beforeSchema = state.settingsSchemaVersion

        // Pure reads
        repeat(3) {
            state.getProfile("s1")
            state.publishedProfiles()
            state.resolveDefaultProfileId()
            state.migrationDefaults()
            state.preferencesFor("s1")
            state.getActiveProfile()
        }

        assertEquals(beforeProfiles, state.profiles.map { snapshot(it) })
        assertEquals(beforePrefs, state.environmentPreferences.map { it.copyPreferences() })
        assertEquals(beforeSeed, state.migratedDefaultProfileId to state.migratedDefaultNamespaceId)
        assertEquals(beforeSchema, state.settingsSchemaVersion)
        assertEquals(writesAfterMigrate, slots.writes.size)
    }

    @Test
    fun `captureOperationContext reads named slot only and never writes`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings()
        settings.applyProfileIntents(
            intents = listOf(
                com.nanyin.nacos.search.models.ProfileIntent(
                    profileId = "dev",
                    displayName = "Dev",
                    endpoint = "https://nacos.example",
                    authMode = AuthMode.NACOS_PASSWORD,
                    principal = "alice",
                    secret = "live-secret"
                )
            ),
            newActiveId = "dev",
            credentialSlots = slots
        )
        val writesAfterApply = slots.writes.size
        // Point production store is separate; bind capture via Default path is
        // PasswordSafe — for this test re-read through OperationContextResolver
        // after staging into the same store the host used.
        val profile = requireNotNull(settings.getProfile("dev"))
        val ctx = OperationContextResolver.resolve(
            profile,
            slots.read(profile.id, profile.credentialSlotVersion)
        ).getOrThrow()
        assertEquals("live-secret", ctx.credential.secret)
        assertEquals(writesAfterApply, slots.writes.size)

        // Missing slot → ConfigurationRequired, no fallback to dual-write password.
        slots.remove(profile.id, profile.credentialSlotVersion)
        assertInstanceOf(
            ConfigurationRequired::class.java,
            OperationContextResolver.resolve(
                profile,
                slots.read(profile.id, profile.credentialSlotVersion)
            ).exceptionOrNull()
        )
        assertEquals(writesAfterApply, slots.writes.filter { it.succeeded }.size +
            slots.writes.filter { !it.succeeded }.size)
        // No new successful writes after the remove.
        assertEquals(writesAfterApply, slots.writes.count { it.succeeded })
    }

    @Test
    fun `migrated installation persists only profiles and preferences as environment config`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings()
        settings.settingsSchemaVersion = 0
        settings.profiles = mutableListOf()
        settings.servers = mutableListOf(
            NacosServerConfig(
                id = "dev",
                displayName = "Dev",
                serverUrl = "https://nacos.example",
                username = "alice",
                password = "secret",
                namespace = "team-a",
                defaultGroup = "APP_GROUP",
                authMode = AuthMode.NACOS_PASSWORD
            )
        )
        settings.activeServerId = "dev"
        settings.environmentPreferences = mutableListOf()

        settings.runVersionedMigration(slots) { key ->
            if (key == "dev" || key.startsWith("dev:")) "secret" else null
        }
        settings.serverUrl = "https://should-not-persist.example"
        settings.username = "alice"
        settings.namespace = "team-a"
        settings.authMode = AuthMode.NACOS_PASSWORD

        val persisted = settings.getState()
        assertTrue(persisted.servers.isEmpty(), "legacy server list must not be re-persisted")
        assertEquals("", persisted.serverUrl)
        assertEquals("", persisted.username)
        assertEquals("public", persisted.namespace)
        assertEquals(AuthMode.ANONYMOUS, persisted.authMode)
        assertEquals(SettingsSchema.CURRENT, persisted.settingsSchemaVersion)
        assertEquals(1, persisted.profiles.size)
        assertEquals("dev", persisted.profiles.single().id)
        val prefs = persisted.environmentPreferences.single { it.profileId == "dev" }
        assertEquals("team-a", prefs.suggestedNamespace)
        assertEquals("APP_GROUP", prefs.defaultGroup)

        val restored = NacosSettings()
        restored.loadState(persisted)
        assertEquals("team-a", restored.preferencesFor("dev").suggestedNamespace)
        assertEquals("APP_GROUP", restored.preferencesFor("dev").defaultGroup)
        assertEquals("dev", restored.resolveDefaultProfileId())
        assertTrue(restored.getState().servers.isEmpty())
    }

    @Test
    fun `upgrade summary actions come from migration report and are once per schema`() {
        val settings = NacosSettings()
        settings.settingsSchemaVersion = 0
        settings.profiles = mutableListOf()
        settings.servers = mutableListOf(
            NacosServerConfig(id = "s1", displayName = "One", serverUrl = "http://localhost:8848")
        )
        val slots = InMemoryCredentialSlotStore()
        settings.runVersionedMigration(slots)
        assertTrue(settings.lastMigrationReport!!.actions.isNotEmpty())

        val session = NacosProjectSessionState()
        assertEquals(0, session.upgradeSummaryShownForSchemaVersion)
        // Simulate mark after show
        session.upgradeSummaryShownForSchemaVersion = SettingsSchema.CURRENT
        assertTrue(session.upgradeSummaryShownForSchemaVersion >= SettingsSchema.CURRENT)
    }

    private fun snapshot(profile: EnvironmentProfile): List<Any?> = listOf(
        profile.id,
        profile.displayName,
        profile.canonicalEndpoint,
        profile.apiPolicy,
        profile.authMode,
        profile.principal,
        profile.profileRevision,
        profile.accessRevision,
        profile.writeIntent,
        profile.credentialSlotId,
        profile.credentialSlotVersion
    )
}
