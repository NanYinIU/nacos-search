package com.nanyin.nacos.search.settings

/**
 * Settings-form policy for authentication strategy progressive disclosure.
 *
 * Strategy is **chooser-only** — never inferred from credential edits, and never
 * a runtime cross-strategy fallback (ADR-0007 / ADR-0040). Credential fields are
 * shown or hidden by strategy; they are not merely disabled.
 */
object AuthStrategyFormPolicy {

    fun chooserModes(): List<AuthMode> = listOf(
        AuthMode.ANONYMOUS,
        AuthMode.NACOS_PASSWORD,
        AuthMode.HTTP_BASIC,
        AuthMode.BEARER_TOKEN
    )

    fun normalizeStored(mode: AuthMode, enableTokenAuth: Boolean = true): AuthMode = when (mode) {
        AuthMode.TOKEN, AuthMode.NACOS_PASSWORD -> AuthMode.NACOS_PASSWORD
        AuthMode.HYBRID -> if (enableTokenAuth) AuthMode.NACOS_PASSWORD else AuthMode.HTTP_BASIC
        AuthMode.BASIC, AuthMode.HTTP_BASIC -> AuthMode.HTTP_BASIC
        AuthMode.ANONYMOUS -> AuthMode.ANONYMOUS
        AuthMode.BEARER_TOKEN -> AuthMode.BEARER_TOKEN
    }

    /**
     * Which credential fields the primary form shows for [mode].
     * Hide unused fields entirely (do not leave them disabled in place).
     */
    fun fieldVisibility(mode: AuthMode): FieldVisibility {
        val strategy = normalizeStored(mode)
        return when (strategy) {
            AuthMode.ANONYMOUS -> FieldVisibility(
                usernameVisible = false,
                secretVisible = false,
                secretFieldKind = SecretFieldKind.PASSWORD
            )
            AuthMode.BEARER_TOKEN -> FieldVisibility(
                usernameVisible = false,
                secretVisible = true,
                secretFieldKind = SecretFieldKind.TOKEN
            )
            else -> FieldVisibility(
                usernameVisible = true,
                secretVisible = true,
                secretFieldKind = SecretFieldKind.PASSWORD
            )
        }
    }

    /**
     * Effects of switching strategy in the chooser.
     *
     * - → ANONYMOUS: clear username + secret immediately
     * - NACOS_PASSWORD ↔ HTTP_BASIC: preserve username + password
     * - any transition involving BEARER_TOKEN: do not migrate secrets; clear secret;
     *   clear username when landing on or leaving Bearer (username is not shown for Bearer)
     * - ANONYMOUS → password family: nothing to clear (fields already empty)
     */
    fun onStrategySwitch(from: AuthMode, to: AuthMode): SwitchEffects {
        val fromN = normalizeStored(from)
        val toN = normalizeStored(to)
        val visibility = fieldVisibility(toN)
        if (fromN == toN) {
            return SwitchEffects(clearUsername = false, clearSecret = false, visibility = visibility)
        }
        if (toN == AuthMode.ANONYMOUS) {
            return SwitchEffects(clearUsername = true, clearSecret = true, visibility = visibility)
        }
        val passwordFamily = setOf(AuthMode.NACOS_PASSWORD, AuthMode.HTTP_BASIC)
        if (fromN in passwordFamily && toN in passwordFamily) {
            return SwitchEffects(clearUsername = false, clearSecret = false, visibility = visibility)
        }
        if (fromN == AuthMode.BEARER_TOKEN || toN == AuthMode.BEARER_TOKEN) {
            return SwitchEffects(
                clearUsername = true,
                clearSecret = true,
                visibility = visibility
            )
        }
        return SwitchEffects(clearUsername = false, clearSecret = false, visibility = visibility)
    }

    /**
     * True when Test Connection may call the network for this strategy + fields.
     * ANONYMOUS is always ready; other strategies need their required secrets.
     * Exhaustive on [AuthMode] so a new enum constant is a compile error; legacy
     * aliases are collapsed by [normalizeStored] before the branch is taken.
     */
    fun credentialsReadyForConnectionTest(mode: AuthMode, username: String, secret: String): Boolean =
        when (val strategy = normalizeStored(mode)) {
            AuthMode.ANONYMOUS -> true
            AuthMode.BEARER_TOKEN -> secret.isNotEmpty()
            AuthMode.NACOS_PASSWORD, AuthMode.HTTP_BASIC ->
                username.trim().isNotEmpty() && secret.isNotEmpty()
            // normalizeStored collapses these; listed for exhaustiveness only.
            AuthMode.TOKEN, AuthMode.BASIC, AuthMode.HYBRID ->
                error("normalizeStored must collapse legacy mode, got $strategy")
        }

    /** Bundle key for the local incomplete-credential Test Connection message. */
    fun incompleteCredentialsMessageKey(mode: AuthMode): String =
        when (val strategy = normalizeStored(mode)) {
            AuthMode.BEARER_TOKEN -> "settings.test.token.incomplete"
            AuthMode.ANONYMOUS, AuthMode.NACOS_PASSWORD, AuthMode.HTTP_BASIC ->
                "settings.test.credentials.incomplete"
            AuthMode.TOKEN, AuthMode.BASIC, AuthMode.HYBRID ->
                error("normalizeStored must collapse legacy mode, got $strategy")
        }

    fun labelKey(mode: AuthMode): String =
        "settings.auth.strategy.${normalizeStored(mode).name.lowercase()}.label"

    fun tooltipKey(mode: AuthMode): String =
        "settings.auth.strategy.${normalizeStored(mode).name.lowercase()}.tooltip"

    enum class SecretFieldKind {
        PASSWORD,
        TOKEN
    }

    data class FieldVisibility(
        val usernameVisible: Boolean,
        val secretVisible: Boolean,
        val secretFieldKind: SecretFieldKind
    )

    data class SwitchEffects(
        val clearUsername: Boolean,
        val clearSecret: Boolean,
        val visibility: FieldVisibility
    )
}
