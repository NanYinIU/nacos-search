package com.nanyin.nacos.search.services

import com.intellij.openapi.components.Service
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

@Service(Service.Level.APP)
class AuthenticationSessionRegistry(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class ProfileFence {
        @Volatile
        var invalidated: Boolean = false
    }

    private val completedTokens = ConcurrentHashMap<AccessIdentity, AuthenticationToken>()
    private val flightLocks = ConcurrentHashMap<AuthenticationExecutionKey, Mutex>()
    private val invalidationEpochs = ConcurrentHashMap<AccessIdentity, AtomicLong>()
    private val profileFences = ConcurrentHashMap<String, ProfileFence>()

    suspend fun getOrLogin(
        key: AuthenticationExecutionKey,
        login: suspend () -> AuthenticationToken?
    ): AuthenticationToken? {
        val profileFence = profileFence(key.identity)
        if (profileFence.invalidated) return null
        completedToken(key.identity)?.let { return it }
        // Capture the fence before joining the flight. A caller already
        // queued when its profile is invalidated must not wake up afterwards
        // and turn the detached, old flight lock into a fresh login.
        val epoch = epochFor(key.identity).get()
        val lock = flightLocks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            if (profileFence.invalidated || epochFor(key.identity).get() != epoch) {
                return@withLock null
            }
            completedToken(key.identity)?.let { return@withLock it }
            val acquired = login()
            val reusable = acquired?.isValid(clock()) == true
            synchronized(profileFence) {
                if (profileFence.invalidated || epochFor(key.identity).get() != epoch) {
                    return@synchronized null
                }
                if (reusable) {
                    completedTokens[key.identity] = acquired
                }
                acquired
            }
        }
    }

    fun invalidate(identity: AccessIdentity) {
        synchronized(profileFence(identity)) {
            epochFor(identity).incrementAndGet()
            completedTokens.remove(identity)
        }
    }

    /**
     * Permanently fence [profileId] for this application lifetime, then drop
     * every completed token and flight lock it owns. The profile fence covers
     * operations that captured context before deletion but had not yet touched
     * this registry; retained identity epochs cover callers already attached to
     * a flight. Together they prevent any late [getOrLogin] from starting,
     * returning, or storing a token after deletion cleanup (ADR-0025 / issues
     * #105 and #151). Idempotent.
     */
    fun invalidateProfile(profileId: String) {
        val id = profileId.trim()
        if (id.isBlank()) return
        val profileFence = profileFence(id)
        synchronized(profileFence) {
            profileFence.invalidated = true
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
        }
        // Keep invalidationEpochs: clearing them would reset the fence to 0 and
        // let an in-flight login that sampled epoch 0 serve after deletion.
    }

    fun completedToken(identity: AccessIdentity): AuthenticationToken? {
        val profileFence = profileFence(identity)
        return synchronized(profileFence) {
            completedTokens[identity]
                ?.takeUnless { profileFence.invalidated }
                ?.takeIf { it.isValid(clock()) }
        }
    }

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

    private fun profileFence(identity: AccessIdentity): ProfileFence =
        profileFence(identity.profileId)

    private fun profileFence(profileId: String): ProfileFence =
        profileFences.computeIfAbsent(profileId.trim()) { ProfileFence() }
}
