package com.nanyin.nacos.search.services

import com.google.gson.Gson
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.intellij.openapi.application.ApplicationManager
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Release-gating test: a standard Nacos 3.2 server that has removed the legacy
 * V1/V2 HTTP API. The default AUTO policy must resolve the V3 generation and
 * read through the V3 adapter — it must NOT depend on the legacy V1 path.
 */
@TestApplication
class NacosApiServiceAutoResolutionTest {

    companion object {
        private lateinit var server: HttpServer
        private val gson = Gson()

        private val stateBody = """{"version":"3.2.3","status":"UP","functionMode":"config"}"""
        private val listBody = """{"code":0,"message":"success","data":{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"1","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"","type":"yaml","content":"enabled: true"}]}}"""
        private val detailBody = """{"code":0,"message":"success","data":{"dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"","content":"key=value","type":"yaml","md5":"m"}}"""

        @JvmStatic
        @BeforeAll
        fun startServer() {
            server = HttpServer.create(InetSocketAddress(0), 0)
            // V3 admin state — raw map, not an envelope. AUTO probes this first.
            server.createContext("/nacos/v3/admin/core/state") { ex ->
                requestCount.incrementAndGet()
                send(ex, 200, "application/json", stateBody)
            }
            server.createContext("/nacos/v3/admin/cs/config/list") { ex ->
                requestCount.incrementAndGet()
                send(ex, 200, "application/json", listBody)
            }
            server.createContext("/nacos/v3/admin/cs/config") { ex ->
                requestCount.incrementAndGet()
                if (ex.requestMethod == "GET") send(ex, 200, "application/json", detailBody)
                else send(ex, 200, "text/plain", "true")
            }
            // No legacy V1 contexts: every /nacos/v1/ path falls through to the
            // default 404 handler below, modelling a server without the adapter.
            server.createContext("/") { ex -> send(ex, 404, "text/plain", "Not Found") }
            server.start()
        }

        @JvmStatic
        @AfterAll
        fun stopServer() = server.stop(0)

        private val requestCount = java.util.concurrent.atomic.AtomicInteger(0)

        private fun send(ex: HttpExchange, status: Int, contentType: String, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.set("Content-Type", contentType)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private lateinit var apiService: NacosApiService
    private lateinit var settings: NacosSettings

    @BeforeEach
    fun setUp() {
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        settings.resetToDefaults()
        settings.serverUrl = "http://localhost:${server.address.port}"
        settings.username = ""
        settings.password = ""
        settings.authMode = AuthMode.ANONYMOUS
        // AUTO is the default; the plugin must resolve V3 on its own.
        kotlinx.coroutines.runBlocking { ApplicationManager.getApplication().getService(com.nanyin.nacos.search.services.CacheService::class.java).clearAll() }
        apiService = NacosApiService()
    }

    @Test
    fun `AUTO reads a configuration list from a standard 3_2 server via V3`() = runBlocking {
        val result = apiService.listConfigurations("public", 1, 10)
        assertTrue(result.isSuccess, "list failed: ${result.exceptionOrNull()}")
        val response = result.getOrThrow()
        assertEquals(1, response.totalCount)
        assertEquals("app.yaml", response.pageItems[0].dataId)
    }

    @Test
    fun `AUTO reads a single configuration from a standard 3_2 server via V3`() = runBlocking {
        val result = apiService.getConfiguration("app.yaml", "DEFAULT_GROUP", "public")
        assertTrue(result.isSuccess, "detail failed: ${result.exceptionOrNull()}")
        val config = result.getOrThrow()
        assertEquals("app.yaml", config?.dataId)
        assertEquals("key=value", config?.content)
    }
}
