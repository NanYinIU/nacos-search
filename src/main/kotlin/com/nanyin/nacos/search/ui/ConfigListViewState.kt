package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.operations.SearchCoverage

/**
 * Closed set of user-visible states for the configuration result list
 * (issue #79 / parent #44).
 *
 * The panel renders with one exhaustive branch and holds no decision — adding a
 * state later forces every renderer to handle it. Mapping from search and access
 * outcomes lives in [ConfigListPresentation], not in Swing.
 *
 * User-visible wording is **not** stored as resolved strings. Status and failure
 * titles are re-derived from structured inputs on every [ConfigListPanel.render]
 * (including language change) so source/confirmation/age/completeness stay
 * independent and localisable (ADR-0036).
 *
 * - [Empty]: a successful response with zero matches (or no namespace yet).
 * - [Loading]: a search is in flight.
 * - [Results]: one or more configurations to show.
 * - [Blocked]: refused access or configuration-required — never an empty namespace.
 * - [Failed]: a search/load failure that is not a block.
 */
sealed class ConfigListViewState {
    data class Empty(
        val status: ListStatus = ListStatus.defaultEmpty()
    ) : ConfigListViewState()

    object Loading : ConfigListViewState()

    data class Results(
        val configurations: List<NacosConfiguration>,
        val status: ListStatus.Dataset
    ) : ConfigListViewState()

    data class Blocked(
        val reason: BlockedReason,
        /** Optional technical detail; the title comes from [reason]. */
        val detail: String? = null
    ) : ConfigListViewState()

    /**
     * @param titleKey optional bundle key for a failure title. Search failures
     * use `config.list.failed`; non-search paths leave this null and show
     * [detail] as the full message (no “Search failed” stamp).
     * @param detail optional detail or full free-form message.
     */
    data class Failed(
        val titleKey: String? = null,
        val detail: String? = null
    ) : ConfigListViewState()
}

/**
 * Status-line content held without resolving bundle strings, so language
 * changes and independent dimension updates re-derive wording outside the
 * panel's decision path.
 */
sealed class ListStatus {
    /** Status line intentionally blank — the panel must not invent wording. */
    object Blank : ListStatus()

    data class Bundle(
        val key: String,
        val params: List<Any> = emptyList()
    ) : ListStatus()

    data class Dataset(
        val confidence: CacheConfidence,
        val coverage: SearchCoverage? = null
    ) : ListStatus()

    companion object {
        fun defaultEmpty(): ListStatus = Bundle("config.list.empty")
    }
}

/**
 * Why the list is blocked rather than empty or failed (CONTEXT.md
 * 「访问受阻」/「需要配置」).
 */
enum class BlockedReason {
    /** Authentication or authorization refused access to the dataset. */
    REFUSED_ACCESS,

    /** Local configuration cannot safely construct an operation context. */
    CONFIGURATION_REQUIRED
}
