package com.nanyin.nacos.search.services

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
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
import java.util.concurrent.atomic.AtomicReference

@TestApplication
class NacosApiServiceTest {

    companion object {
        private lateinit var server: HttpServer
        private var serverPort: Int = 0
        private val gson = Gson()
        private val lastPublishBody = AtomicReference("")
        private val lastPublishQuery = AtomicReference<String?>(null)

        private val namespacesResponse = mapOf(
            "code" to 0,
            "message" to "success",
            "data" to listOf(
                mapOf(
                    "namespace" to "test-ns",
                    "namespaceShowName" to "Test Namespace",
                    "namespaceDesc" to "Test Description",
                    "configCount" to 10,
                    "type" to 1
                )
            )
        )

        private val configListResponse = mapOf(
            "totalCount" to 1,
            "pageNumber" to 1,
            "pagesAvailable" to 1,
            "pageItems" to listOf(
                mapOf(
                    "id" to "1",
                    "dataId" to "test.properties",
                    "group" to "DEFAULT_GROUP",
                    "content" to "initial",
                    "type" to "properties",
                    "tenant" to "test-ns"
                )
            )
        )

        private val configDetailResponse = mapOf(
            "dataId" to "test.properties",
            "group" to "DEFAULT_GROUP",
            "tenant" to "test-ns",
            "content" to "key=value",
            "type" to "properties",
            "md5" to "abc123"
        )

        @JvmStatic
        @BeforeAll
        fun startServer() {
            server = HttpServer.create(InetSocketAddress(0), 0)
            serverPort = server.address.port

            server.createContext("/nacos/v1/console/namespaces", object : HttpHandler {
                override fun handle(exchange: HttpExchange) {
                    sendJsonResponse(exchange, 200, namespacesResponse)
                }
            })
            // Handle POST (publish) to /nacos/v1/cs/configs
            server.createContext("/nacos/v1/cs/configs", object : HttpHandler {
                override fun handle(exchange: HttpExchange) {
                    if (exchange.requestMethod == "POST") {
                        lastPublishQuery.set(exchange.requestURI.query)
                        lastPublishBody.set(exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).readText())
                        sendTextResponse(exchange, 200, "true")
                        return
                    }
                    val query = exchange.requestURI.query ?: ""
                    when {
                        query.contains("show=all") -> sendJsonResponse(exchange, 200, configDetailResponse)
                        else -> sendJsonResponse(exchange, 200, configListResponse)
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

        fun resetLastPublishRequest() {
            lastPublishBody.set("")
            lastPublishQuery.set(null)
        }

       private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, body: Any) {
           val response = gson.toJson(body)
           val bytes = response.toByteArray(StandardCharsets.UTF_8)
           exchange.responseHeaders.set("Content-Type", "application/json")
           exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
           exchange.responseBody.use { it.write(bytes) }
       }

        private fun sendTextResponse(exchange: HttpExchange, statusCode: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain")
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
   }

   private lateinit var apiService: NacosApiService
   private lateinit var settings: NacosSettings

    @BeforeEach
    fun setUp() {
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        settings.resetToDefaults()
        settings.serverUrl = "http://localhost:$serverPort"
        settings.username = "nacos"
        settings.password = "nacos"
        settings.authMode = AuthMode.BASIC

        apiService = NacosApiService()
    }

    @Test
    fun `test nacos service initialization`() {
        assertNotNull(apiService)
    }

    @Test
    fun `test connection to nacos server`() = runBlocking {
        val result = apiService.testConnection()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrDefault(false))
    }

    @Test
    fun `test list configurations`() = runBlocking {
        val result = apiService.listConfigurations("test-ns", 1, 10)
        assertTrue(result.isSuccess)

        val response = result.getOrNull()
        assertNotNull(response)
        assertEquals(1, response!!.totalCount)
        assertEquals(1, response.pageItems.size)
        assertEquals("test.properties", response.pageItems[0].dataId)
    }

    @Test
    fun `test list configurations with null namespace`() = runBlocking {
        val result = apiService.listConfigurations(null, 1, 10)
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
    }

    @Test
    fun `test get configuration`() = runBlocking {
        val result = apiService.getConfiguration("test.properties", "DEFAULT_GROUP", "test-ns")
        assertTrue(result.isSuccess)

        val config = result.getOrNull()
        assertNotNull(config)
        assertEquals("test.properties", config!!.dataId)
        assertEquals("key=value", config.content)
    }

    @Test
    fun `test get configuration with null namespace`() = runBlocking {
        val result = apiService.getConfiguration("test.properties", "DEFAULT_GROUP", null)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `test get all configurations`() = runBlocking {
        val result = apiService.getAllConfigurations("test-ns")
        assertTrue(result.isSuccess)

        val configs = result.getOrNull()
        assertNotNull(configs)
        assertTrue(configs!!.isNotEmpty())
    }

    @Test
    fun `test get all configurations with null namespace`() = runBlocking {
        val result = apiService.getAllConfigurations(null)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `test get namespaces`() = runBlocking {
        val result = apiService.getNamespaces()
        assertTrue(result.isSuccess)

        val namespaces = result.getOrNull()
        assertNotNull(namespaces)
        assertTrue(namespaces!!.isNotEmpty())

        val hasPublicNamespace = namespaces.any { it.isPublicNamespace() }
        assertTrue(hasPublicNamespace)

        val testNamespace = namespaces.find { it.namespaceId == "test-ns" }
        assertNotNull(testNamespace)
        assertEquals("Test Namespace", testNamespace!!.namespaceName)
    }

    @Test
    fun `test get namespaces returns public namespace when api fails`() = runBlocking {
        // Point to a non-existent port to trigger error handling
        settings.serverUrl = "http://localhost:1"
        val failingService = NacosApiService()

        val result = failingService.getNamespaces()
        assertTrue(result.isSuccess)

        val namespaces = result.getOrNull()
        assertNotNull(namespaces)
        assertEquals(1, namespaces!!.size)
        assertTrue(namespaces[0].isPublicNamespace())
    }

    @Test
    fun `test getConfigurationFromItem returns full configuration`() = runBlocking {
        val item = NacosApiService.ConfigItem(
            id = "1",
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            content = "initial",
            type = "properties",
            tenant = "test-ns"
        )

        val config = apiService.getConfigurationFromItem(item)
        assertEquals("test.properties", config.dataId)
        // Item already has content, so it's returned directly
        assertEquals("initial", config.content)
    }

    @Test
    fun `test clear cache`() = runBlocking {
        apiService.clearCache()
        apiService.clearCache("test-ns")
    }

    @Test
    fun `test configuration key generation`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "test=value",
            type = "properties",
            md5 = "abc123",
            lastModified = System.currentTimeMillis()
        )

        assertEquals("test.properties:DEFAULT_GROUP:public", config.getKey())
    }

    @Test
    fun `test configuration display name`() {
        val config = NacosConfiguration(
            dataId = "test.properties",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "test=value"
        )

        assertEquals("test.properties (DEFAULT_GROUP) [public]", config.getDisplayName())
    }

    @Test
    fun `test configuration type inference`() {
        val yamlConfig = NacosConfiguration(
            dataId = "test.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "public",
            content = "key: value",
            type = null
        )

        assertEquals("yaml", yamlConfig.getConfigType())
    }

   @Test
   fun `test settings validation`() {
       assertTrue(settings.isValid())

       settings.serverUrl = ""
       assertFalse(settings.isValid())
   }

    @Test
    fun `test publish configuration to correct endpoint`() = runBlocking {
        resetLastPublishRequest()
        val result = apiService.publishConfiguration(
            dataId = "test-publish.properties",
            group = "TEST_GROUP",
            content = "test.key=test.value",
            type = "properties",
            namespaceId = "test-ns"
        )
        assertTrue(result.isSuccess)
        assertTrue(result.getOrDefault(false))
        assertEquals(
            "dataId=test-publish.properties&group=TEST_GROUP&content=test.key%3Dtest.value&type=properties&tenant=test-ns",
            lastPublishBody.get()
        )
        assertFalse(lastPublishQuery.get().orEmpty().contains("content="))
    }

    @Test
    fun `test publish configuration to null namespace`() = runBlocking {
        val result = apiService.publishConfiguration(
            dataId = "public-config.properties",
            group = "DEFAULT_GROUP",
            content = "key=value",
            type = "properties",
            namespaceId = null
        )
        assertTrue(result.isSuccess)
        assertTrue(result.getOrDefault(false))
    }
}
