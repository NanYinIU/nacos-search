package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SearchCoverage
import com.nanyin.nacos.search.settings.ConfigurationRequired
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Closed list state set (issue #79): every state is driven at least once with
 * no platform fixture, no event-dispatch pumping, and no geometry assertions.
 */
class ConfigListPresentationTest {

    private val labels = mapOf(
        "config.list.status.source.remote" to "Remote",
        "config.list.status.source.cache" to "Cache",
        "config.list.status.confirmation.confirmed" to "Confirmed",
        "config.list.status.confirmation.unconfirmed" to "Unconfirmed",
        "config.list.status.confirmation.refreshFailed" to "Refresh failed",
        "config.list.status.age.fresh" to "Fresh",
        "config.list.status.age.stale" to "Stale",
        "config.list.status.age.deepStale" to "Deep stale",
        "config.list.status.completeness.complete" to "Complete",
        "config.list.status.completeness.partial" to "Partial",
        "config.list.status.completeness.failed" to "Failed",
        "config.list.status.coverage.partial" to "Coverage {0}",
        "config.list.blocked.access" to "Access refused",
        "config.list.blocked.configuration" to "Configuration required",
        "config.list.failed" to "Search failed"
    )

    private val message: (String, Array<out Any>) -> String = { key, params ->
        val template = labels[key] ?: key
        if (params.isEmpty()) template
        else params.foldIndexed(template) { i, acc, p -> acc.replace("{$i}", p.toString()) }
    }

    @Test
    fun `idle maps to empty`() {
        val state = ConfigListPresentation.fromSearchState(
            NacosSearchService.SearchState.Idle,
            message
        )
        assertInstanceOf(ConfigListViewState.Empty::class.java, state)
    }

    @Test
    fun `loading maps to loading`() {
        val state = ConfigListPresentation.fromSearchState(
            NacosSearchService.SearchState.Loading,
            message
        )
        assertEquals(ConfigListViewState.Loading, state)
    }

    @Test
    fun `success with configurations maps to results and carries status line`() {
        val configs = listOf(config("app.properties"))
        val state = ConfigListPresentation.fromSearchState(
            success(configs),
            message
        )

        assertInstanceOf(ConfigListViewState.Results::class.java, state)
        val results = state as ConfigListViewState.Results
        assertEquals(configs, results.configurations)
        assertEquals("Remote · Confirmed · Fresh · Complete", results.statusLine)
    }

    @Test
    fun `success with zero configurations maps to empty not failed`() {
        val state = ConfigListPresentation.fromSearchState(
            success(emptyList()),
            message
        )

        assertInstanceOf(ConfigListViewState.Empty::class.java, state)
        val empty = state as ConfigListViewState.Empty
        assertEquals("Remote · Confirmed · Fresh · Complete", empty.statusLine)
    }

    @Test
    fun `empty and failed are distinct states`() {
        val empty = ConfigListPresentation.fromSearchState(success(emptyList()), message)
        val failed = ConfigListPresentation.fromSearchState(
            NacosSearchService.SearchState.Error("boom", RuntimeException("boom")),
            message
        )

        assertInstanceOf(ConfigListViewState.Empty::class.java, empty)
        assertInstanceOf(ConfigListViewState.Failed::class.java, failed)
        assertTrue(empty::class != failed::class)
    }

    @Test
    fun `configuration-required is blocked not empty`() {
        val state = ConfigListPresentation.fromSearchState(
            NacosSearchService.SearchState.Error(
                "incomplete",
                ConfigurationRequired(listOf("A valid Nacos endpoint is required"))
            ),
            message
        )

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        val blocked = state as ConfigListViewState.Blocked
        assertEquals(BlockedReason.CONFIGURATION_REQUIRED, blocked.reason)
        assertTrue(blocked.message.contains("Configuration required"))
    }

    @Test
    fun `authentication refusal is blocked not empty`() {
        val state = ConfigListPresentation.fromSearchState(
            NacosSearchService.SearchState.Error(
                "auth",
                RemoteOperationError.Authentication(401)
            ),
            message
        )

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        val blocked = state as ConfigListViewState.Blocked
        assertEquals(BlockedReason.REFUSED_ACCESS, blocked.reason)
        assertTrue(blocked.message.contains("Access refused"))
    }

    @Test
    fun `authorization refusal is blocked not empty`() {
        val state = ConfigListPresentation.fromFailure(
            RemoteOperationError.Authorization(403),
            "forbidden",
            message
        )

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        assertEquals(
            BlockedReason.REFUSED_ACCESS,
            (state as ConfigListViewState.Blocked).reason
        )
    }

    @Test
    fun `generic search error is failed not empty and not blocked`() {
        val state = ConfigListPresentation.fromSearchState(
            NacosSearchService.SearchState.Error(
                "timeout",
                RemoteOperationError.Connection(RuntimeException("down"))
            ),
            message
        )

        assertInstanceOf(ConfigListViewState.Failed::class.java, state)
        val failed = state as ConfigListViewState.Failed
        assertTrue(failed.message.contains("Search failed"))
    }

    @Test
    fun `configuration-required wrapped under a generic exception is still blocked`() {
        val wrapped = Exception(
            "search failed",
            ConfigurationRequired(listOf("Select a Nacos environment profile"))
        )
        val state = ConfigListPresentation.fromFailure(wrapped, "search failed", message)

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        assertEquals(
            BlockedReason.CONFIGURATION_REQUIRED,
            (state as ConfigListViewState.Blocked).reason
        )
    }

    @Test
    fun `content-search coverage rides on the results status line`() {
        val state = ConfigListPresentation.fromSearchState(
            success(
                listOf(config("app.properties")),
                coverage = SearchCoverage.partial(2, 5, "local")
            ),
            message
        )

        val results = state as ConfigListViewState.Results
        assertTrue(results.statusLine.contains("Coverage 2/5 (partial)"))
    }

    @Test
    fun `every closed state is constructible and distinct`() {
        // Drives the sealed set itself once each so a new branch without a test
        // cannot hide inside an untested subtype.
        val states: List<ConfigListViewState> = listOf(
            ConfigListViewState.Empty(statusLine = "none"),
            ConfigListViewState.Loading,
            ConfigListViewState.Results(listOf(config("a")), statusLine = "ok"),
            ConfigListViewState.Blocked(BlockedReason.REFUSED_ACCESS, "Access refused"),
            ConfigListViewState.Failed("Search failed")
        )
        assertEquals(5, states.map { it::class }.toSet().size)
    }

    private fun success(
        configurations: List<NacosConfiguration>,
        coverage: SearchCoverage? = null
    ) = NacosSearchService.SearchState.Success(
        configurations = configurations,
        totalCount = configurations.size,
        pageNumber = 1,
        pageSize = 20,
        pagesAvailable = 1,
        confidence = CacheConfidence.remoteConfirmed(
            now = 1L,
            completeness = DatasetCompleteness.COMPLETE
        ),
        coverage = coverage
    )

    private fun config(dataId: String) = NacosConfiguration(
        dataId = dataId,
        group = "DEFAULT_GROUP",
        content = "k=v"
    )
}
