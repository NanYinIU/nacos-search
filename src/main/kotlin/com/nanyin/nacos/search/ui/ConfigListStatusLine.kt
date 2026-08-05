package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.CacheAge
import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.DataSource
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetConfirmation
import com.nanyin.nacos.search.services.operations.SearchCoverage

/**
 * Derives the configuration-list status-line wording where the four independent
 * dimensions are known (ADR-0036 / issue #79).
 *
 * Source, confirmation, age, and completeness stay independent: a failed refresh
 * changes confirmation without rewriting age; elapsed time changes age without
 * erasing failure evidence. Content-search coverage (ADR-0017) is appended when
 * present and incomplete.
 *
 * Pure: no Swing, no services. The [message] resolver supplies localised labels
 * so tests can drive the contract without a platform bundle.
 */
object ConfigListStatusLine {

    fun derive(
        confidence: CacheConfidence,
        coverage: SearchCoverage? = null,
        message: (key: String, params: Array<out Any>) -> String
    ): String {
        val sourceLabel = when (confidence.source) {
            DataSource.REMOTE -> message("config.list.status.source.remote", emptyArray())
            DataSource.CACHE -> message("config.list.status.source.cache", emptyArray())
        }
        val confirmationLabel = when (confidence.confirmation) {
            DatasetConfirmation.CONFIRMED ->
                message("config.list.status.confirmation.confirmed", emptyArray())
            DatasetConfirmation.UNCONFIRMED ->
                message("config.list.status.confirmation.unconfirmed", emptyArray())
            DatasetConfirmation.REFRESH_FAILED ->
                message("config.list.status.confirmation.refreshFailed", emptyArray())
        }
        val ageLabel = when (confidence.age) {
            CacheAge.WITHIN_TTL -> message("config.list.status.age.fresh", emptyArray())
            CacheAge.STALE -> message("config.list.status.age.stale", emptyArray())
            CacheAge.DEEP_STALE -> message("config.list.status.age.deepStale", emptyArray())
        }
        val completenessLabel = when (confidence.completeness) {
            DatasetCompleteness.COMPLETE ->
                message("config.list.status.completeness.complete", emptyArray())
            DatasetCompleteness.PARTIAL ->
                message("config.list.status.completeness.partial", emptyArray())
            DatasetCompleteness.FAILED ->
                message("config.list.status.completeness.failed", emptyArray())
        }
        val parts = mutableListOf(sourceLabel, confirmationLabel, ageLabel, completenessLabel)
        if (coverage != null && !coverage.isComplete) {
            parts += message(
                "config.list.status.coverage.partial",
                arrayOf(coverage.coverageText)
            )
        }
        return parts.joinToString(" · ")
    }
}
