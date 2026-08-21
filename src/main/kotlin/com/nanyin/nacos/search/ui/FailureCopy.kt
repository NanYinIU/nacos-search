package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.settings.ConfigurationRequired

/**
 * Shared typed-failure unwrap and title/detail assembly for the detail and
 * list views (issue #234). Classification into each view's closed state set
 * stays in [DetailPresentation] / [ConfigListPresentation].
 *
 * Pure: no Swing, no bundle resolution.
 */
object FailureCopy {

    const val UNWRAP_DEPTH = 4

    /**
     * Walk a short cause chain so a wrapper message does not hide a typed
     * [ConfigurationRequired] (需要配置) or [RemoteOperationError] (请求失败).
     * Stops at those types so [RemoteOperationError.Connection] is not reduced
     * to its root [RuntimeException] for classification or wording.
     */
    fun unwrap(error: Throwable?): Throwable? {
        var current = error
        var depth = 0
        while (current?.cause != null &&
            current !is ConfigurationRequired &&
            current !is RemoteOperationError &&
            depth < UNWRAP_DEPTH
        ) {
            current = current.cause
            depth++
        }
        return current
    }

    /**
     * Join a localised title and a detail without repeating the title or its
     * English / Chinese colon form. Empty inputs fall through to [emptyFallback]
     * only when both sides are blank.
     */
    fun combine(title: String, detail: String, emptyFallback: String): String {
        val trimmedTitle = title.trim()
        val trimmedDetail = detail.trim()
        return when {
            trimmedTitle.isNotEmpty() && trimmedDetail.isNotEmpty() &&
                (trimmedDetail == trimmedTitle ||
                    trimmedDetail.startsWith("$trimmedTitle:") ||
                    trimmedDetail.startsWith("$trimmedTitle：")) ->
                trimmedDetail
            trimmedTitle.isNotEmpty() && trimmedDetail.isNotEmpty() ->
                "$trimmedTitle: $trimmedDetail"
            trimmedTitle.isNotEmpty() -> trimmedTitle
            trimmedDetail.isNotEmpty() -> trimmedDetail
            else -> emptyFallback
        }
    }
}
