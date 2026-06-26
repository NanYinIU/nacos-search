package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.MatchType
import com.nanyin.nacos.search.models.NacosConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@TestApplication
class SearchServiceTest {

    private lateinit var searchService: SearchService
    private lateinit var cacheService: CacheService

    @BeforeEach
    fun setUp() {
        searchService = SearchService()
        cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
        runBlocking {
            cacheService.clearCache()
        }
    }

    private fun createConfig(
        dataId: String,
        group: String = "DEFAULT_GROUP",
        tenantId: String? = "public",
        content: String
    ): NacosConfiguration {
        return NacosConfiguration(
            dataId = dataId,
            group = group,
            tenantId = tenantId,
            content = content,
            type = "properties"
        )
    }

    @Test
    fun `test searchConfigurations with blank query`() = runBlocking {
        val results = searchService.searchConfigurations("")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `test searchConfigurations by data id`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", content = "key=value"))
        cacheService.cacheConfiguration(createConfig("other.yaml", content = "key: value"))

        val results = searchService.searchConfigurations("test")
        assertEquals(1, results.size)
        assertEquals(MatchType.DATA_ID, results[0].matchType)
    }

    @Test
    fun `test searchConfigurations by group`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", group = "CUSTOM_GROUP", content = "key=value"))

        val results = searchService.searchConfigurations("custom")
        assertEquals(1, results.size)
        assertEquals(MatchType.GROUP, results[0].matchType)
    }

    @Test
    fun `test searchConfigurations by tenant`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", tenantId = "private", content = "key=value"))

        val results = searchService.searchConfigurations("private")
        assertEquals(1, results.size)
        assertEquals(MatchType.TENANT, results[0].matchType)
    }

    @Test
    fun `test searchConfigurations by content`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", content = "database.host=localhost"))

        val results = searchService.searchConfigurations("database")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.matchType == MatchType.CONTENT })
    }

    @Test
    fun `test searchConfigurations with group filter`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("config1.properties", group = "GROUP_A", content = "key=value"))
        cacheService.cacheConfiguration(createConfig("config2.properties", group = "GROUP_B", content = "key=value"))

        val results = searchService.searchConfigurations("config", groupFilter = "GROUP_A")
        assertEquals(1, results.size)
        assertEquals("config1.properties", results[0].configuration.dataId)
    }

    @Test
    fun `test searchConfigurations with tenant filter`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("config1.properties", tenantId = "tenant_a", content = "key=value"))
        cacheService.cacheConfiguration(createConfig("config2.properties", tenantId = "tenant_b", content = "key=value"))

        val results = searchService.searchConfigurations("config", tenantFilter = "tenant_a")
        assertEquals(1, results.size)
        assertEquals("tenant_a", results[0].configuration.tenantId)
    }

    @Test
    fun `test searchConfigurations content only`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", content = "database.host=localhost"))
        cacheService.cacheConfiguration(createConfig("database.yaml", content = "other"))

        val results = searchService.searchConfigurations("database", contentOnly = true)
        assertEquals(1, results.size)
        assertEquals("test.properties", results[0].configuration.dataId)
    }

    @Test
    fun `test searchConfigurations multiple matches`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", content = "test value"))

        val results = searchService.searchConfigurations("test")
        assertTrue(results.any { it.matchType == MatchType.MULTIPLE })
    }

    @Test
    fun `test searchByDataId`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", content = "key=value"))
        cacheService.cacheConfiguration(createConfig("other.yaml", content = "key: value"))

        val results = searchService.searchByDataId("test")
        assertEquals(1, results.size)
        assertEquals("test.properties", results[0].configuration.dataId)
        assertTrue(results[0].highlightedContent.contains("<mark>"))
    }

    @Test
    fun `test searchByGroup`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", group = "CUSTOM", content = "key=value"))

        val results = searchService.searchByGroup("custom")
        assertEquals(1, results.size)
        assertEquals(MatchType.GROUP, results[0].matchType)
    }

    @Test
    fun `test searchByContent`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", content = "database.host=localhost"))

        val results = searchService.searchByContent("database")
        assertEquals(1, results.size)
        assertEquals(MatchType.CONTENT, results[0].matchType)
    }

    @Test
    fun `test searchByRegex with valid pattern`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", content = "key=value123"))

        val results = searchService.searchByRegex("value\\d+")
        assertEquals(1, results.size)
        assertEquals(MatchType.CONTENT, results[0].matchType)
    }

    @Test
    fun `test searchByRegex with invalid pattern`() = runBlocking {
        val results = searchService.searchByRegex("[invalid")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `test getSearchSuggestions`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", content = "key=value"))
        cacheService.cacheConfiguration(createConfig("testing.yaml", group = "TEST_GROUP", content = "key: value"))

        val suggestions = searchService.getSearchSuggestions("test", limit = 10)
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.contains("test.properties"))
        assertTrue(suggestions.contains("testing.yaml"))
        assertTrue(suggestions.contains("TEST_GROUP"))
    }

    @Test
    fun `test getSearchSuggestions with short query`() = runBlocking {
        val suggestions = searchService.getSearchSuggestions("t")
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `test getAvailableGroups`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("config1.properties", group = "GROUP_B", content = "key=value"))
        cacheService.cacheConfiguration(createConfig("config2.properties", group = "GROUP_A", content = "key=value"))

        val groups = searchService.getAvailableGroups()
        assertEquals(listOf("GROUP_A", "GROUP_B"), groups)
    }

    @Test
    fun `test getAvailableTenants`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("config1.properties", tenantId = "tenant_b", content = "key=value"))
        cacheService.cacheConfiguration(createConfig("config2.properties", tenantId = "tenant_a", content = "key=value"))

        val tenants = searchService.getAvailableTenants()
        assertEquals(listOf("tenant_a", "tenant_b"), tenants)
    }

    @Test
    fun `test search result sorting`() = runBlocking {
        cacheService.cacheConfiguration(createConfig("test.properties", content = "key=value"))
        cacheService.cacheConfiguration(createConfig("other.yaml", group = "test", content = "key: value"))

        val results = searchService.searchConfigurations("test")
        val dataIdResults = results.filter { it.matchType == MatchType.DATA_ID }
        val groupResults = results.filter { it.matchType == MatchType.GROUP }

        assertTrue(dataIdResults.isNotEmpty())
        assertTrue(groupResults.isNotEmpty())
    }
}
