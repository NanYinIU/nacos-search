package com.nanyin.nacos.search.services.visibility

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.CacheStore
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.operations.ObservationHighWater
import com.nanyin.nacos.search.services.operations.ProtocolCapability
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
import java.util.concurrent.atomic.AtomicInteger

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
                operationClass = ProtocolCapability.CONFIGURATION_READ,
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
                operationClass = ProtocolCapability.CONFIGURATION_READ,
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
                operationClass = ProtocolCapability.CONFIGURATION_READ,
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
                operationClass = ProtocolCapability.PUBLISH,
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
                operationClass = ProtocolCapability.CONFIGURATION_READ,
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
                operationClass = ProtocolCapability.CONFIGURATION_READ,
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
                operationClass = ProtocolCapability.PUBLISH,
                namespaceId = "team-a",
                outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authorization(403))
            )
        )

        // Issue #125: the denial is recorded in the publish capability's own
        // state, but never in the configuration-read or identity-wide state.
        assertTrue(applied)
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertFalse(visibility.isIdentityAuthBlocked(identity))
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

    // --- Optional-capability isolation (issue #125) ---

    @Test
    fun `publish authorization denial blocks only the publish capability`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        val applied = visibility.reportCompleted(publishFailure(identity, "team-a", observation = 1))

        assertTrue(applied, "publish denial must be recorded in its own capability state")
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
        assertEquals(
            AccessVisibilityRecord.PUBLISH,
            visibility.publishAuthBlock(identity, "team-a")?.capability
        )
        // Configuration-read surfaces never consult the publish block.
        assertFalse(visibility.isIdentityAuthBlocked(identity))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-b"))
        assertTrue(visibility.configurationVisibility(identity, "team-a") is ConfigurationVisibility.Visible)
        // Other capability blocks unaffected.
        assertNull(visibility.discoveryAuthBlock(identity))
        assertNull(visibility.historyAuthBlock(identity, "team-a"))
    }

    @Test
    fun `namespace discovery authorization denial blocks only the discovery capability`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        val applied = visibility.reportCompleted(discoveryFailure(identity, observation = 1))

        assertTrue(applied)
        assertNotNull(visibility.discoveryAuthBlock(identity))
        assertEquals(
            AccessVisibilityRecord.NAMESPACE_DISCOVERY,
            visibility.discoveryAuthBlock(identity)?.capability
        )
        assertNull(visibility.discoveryAuthBlock(identity)?.namespaceId)
        assertFalse(visibility.isIdentityAuthBlocked(identity))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertNull(visibility.publishAuthBlock(identity, "team-a"))
        assertNull(visibility.historyAuthBlock(identity, "team-a"))
    }

    @Test
    fun `history authorization denial blocks only the history capability`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        val applied = visibility.reportCompleted(historyFailure(identity, "team-a", observation = 1))

        assertTrue(applied)
        assertNotNull(visibility.historyAuthBlock(identity, "team-a"))
        assertEquals(
            AccessVisibilityRecord.HISTORY,
            visibility.historyAuthBlock(identity, "team-a")?.capability
        )
        assertFalse(visibility.isIdentityAuthBlocked(identity))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertNull(visibility.publishAuthBlock(identity, "team-a"))
        assertNull(visibility.discoveryAuthBlock(identity))
    }

    @Test
    fun `newer matching publish success clears only that publish block`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val identity = identity("dev")
        visibility.reportCompleted(publishFailure(identity, "team-a", observation = 1))
        visibility.reportCompleted(historyFailure(identity, "team-a", observation = 2))
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
        assertNotNull(store.loadVisibilityRecords()[VisibilityScopes.publishKey(identity, "team-a")])

        val cleared = visibility.reportCompleted(publishSuccess(identity, "team-a", observation = 3))

        assertTrue(cleared)
        assertNull(visibility.publishAuthBlock(identity, "team-a"))
        // Matching scope only: the history block and any readable data survive.
        assertNotNull(visibility.historyAuthBlock(identity, "team-a"))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
        // Store record is gone.
        assertNull(store.loadVisibilityRecords()[VisibilityScopes.publishKey(identity, "team-a")])
    }

    @Test
    fun `publish success in another namespace does not clear a publish block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")
        visibility.reportCompleted(publishFailure(identity, "team-a", observation = 1))

        val accepted = visibility.reportCompleted(publishSuccess(identity, "team-b", observation = 2))

        assertTrue(accepted) // ordering advanced for team-b
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
        assertNull(visibility.publishAuthBlock(identity, "team-b"))
    }

    @Test
    fun `configuration read success does not clear a publish block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")
        visibility.reportCompleted(publishFailure(identity, "team-a", observation = 1))

        val accepted = visibility.reportCompleted(success(identity, observation = 2, namespaceId = "team-a"))

        assertTrue(accepted)
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
    }

    @Test
    fun `discovery success clears only the discovery block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")
        visibility.reportCompleted(discoveryFailure(identity, observation = 1))
        visibility.reportCompleted(publishFailure(identity, "team-a", observation = 2))

        val cleared = visibility.reportCompleted(discoverySuccess(identity, observation = 3))

        assertTrue(cleared)
        assertNull(visibility.discoveryAuthBlock(identity))
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
    }

    @Test
    fun `history success clears only the matching history block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")
        visibility.reportCompleted(historyFailure(identity, "team-a", observation = 1))

        val cleared = visibility.reportCompleted(historySuccess(identity, "team-a", observation = 3))

        assertTrue(cleared)
        assertNull(visibility.historyAuthBlock(identity, "team-a"))
        // A history success in another namespace must not clear this one.
        visibility.reportCompleted(historyFailure(identity, "team-a", observation = 4))
        visibility.reportCompleted(historySuccess(identity, "team-b", observation = 5))
        assertNotNull(visibility.historyAuthBlock(identity, "team-a"))
    }

    @Test
    fun `probe success does not clear a configuration read block`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")
        visibility.reportCompleted(authzFailure(identity, "team-a", observation = 1))

        val accepted = visibility.reportCompleted(contactSuccess(identity, observation = 2))

        // Contact proves authentication and advances identity ordering, but
        // clears no capability scope.
        assertTrue(accepted)
        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertNotNull(visibility.namespaceConfigReadBlock(identity, "team-a"))
    }

    @Test
    fun `authentication failure from a capability operation still blocks the identity`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        val applied = visibility.reportCompleted(
            CompletedObservation(
                observation = 1,
                identity = identity,
                operationClass = ProtocolCapability.PUBLISH,
                namespaceId = "team-a",
                outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authentication(401))
            )
        )

        assertTrue(applied)
        assertTrue(visibility.isIdentityAuthBlocked(identity))
        assertNull(visibility.publishAuthBlock(identity, "team-a"))
    }

    @Test
    fun `optional capability block survives reconstruction while high-water resets`() = runBlocking {
        val store = InMemoryCacheStore()
        val first = AccessVisibility(store, ObservationHighWater())
        val identity = identity("dev")
        first.reportCompleted(publishFailure(identity, "team-a", observation = 10))
        assertNotNull(first.publishAuthBlock(identity, "team-a"))

        val second = AccessVisibility(store, ObservationHighWater())
        second.hydrateFromStore()

        assertNotNull(second.publishAuthBlock(identity, "team-a"))
        val cleared = second.reportCompleted(publishSuccess(identity, "team-a", observation = 1))
        assertTrue(cleared)
        assertNull(second.publishAuthBlock(identity, "team-a"))
    }

    @Test
    fun `older late capability failure cannot re-block after newer capability success`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        visibility.reportCompleted(publishSuccess(identity, "team-a", observation = 5))
        val reblocked = visibility.reportCompleted(publishFailure(identity, "team-a", observation = 3))

        assertFalse(reblocked)
        assertNull(visibility.publishAuthBlock(identity, "team-a"))
    }

    @Test
    fun `older late capability success cannot clear after newer capability refusal`() = runBlocking {
        val visibility = AccessVisibility(InMemoryCacheStore())
        val identity = identity("dev")

        visibility.reportCompleted(publishFailure(identity, "team-a", observation = 5))
        visibility.reportCompleted(publishSuccess(identity, "team-a", observation = 3))

        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
    }

    @Test
    fun `purgeProfile removes optional capability records for that profile only`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val dev = identity("dev")
        val sit = identity("sit")
        visibility.reportCompleted(publishFailure(dev, "team-a", observation = 1))
        visibility.reportCompleted(discoveryFailure(sit, observation = 2))

        visibility.purgeProfile("dev")

        assertNull(visibility.publishAuthBlock(dev, "team-a"))
        assertNotNull(visibility.discoveryAuthBlock(sit))
        assertTrue(
            store.loadVisibilityRecords().keys.none {
                it.startsWith("vis|publish|v2|dev|") ||
                    it.startsWith("vis|discovery|v2|dev|") ||
                    it.startsWith("vis|history|v2|dev|")
            }
        )
    }

    @Test
    fun `user clear removes optional capability blocks and fences older failures`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val identity = identity("dev")
        visibility.reportCompleted(publishFailure(identity, "team-a", observation = 1))
        visibility.reportCompleted(discoveryFailure(identity, observation = 2))
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))

        store.clear()
        visibility.onUserClear(observation = 10)

        assertNull(visibility.publishAuthBlock(identity, "team-a"))
        assertNull(visibility.discoveryAuthBlock(identity))
        // A failure that started before clear must not re-block.
        assertFalse(visibility.reportCompleted(publishFailure(identity, "team-a", observation = 5)))
        assertNull(visibility.publishAuthBlock(identity, "team-a"))
        // A newer failure after clear still records.
        assertTrue(visibility.reportCompleted(publishFailure(identity, "team-a", observation = 11)))
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
    }

    @Test
    fun `configuration read authorization does not record publish discovery or history blocks`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val identity = identity("dev")

        assertTrue(visibility.reportCompleted(authzFailure(identity, "team-a", observation = 1)))

        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertNull(visibility.publishAuthBlock(identity, "team-a"))
        assertNull(visibility.discoveryAuthBlock(identity))
        assertNull(visibility.historyAuthBlock(identity, "team-a"))
        val persisted = store.loadVisibilityRecords()
        assertEquals(1, persisted.size)
        assertEquals(
            AccessVisibilityRecord.CONFIGURATION_READ,
            persisted.values.single().capability
        )
    }

    @Test
    fun `configuration read refusal leaves existing publish discovery and history blocks standing`() = runBlocking {
        val store = InMemoryCacheStore()
        val visibility = AccessVisibility(store)
        val identity = identity("dev")
        visibility.reportCompleted(publishFailure(identity, "team-a", observation = 1))
        visibility.reportCompleted(discoveryFailure(identity, observation = 2))
        visibility.reportCompleted(historyFailure(identity, "team-a", observation = 3))

        assertTrue(visibility.reportCompleted(authzFailure(identity, "team-a", observation = 4)))

        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
        assertNotNull(visibility.discoveryAuthBlock(identity))
        assertNotNull(visibility.historyAuthBlock(identity, "team-a"))
        val persisted = store.loadVisibilityRecords().values.map { it.capability }.toSet()
        assertEquals(
            setOf(
                AccessVisibilityRecord.CONFIGURATION_READ,
                AccessVisibilityRecord.PUBLISH,
                AccessVisibilityRecord.NAMESPACE_DISCOVERY,
                AccessVisibilityRecord.HISTORY
            ),
            persisted
        )
        assertTrue(visibility.reportCompleted(publishFailure(identity, "team-b", observation = 5)))
        assertTrue(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
    }

    @Test
    fun `rejected older capability observation changes neither memory nor durable state`() = runBlocking {
        val counted = CountingVisibilityStore(InMemoryCacheStore())
        val visibility = AccessVisibility(counted)
        val identity = identity("dev")

        assertTrue(visibility.reportCompleted(publishFailure(identity, "team-a", observation = 5)))
        assertEquals(1, counted.puts.get())
        assertEquals(0, counted.removes.get())
        val afterBlock = counted.delegate.loadVisibilityRecords()

        assertFalse(visibility.reportCompleted(publishFailure(identity, "team-a", observation = 3)))
        // Identity ordering may still advance on a success; the capability
        // record must not move (issue #237 / ADR-0020).
        visibility.reportCompleted(publishSuccess(identity, "team-a", observation = 3))

        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
        assertEquals(1, counted.puts.get(), "rejected older observations must not put again")
        assertEquals(0, counted.removes.get(), "rejected older observations must not remove")
        assertEquals(afterBlock, counted.delegate.loadVisibilityRecords())
    }

    @Test
    fun `accepted capability block and matching clear each touch the store once`() = runBlocking {
        val counted = CountingVisibilityStore(InMemoryCacheStore())
        val visibility = AccessVisibility(counted)
        val identity = identity("dev")

        assertTrue(visibility.reportCompleted(authzFailure(identity, "team-a", observation = 1)))
        assertTrue(visibility.reportCompleted(publishFailure(identity, "team-a", observation = 2)))
        assertTrue(visibility.reportCompleted(discoveryFailure(identity, observation = 3)))
        assertTrue(visibility.reportCompleted(historyFailure(identity, "team-a", observation = 4)))
        assertEquals(4, counted.puts.get())
        assertEquals(0, counted.removes.get())

        assertTrue(visibility.reportCompleted(success(identity, observation = 5, namespaceId = "team-a")))
        assertTrue(visibility.reportCompleted(publishSuccess(identity, "team-a", observation = 6)))
        assertTrue(visibility.reportCompleted(discoverySuccess(identity, observation = 7)))
        assertTrue(visibility.reportCompleted(historySuccess(identity, "team-a", observation = 8)))

        assertEquals(4, counted.puts.get(), "clears must not write a replacement record")
        assertEquals(4, counted.removes.get())
        assertTrue(counted.delegate.loadVisibilityRecords().isEmpty())
        assertFalse(visibility.isConfigurationReadBlocked(identity, "team-a"))
        assertNull(visibility.publishAuthBlock(identity, "team-a"))
        assertNull(visibility.discoveryAuthBlock(identity))
        assertNull(visibility.historyAuthBlock(identity, "team-a"))
    }

    @Test
    fun `failed store remove keeps a capability block fail closed`() = runBlocking {
        val delegate = InMemoryCacheStore()
        val store = object : CacheStore by delegate {
            override suspend fun removeVisibilityRecord(key: String) {
                throw java.io.IOException("disk full")
            }
        }
        val visibility = AccessVisibility(store)
        val identity = identity("dev")
        visibility.reportCompleted(publishFailure(identity, "team-a", observation = 1))
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))

        visibility.reportCompleted(publishSuccess(identity, "team-a", observation = 2))

        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
        assertNotNull(delegate.loadVisibilityRecords()[VisibilityScopes.publishKey(identity, "team-a")])
        // Capability high-water must not rise on a failed durable clear, so a
        // later matching success is not locked out of retrying the remove.
        visibility.reportCompleted(publishSuccess(identity, "team-a", observation = 3))
        assertNotNull(visibility.publishAuthBlock(identity, "team-a"))
    }

    private class CountingVisibilityStore(
        val delegate: InMemoryCacheStore
    ) : CacheStore by delegate {
        val puts = AtomicInteger(0)
        val removes = AtomicInteger(0)

        override suspend fun putVisibilityRecord(key: String, record: AccessVisibilityRecord) {
            puts.incrementAndGet()
            delegate.putVisibilityRecord(key, record)
        }

        override suspend fun removeVisibilityRecord(key: String) {
            removes.incrementAndGet()
            delegate.removeVisibilityRecord(key)
        }
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
            operationClass = ProtocolCapability.CONFIGURATION_READ,
            namespaceId = "public",
            outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authentication(401))
        )

    private fun authzFailure(identity: AccessIdentity, namespaceId: String, observation: Long) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = ProtocolCapability.CONFIGURATION_READ,
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
            operationClass = ProtocolCapability.CONFIGURATION_READ,
            namespaceId = namespaceId,
            outcome = ObservationOutcome.Success
        )

    private fun publishFailure(identity: AccessIdentity, namespaceId: String, observation: Long) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = ProtocolCapability.PUBLISH,
            namespaceId = namespaceId,
            outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authorization(403))
        )

    private fun discoveryFailure(identity: AccessIdentity, observation: Long) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = ProtocolCapability.NAMESPACE_DISCOVERY,
            outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authorization(403))
        )

    private fun historyFailure(identity: AccessIdentity, namespaceId: String, observation: Long) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = ProtocolCapability.HISTORY,
            namespaceId = namespaceId,
            outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authorization(403))
        )

    private fun publishSuccess(identity: AccessIdentity, namespaceId: String, observation: Long) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = ProtocolCapability.PUBLISH,
            namespaceId = namespaceId,
            outcome = ObservationOutcome.Success
        )

    private fun discoverySuccess(identity: AccessIdentity, observation: Long) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = ProtocolCapability.NAMESPACE_DISCOVERY,
            outcome = ObservationOutcome.Success
        )

    private fun historySuccess(identity: AccessIdentity, namespaceId: String, observation: Long) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = ProtocolCapability.HISTORY,
            namespaceId = namespaceId,
            outcome = ObservationOutcome.Success
        )

    private fun contactSuccess(identity: AccessIdentity, observation: Long) =
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = ProtocolCapability.AUTHENTICATED_CONTACT,
            namespaceId = "public",
            outcome = ObservationOutcome.Success
        )
}
