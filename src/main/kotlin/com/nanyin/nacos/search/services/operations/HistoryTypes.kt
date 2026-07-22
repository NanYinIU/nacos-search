package com.nanyin.nacos.search.services.operations

/**
 * Read-only metadata for a single historical configuration version.
 *
 * A [HistoryEntry] from [HistoryCapability.listHistory] carries no content
 * body — call [HistoryCapability.readHistoryDetail] to load it.
 */
data class HistoryEntry(
    val id: String,
    val dataId: String,
    val group: String,
    val tenantId: String?,
    val type: String?,
    val md5: String?,
    val lastModified: Long,
    val opType: String?
)

/** Paginated list of historical entries for one configuration coordinate. */
data class HistoryPage(
    val totalCount: Int,
    val pageNumber: Int,
    val pagesAvailable: Int,
    val items: List<HistoryEntry>
)

/** A single historical version with its full content body loaded. */
data class HistoryDetail(
    val id: String,
    val dataId: String,
    val group: String,
    val tenantId: String?,
    val content: String,
    val type: String?,
    val md5: String?,
    val lastModified: Long,
    val opType: String?
) {
    fun toEntry(): HistoryEntry = HistoryEntry(id, dataId, group, tenantId, type, md5, lastModified, opType)
}

/** Query parameters for listing history within one coordinate. */
data class HistoryQuery(
    val coordinate: ConfigurationCoordinate,
    val pageNo: Int = 1,
    val pageSize: Int = 100
) {
    fun cacheKey(): String = listOf(
        "dataId=${coordinate.dataId}",
        "group=${coordinate.group}",
        "pageNo=$pageNo",
        "pageSize=$pageSize"
    ).joinToString("|")
}

/**
 * Declares whether a protocol adapter can list and read configuration
 * history. History is a distinct, permission-sensitive capability.
 *
 * All history data is scoped by the complete access identity, namespace, and
 * configuration coordinate captured in [OperationTarget]. History operations
 * never merge data across identities, generations, principals, or namespaces.
 * No restore, rollback, republish, or other mutation is exposed.
 */
interface HistoryCapability {
    suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage>

    suspend fun readHistoryDetail(target: OperationTarget, historyId: String): Result<HistoryDetail>
}

/**
 * One side of an IntelliJ Diff comparison. A [HistoryDiffSide] never creates
 * an edit session or publish command; it is purely presentational.
 */
data class HistoryDiffSide(
    val title: String,
    val content: String,
    val contentType: String?
)

/**
 * A request to show an IntelliJ Diff between two read-only sides.
 *
 * Both history-to-history and history-to-current comparisons are supported.
 * No mutation, restore, or republish is derived from a diff.
 */
data class HistoryDiffRequest(
    val left: HistoryDiffSide,
    val right: HistoryDiffSide
)
