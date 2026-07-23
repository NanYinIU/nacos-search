package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.network.RequestPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProtocolTransportTest {

    @Test
    fun `GET routes through executor get`() = runBlocking {
        val executor = NacosRequestExecutor(RecordingTransport("GET response"))
        val transport = NacosRequestExecutorProtocolTransport(executor)

        val response = transport.execute(ProtocolRequest(
            method = "GET",
            endpoint = "https://nacos.example",
            path = "/nacos/v1/cs/configs",
            query = emptyList(),
            headers = emptyMap()
        ))

        assertEquals("GET response", response.body)
    }

    @Test
    fun `POST routes through executor post with body`() = runBlocking {
        val transport = RecordingTransport("POST response")
        val executor = NacosRequestExecutor(transport)
        val protocolTransport = NacosRequestExecutorProtocolTransport(executor)

        val response = protocolTransport.execute(ProtocolRequest(
            method = "POST",
            endpoint = "https://nacos.example",
            path = "/nacos/v1/cs/configs",
            query = emptyList(),
            headers = mapOf("casMd5" to "base-md5"),
            body = "dataId=app&content=new"
        ))

        assertEquals("POST response", response.body)
    }

    @Test
    fun `unsupported method throws`() {
        val executor = NacosRequestExecutor(RecordingTransport("noop"))
        val transport = NacosRequestExecutorProtocolTransport(executor)

        assertThrows<RemoteOperationError.Unsupported> {
            kotlinx.coroutines.runBlocking {
                transport.execute(ProtocolRequest(
                    method = "DELETE",
                    endpoint = "https://nacos.example",
                    path = "/",
                    query = emptyList(),
                    headers = emptyMap()
                ))
            }
        }
    }

    private class RecordingTransport(private val responseBody: String) : NacosRequestExecutor.HttpTransport {
        var lastPostBody: String? = null

        override fun get(request: NacosRequestExecutor.TransportRequest): String = responseBody

        override fun post(request: NacosRequestExecutor.TransportRequest): String {
            lastPostBody = request.postBody
            return responseBody
        }
    }

    // ---- S2: the production transport must preserve the real HTTP status so the
    // adapter's status/envelope classification runs in production, not just tests ----

    @Test
    fun `GET preserves 404 status and body from a thrown client error`() = runBlocking {
        val executor = NacosRequestExecutor(ThrowingTransport { throw NacosRequestError.Client(404, "{\"code\":20004}") })
        val transport = NacosRequestExecutorProtocolTransport(executor)

        val response = transport.execute(ProtocolRequest(
            method = "GET", endpoint = "https://nacos.example",
            path = "/nacos/v3/admin/core/state", query = emptyList(), headers = emptyMap()
        ))

        assertEquals(404, response.status)
        assertEquals("{\"code\":20004}", response.body)
    }

    @Test
    fun `GET preserves 403 status from a thrown authentication error`() = runBlocking {
        val executor = NacosRequestExecutor(ThrowingTransport { throw NacosRequestError.Authentication(403) })
        val transport = NacosRequestExecutorProtocolTransport(executor)

        val response = transport.execute(ProtocolRequest(
            method = "GET", endpoint = "https://nacos.example",
            path = "/nacos/v3/admin/core/state", query = emptyList(), headers = emptyMap()
        ))

        assertEquals(403, response.status)
    }

    @Test
    fun `GET preserves 500 status and body from a thrown server error`() = runBlocking {
        val executor = NacosRequestExecutor(ThrowingTransport { throw NacosRequestError.Server(500, "boom") })
        val transport = NacosRequestExecutorProtocolTransport(executor)

        val response = transport.execute(ProtocolRequest(
            method = "GET", endpoint = "https://nacos.example",
            path = "/p", query = emptyList(), headers = emptyMap()
        ))

        assertEquals(500, response.status)
        assertEquals("boom", response.body)
    }

    @Test
    fun `genuine connection failure throws RemoteOperationError Connection`() {
        val executor = NacosRequestExecutor(ThrowingTransport { throw NacosRequestError.Connection(RuntimeException("dns")) })
        val transport = NacosRequestExecutorProtocolTransport(executor)

        val thrown = assertThrows<RemoteOperationError.Connection> {
            kotlinx.coroutines.runBlocking {
                transport.execute(ProtocolRequest(
                    method = "GET", endpoint = "https://nacos.example",
                    path = "/p", query = emptyList(), headers = emptyMap()
                ))
            }
        }
        assertEquals("Connection failed", thrown.message)
    }

    @Test
    fun `read timeout throws RemoteOperationError Connection`() {
        val executor = NacosRequestExecutor(ThrowingTransport { throw NacosRequestError.ReadTimeout(RuntimeException("slow")) })
        val transport = NacosRequestExecutorProtocolTransport(executor)

        assertThrows<RemoteOperationError.Connection> {
            kotlinx.coroutines.runBlocking {
                transport.execute(ProtocolRequest(
                    method = "GET", endpoint = "https://nacos.example",
                    path = "/p", query = emptyList(), headers = emptyMap()
                ))
            }
        }
    }

    private class ThrowingTransport(private val behavior: () -> String) : NacosRequestExecutor.HttpTransport {
        override fun get(request: NacosRequestExecutor.TransportRequest): String = behavior()
        override fun post(request: NacosRequestExecutor.TransportRequest): String = behavior()
    }
}
