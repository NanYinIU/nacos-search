package com.nanyin.nacos.search.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthStrategyFormPolicyTest {

    @Test
    fun `chooser offers only the P0 authentication strategies`() {
        assertEquals(
            listOf(
                AuthMode.ANONYMOUS,
                AuthMode.NACOS_PASSWORD,
                AuthMode.HTTP_BASIC,
                AuthMode.BEARER_TOKEN
            ),
            AuthStrategyFormPolicy.chooserModes()
        )
    }

    @Test
    fun `normalizeStored maps legacy aliases to P0 strategies`() {
        assertEquals(AuthMode.NACOS_PASSWORD, AuthStrategyFormPolicy.normalizeStored(AuthMode.TOKEN))
        assertEquals(AuthMode.HTTP_BASIC, AuthStrategyFormPolicy.normalizeStored(AuthMode.BASIC))
        assertEquals(AuthMode.NACOS_PASSWORD, AuthStrategyFormPolicy.normalizeStored(AuthMode.HYBRID))
        assertEquals(AuthMode.ANONYMOUS, AuthStrategyFormPolicy.normalizeStored(AuthMode.ANONYMOUS))
        assertEquals(AuthMode.BEARER_TOKEN, AuthStrategyFormPolicy.normalizeStored(AuthMode.BEARER_TOKEN))
    }

    @Test
    fun `credentials upgrade anonymous to nacos password only when both are complete`() {
        assertEquals(
            AuthMode.NACOS_PASSWORD,
            AuthStrategyFormPolicy.onCredentialsEdited(AuthMode.ANONYMOUS, "alice", "secret")
        )
        assertEquals(
            AuthMode.ANONYMOUS,
            AuthStrategyFormPolicy.onCredentialsEdited(AuthMode.ANONYMOUS, "alice", "")
        )
        assertEquals(
            AuthMode.ANONYMOUS,
            AuthStrategyFormPolicy.onCredentialsEdited(AuthMode.ANONYMOUS, "", "secret")
        )
        assertEquals(
            AuthMode.ANONYMOUS,
            AuthStrategyFormPolicy.onCredentialsEdited(AuthMode.ANONYMOUS, "", "")
        )
    }

    @Test
    fun `credentials never downgrade nacos password or rewrite locked strategies`() {
        assertEquals(
            AuthMode.NACOS_PASSWORD,
            AuthStrategyFormPolicy.onCredentialsEdited(AuthMode.NACOS_PASSWORD, "", "")
        )
        assertEquals(
            AuthMode.HTTP_BASIC,
            AuthStrategyFormPolicy.onCredentialsEdited(AuthMode.HTTP_BASIC, "proxy", "secret")
        )
        assertEquals(
            AuthMode.BEARER_TOKEN,
            AuthStrategyFormPolicy.onCredentialsEdited(AuthMode.BEARER_TOKEN, "", "token")
        )
    }

    @Test
    fun `choosing anonymous disables credentials and requests clear`() {
        val effects = AuthStrategyFormPolicy.onStrategyChosen(AuthMode.ANONYMOUS)
        assertEquals(true, effects.credentialsEnabled.not())
        assertEquals(true, effects.clearCredentials)
        assertEquals(AuthStrategyFormPolicy.SecretFieldKind.PASSWORD, effects.secretFieldKind)
    }

    @Test
    fun `choosing bearer enables credentials and uses token secret label`() {
        val effects = AuthStrategyFormPolicy.onStrategyChosen(AuthMode.BEARER_TOKEN)
        assertEquals(true, effects.credentialsEnabled)
        assertEquals(false, effects.clearCredentials)
        assertEquals(AuthStrategyFormPolicy.SecretFieldKind.TOKEN, effects.secretFieldKind)
    }

    @Test
    fun `choosing nacos password or http basic uses password secret label`() {
        for (mode in listOf(AuthMode.NACOS_PASSWORD, AuthMode.HTTP_BASIC)) {
            val effects = AuthStrategyFormPolicy.onStrategyChosen(mode)
            assertEquals(true, effects.credentialsEnabled)
            assertEquals(false, effects.clearCredentials)
            assertEquals(AuthStrategyFormPolicy.SecretFieldKind.PASSWORD, effects.secretFieldKind)
        }
    }

    @Test
    fun `label and tooltip message keys cover every chooser mode`() {
        for (mode in AuthStrategyFormPolicy.chooserModes()) {
            assertEquals("settings.auth.strategy.${mode.name.lowercase()}.label", AuthStrategyFormPolicy.labelKey(mode))
            assertEquals("settings.auth.strategy.${mode.name.lowercase()}.tooltip", AuthStrategyFormPolicy.tooltipKey(mode))
        }
    }
}
