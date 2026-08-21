package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.settings.ConfigurationRequired

/**
 * Maps search and access outcomes to the list's closed [ConfigListViewState]
 * (issue #79).
 *
 * Lives outside [ConfigListPanel]: the panel renders exhaustively and makes no
 * decision. Classification is typed — never by localised exception text —
 * so a permission problem cannot read as an empty namespace.
 *
 * Pure: no Swing, no services, no bundle resolution. Resolved wording is
 * produced later by [ConfigListCopy] at render time (and on language change).
 *
 * [NacosSearchService.SearchState.Idle] is not a list-visible state of its own:
 * it maps to [ConfigListViewState.Empty] so cancel / session abandon can leave
 * the loading card only when the window also renders the presentation result.
 */
object ConfigListPresentation {

    /** Bundle key for a true search failure title — not used for load/nav errors. */
    const val SEARCH_FAILED_TITLE_KEY = "config.list.failed"

    fun fromSearchState(state: NacosSearchService.SearchState): ConfigListViewState =
        when (state) {
            // Idle is not painted as a sixth list state; it is the absence of a
            // search outcome. Callers that observe Idle should still render this
            // Empty so a cancelled Loading card does not stick.
            is NacosSearchService.SearchState.Idle ->
                empty()

            is NacosSearchService.SearchState.Loading ->
                ConfigListViewState.Loading

            is NacosSearchService.SearchState.Success -> {
                val status = ListStatus.Dataset(
                    confidence = state.confidence,
                    coverage = state.coverage
                )
                if (state.configurations.isEmpty()) {
                    ConfigListViewState.Empty(status = status)
                } else {
                    ConfigListViewState.Results(
                        configurations = state.configurations,
                        status = status
                    )
                }
            }

            is NacosSearchService.SearchState.Error ->
                fromFailure(
                    error = state.throwable,
                    fallbackMessage = state.message,
                    titleKey = SEARCH_FAILED_TITLE_KEY
                )
        }

    /**
     * Classifies a typed failure into [ConfigListViewState.Blocked] or
     * [ConfigListViewState.Failed]. Blocked covers refused access and
     * configuration-required so neither reads as an empty namespace.
     *
     * @param titleKey when non-null (search path), Failed shows that bundle
     * title plus a cleaned detail. When null (load/nav path), [fallbackMessage]
     * is the full Failed body — never stamped with “Search failed”.
     */
    fun fromFailure(
        error: Throwable?,
        fallbackMessage: String,
        titleKey: String? = null
    ): ConfigListViewState {
        val root = FailureCopy.unwrap(error)
        return when {
            root is ConfigurationRequired -> ConfigListViewState.Blocked(
                reason = BlockedReason.CONFIGURATION_REQUIRED,
                detail = root.reasons.firstOrNull()?.takeIf { it.isNotBlank() }
            )
            isRefusedAccess(root) -> ConfigListViewState.Blocked(
                reason = BlockedReason.REFUSED_ACCESS,
                detail = root?.message?.takeIf { it.isNotBlank() }
            )
            else -> ConfigListViewState.Failed(
                titleKey = titleKey,
                detail = failureDetail(root, fallbackMessage, titleKey)
            )
        }
    }

    fun loading(): ConfigListViewState = ConfigListViewState.Loading

    fun empty(status: ListStatus = ListStatus.defaultEmpty()): ConfigListViewState =
        ConfigListViewState.Empty(status = status)

    fun emptyWithBundleKey(key: String, vararg params: Any): ConfigListViewState =
        ConfigListViewState.Empty(status = ListStatus.Bundle(key, params.toList()))

    /**
     * Detail text for a Failed state.
     *
     * - When [titleKey] is null (load/nav path): the caller-composed
     *   [fallback] **is** the full body — never replace it with a bare
     *   throwable message.
     * - When [titleKey] is set (search path): prefer the unwrapped throwable
     *   message, then [fallback]. Presentation owns the localised title.
     */
    internal fun failureDetail(
        error: Throwable?,
        fallback: String,
        titleKey: String? = null
    ): String {
        if (titleKey == null) {
            return fallback.trim()
        }
        return when {
            error != null && !error.message.isNullOrBlank() -> error.message!!.trim()
            else -> fallback.trim()
        }
    }

    private fun isRefusedAccess(error: Throwable?): Boolean = when (error) {
        is RemoteOperationError.Authentication,
        is RemoteOperationError.Authorization,
        // Terminal after auth recovery failed — still refused access, not a
        // transient network failure (issue #122; pairs with stale-fallback policy).
        is RemoteOperationError.InvalidOrExpiredNacosPasswordToken -> true
        else -> false
    }
}

/**
 * Resolves [ConfigListViewState] copy for display. Pure given a message
 * resolver — panel and tests share one path (issue #79 review).
 */
object ConfigListCopy {

    fun statusLine(
        status: ListStatus,
        message: (key: String, params: Array<out Any>) -> String
    ): String = when (status) {
        is ListStatus.Blank -> ""
        is ListStatus.Bundle ->
            if (status.params.isEmpty()) message(status.key, emptyArray())
            else message(status.key, status.params.toTypedArray())
        is ListStatus.Dataset ->
            ConfigListStatusLine.derive(status.confidence, status.coverage, message)
    }

    fun blockedTitle(
        reason: BlockedReason,
        message: (key: String, params: Array<out Any>) -> String
    ): String = when (reason) {
        BlockedReason.REFUSED_ACCESS ->
            message("config.list.blocked.access", emptyArray())
        BlockedReason.CONFIGURATION_REQUIRED ->
            message("config.list.blocked.configuration", emptyArray())
    }

    fun blockedBody(
        state: ConfigListViewState.Blocked,
        message: (key: String, params: Array<out Any>) -> String
    ): String {
        val title = blockedTitle(state.reason, message)
        return FailureCopy.combine(title, state.detail.orEmpty(), title)
    }

    fun failedBody(
        state: ConfigListViewState.Failed,
        message: (key: String, params: Array<out Any>) -> String
    ): String {
        val title = state.titleKey?.let { message(it, emptyArray()) }.orEmpty()
        val detail = state.detail.orEmpty()
        return FailureCopy.combine(
            title,
            detail,
            message(ConfigListPresentation.SEARCH_FAILED_TITLE_KEY, emptyArray())
        )
    }
}
