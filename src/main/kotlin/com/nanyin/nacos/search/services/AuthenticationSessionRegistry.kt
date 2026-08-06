package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.settings.V1AuthenticationStrategy
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A completed Nacos-password token kept only in application memory. */
data class AuthenticationToken(val value: String, val expiresAtMillis: Long) {
    fun isValid(nowMillis: Long): Boolean = value.isNotBlank() && nowMillis < expiresAtMillis
}

/**
 * A login/refresh flight must additionally pin the profile revision and
 * explicit strategy. Completed tokens remain reusable only by the complete
 * [AccessIdentity], which intentionally excludes secrets.
 */
data class AuthenticationExecutionKey(
    val identity: AccessIdentity,
    val profileRevision: Long,
    val strategy: V1AuthenticationStrategy
)

fun AuthenticationExecutionKey(context: NacosOperationContext): AuthenticationExecutionKey =
    AuthenticationExecutionKey(
        identity = context.identity,
        profileRevision = context.profileRevision,
        strategy = context.authenticationStrategy
    )

class AuthenticationSessionRegistry(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val completedTokens = ConcurrentHashMap<AccessIdentity, AuthenticationToken>()
    private val flightLocks = ConcurrentHashMap<AuthenticationExecutionKey, Mutex>()
    private val invalidationEpochs = ConcurrentHashMap<AccessIdentity, AtomicLong>()

    suspend fun getOrLogin(
        key: AuthenticationExecutionKey,
        login: suspend () -> AuthenticationToken?
    ): AuthenticationToken? {
        completedToken(key.identity)?.let { return it }
        val lock = flightLocks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            completedToken(key.identity)?.let { return@withLock it }
            val epoch = epochFor(key.identity).get()
            val acquired = login()
            if (acquired?.isValid(clock()) == true && epochFor(key.identity).get() == epoch) {
                completedTokens[key.identity] = acquired
            }
            acquired
        }
    }

    fun invalidate(identity: AccessIdentity) {
        epochFor(identity).incrementAndGet()
        completedTokens.remove(identity)
    }

    /**
     * Drop every completed token and flight lock owned by [profileId], after
     * bumping the invalidation epoch for every identity that held state for
     * that profile. Epoch entries are **retained** so a late [getOrLogin] that
     * captured epoch 0 cannot store a token after deletion cleanup
     * (ADR-0025 / issue #105). Idempotent.
     */
    fun invalidateProfile(profileId: String) {
        val id = profileId.trim()
        if (id.isBlank()) return
        val identities = (
            completedTokens.keys +
                flightLocks.keys.map { it.identity } +
                invalidationEpochs.keys
            ).filter { it.profileId == id }.toSet()
        identities.forEach { identity ->
            epochFor(identity).incrementAndGet()
            completedTokens.remove(identity)
        }
        flightLocks.keys.filter { it.identity.profileId == id }.forEach { flightLocks.remove(it) }
        // Keep invalidationEpochs: clearing them would reset the fence to 0 and
        // let an in-flight login that sampled epoch 0 publish after deletion.
    }

    fun completedToken(identity: AccessIdentity): AuthenticationToken? =
        completedTokens[identity]?.takeIf { it.isValid(clock()) }

    /**
     * Every identity this registry holds any state for — a completed token, a
     * flight lock, or an invalidation epoch.
     *
     * A failed login still leaves a lock and an epoch behind, so emptiness here
     * is the only honest way to ask whether an operation touched the registry
     * at all. ADR-0022 requires connection diagnostics not to.
     */
    fun trackedIdentities(): Set<AccessIdentity> =
        completedTokens.keys + flightLocks.keys.map { it.identity } + invalidationEpochs.keys

    private fun epochFor(identity: AccessIdentity): AtomicLong =
        invalidationEpochs.computeIfAbsent(identity) { AtomicLong(0) }
}
