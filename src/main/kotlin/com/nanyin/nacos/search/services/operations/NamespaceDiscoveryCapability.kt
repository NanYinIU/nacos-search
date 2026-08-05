package com.nanyin.nacos.search.services.operations

/**
 * A Namespace returned by dialect Namespace discovery.
 *
 * Discovery permission denial does not hide a manually readable Namespace
 * or report a connection failure; it is surfaced as a capability limitation
 * (ADR-0005 / ADR-0013).
 */
data class DiscoveredNamespace(
    val namespaceId: String,
    val displayName: String,
    val description: String? = null,
    val configCount: Long? = null
)

/**
 * Reports the content-search coverage for a given search result set.
 *
 * Dialects declare content-search grade via [ProtocolCapabilities]; callers
 * turn that grade into [SearchCoverage] without switching on API generation.
 * Regex, case-sensitive, and cached-detail content search report coverage
 * rather than a false definitive no-match (ADR-0017).
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
        /** Server-side content search declared COMPLETE by the dialect. */
        fun complete(searched: Int, total: Int) = SearchCoverage(searched, total, isComplete = true)

        /** Coverage-limited content search (dialect declares LIMITED). */
        fun partial(searched: Int, total: Int, reason: String) =
            SearchCoverage(searched, total, isComplete = false, reason = reason)

        /** Content search over a fully loaded namespace index (local). */
        fun localComplete(searched: Int, total: Int) =
            SearchCoverage(searched, total, isComplete = true, reason = "local index")
    }
}

/**
 * Turns a dialect's content-search [CapabilityCoverage] into a [SearchCoverage]
 * report for a remote or local result set. Does not consult API generation.
 */
fun searchCoverageFromCapability(
    coverage: CapabilityCoverage,
    searched: Int,
    total: Int,
    limitedReason: String,
    localIndex: Boolean = false
): SearchCoverage? = when (coverage) {
    CapabilityCoverage.COMPLETE -> SearchCoverage.complete(searched, total)
    CapabilityCoverage.LIMITED -> SearchCoverage.partial(searched, total, limitedReason)
    CapabilityCoverage.UNAVAILABLE -> if (localIndex) SearchCoverage.localComplete(searched, total) else null
}
