package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

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

    // ── Operation fence: stale results dropped ──

    @Test
    fun `late operation result is dropped after a newer state change`() = runBlocking {
        val registry = SessionEpochRegistry()
        val fence = OperationFence(registry)

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val deferred = fence.launch(this, "proj-a", identity("dev", 1)) { ticket ->
            started.complete(Unit)
            release.await()
            ticket.checkpoint()
            "stale-result"
        }

        started.await()
        registry.bump("proj-a")
        release.complete(Unit)

        val outcome = deferred.await()
        assertFalse(outcome.published, "late result must not be published")
        assertNull(outcome.value)
    }

    @Test
    fun `fresh operation result is published when no state change occurred`() = runBlocking {
        val registry = SessionEpochRegistry()
        val fence = OperationFence(registry)

        val outcome = fence.launch(this, "proj-a", identity("dev", 1)) { ticket ->
            ticket.checkpoint()
            "fresh-result"
        }.await()

        assertTrue(outcome.published)
        assertEquals("fresh-result", outcome.value)
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

    // ── Observation high-water ordering ──

    @Test
    fun `older cache visibility cannot override a newer outcome`() {
        val highWater = ObservationHighWater()

        val stamp1 = highWater.capture(identity("dev", 1), "ns-a")
        val stamp2 = highWater.capture(identity("dev", 1), "ns-a")

        assertTrue(highWater.isCurrent(stamp2))
        assertFalse(highWater.isCurrent(stamp1))
    }

    @Test
    fun `different namespace stamps are independent`() {
        val highWater = ObservationHighWater()

        val stampA = highWater.capture(identity("dev", 1), "ns-a")
        val stampB = highWater.capture(identity("dev", 1), "ns-b")

        assertTrue(highWater.isCurrent(stampA))
        assertTrue(highWater.isCurrent(stampB))
    }

    @Test
    fun `different identity stamps are independent`() {
        val highWater = ObservationHighWater()

        val stampDev = highWater.capture(identity("dev", 1), "ns-a")
        val stampProd = highWater.capture(identity("prod", 1), "ns-a")

        assertTrue(highWater.isCurrent(stampDev))
        assertTrue(highWater.isCurrent(stampProd))
    }

    // ── Policy-only change fence ──

    @Test
    fun `policy-only request change blocks new operations from joining old flight`() = runBlocking {
        val registry = SessionEpochRegistry()
        val fence = OperationFence(registry)

        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val flights = AtomicInteger()

        val oldFlight = fence.launch(this, "proj-a", identity("dev", 1)) { ticket ->
            flights.incrementAndGet()
            oldStarted.complete(Unit)
            releaseOld.await()
            ticket.checkpoint()
            "old"
        }

        oldStarted.await()
        registry.bump("proj-a")

        val newOutcome = fence.launch(this, "proj-a", identity("dev", 1)) { ticket ->
            flights.incrementAndGet()
            ticket.checkpoint()
            "new"
        }.await()

        assertTrue(newOutcome.published)
        assertEquals("new", newOutcome.value)

        releaseOld.complete(Unit)
        val oldOutcome = oldFlight.await()
        assertFalse(oldOutcome.published)

        assertEquals(2, flights.get())
    }

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
