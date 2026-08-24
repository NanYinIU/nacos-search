package com.nanyin.nacos.search.models

/**
 * Visible search-box criteria consumed by the session-owned
 * [com.nanyin.nacos.search.services.NacosSearchService].
 *
 * [dataId] is a case-insensitive literal substring of Data ID. [group] is the
 * independent Group picker value and is never folded into that substring.
 * Namespace and pagination live on the held search session /
 * [com.nanyin.nacos.search.services.NacosSearchService.PaginationState], not here.
 */
data class SearchCriteria(
    val dataId: String = "",
    val group: String = ""
)
