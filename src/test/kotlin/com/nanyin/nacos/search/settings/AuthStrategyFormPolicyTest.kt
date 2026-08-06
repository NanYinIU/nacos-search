package com.nanyin.nacos.search.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertEquals(AuthMode.HTTP_BASIC, AuthStrategyFormPolicy.normalizeStored(AuthMode.HYBRID, enableTokenAuth = false))
        assertEquals(AuthMode.ANONYMOUS, AuthStrategyFormPolicy.normalizeStored(AuthMode.ANONYMOUS))
        assertEquals(AuthMode.BEARER_TOKEN, AuthStrategyFormPolicy.normalizeStored(AuthMode.BEARER_TOKEN))
    }

    @Test
    fun `anonymous hides all credential fields`() {
        val v = AuthStrategyFormPolicy.fieldVisibility(AuthMode.ANONYMOUS)
        assertFalse(v.usernameVisible)
        assertFalse(v.secretVisible)
        assertEquals(AuthStrategyFormPolicy.SecretFieldKind.PASSWORD, v.secretFieldKind)
    }

    @Test
    fun `nacos password and http basic show username and password`() {
        for (mode in listOf(AuthMode.NACOS_PASSWORD, AuthMode.HTTP_BASIC, AuthMode.TOKEN, AuthMode.BASIC)) {
            val v = AuthStrategyFormPolicy.fieldVisibility(mode)
            assertTrue(v.usernameVisible, "username visible for $mode")
            assertTrue(v.secretVisible, "secret visible for $mode")
            assertEquals(AuthStrategyFormPolicy.SecretFieldKind.PASSWORD, v.secretFieldKind)
        }
    }

    @Test
    fun `bearer shows only token secret field`() {
        val v = AuthStrategyFormPolicy.fieldVisibility(AuthMode.BEARER_TOKEN)
        assertFalse(v.usernameVisible)
        assertTrue(v.secretVisible)
        assertEquals(AuthStrategyFormPolicy.SecretFieldKind.TOKEN, v.secretFieldKind)
    }

    @Test
    fun `switching to anonymous clears username and secret`() {
        for (from in listOf(AuthMode.NACOS_PASSWORD, AuthMode.HTTP_BASIC, AuthMode.BEARER_TOKEN)) {
            val effects = AuthStrategyFormPolicy.onStrategySwitch(from, AuthMode.ANONYMOUS)
            assertTrue(effects.clearUsername, "clear username from $from")
            assertTrue(effects.clearSecret, "clear secret from $from")
            assertFalse(effects.visibility.usernameVisible)
            assertFalse(effects.visibility.secretVisible)
        }
    }

    @Test
    fun `nacos password and http basic preserve credentials when switching between them`() {
        val forward = AuthStrategyFormPolicy.onStrategySwitch(AuthMode.NACOS_PASSWORD, AuthMode.HTTP_BASIC)
        assertFalse(forward.clearUsername)
        assertFalse(forward.clearSecret)
        assertTrue(forward.visibility.usernameVisible)
        assertTrue(forward.visibility.secretVisible)

        val back = AuthStrategyFormPolicy.onStrategySwitch(AuthMode.HTTP_BASIC, AuthMode.NACOS_PASSWORD)
        assertFalse(back.clearUsername)
        assertFalse(back.clearSecret)
    }

    @Test
    fun `any transition involving bearer clears secret and username`() {
        val toBearer = AuthStrategyFormPolicy.onStrategySwitch(AuthMode.NACOS_PASSWORD, AuthMode.BEARER_TOKEN)
        assertTrue(toBearer.clearUsername)
        assertTrue(toBearer.clearSecret)
        assertFalse(toBearer.visibility.usernameVisible)
        assertTrue(toBearer.visibility.secretVisible)
        assertEquals(AuthStrategyFormPolicy.SecretFieldKind.TOKEN, toBearer.visibility.secretFieldKind)

        val fromBearer = AuthStrategyFormPolicy.onStrategySwitch(AuthMode.BEARER_TOKEN, AuthMode.HTTP_BASIC)
        assertTrue(fromBearer.clearUsername)
        assertTrue(fromBearer.clearSecret)
        assertTrue(fromBearer.visibility.usernameVisible)
        assertEquals(AuthStrategyFormPolicy.SecretFieldKind.PASSWORD, fromBearer.visibility.secretFieldKind)
    }

    @Test
    fun `anonymous to password family does not clear empty fields`() {
        val effects = AuthStrategyFormPolicy.onStrategySwitch(AuthMode.ANONYMOUS, AuthMode.NACOS_PASSWORD)
        assertFalse(effects.clearUsername)
        assertFalse(effects.clearSecret)
        assertTrue(effects.visibility.usernameVisible)
        assertTrue(effects.visibility.secretVisible)
    }

    @Test
    fun `same strategy switch is a no-op for clears`() {
        val effects = AuthStrategyFormPolicy.onStrategySwitch(AuthMode.NACOS_PASSWORD, AuthMode.NACOS_PASSWORD)
        assertFalse(effects.clearUsername)
        assertFalse(effects.clearSecret)
    }

    @Test
    fun `connection test readiness matches strategy requirements`() {
        assertTrue(
            AuthStrategyFormPolicy.credentialsReadyForConnectionTest(AuthMode.ANONYMOUS, "", "")
        )
        assertFalse(
            AuthStrategyFormPolicy.credentialsReadyForConnectionTest(AuthMode.NACOS_PASSWORD, "", "")
        )
        assertFalse(
            AuthStrategyFormPolicy.credentialsReadyForConnectionTest(AuthMode.NACOS_PASSWORD, "alice", "")
        )
        assertFalse(
            AuthStrategyFormPolicy.credentialsReadyForConnectionTest(AuthMode.HTTP_BASIC, "", "secret")
        )
        assertTrue(
            AuthStrategyFormPolicy.credentialsReadyForConnectionTest(AuthMode.HTTP_BASIC, "alice", "secret")
        )
        assertFalse(
            AuthStrategyFormPolicy.credentialsReadyForConnectionTest(AuthMode.BEARER_TOKEN, "", "")
        )
        assertTrue(
            AuthStrategyFormPolicy.credentialsReadyForConnectionTest(AuthMode.BEARER_TOKEN, "", "tok")
        )
    }

    @Test
    fun `incomplete credential message keys distinguish bearer`() {
        assertEquals(
            "settings.test.credentials.incomplete",
            AuthStrategyFormPolicy.incompleteCredentialsMessageKey(AuthMode.NACOS_PASSWORD)
        )
        assertEquals(
            "settings.test.credentials.incomplete",
            AuthStrategyFormPolicy.incompleteCredentialsMessageKey(AuthMode.HTTP_BASIC)
        )
        assertEquals(
            "settings.test.token.incomplete",
            AuthStrategyFormPolicy.incompleteCredentialsMessageKey(AuthMode.BEARER_TOKEN)
        )
    }

    @Test
    fun `label and tooltip message keys cover every chooser mode`() {
        for (mode in AuthStrategyFormPolicy.chooserModes()) {
            assertEquals("settings.auth.strategy.${mode.name.lowercase()}.label", AuthStrategyFormPolicy.labelKey(mode))
            assertEquals("settings.auth.strategy.${mode.name.lowercase()}.tooltip", AuthStrategyFormPolicy.tooltipKey(mode))
        }
    }
}
