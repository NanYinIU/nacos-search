package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.AuthenticationToken
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EphemeralV1AuthenticatorTest {

    private var now = 0L

    @Test
    fun `one login serves every request made through the same instance`() = runBlocking {
        var logins = 0
        val authenticator = authenticator { logins++; token("t1", expiresAt = 60_000) }

        assertEquals("t1", authenticator.accessToken(context()))
        assertEquals("t1", authenticator.accessToken(context()))

        assertEquals(1, logins)
    }

    @Test
    fun `invalidate forces the next request to log in again`() = runBlocking {
        var logins = 0
        val authenticator = authenticator { token("t${++logins}", expiresAt = 60_000) }

        assertEquals("t1", authenticator.accessToken(context()))
        authenticator.invalidate(context())

        assertEquals("t2", authenticator.accessToken(context()))
        assertEquals(2, logins)
    }

    /**
     * The token lives in the instance and nowhere else, so a second diagnostic
     * run cannot inherit a token acquired for a draft the user has since edited.
     */
    @Test
    fun `a fresh instance never inherits the previous instance's token`() = runBlocking {
        var logins = 0
        val login: suspend (NacosOperationContext) -> AuthenticationToken? =
            { token("t${++logins}", expiresAt = 60_000) }

        assertEquals("t1", authenticator(login).accessToken(context()))
        assertEquals("t2", authenticator(login).accessToken(context()))
    }

    @Test
    fun `an expired token is replaced rather than presented again`() = runBlocking {
        var logins = 0
        val authenticator = authenticator { token("t${++logins}", expiresAt = now + 1_000) }

        assertEquals("t1", authenticator.accessToken(context()))
        now += 5_000

        assertEquals("t2", authenticator.accessToken(context()))
    }

    @Test
    fun `a failed login yields no token and is retried on the next request`() = runBlocking {
        var logins = 0
        val authenticator = authenticator { if (++logins == 1) null else token("t2", expiresAt = 60_000) }

        assertNull(authenticator.accessToken(context()))
        assertEquals("t2", authenticator.accessToken(context()))
    }

    /** A login that returns an already-expired token must not be held. */
    @Test
    fun `an already-expired login result is not retained`() = runBlocking {
        var logins = 0
        val authenticator = authenticator { logins++; token("stale", expiresAt = now) }

        assertNull(authenticator.accessToken(context()))
        assertNull(authenticator.accessToken(context()))

        assertEquals(2, logins)
    }

    private fun authenticator(
        login: suspend (NacosOperationContext) -> AuthenticationToken?
    ) = EphemeralV1Authenticator(login = login, clock = { now })

    private fun token(value: String, expiresAt: Long) = AuthenticationToken(value, expiresAt)

    private fun context(): NacosOperationContext {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        return NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "diagnostic",
                accessRevision = 0,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.NACOS_PASSWORD,
                principal = "admin"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot("secret"),
            authMode = AuthMode.NACOS_PASSWORD,
            profileRevision = 0,
            accessRevision = 0,
            resolvedGeneration = NacosApiGeneration.V1
        )
    }
}
