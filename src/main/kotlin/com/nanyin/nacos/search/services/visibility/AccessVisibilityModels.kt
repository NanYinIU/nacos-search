package com.nanyin.nacos.search.services.visibility

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.CacheCoordinate
import com.nanyin.nacos.search.services.operations.ProtocolCapability
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.settings.AuthMode

// The operation axis scoping visibility mutations IS the protocol capability
// vocabulary (ProtocolCapability, issue #125). There is no parallel
// visibility-only enumeration: identity-wide authentication blocks apply
// across every capability; configuration-read authorization is Namespace-scoped
// (#124); publish, discovery, and history authorization is capability-scoped
// (#125) and never hides configuration-read cache data.

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
 * [operationClass] is a member of the protocol capability vocabulary
 * ([ProtocolCapability]) — the same axis the protocol dialects use to declare
 * coverage — so support and permission remain separate judgments on one axis
 * (issue #48 / #125).
 *
 * This is the single report payload for [AccessVisibility.reportCompleted]
 * (ADR-0050).
 */
data class CompletedObservation(
    val observation: Long,
    val identity: AccessIdentity,
    val operationClass: ProtocolCapability,
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
         *
         * Named by the protocol capability vocabulary ([ProtocolCapability]).
         */
        val CONFIGURATION_READ: String = ProtocolCapability.CONFIGURATION_READ.name

        /**
         * Capability key for a publish authorization block scoped to one
         * complete access identity and one canonical Namespace (issue #125).
         */
        val PUBLISH: String = ProtocolCapability.PUBLISH.name

        /**
         * Capability key for a namespace-discovery authorization block scoped
         * to one complete access identity (discovery is not Namespace-scoped).
         */
        val NAMESPACE_DISCOVERY: String = ProtocolCapability.NAMESPACE_DISCOVERY.name

        /**
         * Capability key for a history authorization block scoped to one
         * complete access identity and one canonical Namespace.
         */
        val HISTORY: String = ProtocolCapability.HISTORY.name

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

        /**
         * Publish authorization block: capability-scoped to identity +
         * canonical Namespace. It never hides configuration-read cache data;
         * only a newer matching publish success clears it (issue #125).
         */
        fun publishAuthz(
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
            capability = PUBLISH,
            namespaceId = NamespaceInfo.canonicalId(namespaceId),
            refusalReason = AccessRefusalReason.AUTHORIZATION.name,
            detail = detail?.take(200),
            recordedAtMillis = recordedAtMillis
        )

        /**
         * Namespace-discovery authorization block: capability-scoped to the
         * complete access identity only (discovery is not Namespace-scoped).
         * It never hides a manually readable Namespace or its cached
         * configuration data (issue #125).
         */
        fun discoveryAuthz(
            identity: AccessIdentity,
            detail: String? = null,
            recordedAtMillis: Long
        ): AccessVisibilityRecord = AccessVisibilityRecord(
            profileId = identity.profileId,
            accessRevision = identity.accessRevision,
            canonicalEndpoint = identity.canonicalEndpoint,
            resolvedGeneration = identity.resolvedGeneration.name,
            authMode = identity.authMode.name,
            principal = identity.principal,
            capability = NAMESPACE_DISCOVERY,
            namespaceId = null,
            refusalReason = AccessRefusalReason.AUTHORIZATION.name,
            detail = detail?.take(200),
            recordedAtMillis = recordedAtMillis
        )

        /**
         * History authorization block: capability-scoped to identity +
         * canonical Namespace. It never hides current configuration-read cache
         * data; only a newer matching history success clears it (issue #125).
         */
        fun historyAuthz(
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
            capability = HISTORY,
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

    fun configurationReadKeyPrefix(identity: AccessIdentity): String =
        "vis|cfgread|${CacheCoordinate.identityPrefix(identity)}|"

    fun configurationReadKey(identity: AccessIdentity, namespaceId: String): String =
        configurationReadKeyPrefix(identity) + NamespaceInfo.canonicalId(namespaceId)

    fun configurationReadScope(identity: AccessIdentity, namespaceId: String): String =
        configurationReadKey(identity, namespaceId)

    fun configurationReadKey(record: AccessVisibilityRecord): String {
        val ns = record.namespaceId
            ?: error("CONFIGURATION_READ visibility record requires a namespaceId")
        return configurationReadKey(record.toAccessIdentity(), ns)
    }

    /**
     * Foreign-key prefix for every capability scope under one identity.
     * [CacheCoordinate.identityPrefix] already carries the `v2` schema marker;
     * mirror the `vis|auth|…` / `vis|cfgread|…` key shapes, which prefix the
     * identity directly.
     */
    private fun capabilityKeyPrefix(capability: String, identity: AccessIdentity): String =
        "vis|$capability|${CacheCoordinate.identityPrefix(identity)}"

    fun publishKey(identity: AccessIdentity, namespaceId: String): String =
        "${capabilityKeyPrefix("publish", identity)}|${NamespaceInfo.canonicalId(namespaceId)}"

    fun publishScope(identity: AccessIdentity, namespaceId: String): String =
        publishKey(identity, namespaceId)

    fun discoveryKey(identity: AccessIdentity): String =
        capabilityKeyPrefix("discovery", identity)

    fun discoveryScope(identity: AccessIdentity): String =
        discoveryKey(identity)

    fun historyKey(identity: AccessIdentity, namespaceId: String): String =
        "${capabilityKeyPrefix("history", identity)}|${NamespaceInfo.canonicalId(namespaceId)}"

    fun historyScope(identity: AccessIdentity, namespaceId: String): String =
        historyKey(identity, namespaceId)
}
