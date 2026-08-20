package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.ProfileIntent
import com.nanyin.nacos.search.services.operations.DiagnosticReport
import com.nanyin.nacos.search.services.operations.DiagnosticSnapshot
import com.nanyin.nacos.search.services.operations.DiscoveredNamespace
import kotlinx.coroutines.CompletableDeferred

/**
 * Owns the in-memory lifecycle for Namespace options derived from an unsaved
 * settings intent. It has no persistence or credential-store dependencies.
 */
class SettingsDiscoveryLifecycle(
    private val discoverNamespaces: suspend (DiagnosticSnapshot) -> Result<List<DiscoveredNamespace>>
) {
    private val lock = Any()
    private var currentIdentity: DiscoveryIdentity? = null
    private var currentToken = Any()
    private var currentOptions: SettingsNamespaceOptions = SettingsNamespaceOptions.Empty
    private var inFlight: DiscoveryFlight? = null

    fun beginDiagnostic(intent: ProfileIntent): SettingsDiagnosticRequest {
        val derived = derive(intent)
        return synchronized(lock) {
            adopt(derived.identity)
            SettingsDiagnosticRequest(derived.snapshot, currentToken)
        }
    }

    fun completeDiagnostic(
        request: SettingsDiagnosticRequest,
        report: DiagnosticReport?
    ): SettingsDiscoveryCompletion = synchronized(lock) {
        if (request.token !== currentToken) return SettingsDiscoveryCompletion.Stale
        currentOptions = if (report?.stages?.any { it.stage == "discovery" && it.success } == true) {
            SettingsNamespaceOptions.Available(report.discoveredNamespaces.toList())
        } else {
            SettingsNamespaceOptions.Failed
        }
        SettingsDiscoveryCompletion.Applied(currentOptions)
    }

    suspend fun discover(intent: ProfileIntent): SettingsDiscoveryCompletion {
        val derived = derive(intent)
        val flightAndOwnership = synchronized(lock) {
            adopt(derived.identity)
            if (currentOptions is SettingsNamespaceOptions.Available) {
                return SettingsDiscoveryCompletion.Applied(currentOptions)
            }
            val existing = inFlight
            if (existing != null && existing.token === currentToken) {
                existing to false
            } else {
                val created = DiscoveryFlight(
                    token = currentToken,
                    completion = CompletableDeferred()
                )
                inFlight = created
                currentOptions = SettingsNamespaceOptions.Loading
                created to true
            }
        }
        val (flight, ownsFlight) = flightAndOwnership
        val result = if (ownsFlight) {
            try {
                discoverNamespaces(derived.snapshot).also { flight.completion.complete(it) }
            } catch (error: Exception) {
                Result.failure<List<DiscoveredNamespace>>(error)
                    .also { flight.completion.complete(it) }
            }
        } else {
            flight.completion.await()
        }

        return synchronized(lock) {
            if (flight.token !== currentToken) return SettingsDiscoveryCompletion.Stale
            if (inFlight === flight) inFlight = null
            currentOptions = result.fold(
                onSuccess = { SettingsNamespaceOptions.Available(it.toList()) },
                onFailure = { SettingsNamespaceOptions.Failed }
            )
            SettingsDiscoveryCompletion.Applied(currentOptions)
        }
    }

    /**
     * Adopts the latest unsaved intent, invalidating options when its discovery
     * identity changed. Suggested Namespace is deliberately outside that identity.
     */
    fun updateIntent(intent: ProfileIntent): SettingsNamespaceOptions {
        val derived = derive(intent)
        return synchronized(lock) {
            adopt(derived.identity)
            currentOptions
        }
    }

    private fun adopt(identity: DiscoveryIdentity) {
        if (currentIdentity == identity) return
        currentIdentity = identity
        currentToken = Any()
        currentOptions = SettingsNamespaceOptions.Empty
        inFlight = null
    }

    private fun derive(intent: ProfileIntent): DerivedDiscovery {
        val snapshot = DiagnosticSnapshot(
            endpoint = intent.endpoint.trim(),
            apiPolicy = intent.apiPolicy.name,
            authStrategy = intent.authMode.name,
            principal = intent.principal.trim(),
            secret = intent.secret,
            namespaceId = intent.suggestedNamespace.trim().ifBlank { "public" }
        )
        return DerivedDiscovery(
            identity = DiscoveryIdentity(
                endpoint = snapshot.endpoint,
                apiPolicy = snapshot.apiPolicy,
                authStrategy = snapshot.authStrategy,
                principal = snapshot.principal,
                secret = snapshot.secret
            ),
            snapshot = snapshot
        )
    }

    private class DerivedDiscovery(
        val identity: DiscoveryIdentity,
        val snapshot: DiagnosticSnapshot
    )

    private class DiscoveryIdentity(
        private val endpoint: String,
        private val apiPolicy: String,
        private val authStrategy: String,
        private val principal: String,
        private val secret: String
    ) {
        override fun equals(other: Any?): Boolean =
            other is DiscoveryIdentity &&
                endpoint == other.endpoint &&
                apiPolicy == other.apiPolicy &&
                authStrategy == other.authStrategy &&
                principal == other.principal &&
                secret == other.secret

        override fun hashCode(): Int {
            var result = endpoint.hashCode()
            result = 31 * result + apiPolicy.hashCode()
            result = 31 * result + authStrategy.hashCode()
            result = 31 * result + principal.hashCode()
            return 31 * result + secret.hashCode()
        }
    }

    private data class DiscoveryFlight(
        val token: Any,
        val completion: CompletableDeferred<Result<List<DiscoveredNamespace>>>
    )
}

class SettingsDiagnosticRequest internal constructor(
    val snapshot: DiagnosticSnapshot,
    internal val token: Any
)

sealed class SettingsNamespaceOptions {
    object Empty : SettingsNamespaceOptions()
    object Loading : SettingsNamespaceOptions()
    object Failed : SettingsNamespaceOptions()
    data class Available(val options: List<DiscoveredNamespace>) : SettingsNamespaceOptions()
}

sealed class SettingsDiscoveryCompletion {
    data class Applied(val options: SettingsNamespaceOptions) : SettingsDiscoveryCompletion()
    object Stale : SettingsDiscoveryCompletion()
}
