package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.services.operations.ConfigurationCoordinate
import com.nanyin.nacos.search.services.operations.HistoryDetail
import com.nanyin.nacos.search.services.operations.HistoryEntry
import com.nanyin.nacos.search.services.operations.HistoryPage
import com.nanyin.nacos.search.services.operations.HistoryQuery
import com.nanyin.nacos.search.services.operations.Observed
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.services.operations.RemoteOperationError

/**
 * Non-Swing controller for configuration history browsing. Maps gateway
 * outcomes to distinct user-visible states so UI and tests share one path.
 *
 * Whether a completed read may still update the dialog is not decided here: it
 * is the same judgement the detail view and the result list use (ADR-0047).
 * The revision list and the diff are two presentation slots, so each is offered
 * its own gate rather than sharing one mark.
 *
 * Every coordinate judged here comes from the browsing caller's [OperationTarget]
 * and [ConfigurationCoordinate], never from the server's response: a revision
 * row that omits its data id would otherwise name a coordinate no view presents,
 * and every detail read would be discarded.
 */
class HistoryBrowserController(
    private val gateway: OperationGateway,
    private val listPresentation: PresentationGate,
    private val diffPresentation: PresentationGate
) {
    sealed class Outcome {
        data object Loading : Outcome()
        data object Empty : Outcome()
        data class Body(val page: HistoryPage) : Outcome()
        data object PermissionDenied : Outcome()
        data object Unsupported : Outcome()
        data class Failed(val message: String) : Outcome()
        /** Dropped because the judgement no longer admits this result. */
        data object Stale : Outcome()
    }

    suspend fun loadPage(
        target: OperationTarget,
        query: HistoryQuery,
        sessionEpoch: Long
    ): Outcome {
        val result = gateway.listHistory(target, query, forceRefresh = true, useCache = false)
        if (!listPresentation.admitAndRecord(presented(target, query.coordinate, sessionEpoch, result))) {
            return Outcome.Stale
        }
        return result.fold(
            onSuccess = { observed ->
                val page = observed.value
                if (page.totalCount <= 0 || page.items.isEmpty()) Outcome.Empty
                else Outcome.Body(page)
            },
            onFailure = { error ->
                when (error) {
                    is RemoteOperationError.Authorization -> Outcome.PermissionDenied
                    is RemoteOperationError.CapabilityUnsupported,
                    is RemoteOperationError.Unsupported -> Outcome.Unsupported
                    else -> Outcome.Failed(error.message ?: error.toString())
                }
            }
        )
    }

    suspend fun loadDetail(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate,
        historyId: String,
        sessionEpoch: Long
    ): Result<HistoryDetail> {
        val result = gateway.readHistoryDetail(target, historyId, forceRefresh = true, useCache = false)
        if (!diffPresentation.admitAndRecord(presented(target, coordinate, sessionEpoch, result))) {
            return Result.failure(RemoteOperationError.Cancelled("History detail dropped after session change"))
        }
        return result.map { it.value }
    }

    fun selectedEntries(entries: List<HistoryEntry>, indices: IntArray): List<HistoryEntry> =
        indices.toList().mapNotNull { idx -> entries.getOrNull(idx) }

    /** A failed read reports no sequence, so it is judged on epoch and coordinate alone. */
    private fun <T> presented(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate,
        sessionEpoch: Long,
        result: Result<Observed<T>>
    ): PresentedResult = PresentedResult(
        sessionEpoch,
        PresentedCoordinate.of(target.namespaceId, coordinate.dataId, coordinate.group),
        result.getOrNull()?.observation ?: Observed.NO_OBSERVATION
    )
}
