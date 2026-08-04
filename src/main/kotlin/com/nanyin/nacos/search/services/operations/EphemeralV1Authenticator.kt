package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.services.AuthenticationToken
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [V1Authenticator] whose token lives only as long as this object.
 *
 * ADR-0022 requires connection diagnostics to run on temporary authentication
 * state and to modify no authentication registry. They still need a real
 * Nacos-password token to exercise the V1 read path, so this holds one in a
 * field instead of in the shared session registry: nothing is keyed, nothing
 * outlives the instance, and no other identity can observe it.
 *
 * Create one per diagnostic run. A longer-lived instance would reuse a token
 * obtained for one unapplied draft after the user edited the credentials,
 * reporting success for a password the server never accepted.
 */
class EphemeralV1Authenticator(
    private val login: suspend (NacosOperationContext) -> AuthenticationToken?,
    private val clock: () -> Long = System::currentTimeMillis
) : V1Authenticator {

    private val mutex = Mutex()
    private var token: AuthenticationToken? = null

    override suspend fun accessToken(context: NacosOperationContext): String? = mutex.withLock {
        token?.takeIf { it.isValid(clock()) }?.let { return@withLock it.value }
        val acquired = login(context)?.takeIf { it.isValid(clock()) }
        token = acquired
        acquired?.value
    }

    /**
     * Drops the held token so the adapter's one-time recovery replay logs in
     * again rather than re-presenting a credential the server just refused.
     */
    override fun invalidate(context: NacosOperationContext) {
        token = null
    }
}
