package com.nanyin.nacos.search.services

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.ConfigurationRequired
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
import java.util.concurrent.atomic.AtomicInteger

@TestApplication
class NacosApiServiceTest {

    companion object {
        private lateinit var server: HttpServer
        private var serverPort: Int = 0
        private val gson = Gson()
        private val activeDetail = AtomicInteger(0)
        private val inFlightMax = AtomicInteger(0)
        private val requestCount = AtomicInteger(0)

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
                    requestCount.incrementAndGet()
                    sendJsonResponse(exchange, 200, namespacesResponse)
                }
            })
            server.createContext("/nacos/v1/cs/configs", object : HttpHandler {
                override fun handle(exchange: HttpExchange) {
                    requestCount.incrementAndGet()
                    val query = exchange.requestURI.query ?: ""
                    when {
                        // Single-config detail fetch; introduce latency to surface N+1 behavior.
                        query.contains("show=all") -> {
                            if (query.contains("dataId=cfg")) {
                                val dataId = query.substringAfter("dataId=").substringBefore("&")
                                val active = activeDetail.incrementAndGet()
                                if (active > inFlightMax.get()) inFlightMax.set(active)
                                Thread.sleep(50)
                                activeDetail.decrementAndGet()
                                sendJsonResponse(exchange, 200, mapOf(
                                    "dataId" to dataId, "group" to "DEFAULT_GROUP", "tenant" to "concurrent-ns",
                                    "content" to "v=1", "type" to "properties", "md5" to "m"
                                ))
                            } else {
                                sendJsonResponse(exchange, 200, configDetailResponse)
                            }
                        }
                        query.contains("tenant=concurrent-ns") -> {
                            val items = (1..12).map { i ->
                                mapOf("id" to "$i", "dataId" to "cfg$i", "group" to "DEFAULT_GROUP",
                                      "content" to null, "type" to "properties", "tenant" to "concurrent-ns")
                            }
                            sendJsonResponse(exchange, 200, mapOf(
                                "totalCount" to 12, "pageNumber" to 1, "pagesAvailable" to 1, "pageItems" to items
                            ))
                        }
                        else -> sendJsonResponse(exchange, 200, configListResponse)
                    }
                }
            })

            server.executor = java.util.concurrent.Executors.newFixedThreadPool(32)
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

   private lateinit var apiService: NacosApiService
   private lateinit var settings: NacosSettings

    @BeforeEach
    fun setUp() {
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        settings.resetToDefaults()
        publishEnvironment(
            serverUrl = "http://localhost:$serverPort",
            username = "nacos",
            password = "nacos",
            authMode = AuthMode.BASIC
        )

        apiService = NacosApiService()
    }

    /** Publish via the profile write path — flat fields are not runtime sources after migration. */
    private fun publishEnvironment(
        serverUrl: String,
        username: String = "",
        password: String = "",
        authMode: AuthMode = AuthMode.ANONYMOUS,
        id: String = "s_local"
    ) {
        settings.applyServers(
            listOf(
                NacosServerConfig(
                    id = id,
                    displayName = "Test",
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    authMode = authMode
                )
            ),
            id
        )
    }

    @Test
    fun `test nacos service initialization`() {
        assertNotNull(apiService)
    }

    private fun capturedContext() = settings.captureOperationContext().getOrThrow()

    @Test
    fun `test connection to nacos server`() = runBlocking {
        val result = apiService.testConnection(capturedContext())
        assertTrue(result.isSuccess)
        assertTrue(result.getOrDefault(false))
    }

    @Test
    fun `test list configurations`() = runBlocking {
        val result = apiService.listConfigurations("test-ns", 1, 10)
        assertTrue(result.isSuccess)

        val response = result.getOrNull()?.value
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

        val config = result.getOrNull()?.value
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
    fun `invalid endpoint or incomplete credentials fails closed before cache or transport`() = runBlocking {
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        cache.clearAll()
        cache.writeDetail(
            settings.captureAccessIdentity(),
            "test-ns",
            NacosConfiguration("test.properties", "DEFAULT_GROUP", "test-ns", "cached=value")
        )
        publishEnvironment(
            serverUrl = "http://localhost:$serverPort/nacos",
            username = "nacos",
            password = "",
            authMode = AuthMode.BASIC
        )
        val before = requestCount.get()

        val result = apiService.getConfiguration("test.properties", "DEFAULT_GROUP", "test-ns")

        assertTrue(result.isFailure)
        assertInstanceOf(ConfigurationRequired::class.java, result.exceptionOrNull())
        assertEquals(before, requestCount.get())
        cache.clearAll()
    }

    @Test
    fun `loadNamespace returns COMPLETE when all items fetched`() = runBlocking {
        val result = apiService.loadNamespace("test-ns", useCache = false, operationContext = capturedContext())
        assertTrue(result.isSuccess)
        val loadResult = result.getOrNull()!!
        assertEquals(DatasetCompleteness.COMPLETE, loadResult.completeness)
        assertEquals(1, loadResult.configurations.size)
        assertTrue(loadResult.failures.isEmpty())
    }

    @Test
    fun `loadNamespace returns FAILED when list endpoint unreachable`() = runBlocking {
        publishEnvironment(serverUrl = "http://localhost:1", authMode = AuthMode.ANONYMOUS)
        // Capture context against the unreachable endpoint before constructing the service.
        val failingContext = settings.captureOperationContext().getOrThrow()
        val failingService = NacosApiService()
        val result = failingService.loadNamespace(null, useCache = false, operationContext = failingContext)
        assertTrue(result.isSuccess)
        assertEquals(DatasetCompleteness.FAILED, result.getOrNull()!!.completeness)
    }

    @Test
    fun `test get namespaces`() = runBlocking {
        val result = apiService.getNamespaces(capturedContext())
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
        publishEnvironment(serverUrl = "http://localhost:1", authMode = AuthMode.ANONYMOUS)
        val failingContext = settings.captureOperationContext().getOrThrow()
        val failingService = NacosApiService()

        val result = failingService.getNamespaces(failingContext)
        assertTrue(result.isSuccess)

        val namespaces = result.getOrNull()
        assertNotNull(namespaces)
        assertEquals(1, namespaces!!.size)
        assertTrue(namespaces[0].isPublicNamespace())
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

       publishEnvironment(serverUrl = "http://invalid", authMode = AuthMode.ANONYMOUS)
       // Empty origin is invalid; an unparseable endpoint fails validation too.
       settings.serverUrl = ""
       assertFalse(settings.isValid())
   }

    @Test
    fun `loadNamespace completeness ignores detail state and follows summary pagination`() = runBlocking {
        val result = apiService.loadNamespace("concurrent-ns", useCache = false, operationContext = capturedContext())
        assertTrue(result.isSuccess)
        val load = result.getOrNull()!!
        assertEquals(DatasetCompleteness.COMPLETE, load.completeness)
        assertEquals(12, load.expectedCount)
        assertEquals(12, load.configurations.size)
        assertTrue(load.failures.isEmpty())
        assertEquals(0, activeDetail.get())
    }
}
