package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.operations.Observed

/**
 * The configuration coordinate a presented result is about (CONTEXT.md
 * 「配置坐标」): canonical namespace id, data id and group.
 *
 * The namespace is part of it because two namespaces can hold the same data id
 * and group. [NamespaceInfo.canonicalId] is the one derivation of that
 * namespace, so a coordinate taken from a list row and one taken from a detail
 * read cannot differ by spelling alone. The operation layer's
 * [com.nanyin.nacos.search.services.operations.ConfigurationCoordinate] is the
 * namespace-relative pair used inside an already-namespaced operation target;
 * this one names the namespace itself because no target accompanies it here.
 */
data class PresentedCoordinate(
    val namespaceId: String,
    val dataId: String,
    val group: String
) {
    companion object {
        fun of(namespaceId: String?, dataId: String, group: String): PresentedCoordinate =
            PresentedCoordinate(NamespaceInfo.canonicalId(namespaceId), dataId, group)

        fun of(configuration: NacosConfiguration): PresentedCoordinate =
            of(configuration.tenantId, configuration.dataId, configuration.group)
    }
}

/**
 * One asynchronous result offered to a view, described by the three facts that
 * decide whether it may still be painted (ADR-0047):
 *
 * - [sessionEpoch] is the project session's epoch **when the operation
 *   started**, not when it finished.
 * - [coordinate] is the configuration the result describes, or null for a
 *   result that is not about a single configuration — the result list. Null
 *   equals only null, so a detail result can never paint into a view with
 *   nothing selected.
 * - [observation] is the sequence the read took, which only a read that reached
 *   the server has. A read served from cache carries [Observed.NO_OBSERVATION],
 *   and so does a **failed** read: the gateway takes its sequence before
 *   dispatching but reports it only alongside a payload, so a failure has no
 *   number to be ordered by and is judged on epoch and coordinate alone.
 */
data class PresentedResult(
    val sessionEpoch: Long,
    val coordinate: PresentedCoordinate?,
    val observation: Long = Observed.NO_OBSERVATION
)

/**
 * The one judgement of whether an asynchronous result may still update what the
 * tool window presents (ADR-0047). It holds no Swing — the detail view, the
 * result list and the history dialog decide this one way, and tests drive it
 * without a platform fixture.
 *
 * This is deliberately the same shape as
 * [com.nanyin.nacos.search.services.operations.ObservationHighWater], which
 * orders cache mutations: a mark that only an accepted result raises, under a
 * scope that equals the thing being judged. The scope here is the presented
 * coordinate — the mark is what that coordinate last painted, so a read for a
 * newly selected coordinate is never ranked against the previous selection's.
 *
 * One gate governs one presentation slot. A view that paints two regions from
 * separate reads — the history dialog's revision list and its diff — holds one
 * gate per region, so an older read of one region cannot be rejected by a newer
 * read of the other.
 *
 * A cache hit is admitted but never raises the mark: the sequence rule orders
 * remote observations against each other, it is not a freshness gate.
 */
class PresentationGate(
    private val currentSessionEpoch: () -> Long,
    private val currentCoordinate: () -> PresentedCoordinate? = { null }
) {
    private data class Painted(val coordinate: PresentedCoordinate?, val observation: Long)

    private var lastPainted: Painted? = null

    /**
     * True when [result] may still be painted, in which case it is recorded as
     * what its coordinate last painted. A result this rejects leaves the mark
     * alone, so a discarded late arrival cannot lock out a genuine one.
     *
     * Idempotent for a single result: a caller that checkpoints again after
     * hopping to the event dispatch thread asks with the same triple and gets
     * the same answer.
     */
    @Synchronized
    fun admitAndRecord(result: PresentedResult): Boolean {
        if (result.sessionEpoch != currentSessionEpoch()) return false
        if (result.coordinate != currentCoordinate()) return false

        val painted = lastPainted?.takeIf { it.coordinate == result.coordinate }
        if (result.observation != Observed.NO_OBSERVATION &&
            painted != null &&
            result.observation < painted.observation
        ) {
            return false
        }

        lastPainted = Painted(
            result.coordinate,
            maxOf(result.observation, painted?.observation ?: Observed.NO_OBSERVATION)
        )
        return true
    }
}
