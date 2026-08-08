package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.operations.PublishDiff
import com.nanyin.nacos.search.services.operations.PublishNamedTarget
import com.nanyin.nacos.search.services.operations.WriteIntent

/**
 * Closed set of user-visible states for the configuration detail view
 * (issue #81 / parent #44).
 *
 * The panel renders with one exhaustive branch and holds no decision — adding a
 * state later forces every renderer to handle it. Mapping from load, edit-start,
 * and publish outcomes lives in [DetailPresentation] / [DetailController], not
 * in Swing.
 *
 * User-visible wording is **not** stored as resolved strings. Overlay and
 * failure titles are re-derived from structured inputs on every
 * [ConfigDetailPanel.render] (including language change) so source /
 * confirmation / age stay independent and localisable (ADR-0036).
 *
 * The thirteen members:
 * - [Empty]: nothing selected.
 * - [Loading]: a detail read is in flight (and no cached body is kept visible).
 * - [Body]: configuration content with its cache-age dimensions (and optional overlay).
 * - [WriteIntentDisabled]: edit refused because publishing is not opted in.
 * - [ConfigurationRequired]: local settings cannot safely construct an operation.
 * - [AwaitingConfirmation]: publish preflight parked; diff and named target ready.
 * - [Publishing]: the single write is in flight.
 * - [Verifying]: read-back verification is in flight.
 * - [Verified]: publish verified; dirty cleared.
 * - [Conflict]: remote content differs from the draft.
 * - [ServerStateUnknown]: write outcome could not be determined.
 * - [Failed]: a load or publish failure that is not one of the above.
 * - [Stale]: an asynchronous result the presentation gate discarded.
 */
sealed class DetailViewState {
    object Empty : DetailViewState()

    object Loading : DetailViewState()

    /**
     * @param confidence the three cache-quality dimensions for what is shown
     * (ADR-0036); null when the body came from a fresh remote read that has not
     * yet been framed as cache-backed.
     * @param overlay side status while a cached body stays visible (deep-stale
     * refresh, refresh failure, authoritative deletion).
     * @param dirty whether the project's draft differs from its baseline.
     * @param editing whether the editor is writable under an open session.
     */
    data class Body(
        val configuration: NacosConfiguration,
        val confidence: CacheConfidence? = null,
        val overlay: DetailOverlay = DetailOverlay.None,
        val dirty: Boolean = false,
        val editing: Boolean = false,
        /**
         * Observation sequence of the read that produced this body, so an EDT
         * re-paint raises the same mark the controller admitted (ADR-0047).
         */
        val observation: Long = com.nanyin.nacos.search.services.operations.Observed.NO_OBSERVATION
    ) : DetailViewState()

    data class WriteIntentDisabled(
        val cause: WriteIntent.Cause
    ) : DetailViewState()

    data class ConfigurationRequired(
        val detail: String? = null
    ) : DetailViewState()

    data class AwaitingConfirmation(
        val diff: PublishDiff,
        val namedTarget: PublishNamedTarget
    ) : DetailViewState()

    object Publishing : DetailViewState()

    object Verifying : DetailViewState()

    data class Verified(
        val configuration: NacosConfiguration?
    ) : DetailViewState()

    data class Conflict(
        val remoteContent: String,
        val draftContent: String
    ) : DetailViewState()

    object ServerStateUnknown : DetailViewState()

    /**
     * @param titleKey optional bundle key for a failure title.
     * @param detailKey optional bundle key for the detail body (publish outcomes).
     * @param detailParams parameters for [detailKey].
     * @param detail optional free-form detail when no [detailKey] applies.
     * @param asErrorCard when true, swap to the error card (load failures);
     * publish outcomes leave the editor visible and only show a dialog.
     */
    data class Failed(
        val titleKey: String? = null,
        val detailKey: String? = null,
        val detailParams: List<Any> = emptyList(),
        val detail: String? = null,
        val asErrorCard: Boolean = true
    ) : DetailViewState()

    /** Dropped because the presentation judgement no longer admits this result. */
    object Stale : DetailViewState()
}

/**
 * Side status drawn over a [DetailViewState.Body] that stays visible while a
 * deep-stale refresh runs, fails, or proves the configuration gone.
 */
enum class DetailOverlay {
    None,
    Refreshing,
    RefreshFailed,
    Deleted
}
