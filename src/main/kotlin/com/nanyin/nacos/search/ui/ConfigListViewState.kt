package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NacosConfiguration

/**
 * Closed set of user-visible states for the configuration result list
 * (issue #79 / parent #44).
 *
 * The panel renders with one exhaustive branch and holds no decision — adding a
 * state later forces every renderer to handle it. Mapping from search and access
 * outcomes lives in [ConfigListPresentation], not in Swing.
 *
 * - [Empty]: a successful response with zero matches (or no namespace yet).
 * - [Loading]: a search is in flight.
 * - [Results]: one or more configurations to show.
 * - [Blocked]: refused access or configuration-required — never an empty namespace.
 * - [Failed]: a search/load failure that is not a block.
 */
sealed class ConfigListViewState {
    data class Empty(
        val statusLine: String = ""
    ) : ConfigListViewState()

    data object Loading : ConfigListViewState()

    data class Results(
        val configurations: List<NacosConfiguration>,
        val statusLine: String
    ) : ConfigListViewState()

    data class Blocked(
        val reason: BlockedReason,
        val message: String
    ) : ConfigListViewState()

    data class Failed(
        val message: String
    ) : ConfigListViewState()
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
