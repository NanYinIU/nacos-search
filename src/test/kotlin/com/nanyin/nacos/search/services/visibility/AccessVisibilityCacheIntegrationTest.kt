@file:OptIn(com.nanyin.nacos.search.services.CacheWriteAccess::class)

package com.nanyin.nacos.search.services.visibility

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.ProfileTombstoneRegistry
import com.nanyin.nacos.search.services.operations.ProtocolCapability
import com.nanyin.nacos.search.services.clearAll
import com.nanyin.nacos.search.services.deleteDetailNotFound
import com.nanyin.nacos.search.services.invalidateNamespace
import com.nanyin.nacos.search.services.replaceNamespaceIndex
import com.nanyin.nacos.search.services.writeDetail
import com.nanyin.nacos.search.services.writeListPage
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cache-read enforcement for identity-wide authentication blocks (issue #123).
 * Payloads remain stored and become visible again after a matching success.
 */
class AccessVisibilityCacheIntegrationTest {

    @Test
    fun `identity auth block hides every cache read surface without deleting payloads`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val cache = CacheService(System::currentTimeMillis, ProfileTombstoneRegistry(), store, visibility)
        cache.awaitLoadCompleted()
        val identity = identity("dev")
        val other = identity("other", principal = "bob")

        cache.writeDetail(
            identity, "public",
            NacosConfiguration("secret.properties", "DEFAULT_GROUP", "public", "k=v", "properties")
        )
        cache.writeListPage(
            identity, "public", "page=1",
            ConfigListResponse(
                1, 1, 1,
                listOf(ConfigItem("1", "secret.properties", "DEFAULT_GROUP", "k=v", "properties", "public"))
            )
        )
        cache.replaceNamespaceIndex(
            identity, "public",
            listOf(NacosConfiguration("secret.properties", "DEFAULT_GROUP", "public", "", "properties"))
        )
        cache.writeDetail(
            other, "public",
            NacosConfiguration("other.properties", "DEFAULT_GROUP", "public", "ok=1", "properties")
        )

        visibility.reportCompleted(
            CompletedObservation(
                observation = 100,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authentication(401)
                )
            )
        )

        assertNull(cache.getConfigDetail(identity, "public", "secret.properties", "DEFAULT_GROUP"))
        assertNull(cache.getListPage(identity, "public", "page=1"))
        assertNull(cache.getNamespaceIndex(identity, "public"))
        assertTrue(cache.getAllCachedConfigurations(identity).isEmpty())
        val snap = cache.snapshot(identity)
        assertTrue(snap.isAccessBlocked)
        assertTrue(snap.configurations.isEmpty())
        assertTrue(snap.freshConfigurations.isEmpty())
        assertNull(snap.detail("public", "secret.properties", "DEFAULT_GROUP"))
        assertNull(snap.namespaceIndex("public"))

        // Other identity unaffected.
        assertEquals(
            "ok=1",
            cache.getConfigDetail(other, "public", "other.properties", "DEFAULT_GROUP")?.content
        )

        // Payloads still on the store / would be readable after recovery.
        assertNotNull(store.loadDetail(
            com.nanyin.nacos.search.services.CacheCoordinate.detailKey(
                identity, "public", "secret.properties", "DEFAULT_GROUP"
            )
        ))
        assertNotNull(store.loadListPages().entries.find {
            it.key.contains("secret") || it.value.data.pageItems.any { item -> item.dataId == "secret.properties" }
        } ?: store.loadListPages().values.find {
            it.data.pageItems.any { item -> item.dataId == "secret.properties" }
        })

        // Matching success reveals retained data again.
        visibility.reportCompleted(
            CompletedObservation(
                observation = 101,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.Success
            )
        )
        assertEquals(
            "k=v",
            cache.getConfigDetail(identity, "public", "secret.properties", "DEFAULT_GROUP")?.content
        )
        assertEquals(1, cache.getListPage(identity, "public", "page=1")?.totalCount)
        assertEquals(1, cache.getNamespaceIndex(identity, "public")?.size)
        assertFalse(cache.snapshot(identity).isAccessBlocked)
    }

    @Test
    fun `direct persistent detail reconstitution is hidden under a block`() = runBlocking {
        val store = InMemoryCacheStore()
        val identity = identity("dev")
        val detail = NacosConfiguration("seeded.properties", "DEFAULT_GROUP", "public", "seed=1", "properties")
        val key = com.nanyin.nacos.search.services.CacheCoordinate.detailKey(
            identity, "public", "seeded.properties", "DEFAULT_GROUP"
        )
        store.putDetail(
            key,
            CacheService.CacheEntry(
                CacheService.CacheEntryType.CONFIG_DETAIL,
                detail,
                createdAt = System.currentTimeMillis(),
                ttlMs = 300_000L,
                source = CacheService.CacheSource.REMOTE
            )
        )
        store.putVisibilityRecord(
            VisibilityScopes.identityAuthKey(identity),
            AccessVisibilityRecord.identityAuth(
                identity,
                AccessRefusalReason.AUTHENTICATION,
                recordedAtMillis = System.currentTimeMillis()
            )
        )

        val cache = CacheService(store)
        cache.awaitLoadCompleted()

        // Memory is empty for this key until reconstitution; block must still hide.
        assertNull(cache.getConfigDetail(identity, "public", "seeded.properties", "DEFAULT_GROUP"))
        assertTrue(cache.configurationVisibility(identity) is ConfigurationVisibility.Blocked)
        // Payload still stored.
        assertNotNull(store.loadDetail(key))
    }

    @Test
    fun `user clear drops visibility records with payloads`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val cache = CacheService(System::currentTimeMillis, ProfileTombstoneRegistry(), store, visibility)
        cache.awaitLoadCompleted()
        val identity = identity("dev")
        cache.writeDetail(
            identity, "public",
            NacosConfiguration("a.properties", "DEFAULT_GROUP", "public", "x=1", "properties")
        )
        visibility.reportCompleted(
            CompletedObservation(
                observation = 1,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authentication(401)
                )
            )
        )
        assertTrue(visibility.isIdentityAuthBlocked(identity))

        cache.clearAll()

        assertFalse(visibility.isIdentityAuthBlocked(identity))
        assertTrue(store.loadVisibilityRecords().isEmpty())
        assertTrue(store.loadDetails().isEmpty())
    }

    @Test
    fun `late lower-sequence auth failure after clearAll cannot re-block`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val cache = CacheService(System::currentTimeMillis, ProfileTombstoneRegistry(), store, visibility)
        cache.awaitLoadCompleted()
        val identity = identity("dev")
        visibility.reportCompleted(
            CompletedObservation(
                observation = 1,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authentication(401)
                )
            )
        )
        // clearAll takes ObservationSequence.process.next(); capture a high bar by
        // using a large explicit observation via a second clear path after seed.
        cache.clearAll(observation = 50)

        val reblocked = visibility.reportCompleted(
            CompletedObservation(
                observation = 10,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authentication(401)
                )
            )
        )
        assertFalse(reblocked)
        assertFalse(visibility.isIdentityAuthBlocked(identity))
        assertNull(
            cache.getConfigDetail(identity, "public", "a.properties", "DEFAULT_GROUP")
        )
    }

    /**
     * Mirrors CacheMutation.Clear order (fence then store wipe). A lower-seq
     * failure injected in the window after the fence and before/after the wipe
     * must not leave an orphan visibility record that would re-block on restart
     * (issue #123 re-review).
     */
    @Test
    fun `fence-before-store-clear rejects lower-seq put leaving no orphan`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val identity = identity("dev")
        visibility.reportCompleted(
            CompletedObservation(
                observation = 1,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authentication(401)
                )
            )
        )
        assertTrue(store.loadVisibilityRecords().isNotEmpty())

        // Production clear order: onUserClear first, then store.clear.
        visibility.onUserClear(observation = 50)
        val raced = visibility.reportCompleted(
            CompletedObservation(
                observation = 10,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authentication(401)
                )
            )
        )
        assertFalse(raced, "lower-seq failure after fence must be rejected")
        store.clear()

        assertFalse(visibility.isIdentityAuthBlocked(identity))
        assertTrue(store.loadVisibilityRecords().isEmpty())

        // Restart over the same store must not rehydrate a block.
        val rebuilt = AccessVisibility(store)
        rebuilt.hydrateFromStore()
        assertFalse(rebuilt.isIdentityAuthBlocked(identity))
    }

    @Test
    fun `cache rebuilt over same store restores block and first success clears it`() = runBlocking {
        val store = InMemoryCacheStore()
        val identity = identity("dev")
        val first = CacheService(store)
        first.awaitLoadCompleted()
        first.writeDetail(
            identity, "public",
            NacosConfiguration("keep.properties", "DEFAULT_GROUP", "public", "v=1", "properties")
        )
        first.accessVisibility().reportCompleted(
            CompletedObservation(
                observation = 50,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authentication(401)
                )
            )
        )
        assertNull(first.getConfigDetail(identity, "public", "keep.properties", "DEFAULT_GROUP"))

        // Reconstruct over the same store (simulates IDE restart).
        val second = CacheService(store)
        second.awaitLoadCompleted()
        assertTrue(second.accessVisibility().isIdentityAuthBlocked(identity))
        assertNull(second.getConfigDetail(identity, "public", "keep.properties", "DEFAULT_GROUP"))

        second.accessVisibility().reportCompleted(
            CompletedObservation(
                observation = 1, // lower than previous process
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.Success
            )
        )
        assertEquals(
            "v=1",
            second.getConfigDetail(identity, "public", "keep.properties", "DEFAULT_GROUP")?.content
        )
    }

    // --- Namespace-scoped configuration-read authorization (issue #124) ---

    @Test
    fun `namespace authz block hides only that namespace across every cache read surface`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val cache = CacheService(System::currentTimeMillis, ProfileTombstoneRegistry(), store, visibility)
        cache.awaitLoadCompleted()
        val identity = identity("dev")
        val other = identity("other", principal = "bob")

        cache.writeDetail(
            identity, "team-a",
            NacosConfiguration("secret.properties", "DEFAULT_GROUP", "team-a", "k=v", "properties")
        )
        cache.writeDetail(
            identity, "team-b",
            NacosConfiguration("open.properties", "DEFAULT_GROUP", "team-b", "ok=1", "properties")
        )
        cache.writeListPage(
            identity, "team-a", "page=1",
            ConfigListResponse(
                1, 1, 1,
                listOf(ConfigItem("1", "secret.properties", "DEFAULT_GROUP", "k=v", "properties", "team-a"))
            )
        )
        cache.writeListPage(
            identity, "team-b", "page=1",
            ConfigListResponse(
                1, 1, 1,
                listOf(ConfigItem("1", "open.properties", "DEFAULT_GROUP", "ok=1", "properties", "team-b"))
            )
        )
        cache.replaceNamespaceIndex(
            identity, "team-a",
            listOf(NacosConfiguration("secret.properties", "DEFAULT_GROUP", "team-a", "", "properties"))
        )
        cache.replaceNamespaceIndex(
            identity, "team-b",
            listOf(NacosConfiguration("open.properties", "DEFAULT_GROUP", "team-b", "", "properties"))
        )
        cache.writeDetail(
            other, "team-a",
            NacosConfiguration("other.properties", "DEFAULT_GROUP", "team-a", "peer=1", "properties")
        )

        visibility.reportCompleted(
            CompletedObservation(
                observation = 100,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                namespaceId = "team-a",
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authorization(403)
                )
            )
        )

        // Blocked namespace: every surface hidden.
        assertNull(cache.getConfigDetail(identity, "team-a", "secret.properties", "DEFAULT_GROUP"))
        assertNull(cache.getListPage(identity, "team-a", "page=1"))
        assertNull(cache.getNamespaceIndex(identity, "team-a"))

        // Other namespace under the same identity remains visible.
        assertEquals(
            "ok=1",
            cache.getConfigDetail(identity, "team-b", "open.properties", "DEFAULT_GROUP")?.content
        )
        assertEquals(1, cache.getListPage(identity, "team-b", "page=1")?.totalCount)
        assertEquals(1, cache.getNamespaceIndex(identity, "team-b")?.size)

        // Same namespace under another identity remains visible.
        assertEquals(
            "peer=1",
            cache.getConfigDetail(other, "team-a", "other.properties", "DEFAULT_GROUP")?.content
        )

        // Snapshot: identity not fully blocked; blocked namespace filtered out.
        val snap = cache.snapshot(identity)
        assertFalse(snap.isAccessBlocked)
        assertNull(snap.detail("team-a", "secret.properties", "DEFAULT_GROUP"))
        assertNull(snap.namespaceIndex("team-a"))
        assertNotNull(snap.detail("team-b", "open.properties", "DEFAULT_GROUP"))
        assertTrue(snap.configurations.none { it.configuration.dataId == "secret.properties" })
        assertTrue(snap.configurations.any { it.configuration.dataId == "open.properties" })

        // Payload still on the store.
        assertNotNull(
            store.loadDetail(
                com.nanyin.nacos.search.services.CacheCoordinate.detailKey(
                    identity, "team-a", "secret.properties", "DEFAULT_GROUP"
                )
            )
        )

        // Matching success in the blocked namespace reveals retained data.
        visibility.reportCompleted(
            CompletedObservation(
                observation = 101,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                namespaceId = "team-a",
                outcome = ObservationOutcome.Success
            )
        )
        assertEquals(
            "k=v",
            cache.getConfigDetail(identity, "team-a", "secret.properties", "DEFAULT_GROUP")?.content
        )
        assertEquals(1, cache.getListPage(identity, "team-a", "page=1")?.totalCount)
        assertEquals(1, cache.getNamespaceIndex(identity, "team-a")?.size)
    }

    @Test
    fun `authoritative not-found deletes coordinate without creating namespace block`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val cache = CacheService(System::currentTimeMillis, ProfileTombstoneRegistry(), store, visibility)
        cache.awaitLoadCompleted()
        val identity = identity("dev")

        cache.writeDetail(
            identity, "team-a",
            NacosConfiguration("gone.properties", "DEFAULT_GROUP", "team-a", "x=1", "properties")
        )
        cache.writeDetail(
            identity, "team-a",
            NacosConfiguration("stay.properties", "DEFAULT_GROUP", "team-a", "y=2", "properties")
        )

        // Authoritative not-found is a cache mutation, not a visibility report.
        // Use the process sequence so the delete outranks the seed writes above.
        cache.deleteDetailNotFound(
            identity, "team-a", "gone.properties", "DEFAULT_GROUP"
        )
        // Visibility report for NotFound must not create a Namespace block.
        visibility.reportCompleted(
            CompletedObservation(
                observation = com.nanyin.nacos.search.services.operations.ObservationSequence.process.next(),
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                namespaceId = "team-a",
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.NotFound()
                )
            )
        )

        assertNull(cache.getConfigDetail(identity, "team-a", "gone.properties", "DEFAULT_GROUP"))
        assertEquals(
            "y=2",
            cache.getConfigDetail(identity, "team-a", "stay.properties", "DEFAULT_GROUP")?.content
        )
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
    }

    @Test
    fun `direct reconstitution is hidden under a namespace authz block`() = runBlocking {
        val store = InMemoryCacheStore()
        val identity = identity("dev")
        val detail = NacosConfiguration("seeded.properties", "DEFAULT_GROUP", "team-a", "seed=1", "properties")
        val key = com.nanyin.nacos.search.services.CacheCoordinate.detailKey(
            identity, "team-a", "seeded.properties", "DEFAULT_GROUP"
        )
        store.putDetail(
            key,
            CacheService.CacheEntry(
                CacheService.CacheEntryType.CONFIG_DETAIL,
                detail,
                createdAt = System.currentTimeMillis(),
                ttlMs = 300_000L,
                source = CacheService.CacheSource.REMOTE
            )
        )
        store.putVisibilityRecord(
            VisibilityScopes.configurationReadKey(identity, "team-a"),
            AccessVisibilityRecord.configurationReadAuthz(
                identity,
                "team-a",
                recordedAtMillis = System.currentTimeMillis()
            )
        )

        val cache = CacheService(store)
        cache.awaitLoadCompleted()

        assertNull(cache.getConfigDetail(identity, "team-a", "seeded.properties", "DEFAULT_GROUP"))
        assertTrue(
            cache.configurationVisibility(identity, "team-a") is ConfigurationVisibility.Blocked
        )
    }

    // --- Release-gate lifecycle safety (issue #127) ---

    @Test
    fun `namespace invalidation keeps the block so a residual payload stays hidden across restart`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val cache = CacheService(System::currentTimeMillis, ProfileTombstoneRegistry(), store, visibility)
        cache.awaitLoadCompleted()
        val identity = identity("dev")

        cache.writeDetail(
            identity, "team-a",
            NacosConfiguration("secret.properties", "DEFAULT_GROUP", "team-a", "k=v", "properties")
        )
        visibility.reportCompleted(
            CompletedObservation(
                observation = 100,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                namespaceId = "team-a",
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authorization(403)
                )
            )
        )
        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))

        // Invalidation drops the payloads; it must not drop the gate that hides them.
        cache.invalidateNamespace(identity, "team-a")

        assertNull(cache.getConfigDetail(identity, "team-a", "secret.properties", "DEFAULT_GROUP"))
        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertTrue(store.loadVisibilityRecords().isNotEmpty())

        // Simulate the partial-wipe case: a payload file invalidation should
        // have removed lands back on the store before restart.
        store.putDetail(
            com.nanyin.nacos.search.services.CacheCoordinate.detailKey(
                identity, "team-a", "secret.properties", "DEFAULT_GROUP"
            ),
            CacheService.CacheEntry(
                CacheService.CacheEntryType.CONFIG_DETAIL,
                NacosConfiguration("secret.properties", "DEFAULT_GROUP", "team-a", "k=v", "properties"),
                createdAt = System.currentTimeMillis(),
                ttlMs = 300_000L,
                source = CacheService.CacheSource.REMOTE
            )
        )

        // Restart over the same store: the surviving block hides the residual.
        val rebuilt = CacheService(store)
        rebuilt.awaitLoadCompleted()
        assertTrue(rebuilt.accessVisibility().isConfigurationReadBlocked(identity, "team-a"))
        assertNull(rebuilt.getConfigDetail(identity, "team-a", "secret.properties", "DEFAULT_GROUP"))

        // Only a newer matching success reveals it again.
        rebuilt.accessVisibility().reportCompleted(
            CompletedObservation(
                observation = 101,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                namespaceId = "team-a",
                outcome = ObservationOutcome.Success
            )
        )
        assertEquals(
            "k=v",
            rebuilt.getConfigDetail(identity, "team-a", "secret.properties", "DEFAULT_GROUP")?.content
        )
    }

    @Test
    fun `partial visibility persistence failure keeps the block fail closed`() = runBlocking {
        val store = FailingVisibilityPutStore(InMemoryCacheStore())
        val visibility = AccessVisibility(store)
        val cache = CacheService(System::currentTimeMillis, ProfileTombstoneRegistry(), store, visibility)
        cache.awaitLoadCompleted()
        val identity = identity("dev")

        cache.writeDetail(
            identity, "public",
            NacosConfiguration("secret.properties", "DEFAULT_GROUP", "public", "k=v", "properties")
        )

        // The store refuses to persist the gate; the in-memory block must still apply.
        val accepted = visibility.reportCompleted(
            CompletedObservation(
                observation = 100,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authentication(401)
                )
            )
        )
        assertTrue(accepted, "block must apply in memory when persistence fails")
        assertTrue(visibility.isIdentityAuthBlocked(identity))
        assertNull(cache.getConfigDetail(identity, "public", "secret.properties", "DEFAULT_GROUP"))

        // A newer matching success still clears the in-memory block.
        visibility.reportCompleted(
            CompletedObservation(
                observation = 101,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.Success
            )
        )
        assertFalse(visibility.isIdentityAuthBlocked(identity))
        assertEquals(
            "k=v",
            cache.getConfigDetail(identity, "public", "secret.properties", "DEFAULT_GROUP")?.content
        )
    }

    @Test
    fun `profile deletion cannot reveal another profile's blocked payloads`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val cache = CacheService(System::currentTimeMillis, ProfileTombstoneRegistry(), store, visibility)
        cache.awaitLoadCompleted()
        val blocked = identity("dev")
        val sibling = identity("sibling", principal = "bob")

        cache.writeDetail(
            blocked, "public",
            NacosConfiguration("secret.properties", "DEFAULT_GROUP", "public", "k=v", "properties")
        )
        visibility.reportCompleted(
            CompletedObservation(
                observation = 100,
                identity = blocked,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    com.nanyin.nacos.search.services.operations.RemoteOperationError.Authentication(401)
                )
            )
        )

        // Deleting a sibling profile leaves the blocked profile's gate intact.
        cache.purgeProfile("sibling")
        assertTrue(visibility.isIdentityAuthBlocked(blocked))
        assertNull(cache.getConfigDetail(blocked, "public", "secret.properties", "DEFAULT_GROUP"))

        // Deleting the blocked profile drops gate and payloads together, so a
        // restart cannot reveal anything either.
        cache.purgeProfile("dev")
        assertFalse(visibility.isIdentityAuthBlocked(blocked))
        assertTrue(store.loadVisibilityRecords().isEmpty())
        assertNull(cache.getConfigDetail(blocked, "public", "secret.properties", "DEFAULT_GROUP"))
        val rebuilt = CacheService(store)
        rebuilt.awaitLoadCompleted()
        assertNull(rebuilt.getConfigDetail(blocked, "public", "secret.properties", "DEFAULT_GROUP"))
    }

    /**
     * The sibling of the test above, made deterministic. That one only caught this
     * by luck — it purged and read back within the microseconds a fire-and-forget
     * removal usually needs, so it passed alone and failed under suite load. Here
     * the store is slow to delete, which is the same window held open on purpose:
     * `purgeProfile` drops the in-memory entry synchronously, and if it does not
     * also await the store removal, the read behind it reconstitutes the payload
     * straight back out of the store (`discardGeneration` orders a reconstitution
     * racing the purge, not one starting after it returns).
     */
    @Test
    fun `profile purge does not return before the store rows are gone`() = runBlocking {
        val store = SlowRemovalStore(InMemoryCacheStore())
        val cache = CacheService(System::currentTimeMillis, ProfileTombstoneRegistry(), store, AccessVisibility(store))
        cache.awaitLoadCompleted()
        val identity = identity("dev")

        cache.writeDetail(
            identity, "public",
            NacosConfiguration("secret.properties", "DEFAULT_GROUP", "public", "k=v", "properties")
        )
        assertNotNull(cache.getConfigDetail(identity, "public", "secret.properties", "DEFAULT_GROUP"))

        cache.purgeProfile("dev")

        // Both surfaces the window was visible through: this cache's own read path,
        // which falls back to the store, and a restart that reads only the store.
        assertNull(cache.getConfigDetail(identity, "public", "secret.properties", "DEFAULT_GROUP"))
        val rebuilt = CacheService(store)
        rebuilt.awaitLoadCompleted()
        assertNull(rebuilt.getConfigDetail(identity, "public", "secret.properties", "DEFAULT_GROUP"))
    }

    /**
     * Store that takes a visible moment to delete, so an unawaited removal loses
     * the race every time instead of almost never.
     *
     * ponytail: a fixed delay, not a latch. A latch the test releases cannot work
     * here — `purgeProfile` blocks the calling thread, so the fixed version would
     * wait on a signal the test can no longer send. Raise the delay if this ever
     * flakes the other way.
     */
    private class SlowRemovalStore(
        private val delegate: InMemoryCacheStore
    ) : com.nanyin.nacos.search.services.CacheStore by delegate {
        override suspend fun removeDetail(key: String) {
            kotlinx.coroutines.delay(200)
            delegate.removeDetail(key)
        }
    }

    /** Store whose visibility-record writes fail, simulating partial persistence failure. */
    private class FailingVisibilityPutStore(
        private val delegate: InMemoryCacheStore
    ) : com.nanyin.nacos.search.services.CacheStore by delegate {
        override suspend fun putVisibilityRecord(key: String, record: AccessVisibilityRecord) {
            throw IllegalStateException("simulated partial persistence failure")
        }
    }

    private fun identity(
        profileId: String,
        principal: String = "alice"
    ): AccessIdentity = AccessIdentity.ofProfile(
        profileId = profileId,
        accessRevision = 1,
        canonicalEndpoint = "https://nacos.example",
        resolvedGeneration = NacosApiGeneration.V1,
        authMode = AuthMode.BASIC,
        principal = principal
    )
}
