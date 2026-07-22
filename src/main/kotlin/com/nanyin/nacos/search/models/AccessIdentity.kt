package com.nanyin.nacos.search.models

import com.nanyin.nacos.search.settings.AuthMode

/** The protocol generation that was resolved before an operation started. */
enum class NacosApiGeneration { UNKNOWN, V1 }

/**
 * Identifies the user/environment context that a cache entry belongs to.
 *
 * Two entries are mutually invisible unless their identities are equal.
 * Password and access tokens are deliberately excluded so that switching
 * credentials within the same identity (e.g. re-login) does not invalidate
 * the cache, while switching the username or auth mode does.
 */
data class AccessIdentity(
    val profileId: String,
    val accessRevision: Long,
    val canonicalEndpoint: String,
    val resolvedGeneration: NacosApiGeneration,
    val authMode: AuthMode,
    val principal: String
) {
    /** Compatibility name for callers written before profiles were introduced. */
    val serverId: String get() = profileId

    companion object {
        fun of(serverId: String, authMode: AuthMode, username: String): AccessIdentity {
            val normalizedServer = serverId.trim().trimEnd('/').ifBlank { "<default>" }
            val normalizedPrincipal = username.trim().ifBlank { "<anonymous>" }
            return AccessIdentity(
                profileId = normalizedServer,
                accessRevision = 0,
                canonicalEndpoint = normalizedServer,
                resolvedGeneration = NacosApiGeneration.UNKNOWN,
                authMode = authMode,
                principal = normalizedPrincipal
            )
        }

        fun ofProfile(
            profileId: String,
            accessRevision: Long,
            canonicalEndpoint: String,
            resolvedGeneration: NacosApiGeneration,
            authMode: AuthMode,
            principal: String
        ): AccessIdentity = AccessIdentity(
            profileId = profileId.trim().ifBlank { "<default>" },
            accessRevision = accessRevision,
            canonicalEndpoint = canonicalEndpoint.trim().trimEnd('/').ifBlank { "<invalid>" },
            resolvedGeneration = resolvedGeneration,
            authMode = authMode,
            principal = principal.trim().ifBlank { "<anonymous>" }
        )
    }
}

enum class DataSource { REMOTE, CACHE }

enum class DataFreshness { FRESH, STALE, UNKNOWN }

/**
 * Snapshot of a dataset's three independent quality dimensions.
 *
 * - [source]: where the data came from (remote server vs. local cache)
 * - [freshness]: whether the TTL is still valid, expired but usable, or unknown
 * - [completeness]: whether all expected items were loaded successfully
 */
data class DatasetState(
    val source: DataSource,
    val freshness: DataFreshness,
    val completeness: DatasetCompleteness,
    val fetchedAtMillis: Long?
)
