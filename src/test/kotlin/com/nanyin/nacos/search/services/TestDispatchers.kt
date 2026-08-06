package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.visibility.CompletedObservation
import com.nanyin.nacos.search.services.visibility.ObservationOutcome
import com.nanyin.nacos.search.services.visibility.VisibilityOperationClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Runs nothing until [drain], so a rebuild scheduled on it can be observed
 * while still queued — the window in which a stale derivation must not be
 * served (issue #126).
 */
internal class QueueingDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(queued) { queued.addLast(block) }
    }

    fun drain() {
        while (true) {
            val next = synchronized(queued) { queued.removeFirstOrNull() } ?: return
            next.run()
        }
    }
}

/**
 * Reports one completed configuration-read observation to [cache]'s visibility
 * module: a [RemoteOperationError] lands a block, `null` lands a matching
 * success that clears one. Shared by the PSI visibility tests (issue #126).
 */
internal suspend fun reportVisibility(
    cache: CacheService,
    identity: AccessIdentity,
    observation: Long,
    namespaceId: String? = null,
    error: RemoteOperationError? = null
) {
    cache.visibilityReporter().reportCompleted(
        CompletedObservation(
            observation = observation,
            identity = identity,
            operationClass = VisibilityOperationClass.CONFIGURATION_READ,
            namespaceId = namespaceId,
            outcome = error?.let { ObservationOutcome.RemoteFailure(it) } ?: ObservationOutcome.Success
        )
    )
}
