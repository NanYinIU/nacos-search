package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.services.operations.HistoryDetail
import com.nanyin.nacos.search.services.operations.HistoryEntry
import com.nanyin.nacos.search.services.operations.HistoryPage
import com.nanyin.nacos.search.services.operations.HistoryQuery
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.services.operations.RemoteOperationError

/**
 * Non-Swing controller for configuration history browsing. Maps gateway
 * outcomes to distinct user-visible states so UI and tests share one path.
 */
class HistoryBrowserController(
    private val gateway: OperationGateway,
    private val generation: () -> Long = { 0L }
) {
    sealed class Outcome {
        data object Loading : Outcome()
        data object Empty : Outcome()
        data class Body(val page: HistoryPage) : Outcome()
        data object PermissionDenied : Outcome()
        data object Unsupported : Outcome()
        data class Failed(val message: String) : Outcome()
        /** Dropped because the caller's generation advanced while the request was in flight. */
        data object Stale : Outcome()
    }

    suspend fun loadPage(
        target: OperationTarget,
        query: HistoryQuery,
        expectedGeneration: Long
    ): Outcome {
        val result = gateway.listHistory(target, query, forceRefresh = true, useCache = false)
        if (generation() != expectedGeneration) return Outcome.Stale
        return result.fold(
            onSuccess = { page ->
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
        historyId: String,
        expectedGeneration: Long
    ): Result<HistoryDetail> {
        val result = gateway.readHistoryDetail(target, historyId, forceRefresh = true, useCache = false)
        if (generation() != expectedGeneration) {
            return Result.failure(RemoteOperationError.Cancelled("History detail dropped after session change"))
        }
        return result
    }

    fun selectedEntries(entries: List<HistoryEntry>, indices: IntArray): List<HistoryEntry> =
        indices.toList().mapNotNull { idx -> entries.getOrNull(idx) }
}
