package com.nanyin.nacos.search.settings

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.ProfileIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Issue #233 contract tests at the published settings, migration, intent-draft,
 * credential-slot, and project-session seams.
 */
@TestApplication
class LegacySettingsContractTest {

    @Test
    fun `Apply publishes profiles without repopulating legacy environment mirrors`() {
        val slots = InMemoryCredentialSlotStore().also { it.stage("dev", 1, "secret") }
        val settings = publishedSettings(
            profiles = listOf(profile("dev")),
            preferences = listOf(EnvironmentPreferences.defaultsFor("dev")),
            defaultProfileId = "dev"
        )
        settings.clearLegacyInputs()

        settings.applyProfileIntents(
            intents = listOf(intent("dev", secret = "secret")),
            newActiveId = "dev",
            credentialSlots = slots
        )

        assertTrue(settings.servers.isEmpty())
        assertEquals("", settings.activeServerId)
        assertEquals("", settings.serverUrl)
        assertEquals("", settings.username)
        assertEquals("", settings.password)
        assertEquals("public", settings.namespace)
        assertEquals(AuthMode.ANONYMOUS, settings.authMode)
    }

    @Test
    fun `CURRENT state ignores and discards stale legacy environment inputs`() {
        val slots = InMemoryCredentialSlotStore().also {
            it.stage("first", 4, "first-secret")
            it.stage("second", 6, "second-secret")
        }
        val first = profile("first", profileRevision = 9, accessRevision = 7, slotVersion = 4)
        val second = profile("second", profileRevision = 11, accessRevision = 8, slotVersion = 6)
        val settings = publishedSettings(
            profiles = listOf(first, second),
            preferences = emptyList(),
            defaultProfileId = ""
        ).apply {
            servers = mutableListOf(
                NacosServerConfig(
                    id = "second",
                    displayName = "Stale",
                    serverUrl = "https://stale.example",
                    namespace = "stale-namespace",
                    defaultGroup = "STALE_GROUP"
                )
            )
            activeServerId = "second"
            serverUrl = "https://flat-stale.example"
            username = "stale-user"
            password = "stale-secret"
            namespace = "flat-stale"
            authMode = AuthMode.BEARER_TOKEN
        }

        val report = settings.runVersionedMigration(slots)

        assertEquals(listOf(first, second), report.profiles)
        assertEquals("first", report.defaultProfileId)
        assertEquals(
            listOf(
                EnvironmentPreferences.defaultsFor("first"),
                EnvironmentPreferences.defaultsFor("second")
            ),
            report.preferences
        )
        assertEquals(0, report.credentialSlotWrites)
        assertTrue(settings.servers.isEmpty())
        assertEquals("", settings.activeServerId)
        assertEquals("", settings.serverUrl)
        assertEquals("", settings.username)
        assertEquals("", settings.password)
        assertEquals("public", settings.namespace)
        assertEquals(AuthMode.ANONYMOUS, settings.authMode)
    }

    @Test
    fun `validation reads published profiles and ignores flat endpoint inputs`() {
        val settings = publishedSettings(
            profiles = listOf(profile("dev")),
            preferences = listOf(EnvironmentPreferences.defaultsFor("dev")),
            defaultProfileId = "dev"
        ).apply {
            serverUrl = ""
        }

        assertTrue(settings.validate().isEmpty())

        settings.profiles.single().canonicalEndpoint = "not a url"

        assertTrue(settings.validate().contains("Invalid server URL format"))
        assertFalse(settings.isValid())
    }

    @Test
    fun `persisted CURRENT state omits every legacy environment input`() {
        val settings = publishedSettings(
            profiles = listOf(profile("dev")),
            preferences = listOf(EnvironmentPreferences.defaultsFor("dev")),
            defaultProfileId = "dev"
        ).apply {
            servers = mutableListOf(NacosServerConfig(id = "stale"))
            activeServerId = "stale"
            serverUrl = "https://stale.example"
            username = "stale-user"
            password = "stale-secret"
            namespace = "stale-namespace"
            authMode = AuthMode.BEARER_TOKEN
            enableTokenAuth = false
        }

        val xml = com.intellij.openapi.util.JDOMUtil.writeElement(
            com.intellij.util.xmlb.XmlSerializer.serialize(
                settings.getState(),
                com.intellij.util.xmlb.SkipDefaultsSerializationFilter()
            )
        )

        listOf(
            "servers",
            "activeServerId",
            "serverUrl",
            "username",
            "password",
            "namespace",
            "authMode",
            "enableTokenAuth"
        ).forEach { legacyName ->
            assertFalse(
                xml.contains(legacyName),
                "$legacyName must be absent from persisted CURRENT state: $xml"
            )
        }
    }

    @Test
    fun `flat server-list intermediate-profile and CURRENT migration shapes are idempotent`() {
        assertFlatOnlyShapeIsIdempotent()
        assertDefaultSeedShapeIsIdempotent()
        assertOmittedAuthShapeIsIdempotent()

        val serverSlots = InMemoryCredentialSlotStore()
        val serverInput = SettingsMigrationInput(
            schemaVersion = 0,
            servers = listOf(
                NacosServerConfig(
                    id = "server-list",
                    displayName = "Server list",
                    serverUrl = "https://server-list.example",
                    password = "server-secret",
                    namespace = "team-server",
                    authMode = AuthMode.ANONYMOUS
                )
            ),
            activeServerId = "server-list",
            flatNamespace = "ignored-flat",
            profiles = emptyList(),
            preferences = emptyList(),
            defaultProfileId = "",
            defaultNamespaceId = ""
        )
        assertMigrationIdempotent(
            input = serverInput,
            slots = serverSlots,
            expectedProfileRevision = 1,
            expectedAccessRevision = 1,
            expectedSlotVersion = 1,
            expectedSecret = "server-secret"
        )

        val intermediateSlots = InMemoryCredentialSlotStore()
        val intermediate = profile(
            id = "intermediate",
            profileRevision = 12,
            accessRevision = 8,
            slotVersion = 5
        )
        val intermediateInput = SettingsMigrationInput(
            schemaVersion = 0,
            servers = listOf(
                NacosServerConfig(
                    id = "intermediate",
                    serverUrl = "https://legacy-must-not-replace.example",
                    password = "intermediate-secret",
                    namespace = "team-intermediate"
                )
            ),
            activeServerId = "intermediate",
            flatNamespace = "ignored-flat",
            profiles = listOf(intermediate),
            preferences = emptyList(),
            defaultProfileId = "intermediate",
            defaultNamespaceId = "team-intermediate"
        )
        assertMigrationIdempotent(
            input = intermediateInput,
            slots = intermediateSlots,
            expectedProfileRevision = 12,
            expectedAccessRevision = 8,
            expectedSlotVersion = 5,
            expectedSecret = "intermediate-secret"
        )

        val currentSlots = InMemoryCredentialSlotStore().also {
            it.stage("current", 7, "published-secret")
        }
        val current = profile(
            id = "current",
            profileRevision = 14,
            accessRevision = 10,
            slotVersion = 7
        )
        val currentInput = SettingsMigrationInput(
            schemaVersion = SettingsSchema.CURRENT,
            servers = listOf(
                NacosServerConfig(
                    id = "current",
                    serverUrl = "https://stale.example",
                    password = "stale-secret",
                    namespace = "stale-namespace"
                )
            ),
            activeServerId = "current",
            flatNamespace = "flat-stale",
            profiles = listOf(current),
            preferences = listOf(EnvironmentPreferences.defaultsFor("current")),
            defaultProfileId = "current",
            defaultNamespaceId = "public"
        )
        assertMigrationIdempotent(
            input = currentInput,
            slots = currentSlots,
            expectedProfileRevision = 14,
            expectedAccessRevision = 10,
            expectedSlotVersion = 7,
            expectedSecret = "published-secret"
        )
    }

    @Test
    fun `opening cancelling and runtime reads ignore mutated legacy inputs`() {
        val slots = InMemoryCredentialSlotStore().also {
            it.stage("dev", 1, "dev-secret")
            it.stage("prod", 1, "prod-secret")
        }
        val settings = publishedSettings(
            profiles = listOf(profile("dev"), profile("prod")),
            preferences = listOf(
                EnvironmentPreferences(
                    profileId = "dev",
                    allowCrossNamespaceNavigation = false,
                    navigationDetailPrefetchEnabled = false,
                    suggestedNamespace = "team-dev"
                ),
                EnvironmentPreferences(
                    profileId = "prod",
                    allowCrossNamespaceNavigation = true,
                    navigationDetailPrefetchEnabled = true,
                    suggestedNamespace = "team-prod"
                )
            ),
            defaultProfileId = "prod"
        ).apply {
            servers = mutableListOf(
                NacosServerConfig(
                    id = "dev",
                    displayName = "Legacy",
                    serverUrl = "https://legacy.example",
                    namespace = "legacy-ns",
                    allowCrossNamespaceNavigation = true,
                    navigationDetailPrefetchEnabled = false
                )
            )
            activeServerId = "dev"
            serverUrl = "https://flat.example"
            namespace = "flat-ns"
        }
        val profilesBefore = settings.publishedProfiles()
        val preferencesBefore = settings.allEnvironmentPreferences()

        val opened = settings.loadIntentDraft(slots)
        val cancelled = opened.changeSecret("prod", "unsaved")
        val session = NacosProjectSessionState()
        session.ensureInitialized(settings.defaultPublishedEnvironment())

        assertEquals("prod", opened.activeProfileId)
        assertEquals("unsaved", cancelled.snapshot().first { it.profileId == "prod" }.secret)
        assertEquals("prod-secret", slots.read("prod", 1))
        assertEquals(profilesBefore, settings.publishedProfiles())
        assertEquals(preferencesBefore, settings.allEnvironmentPreferences())
        assertTrue(settings.preferencesFor("").allowCrossNamespaceNavigation)
        assertTrue(settings.navigationDetailPrefetchEnabled())
        assertEquals("prod", session.selectedProfileId)
        assertEquals("team-prod", session.namespaceId)
    }

    private fun assertOmittedAuthShapeIsIdempotent() {
        val slots = InMemoryCredentialSlotStore()
        val input = SettingsMigrationInput(
            schemaVersion = 0,
            servers = listOf(
                NacosServerConfig(
                    id = "omitted-auth",
                    serverUrl = "https://omitted-auth.example"
                )
            ),
            activeServerId = "omitted-auth",
            flatNamespace = "public",
            profiles = emptyList(),
            preferences = emptyList(),
            defaultProfileId = "",
            defaultNamespaceId = ""
        )

        assertEquals(
            AuthMode.ANONYMOUS,
            SettingsMigrator(slots).migrate(input).profiles.single().authMode
        )
        assertMigrationIdempotent(
            input = input,
            slots = slots,
            expectedProfileRevision = 1,
            expectedAccessRevision = 1,
            expectedSlotVersion = 1,
            expectedSecret = null
        )
    }

    private fun assertDefaultSeedShapeIsIdempotent() {
        val slots = InMemoryCredentialSlotStore()
        val input = SettingsMigrationInput(
            schemaVersion = 0,
            servers = emptyList(),
            activeServerId = "",
            flatNamespace = "",
            profiles = emptyList(),
            preferences = emptyList(),
            defaultProfileId = "",
            defaultNamespaceId = ""
        )

        val first = SettingsMigrator(slots).migrate(input)

        assertEquals("s_local", first.profiles.single().id)
        assertEquals("http://localhost:8848", first.profiles.single().canonicalEndpoint)
        assertEquals(AuthMode.NACOS_PASSWORD, first.profiles.single().authMode)
        assertMigrationIdempotent(
            input = input,
            slots = slots,
            expectedProfileRevision = 1,
            expectedAccessRevision = 1,
            expectedSlotVersion = 1,
            expectedSecret = null
        )
    }

    private fun assertFlatOnlyShapeIsIdempotent() {
        val id = "flat-${System.nanoTime()}"
        NacosCredentialStore.setDurable(id, "flat-secret")
        NacosCredentialStore.remove(CredentialSlotStore.slotKey(id, 1))
        try {
            val legacy = NacosSettings().apply {
                settingsSchemaVersion = 0
                profileMigrationCompleted = false
                credentialSlotsPublished = false
                profiles = mutableListOf()
                environmentPreferences = mutableListOf()
                servers = mutableListOf()
                activeServerId = id
                serverUrl = "https://flat-only.example"
                username = "flat-user"
                password = ""
                namespace = "team-flat"
                authMode = AuthMode.ANONYMOUS
                migratedDefaultProfileId = ""
                migratedDefaultNamespaceId = ""
            }
            val first = NacosSettings().also { it.loadState(legacy) }
            val profile = first.publishedProfiles().single()
            assertEquals(id, profile.id)
            assertEquals("https://flat-only.example", profile.canonicalEndpoint)
            assertEquals(AuthMode.ANONYMOUS, profile.authMode)
            assertEquals(1, profile.profileRevision)
            assertEquals(1, profile.accessRevision)
            assertEquals(1, profile.credentialSlotVersion)
            assertEquals("flat-secret", NacosCredentialStore.get(CredentialSlotStore.slotKey(id, 1)))

            val second = NacosSettings().also { it.loadState(first.getState()) }
            assertEquals(first.publishedProfiles(), second.publishedProfiles())
            assertEquals(first.allEnvironmentPreferences(), second.allEnvironmentPreferences())
            assertEquals(first.resolveDefaultProfileId(), second.resolveDefaultProfileId())
        } finally {
            NacosCredentialStore.remove(id)
            NacosCredentialStore.remove(CredentialSlotStore.slotKey(id, 1))
        }
    }

    private fun assertMigrationIdempotent(
        input: SettingsMigrationInput,
        slots: InMemoryCredentialSlotStore,
        expectedProfileRevision: Long,
        expectedAccessRevision: Long,
        expectedSlotVersion: Long,
        expectedSecret: String?
    ) {
        val first = SettingsMigrator(slots).migrate(input)
        val profile = first.profiles.single()
        assertEquals(expectedProfileRevision, profile.profileRevision)
        assertEquals(expectedAccessRevision, profile.accessRevision)
        assertEquals(expectedSlotVersion, profile.credentialSlotVersion)
        assertEquals(expectedSecret, slots.read(profile.id, expectedSlotVersion))

        val writesBeforeSecond = slots.writes.size
        val second = SettingsMigrator(slots).migrate(
            input.copy(
                schemaVersion = SettingsSchema.CURRENT,
                profiles = first.profiles,
                preferences = first.preferences,
                defaultProfileId = first.defaultProfileId,
                defaultNamespaceId = first.defaultNamespaceId
            )
        )
        assertEquals(first.profiles, second.profiles)
        assertEquals(first.preferences, second.preferences)
        assertEquals(first.defaultProfileId, second.defaultProfileId)
        assertEquals(first.defaultNamespaceId, second.defaultNamespaceId)
        assertEquals(0, second.credentialSlotWrites)
        assertEquals(writesBeforeSecond, slots.writes.size)
    }

    private fun publishedSettings(
        profiles: List<EnvironmentProfile>,
        preferences: List<EnvironmentPreferences>,
        defaultProfileId: String
    ): NacosSettings = NacosSettings().apply {
        settingsSchemaVersion = SettingsSchema.CURRENT
        profileMigrationCompleted = true
        credentialSlotsPublished = true
        this.profiles = profiles.toMutableList()
        environmentPreferences = preferences.toMutableList()
        migratedDefaultProfileId = defaultProfileId
        migratedDefaultNamespaceId = "public"
    }

    private fun profile(
        id: String,
        profileRevision: Long = 1,
        accessRevision: Long = 1,
        slotVersion: Long = 1
    ): EnvironmentProfile = EnvironmentProfile(
        id = id,
        displayName = id,
        canonicalEndpoint = "https://$id.example",
        authMode = AuthMode.ANONYMOUS,
        profileRevision = profileRevision,
        accessRevision = accessRevision,
        credentialSlotId = CredentialSlotStore.slotKey(id, slotVersion),
        credentialSlotVersion = slotVersion
    )

    private fun intent(id: String, secret: String): ProfileIntent = ProfileIntent(
        profileId = id,
        displayName = id,
        endpoint = "https://$id.example",
        authMode = AuthMode.ANONYMOUS,
        secret = secret,
        preferences = EnvironmentPreferences.defaultsFor(id)
    )

    private fun NacosSettings.clearLegacyInputs() {
        servers = mutableListOf()
        activeServerId = ""
        serverUrl = ""
        username = ""
        password = ""
        namespace = "public"
        authMode = AuthMode.ANONYMOUS
    }
}
