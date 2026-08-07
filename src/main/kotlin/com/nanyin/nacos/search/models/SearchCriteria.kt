package com.nanyin.nacos.search.models

/**
 * Search criteria consumed by the session-owned [com.nanyin.nacos.search.services.NacosSearchService].
 * Namespace and pagination live on the held search session / [PaginationState], not here.
 */
data class SearchCriteria(
    val query: String = "",
    val group: String = "",
    val dataId: String = "",
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val searchContent: Boolean = true
)
