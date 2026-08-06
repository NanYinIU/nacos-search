package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosApiPolicy

// Credential-slot storage lives in CredentialSlotStore.kt (issue #102).
// Versioned settings migration lives in SettingsMigration.kt (issue #104).

/**
 * Compatibility wrapper over [SettingsMigrator] for older tests that still
 * drive migration through the narrow [CredentialSlots] surface.
 */
class LegacyProfileMigrator(private val credentials: CredentialSlots = PasswordSafeCredentialSlots) {
    fun migrate(
        legacyServers: List<NacosServerConfig>,
        legacyActiveServerId: String,
        legacyNamespaceId: String
    ): LegacyMigrationResult {
        val slots = object : CredentialSlotStore {
            override fun read(profileId: String, accessRevision: Long): String? =
                credentials[CredentialSlotStore.slotKey(profileId, accessRevision)]

            override fun stage(
                profileId: String,
                accessRevision: Long,
                secret: String
            ): CredentialStageResult {
                val key = CredentialSlotStore.slotKey(profileId, accessRevision)
                credentials.put(key, secret)
                return CredentialStageResult.Success
            }

            override fun removeAllForProfile(profileId: String) {
                // Compatibility surface has no enumerate-by-prefix; no-op.
            }
        }
        val report = SettingsMigrator(
            credentialSlots = slots,
            legacySecretByKey = { key -> credentials[key] }
        ).migrate(
            SettingsMigrationInput(
                schemaVersion = 0,
                servers = legacyServers,
                activeServerId = legacyActiveServerId,
                flatNamespace = legacyNamespaceId,
                profiles = emptyList(),
                preferences = emptyList(),
                defaultProfileId = legacyActiveServerId,
                defaultNamespaceId = legacyNamespaceId
            )
        )
        return LegacyMigrationResult(
            profiles = report.profiles,
            defaultProfileId = report.defaultProfileId,
            defaultNamespaceId = report.defaultNamespaceId,
            migrationActions = report.actions,
            fromSchemaVersion = report.fromSchemaVersion,
            toSchemaVersion = report.toSchemaVersion
        )
    }
}

class CredentialSnapshot internal constructor(val secret: String) {
    override fun toString(): String = "CredentialSnapshot(***)"
}

data class NacosOperationContext(
    val identity: AccessIdentity,
    val endpoint: CanonicalNacosEndpoint,
    val credential: CredentialSnapshot,
    val authMode: AuthMode,
    val profileRevision: Long,
    val accessRevision: Long,
    val resolvedGeneration: NacosApiGeneration
) {
    val authenticationStrategy: V1AuthenticationStrategy
        get() = authMode.toV1AuthenticationStrategy()
}

/**
 * The V1 adapter's closed authentication vocabulary. It deliberately has no
 * fallback strategy: each value owns its one wire representation.
 */
enum class V1AuthenticationStrategy {
    ANONYMOUS,
    NACOS_PASSWORD,
    HTTP_BASIC,
    BEARER_TOKEN
}

internal fun AuthMode.toV1AuthenticationStrategy(): V1AuthenticationStrategy = when (this) {
    AuthMode.ANONYMOUS -> V1AuthenticationStrategy.ANONYMOUS
    AuthMode.TOKEN, AuthMode.NACOS_PASSWORD, AuthMode.HYBRID -> V1AuthenticationStrategy.NACOS_PASSWORD
    AuthMode.BASIC, AuthMode.HTTP_BASIC -> V1AuthenticationStrategy.HTTP_BASIC
    AuthMode.BEARER_TOKEN -> V1AuthenticationStrategy.BEARER_TOKEN
}

/** A typed fail-closed result that UI callers can render as configuration required. */
class ConfigurationRequired(val reasons: List<String>) : IllegalStateException(reasons.joinToString("; "))

object OperationContextResolver {

    /**
     * Derives the [AccessIdentity] from a profile without reading any credential.
     * PSI/Swing hot paths call this so they never touch PasswordSafe on the EDT.
     * Identity fields come entirely from the profile and its parsed endpoint; the
     * credential is only needed for the full [NacosOperationContext] used by network
     * operations.
     */
    fun identityFromProfile(profile: EnvironmentProfile): AccessIdentity {
        val endpoint = CanonicalNacosEndpoint.parse(profile.canonicalEndpoint)
            .getOrNull()?.value ?: "<invalid>"
        val resolvedGeneration = when (profile.apiPolicy) {
            NacosApiPolicy.V1 -> NacosApiGeneration.V1
            NacosApiPolicy.V3 -> NacosApiGeneration.V3
            NacosApiPolicy.AUTO -> NacosApiGeneration.UNKNOWN
        }
        return AccessIdentity.ofProfile(
            profileId = profile.id,
            accessRevision = profile.accessRevision,
            canonicalEndpoint = endpoint,
            resolvedGeneration = resolvedGeneration,
            authMode = profile.authMode,
            principal = profile.principal.trim().ifBlank { "<anonymous>" }
        )
    }

    fun resolve(profile: EnvironmentProfile, credential: String?): Result<NacosOperationContext> = runCatching {
        val endpoint = CanonicalNacosEndpoint.parse(profile.canonicalEndpoint).getOrElse {
            throw ConfigurationRequired(listOf("A valid Nacos endpoint is required"))
        }
        val principal = profile.principal.trim()
        val secret = credential.orEmpty()
        val authenticationStrategy = profile.authMode.toV1AuthenticationStrategy()
        if (profile.authMode == AuthMode.HYBRID) {
            throw ConfigurationRequired(listOf("An explicit authentication strategy is required; HYBRID is not supported"))
        }
        // Auth strategy validation applies to every policy: the strategy must be
        // satisfiable regardless of whether the generation is locked or AUTO.
        when (authenticationStrategy) {
            V1AuthenticationStrategy.ANONYMOUS -> require(principal.isBlank() && secret.isBlank()) {
                throw ConfigurationRequired(listOf("Anonymous access cannot include a principal or credential"))
            }
            V1AuthenticationStrategy.NACOS_PASSWORD,
            V1AuthenticationStrategy.HTTP_BASIC -> require(principal.isNotBlank() && secret.isNotBlank()) {
                throw ConfigurationRequired(listOf("Username and credential must be provided together"))
            }
            V1AuthenticationStrategy.BEARER_TOKEN -> require(secret.isNotBlank()) {
                throw ConfigurationRequired(listOf("Bearer token is required"))
            }
        }
        val normalizedPrincipal = principal.ifBlank { "<anonymous>" }
        val resolvedGeneration = when (profile.apiPolicy) {
            NacosApiPolicy.V1 -> NacosApiGeneration.V1
            NacosApiPolicy.V3 -> NacosApiGeneration.V3
            NacosApiPolicy.AUTO -> NacosApiGeneration.UNKNOWN
        }
        NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = profile.id,
                accessRevision = profile.accessRevision,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = resolvedGeneration,
                authMode = profile.authMode,
                principal = normalizedPrincipal
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(secret),
            authMode = profile.authMode,
            profileRevision = profile.profileRevision,
            accessRevision = profile.accessRevision,
            resolvedGeneration = resolvedGeneration
        )
    }.recoverCatching { error ->
        if (error is ConfigurationRequired) throw error
        throw ConfigurationRequired(listOf(error.message ?: "Configuration is invalid"))
    }
}
