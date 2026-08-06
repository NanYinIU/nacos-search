package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.ProfileIntent
import com.nanyin.nacos.search.services.CacheMutation
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.CacheWriteAccess
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.ProfileTombstoneRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Issue #105 / ADR-0025 / design §13.6: confirmed profile deletion runs one
 * fail-closed lifecycle at the profile-store write boundary.
 */
@OptIn(CacheWriteAccess::class)
class ProfileDeletionLifecycleTest {

    // ------------------------------------------------------------------
    // Lifecycle unit: ordering, tombstone failure, partial cleanup, retry
    // ------------------------------------------------------------------

    @Test
    fun `delete persists the tombstone before any cleanup side effect`() {
        val order = mutableListOf<String>()
        val tombstones = object : ProfileTombstoneWriter {
            private val entombed = linkedSetOf<String>()
            override fun tryEntomb(profileId: String): Boolean {
                order += "entomb:$profileId"
                entombed += profileId
                return true
            }
            override fun isEntombed(profileId: String): Boolean = profileId in entombed
            override fun entombedProfileIds(): Set<String> = entombed.toSet()
        }
        val cleanup = object : ProfileDeletionCleanup {
            override fun markReferringSessionsUnavailable(profileId: String) {
                order += "sessions:$profileId"
            }
            override fun removeCredentialSlots(profileId: String) {
                order += "credentials:$profileId"
            }
            override fun invalidateAuthentication(profileId: String) {
                order += "auth:$profileId"
            }
            override fun clearLastKnownGeneration(profileId: String) {
                order += "generation:$profileId"
            }
            override fun purgeCache(profileId: String) {
                order += "cache:$profileId"
            }
        }

        val result = ProfileDeletionLifecycle(tombstones, cleanup).delete("dev")

        assertInstanceOf(ProfileDeletionResult.Deleted::class.java, result)
        assertEquals("entomb:dev", order.first(), "tombstone must land before cleanup")
        assertEquals(
            listOf(
                "entomb:dev",
                "sessions:dev",
                "credentials:dev",
                "auth:dev",
                "generation:dev",
                "cache:dev"
            ),
            order
        )
    }

    @Test
    fun `tombstone failure runs no cleanup and reports TombstoneFailed`() {
        val tombstones = InMemoryProfileTombstoneWriter(failEntomb = true)
        val cleanup = RecordingProfileDeletionCleanup()

        val result = ProfileDeletionLifecycle(tombstones, cleanup).delete("dev")

        assertInstanceOf(ProfileDeletionResult.TombstoneFailed::class.java, result)
        assertTrue(tombstones.entombedIds.isEmpty())
        assertTrue(cleanup.sessionsMarked.isEmpty())
        assertTrue(cleanup.credentialsRemoved.isEmpty())
        assertTrue(cleanup.authInvalidated.isEmpty())
        assertTrue(cleanup.generationsCleared.isEmpty())
        assertTrue(cleanup.cachesPurged.isEmpty())
    }

    @Test
    fun `partial cleanup failure still returns Deleted and retry finishes remaining work`() {
        var failCredentials = true
        val tombstones = InMemoryProfileTombstoneWriter()
        val cleanup = object : ProfileDeletionCleanup {
            val credentialsRemoved = mutableListOf<String>()
            val cachesPurged = mutableListOf<String>()
            override fun markReferringSessionsUnavailable(profileId: String) = Unit
            override fun removeCredentialSlots(profileId: String) {
                if (failCredentials) error("credential remove refused")
                credentialsRemoved += profileId
            }
            override fun invalidateAuthentication(profileId: String) = Unit
            override fun clearLastKnownGeneration(profileId: String) = Unit
            override fun purgeCache(profileId: String) {
                cachesPurged += profileId
            }
        }
        val lifecycle = ProfileDeletionLifecycle(tombstones, cleanup)

        val first = lifecycle.delete("dev")
        assertInstanceOf(ProfileDeletionResult.Deleted::class.java, first)
        assertTrue(tombstones.isEntombed("dev"), "tombstone must stay even when cleanup partially fails")
        assertTrue(cleanup.credentialsRemoved.isEmpty())
        assertEquals(listOf("dev"), cleanup.cachesPurged)

        failCredentials = false
        cleanup.cachesPurged.clear()
        val retry = lifecycle.delete("dev")
        assertInstanceOf(ProfileDeletionResult.Deleted::class.java, retry)
        assertEquals(listOf("dev"), cleanup.credentialsRemoved)
        assertEquals(listOf("dev"), cleanup.cachesPurged)
    }

    @Test
    fun `repeating delete is idempotent`() {
        val tombstones = InMemoryProfileTombstoneWriter()
        val cleanup = RecordingProfileDeletionCleanup()
        val lifecycle = ProfileDeletionLifecycle(tombstones, cleanup)

        assertInstanceOf(ProfileDeletionResult.Deleted::class.java, lifecycle.delete("dev"))
        assertInstanceOf(ProfileDeletionResult.Deleted::class.java, lifecycle.delete("dev"))
        assertInstanceOf(ProfileDeletionResult.Deleted::class.java, lifecycle.retryCleanup("dev"))

        assertEquals(setOf("dev"), tombstones.entombedIds)
        assertEquals(listOf("dev", "dev", "dev"), cleanup.credentialsRemoved)
        assertEquals(listOf("dev", "dev", "dev"), cleanup.cachesPurged)
    }

    // ------------------------------------------------------------------
    // Host write boundary: ordering, fail-closed publish, late ops
    // ------------------------------------------------------------------

    @Test
    fun `host entombs before the published set omits the environment`() {
        val order = mutableListOf<String>()
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings().also { it.resetToDefaults() }
        settings.applyProfileIntents(
            intents = listOf(
                intent("keep", "k"),
                intent("drop", "d")
            ),
            newActiveId = "keep",
            credentialSlots = slots
        )

        val tombstones = object : ProfileTombstoneWriter {
            private val entombed = linkedSetOf<String>()
            override fun tryEntomb(profileId: String): Boolean {
                order += "entomb:$profileId"
                // Observe host state at entomb time: profile must still be live.
                order += "still-published:${settings.getProfile(profileId) != null}"
                entombed += profileId
                return true
            }
            override fun isEntombed(profileId: String): Boolean = profileId in entombed
            override fun entombedProfileIds(): Set<String> = entombed.toSet()
        }
        val cleanup = object : ProfileDeletionCleanup {
            override fun markReferringSessionsUnavailable(profileId: String) {
                order += "sessions:$profileId"
            }
            override fun removeCredentialSlots(profileId: String) {
                order += "credentials:$profileId"
                slots.removeAllForProfile(profileId)
            }
            override fun invalidateAuthentication(profileId: String) {
                order += "auth:$profileId"
            }
            override fun clearLastKnownGeneration(profileId: String) {
                order += "generation:$profileId"
            }
            override fun purgeCache(profileId: String) {
                order += "cache:$profileId"
            }
        }

        val outcome = settings.applyProfileIntents(
            intents = listOf(intent("keep", "k")),
            newActiveId = "keep",
            credentialSlots = slots,
            deletionLifecycle = ProfileDeletionLifecycle(tombstones, cleanup)
        )

        assertEquals(setOf("drop"), outcome.removedProfileIds)
        assertTrue(outcome.withheldDeletionProfileIds.isEmpty())
        assertNull(settings.getProfile("drop"))
        assertEquals("entomb:drop", order.first())
        assertTrue(order.contains("still-published:true"), "entomb must precede unpublish")
        assertTrue(
            order.indexOf("entomb:drop") < order.indexOf("credentials:drop"),
            "cleanup after tombstone"
        )
    }

    @Test
    fun `tombstone failure withholds deletion and keeps the previous published pair`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings().also { it.resetToDefaults() }
        settings.applyProfileIntents(
            intents = listOf(
                intent("keep", "k"),
                intent("drop", "d")
            ),
            newActiveId = "keep",
            credentialSlots = slots
        )
        val beforeDrop = settings.getProfile("drop")!!
        val beforeSlot = slots.read("drop", beforeDrop.credentialSlotVersion)

        val outcome = settings.applyProfileIntents(
            intents = listOf(intent("keep", "k")),
            newActiveId = "keep",
            credentialSlots = slots,
            deletionLifecycle = ProfileDeletionLifecycle(
                InMemoryProfileTombstoneWriter(failEntomb = true),
                RecordingProfileDeletionCleanup()
            )
        )

        assertTrue(outcome.removedProfileIds.isEmpty())
        assertEquals(setOf("drop"), outcome.withheldDeletionProfileIds)
        assertTrue(outcome.hasWithheldDeletions())
        val still = settings.getProfile("drop")
        assertEquals(beforeDrop.id, still?.id)
        assertEquals(beforeDrop.accessRevision, still?.accessRevision)
        assertEquals(beforeSlot, slots.read("drop", beforeDrop.credentialSlotVersion))
        assertTrue(settings.servers.any { it.id == "drop" })
    }

    @Test
    fun `successful deletion removes every credential slot for the profile`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings().also { it.resetToDefaults() }
        settings.applyProfileIntents(
            intents = listOf(intent("drop", "secret-v1")),
            newActiveId = "drop",
            credentialSlots = slots
        )
        // Rotate once so two historical slots exist.
        settings.applyProfileIntents(
            intents = listOf(intent("drop", "secret-v2")),
            newActiveId = "drop",
            credentialSlots = slots
        )
        assertEquals("secret-v1", slots.read("drop", 1L))
        assertEquals("secret-v2", slots.read("drop", 2L))

        val tombstones = InMemoryProfileTombstoneWriter()
        val cleanup = RecordingProfileDeletionCleanup()
        // Wire credential removal through the real seam the production cleanup uses.
        val lifecycle = ProfileDeletionLifecycle(
            tombstones,
            object : ProfileDeletionCleanup by cleanup {
                override fun removeCredentialSlots(profileId: String) {
                    cleanup.removeCredentialSlots(profileId)
                    slots.removeAllForProfile(profileId)
                }
            }
        )

        val outcome = settings.applyProfileIntents(
            intents = emptyList(),
            newActiveId = "",
            credentialSlots = slots,
            deletionLifecycle = lifecycle
        )

        assertEquals(setOf("drop"), outcome.removedProfileIds)
        assertNull(slots.read("drop", 1L))
        assertNull(slots.read("drop", 2L))
        assertTrue(tombstones.isEntombed("drop"))
        assertEquals(listOf("drop"), cleanup.credentialsRemoved)
    }

    @Test
    fun `late pre-deletion cache write cannot land after the tombstone`() = runBlocking {
        val registry = ProfileTombstoneRegistry()
        val cache = CacheService({ System.currentTimeMillis() }, registry, InMemoryCacheStore())
        val identity = AccessIdentity.ofProfile(
            profileId = "drop",
            accessRevision = 1L,
            canonicalEndpoint = "https://nacos.example",
            resolvedGeneration = NacosApiGeneration.V1,
            authMode = AuthMode.NACOS_PASSWORD,
            principal = "alice"
        )
        val config = NacosConfiguration("app.properties", "DEFAULT_GROUP", "public", "v=1", "text")
        assertTrue(
            cache.applyMutation(
                CacheMutation.WriteDetail(identity, "public", config, 60_000L),
                observation = 1L
            )
        )

        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings().also { it.resetToDefaults() }
        settings.applyProfileIntents(
            intents = listOf(intent("drop", "s")),
            newActiveId = "drop",
            credentialSlots = slots
        )

        val lifecycle = ProfileDeletionLifecycle(
            object : ProfileTombstoneWriter {
                override fun tryEntomb(profileId: String): Boolean = registry.tryEntomb(profileId)
                override fun isEntombed(profileId: String): Boolean = registry.isEntombed(profileId)
                override fun entombedProfileIds(): Set<String> = registry.entombedProfileIds()
            },
            object : ProfileDeletionCleanup {
                override fun markReferringSessionsUnavailable(profileId: String) = Unit
                override fun removeCredentialSlots(profileId: String) {
                    slots.removeAllForProfile(profileId)
                }
                override fun invalidateAuthentication(profileId: String) = Unit
                override fun clearLastKnownGeneration(profileId: String) = Unit
                override fun purgeCache(profileId: String) {
                    cache.purgeProfile(profileId)
                }
            }
        )

        settings.applyProfileIntents(
            intents = emptyList(),
            newActiveId = "",
            credentialSlots = slots,
            deletionLifecycle = lifecycle
        )

        // Late write that started "before" deletion (low observation) still loses
        // to the absolute tombstone, and residual data is gone after purge.
        assertFalse(
            cache.applyMutation(
                CacheMutation.WriteDetail(
                    identity,
                    "public",
                    config.copy(content = "v=late"),
                    60_000L
                ),
                observation = 99L
            )
        )
        assertNull(cache.snapshot(identity).detail("public", "app.properties", "DEFAULT_GROUP"))
    }

    @Test
    fun `entombed profile cannot be re-published under the same id`() {
        val slots = InMemoryCredentialSlotStore()
        val tombstones = InMemoryProfileTombstoneWriter()
        val recording = RecordingProfileDeletionCleanup()
        val lifecycle = ProfileDeletionLifecycle(
            tombstones,
            object : ProfileDeletionCleanup by recording {
                override fun removeCredentialSlots(profileId: String) {
                    recording.removeCredentialSlots(profileId)
                    slots.removeAllForProfile(profileId)
                }
            }
        )
        val settings = NacosSettings().also { it.resetToDefaults() }
        settings.applyProfileIntents(
            intents = listOf(intent("drop", "s")),
            newActiveId = "drop",
            credentialSlots = slots,
            deletionLifecycle = lifecycle
        )
        settings.applyProfileIntents(
            intents = emptyList(),
            newActiveId = "",
            credentialSlots = slots,
            deletionLifecycle = lifecycle
        )
        assertTrue(tombstones.isEntombed("drop"))
        assertNull(slots.read("drop", 1L), "deletion must clear historical slots")

        val readd = settings.applyProfileIntents(
            intents = listOf(intent("drop", "fresh")),
            newActiveId = "drop",
            credentialSlots = slots,
            deletionLifecycle = lifecycle
        )

        assertTrue(readd.addedProfileIds.isEmpty())
        assertTrue("drop" in readd.failedStageProfileIds)
        assertNull(settings.getProfile("drop"))
        // Lifecycle guard refuses staging — the fresh secret must not land.
        assertNull(slots.read("drop", 1L))
        assertTrue(slots.writes.none { it.profileId == "drop" && it.succeeded && it.accessRevision >= 1L &&
            slots.read("drop", it.accessRevision) == "fresh" })
    }

    @Test
    fun `read paths do not tombstone or remove credentials when a profile is missing`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings().also { it.resetToDefaults() }
        // Seed a slot that is not paired with a published profile — a missing
        // row. getProfile / capture must not discover-and-delete it.
        slots.stage("orphan", 1L, "secret")
        assertNull(settings.getProfile("orphan"))
        assertInstanceOf(
            ConfigurationRequired::class.java,
            settings.captureOperationContext("orphan").exceptionOrNull()
        )
        assertEquals("secret", slots.read("orphan", 1L), "read path must not remove credentials")
    }

    @Test
    fun `sibling deletion still unpublishes when one tombstone fails`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings().also { it.resetToDefaults() }
        settings.applyProfileIntents(
            intents = listOf(intent("keep", "k"), intent("drop-ok", "a"), intent("drop-fail", "b")),
            newActiveId = "keep",
            credentialSlots = slots
        )
        val selectiveWriter = object : ProfileTombstoneWriter {
            private val entombed = linkedSetOf<String>()
            override fun tryEntomb(profileId: String): Boolean {
                if (profileId == "drop-fail") return false
                entombed += profileId
                return true
            }
            override fun isEntombed(profileId: String): Boolean = profileId in entombed
            override fun entombedProfileIds(): Set<String> = entombed.toSet()
        }
        val cleanup = RecordingProfileDeletionCleanup()
        val lifecycle = ProfileDeletionLifecycle(
            selectiveWriter,
            object : ProfileDeletionCleanup by cleanup {
                override fun removeCredentialSlots(profileId: String) {
                    cleanup.removeCredentialSlots(profileId)
                    slots.removeAllForProfile(profileId)
                }
            }
        )

        val outcome = settings.applyProfileIntents(
            intents = listOf(intent("keep", "k")),
            newActiveId = "keep",
            credentialSlots = slots,
            deletionLifecycle = lifecycle
        )

        assertEquals(setOf("drop-ok"), outcome.removedProfileIds)
        assertEquals(setOf("drop-fail"), outcome.withheldDeletionProfileIds)
        assertNull(settings.getProfile("drop-ok"))
        assertNotNull(settings.getProfile("drop-fail"))
        // Withheld stays at previous order relative to keep (previous: keep, drop-ok, drop-fail
        // → published: keep, drop-fail).
        assertEquals(listOf("keep", "drop-fail"), settings.publishedProfiles().map { it.id })
        assertFalse(outcome.isNoOp())
        assertTrue(outcome.isOperationalChange(), "successful sibling removal is operational")
    }

    @Test
    fun `host retries residual cleanup on a later apply after unpublish`() {
        val slots = InMemoryCredentialSlotStore()
        val settings = NacosSettings().also { it.resetToDefaults() }
        // Keep a surviving profile so the published set never collapses to empty
        // (empty servers re-migrate a synthetic default and muddy removals).
        settings.applyProfileIntents(
            intents = listOf(intent("keep", "k"), intent("drop", "s")),
            newActiveId = "keep",
            credentialSlots = slots
        )
        var failCredentials = true
        val tombstones = InMemoryProfileTombstoneWriter()
        val cleanup = object : ProfileDeletionCleanup {
            val credentialsRemoved = mutableListOf<String>()
            override fun markReferringSessionsUnavailable(profileId: String) = Unit
            override fun removeCredentialSlots(profileId: String) {
                if (failCredentials) error("credential remove refused")
                credentialsRemoved += profileId
                slots.removeAllForProfile(profileId)
            }
            override fun invalidateAuthentication(profileId: String) = Unit
            override fun clearLastKnownGeneration(profileId: String) = Unit
            override fun purgeCache(profileId: String) = Unit
        }
        val lifecycle = ProfileDeletionLifecycle(tombstones, cleanup)

        // First apply: tombstone + unpublish drop, credential remove fails.
        val first = settings.applyProfileIntents(
            intents = listOf(intent("keep", "k")),
            newActiveId = "keep",
            credentialSlots = slots,
            deletionLifecycle = lifecycle
        )
        assertEquals(setOf("drop"), first.removedProfileIds)
        assertNull(settings.getProfile("drop"))
        assertTrue(tombstones.isEntombed("drop"))
        assertEquals("s", slots.read("drop", 1L), "residual slot remains after partial cleanup")
        assertTrue(cleanup.credentialsRemoved.isEmpty())

        // Second apply: drop is already unpublished so removedProfileIds omits it,
        // but residual drain must retry cleanup for the entombed id (AC7).
        failCredentials = false
        val second = settings.applyProfileIntents(
            intents = listOf(intent("keep", "k")),
            newActiveId = "keep",
            credentialSlots = slots,
            deletionLifecycle = lifecycle
        )
        assertFalse("drop" in second.removedProfileIds)
        assertEquals(listOf("drop"), cleanup.credentialsRemoved)
        assertNull(slots.read("drop", 1L))
    }

    @Test
    fun `markSelectedProfileUnavailable blocks healSelection auto-retarget`() {
        val live = NacosServerConfig(
            id = "live",
            displayName = "Live",
            serverUrl = "https://nacos.example",
            username = "alice",
            password = "s",
            authMode = AuthMode.NACOS_PASSWORD
        )
        val settings = NacosSettings().also { it.resetToDefaults() }
        settings.applyServers(listOf(live), "live")

        // Non-explicit session pointing at a now-deleted profile — without
        // markSelectedProfileUnavailable, healSelection would retarget to "live".
        // Construct via no-arg + fields so the test stays stable across session
        // schema field order (issue #104 upgraded the upgrade-summary fields).
        val session = NacosProjectSessionState().apply {
            selectedProfileId = "deleted"
            namespaceId = "public"
            selectionWasExplicit = false
        }
        // Simulate deletion cleanup forcing the unavailable posture.
        session.selectionWasExplicit = true
        session.healSelection(settings.migrationDefaults()) { id -> settings.getProfile(id) != null }
        assertEquals("deleted", session.selectedProfileId, "must not auto-select another environment")
        assertTrue(session.selectionWasExplicit)

        // NacosProjectSession.markSelectedProfileUnavailable is the production seam.
        val projectSession = NacosProjectSession()
        projectSession.sessionState.selectedProfileId = "deleted"
        projectSession.sessionState.selectionWasExplicit = false
        assertTrue(projectSession.markSelectedProfileUnavailable("deleted"))
        assertTrue(projectSession.sessionState.selectionWasExplicit)
        projectSession.healSelection(settings)
        assertEquals("deleted", projectSession.sessionState.selectedProfileId)
    }

    @Test
    fun `withheld deletion outcome is not a no-op and stays non-preferences-only`() {
        val outcome = ProfileStoreWriteOutcome(
            withheldDeletionProfileIds = setOf("drop"),
            publishedProfiles = emptyList()
        )
        assertTrue(outcome.hasWithheldDeletions())
        assertFalse(outcome.isNoOp())
        assertFalse(outcome.isPreferencesOnlyChange())
        assertFalse(outcome.isOperationalChange(), "withheld alone does not change published set")
    }

    @Test
    fun `blank profile id is not reported as Deleted`() {
        val lifecycle = ProfileDeletionLifecycle(
            InMemoryProfileTombstoneWriter(),
            RecordingProfileDeletionCleanup()
        )
        assertInstanceOf(
            ProfileDeletionResult.TombstoneFailed::class.java,
            lifecycle.delete("  ")
        )
        assertInstanceOf(
            ProfileDeletionResult.TombstoneFailed::class.java,
            lifecycle.retryCleanup("")
        )
    }

    @Test
    fun `store and heal path refuse staging an entombed dual-write orphan`() {
        // ensureProfilesForServers and applyIntents share the isEntombed guard:
        // a missing published row must not stage credentials for a tombstoned id
        // (no cleanup-on-read / no resurrect — issue #105 AC6).
        val slots = InMemoryCredentialSlotStore()
        val store = EnvironmentProfileStore(slots, isEntombed = { it == "ghost" })
        val outcome = store.applyIntents(
            intents = listOf(intent("ghost", "orphan-secret")),
            activeProfileId = "ghost",
            previousProfiles = emptyList()
        )
        assertTrue(outcome.addedProfileIds.isEmpty())
        assertTrue("ghost" in outcome.failedStageProfileIds)
        assertTrue(outcome.publishedProfiles.isEmpty())
        assertNull(slots.read("ghost", 1L), "entombed id must not stage a credential slot")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun intent(id: String, secret: String): ProfileIntent = ProfileIntent(
        profileId = id,
        displayName = id,
        endpoint = "https://nacos.example",
        authMode = AuthMode.NACOS_PASSWORD,
        principal = "alice",
        secret = secret
    )
}
