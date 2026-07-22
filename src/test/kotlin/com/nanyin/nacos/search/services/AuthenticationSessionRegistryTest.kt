package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.V1AuthenticationStrategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class AuthenticationSessionRegistryTest {

    @Test
    fun `completed tokens are isolated by the complete access identity`() = runBlocking {
        val registry = AuthenticationSessionRegistry()
        val alice = executionKey("alice", accessRevision = 2, profileRevision = 3)
        val bob = executionKey("bob", accessRevision = 2, profileRevision = 3)
        val logins = AtomicInteger()

        val aliceToken = registry.getOrLogin(alice) { token(logins.incrementAndGet().toString()) }
        val bobToken = registry.getOrLogin(bob) { token(logins.incrementAndGet().toString()) }
        val reusedAliceToken = registry.getOrLogin(alice) { token("unexpected") }

        assertEquals("1", aliceToken?.value)
        assertEquals("2", bobToken?.value)
        assertEquals("1", reusedAliceToken?.value)
        assertEquals(2, logins.get())
    }

    @Test
    fun `changed execution policy does not join an earlier login flight`() = runBlocking {
        val registry = AuthenticationSessionRegistry()
        val oldPolicy = executionKey("alice", accessRevision = 2, profileRevision = 3)
        val changedPolicy = executionKey("alice", accessRevision = 2, profileRevision = 4)
        val firstLoginStarted = CompletableDeferred<Unit>()
        val releaseFirstLogin = CompletableDeferred<Unit>()
        val logins = AtomicInteger()

        val oldFlight = async {
            registry.getOrLogin(oldPolicy) {
                logins.incrementAndGet()
                firstLoginStarted.complete(Unit)
                releaseFirstLogin.await()
                token("old")
            }
        }
        firstLoginStarted.await()
        val changedFlight = async {
            registry.getOrLogin(changedPolicy) {
                logins.incrementAndGet()
                token("new")
            }
        }

        assertEquals("new", changedFlight.await()?.value)
        assertEquals(2, logins.get())
        releaseFirstLogin.complete(Unit)
        oldFlight.await()
        Unit
    }

    @Test
    fun `an invalidated token cannot be revived by an older login flight`() = runBlocking {
        val registry = AuthenticationSessionRegistry()
        val key = executionKey("alice", accessRevision = 2, profileRevision = 3)
        val loginStarted = CompletableDeferred<Unit>()
        val releaseLogin = CompletableDeferred<Unit>()

        val oldFlight = async {
            registry.getOrLogin(key) {
                loginStarted.complete(Unit)
                releaseLogin.await()
                token("stale")
            }
        }
        loginStarted.await()
        registry.invalidate(key.identity)
        releaseLogin.complete(Unit)
        oldFlight.await()

        assertNull(registry.completedToken(key.identity))
    }

    private fun executionKey(principal: String, accessRevision: Long, profileRevision: Long): AuthenticationExecutionKey =
        AuthenticationExecutionKey(
            identity = AccessIdentity.ofProfile(
                profileId = "dev",
                accessRevision = accessRevision,
                canonicalEndpoint = "https://nacos.example",
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.TOKEN,
                principal = principal
            ),
            profileRevision = profileRevision,
            strategy = V1AuthenticationStrategy.NACOS_PASSWORD
        )

    private fun token(value: String) = AuthenticationToken(value, System.currentTimeMillis() + 60_000)
}
