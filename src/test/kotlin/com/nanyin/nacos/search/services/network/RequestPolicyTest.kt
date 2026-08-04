package com.nanyin.nacos.search.services.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestPolicyTest {

    @Test
    fun `interactive policy has bounded retries and duration`() {
        assertEquals(3_000, RequestPolicy.INTERACTIVE.connectTimeoutMs)
        assertEquals(8_000, RequestPolicy.INTERACTIVE.readTimeoutMs)
        assertEquals(15_000, RequestPolicy.INTERACTIVE.totalBudgetMs)
        assertEquals(2, RequestPolicy.INTERACTIVE.maxAttempts)
    }

    /**
     * The diagnostic profile differs from the interactive one in total budget
     * alone. That is what makes it selectable under ADR-0021: it buys a longer
     * ceiling for the same work, never a different retry classification.
     */
    @Test
    fun `diagnostic policy differs from interactive in budget alone`() {
        assertEquals(30_000, RequestPolicy.DIAGNOSTIC.totalBudgetMs)
        assertTrue(RequestPolicy.DIAGNOSTIC.totalBudgetMs > RequestPolicy.INTERACTIVE.totalBudgetMs)
        assertEquals(RequestPolicy.INTERACTIVE.maxAttempts, RequestPolicy.DIAGNOSTIC.maxAttempts)
        assertEquals(RequestPolicy.INTERACTIVE.connectTimeoutMs, RequestPolicy.DIAGNOSTIC.connectTimeoutMs)
        assertEquals(RequestPolicy.INTERACTIVE.readTimeoutMs, RequestPolicy.DIAGNOSTIC.readTimeoutMs)
    }

    /**
     * No profile may express "no retry" for an idempotent read. Writes and
     * login get none from the operation kind (`post` never retries), not from
     * a caller picking a profile — see the removed PREHEAT constant.
     */
    @Test
    fun `no policy expresses a no-retry budget`() {
        RequestPolicy.entries.forEach { policy ->
            assertTrue("$policy must allow a retry", policy.maxAttempts >= 2)
        }
    }

    @Test
    fun `error types carry no credential text`() {
        val err = NacosRequestError.Client(400, "bad request from Authorization: Basic secret")
        val msg = err.message ?: ""
        // The typed wrapper does not strip upstream body text, but its own
        // category prefix ("Client error 400") never includes credentials.
        assertEquals("Client error 400", msg)
    }
}
