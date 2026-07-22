package com.nanyin.nacos.search.models

/**
 * Three independent quality dimensions for cached or remotely fetched data.
 *
 * These are deliberately orthogonal: time passage does not clear failure
 * evidence, a refresh failure does not modify age, and restored cache is
 * unconfirmed regardless of TTL.
 *
 * - [source]: REMOTE or CACHE
 * - [confirmation]: CONFIRMED, UNCONFIRMED, or REFRESH_FAILED
 * - [age]: WITHIN_TTL, STALE, or DEEP_STALE
 */
enum class DatasetConfirmation { CONFIRMED, UNCONFIRMED, REFRESH_FAILED }

enum class CacheAge { WITHIN_TTL, STALE, DEEP_STALE }

/**
 * Snapshot of a dataset's three independent confidence dimensions plus
 * the legacy completeness axis.
 */
data class CacheConfidence(
    val source: DataSource,
    val confirmation: DatasetConfirmation,
    val age: CacheAge,
    val completeness: DatasetCompleteness,
    val fetchedAtMillis: Long? = null
) {
    companion object {
        /** Data fetched from the remote server in this session. */
        fun remoteConfirmed(now: Long, completeness: DatasetCompleteness) = CacheConfidence(
            source = DataSource.REMOTE,
            confirmation = DatasetConfirmation.CONFIRMED,
            age = CacheAge.WITHIN_TTL,
            completeness = completeness,
            fetchedAtMillis = now
        )

        /** Restored from persistent cache on restart; needs confirmation. */
        fun restoredUnconfirmed(completeness: DatasetCompleteness, age: CacheAge, fetchedAt: Long?) = CacheConfidence(
            source = DataSource.CACHE,
            confirmation = DatasetConfirmation.UNCONFIRMED,
            age = age,
            completeness = completeness,
            fetchedAtMillis = fetchedAt
        )

        /** A refresh attempt failed but the old data is still visible. */
        fun refreshFailed(completeness: DatasetCompleteness, age: CacheAge, fetchedAt: Long?) = CacheConfidence(
            source = DataSource.CACHE,
            confirmation = DatasetConfirmation.REFRESH_FAILED,
            age = age,
            completeness = completeness,
            fetchedAtMillis = fetchedAt
        )
    }
}

/**
 * Computes [CacheAge] from timestamps relative to TTL and the deep-stale
 * threshold (seven days).
 */
object CacheAgeCalculator {
    private const val DEEP_STALE_THRESHOLD_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun compute(
        fetchedAtMillis: Long?,
        nowMillis: Long,
        ttlMillis: Long
    ): CacheAge {
        if (fetchedAtMillis == null) return CacheAge.WITHIN_TTL
        val ageMillis = nowMillis - fetchedAtMillis
        return when {
            ageMillis <= ttlMillis -> CacheAge.WITHIN_TTL
            ageMillis <= DEEP_STALE_THRESHOLD_MILLIS -> CacheAge.STALE
            else -> CacheAge.DEEP_STALE
        }
    }
}
