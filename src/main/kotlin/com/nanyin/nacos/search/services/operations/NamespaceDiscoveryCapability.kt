package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosApiGeneration

/**
 * Declares whether a protocol adapter can discover namespaces.
 *
 * Namespace discovery is a distinct, permission-sensitive capability.
 * Discovery permission denial does not hide a manually readable namespace
 * or report a connection failure; it is surfaced as a capability limitation.
 */
interface NamespaceDiscoveryCapability {
    suspend fun discoverNamespaces(target: OperationTarget): Result<List<DiscoveredNamespace>>
}

data class DiscoveredNamespace(
    val namespaceId: String,
    val displayName: String,
    val description: String? = null,
    val configCount: Long? = null
)

/**
 * Reports the content-search coverage for a given search result set.
 *
 * V3 declares server-side content search; V1 does not claim complete
 * server-side content search unless its pinned contract proves it.
 * Regex, case-sensitive, and cached-detail content search report coverage
 * rather than a false definitive no-match.
 */
data class SearchCoverage(
    val searchedCount: Int,
    val totalCount: Int,
    val isComplete: Boolean,
    val reason: String? = null
) {
    val coverageText: String
        get() = if (isComplete) "$searchedCount/$totalCount" else "$searchedCount/$totalCount (partial)"

 companion object {
     /** V3 content search is a documented server-side capability. */
     fun complete(searched: Int, total: Int) = SearchCoverage(searched, total, isComplete = true)

     /** V1 coverage-limited content search over locally available summaries/details. */
     fun partial(searched: Int, total: Int, reason: String) =
         SearchCoverage(searched, total, isComplete = false, reason = reason)

     /** Content search over a fully loaded namespace index (local). */
     fun localComplete(searched: Int, total: Int) =
         SearchCoverage(searched, total, isComplete = true, reason = "local index")
 }
}

/** Declares the content-search capability for a protocol generation. */
fun contentSearchCapability(generation: NacosApiGeneration): SearchCapability = when (generation) {
    NacosApiGeneration.V3 -> SearchCapability.SERVER_SIDE
    NacosApiGeneration.V1 -> SearchCapability.COVERAGE_LIMITED
    NacosApiGeneration.UNKNOWN -> SearchCapability.UNKNOWN
}

enum class SearchCapability {
     /** V3 documented server-side content search. */
     SERVER_SIDE,
     /** V1 coverage-limited local search; does not claim complete server-side search. */
     COVERAGE_LIMITED,
     UNKNOWN
}
