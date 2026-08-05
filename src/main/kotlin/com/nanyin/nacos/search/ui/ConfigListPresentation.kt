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
 * Pure: no Swing, no services. The [message] resolver supplies localised labels
 * so tests drive every state without a platform fixture.
 */
object ConfigListPresentation {

    fun fromSearchState(
        state: NacosSearchService.SearchState,
        message: (key: String, params: Array<out Any>) -> String
    ): ConfigListViewState = when (state) {
        is NacosSearchService.SearchState.Idle ->
            ConfigListViewState.Empty()

        is NacosSearchService.SearchState.Loading ->
            ConfigListViewState.Loading

        is NacosSearchService.SearchState.Success -> {
            val statusLine = ConfigListStatusLine.derive(
                confidence = state.confidence,
                coverage = state.coverage,
                message = message
            )
            if (state.configurations.isEmpty()) {
                ConfigListViewState.Empty(statusLine = statusLine)
            } else {
                ConfigListViewState.Results(
                    configurations = state.configurations,
                    statusLine = statusLine
                )
            }
        }

        is NacosSearchService.SearchState.Error ->
            fromFailure(state.throwable, state.message, message)
    }

    /**
     * Classifies a typed failure into [ConfigListViewState.Blocked] or
     * [ConfigListViewState.Failed]. Blocked covers refused access and
     * configuration-required so neither reads as an empty namespace.
     */
    fun fromFailure(
        error: Throwable?,
        fallbackMessage: String,
        message: (key: String, params: Array<out Any>) -> String
    ): ConfigListViewState {
        val root = unwrap(error)
        return when {
            root is ConfigurationRequired -> ConfigListViewState.Blocked(
                reason = BlockedReason.CONFIGURATION_REQUIRED,
                message = blockedMessage(
                    BlockedReason.CONFIGURATION_REQUIRED,
                    root.reasons.firstOrNull() ?: fallbackMessage,
                    message
                )
            )
            isRefusedAccess(root) -> ConfigListViewState.Blocked(
                reason = BlockedReason.REFUSED_ACCESS,
                message = blockedMessage(
                    BlockedReason.REFUSED_ACCESS,
                    root?.message ?: fallbackMessage,
                    message
                )
            )
            else -> ConfigListViewState.Failed(
                message = failedMessage(fallbackMessage, message)
            )
        }
    }

    fun loading(): ConfigListViewState = ConfigListViewState.Loading

    fun empty(statusLine: String = ""): ConfigListViewState =
        ConfigListViewState.Empty(statusLine = statusLine)

    private fun blockedMessage(
        reason: BlockedReason,
        detail: String,
        message: (key: String, params: Array<out Any>) -> String
    ): String {
        val titleKey = when (reason) {
            BlockedReason.REFUSED_ACCESS -> "config.list.blocked.access"
            BlockedReason.CONFIGURATION_REQUIRED -> "config.list.blocked.configuration"
        }
        val title = message(titleKey, emptyArray())
        val trimmed = detail.trim()
        return if (trimmed.isEmpty() || trimmed == title) title else "$title: $trimmed"
    }

    private fun failedMessage(
        fallbackMessage: String,
        message: (key: String, params: Array<out Any>) -> String
    ): String {
        val title = message("config.list.failed", emptyArray())
        val trimmed = fallbackMessage.trim()
        return when {
            trimmed.isEmpty() -> title
            trimmed.startsWith(title) -> trimmed
            else -> "$title: $trimmed"
        }
    }

    private fun isRefusedAccess(error: Throwable?): Boolean = when (error) {
        is RemoteOperationError.Authentication,
        is RemoteOperationError.Authorization -> true
        else -> false
    }

    private fun unwrap(error: Throwable?): Throwable? {
        var current = error
        // Walk a short cause chain so a search-wrapper message does not hide a
        // typed ConfigurationRequired or auth failure underneath.
        var depth = 0
        while (current?.cause != null &&
            current !is ConfigurationRequired &&
            !isRefusedAccess(current) &&
            depth < 4
        ) {
            current = current.cause
            depth++
        }
        return current
    }
}
