package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.settings.V1AuthenticationStrategy
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

    fun completedToken(identity: AccessIdentity): AuthenticationToken? =
        completedTokens[identity]?.takeIf { it.isValid(clock()) }

    private fun epochFor(identity: AccessIdentity): AtomicLong =
        invalidationEpochs.computeIfAbsent(identity) { AtomicLong(0) }
}
