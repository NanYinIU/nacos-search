package com.nanyin.nacos.search.services.operations

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide monotonic observation sequence for ordering cache mutations.
 *
 * Every remote observation (including publish reconciliation reads) takes a
 * sequence **when it starts**, so a later-started observation wins even if an
 * earlier one completes after it. Sequences are not persisted; they reset on
 * process start (ADR-0020).
 */
class ObservationSequence(private val clock: () -> Long = System::nanoTime) {
    private val counter = AtomicLong(0)

    fun next(): Long = counter.incrementAndGet()

    fun current(): Long = counter.get()

    companion object {
        /**
         * The one in-process sequence. Ordering only means something when every
         * observation draws its number from the same counter, so gateways and
         * the user gestures that clear or invalidate the cache share this one.
         */
        val process = ObservationSequence()
    }
}

/**
 * A read result together with the observation sequence the read took, so a
 * caller that paints the result and a cache mutation derived from the same
 * observation are ordered by the same number (ADR-0047).
 */
data class Observed<T>(val value: T, val observation: Long)

/**
 * The single observation high-water implementation (ADR-0044).
 *
 * A scope is any string the caller derives from the **complete** access
 * identity plus the mutation's coordinate — truncating it to profile id and
 * access revision would let two identities that resolved to different API
 * generations reject each other's writes to entirely distinct coordinates.
 *
 * A mutation is offered a scope *chain* running from the broadest scope to its
 * own: a user's clear raises the global mark, a namespace invalidation raises
 * the namespace mark, and either outranks a coordinate write that started
 * earlier. [raise] moves a mark only for an accepted mutation, which is what
 * ADR-0020's later-started-accepted rule requires.
 */
class ObservationHighWater {
    private val marks = ConcurrentHashMap<String, Long>()

    /** True when [sequence] is newer than every mark on [scopeChain]. */
    fun accepts(scopeChain: List<String>, sequence: Long): Boolean =
        scopeChain.none { sequence <= (marks[it] ?: 0L) }

    /** Raises [scope]'s mark. Call only once a mutation has been accepted. */
    fun raise(scope: String, sequence: Long) {
        marks.merge(scope, sequence, ::maxOf)
    }

    /** The last accepted sequence for [scope]; zero when nothing was accepted. */
    fun markOf(scope: String): Long = marks[scope] ?: 0L
}
