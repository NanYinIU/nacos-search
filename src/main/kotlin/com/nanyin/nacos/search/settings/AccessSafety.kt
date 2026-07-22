package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosServerConfig
import java.nio.charset.StandardCharsets
import java.util.UUID

/** A narrow credential seam so migration remains deterministic and testable. */
interface CredentialSlots {
    operator fun get(slotId: String): String?
    fun put(slotId: String, secret: String)
}

class InMemoryCredentialSlots : CredentialSlots {
    private val values = mutableMapOf<String, String>()
    override operator fun get(slotId: String): String? = values[slotId]
    override fun put(slotId: String, secret: String) {
        if (secret.isNotEmpty()) values[slotId] = secret
    }
}

private object PasswordSafeCredentialSlots : CredentialSlots {
    override operator fun get(slotId: String): String? = NacosCredentialStore.get(slotId)
    override fun put(slotId: String, secret: String) = NacosCredentialStore.set(slotId, secret)
}

data class LegacyMigrationResult(
    val profiles: List<EnvironmentProfile>,
    val defaultProfileId: String,
    val defaultNamespaceId: String
)

/** Converts legacy app-wide connection state once without guessing cache ownership. */
class LegacyProfileMigrator(private val credentials: CredentialSlots = PasswordSafeCredentialSlots) {
    fun migrate(
        legacyServers: List<NacosServerConfig>,
        legacyActiveServerId: String,
        legacyNamespaceId: String
    ): LegacyMigrationResult {
        val usedIds = mutableSetOf<String>()
        val profiles = legacyServers
            .ifEmpty { listOf(NacosServerConfig(id = "default")) }
            .mapIndexed { index, server ->
                val profileId = uniqueProfileId(server, index, usedIds)
                val normalizedServer = server.copy(id = profileId)
                val profile = EnvironmentProfile.fromLegacy(normalizedServer)
                val legacySecret = credentials.get(server.id).orEmpty().ifEmpty { server.password }
                credentials.put(profile.credentialSlotId, legacySecret)
                profile
            }
        val selected = profiles.firstOrNull { it.id == legacyActiveServerId } ?: profiles.first()
        return LegacyMigrationResult(
            profiles = profiles,
            defaultProfileId = selected.id,
            defaultNamespaceId = legacyNamespaceId.trim().ifBlank { "public" }
        )
    }

    private fun uniqueProfileId(server: NacosServerConfig, index: Int, usedIds: MutableSet<String>): String {
        val requested = server.id.trim()
        val source = listOf(index, server.displayName, server.serverUrl, server.username, server.authMode.name)
            .joinToString("\u0000")
        val deterministicSuffix = UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8)).toString()
        val base = requested.ifBlank { "legacy-$deterministicSuffix" }
        val unique = if (usedIds.add(base)) base else "$base-$deterministicSuffix"
        usedIds.add(unique)
        return unique
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
    val resolvedGeneration: Long
)

/** A typed fail-closed result that UI callers can render as configuration required. */
class ConfigurationRequired(val reasons: List<String>) : IllegalStateException(reasons.joinToString("; "))

object OperationContextResolver {
    fun resolve(profile: EnvironmentProfile, credential: String?): Result<NacosOperationContext> = runCatching {
        val endpoint = CanonicalNacosEndpoint.parse(profile.canonicalEndpoint).getOrElse {
            throw ConfigurationRequired(listOf("A valid Nacos endpoint is required"))
        }
        val principal = profile.principal.trim()
        val secret = credential.orEmpty()
        require(!(principal.isBlank() xor secret.isBlank())) {
            throw ConfigurationRequired(listOf("Username and credential must be provided together"))
        }
        val normalizedPrincipal = principal.ifBlank { "<anonymous>" }
        NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = profile.id,
                accessRevision = profile.accessRevision,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = profile.accessRevision,
                authMode = profile.authMode,
                principal = normalizedPrincipal
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(secret),
            authMode = profile.authMode,
            profileRevision = profile.profileRevision,
            accessRevision = profile.accessRevision,
            resolvedGeneration = profile.accessRevision
        )
    }.recoverCatching { error ->
        if (error is ConfigurationRequired) throw error
        throw ConfigurationRequired(listOf(error.message ?: "Configuration is invalid"))
    }
}
