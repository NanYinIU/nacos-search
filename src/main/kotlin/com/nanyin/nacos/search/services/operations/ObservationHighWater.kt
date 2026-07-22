package com.nanyin.nacos.search.services.operations

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide monotonic observation sequence for ordering cache mutations.
 *
 * Every remote observation (including publish reconciliation reads) takes a
 * sequence before it starts. Cache entries, identity gates, and scope gates
 * each maintain a high-water mark so that:
 *
 * - A late failure cannot overwrite a newer success.
 * - A late success cannot clear a newer permission block.
 * - A complete index cannot delete entries before confirming there is no
 *   newer detail observation for that coordinate.
 */
class ObservationSequence(private val clock: () -> Long = System::nanoTime) {
    private val counter = AtomicLong(0)

    fun next(): Long = counter.incrementAndGet()

    fun current(): Long = counter.get()
}

/**
 * A high-water gate that rejects observations older than the last accepted one.
 * Used per-coordinate and per-scope to enforce causal ordering.
 */
class ObservationGate {
    private val highWater = AtomicLong(0)

    /**
     * Returns true if [sequence] is newer than the current high-water and
     * updates the mark. Returns false if the observation is stale and should
     * be silently dropped.
     */
    fun acceptIfNewer(sequence: Long): Boolean {
        while (true) {
            val current = highWater.get()
            if (sequence <= current) return false
            if (highWater.compareAndSet(current, sequence)) return true
        }
    }

    fun currentHighWater(): Long = highWater.get()
}
