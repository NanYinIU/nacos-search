package com.nanyin.nacos.search.models

import com.nanyin.nacos.search.settings.AuthMode

/** The protocol generation that was resolved before an operation started. */
enum class NacosApiGeneration {
    UNKNOWN, V1, V3;

    /**
     * Whether this names a concrete protocol. [UNKNOWN] is the absence of an
     * answer, not a third protocol: it dispatches nothing and addresses a cache
     * key space that only an unresolved AUTO profile ever reads.
     */
    fun isResolved(): Boolean = this == V1 || this == V3
}

/**
 * Identifies the user/environment context that a cache entry belongs to.
 *
 * Two entries are mutually invisible unless their identities are equal.
 * Password and access tokens are deliberately excluded so that switching
 * credentials within the same identity (e.g. re-login) does not invalidate
 * the cache, while switching the username or auth mode does.
 *
 * Every value is produced by [ofProfile] and always carries a real
 * [accessRevision] and [resolvedGeneration] (issue #62). There is no
 * legacy factory that fabricates zero-revision / UNKNOWN sentinels.
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
