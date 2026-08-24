package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.models.ProfileIntent
import com.nanyin.nacos.search.services.operations.DiagnosticReport
import com.nanyin.nacos.search.services.operations.DiagnosticSnapshot
import com.nanyin.nacos.search.services.operations.DiagnosticStageResult
import com.nanyin.nacos.search.services.operations.DiscoveredNamespace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class SettingsDiscoveryLifecycleTest {

    @Test
    fun `diagnostic request derives one snapshot from the unsaved environment intent`() {
        val lifecycle = SettingsDiscoveryLifecycle { error("discovery must not run") }
        val intent = ProfileIntent(
            profileId = "dev",
            endpoint = "  https://nacos.example/nacos  ",
            apiPolicy = NacosApiPolicy.V3,
            authMode = AuthMode.HTTP_BASIC,
            principal = "  alice  ",
            secret = "secret",
            suggestedNamespace = "typed-namespace"
        )

        val request = lifecycle.beginDiagnostic(intent)

        assertEquals(
            DiagnosticSnapshot(
                endpoint = "https://nacos.example/nacos",
                apiPolicy = "V3",
                authStrategy = "HTTP_BASIC",
                principal = "alice",
                secret = "secret",
                namespaceId = "typed-namespace"
            ),
            request.snapshot
        )
    }

    @Test
    fun `options survive a suggested Namespace edit but every discovery identity edit clears them`() {
        val team = DiscoveredNamespace("team-id", "Team")
        val base = intent()
        val identityEdits = listOf(
            base.copy(endpoint = "https://other.example"),
            base.copy(apiPolicy = NacosApiPolicy.V1),
            base.copy(authMode = AuthMode.BEARER_TOKEN),
            base.copy(principal = "bob"),
            base.copy(secret = "rotated")
        )

        for (edited in identityEdits) {
            val lifecycle = SettingsDiscoveryLifecycle { error("discovery must not run") }
            val request = lifecycle.beginDiagnostic(base)
            lifecycle.completeDiagnostic(request, successfulReport(listOf(team)))

            assertEquals(
                SettingsNamespaceOptions.Available(listOf(team)),
                lifecycle.updateIntent(base.copy(suggestedNamespace = "typed-other")),
                "suggested Namespace must not be part of discovery identity"
            )
            assertEquals(
                SettingsNamespaceOptions.Empty,
                lifecycle.updateIntent(edited),
                "identity edit must invalidate options"
            )
        }
    }

    @Test
    fun `chooser reuses options supplied by connection diagnosis`() = runBlocking {
        var discoveryCalls = 0
        val team = DiscoveredNamespace("team-id", "Team")
        val lifecycle = SettingsDiscoveryLifecycle {
            discoveryCalls++
            Result.success(emptyList())
        }
        val intent = intent()
        lifecycle.completeDiagnostic(
            lifecycle.beginDiagnostic(intent),
            successfulReport(listOf(team))
        )

        val completion = lifecycle.discover(intent)

        assertEquals(
            SettingsDiscoveryCompletion.Applied(SettingsNamespaceOptions.Available(listOf(team))),
            completion
        )
        assertEquals(0, discoveryCalls)
    }

    @Test
    fun `concurrent chooser requests for one identity share one discovery`() = runBlocking {
        var discoveryCalls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Result<List<DiscoveredNamespace>>>()
        val team = DiscoveredNamespace("team-id", "Team")
        val lifecycle = SettingsDiscoveryLifecycle {
            discoveryCalls++
            started.complete(Unit)
            release.await()
        }

        val first = async { lifecycle.discover(intent()) }
        started.await()
        val second = async { lifecycle.discover(intent()) }
        yield()

        assertEquals(1, discoveryCalls)
        assertEquals(SettingsNamespaceOptions.Loading, lifecycle.updateIntent(intent()))
        release.complete(Result.success(listOf(team)))
        val expected =
            SettingsDiscoveryCompletion.Applied(SettingsNamespaceOptions.Available(listOf(team)))
        assertEquals(expected, first.await())
        assertEquals(expected, second.await())
    }

    @Test
    fun `chooser completion cannot repopulate options after its identity was superseded`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Result<List<DiscoveredNamespace>>>()
        val team = DiscoveredNamespace("team-id", "Team")
        val base = intent()
        val lifecycle = SettingsDiscoveryLifecycle {
            started.complete(Unit)
            release.await()
        }

        val stale = async { lifecycle.discover(base) }
        started.await()
        lifecycle.updateIntent(base.copy(endpoint = "https://other.example"))
        lifecycle.updateIntent(base)
        release.complete(Result.success(listOf(team)))

        assertEquals(SettingsDiscoveryCompletion.Stale, stale.await())
        assertEquals(SettingsNamespaceOptions.Empty, lifecycle.updateIntent(base))
    }

    @Test
    fun `diagnostic completion cannot repopulate options after its identity was superseded`() {
        val lifecycle = SettingsDiscoveryLifecycle { error("discovery must not run") }
        val base = intent()
        val stale = lifecycle.beginDiagnostic(base)
        lifecycle.updateIntent(base.copy(secret = "rotated"))
        lifecycle.updateIntent(base)

        val completion = lifecycle.completeDiagnostic(
            stale,
            successfulReport(listOf(DiscoveredNamespace("team-id", "Team")))
        )

        assertEquals(SettingsDiscoveryCompletion.Stale, completion)
        assertEquals(SettingsNamespaceOptions.Empty, lifecycle.updateIntent(base))
    }

    @Test
    fun `provider CancellationException propagates and does not publish Failed`() = runBlocking {
        val lifecycle = SettingsDiscoveryLifecycle {
            throw CancellationException("provider cancelled")
        }

        try {
            lifecycle.discover(intent())
            fail("expected CancellationException")
        } catch (error: CancellationException) {
            assertEquals("provider cancelled", error.message)
        }
        assertEquals(SettingsNamespaceOptions.Empty, lifecycle.updateIntent(intent()))
    }

    @Test
    fun `owner cancellation terminates shared waiters`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val lifecycle = SettingsDiscoveryLifecycle {
            started.complete(Unit)
            awaitCancellation()
        }
        val waiterDone = CompletableDeferred<Throwable?>()
        val owner = launch { lifecycle.discover(intent()) }
        started.await()
        val waiter = launch {
            try {
                lifecycle.discover(intent())
                waiterDone.complete(null)
            } catch (error: Throwable) {
                waiterDone.complete(error)
                if (error is CancellationException) throw error
            }
        }
        yield()
        owner.cancel()
        val waiterError = withTimeout(1_000) { waiterDone.await() }
        assertTrue(waiterError is CancellationException, waiterError.toString())
        owner.join()
        waiter.join()
        assertEquals(SettingsNamespaceOptions.Empty, lifecycle.updateIntent(intent()))
    }

    @Test
    fun `a later request starts a new flight after cancellation`() = runBlocking {
        var discoveryCalls = 0
        val started = CompletableDeferred<Unit>()
        val team = DiscoveredNamespace("team-id", "Team")
        val lifecycle = SettingsDiscoveryLifecycle {
            discoveryCalls++
            if (discoveryCalls == 1) {
                started.complete(Unit)
                awaitCancellation()
            }
            Result.success(listOf(team))
        }

        val first = launch { lifecycle.discover(intent()) }
        started.await()
        first.cancel()
        first.join()
        assertEquals(1, discoveryCalls)
        assertEquals(SettingsNamespaceOptions.Empty, lifecycle.updateIntent(intent()))

        val retry = lifecycle.discover(intent())
        assertEquals(
            SettingsDiscoveryCompletion.Applied(SettingsNamespaceOptions.Available(listOf(team))),
            retry
        )
        assertEquals(2, discoveryCalls)
    }

    @Test
    fun `cancelling a superseded request does not invalidate the current flight`() = runBlocking {
        val aStarted = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Result<List<DiscoveredNamespace>>>()
        val team = DiscoveredNamespace("team-id", "Team")
        var discoveryCalls = 0
        val lifecycle = SettingsDiscoveryLifecycle {
            discoveryCalls++
            if (discoveryCalls == 1) {
                aStarted.complete(Unit)
                awaitCancellation()
            }
            bStarted.complete(Unit)
            bRelease.await()
        }
        val aIntent = intent()
        val bIntent = aIntent.copy(endpoint = "https://other.example")
        val stale = launch { lifecycle.discover(aIntent) }
        aStarted.await()
        val current = async { lifecycle.discover(bIntent) }
        bStarted.await()
        stale.cancel()
        stale.join()

        assertEquals(SettingsNamespaceOptions.Loading, lifecycle.options())
        bRelease.complete(Result.success(listOf(team)))
        assertEquals(
            SettingsDiscoveryCompletion.Applied(SettingsNamespaceOptions.Available(listOf(team))),
            current.await()
        )
        assertEquals(2, discoveryCalls)
    }

    @Test
    fun `ordinary provider failure still publishes Failed`() = runBlocking {
        val lifecycle = SettingsDiscoveryLifecycle {
            Result.failure(IllegalStateException("down"))
        }

        assertEquals(
            SettingsDiscoveryCompletion.Applied(SettingsNamespaceOptions.Failed),
            lifecycle.discover(intent())
        )
    }

    private fun intent() = ProfileIntent(
        profileId = "dev",
        endpoint = "https://nacos.example",
        apiPolicy = NacosApiPolicy.V3,
        authMode = AuthMode.HTTP_BASIC,
        principal = "alice",
        secret = "secret",
        suggestedNamespace = "public"
    )

    private fun successfulReport(options: List<DiscoveredNamespace>) = DiagnosticReport(
        connected = true,
        stages = listOf(
            DiagnosticStageResult("discovery", success = true, durationMillis = 1)
        ),
        manualNamespaceRequired = false,
        discoveredNamespaces = options
    )
}
