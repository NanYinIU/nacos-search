package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.V1AuthenticationStrategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@TestApplication
class AuthenticationSessionRegistryTest {

    @Test
    fun `application owns one authentication session registry`() {
        val first = ApplicationManager.getApplication().getService(AuthenticationSessionRegistry::class.java)
        val second = ApplicationManager.getApplication().getService(AuthenticationSessionRegistry::class.java)

        assertNotNull(first)
        assertSame(first, second)
    }

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

    @Test
    fun `identities that differ in auth mode never share a completed token`() = runBlocking {
        val registry = AuthenticationSessionRegistry()
        val password = executionKey("alice", accessRevision = 2, profileRevision = 3, authMode = AuthMode.NACOS_PASSWORD)
        val basic = executionKey("alice", accessRevision = 2, profileRevision = 3, authMode = AuthMode.HTTP_BASIC)
        val logins = AtomicInteger()

        registry.getOrLogin(password) { token(logins.incrementAndGet().toString()) }
        registry.getOrLogin(basic) { token(logins.incrementAndGet().toString()) }

        assertEquals(2, logins.get())
        assertEquals("1", registry.completedToken(password.identity)?.value)
        assertEquals("2", registry.completedToken(basic.identity)?.value)
    }

    @Test
    fun `invalidateProfile rejects active and queued in-flight login callers`() = runBlocking {
        val registry = AuthenticationSessionRegistry()
        val key = executionKey("alice", accessRevision = 2, profileRevision = 3)
        val sibling = executionKey(
            "bob",
            accessRevision = 2,
            profileRevision = 3,
            profileId = "sibling"
        )
        val loginStarted = CompletableDeferred<Unit>()
        val releaseLogin = CompletableDeferred<Unit>()
        val logins = AtomicInteger()

        registry.getOrLogin(sibling) { token("sibling") }

        val flight = async {
            registry.getOrLogin(key) {
                logins.incrementAndGet()
                loginStarted.complete(Unit)
                releaseLogin.await()
                token("after-delete")
            }
        }
        loginStarted.await()
        // UNDISPATCHED runs until the mutex suspension, so this caller is
        // definitely attached to the old flight before deletion begins.
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            registry.getOrLogin(key) {
                logins.incrementAndGet()
                token("queued-after-delete")
            }
        }
        // Profile deletion cleanup: bump epochs and drop tokens/locks, but keep
        // the epoch fence so neither active nor queued old-flight callers can
        // serve, store, or start a login after deletion.
        registry.invalidateProfile("dev")
        releaseLogin.complete(Unit)
        val lateToken = flight.await()

        assertNull(lateToken)
        assertNull(queued.await())
        assertEquals(1, logins.get())
        assertNull(registry.completedToken(key.identity))
        assertEquals("sibling", registry.completedToken(sibling.identity)?.value)
        // A deleted profile id stays fenced for the application lifetime.
        val next = registry.getOrLogin(key) { token("fresh") }
        assertNull(next)
    }

    @Test
    fun `invalidateProfile rejects a captured caller that has not joined a login flight`() = runBlocking {
        val registry = AuthenticationSessionRegistry()
        val key = executionKey("alice", accessRevision = 2, profileRevision = 3)
        val logins = AtomicInteger()

        registry.invalidateProfile("dev")
        val rejected = registry.getOrLogin(key) {
            logins.incrementAndGet()
            token("after-delete")
        }

        assertNull(rejected)
        assertEquals(0, logins.get())
        assertNull(registry.completedToken(key.identity))
    }

    @Test
    fun `profile invalidation wins at the final token publication seam`() = runBlocking {
        val publicationPaused = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val registry = AuthenticationSessionRegistry {
            publicationPaused.countDown()
            assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
            System.currentTimeMillis()
        }
        val key = executionKey("alice", accessRevision = 2, profileRevision = 3)

        val flight = async(Dispatchers.Default) {
            registry.getOrLogin(key) { token("late-publication") }
        }
        assertTrue(publicationPaused.await(5, TimeUnit.SECONDS))
        registry.invalidateProfile("dev")
        releasePublication.countDown()

        assertNull(flight.await())
        assertNull(registry.completedToken(key.identity))
    }

    private fun executionKey(
        principal: String,
        accessRevision: Long,
        profileRevision: Long,
        authMode: AuthMode = AuthMode.TOKEN,
        generation: NacosApiGeneration = NacosApiGeneration.V1,
        profileId: String = "dev"
    ): AuthenticationExecutionKey =
        AuthenticationExecutionKey(
            identity = AccessIdentity.ofProfile(
                profileId = profileId,
                accessRevision = accessRevision,
                canonicalEndpoint = "https://nacos.example",
                resolvedGeneration = generation,
                authMode = authMode,
                principal = principal
            ),
            profileRevision = profileRevision,
            strategy = when (authMode) {
                AuthMode.HTTP_BASIC, AuthMode.BASIC -> V1AuthenticationStrategy.HTTP_BASIC
                AuthMode.BEARER_TOKEN -> V1AuthenticationStrategy.BEARER_TOKEN
                AuthMode.ANONYMOUS -> V1AuthenticationStrategy.ANONYMOUS
                else -> V1AuthenticationStrategy.NACOS_PASSWORD
            }
        )

    private fun token(value: String) = AuthenticationToken(value, System.currentTimeMillis() + 60_000)
}
