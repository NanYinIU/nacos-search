package com.nanyin.nacos.search.services.visibility

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.CacheStore
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.operations.ObservationHighWater
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Identity-wide authentication blocks: ordering, persistence, restart, and
 * isolation (issue #123).
 */
class AccessVisibilityTest {

    @Test
    fun `terminal authentication failure creates identity-wide block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        val applied = visibility.reportCompleted(
            authFailure(identity, observation = 1)
        )

        assertTrue(applied)
        assertTrue(visibility.isIdentityAuthBlocked(identity))
        assertInstanceOf(
            ConfigurationVisibility.Blocked::class.java,
            visibility.configurationVisibility(identity)
        )
    }

    @Test
    fun `invalid or expired nacos password token creates identity-wide block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        visibility.reportCompleted(
            CompletedObservation(
                observation = 1,
                identity = identity,
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    RemoteOperationError.InvalidOrExpiredNacosPasswordToken(401)
                )
            )
        )

        assertTrue(visibility.isIdentityAuthBlocked(identity))
    }

    @Test
    fun `connection failure does not create a block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        val applied = visibility.reportCompleted(
            CompletedObservation(
                observation = 1,
                identity = identity,
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(
                    RemoteOperationError.Connection(RuntimeException("dns"))
                )
            )
        )

        assertFalse(applied)
        assertFalse(visibility.isIdentityAuthBlocked(identity))
    }

    @Test
    fun `newer matching success clears the block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")
        visibility.reportCompleted(authFailure(identity, observation = 1))
        assertTrue(visibility.isIdentityAuthBlocked(identity))

        val cleared = visibility.reportCompleted(success(identity, observation = 2))

        assertTrue(cleared)
        assertFalse(visibility.isIdentityAuthBlocked(identity))
    }

    @Test
    fun `older late failure cannot re-block after newer success`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        visibility.reportCompleted(success(identity, observation = 5))
        val reblocked = visibility.reportCompleted(authFailure(identity, observation = 3))

        assertFalse(reblocked)
        assertFalse(visibility.isIdentityAuthBlocked(identity))
    }

    @Test
    fun `older late success cannot clear after newer refusal`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        visibility.reportCompleted(authFailure(identity, observation = 5))
        val cleared = visibility.reportCompleted(success(identity, observation = 3))

        assertFalse(cleared)
        assertTrue(visibility.isIdentityAuthBlocked(identity))
    }

    @Test
    fun `second identity at same endpoint remains unaffected`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val alice = identity("dev", principal = "alice")
        val bob = identity("dev", principal = "bob")

        visibility.reportCompleted(authFailure(alice, observation = 1))

        assertTrue(visibility.isIdentityAuthBlocked(alice))
        assertFalse(visibility.isIdentityAuthBlocked(bob))
    }

    @Test
    fun `identity differing only by resolved generation remains unaffected`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val v1 = identity("dev", generation = NacosApiGeneration.V1)
        val v3 = identity("dev", generation = NacosApiGeneration.V3)

        visibility.reportCompleted(authFailure(v1, observation = 1))

        assertTrue(visibility.isIdentityAuthBlocked(v1))
        assertFalse(visibility.isIdentityAuthBlocked(v3))
    }

    @Test
    fun `advancing access revision addresses a new unblocked identity`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val old = identity("dev", accessRevision = 1)
        val next = identity("dev", accessRevision = 2)

        visibility.reportCompleted(authFailure(old, observation = 1))

        assertTrue(visibility.isIdentityAuthBlocked(old))
        assertFalse(visibility.isIdentityAuthBlocked(next))
    }

    @Test
    fun `block survives reconstruction over the same store while high-water resets`() = runBlocking {
        val store = InMemoryCacheStore()
        val first = AccessVisibility(store, ObservationHighWater())
        val identity = identity("dev")
        first.reportCompleted(authFailure(identity, observation = 10))
        assertTrue(first.isIdentityAuthBlocked(identity))

        // Teardown + rebuild: new high-water, same store.
        val second = AccessVisibility(store, ObservationHighWater())
        second.hydrateFromStore()

        assertTrue(second.isIdentityAuthBlocked(identity))
        // Lower sequence than the previous process used still clears after restart.
        val cleared = second.reportCompleted(success(identity, observation = 1))
        assertTrue(cleared)
        assertFalse(second.isIdentityAuthBlocked(identity))
        assertNull(store.loadVisibilityRecords()[VisibilityScopes.identityAuthKey(identity)])
    }

    @Test
    fun `zero observation cannot clear a block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")
        visibility.reportCompleted(authFailure(identity, observation = 1))

        val cleared = visibility.reportCompleted(
            CompletedObservation(
                observation = 0L,
                identity = identity,
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
                outcome = ObservationOutcome.Success
            )
        )

        assertFalse(cleared)
        assertTrue(visibility.isIdentityAuthBlocked(identity))
    }

    @Test
    fun `purgeProfile removes visibility records for that profile only`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val dev = identity("dev")
        val sit = identity("sit")
        visibility.reportCompleted(authFailure(dev, observation = 1))
        visibility.reportCompleted(authFailure(sit, observation = 2))

        visibility.purgeProfile("dev")

        assertFalse(visibility.isIdentityAuthBlocked(dev))
        assertTrue(visibility.isIdentityAuthBlocked(sit))
        assertTrue(
            store.loadVisibilityRecords().keys.none { it.contains("|dev|") }
        )
    }

    @Test
    fun `success without a prior block still returns true when observation is accepted`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        val accepted = visibility.reportCompleted(success(identity, observation = 1))

        assertTrue(accepted)
        assertFalse(visibility.isIdentityAuthBlocked(identity))
    }

    @Test
    fun `failed store remove keeps the block fail closed`() = runBlocking {
        val delegate = InMemoryCacheStore()
        val store = object : CacheStore by delegate {
            override suspend fun removeVisibilityRecord(key: String) {
                throw java.io.IOException("disk full")
            }
        }
        val visibility = AccessVisibility(store)
        val identity = identity("dev")
        visibility.reportCompleted(authFailure(identity, observation = 1))
        assertTrue(visibility.isIdentityAuthBlocked(identity))

        val cleared = visibility.reportCompleted(success(identity, observation = 2))

        assertFalse(cleared)
        assertTrue(visibility.isIdentityAuthBlocked(identity))
    }

    @Test
    fun `user clear fences high-water so older late failure cannot re-block`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val identity = identity("dev")
        visibility.reportCompleted(authFailure(identity, observation = 1))
        assertTrue(visibility.isIdentityAuthBlocked(identity))

        // Clear at sequence 10 (store already wiped by cache clear in production).
        store.clear()
        visibility.onUserClear(observation = 10)
        assertFalse(visibility.isIdentityAuthBlocked(identity))

        // Auth failure that started before clear must not re-block.
        val reblocked = visibility.reportCompleted(authFailure(identity, observation = 5))
        assertFalse(reblocked)
        assertFalse(visibility.isIdentityAuthBlocked(identity))

        // A newer failure after clear still can block.
        assertTrue(visibility.reportCompleted(authFailure(identity, observation = 11)))
        assertTrue(visibility.isIdentityAuthBlocked(identity))
    }

    // --- Namespace-scoped configuration-read authorization (issue #124) ---

    @Test
    fun `configuration list authorization blocks only that identity and namespace`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        assertTrue(visibility.reportCompleted(authzFailure(identity, "team-a", observation = 1)))

        assertFalse(visibility.isIdentityAuthBlocked(identity))
        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-b"))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "public"))
        assertEquals(
            AccessRefusalReason.AUTHORIZATION,
            (visibility.configurationVisibility(identity, "team-a") as ConfigurationVisibility.Blocked).reason
        )
        assertTrue(visibility.configurationVisibility(identity) is ConfigurationVisibility.Visible)
    }

    @Test
    fun `configuration detail authorization produces the same namespace block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        // Detail and list share CONFIGURATION_READ + namespace; either source is enough.
        assertTrue(visibility.reportCompleted(authzFailure(identity, "public", observation = 1)))
        assertTrue(visibility.isConfigurationReadBlocked(identity, ""))
        assertTrue(visibility.isConfigurationReadBlocked(identity, "public"))
        assertTrue(visibility.isConfigurationReadBlocked(identity, null))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertFalse(visibility.isIdentityAuthBlocked(identity))
    }

    @Test
    fun `same namespace under another identity remains visible`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val alice = identity("dev", principal = "alice")
        val bob = identity("dev", principal = "bob")

        visibility.reportCompleted(authzFailure(alice, "team-a", observation = 1))

        assertTrue(visibility.isConfigurationReadBlocked(alice, "team-a"))
        assertFalse(visibility.isConfigurationReadBlocked(bob, "team-a"))
    }

    @Test
    fun `newer matching configuration read success clears the namespace block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")
        visibility.reportCompleted(authzFailure(identity, "team-a", observation = 1))
        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))

        val cleared = visibility.reportCompleted(
            success(identity, observation = 2, namespaceId = "team-a")
        )

        assertTrue(cleared)
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
    }

    @Test
    fun `success in another namespace does not clear the blocked namespace`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")
        visibility.reportCompleted(authzFailure(identity, "team-a", observation = 1))

        val accepted = visibility.reportCompleted(
            success(identity, observation = 2, namespaceId = "team-b")
        )

        assertTrue(accepted) // ordering for team-b advanced
        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-b"))
    }

    @Test
    fun `publish success does not clear a namespace configuration-read block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")
        visibility.reportCompleted(authzFailure(identity, "team-a", observation = 1))

        val accepted = visibility.reportCompleted(
            CompletedObservation(
                observation = 2,
                identity = identity,
                operationClass = VisibilityOperationClass.PUBLISH,
                namespaceId = "team-a",
                outcome = ObservationOutcome.Success
            )
        )

        // Publish success still clears identity-auth (none present) and advances
        // identity ordering, but must not clear the Namespace config-read block.
        assertTrue(accepted)
        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))
    }

    @Test
    fun `success for another identity does not clear the namespace block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val alice = identity("dev", principal = "alice")
        val bob = identity("dev", principal = "bob")
        visibility.reportCompleted(authzFailure(alice, "team-a", observation = 1))

        visibility.reportCompleted(success(bob, observation = 2, namespaceId = "team-a"))

        assertTrue(visibility.isConfigurationReadBlocked(alice, "team-a"))
        assertFalse(visibility.isConfigurationReadBlocked(bob, "team-a"))
    }

    @Test
    fun `older late namespace failure cannot re-block after newer success`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        visibility.reportCompleted(success(identity, observation = 5, namespaceId = "team-a"))
        val reblocked = visibility.reportCompleted(authzFailure(identity, "team-a", observation = 3))

        assertFalse(reblocked)
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
    }

    @Test
    fun `older late success cannot clear after newer namespace refusal`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        visibility.reportCompleted(authzFailure(identity, "team-a", observation = 5))
        // Namespace chain rejects observation 3; the identity half may still
        // advance (its mark was never raised by the Namespace refusal), but the
        // Namespace block must remain.
        visibility.reportCompleted(success(identity, observation = 3, namespaceId = "team-a"))

        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertNotNull(visibility.namespaceConfigReadBlock(identity, "team-a"))
    }

    @Test
    fun `authoritative not-found does not create a namespace block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        val applied = visibility.reportCompleted(
            CompletedObservation(
                observation = 1,
                identity = identity,
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
                namespaceId = "team-a",
                outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.NotFound())
            )
        )

        assertFalse(applied)
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
    }

    @Test
    fun `cancellation does not create a namespace block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        val applied = visibility.reportCompleted(
            CompletedObservation(
                observation = 1,
                identity = identity,
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
                namespaceId = "team-a",
                outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Cancelled())
            )
        )

        assertFalse(applied)
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
    }

    @Test
    fun `publish authorization does not create a configuration-read namespace block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        val applied = visibility.reportCompleted(
            CompletedObservation(
                observation = 1,
                identity = identity,
                operationClass = VisibilityOperationClass.PUBLISH,
                namespaceId = "team-a",
                outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authorization(403))
            )
        )

        assertFalse(applied)
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
    }

    @Test
    fun `namespace block survives reconstruction over the same store while high-water resets`() = runBlocking {
        val store = InMemoryCacheStore()
        val first = AccessVisibility(store, ObservationHighWater())
        val identity = identity("dev")
        first.reportCompleted(authzFailure(identity, "team-a", observation = 10))
        assertTrue(first.isConfigurationReadBlocked(identity, "team-a"))

        val second = AccessVisibility(store, ObservationHighWater())
        second.hydrateFromStore()

        assertTrue(second.isConfigurationReadBlocked(identity, "team-a"))
        val cleared = second.reportCompleted(success(identity, observation = 1, namespaceId = "team-a"))
        assertTrue(cleared)
        assertFalse(second.isConfigurationReadBlocked(identity, "team-a"))
        assertNull(
            store.loadVisibilityRecords()[VisibilityScopes.configurationReadKey(identity, "team-a")]
        )
    }

    @Test
    fun `blank and public namespace spellings share one block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        visibility.reportCompleted(authzFailure(identity, "", observation = 1))

        assertTrue(visibility.isConfigurationReadBlocked(identity, "public"))
        assertTrue(visibility.isConfigurationReadBlocked(identity, null))
        assertTrue(visibility.isConfigurationReadBlocked(identity, "  "))
    }

    @Test
    fun `purgeProfile removes namespace visibility records for that profile only`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val dev = identity("dev")
        val sit = identity("sit")
        visibility.reportCompleted(authzFailure(dev, "team-a", observation = 1))
        visibility.reportCompleted(authzFailure(sit, "team-a", observation = 2))

        visibility.purgeProfile("dev")

        assertFalse(visibility.isConfigurationReadBlocked(dev, "team-a"))
        assertTrue(visibility.isConfigurationReadBlocked(sit, "team-a"))
        assertTrue(
            store.loadVisibilityRecords().keys.none {
                it.startsWith("vis|cfgread|v2|dev|")
            }
        )
    }

    private fun identity(
        profileId: String,
        accessRevision: Long = 1,
        principal: String = "alice",
        generation: NacosApiGeneration = NacosApiGeneration.V1
    ): AccessIdentity = AccessIdentity.ofProfile(
        profileId = profileId,
        accessRevision = accessRevision,
        canonicalEndpoint = "https://nacos.example",
        resolvedGeneration = generation,
        authMode = AuthMode.BASIC,
        principal = principal
    )

    private fun authFailure(identity: AccessIdentity, observation: Long) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = VisibilityOperationClass.CONFIGURATION_READ,
            namespaceId = "public",
            outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authentication(401))
        )

    private fun authzFailure(identity: AccessIdentity, namespaceId: String, observation: Long) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = VisibilityOperationClass.CONFIGURATION_READ,
            namespaceId = namespaceId,
            outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authorization(403))
        )

    private fun success(
        identity: AccessIdentity,
        observation: Long,
        namespaceId: String = "public"
    ) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = VisibilityOperationClass.CONFIGURATION_READ,
            namespaceId = namespaceId,
            outcome = ObservationOutcome.Success
        )
}
