package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.settings.ConfigurationRequired
import kotlinx.coroutines.CancellationException

/**
 * Exhaustive policy for whether a failed remote **search** may fall back to
 * same-identity cached data with [com.nanyin.nacos.search.models.DatasetConfirmation.REFRESH_FAILED]
 * (ADR-0019 / issue #122).
 *
 * Shared by list-page search and full-namespace local-index search so the two
 * paths cannot drift. The [RemoteOperationError] and [NacosRequestError]
 * branches are sealed `when`s without `else`: adding a subtype is a compile
 * error until this policy is updated.
 *
 * This is a search-path stopgap only — it does not persist access blocks or
 * gate direct detail / snapshot / PSI reads (parent #48).
 */
object StaleSearchFallbackPolicy {

    /**
     * @return true when the search may surface retained cache as refresh-failed;
     * false when the failure must surface as its structured non-cache outcome
     * (blocked, configuration-required, unsupported, cancelled, …).
     */
    fun allowsStaleCache(error: Throwable): Boolean {
        val root = unwrap(error)
        return when (root) {
            is RemoteOperationError -> allowsForRemote(root)
            is NacosRequestError -> allowsForRequest(root)
            is ConfigurationRequired -> false
            is CancellationException -> false
            // Unknown throwables fail closed: a silent default would re-open
            // refused-access leakage the next time a new type appears.
            else -> false
        }
    }

    private fun allowsForRemote(error: RemoteOperationError): Boolean = when (error) {
        // Eligible transient / protocol trouble → cache + REFRESH_FAILED.
        is RemoteOperationError.Connection,
        is RemoteOperationError.RateLimited,
        is RemoteOperationError.Server,
        is RemoteOperationError.Protocol -> true

        // Terminal access / setup / cancel / write-path noise → no cache.
        is RemoteOperationError.Authentication,
        is RemoteOperationError.Authorization,
        is RemoteOperationError.InvalidOrExpiredNacosPasswordToken,
        is RemoteOperationError.NotFound,
        is RemoteOperationError.Client,
        is RemoteOperationError.Unsupported,
        is RemoteOperationError.GenerationUnsupported,
        is RemoteOperationError.CapabilityUnsupported,
        is RemoteOperationError.Cancelled,
        is RemoteOperationError.Redirected,
        is RemoteOperationError.WriteConflict,
        is RemoteOperationError.AmbiguousWriteResult -> false
    }

    private fun allowsForRequest(error: NacosRequestError): Boolean = when (error) {
        is NacosRequestError.ConnectTimeout,
        is NacosRequestError.ReadTimeout,
        is NacosRequestError.Connection,
        is NacosRequestError.RateLimited,
        is NacosRequestError.Server,
        is NacosRequestError.Protocol -> true

        is NacosRequestError.Authentication,
        is NacosRequestError.Client -> false
    }

    /**
     * Walk a short cause chain so a search-wrapper message does not hide a
     * typed remote / request / configuration failure. Stops at those types so
     * [RemoteOperationError.Connection] is not reduced to its root cause for
     * classification (connection remains eligible for stale fallback).
     */
    private fun unwrap(error: Throwable): Throwable {
        var current: Throwable = error
        var depth = 0
        while (current.cause != null &&
            current !is RemoteOperationError &&
            current !is NacosRequestError &&
            current !is ConfigurationRequired &&
            current !is CancellationException &&
            depth < 4
        ) {
            current = current.cause!!
            depth++
        }
        return current
    }
}
