package com.nanyin.nacos.search.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CacheConfidenceTest {

    @Test
    fun `remote confirmed data is REMOTE CONFIRMED WITHIN_TTL`() {
        val c = CacheConfidence.remoteConfirmed(1000L, DatasetCompleteness.COMPLETE)
        assertEquals(DataSource.REMOTE, c.source)
        assertEquals(DatasetConfirmation.CONFIRMED, c.confirmation)
        assertEquals(CacheAge.WITHIN_TTL, c.age)
    }

    @Test
    fun `restored cache is CACHE UNCONFIRMED and preserves age`() {
        val c = CacheConfidence.restoredUnconfirmed(DatasetCompleteness.PARTIAL, CacheAge.STALE, 500L)
        assertEquals(DataSource.CACHE, c.source)
        assertEquals(DatasetConfirmation.UNCONFIRMED, c.confirmation)
        assertEquals(CacheAge.STALE, c.age)
    }

    @Test
    fun `refresh failure preserves age without clearing it`() {
        val c = CacheConfidence.refreshFailed(DatasetCompleteness.COMPLETE, CacheAge.WITHIN_TTL, 500L)
        assertEquals(DataSource.CACHE, c.source)
        assertEquals(DatasetConfirmation.REFRESH_FAILED, c.confirmation)
        assertEquals(CacheAge.WITHIN_TTL, c.age)
    }

    @Test
    fun `three dimensions are orthogonal in restart plus refresh failure scenario`() {
        // Restart: within TTL, unconfirmed
        val restart = CacheConfidence.restoredUnconfirmed(DatasetCompleteness.COMPLETE, CacheAge.WITHIN_TTL, 100L)
        // After a failed refresh: still within TTL, but now refresh-failed
        val afterFailure = CacheConfidence.refreshFailed(DatasetCompleteness.COMPLETE, CacheAge.WITHIN_TTL, 100L)

        // Time passage does not clear the failure evidence
        assertEquals(DatasetConfirmation.REFRESH_FAILED, afterFailure.confirmation)
        assertEquals(CacheAge.WITHIN_TTL, afterFailure.age)

        // But the dimensions can differ independently
        assertEquals(DatasetConfirmation.UNCONFIRMED, restart.confirmation)
        assertEquals(DatasetConfirmation.REFRESH_FAILED, afterFailure.confirmation)
    }

    @Test
    fun `deep stale detail is navigable but forces refresh`() {
        val deepStale = CacheConfidence.restoredUnconfirmed(
            DatasetCompleteness.COMPLETE,
            CacheAge.DEEP_STALE,
            1L
        )
        assertEquals(CacheAge.DEEP_STALE, deepStale.age)
        // Deep stale is navigable (not failed) but unconfirmed
        assertEquals(DatasetConfirmation.UNCONFIRMED, deepStale.confirmation)
    }

    @Test
    fun `cache age calculator distinguishes within TTL stale and deep stale`() {
        val now = 10_000_000L
        val ttl = 5 * 60 * 1000L // 5 minutes
        val oneDay = 24 * 60 * 60 * 1000L
        val eightDays = 8L * 24 * 60 * 60 * 1000L

        assertEquals(CacheAge.WITHIN_TTL, CacheAgeCalculator.compute(now - ttl, now, ttl))
        assertEquals(CacheAge.STALE, CacheAgeCalculator.compute(now - ttl - 1, now, ttl))
        assertEquals(CacheAge.STALE, CacheAgeCalculator.compute(now - oneDay, now, ttl))
        assertEquals(CacheAge.DEEP_STALE, CacheAgeCalculator.compute(now - eightDays, now, ttl))
    }

    @Test
    fun `null fetchedAt is treated as within TTL`() {
        assertEquals(CacheAge.WITHIN_TTL, CacheAgeCalculator.compute(null, 1000L, 500L))
    }
}
