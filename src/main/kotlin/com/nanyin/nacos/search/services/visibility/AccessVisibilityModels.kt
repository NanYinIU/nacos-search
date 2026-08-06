package com.nanyin.nacos.search.services.visibility

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.CacheCoordinate
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.settings.AuthMode

/**
 * Operation axis used to scope visibility mutations (ADR-0019 / ADR-0050).
 *
 * Reuses the protocol capability vocabulary rather than inventing a parallel
 * enumeration: configuration reads, publishing, namespace discovery, history,
 * and later optional capabilities. Identity-wide authentication blocks apply
 * across every class; configuration-read authorization is Namespace-scoped
 * (#124); other capability-scoped authorization is layered later (#125).
 */
enum class VisibilityOperationClass {
    CONFIGURATION_READ,
    PUBLISH,
    NAMESPACE_DISCOVERY,
    HISTORY,
    /** Formal generation probe / other authenticated contact that is not a data read. */
    AUTHENTICATED_CONTACT
}

/**
 * Why configuration-read data for an identity (or narrower scope) is hidden.
 * Stored on the block record; never carries secrets or configuration content.
 */
enum class AccessRefusalReason {
    /** Terminal authentication failure (including exhausted token recovery). */
    AUTHENTICATION,

    /** Authorization / permission denial (namespace or capability scoped). */
    AUTHORIZATION
}

/**
 * Outcome of one completed formal remote observation, as reported by the gateway.
 *
 * Only [Success] and [RemoteFailure] can mutate visibility. Cache hits carry
 * [Observed.NO_OBSERVATION] and are never reported. Local configuration failures
 * and cancellation never reach this type.
 */
sealed class ObservationOutcome {
    data object Success : ObservationOutcome()

    data class RemoteFailure(val error: RemoteOperationError) : ObservationOutcome()
}

/**
 * One completed formal remote operation: start-time observation sequence, complete
 * access identity, operation class, optional canonical namespace, and outcome.
 *
 * This is the single report payload for [AccessVisibility.reportCompleted]
 * (ADR-0050).
 */
data class CompletedObservation(
    val observation: Long,
    val identity: AccessIdentity,
    val operationClass: VisibilityOperationClass,
    val namespaceId: String? = null,
    val outcome: ObservationOutcome
)

/**
 * Persisted access-visibility record. Holds the complete access identity,
 * capability, optional namespace, refusal reason, and non-sensitive metadata.
 *
 * Deliberately omits secrets, tokens, configuration content, and process-local
 * observation sequences so a restored block can be cleared by the first matching
 * success after restart (ADR-0019 / issue #48).
 */
data class AccessVisibilityRecord(
    val profileId: String,
    val accessRevision: Long,
    val canonicalEndpoint: String,
    val resolvedGeneration: String,
    val authMode: String,
    val principal: String,
    /** Capability this block is scoped to; [IDENTITY_AUTH] is identity-wide. */
    val capability: String,
    val namespaceId: String? = null,
    val refusalReason: String,
    val detail: String? = null,
    val recordedAtMillis: Long = 0L
) {
    fun toAccessIdentity(): AccessIdentity = AccessIdentity.ofProfile(
        profileId = profileId,
        accessRevision = accessRevision,
        canonicalEndpoint = canonicalEndpoint,
        resolvedGeneration = runCatching { NacosApiGeneration.valueOf(resolvedGeneration) }
            .getOrDefault(NacosApiGeneration.UNKNOWN),
        authMode = runCatching { AuthMode.valueOf(authMode) }.getOrDefault(AuthMode.TOKEN),
        principal = principal
    )

    fun refusal(): AccessRefusalReason =
        runCatching { AccessRefusalReason.valueOf(refusalReason) }
            .getOrDefault(AccessRefusalReason.AUTHENTICATION)

    companion object {
        /** Capability key for an identity-wide authentication block. */
        const val IDENTITY_AUTH = "IDENTITY_AUTH"

        /**
         * Capability key for a configuration-read authorization block scoped to
         * one complete access identity and one canonical Namespace (issue #124).
         */
        const val CONFIGURATION_READ = "CONFIGURATION_READ"

        fun identityAuth(
            identity: AccessIdentity,
            reason: AccessRefusalReason,
            detail: String? = null,
            recordedAtMillis: Long
        ): AccessVisibilityRecord = AccessVisibilityRecord(
            profileId = identity.profileId,
            accessRevision = identity.accessRevision,
            canonicalEndpoint = identity.canonicalEndpoint,
            resolvedGeneration = identity.resolvedGeneration.name,
            authMode = identity.authMode.name,
            principal = identity.principal,
            capability = IDENTITY_AUTH,
            namespaceId = null,
            refusalReason = reason.name,
            detail = detail?.take(200),
            recordedAtMillis = recordedAtMillis
        )

        fun configurationReadAuthz(
            identity: AccessIdentity,
            namespaceId: String,
            detail: String? = null,
            recordedAtMillis: Long
        ): AccessVisibilityRecord = AccessVisibilityRecord(
            profileId = identity.profileId,
            accessRevision = identity.accessRevision,
            canonicalEndpoint = identity.canonicalEndpoint,
            resolvedGeneration = identity.resolvedGeneration.name,
            authMode = identity.authMode.name,
            principal = identity.principal,
            capability = CONFIGURATION_READ,
            namespaceId = NamespaceInfo.canonicalId(namespaceId),
            refusalReason = AccessRefusalReason.AUTHORIZATION.name,
            detail = detail?.take(200),
            recordedAtMillis = recordedAtMillis
        )
    }
}

/**
 * Read-side decision for configuration-read cache surfaces.
 *
 * [Visible] means the identity (and optional Namespace) is not under an access
 * block for configuration data. [Blocked] is distinct from a cache miss: the
 * payload may still exist in the store and becomes readable again after a
 * matching success.
 */
sealed class ConfigurationVisibility {
    data object Visible : ConfigurationVisibility()

    data class Blocked(
        val reason: AccessRefusalReason,
        val detail: String? = null
    ) : ConfigurationVisibility()
}

/** Storage and high-water scope helpers for visibility records. */
internal object VisibilityScopes {
    fun identityAuthKey(identity: AccessIdentity): String =
        "vis|auth|${CacheCoordinate.identityPrefix(identity)}"

    fun identityAuthScope(identity: AccessIdentity): String =
        identityAuthKey(identity)

    fun identityAuthKey(record: AccessVisibilityRecord): String =
        identityAuthKey(record.toAccessIdentity())

    fun configurationReadKey(identity: AccessIdentity, namespaceId: String): String =
        "vis|cfgread|${CacheCoordinate.identityPrefix(identity)}|${NamespaceInfo.canonicalId(namespaceId)}"

    fun configurationReadScope(identity: AccessIdentity, namespaceId: String): String =
        configurationReadKey(identity, namespaceId)

    fun configurationReadKey(record: AccessVisibilityRecord): String {
        val ns = record.namespaceId
            ?: error("CONFIGURATION_READ visibility record requires a namespaceId")
        return configurationReadKey(record.toAccessIdentity(), ns)
    }
}
