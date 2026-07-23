package com.nanyin.nacos.search.settings

/**
 * Settings-form policy for authentication strategy progressive disclosure.
 * Inference here is apply-path UX only — never a runtime cross-strategy fallback.
 */
object AuthStrategyFormPolicy {

    fun chooserModes(): List<AuthMode> = listOf(
        AuthMode.ANONYMOUS,
        AuthMode.NACOS_PASSWORD,
        AuthMode.HTTP_BASIC,
        AuthMode.BEARER_TOKEN
    )

    fun normalizeStored(mode: AuthMode): AuthMode = when (mode) {
        AuthMode.TOKEN, AuthMode.HYBRID, AuthMode.NACOS_PASSWORD -> AuthMode.NACOS_PASSWORD
        AuthMode.BASIC, AuthMode.HTTP_BASIC -> AuthMode.HTTP_BASIC
        AuthMode.ANONYMOUS -> AuthMode.ANONYMOUS
        AuthMode.BEARER_TOKEN -> AuthMode.BEARER_TOKEN
    }

    /**
     * Apply-path inference for primary-form credential edits.
     * Only auto-upgrade: ANONYMOUS + complete credentials → NACOS_PASSWORD.
     */
    fun onCredentialsEdited(current: AuthMode, username: String, password: String): AuthMode {
        val strategy = normalizeStored(current)
        if (strategy != AuthMode.ANONYMOUS) return strategy
        val principal = username.trim()
        val secret = password
        return if (principal.isNotEmpty() && secret.isNotEmpty()) {
            AuthMode.NACOS_PASSWORD
        } else {
            AuthMode.ANONYMOUS
        }
    }

    fun onStrategyChosen(mode: AuthMode): FormEffects {
        val strategy = normalizeStored(mode)
        return when (strategy) {
            AuthMode.ANONYMOUS -> FormEffects(
                credentialsEnabled = false,
                clearCredentials = true,
                secretFieldKind = SecretFieldKind.PASSWORD
            )
            AuthMode.BEARER_TOKEN -> FormEffects(
                credentialsEnabled = true,
                clearCredentials = false,
                secretFieldKind = SecretFieldKind.TOKEN
            )
            else -> FormEffects(
                credentialsEnabled = true,
                clearCredentials = false,
                secretFieldKind = SecretFieldKind.PASSWORD
            )
        }
    }

    fun labelKey(mode: AuthMode): String =
        "settings.auth.strategy.${normalizeStored(mode).name.lowercase()}.label"

    fun tooltipKey(mode: AuthMode): String =
        "settings.auth.strategy.${normalizeStored(mode).name.lowercase()}.tooltip"

    enum class SecretFieldKind {
        PASSWORD,
        TOKEN
    }

    data class FormEffects(
        val credentialsEnabled: Boolean,
        val clearCredentials: Boolean,
        val secretFieldKind: SecretFieldKind
    )
}
