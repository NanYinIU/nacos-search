package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.models.ProfileIntent
import com.nanyin.nacos.search.services.operations.DiagnosticReport
import com.nanyin.nacos.search.services.operations.DiagnosticSnapshot
import com.nanyin.nacos.search.services.operations.DiagnosticStageResult
import com.nanyin.nacos.search.services.operations.DiscoveredNamespace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
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
