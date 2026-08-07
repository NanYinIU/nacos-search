package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.models.ProfileIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Issue #103 / ADR-0049 / ADR-0024 / ADR-0035: profile intents publish through
 * one revision-owning store. Tests drive [InMemoryCredentialSlotStore] so the
 * credential seam is explicit and failures stay fail-closed.
 */
class EnvironmentProfileStoreTest {

    @Test
    fun `profile intent type cannot express revisions or credential slot identity`() {
        // Compile-time shape check: ProfileIntent has only user-chosen fields.
        val fields = ProfileIntent::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fields.any { it.contains("revision", ignoreCase = true) })
        assertFalse(fields.any { it.contains("credential", ignoreCase = true) })
        assertFalse(fields.any { it.contains("slot", ignoreCase = true) })
        assertTrue("profileId" in fields || fields.any { it == "profileId" })
    }

    @Test
    fun `addition stages credential and publishes profile at revision 1`() {
        val slots = InMemoryCredentialSlotStore()
        val store = EnvironmentProfileStore(slots)

        val outcome = store.applyIntents(
            intents = listOf(intent(id = "dev", secret = "s1", principal = "alice")),
            activeProfileId = "dev",
            previousProfiles = emptyList()
        )

        assertEquals(setOf("dev"), outcome.addedProfileIds)
        assertTrue(outcome.removedProfileIds.isEmpty())
        assertTrue(outcome.isOperationalChange())
        val published = outcome.publishedProfiles.single()
        assertEquals("dev", published.id)
        assertEquals(1L, published.profileRevision)
        assertEquals(1L, published.accessRevision)
        assertEquals("dev:v1", published.credentialSlotId)
        assertEquals(1L, published.credentialSlotVersion)
        assertEquals("s1", slots.read("dev", 1L))
        assertEquals(1, slots.writes.count { it.succeeded })
    }

    @Test
    fun `endpoint change advances both profile and access revisions`() {
        val (store, slots, previous) = seeded("dev", secret = "s1")

        val outcome = store.applyIntents(
            intents = listOf(intent(id = "dev", endpoint = "https://other.example", secret = "s1", principal = "alice")),
            activeProfileId = "dev",
            previousProfiles = listOf(previous)
        )

        val published = outcome.publishedProfiles.single()
        assertEquals(previous.profileRevision + 1, published.profileRevision)
        assertEquals(previous.accessRevision + 1, published.accessRevision)
        assertEquals(setOf("dev"), outcome.accessRevisionAdvancedIds)
        assertEquals(setOf("dev"), outcome.profileRevisionAdvancedIds)
        assertEquals(previous.credentialSlotVersion, published.credentialSlotVersion)
        assertEquals("s1", slots.read("dev", published.credentialSlotVersion))
    }

    @Test
    fun `api policy auth strategy and principal each advance both revisions`() {
        val cases = listOf(
            intent(id = "dev", secret = "s1", principal = "alice", apiPolicy = NacosApiPolicy.V3),
            intent(id = "dev", secret = "s1", principal = "alice", authMode = AuthMode.HTTP_BASIC),
            intent(id = "dev", secret = "s1", principal = "bob")
        )
        for (changed in cases) {
            val (store, _, previous) = seeded("dev", secret = "s1")
            val outcome = store.applyIntents(
                intents = listOf(changed),
                activeProfileId = "dev",
                previousProfiles = listOf(previous)
            )
            val published = outcome.publishedProfiles.single()
            assertEquals(previous.accessRevision + 1, published.accessRevision, changed.toString())
            assertEquals(previous.profileRevision + 1, published.profileRevision, changed.toString())
            assertTrue("dev" in outcome.accessRevisionAdvancedIds, changed.toString())
        }
    }

    @Test
    fun `secret change stages new slot before publishing advanced revisions`() {
        val (store, slots, previous) = seeded("dev", secret = "old")

        val outcome = store.applyIntents(
            intents = listOf(intent(id = "dev", secret = "new", principal = "alice")),
            activeProfileId = "dev",
            previousProfiles = listOf(previous)
        )

        val published = outcome.publishedProfiles.single()
        assertEquals(previous.accessRevision + 1, published.accessRevision)
        assertEquals(previous.profileRevision + 1, published.profileRevision)
        assertEquals(previous.credentialSlotVersion + 1, published.credentialSlotVersion)
        // New slot is durable and named by the new version.
        assertEquals("new", slots.read("dev", published.credentialSlotVersion))
        // Predecessor remains (immutable history) — never overwritten.
        assertEquals("old", slots.read("dev", previous.credentialSlotVersion))
        // Stage for the new version succeeded before visibility.
        assertTrue(
            slots.writes.any {
                it.accessRevision == published.credentialSlotVersion && it.succeeded
            }
        )
        assertEquals(setOf("dev"), outcome.accessRevisionAdvancedIds)
    }

    @Test
    fun `write intent advances only the profile revision`() {
        val (store, _, previous) = seeded("dev", secret = "s1")

        val outcome = store.applyIntents(
            intents = listOf(intent(id = "dev", secret = "s1", principal = "alice", writeIntent = true)),
            activeProfileId = "dev",
            previousProfiles = listOf(previous)
        )

        val published = outcome.publishedProfiles.single()
        assertEquals(previous.profileRevision + 1, published.profileRevision)
        assertEquals(previous.accessRevision, published.accessRevision)
        assertEquals(setOf("dev"), outcome.profileRevisionAdvancedIds)
        assertTrue(outcome.accessRevisionAdvancedIds.isEmpty())
        assertTrue(outcome.isOperationalChange())
    }

    @Test
    fun `display name and suggested namespace advance neither revision`() {
        val (store, _, previous) = seeded("dev", secret = "s1")

        val outcome = store.applyIntents(
            intents = listOf(
                intent(
                    id = "dev",
                    secret = "s1",
                    principal = "alice",
                    displayName = "Renamed",
                    suggestedNamespace = "team-a"
                )
            ),
            activeProfileId = "dev",
            previousProfiles = listOf(previous),
            previousActiveId = "dev",
            previousSuggestedNamespaces = mapOf("dev" to "public")
        )

        val published = outcome.publishedProfiles.single()
        assertEquals(previous.profileRevision, published.profileRevision)
        assertEquals(previous.accessRevision, published.accessRevision)
        assertEquals("Renamed", published.displayName)
        assertEquals(setOf("dev"), outcome.displayOnlyChangedIds)
        assertEquals(setOf("dev"), outcome.suggestedNamespaceChangedIds)
        assertTrue(outcome.profileRevisionAdvancedIds.isEmpty())
        assertTrue(outcome.accessRevisionAdvancedIds.isEmpty())
        // Namespace still operational for session recapture.
        assertTrue(outcome.isOperationalChange())
    }

    @Test
    fun `preference only change advances neither revision and is not operational`() {
        val (store, _, previous) = seeded("dev", secret = "s1")
        val previousPrefs = listOf(EnvironmentPreferences.defaultsFor("dev"))

        val outcome = store.applyIntents(
            intents = listOf(
                intent(
                    id = "dev",
                    secret = "s1",
                    principal = "alice",
                    preferences = EnvironmentPreferences(
                        profileId = "dev",
                        allowCrossNamespaceNavigation = true,
                        navigationDetailPrefetchEnabled = false
                    )
                )
            ),
            activeProfileId = "dev",
            previousProfiles = listOf(previous),
            previousPreferences = previousPrefs,
            previousActiveId = "dev",
            previousSuggestedNamespaces = mapOf("dev" to "public")
        )

        val published = outcome.publishedProfiles.single()
        assertEquals(previous.profileRevision, published.profileRevision)
        assertEquals(previous.accessRevision, published.accessRevision)
        assertEquals(setOf("dev"), outcome.preferenceChangedIds)
        assertFalse(outcome.isOperationalChange())
        assertTrue(outcome.isPreferencesOnlyChange())
    }

    @Test
    fun `reordering intents advances no revision and is not operational`() {
        val slots = InMemoryCredentialSlotStore()
        val store = EnvironmentProfileStore(slots)
        val first = publish(store, intent(id = "a", secret = "sa", displayName = "A"))
        val second = publish(
            store,
            intent(id = "a", secret = "sa", displayName = "A"),
            intent(id = "b", secret = "sb", displayName = "B"),
            previous = first.publishedProfiles,
            previousPrefs = first.publishedPreferences,
            previousNs = first.suggestedNamespaces,
            active = "a"
        )
        val reordered = store.applyIntents(
            intents = listOf(
                intent(id = "b", secret = "sb", displayName = "B"),
                intent(id = "a", secret = "sa", displayName = "A")
            ),
            activeProfileId = "a",
            previousProfiles = second.publishedProfiles,
            previousPreferences = second.publishedPreferences,
            previousActiveId = "a",
            previousSuggestedNamespaces = second.suggestedNamespaces
        )

        assertEquals(listOf("b", "a"), reordered.publishedProfiles.map { it.id })
        assertEquals(
            second.publishedProfiles.associate { it.id to it.profileRevision },
            reordered.publishedProfiles.associate { it.id to it.profileRevision }
        )
        assertEquals(
            second.publishedProfiles.associate { it.id to it.accessRevision },
            reordered.publishedProfiles.associate { it.id to it.accessRevision }
        )
        assertTrue(reordered.profileRevisionAdvancedIds.isEmpty())
        assertTrue(reordered.accessRevisionAdvancedIds.isEmpty())
        assertTrue(reordered.addedProfileIds.isEmpty())
        assertTrue(reordered.removedProfileIds.isEmpty())
        assertFalse(reordered.isOperationalChange())
        assertTrue(reordered.isNoOp())
    }

    @Test
    fun `unchanged write is idempotent and reports no operational change`() {
        val (store, slots, previous) = seeded("dev", secret = "s1")
        val prefs = listOf(EnvironmentPreferences.defaultsFor("dev"))
        val writesBefore = slots.writes.size

        val outcome = store.applyIntents(
            intents = listOf(intent(id = "dev", secret = "s1", principal = "alice")),
            activeProfileId = "dev",
            previousProfiles = listOf(previous),
            previousPreferences = prefs,
            previousActiveId = "dev",
            previousSuggestedNamespaces = mapOf("dev" to "public")
        )

        assertEquals(previous.profileRevision, outcome.publishedProfiles.single().profileRevision)
        assertEquals(previous.accessRevision, outcome.publishedProfiles.single().accessRevision)
        assertTrue(outcome.addedProfileIds.isEmpty())
        assertTrue(outcome.removedProfileIds.isEmpty())
        assertTrue(outcome.preferenceChangedIds.isEmpty())
        assertTrue(outcome.displayOnlyChangedIds.isEmpty())
        assertFalse(outcome.isOperationalChange())
        assertTrue(outcome.isNoOp() || slots.writes.size >= writesBefore)
        // Idempotent restage of the same secret may write, but outcome is still no-op.
        assertTrue(outcome.isNoOp())
    }

    @Test
    fun `credential stage failure leaves previous profile and revisions unpublished`() {
        val goodSlots = InMemoryCredentialSlotStore()
        val goodStore = EnvironmentProfileStore(goodSlots)
        val seeded = goodStore.applyIntents(
            intents = listOf(intent(id = "dev", secret = "old", principal = "alice")),
            activeProfileId = "dev",
            previousProfiles = emptyList()
        )
        val previous = seeded.publishedProfiles.single()
        assertEquals("old", goodSlots.read("dev", 1L))

        // New store over a failing seam — pending secret must not publish.
        val failing = InMemoryCredentialSlotStore(failWrites = true)
        // Pre-seed the old slot into a separate failing store? FailWrites fails
        // every stage, including restage. Simulate: previous published under
        // goodSlots; apply against a store that cannot stage the new revision.
        // Use a hybrid: stage already has v1, fail only new addresses by using
        // failWrites after seeding via direct stage on a non-failing store is
        // impossible — so seed previous profile as known and use failWrites
        // which refuses the new secret stage under v2.
        //
        // Approach: previous profile describes v1; failing store has no slots
        // and refuses all writes → Keep path stages under v2 and fails.
        val store = EnvironmentProfileStore(failing)
        val outcome = store.applyIntents(
            intents = listOf(intent(id = "dev", secret = "new", principal = "alice")),
            activeProfileId = "dev",
            previousProfiles = listOf(previous),
            previousPreferences = seeded.publishedPreferences,
            previousActiveId = "dev",
            previousSuggestedNamespaces = mapOf("dev" to "public")
        )

        assertEquals(setOf("dev"), outcome.failedStageProfileIds)
        val visible = outcome.publishedProfiles.single()
        assertEquals(previous.profileRevision, visible.profileRevision)
        assertEquals(previous.accessRevision, visible.accessRevision)
        assertEquals(previous.credentialSlotVersion, visible.credentialSlotVersion)
        assertTrue(outcome.accessRevisionAdvancedIds.isEmpty())
        assertTrue(outcome.preferenceChangedIds.isEmpty())
        assertTrue(outcome.displayOnlyChangedIds.isEmpty())
        // Preferences retained from previous publication, not the failed draft.
        assertEquals(
            seeded.publishedPreferences.single().allowCrossNamespaceNavigation,
            outcome.publishedPreferences.single().allowCrossNamespaceNavigation
        )
        assertNullRead(failing, "dev", previous.credentialSlotVersion + 1)
    }

    @Test
    fun `failed stage does not classify preference or display changes as published`() {
        val good = InMemoryCredentialSlotStore()
        val goodStore = EnvironmentProfileStore(good)
        val seeded = goodStore.applyIntents(
            intents = listOf(intent(id = "dev", secret = "old", principal = "alice", displayName = "Old")),
            activeProfileId = "dev",
            previousProfiles = emptyList()
        )
        val previous = seeded.publishedProfiles.single()
        val failing = InMemoryCredentialSlotStore(failWrites = true)
        val store = EnvironmentProfileStore(failing)

        val outcome = store.applyIntents(
            intents = listOf(
                intent(
                    id = "dev",
                    secret = "new",
                    principal = "alice",
                    displayName = "Renamed",
                    preferences = EnvironmentPreferences(
                        profileId = "dev",
                        allowCrossNamespaceNavigation = true,
                        navigationDetailPrefetchEnabled = false
                    )
                )
            ),
            activeProfileId = "dev",
            previousProfiles = listOf(previous),
            previousPreferences = seeded.publishedPreferences,
            previousActiveId = "dev",
            previousSuggestedNamespaces = mapOf("dev" to "public")
        )

        assertEquals(setOf("dev"), outcome.failedStageProfileIds)
        assertTrue(outcome.preferenceChangedIds.isEmpty())
        assertTrue(outcome.displayOnlyChangedIds.isEmpty())
        assertEquals("Old", outcome.publishedProfiles.single().displayName)
        assertFalse(outcome.publishedPreferences.single().allowCrossNamespaceNavigation)
    }

    @Test
    fun `platform stage failure on add does not reclaim slots`() {
        val slots = InMemoryCredentialSlotStore(failWrites = true)
        // Seed an orphan under a different revision so reclaim would wipe it.
        val seedable = InMemoryCredentialSlotStore()
        seedable.stage("dev", 9L, "historical")
        // Copy historical into failing store by staging is impossible; use a
        // hybrid store that fails only non-immutable reasons is not available —
        // instead assert failWrites path never calls remove (no removal obs).
        val store = EnvironmentProfileStore(slots)
        val outcome = store.applyIntents(
            intents = listOf(intent(id = "dev", secret = "fresh", principal = "alice")),
            activeProfileId = "dev",
            previousProfiles = emptyList()
        )
        assertEquals(setOf("dev"), outcome.failedStageProfileIds)
        assertTrue(outcome.publishedProfiles.isEmpty())
        // failWrites is not an immutability refusal — no removeAllForProfile.
        assertTrue(slots.removals.isEmpty())
    }

    @Test
    fun `removal is classified and surviving profiles keep their revisions`() {
        val slots = InMemoryCredentialSlotStore()
        val store = EnvironmentProfileStore(slots)
        val both = publish(
            store,
            intent(id = "keep", secret = "k", displayName = "Keep"),
            intent(id = "drop", secret = "d", displayName = "Drop")
        )

        val outcome = store.applyIntents(
            intents = listOf(intent(id = "keep", secret = "k", displayName = "Keep")),
            activeProfileId = "keep",
            previousProfiles = both.publishedProfiles,
            previousPreferences = both.publishedPreferences,
            previousActiveId = "keep",
            previousSuggestedNamespaces = both.suggestedNamespaces
        )

        assertEquals(setOf("drop"), outcome.removedProfileIds)
        assertEquals(listOf("keep"), outcome.publishedProfiles.map { it.id })
        assertEquals(
            both.publishedProfiles.first { it.id == "keep" }.profileRevision,
            outcome.publishedProfiles.single().profileRevision
        )
        assertTrue(outcome.isOperationalChange())
    }

    @Test
    fun `reads from outcome are immutable snapshots`() {
        val (store, _, previous) = seeded("dev", secret = "s1")
        val outcome = store.applyIntents(
            intents = listOf(intent(id = "dev", secret = "s1", principal = "alice", displayName = "Snap")),
            activeProfileId = "dev",
            previousProfiles = listOf(previous)
        )
        val snap = outcome.publishedProfiles.single()
        snap.displayName = "mutated"
        snap.profileRevision = 99
        // A subsequent read from a fresh apply still has the store-owned values.
        val again = store.applyIntents(
            intents = listOf(intent(id = "dev", secret = "s1", principal = "alice", displayName = "Snap")),
            activeProfileId = "dev",
            previousProfiles = listOf(
                previous.copy(displayName = "Snap")
            ),
            previousActiveId = "dev",
            previousSuggestedNamespaces = mapOf("dev" to "public")
        )
        assertEquals("Snap", again.publishedProfiles.single().displayName)
        assertEquals(previous.profileRevision, again.publishedProfiles.single().profileRevision)
        // snapshotOf also returns a defensive copy.
        val copy = store.snapshotOf(previous)
        copy.accessRevision = 42
        assertEquals(1L, previous.accessRevision)
    }

    @Test
    fun `add reclaims orphan credential slots left by an incomplete deletion`() {
        val slots = InMemoryCredentialSlotStore()
        // Orphan from a previous incarnation of this profile id.
        assertInstanceOf(CredentialStageResult.Success::class.java, slots.stage("dev", 1L, "stale-orphan"))
        val store = EnvironmentProfileStore(slots)

        val outcome = store.applyIntents(
            intents = listOf(intent(id = "dev", secret = "fresh", principal = "alice")),
            activeProfileId = "dev",
            previousProfiles = emptyList()
        )

        assertEquals(setOf("dev"), outcome.addedProfileIds)
        assertTrue(outcome.failedStageProfileIds.isEmpty())
        assertEquals("fresh", slots.read("dev", 1L))
        assertEquals(1L, outcome.publishedProfiles.single().accessRevision)
    }

    @Test
    fun `NacosSettings applyProfileIntents is the host publisher and returns the outcome`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings()
        settings.resetToDefaults()

        val outcome = settings.applyProfileIntents(
            intents = listOf(intent(id = "dev", secret = "pw", principal = "alice", displayName = "Dev")),
            newActiveId = "dev",
            credentialSlots = slots
        )

        assertEquals(setOf("dev"), outcome.addedProfileIds)
        assertEquals("dev", settings.activeServerId)
        val profile = settings.getProfile("dev")!!
        assertEquals(1L, profile.profileRevision)
        assertEquals("pw", slots.read("dev", profile.credentialSlotVersion))
        // Read returns a snapshot — mutating it does not rewrite settings.
        profile.displayName = "hacked"
        assertEquals("Dev", settings.getProfile("dev")!!.displayName)
    }

    @Test
    fun `host dual-write keeps published secret on stage failure and omits failed add`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings()
        settings.resetToDefaults()
        settings.applyProfileIntents(
            intents = listOf(intent(id = "dev", secret = "old", principal = "alice", displayName = "Dev")),
            newActiveId = "dev",
            credentialSlots = slots
        )
        val beforeRev = settings.getProfile("dev")!!.accessRevision
        assertEquals("old", settings.servers.single { it.id == "dev" }.password)

        val failing = InMemoryCredentialSlotStore(failWrites = true)
        val outcome = settings.applyProfileIntents(
            intents = listOf(
                intent(id = "dev", secret = "new", principal = "alice", displayName = "Dev"),
                intent(id = "fresh", secret = "x", principal = "bob", displayName = "Fresh")
            ),
            newActiveId = "dev",
            credentialSlots = failing
        )

        assertEquals(setOf("dev", "fresh"), outcome.failedStageProfileIds)
        // Failed Keep: still published at old revision; dual-write password stays old.
        assertEquals(beforeRev, settings.getProfile("dev")!!.accessRevision)
        assertEquals("old", settings.servers.single { it.id == "dev" }.password)
        // Failed Add: no server/profile row left for "fresh".
        assertTrue(settings.servers.none { it.id == "fresh" })
        assertNull(settings.profiles.firstOrNull { it.id == "fresh" })
        // Published slot pair unchanged under the good store (failing store has nothing).
        assertEquals("old", slots.read("dev", 1L))
    }

    @Test
    fun `host secret rotation success dual-writes the new secret after stage`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings()
        settings.resetToDefaults()
        settings.applyProfileIntents(
            intents = listOf(intent(id = "dev", secret = "old", principal = "alice")),
            newActiveId = "dev",
            credentialSlots = slots
        )
        val outcome = settings.applyProfileIntents(
            intents = listOf(intent(id = "dev", secret = "rotated", principal = "alice")),
            newActiveId = "dev",
            credentialSlots = slots
        )
        assertTrue(outcome.accessRevisionAdvancedIds.contains("dev"))
        assertEquals("rotated", settings.servers.single { it.id == "dev" }.password)
        assertEquals("rotated", slots.read("dev", settings.getProfile("dev")!!.credentialSlotVersion))
        assertEquals("old", slots.read("dev", 1L))
    }

    @Test
    fun `preference only dual-write fields do not advance profile revision through the store`() {
        // Preference-only edits (default group) must not advance profile/access
        // revisions — store classification owns that gate (ADR-0042 / #156).
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings()
        settings.resetToDefaults()
        fun server(defaultGroup: String) = com.nanyin.nacos.search.models.NacosServerConfig(
            id = "dev",
            displayName = "Dev",
            serverUrl = "https://nacos.example",
            username = "alice",
            password = "s1",
            authMode = AuthMode.NACOS_PASSWORD,
            defaultGroup = defaultGroup
        )
        settings.applyProfileIntents(
            intents = listOf(intent(id = "dev", secret = "s1", principal = "alice", displayName = "Dev")),
            newActiveId = "dev",
            dualWriteServers = listOf(server("DEFAULT_GROUP")),
            credentialSlots = slots
        )
        val before = settings.getProfile("dev")!!
        val outcome = settings.applyProfileIntents(
            intents = listOf(
                intent(
                    id = "dev",
                    secret = "s1",
                    principal = "alice",
                    displayName = "Dev",
                    preferences = EnvironmentPreferences.defaultsFor("dev").copy(defaultGroup = "CUSTOM_GROUP")
                )
            ),
            newActiveId = "dev",
            dualWriteServers = listOf(server("CUSTOM_GROUP")),
            credentialSlots = slots
        )
        assertEquals(before.profileRevision, settings.getProfile("dev")!!.profileRevision)
        assertEquals(before.accessRevision, settings.getProfile("dev")!!.accessRevision)
        assertFalse(outcome.isOperationalChange())
        assertEquals("CUSTOM_GROUP", settings.preferencesFor("dev").defaultGroup)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun intent(
        id: String,
        secret: String = "",
        principal: String = "",
        displayName: String = id,
        endpoint: String = "https://nacos.example",
        apiPolicy: NacosApiPolicy = NacosApiPolicy.AUTO,
        authMode: AuthMode = AuthMode.NACOS_PASSWORD,
        writeIntent: Boolean = false,
        suggestedNamespace: String = "public",
        preferences: EnvironmentPreferences = EnvironmentPreferences.defaultsFor(id)
    ): ProfileIntent = ProfileIntent(
        profileId = id,
        displayName = displayName,
        endpoint = endpoint,
        apiPolicy = apiPolicy,
        authMode = authMode,
        principal = principal,
        secret = secret,
        writeIntent = writeIntent,
        suggestedNamespace = suggestedNamespace,
        preferences = preferences
    )

    private fun seeded(
        id: String,
        secret: String
    ): Triple<EnvironmentProfileStore, InMemoryCredentialSlotStore, EnvironmentProfile> {
        val slots = InMemoryCredentialSlotStore()
        val store = EnvironmentProfileStore(slots)
        val outcome = store.applyIntents(
            intents = listOf(intent(id = id, secret = secret, principal = "alice")),
            activeProfileId = id,
            previousProfiles = emptyList()
        )
        return Triple(store, slots, outcome.publishedProfiles.single())
    }

    private fun publish(
        store: EnvironmentProfileStore,
        vararg intents: ProfileIntent,
        previous: List<EnvironmentProfile> = emptyList(),
        previousPrefs: List<EnvironmentPreferences> = emptyList(),
        previousNs: Map<String, String> = emptyMap(),
        active: String = intents.first().profileId
    ): ProfileStoreWriteOutcome = store.applyIntents(
        intents = intents.toList(),
        activeProfileId = active,
        previousProfiles = previous,
        previousPreferences = previousPrefs,
        previousActiveId = if (previous.isEmpty()) "" else active,
        previousSuggestedNamespaces = previousNs
    )

    private fun assertNullRead(store: InMemoryCredentialSlotStore, profileId: String, rev: Long) {
        assertEquals(null, store.read(profileId, rev))
    }
}
