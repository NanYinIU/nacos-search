package com.nanyin.nacos.search.services.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NacosRequestExecutorTest {

    private fun executor(
        transport: NacosRequestExecutor.HttpTransport,
        clock: () -> Long = { System.currentTimeMillis() },
        jitter: () -> Long = { 0L }
    ) = NacosRequestExecutor(transport, clock, jitter)

    @Test
    fun `successful request returns body without retry`() = runBlocking {
        var calls = 0
        val transport = NacosRequestExecutor.HttpTransport { _ ->
            calls++
            "ok"
        }
        val result = executor(transport).get("http://nacos", RequestPolicy.INTERACTIVE)
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `connection error retries once within budget`() = runBlocking {
        val calls = AtomicInteger(0)
        val transport = NacosRequestExecutor.HttpTransport { _ ->
            if (calls.incrementAndGet() == 1) {
                throw NacosRequestError.Connection(RuntimeException("refused"))
            }
            "ok"
        }
        val result = executor(transport, jitter = { 1L }).get("http://nacos", RequestPolicy.INTERACTIVE)
        assertEquals("ok", result)
        assertEquals(2, calls.get())
    }

    @Test
    fun `authentication error does not retry`() = runBlocking {
        val calls = AtomicInteger(0)
        val transport = NacosRequestExecutor.HttpTransport { _ ->
            calls.incrementAndGet()
            throw NacosRequestError.Authentication(401)
        }
        var thrown: NacosRequestError.Authentication? = null
        try {
            executor(transport).get("http://nacos", RequestPolicy.INTERACTIVE)
        } catch (e: NacosRequestError.Authentication) {
            thrown = e
        }
        assertEquals(1, calls.get())
        assertEquals(401, thrown?.status)
    }

    @Test
    fun `client error does not retry`() = runBlocking {
        val calls = AtomicInteger(0)
        val transport = NacosRequestExecutor.HttpTransport { _ ->
            calls.incrementAndGet()
            throw NacosRequestError.Client(400, "bad dataId")
        }
        var thrown: NacosRequestError.Client? = null
        try {
            executor(transport).get("http://nacos", RequestPolicy.INTERACTIVE)
        } catch (e: NacosRequestError.Client) {
            thrown = e
        }
        assertEquals(1, calls.get())
        assertEquals(400, thrown?.status)
    }

    @Test
    fun `server error retries within budget`() = runBlocking {
        val calls = AtomicInteger(0)
        val transport = NacosRequestExecutor.HttpTransport { _ ->
            if (calls.incrementAndGet() == 1) {
                throw NacosRequestError.Server(503, "unavailable")
            }
            "ok"
        }
        val result = executor(transport, jitter = { 1L }).get("http://nacos", RequestPolicy.INTERACTIVE)
        assertEquals("ok", result)
        assertEquals(2, calls.get())
    }

    @Test
    fun `preheat policy does not retry on failure`() = runBlocking {
        val calls = AtomicInteger(0)
        val transport = NacosRequestExecutor.HttpTransport { _ ->
            calls.incrementAndGet()
            throw NacosRequestError.Connection(RuntimeException("refused"))
        }
        var thrown: NacosRequestError.Connection? = null
        try {
            executor(transport).get("http://nacos", RequestPolicy.PREHEAT)
        } catch (e: NacosRequestError.Connection) {
            thrown = e
        }
        assertEquals(1, calls.get())
        assertTrue(thrown != null)
    }

    @Test
    fun `protocol error does not retry`() = runBlocking {
        val calls = AtomicInteger(0)
        val transport = NacosRequestExecutor.HttpTransport { _ ->
            calls.incrementAndGet()
            throw NacosRequestError.Protocol("malformed JSON")
        }
        var thrown: NacosRequestError.Protocol? = null
        try {
            executor(transport).get("http://nacos", RequestPolicy.INTERACTIVE)
        } catch (e: NacosRequestError.Protocol) {
            thrown = e
        }
        assertEquals(1, calls.get())
        assertTrue(thrown != null)
    }

    @Test
    fun `exhausted retries throws last error`() = runBlocking {
        val calls = AtomicInteger(0)
        val transport = NacosRequestExecutor.HttpTransport { _ ->
            calls.incrementAndGet()
            throw NacosRequestError.ReadTimeout(RuntimeException("timeout"))
        }
        var thrown: NacosRequestError.ReadTimeout? = null
        try {
            executor(transport, jitter = { 1L }).get("http://nacos", RequestPolicy.INTERACTIVE)
        } catch (e: NacosRequestError.ReadTimeout) {
            thrown = e
        }
        assertEquals(2, calls.get())
        assertTrue(thrown != null)
    }

    @Test
    fun `credential text never appears in error messages`() = runBlocking {
        val transport = NacosRequestExecutor.HttpTransport { _ ->
            throw NacosRequestError.Client(403, "denied")
        }
        var msg = ""
        try {
            executor(transport).get("http://nacos?accessToken=secret123", RequestPolicy.INTERACTIVE)
        } catch (e: NacosRequestError) {
            msg = e.message ?: ""
        }
        assertTrue("Error should not contain secret: $msg", !msg.contains("secret"))
        assertTrue("Error should not contain accessToken: $msg", !msg.contains("accessToken"))
    }
}
