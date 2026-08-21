package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.DataSource
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SearchCoverage
import com.nanyin.nacos.search.settings.ConfigurationRequired
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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
        "config.list.failed" to "Search failed",
        "config.list.empty" to "No configurations found",
        "error.config.load.failed" to "Failed to load configurations"
    )

    private val message: (String, Array<out Any>) -> String = { key, params ->
        val template = labels[key] ?: key
        if (params.isEmpty()) template
        else params.foldIndexed(template) { i, acc, p -> acc.replace("{$i}", p.toString()) }
    }

    @Test
    fun `idle maps to empty so a cancelled loading card can be cleared`() {
        val state = ConfigListPresentation.fromSearchState(NacosSearchService.SearchState.Idle)
        assertInstanceOf(ConfigListViewState.Empty::class.java, state)
        assertEquals(ListStatus.defaultEmpty(), (state as ConfigListViewState.Empty).status)
    }

    @Test
    fun `loading maps to loading`() {
        val state = ConfigListPresentation.fromSearchState(NacosSearchService.SearchState.Loading)
        assertEquals(ConfigListViewState.Loading, state)
    }

    @Test
    fun `success with configurations maps to results and holds dataset status`() {
        val configs = listOf(config("app.properties"))
        val state = ConfigListPresentation.fromSearchState(success(configs))

        assertInstanceOf(ConfigListViewState.Results::class.java, state)
        val results = state as ConfigListViewState.Results
        assertEquals(configs, results.configurations)
        assertEquals(DataSource.REMOTE, results.status.confidence.source)
        assertEquals(
            "Remote · Confirmed · Fresh · Complete",
            ConfigListCopy.statusLine(results.status, message)
        )
    }

    @Test
    fun `success with zero configurations maps to empty not failed`() {
        val state = ConfigListPresentation.fromSearchState(success(emptyList()))

        assertInstanceOf(ConfigListViewState.Empty::class.java, state)
        val empty = state as ConfigListViewState.Empty
        assertInstanceOf(ListStatus.Dataset::class.java, empty.status)
        assertEquals(
            "Remote · Confirmed · Fresh · Complete",
            ConfigListCopy.statusLine(empty.status, message)
        )
    }

    @Test
    fun `empty and failed are distinct states`() {
        val empty = ConfigListPresentation.fromSearchState(success(emptyList()))
        val failed = ConfigListPresentation.fromSearchState(
            NacosSearchService.SearchState.Error("boom", RuntimeException("boom"))
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
            )
        )

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        val blocked = state as ConfigListViewState.Blocked
        assertEquals(BlockedReason.CONFIGURATION_REQUIRED, blocked.reason)
        assertEquals("A valid Nacos endpoint is required", blocked.detail)
        assertTrue(ConfigListCopy.blockedBody(blocked, message).contains("Configuration required"))
    }

    @Test
    fun `authentication refusal is blocked not empty`() {
        val state = ConfigListPresentation.fromSearchState(
            NacosSearchService.SearchState.Error(
                "auth",
                RemoteOperationError.Authentication(401)
            )
        )

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        val blocked = state as ConfigListViewState.Blocked
        assertEquals(BlockedReason.REFUSED_ACCESS, blocked.reason)
        assertTrue(ConfigListCopy.blockedBody(blocked, message).contains("Access refused"))
    }

    @Test
    fun `authorization refusal is blocked not empty`() {
        val state = ConfigListPresentation.fromFailure(
            RemoteOperationError.Authorization(403),
            "forbidden"
        )

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        assertEquals(
            BlockedReason.REFUSED_ACCESS,
            (state as ConfigListViewState.Blocked).reason
        )
    }

    @Test
    fun `invalid or expired nacos password token is blocked not empty`() {
        // Pairs with StaleSearchFallbackPolicy refuse for the same type (#122).
        val state = ConfigListPresentation.fromFailure(
            RemoteOperationError.InvalidOrExpiredNacosPasswordToken(403),
            "token expired"
        )

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        assertEquals(
            BlockedReason.REFUSED_ACCESS,
            (state as ConfigListViewState.Blocked).reason
        )
    }

    @Test
    fun `generic search error is failed with search title key`() {
        val state = ConfigListPresentation.fromSearchState(
            NacosSearchService.SearchState.Error(
                "timeout",
                RemoteOperationError.Connection(RuntimeException("down"))
            )
        )

        assertInstanceOf(ConfigListViewState.Failed::class.java, state)
        val failed = state as ConfigListViewState.Failed
        assertEquals(ConfigListPresentation.SEARCH_FAILED_TITLE_KEY, failed.titleKey)
        assertEquals("Connection failed", failed.detail)
        assertEquals(
            "Search failed: Connection failed",
            ConfigListCopy.failedBody(failed, message)
        )
    }

    @Test
    fun `non-search failure is not stamped Search failed`() {
        val state = ConfigListPresentation.fromFailure(
            error = null,
            fallbackMessage = "Failed to load configurations: boom",
            titleKey = null
        )

        assertInstanceOf(ConfigListViewState.Failed::class.java, state)
        val failed = state as ConfigListViewState.Failed
        assertNull(failed.titleKey)
        assertEquals("Failed to load configurations: boom", failed.detail)
        assertEquals(
            "Failed to load configurations: boom",
            ConfigListCopy.failedBody(failed, message)
        )
        assertTrue(!ConfigListCopy.failedBody(failed, message).startsWith("Search failed"))
    }

    @Test
    fun `non-search failure keeps caller fallback when a throwable is present`() {
        // Production showError(loadMessage, e) always passes both; the composed
        // fallback is the full body and must not collapse to e.message alone.
        val state = ConfigListPresentation.fromFailure(
            error = RuntimeException("boom"),
            fallbackMessage = "Failed to load configurations: boom",
            titleKey = null
        )

        assertInstanceOf(ConfigListViewState.Failed::class.java, state)
        val failed = state as ConfigListViewState.Failed
        assertNull(failed.titleKey)
        assertEquals("Failed to load configurations: boom", failed.detail)
        assertEquals(
            "Failed to load configurations: boom",
            ConfigListCopy.failedBody(failed, message)
        )
        assertTrue(failed.detail != "boom")
    }

    @Test
    fun `search failure detail is the throwable message not a prefixed fallback`() {
        val state = ConfigListPresentation.fromSearchState(
            NacosSearchService.SearchState.Error(
                "Connection failed",
                Exception("Connection failed")
            )
        )

        val failed = state as ConfigListViewState.Failed
        assertEquals(ConfigListPresentation.SEARCH_FAILED_TITLE_KEY, failed.titleKey)
        assertEquals("Connection failed", failed.detail)
        assertEquals(
            "Search failed: Connection failed",
            ConfigListCopy.failedBody(failed, message)
        )
    }

    @Test
    fun `blank throwable message keeps the caller fallback as the detail`() {
        val state = ConfigListPresentation.fromFailure(
            error = RuntimeException(""),
            fallbackMessage = "boom",
            titleKey = ConfigListPresentation.SEARCH_FAILED_TITLE_KEY
        )

        val failed = state as ConfigListViewState.Failed
        assertEquals("boom", failed.detail)
    }

    @Test
    fun `configuration-required wrapped under a generic exception is still blocked`() {
        val wrapped = Exception(
            "search failed",
            ConfigurationRequired(listOf("Select a Nacos environment profile"))
        )
        val state = ConfigListPresentation.fromFailure(wrapped, "search failed")

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        assertEquals(
            BlockedReason.CONFIGURATION_REQUIRED,
            (state as ConfigListViewState.Blocked).reason
        )
    }

    @Test
    fun `authentication wrapped under a generic exception is still blocked`() {
        val wrapped = Exception("search failed", RemoteOperationError.Authentication(401))
        val state = ConfigListPresentation.fromFailure(wrapped, "search failed")

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        assertEquals(
            BlockedReason.REFUSED_ACCESS,
            (state as ConfigListViewState.Blocked).reason
        )
    }

    @Test
    fun `authorization wrapped under a generic exception is still blocked`() {
        val wrapped = Exception("search failed", RemoteOperationError.Authorization(403))
        val state = ConfigListPresentation.fromFailure(wrapped, "search failed")

        assertInstanceOf(ConfigListViewState.Blocked::class.java, state)
        assertEquals(
            BlockedReason.REFUSED_ACCESS,
            (state as ConfigListViewState.Blocked).reason
        )
    }

    @Test
    fun `content-search coverage rides on the results status line`() {
        val state = ConfigListPresentation.fromSearchState(
            success(
                listOf(config("app.properties")),
                coverage = SearchCoverage.partial(2, 5, "local")
            )
        )

        val results = state as ConfigListViewState.Results
        assertEquals(
            "Remote · Confirmed · Fresh · Complete · Coverage 2/5 (partial)",
            ConfigListCopy.statusLine(results.status, message)
        )
    }

    @Test
    fun `blank status resolves to empty string so the panel invents nothing`() {
        assertEquals("", ConfigListCopy.statusLine(ListStatus.Blank, message))
    }

    @Test
    fun `default empty status resolves to the empty label outside the panel`() {
        assertEquals(
            "No configurations found",
            ConfigListCopy.statusLine(ListStatus.defaultEmpty(), message)
        )
    }

    @Test
    fun `every closed state is constructible and distinct`() {
        val states: List<ConfigListViewState> = listOf(
            ConfigListViewState.Empty(),
            ConfigListViewState.Loading,
            ConfigListViewState.Results(
                listOf(config("a")),
                ListStatus.Dataset(
                    CacheConfidence.remoteConfirmed(1L, DatasetCompleteness.COMPLETE)
                )
            ),
            ConfigListViewState.Blocked(BlockedReason.REFUSED_ACCESS),
            ConfigListViewState.Failed(
                titleKey = ConfigListPresentation.SEARCH_FAILED_TITLE_KEY,
                detail = "x"
            )
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
