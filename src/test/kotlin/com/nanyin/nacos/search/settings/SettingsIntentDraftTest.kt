package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.ProfileIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Issue #106: settings drafts are intent-only and cancel-safe; classification
 * and defaults are owned by the store / complete persisted shape.
 *
 * Seam: [NacosSettings] + [EnvironmentProfileStore] with
 * [InMemoryCredentialSlotStore]. No shared application credential service and
 * no reflection into the dialog.
 */
class SettingsIntentDraftTest {

    @Test
    fun `draft intents omit revisions and credential slot identity`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = seededSettings(slots)

        val draft = settings.loadSettingsDraft(slots)
        val intents = settings.intentsFromDraft(draft)

        assertEquals(1, intents.size)
        val fields = ProfileIntent::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fields.any { it.contains("revision", ignoreCase = true) })
        assertFalse(fields.any { it.contains("credential", ignoreCase = true) })
        assertFalse(fields.any { it.contains("slot", ignoreCase = true) })
        assertEquals("dev", intents.single().profileId)
        assertEquals("secret-1", intents.single().secret)
        assertEquals(false, intents.single().preferences.allowCrossNamespaceNavigation)
    }

    @Test
    fun `loading a draft does not write settings or credentials`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = seededSettings(slots)
        val writesBefore = slots.writes.size
        val activeBefore = settings.activeServerId
        val profilesBefore = settings.publishedProfiles().map { it.copy() }
        val prefsBefore = settings.allEnvironmentPreferences()

        settings.loadSettingsDraft(slots)
        settings.classifyDraft(
            draft = settings.loadSettingsDraft(slots),
            draftActiveId = "dev",
            previousActiveId = "dev",
            credentialSlots = slots
        )

        assertEquals(writesBefore, slots.writes.size)
        assertEquals(activeBefore, settings.activeServerId)
        assertEquals(profilesBefore.map { it.accessRevision }, settings.publishedProfiles().map { it.accessRevision })
        assertEquals(prefsBefore, settings.allEnvironmentPreferences())
    }

    @Test
    fun `password and api policy edits classify as access changes`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = seededSettings(slots)
        val draft = settings.loadSettingsDraft(slots).map { it.copy() }.toMutableList()

        draft[0] = draft[0].copy(password = "rotated")
        val passwordClass = settings.classifyDraft(draft, "dev", "dev", slots)
        assertTrue(passwordClass.accessRevisionWouldAdvanceIds.contains("dev"))
        assertTrue(passwordClass.isOperationalChange())
        assertTrue(passwordClass.dirtyProfileIds().contains("dev"))

        draft[0] = draft[0].copy(password = "secret-1", apiPolicy = NacosApiPolicy.V3)
        val policyClass = settings.classifyDraft(draft, "dev", "dev", slots)
        assertTrue(policyClass.accessRevisionWouldAdvanceIds.contains("dev"))
        assertTrue(policyClass.isOperationalChange())
    }

    @Test
    fun `display-only edit and pure reorder do not classify as operational`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = twoProfileSettings(slots)

        val renamed = settings.loadSettingsDraft(slots).map {
            if (it.id == "dev") it.copy(displayName = "Dev (renamed)") else it.copy()
        }
        val display = settings.classifyDraft(renamed, "dev", "dev", slots)
        assertTrue(display.displayOnlyChangedIds.contains("dev"))
        assertFalse(display.isOperationalChange())
        assertTrue(display.isPreferencesOnlyChange())

        val reordered = settings.loadSettingsDraft(slots).reversed().map { it.copy() }
        val reorder = settings.classifyDraft(reordered, "dev", "dev", slots)
        assertTrue(reorder.isNoOp())
        assertFalse(reorder.isModified())
        assertTrue(reorder.dirtyProfileIds().isEmpty())
    }

    @Test
    fun `preference-only edit is modified but not operational`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = seededSettings(slots)
        val draft = settings.loadSettingsDraft(slots).map {
            it.copy(allowCrossNamespaceNavigation = true)
        }

        val classification = settings.classifyDraft(draft, "dev", "dev", slots)
        assertTrue(classification.preferenceChangedIds.contains("dev"))
        assertFalse(classification.isOperationalChange())
        assertTrue(classification.isModified())
    }

    @Test
    fun `active change against open baseline is modified`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = twoProfileSettings(slots)
        val draft = settings.loadSettingsDraft(slots)

        // Open baseline was "dev"; user set active to "prod".
        val classification = settings.classifyDraft(draft, "prod", previousActiveId = "dev", slots)
        assertTrue(classification.activeProfileIdChanged)
        assertTrue(classification.isOperationalChange())
        assertTrue(classification.isModified())

        // Same active as open baseline: not modified for active alone.
        val same = settings.classifyDraft(draft, "dev", previousActiveId = "dev", slots)
        assertFalse(same.activeProfileIdChanged)
        assertTrue(same.isNoOp())
    }

    @Test
    fun `removal is classified but not executed until apply`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = twoProfileSettings(slots)
        val draft = settings.loadSettingsDraft(slots).filter { it.id == "dev" }

        val classification = settings.classifyDraft(draft, "dev", "dev", slots)
        assertEquals(setOf("prod"), classification.removedProfileIds)
        assertTrue(classification.isOperationalChange())

        // Still published — classify does not delete.
        assertEquals(setOf("dev", "prod"), settings.publishedProfiles().map { it.id }.toSet())
        assertEquals("secret-2", slots.read("prod", 1L))
    }

    @Test
    fun `apply publishes intents and only then runs deletion lifecycle`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = twoProfileSettings(slots)
        val tombstones = InMemoryProfileTombstoneWriter()
        val cleanup = object : ProfileDeletionCleanup {
            override fun markReferringSessionsUnavailable(profileId: String) = Unit
            override fun removeCredentialSlots(profileId: String) {
                slots.removeAllForProfile(profileId)
            }
            override fun invalidateAuthentication(profileId: String) = Unit
            override fun clearLastKnownGeneration(profileId: String) = Unit
            override fun purgeCache(profileId: String) = Unit
        }
        val lifecycle = ProfileDeletionLifecycle(tombstones, cleanup)

        val draft = settings.loadSettingsDraft(slots).filter { it.id == "dev" }
        assertTrue(tombstones.entombedIds.isEmpty())

        val outcome = settings.applyProfileIntents(
            intents = settings.intentsFromDraft(draft),
            newActiveId = "dev",
            dualWriteServers = draft,
            credentialSlots = slots,
            deletionLifecycle = lifecycle
        )

        assertEquals(setOf("prod"), outcome.removedProfileIds)
        assertTrue("prod" in tombstones.entombedIds)
        assertEquals(listOf("dev"), settings.publishedProfiles().map { it.id })
        org.junit.jupiter.api.Assertions.assertNull(slots.read("prod", 1L))
    }

    @Test
    fun `reset to defaults leaves no persisted field at non-default after shape apply`() {
        val settings = NacosSettings().also { it.resetToDefaults() }
        // Mutate a sampling of fields and a preference, then reset again.
        settings.cacheEnabled = false
        settings.cacheTtlMinutes = 99
        settings.language = "zh_CN"
        settings.applyServers(
            listOf(
                NacosServerConfig(
                    id = "custom",
                    displayName = "Custom",
                    serverUrl = "https://custom.example",
                    allowCrossNamespaceNavigation = true,
                    navigationDetailPrefetchEnabled = false
                )
            ),
            "custom"
        )

        settings.resetToDefaults()

        val shape = NacosSettings.defaultPersistedShape()
        assertEquals(shape.cacheEnabled, settings.cacheEnabled)
        assertEquals(shape.cacheTtlMinutes, settings.cacheTtlMinutes)
        assertEquals(shape.language, settings.language)
        assertEquals(shape.enableTokenAuth, settings.enableTokenAuth)
        assertEquals(shape.settingsSchemaVersion, settings.settingsSchemaVersion)
        assertEquals(shape.activeServerId, settings.activeServerId)
        assertEquals(shape.migratedDefaultProfileId, settings.migratedDefaultProfileId)
        assertEquals(shape.servers.map { it.id }, settings.servers.map { it.id })
        assertEquals(shape.profiles.map { it.id }, settings.profiles.map { it.id })
        val prefs = settings.preferencesFor(settings.activeServerId)
        val defaultPrefs = EnvironmentPreferences.defaultsFor(settings.activeServerId)
        assertEquals(defaultPrefs.allowCrossNamespaceNavigation, prefs.allowCrossNamespaceNavigation)
        assertEquals(defaultPrefs.navigationDetailPrefetchEnabled, prefs.navigationDetailPrefetchEnabled)
    }

    @Test
    fun `default draft row derives from complete persisted shape`() {
        val settings = NacosSettings()
        val row = settings.defaultDraftRow("keep-me", "Kept Name")
        val shapeSeed = NacosSettings.defaultPersistedShape().servers.first()

        assertEquals("keep-me", row.id)
        assertEquals("Kept Name", row.displayName)
        assertEquals(shapeSeed.serverUrl, row.serverUrl)
        assertEquals(shapeSeed.apiPolicy, row.apiPolicy)
        assertEquals(shapeSeed.authMode, row.authMode)
        assertEquals(shapeSeed.defaultGroup, row.defaultGroup)
        assertEquals(shapeSeed.allowCrossNamespaceNavigation, row.allowCrossNamespaceNavigation)
        assertEquals(shapeSeed.navigationDetailPrefetchEnabled, row.navigationDetailPrefetchEnabled)
        assertEquals(shapeSeed.writeIntent, row.writeIntent)
    }

    private fun seededSettings(slots: InMemoryCredentialSlotStore): NacosSettings {
        val settings = NacosSettings()
        settings.applyProfileIntents(
            intents = listOf(
                ProfileIntent(
                    profileId = "dev",
                    displayName = "Dev",
                    endpoint = "https://nacos.example",
                    apiPolicy = NacosApiPolicy.AUTO,
                    authMode = AuthMode.NACOS_PASSWORD,
                    principal = "alice",
                    secret = "secret-1",
                    suggestedNamespace = "public",
                    preferences = EnvironmentPreferences.defaultsFor("dev")
                )
            ),
            newActiveId = "dev",
            credentialSlots = slots,
            deletionLifecycle = noOpLifecycle(slots)
        )
        return settings
    }

    private fun twoProfileSettings(slots: InMemoryCredentialSlotStore): NacosSettings {
        val settings = NacosSettings()
        settings.applyProfileIntents(
            intents = listOf(
                ProfileIntent(
                    profileId = "dev",
                    displayName = "Dev",
                    endpoint = "https://nacos.example",
                    authMode = AuthMode.NACOS_PASSWORD,
                    principal = "alice",
                    secret = "secret-1",
                    preferences = EnvironmentPreferences.defaultsFor("dev")
                ),
                ProfileIntent(
                    profileId = "prod",
                    displayName = "Prod",
                    endpoint = "https://prod.example",
                    authMode = AuthMode.NACOS_PASSWORD,
                    principal = "bob",
                    secret = "secret-2",
                    preferences = EnvironmentPreferences.defaultsFor("prod")
                )
            ),
            newActiveId = "dev",
            credentialSlots = slots,
            deletionLifecycle = noOpLifecycle(slots)
        )
        return settings
    }

    private fun noOpLifecycle(slots: InMemoryCredentialSlotStore): ProfileDeletionLifecycle =
        ProfileDeletionLifecycle(
            InMemoryProfileTombstoneWriter(),
            object : ProfileDeletionCleanup {
                override fun markReferringSessionsUnavailable(profileId: String) = Unit
                override fun removeCredentialSlots(profileId: String) {
                    slots.removeAllForProfile(profileId)
                }
                override fun invalidateAuthentication(profileId: String) = Unit
                override fun clearLastKnownGeneration(profileId: String) = Unit
                override fun purgeCache(profileId: String) = Unit
            }
        )
}
