package com.nanyin.nacos.search.services.operations

/**
 * Read-only metadata for a single historical configuration version.
 *
 * A [HistoryEntry] from [ProtocolAdapter.listHistory] carries no content
 * body — call [ProtocolAdapter.readHistoryDetail] to load it.
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
