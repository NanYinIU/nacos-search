package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.CacheAge
import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.DataSource
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetConfirmation
import com.nanyin.nacos.search.services.operations.SearchCoverage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Status-line wording is derived where the four dimensions are known
 * (ADR-0036 / issue #79). No platform fixture, no event-dispatch pumping,
 * no geometry — the contract is the string the pure function produces.
 */
class ConfigListStatusLineTest {

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
        "config.list.status.coverage.partial" to "Coverage {0}"
    )

    private val message: (String, Array<out Any>) -> String = { key, params ->
        val template = labels[key] ?: key
        if (params.isEmpty()) template
        else params.foldIndexed(template) { i, acc, p -> acc.replace("{$i}", p.toString()) }
    }

    @Test
    fun `remote confirmed complete within TTL is the four default labels`() {
        val line = ConfigListStatusLine.derive(
            confidence(
                source = DataSource.REMOTE,
                confirmation = DatasetConfirmation.CONFIRMED,
                age = CacheAge.WITHIN_TTL,
                completeness = DatasetCompleteness.COMPLETE
            ),
            message = message
        )

        assertEquals("Remote · Confirmed · Fresh · Complete", line)
    }

    @Test
    fun `source changes independently of confirmation age and completeness`() {
        val remote = ConfigListStatusLine.derive(
            confidence(source = DataSource.REMOTE),
            message = message
        )
        val cache = ConfigListStatusLine.derive(
            confidence(source = DataSource.CACHE),
            message = message
        )

        assertTrue(remote.startsWith("Remote"))
        assertTrue(cache.startsWith("Cache"))
        // The other three dimensions stay put.
        assertTrue(remote.endsWith("Confirmed · Fresh · Complete"))
        assertTrue(cache.endsWith("Confirmed · Fresh · Complete"))
    }

    @Test
    fun `confirmation changes without rewriting age`() {
        val confirmed = ConfigListStatusLine.derive(
            confidence(
                confirmation = DatasetConfirmation.CONFIRMED,
                age = CacheAge.STALE
            ),
            message = message
        )
        val refreshFailed = ConfigListStatusLine.derive(
            confidence(
                confirmation = DatasetConfirmation.REFRESH_FAILED,
                age = CacheAge.STALE
            ),
            message = message
        )

        assertTrue(confirmed.contains("Confirmed"))
        assertTrue(refreshFailed.contains("Refresh failed"))
        assertFalse(refreshFailed.contains("Confirmed"))
        // Age is still Stale on both — failure evidence did not rewrite it.
        assertTrue(confirmed.contains("Stale"))
        assertTrue(refreshFailed.contains("Stale"))
    }

    @Test
    fun `age changes without erasing failure evidence`() {
        val staleFailed = ConfigListStatusLine.derive(
            confidence(
                confirmation = DatasetConfirmation.REFRESH_FAILED,
                age = CacheAge.STALE
            ),
            message = message
        )
        val deepStaleFailed = ConfigListStatusLine.derive(
            confidence(
                confirmation = DatasetConfirmation.REFRESH_FAILED,
                age = CacheAge.DEEP_STALE
            ),
            message = message
        )

        assertTrue(staleFailed.contains("Refresh failed"))
        assertTrue(deepStaleFailed.contains("Refresh failed"))
        assertTrue(staleFailed.contains("Stale"))
        assertTrue(deepStaleFailed.contains("Deep stale"))
        assertFalse(deepStaleFailed.contains(" · Stale ·"))
    }

    @Test
    fun `completeness changes independently of the other three dimensions`() {
        val complete = ConfigListStatusLine.derive(
            confidence(completeness = DatasetCompleteness.COMPLETE),
            message = message
        )
        val partial = ConfigListStatusLine.derive(
            confidence(completeness = DatasetCompleteness.PARTIAL),
            message = message
        )
        val failed = ConfigListStatusLine.derive(
            confidence(completeness = DatasetCompleteness.FAILED),
            message = message
        )

        assertTrue(complete.endsWith("Complete"))
        assertTrue(partial.endsWith("Partial"))
        assertTrue(failed.endsWith("Failed"))
        // Shared prefix: source, confirmation, age unchanged.
        assertTrue(complete.startsWith("Remote · Confirmed · Fresh ·"))
        assertTrue(partial.startsWith("Remote · Confirmed · Fresh ·"))
        assertTrue(failed.startsWith("Remote · Confirmed · Fresh ·"))
    }

    @Test
    fun `unconfirmed restored cache keeps its age label`() {
        val line = ConfigListStatusLine.derive(
            confidence(
                source = DataSource.CACHE,
                confirmation = DatasetConfirmation.UNCONFIRMED,
                age = CacheAge.WITHIN_TTL,
                completeness = DatasetCompleteness.COMPLETE
            ),
            message = message
        )

        assertEquals("Cache · Unconfirmed · Fresh · Complete", line)
    }

    @Test
    fun `partial content-search coverage is surfaced alongside the four dimensions`() {
        val line = ConfigListStatusLine.derive(
            confidence(),
            coverage = SearchCoverage.partial(3, 10, "local details only"),
            message = message
        )

        assertEquals(
            "Remote · Confirmed · Fresh · Complete · Coverage 3/10 (partial)",
            line
        )
    }

    @Test
    fun `complete content-search coverage is not appended`() {
        val line = ConfigListStatusLine.derive(
            confidence(),
            coverage = SearchCoverage.complete(10, 10),
            message = message
        )

        assertEquals("Remote · Confirmed · Fresh · Complete", line)
        assertFalse(line.contains("Coverage"))
    }

    private fun confidence(
        source: DataSource = DataSource.REMOTE,
        confirmation: DatasetConfirmation = DatasetConfirmation.CONFIRMED,
        age: CacheAge = CacheAge.WITHIN_TTL,
        completeness: DatasetCompleteness = DatasetCompleteness.COMPLETE
    ) = CacheConfidence(
        source = source,
        confirmation = confirmation,
        age = age,
        completeness = completeness,
        fetchedAtMillis = 1L
    )
}
