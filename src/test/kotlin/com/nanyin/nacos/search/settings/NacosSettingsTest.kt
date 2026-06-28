package com.nanyin.nacos.search.settings

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@TestApplication
class NacosSettingsTest {

    private lateinit var settings: NacosSettings

    @BeforeEach
    fun setUp() {
        settings = NacosSettings()
        settings.resetToDefaults()
    }

    @Test
    fun `test default settings values`() {
        assertEquals("http://localhost:8848", settings.serverUrl)
        assertEquals("", settings.username)
        assertEquals("", settings.password)
        assertEquals("public", settings.namespace)
        assertEquals(AuthMode.TOKEN, settings.authMode)
        assertTrue(settings.enableTokenAuth)
        assertEquals(30, settings.tokenCacheDurationMinutes)
        assertTrue(settings.autoTokenRefresh)
        assertTrue(settings.cacheEnabled)
        assertEquals(5, settings.cacheTtlMinutes)
        assertEquals(1000, settings.maxCacheSize)
        assertTrue(settings.autoRefreshEnabled)
        assertEquals(10, settings.autoRefreshIntervalMinutes)
        assertEquals(100, settings.searchResultLimit)
        assertTrue(settings.enableRegexSearch)
        assertFalse(settings.caseSensitiveSearch)
        assertTrue(settings.highlightMatches)
        assertTrue(settings.showToolWindow)
        assertEquals("right", settings.toolWindowLocation)
        assertTrue(settings.rememberLastSearch)
        assertEquals("en", settings.language)
        assertEquals(30, settings.connectionTimeoutSeconds)
        assertEquals(60, settings.readTimeoutSeconds)
        assertEquals(3, settings.retryAttempts)
        assertEquals(2, settings.retryDelaySeconds)
    }

    @Test
    fun `test validate with valid settings`() {
        val errors = settings.validate()
        assertTrue(errors.isEmpty())
        assertTrue(settings.isValid())
    }

    @Test
    fun `test validate with empty server url`() {
        settings.serverUrl = ""
        val errors = settings.validate()
        assertTrue(errors.contains("Server URL cannot be empty"))
        assertFalse(settings.isValid())
    }

    @Test
    fun `test validate with invalid server url`() {
        settings.serverUrl = "not a url"
        val errors = settings.validate()
        assertTrue(errors.contains("Invalid server URL format"))
        assertFalse(settings.isValid())
    }

    @Test
    fun `test validate with invalid cache ttl`() {
        settings.cacheTtlMinutes = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Cache TTL must be at least 1 minute"))
    }

    @Test
    fun `test validate with invalid max cache size`() {
        settings.maxCacheSize = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Max cache size must be at least 1"))
    }

    @Test
    fun `test validate with invalid auto refresh interval`() {
        settings.autoRefreshIntervalMinutes = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Auto refresh interval must be at least 1 minute"))
    }

    @Test
    fun `test validate with invalid search result limit`() {
        settings.searchResultLimit = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Search result limit must be at least 1"))
    }

    @Test
    fun `test validate with invalid connection timeout`() {
        settings.connectionTimeoutSeconds = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Connection timeout must be at least 1 second"))
    }

    @Test
    fun `test validate with invalid read timeout`() {
        settings.readTimeoutSeconds = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Read timeout must be at least 1 second"))
    }

    @Test
    fun `test validate with negative retry attempts`() {
        settings.retryAttempts = -1
        val errors = settings.validate()
        assertTrue(errors.contains("Retry attempts cannot be negative"))
    }

    @Test
    fun `test validate with negative retry delay`() {
        settings.retryDelaySeconds = -1
        val errors = settings.validate()
        assertTrue(errors.contains("Retry delay cannot be negative"))
    }

    @Test
    fun `test isValidServerUrl with http url`() {
        assertTrue(settings.isValidServerUrl("http://localhost:8848"))
    }

    @Test
    fun `test isValidServerUrl with https url`() {
        assertTrue(settings.isValidServerUrl("https://example.com"))
    }

    @Test
    fun `test isValidServerUrl with invalid protocol`() {
        assertFalse(settings.isValidServerUrl("ftp://example.com"))
    }

    @Test
    fun `test isValidServerUrl with empty url`() {
        assertFalse(settings.isValidServerUrl(""))
    }

    @Test
    fun `test resetToDefaults`() {
        settings.serverUrl = "http://custom:8848"
        settings.username = "user"
        settings.cacheEnabled = false

        settings.resetToDefaults()

        assertEquals("http://localhost:8848", settings.serverUrl)
        assertEquals("", settings.username)
        assertTrue(settings.cacheEnabled)
    }

    @Test
    fun `test copy creates independent instance`() {
        settings.serverUrl = "http://custom:8848"
        val copy = settings.copy()

        assertEquals("http://custom:8848", copy.serverUrl)
        copy.serverUrl = "http://modified:8848"
        assertEquals("http://custom:8848", settings.serverUrl)
    }

    @Test
    fun `test copyFrom`() {
        val source = NacosSettings().apply {
            serverUrl = "http://source:8848"
            username = "sourceUser"
        }

        settings.copyFrom(source)

        assertEquals("http://source:8848", settings.serverUrl)
        assertEquals("sourceUser", settings.username)
    }

    @Test
    fun `test hasAuthentication`() {
        assertFalse(settings.hasAuthentication())

        settings.username = "user"
        settings.password = "pass"
        assertTrue(settings.hasAuthentication())
    }

    @Test
    fun `test hasTokenAuthentication`() {
        settings.enableTokenAuth = true
        assertFalse(settings.hasTokenAuthentication())

        settings.username = "user"
        settings.password = "pass"
        assertTrue(settings.hasTokenAuthentication())

        settings.enableTokenAuth = false
        assertFalse(settings.hasTokenAuthentication())
    }

    @Test
    fun `test cache aliases`() {
        settings.cacheEnabled = false
        assertFalse(settings.enableCache)

        settings.enableCache = true
        assertTrue(settings.cacheEnabled)
    }

    @Test
    fun `test cacheTtlSeconds alias`() {
        settings.cacheTtlMinutes = 5
        assertEquals(300, settings.cacheTtlSeconds)

        settings.cacheTtlSeconds = 600
        assertEquals(10, settings.cacheTtlMinutes)
    }

    @Test
    fun `test autoRefresh aliases`() {
        settings.autoRefreshEnabled = false
        assertFalse(settings.autoRefreshCache)

        settings.autoRefreshCache = true
        assertTrue(settings.autoRefreshEnabled)
    }

    @Test
    fun `test autoRefreshIntervalSeconds alias`() {
        settings.autoRefreshIntervalMinutes = 10
        assertEquals(600, settings.autoRefreshIntervalSeconds)

        settings.autoRefreshIntervalSeconds = 1200
        assertEquals(20, settings.autoRefreshIntervalMinutes)
    }

    @Test
    fun `test getTokenCacheDurationMillis`() {
        settings.tokenCacheDurationMinutes = 30
        assertEquals(30 * 60 * 1000L, settings.getTokenCacheDurationMillis())
    }

    @Test
    fun `test getCacheTtlMillis`() {
        settings.cacheTtlMinutes = 5
        assertEquals(5 * 60 * 1000L, settings.getCacheTtlMillis())
    }

    @Test
    fun `test getAutoRefreshIntervalMillis`() {
        settings.autoRefreshIntervalMinutes = 10
        assertEquals(10 * 60 * 1000L, settings.getAutoRefreshIntervalMillis())
    }

    @Test
    fun `test getConnectionTimeoutMillis`() {
        settings.connectionTimeoutSeconds = 30
        assertEquals(30000, settings.getConnectionTimeoutMillis())
    }

    @Test
    fun `test getReadTimeoutMillis`() {
        settings.readTimeoutSeconds = 60
        assertEquals(60000, settings.getReadTimeoutMillis())
    }

    @Test
    fun `test getRetryDelayMillis`() {
        settings.retryDelaySeconds = 2
        assertEquals(2000L, settings.getRetryDelayMillis())
    }

    @Test
    fun `test updateLastSearch when remember enabled`() {
        settings.rememberLastSearch = true
        settings.updateLastSearch("query", "group", "tenant")

        assertEquals("query", settings.lastSearchQuery)
        assertEquals("group", settings.lastGroupFilter)
        assertEquals("tenant", settings.lastTenantFilter)
    }

    @Test
    fun `test updateLastSearch when remember disabled`() {
        settings.rememberLastSearch = false
        settings.updateLastSearch("query", "group", "tenant")

        assertEquals("", settings.lastSearchQuery)
        assertEquals("", settings.lastGroupFilter)
        assertEquals("", settings.lastTenantFilter)
    }

    @Test
    fun `test clearLastSearch`() {
        settings.rememberLastSearch = true
        settings.updateLastSearch("query", "group", "tenant")
        settings.clearLastSearch()

        assertEquals("", settings.lastSearchQuery)
        assertEquals("", settings.lastGroupFilter)
        assertEquals("", settings.lastTenantFilter)
    }

    @Test
    fun `test state persistence`() {
        settings.serverUrl = "http://persist:8848"
        // Also update the active server's URL since loadState() calls syncFromActiveServer()
        settings.getActiveServer().serverUrl = "http://persist:8848"
        val state = settings.getState()

        val newSettings = NacosSettings()
        newSettings.loadState(state)

        assertEquals("http://persist:8848", newSettings.serverUrl)
    }

    @Test
    fun `test active server sync updates runtime connection settings`() {
        val fast = com.nanyin.nacos.search.models.NacosServerConfig(
            id = "fast",
            displayName = "Fast Local",
            serverUrl = "http://localhost:8848",
            connectionTimeoutMs = 5000,
            autoRefreshOnOpen = false
        )
        settings.applyServers(listOf(fast), "fast")

        assertEquals("http://localhost:8848", settings.serverUrl)
        assertEquals(5, settings.connectionTimeoutSeconds)
        assertFalse(settings.autoRefreshEnabled)
    }

    @Test
    fun `test toString masks password`() {
        settings.username = "user"
        settings.password = "secret"
        val str = settings.toString()

        assertTrue(str.contains("serverUrl"))
        assertFalse(str.contains("secret"))
    }
}
