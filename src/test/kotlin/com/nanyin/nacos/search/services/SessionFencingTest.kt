package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionFencingTest {

    // ── Session epoch ──

    @Test
    fun `session epoch increments on profile or namespace change`() {
        val registry = SessionEpochRegistry()

        assertEquals(0L, registry.currentEpoch("proj-a"))

        registry.bump("proj-a")
        assertEquals(1L, registry.currentEpoch("proj-a"))

        registry.bump("proj-a")
        assertEquals(2L, registry.currentEpoch("proj-a"))
    }

    @Test
    fun `two projects sharing a profile have independent epochs`() {
        val registry = SessionEpochRegistry()

        registry.bump("proj-a")
        registry.bump("proj-a")
        registry.bump("proj-b")

        assertEquals(2L, registry.currentEpoch("proj-a"))
        assertEquals(1L, registry.currentEpoch("proj-b"))
        assertEquals(0L, registry.currentEpoch("proj-c"))
    }

    @Test
    fun `an operation ticket captures the current epoch and rejects after bump`() {
        val registry = SessionEpochRegistry()
        val ticket = registry.capture("proj-a", identity("dev", 1))

        assertTrue(ticket.isCurrent())

        registry.bump("proj-a")

        assertFalse(ticket.isCurrent())
    }

    // ── Profile tombstone ──

    @Test
    fun `deleted profile rejects every late credential token probe session index detail and cache completion`() {
        val tombstones = ProfileTombstoneRegistry()

        tombstones.entomb("dev", 3)

        assertTrue(tombstones.isEntombed(identity("dev", 3)))
        assertTrue(tombstones.isEntombed(identity("dev", 4)))
        assertTrue(tombstones.isEntombed(identity("dev", 5)))
        assertFalse(tombstones.isEntombed(identity("prod", 1)))
    }

    @Test
    fun `a tombstoned profile blocks new operations even after re-creation with same id`() {
        val tombstones = ProfileTombstoneRegistry()

        tombstones.entomb("dev", 2)
        assertTrue(tombstones.isEntombed(identity("dev", 3)))
    }

    @Test
    fun `profile tombstone persists across state reload`() {
        val original = ProfileTombstoneRegistry()
        original.entomb("dev", 1)
        original.entomb("sit", 2)

        val restored = ProfileTombstoneRegistry()
        restored.loadState(original.getState())

        assertTrue(restored.isEntombed(identity("dev", 9)))
        assertTrue(restored.isEntombed(identity("sit", 1)))
        assertFalse(restored.isEntombed(identity("prod", 1)))
    }

    // Observation high-water ordering lives with the one implementation that
    // survived the merge — see ObservationHighWaterTest and CacheWriteGateTest.

    // ── Helpers ──

    private fun identity(profileId: String, accessRevision: Long): AccessIdentity =
        AccessIdentity.ofProfile(
            profileId = profileId,
            accessRevision = accessRevision,
            canonicalEndpoint = "https://nacos.example",
            resolvedGeneration = NacosApiGeneration.V1,
            authMode = AuthMode.TOKEN,
            principal = "alice"
        )
}
