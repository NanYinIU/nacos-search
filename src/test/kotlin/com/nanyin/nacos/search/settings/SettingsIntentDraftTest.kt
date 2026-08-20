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
 * Issue #230: the pure Settings draft owns only profile intents, ephemeral
 * secrets, and active-selection state; legacy rows are compatibility adapters.
 *
 * Seam: [NacosSettings] + [EnvironmentProfileStore] with
 * [InMemoryCredentialSlotStore]. No shared application credential service and
 * no reflection into the dialog.
 */
class SettingsIntentDraftTest {

    @Test
    fun `intent draft snapshots ordered rows without leaking mutable preferences`() {
        val preferences = EnvironmentPreferences.defaultsFor("dev")
        val draft = SettingsIntentDraft.of(
            intents = listOf(
                ProfileIntent(
                    profileId = "dev",
                    displayName = "Dev",
                    secret = "unsaved",
                    suggestedNamespace = "team-a",
                    preferences = preferences
                )
            ),
            activeProfileId = "dev",
            openBaselineActiveId = "dev"
        )

        preferences.allowCrossNamespaceNavigation = true
        val leaked = draft.snapshot().single()
        leaked.preferences.navigationDetailPrefetchEnabled = false

        val snapshot = draft.snapshot()
        assertEquals(listOf("dev"), snapshot.map { it.profileId })
        assertFalse(snapshot.single().preferences.allowCrossNamespaceNavigation)
        assertTrue(snapshot.single().preferences.navigationDetailPrefetchEnabled)
        assertEquals("unsaved", snapshot.single().secret)
        assertEquals("team-a", snapshot.single().preferences.suggestedNamespace)
        assertEquals("dev", draft.activeProfileId)
        assertEquals("dev", draft.openBaselineActiveId)
    }

    @Test
    fun `intent draft adds and duplicates rows without mutating its prior value`() {
        val original = SettingsIntentDraft.of(
            intents = listOf(ProfileIntent(profileId = "dev", displayName = "Dev", secret = "dev-secret")),
            activeProfileId = "dev"
        )

        val added = original.add(
            ProfileIntent(profileId = "prod", displayName = "Prod", secret = "prod-secret")
        )
        val duplicated = added.duplicate("dev", "dev-copy")

        assertEquals(listOf("dev"), original.snapshot().map { it.profileId })
        assertEquals(listOf("dev", "prod"), added.snapshot().map { it.profileId })
        assertEquals(listOf("dev", "prod", "dev-copy"), duplicated.snapshot().map { it.profileId })
        assertEquals("Dev (copy)", duplicated.snapshot().last().displayName)
        assertEquals("dev-secret", duplicated.snapshot().last().secret)
        assertEquals("dev-copy", duplicated.snapshot().last().preferences.profileId)
    }

    @Test
    fun `intent draft removes and reorders rows while keeping selection valid`() {
        val draft = SettingsIntentDraft.of(
            intents = listOf(
                ProfileIntent(profileId = "dev"),
                ProfileIntent(profileId = "qa"),
                ProfileIntent(profileId = "prod")
            ),
            activeProfileId = "qa",
            openBaselineActiveId = "qa"
        )

        val reordered = draft.reorder(listOf("prod", "qa", "dev"))
        val removed = reordered.remove("qa")

        assertEquals(listOf("prod", "qa", "dev"), reordered.snapshot().map { it.profileId })
        assertEquals("qa", reordered.activeProfileId)
        assertEquals(listOf("prod", "dev"), removed.snapshot().map { it.profileId })
        assertEquals("prod", removed.activeProfileId)
        assertEquals("qa", removed.openBaselineActiveId)
    }

    @Test
    fun `intent draft replaces updates and changes secrets with synchronized namespace`() {
        val original = SettingsIntentDraft.of(
            intents = listOf(ProfileIntent(profileId = "dev", secret = "old")),
            activeProfileId = "dev"
        )

        val replaced = original.replace(
            "dev",
            ProfileIntent(
                profileId = "dev",
                displayName = "Development",
                suggestedNamespace = "team-a",
                preferences = EnvironmentPreferences(
                    profileId = "wrong",
                    suggestedNamespace = "drifted"
                )
            )
        )
        val updated = replaced.update("dev") {
            it.copy(endpoint = "https://dev.example", suggestedNamespace = "team-b")
        }
        val changedSecret = updated.changeSecret("dev", "rotated")

        assertEquals("old", original.snapshot().single().secret)
        assertEquals("team-a", replaced.snapshot().single().preferences.suggestedNamespace)
        assertEquals("dev", replaced.snapshot().single().preferences.profileId)
        assertEquals("https://dev.example", updated.snapshot().single().endpoint)
        assertEquals("team-b", updated.snapshot().single().preferences.suggestedNamespace)
        assertEquals("rotated", changedSecret.snapshot().single().secret)
    }

    @Test
    fun `intent native load reads published profiles preferences and revision pinned secrets only`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = seededSettings(slots)
        settings.servers.single().apply {
            serverUrl = "https://unpublished.example"
            password = "unpublished-secret"
        }
        val writesBefore = slots.writes.size

        val draft = settings.loadIntentDraft(
            credentialSlots = slots,
            activeProfileId = "dev",
            openBaselineActiveId = "dev"
        )

        val intent = draft.snapshot().single()
        assertEquals("https://nacos.example", intent.endpoint)
        assertEquals("secret-1", intent.secret)
        assertEquals("public", intent.suggestedNamespace)
        assertEquals(intent.suggestedNamespace, intent.preferences.suggestedNamespace)
        assertEquals(writesBefore, slots.writes.size)
        assertTrue(slots.reads.any { it == CredentialSlotObservation.Read("dev", 1L, hit = true) })
    }

    @Test
    fun `intent native classification uses the open active baseline and writes nothing`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = twoProfileSettings(slots)
        val writesBefore = slots.writes.size
        val draft = settings.loadIntentDraft(
            credentialSlots = slots,
            activeProfileId = "dev",
            openBaselineActiveId = "dev"
        )

        val unchanged = settings.classifyIntentDraft(draft, slots)
        val selected = settings.classifyIntentDraft(draft.selectActive("prod"), slots)

        assertTrue(unchanged.isNoOp())
        assertFalse(unchanged.isModified())
        assertTrue(selected.activeProfileIdChanged)
        assertTrue(selected.isOperationalChange())
        assertEquals(writesBefore, slots.writes.size)
    }

    @Test
    fun `intent native defaults reset one row in memory and preserve its identity and name`() {
        val settings = NacosSettings()
        val default = settings.defaultProfileIntent("custom", "Keep Name")
        val draft = SettingsIntentDraft.of(
            intents = listOf(
                default.copy(
                    endpoint = "https://changed.example",
                    secret = "unsaved",
                    writeIntent = true,
                    suggestedNamespace = "changed"
                )
            ),
            activeProfileId = "custom"
        )

        val reset = draft.reset("custom", default)
        val intent = reset.snapshot().single()

        assertEquals("custom", intent.profileId)
        assertEquals("Keep Name", intent.displayName)
        assertEquals("http://localhost:8848", intent.endpoint)
        assertEquals(AuthMode.NACOS_PASSWORD, intent.authMode)
        assertEquals("", intent.secret)
        assertFalse(intent.writeIntent)
        assertEquals("public", intent.suggestedNamespace)
        assertEquals("public", intent.preferences.suggestedNamespace)
        assertEquals("DEFAULT_GROUP", intent.preferences.defaultGroup)
    }

    @Test
    fun `legacy draft adapters have parity with intent native load classify and defaults`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = seededSettings(slots)
        settings.servers.single().apply {
            serverUrl = "https://unpublished.example"
            password = "unpublished-secret"
        }

        val native = settings.loadIntentDraft(slots, "dev", "dev")
        val legacyRows = settings.loadSettingsDraft(slots)

        assertEquals(native.snapshot(), settings.intentsFromDraft(legacyRows))
        assertEquals(
            settings.classifyIntentDraft(native, slots),
            settings.classifyDraft(legacyRows, "dev", "dev", slots)
        )
        assertEquals(
            settings.defaultProfileIntent("new", "New"),
            ProfileIntent.fromServerConfig(settings.defaultDraftRow("new", "New"))
        )
    }

    @Test
    fun `open cancel and reset without apply keep unsaved secrets out of persisted state`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = seededSettings(slots)
        val writesBefore = slots.writes.size

        val edited = settings.loadIntentDraft(slots).changeSecret("dev", "unsaved-secret")
        val reset = settings.loadIntentDraft(slots)

        assertEquals("unsaved-secret", edited.snapshot().single().secret)
        assertEquals("secret-1", reset.snapshot().single().secret)
        assertEquals("secret-1", slots.read("dev", 1L))
        assertEquals(writesBefore, slots.writes.size)
        val persistedXml = com.intellij.openapi.util.JDOMUtil.writeElement(
            com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState())
        )
        assertFalse(persistedXml.contains("unsaved-secret"))
        assertTrue(settings.getState().servers.isEmpty())
    }

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
