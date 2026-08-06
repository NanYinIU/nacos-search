package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.settings.ConfigurationRequired
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Exhaustive matrix for issue #122 / ADR-0019: every sealed remote and request
 * failure must declare allow vs refuse. Adding a [RemoteOperationError] or
 * [NacosRequestError] subtype without updating the policy is a compile error
 * in production code; this table is the behavioural lock so a silent default
 * cannot reappear in tests either.
 */
class StaleSearchFallbackPolicyTest {

    @ParameterizedTest(name = "allows stale: {0}")
    @MethodSource("eligibleFailures")
    fun `eligible transient failures allow stale cache`(error: Throwable) {
        assertTrue(StaleSearchFallbackPolicy.allowsStaleCache(error), "expected allow for $error")
    }

    @ParameterizedTest(name = "refuses stale: {0}")
    @MethodSource("terminalFailures")
    fun `terminal access and setup failures refuse stale cache`(error: Throwable) {
        assertFalse(StaleSearchFallbackPolicy.allowsStaleCache(error), "expected refuse for $error")
    }

    @Test
    fun `wrapper exception still classifies the typed cause`() {
        val wrapped = Exception(
            "search failed",
            RemoteOperationError.Authentication(401)
        )
        assertFalse(StaleSearchFallbackPolicy.allowsStaleCache(wrapped))

        val wrappedConnection = Exception(
            "search failed",
            RemoteOperationError.Connection(RuntimeException("dns"))
        )
        assertTrue(StaleSearchFallbackPolicy.allowsStaleCache(wrappedConnection))
    }

    @Test
    fun `connection is not reduced to its root authentication cause`() {
        // Classification stops at RemoteOperationError so a Connection that
        // happens to wrap something else stays eligible for stale fallback.
        val connection = RemoteOperationError.Connection(
            RemoteOperationError.Authentication(401)
        )
        assertTrue(StaleSearchFallbackPolicy.allowsStaleCache(connection))
    }

    @Test
    fun `unknown throwables fail closed`() {
        assertFalse(StaleSearchFallbackPolicy.allowsStaleCache(RuntimeException("mystery")))
    }

    @Test
    fun `remote matrix covers every sealed RemoteOperationError subtype`() {
        val covered = (eligibleRemote() + terminalRemote())
            .map { it::class.simpleName!! }
            .toSet()
        val all = RemoteOperationError::class.sealedSubclasses
            .map { it.simpleName!! }
            .toSet()
        assertEquals(
            all.sorted(),
            covered.sorted(),
            "StaleSearchFallbackPolicyTest must list every RemoteOperationError subtype"
        )
    }

    @Test
    fun `request matrix covers every sealed NacosRequestError subtype`() {
        val covered = (eligibleRequest() + terminalRequest())
            .map { it::class.simpleName!! }
            .toSet()
        val all = NacosRequestError::class.sealedSubclasses
            .map { it.simpleName!! }
            .toSet()
        assertEquals(
            all.sorted(),
            covered.sorted(),
            "StaleSearchFallbackPolicyTest must list every NacosRequestError subtype"
        )
    }

    companion object {
        @JvmStatic
        fun eligibleFailures(): Stream<Throwable> =
            Stream.concat(eligibleRemote().stream(), eligibleRequest().stream())

        @JvmStatic
        fun terminalFailures(): Stream<Throwable> {
            val extras = listOf(
                ConfigurationRequired(listOf("endpoint required")),
                CancellationException("cancelled"),
                RuntimeException("unknown")
            )
            return Stream.of(
                terminalRemote().stream(),
                terminalRequest().stream(),
                extras.stream()
            ).flatMap { it }
        }

        private fun eligibleRemote(): List<RemoteOperationError> = listOf(
            RemoteOperationError.Connection(RuntimeException("dns")),
            RemoteOperationError.RateLimited(),
            RemoteOperationError.Server(500),
            RemoteOperationError.Protocol("malformed body")
        )

        private fun terminalRemote(): List<RemoteOperationError> = listOf(
            RemoteOperationError.Authentication(401),
            RemoteOperationError.Authorization(403),
            RemoteOperationError.InvalidOrExpiredNacosPasswordToken(403),
            RemoteOperationError.NotFound(),
            RemoteOperationError.Client(400),
            RemoteOperationError.Unsupported("unsupported"),
            RemoteOperationError.GenerationUnsupported("no generation"),
            RemoteOperationError.CapabilityUnsupported("no capability"),
            RemoteOperationError.Cancelled("cancelled"),
            RemoteOperationError.Redirected("https://other.example"),
            RemoteOperationError.WriteConflict(),
            RemoteOperationError.AmbiguousWriteResult()
        )

        private fun eligibleRequest(): List<NacosRequestError> = listOf(
            NacosRequestError.ConnectTimeout(RuntimeException("connect")),
            NacosRequestError.ReadTimeout(RuntimeException("read")),
            NacosRequestError.Connection(RuntimeException("dns")),
            NacosRequestError.RateLimited(retryAfterMs = 1000L),
            NacosRequestError.Server(503, "unavailable"),
            NacosRequestError.Protocol("bad json")
        )

        private fun terminalRequest(): List<NacosRequestError> = listOf(
            NacosRequestError.Authentication(401, "unauthorized"),
            NacosRequestError.Client(400, "bad request")
        )
    }
}
