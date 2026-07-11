package com.nanyin.nacos.search.models

import com.nanyin.nacos.search.services.network.NacosRequestError

enum class DatasetCompleteness { COMPLETE, PARTIAL, FAILED }

/**
 * Result of loading all configurations for a namespace. Captures the expected
 * count from metadata, the successfully fetched configurations, and any
 * per-item failures so callers can distinguish complete, partial, and failed
 * loads instead of collapsing everything into success.
 */
data class NamespaceLoadResult(
    val completeness: DatasetCompleteness,
    val expectedCount: Int,
    val configurations: List<NacosConfiguration>,
    val failures: List<ConfigLoadFailure>
)

data class ConfigLoadFailure(
    val dataId: String,
    val group: String,
    val error: NacosRequestError
)
