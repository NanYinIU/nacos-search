package com.nanyin.nacos.search.services

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

@TestApplication
class NacosAuthServiceTest {

    companion object {
        private lateinit var server: HttpServer
        private var serverPort: Int = 0
        private val gson = Gson()

        private val successfulLoginResponse = mapOf(
            "accessToken" to "test-token-123",
            "tokenTtl" to 18000L,
            "globalAdmin" to true
        )

        private val failedLoginResponse = mapOf(
            "accessToken" to "",
            "tokenTtl" to 0L,
            "globalAdmin" to false
        )

        @JvmStatic
        @BeforeAll
        fun startServer() {
            server = HttpServer.create(InetSocketAddress(0), 0)
            serverPort = server.address.port

            server.createContext("/nacos/v1/auth/login", object : HttpHandler {
                override fun handle(exchange: HttpExchange) {
                    val requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    when {
                        requestBody.contains("password=valid") -> sendJsonResponse(exchange, 200, successfulLoginResponse)
                        else -> sendJsonResponse(exchange, 200, failedLoginResponse)
                    }
                }
            })

            server.createContext("/nacos/v1/auth/users", object : HttpHandler {
                override fun handle(exchange: HttpExchange) {
                    val token = exchange.requestURI.query?.substringAfter("accessToken=") ?: ""
                    when (token) {
                        "test-token-123" -> sendJsonResponse(exchange, 200, mapOf("code" to 0))
                        else -> sendJsonResponse(exchange, 401, mapOf("code" to 401, "message" to "Unauthorized"))
                    }
                }
            })

            server.executor = null
            server.start()
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            server.stop(0)
        }

        private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, body: Any) {
            val response = gson.toJson(body)
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private lateinit var authService: NacosAuthService
    private lateinit var settings: NacosSettings

    @BeforeEach
    fun setUp() {
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        settings.resetToDefaults()
        settings.serverUrl = "http://localhost:$serverPort"

        authService = NacosAuthService()
    }

    @Test
    fun `test isTokenAuthConfigured with valid credentials`() {
        settings.username = "valid"
        settings.password = "valid"

        assertTrue(authService.isTokenAuthConfigured())
    }

    @Test
    fun `test isTokenAuthConfigured with missing credentials`() {
        settings.username = ""
        settings.password = ""

        assertFalse(authService.isTokenAuthConfigured())
    }

    @Test
    fun `test isTokenAuthConfigured with missing server url`() {
        settings.serverUrl = ""
        settings.username = "valid"
        settings.password = "valid"

        assertFalse(authService.isTokenAuthConfigured())
    }

    @Test
    fun `test getValidAccessToken returns token on successful login`() = runBlocking {
        settings.username = "valid"
        settings.password = "valid"

        val token = authService.getValidAccessToken()
        assertEquals("test-token-123", token)
    }

    @Test
    fun `test getValidAccessToken returns empty on failed login`() = runBlocking {
        settings.username = "invalid"
        settings.password = "invalid"

        val token = authService.getValidAccessToken()
        assertTrue(token.isNullOrEmpty())
    }

    @Test
    fun `test getValidAccessToken with blank credentials returns empty`() = runBlocking {
        settings.username = ""
        settings.password = ""

        val token = authService.getValidAccessToken()
        assertTrue(token.isNullOrEmpty())
    }

    @Test
    fun `test getValidAccessToken caches token`() = runBlocking {
        settings.username = "valid"
        settings.password = "valid"

        val token1 = authService.getValidAccessToken()
        val token2 = authService.getValidAccessToken()

        assertEquals("test-token-123", token1)
        assertEquals(token1, token2)
    }

    @Test
    fun `test validateToken with valid token`() = runBlocking {
        val isValid = authService.validateToken("test-token-123")
        assertTrue(isValid)
    }

    @Test
    fun `test validateToken with invalid token`() = runBlocking {
        val isValid = authService.validateToken("invalid-token")
        assertFalse(isValid)
    }

    @Test
    fun `test refreshTokenIfNeeded when no cached token`() = runBlocking {
        settings.username = "valid"
        settings.password = "valid"

        val result = authService.refreshTokenIfNeeded()
        assertTrue(result)
    }

    @Test
    fun `test refreshTokenIfNeeded with blank credentials`() = runBlocking {
        settings.username = ""
        settings.password = ""

        val result = authService.refreshTokenIfNeeded()
        assertFalse(result)
    }

    @Test
    fun `test isTokenValid initially false`() {
        assertFalse(authService.isTokenValid())
    }

    @Test
    fun `test isTokenValid after login`() = runBlocking {
        settings.username = "valid"
        settings.password = "valid"
        authService.getValidAccessToken()

        assertTrue(authService.isTokenValid())
    }

    @Test
    fun `test logout clears token`() = runBlocking {
        settings.username = "valid"
        settings.password = "valid"
        authService.getValidAccessToken()

        assertTrue(authService.isTokenValid())

        authService.logout()
        assertFalse(authService.isTokenValid())
    }

    @Test
    fun `test getTokenStatus when no token`() {
        val status = authService.getTokenStatus()
        assertEquals("No token cached", status)
    }

    @Test
    fun `test getTokenStatus when valid`() = runBlocking {
        settings.username = "valid"
        settings.password = "valid"
        authService.getValidAccessToken()

        val status = authService.getTokenStatus()
        assertTrue(status.startsWith("Token valid"))
    }

    @Test
    fun `test token cache uses different keys for different users`() = runBlocking {
        settings.username = "user1"
        settings.password = "valid"

        val token1 = authService.getValidAccessToken()
        assertEquals("test-token-123", token1)

        settings.username = "user2"
        settings.password = "valid"

        val token2 = authService.getValidAccessToken()
        assertEquals("test-token-123", token2)

        settings.username = "user1"
        val token3 = authService.getValidAccessToken()
        assertEquals("test-token-123", token3)
    }
}
