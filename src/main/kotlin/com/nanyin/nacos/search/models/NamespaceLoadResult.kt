package com.nanyin.nacos.search.models

enum class DatasetCompleteness { COMPLETE, PARTIAL, FAILED }

/**
 * Result of loading all configurations for a namespace. Captures the expected
 * count from metadata and the successfully fetched configurations, so callers
 * can distinguish complete, partial, and failed loads instead of collapsing
 * everything into success.
 *
 * [stoppingCause] is the typed error that stopped summary pagination (issue
 * #122). It is independent of [completeness]: a first-page failure is
 * [DatasetCompleteness.FAILED] with no rows, a later-page failure is
 * [DatasetCompleteness.PARTIAL] with the rows loaded so far, and both keep
 * the original remote cause rather than collapsing it to a generic
 * connection failure.
 */
data class NamespaceLoadResult(
    val completeness: DatasetCompleteness,
    val expectedCount: Int,
    val configurations: List<NacosConfiguration>,
    val stoppingCause: Throwable? = null
)
