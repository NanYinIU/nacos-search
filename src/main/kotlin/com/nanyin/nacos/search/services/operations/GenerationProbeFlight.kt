package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosApiGeneration
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-scoped single-flight for formal AUTO generation detection.
 *
 * Concurrent consumers that share the complete non-secret probe inputs join one
 * in-flight probe. Once the probe completes the flight is released — sequential
 * reuse is the project session's job, not this registry's.
 *
 * Isolated connection diagnostics must not use this registry; they call
 * [GenerationResolver] directly with isolated temporary authentication.
 *
 * The flight only shares the probe *result*. Each consumer still decides
 * independently whether to commit that result into its project session
 * (rechecking epoch, revisions, and tombstone).
 */
class GenerationProbeFlight {
    private val inFlight =
        ConcurrentHashMap<GenerationProbeKey, CompletableDeferred<Result<NacosApiGeneration>>>()

    /**
     * Joins an in-flight probe for [key] or starts [probe] as the sole owner.
     * The deferred is removed once complete so a later formal detection with the
     * same inputs can re-probe when the project session no longer holds a result.
     */
    suspend fun joinOrProbe(
        key: GenerationProbeKey,
        probe: suspend () -> Result<NacosApiGeneration>
    ): Result<NacosApiGeneration> {
        while (true) {
            val existing = inFlight[key]
            if (existing != null) {
                return existing.await()
            }
            val created = CompletableDeferred<Result<NacosApiGeneration>>()
            val winner = inFlight.putIfAbsent(key, created)
            if (winner != null) {
                return winner.await()
            }
            try {
                val result = probe()
                created.complete(result)
                return result
            } catch (error: Throwable) {
                val failure = Result.failure<NacosApiGeneration>(error)
                created.complete(failure)
                return failure
            } finally {
                inFlight.remove(key, created)
            }
        }
    }

    /** Test helper — drops any in-flight entries. */
    fun clear() {
        inFlight.clear()
    }
}
