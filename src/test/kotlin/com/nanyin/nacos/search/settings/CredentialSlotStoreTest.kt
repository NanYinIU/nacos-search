package com.nanyin.nacos.search.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Issue #102 / ADR-0035: credential-slot storage is an injectable seam.
 * Production uses the platform adapter; these unit tests drive the in-memory
 * adapter and assert stage outcomes, immutability, fail-closed reads, and
 * profile-wide removal — without ever putting secrets into profiles or logs.
 */
class CredentialSlotStoreTest {

    @Test
    fun `slots are addressed by profile id and access revision`() {
        val store = InMemoryCredentialSlotStore()

        assertInstanceOf(CredentialStageResult.Success::class.java, store.stage("dev", 1L, "secret-v1"))
        assertInstanceOf(CredentialStageResult.Success::class.java, store.stage("dev", 2L, "secret-v2"))

        assertEquals("secret-v1", store.read("dev", 1L))
        assertEquals("secret-v2", store.read("dev", 2L))
        assertNull(store.read("dev", 3L))
        assertNull(store.read("other", 1L))
    }

    @Test
    fun `stage reports success only when the write is durable and refuses to overwrite a different secret`() {
        val store = InMemoryCredentialSlotStore()

        assertInstanceOf(CredentialStageResult.Success::class.java, store.stage("dev", 1L, "first"))
        // Same secret restaged under the same address is idempotent success.
        assertInstanceOf(CredentialStageResult.Success::class.java, store.stage("dev", 1L, "first"))
        // A different secret must not replace an already-published slot.
        assertInstanceOf(CredentialStageResult.Failure::class.java, store.stage("dev", 1L, "second"))
        assertEquals("first", store.read("dev", 1L))
    }

    @Test
    fun `failed stage leaves no slot and cannot make a pending revision readable`() {
        val store = InMemoryCredentialSlotStore(failWrites = true)

        val result = store.stage("dev", 2L, "pending-secret")

        assertInstanceOf(CredentialStageResult.Failure::class.java, result)
        assertNull(store.read("dev", 2L))
        assertTrue(store.writes.none { it.accessRevision == 2L && it.succeeded })
    }

    @Test
    fun `reading a required missing slot returns null and never falls back to a predecessor`() {
        val store = InMemoryCredentialSlotStore()
        store.stage("dev", 1L, "old-secret")
        store.stage("dev", 2L, "current-secret")

        // The current revision is missing — predecessor must not be substituted.
        store.remove("dev", 2L)
        assertNull(store.read("dev", 2L))
        assertEquals("old-secret", store.read("dev", 1L))
    }

    @Test
    fun `removeAllForProfile deletes every slot for that profile and is idempotent`() {
        val store = InMemoryCredentialSlotStore()
        store.stage("dev", 1L, "a")
        store.stage("dev", 2L, "b")
        store.stage("prod", 1L, "c")

        store.removeAllForProfile("dev")
        store.removeAllForProfile("dev")

        assertNull(store.read("dev", 1L))
        assertNull(store.read("dev", 2L))
        assertEquals("c", store.read("prod", 1L))
    }

    @Test
    fun `in-memory adapter records observable reads and writes without exposing secrets in observations`() {
        val store = InMemoryCredentialSlotStore()
        store.stage("dev", 1L, "top-secret")
        store.read("dev", 1L)
        store.read("dev", 9L)

        assertEquals(1, store.writes.size)
        assertEquals(2, store.reads.size)
        assertEquals(CredentialSlotObservation.Write("dev", 1L, succeeded = true), store.writes.single())
        assertEquals(
            listOf(
                CredentialSlotObservation.Read("dev", 1L, hit = true),
                CredentialSlotObservation.Read("dev", 9L, hit = false)
            ),
            store.reads
        )
        assertFalse(store.writes.toString().contains("top-secret"))
        assertFalse(store.reads.toString().contains("top-secret"))
    }

    @Test
    fun `stager reports failure and callers must not publish the pending profile revision`() {
        val store = InMemoryCredentialSlotStore(failWrites = true)
        val stager = CredentialSlotStager(store)
        val pending = EnvironmentProfileFixture.profile(
            id = "dev",
            accessRevision = 2L,
            credentialSlotVersion = 2L
        )

        val result = stager.stage(pending, "new-secret")

        assertInstanceOf(CredentialStageResult.Failure::class.java, result)
        assertNull(store.read("dev", 2L))
    }

    @Test
    fun `stager stages under the profile access revision before the revision is considered published`() {
        val store = InMemoryCredentialSlotStore()
        val stager = CredentialSlotStager(store)
        val pending = EnvironmentProfileFixture.profile(
            id = "dev",
            accessRevision = 3L,
            credentialSlotVersion = 3L
        )

        assertInstanceOf(CredentialStageResult.Success::class.java, stager.stage(pending, "rotated"))
        assertEquals("rotated", store.read("dev", 3L))
        // Predecessor revision was never written — no fallback path exists.
        assertNull(store.read("dev", 2L))
    }

    @Test
    fun `slot keys and observations never embed secret values`() {
        val key = CredentialSlotStore.slotKey("dev", 4L)
        assertEquals("dev:v4", key)
        assertFalse(key.contains("secret"))
    }

    @Test
    fun `platform adapter reports backend write failure and refuses mutable overwrite`() {
        val backend = FakePlatformBackend()
        val store = PlatformCredentialSlotStore(backend)

        assertInstanceOf(CredentialStageResult.Success::class.java, store.stage("dev", 1L, "secret"))
        assertEquals("secret", store.read("dev", 1L))

        assertInstanceOf(CredentialStageResult.Failure::class.java, store.stage("dev", 1L, "other"))
        assertEquals("secret", store.read("dev", 1L))

        backend.failNextWrite = true
        assertInstanceOf(CredentialStageResult.Failure::class.java, store.stage("dev", 2L, "pending"))
        assertNull(store.read("dev", 2L))
    }

    @Test
    fun `platform adapter removeAllForProfile clears staged keys for that profile only`() {
        val backend = FakePlatformBackend()
        val store = PlatformCredentialSlotStore(backend)
        store.stage("dev", 1L, "a")
        store.stage("dev", 2L, "b")
        store.stage("prod", 1L, "c")

        store.removeAllForProfile("dev")
        store.removeAllForProfile("dev")

        assertNull(store.read("dev", 1L))
        assertNull(store.read("dev", 2L))
        assertEquals("c", store.read("prod", 1L))
        assertNull(backend.get(PlatformCredentialSlotStore.revisionIndexKey("dev")))
    }

    @Test
    fun `platform removeAllForProfile still finds slots after a cold start from the durable index`() {
        val backend = FakePlatformBackend()
        PlatformCredentialSlotStore(backend).apply {
            stage("dev", 1L, "a")
            stage("dev", 3L, "b")
        }
        // New store instance — process-local knownKeys are empty; only the index remains.
        val restarted = PlatformCredentialSlotStore(backend)
        restarted.removeAllForProfile("dev")

        assertNull(restarted.read("dev", 1L))
        assertNull(restarted.read("dev", 3L))
    }

    @Test
    fun `a failed stage leaves the previous slot intact so a later stage can open a new revision`() {
        val store = InMemoryCredentialSlotStore()
        assertInstanceOf(CredentialStageResult.Success::class.java, store.stage("dev", 1L, "old"))

        // Overwriting the published slot with a different secret is refused.
        assertInstanceOf(CredentialStageResult.Failure::class.java, store.stage("dev", 1L, "new"))
        assertEquals("old", store.read("dev", 1L))

        // Retry opens a new immutable revision rather than overwriting v1.
        assertInstanceOf(CredentialStageResult.Success::class.java, store.stage("dev", 2L, "new"))
        assertEquals("old", store.read("dev", 1L))
        assertEquals("new", store.read("dev", 2L))
    }
}

/** Deterministic platform backend for [PlatformCredentialSlotStore] unit tests. */
private class FakePlatformBackend : PlatformCredentialBackend {
    private val values = mutableMapOf<String, String>()
    var failNextWrite: Boolean = false

    override fun get(key: String): String? = values[key]

    override fun set(key: String, secret: String): Boolean {
        if (failNextWrite) {
            failNextWrite = false
            return false
        }
        if (secret.isEmpty()) values.remove(key) else values[key] = secret
        return true
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun knownKeys(): Set<String> = values.keys.toSet()
}

/** Minimal profile factory so slot-store tests do not depend on full settings state. */
private object EnvironmentProfileFixture {
    fun profile(
        id: String,
        accessRevision: Long,
        credentialSlotVersion: Long
    ): com.nanyin.nacos.search.models.EnvironmentProfile =
        com.nanyin.nacos.search.models.EnvironmentProfile(
            id = id,
            displayName = id,
            canonicalEndpoint = "https://nacos.example",
            accessRevision = accessRevision,
            credentialSlotId = CredentialSlotStore.slotKey(id, credentialSlotVersion),
            credentialSlotVersion = credentialSlotVersion
        )
}
