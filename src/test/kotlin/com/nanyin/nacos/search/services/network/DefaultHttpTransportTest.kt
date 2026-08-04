package com.nanyin.nacos.search.services.network

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress

/**
 * Drives the real production transport against a loopback HTTP server.
 *
 * Issue #39's root cause was that the transport discarded the body of a non-2xx
 * response, so no adapter could classify a refused token. Every other test in
 * the suite injects an [NacosRequestExecutor.HttpTransport] fixture, so this is
 * the only place that can prove the body actually survives the wire.
 */
class DefaultHttpTransportTest {

    private lateinit var server: HttpServer
    private var status = 200
    private var responseBody = ""

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `GET surfaces the 403 envelope so V1 can classify a refused token`() {
        status = 403
        responseBody = """{"code":403,"message":"token is invalid"}"""

        val error = assertThrows<NacosRequestError.Authentication> {
            DefaultHttpTransport.get(request())
        }

        assertEquals(403, error.status)
        assertEquals(responseBody, error.body)
    }

    @Test
    fun `POST surfaces the 401 envelope`() {
        status = 401
        responseBody = """{"code":401,"message":"token expired"}"""

        val error = assertThrows<NacosRequestError.Authentication> {
            DefaultHttpTransport.post(request(postBody = "dataId=app&group=DEFAULT_GROUP"))
        }

        assertEquals(401, error.status)
        assertEquals(responseBody, error.body)
    }

    @Test
    fun `a 403 mentioning authorization stays a parseable envelope`() {
        status = 403
        responseBody = """{"code":403,"message":"authorization failed"}"""

        val error = assertThrows<NacosRequestError.Authentication> {
            DefaultHttpTransport.get(request())
        }

        // Greedy masking used to swallow the closing quote and brace, leaving an
        // unparseable body and defeating adapter classification (issue #39).
        assertTrue(error.body.endsWith("}"), "sanitized body must stay a JSON object: ${error.body}")
        assertTrue(error.body.startsWith("""{"code":403"""), error.body)
    }

    @Test
    fun `a credential echoed in an error body never reaches the typed failure`() {
        status = 403
        responseBody = """{"code":403,"accessToken":"s3cr3t","message":"token is invalid"}"""

        val error = assertThrows<NacosRequestError.Authentication> {
            DefaultHttpTransport.get(request())
        }

        assertFalse(error.body.contains("s3cr3t"), error.body)
        assertTrue(error.body.contains("token is invalid"), error.body)
    }

    @Test
    fun `a 2xx still returns its body`() {
        status = 200
        responseBody = """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}"""

        assertEquals(responseBody, DefaultHttpTransport.get(request()))
    }

    @Test
    fun `a 500 is classified as a retriable server error carrying its body`() {
        status = 500
        responseBody = "upstream exploded"

        val error = assertThrows<NacosRequestError.Server> {
            DefaultHttpTransport.get(request())
        }

        assertEquals(500, error.status)
        assertEquals("upstream exploded", error.body)
    }

    private fun request(postBody: String? = null) = NacosRequestExecutor.TransportRequest(
        url = "http://127.0.0.1:${server.address.port}/nacos/v1/cs/configs",
        connectTimeoutMs = 2_000,
        readTimeoutMs = 2_000,
        authHeaders = emptyMap(),
        attempt = 1,
        postBody = postBody
    )
}
