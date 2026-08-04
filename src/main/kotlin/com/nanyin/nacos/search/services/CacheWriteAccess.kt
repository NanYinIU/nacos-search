package com.nanyin.nacos.search.services

/**
 * Marks the cache module's write surface — [CacheMutation] and
 * [CacheService.applyMutation] — as reachable only from the operation layer,
 * where remote operations are performed and observation sequences are taken.
 *
 * "No module outside the operation layer writes to the cache" is an
 * architecture-fitness property rather than a behaviour, so it is enforced by
 * the compiler instead of by a test: a Swing panel or a PSI renderer that
 * names either declaration fails to compile. The gate the mutation would have
 * passed is not the point — a UI or code-navigation caller holds no
 * observation sequence of its own, so anything it writes is either a restamp
 * of what the gateway just wrote or a sequence it invented, and neither is
 * something review has reliably caught here.
 *
 * Opting in is deliberate and greppable. It belongs to the operation layer:
 * components that perform remote operations and own an observation sequence,
 * and the recorders that turn a completed operation's observation into a
 * mutation on its caller's behalf. Today that is the gateway's cache adapter,
 * the namespace index coordinator, the navigation detail prefetch,
 * [com.nanyin.nacos.search.services.operations.ObservedDetailRecorder], the API
 * service's clear gestures, and the cache module's own tests. It does not
 * belong in `ui/` or `psi/`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Cache mutations belong to the operation layer, which owns the observation sequence " +
        "that orders them. UI and code-navigation code reads the cache; it does not write to it."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class CacheWriteAccess
