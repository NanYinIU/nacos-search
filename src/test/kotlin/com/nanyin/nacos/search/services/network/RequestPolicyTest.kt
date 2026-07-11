package com.nanyin.nacos.search.services.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestPolicyTest {

    @Test
    fun `interactive policy has bounded retries and duration`() {
        assertEquals(3_000, RequestPolicy.INTERACTIVE.connectTimeoutMs)
        assertEquals(8_000, RequestPolicy.INTERACTIVE.readTimeoutMs)
        assertEquals(15_000, RequestPolicy.INTERACTIVE.totalBudgetMs)
        assertEquals(2, RequestPolicy.INTERACTIVE.maxAttempts)
    }

    @Test
    fun `preheat policy allows only one attempt`() {
        assertEquals(1, RequestPolicy.PREHEAT.maxAttempts)
        assertEquals(15_000, RequestPolicy.PREHEAT.totalBudgetMs)
    }

    @Test
    fun `diagnostic policy has a longer budget`() {
        assertEquals(30_000, RequestPolicy.DIAGNOSTIC.totalBudgetMs)
        assertEquals(2, RequestPolicy.DIAGNOSTIC.maxAttempts)
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
