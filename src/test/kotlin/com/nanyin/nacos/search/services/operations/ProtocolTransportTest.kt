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
}
