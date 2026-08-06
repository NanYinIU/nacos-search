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
import com.nanyin.nacos.search.services.clearAll
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
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
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
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
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
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
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
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
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
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
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
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
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
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
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
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
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
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
                outcome = ObservationOutcome.Success
            )
        )
        assertEquals(
            "v=1",
            second.getConfigDetail(identity, "public", "keep.properties", "DEFAULT_GROUP")?.content
        )
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
