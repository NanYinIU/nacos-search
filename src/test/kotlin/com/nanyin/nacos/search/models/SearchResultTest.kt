package com.nanyin.nacos.search.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SearchResultTest {

    private fun createConfig(dataId: String = "test.properties", group: String = "DEFAULT_GROUP"): NacosConfiguration {
        return NacosConfiguration(
            dataId = dataId,
            group = group,
            tenantId = "public",
            content = "key=value"
        )
    }

    @Test
    fun `test SearchResult creation`() {
        val config = createConfig()
        val result = SearchResult(config, MatchType.DATA_ID, "highlighted", 100)

        assertEquals(config, result.configuration)
        assertEquals(MatchType.DATA_ID, result.matchType)
        assertEquals("highlighted", result.highlightedContent)
        assertEquals(100, result.score)
    }

    @Test
    fun `test getMatchSummary for data id match`() {
        val config = createConfig()
        val result = SearchResult(config, MatchType.DATA_ID, "highlighted", 100)

        assertEquals("Found in Data ID: test.properties", result.getMatchSummary())
    }

    @Test
    fun `test getMatchSummary for group match`() {
        val config = createConfig()
        val result = SearchResult(config, MatchType.GROUP, "highlighted", 90)

        assertEquals("Found in Group: DEFAULT_GROUP", result.getMatchSummary())
    }

    @Test
    fun `test getMatchSummary for tenant match`() {
        val config = createConfig()
        val result = SearchResult(config, MatchType.TENANT, "highlighted", 80)

        assertEquals("Found in Tenant: public", result.getMatchSummary())
    }

    @Test
    fun `test getMatchSummary for content match`() {
        val config = createConfig()
        val result = SearchResult(config, MatchType.CONTENT, "highlighted", 70)

        assertEquals("Found in Content", result.getMatchSummary())
    }

    @Test
    fun `test getMatchSummary for multiple matches`() {
        val config = createConfig()
        val result = SearchResult(config, MatchType.MULTIPLE, "highlighted", 110)

        assertEquals("Multiple matches found", result.getMatchSummary())
    }

    @Test
    fun `test getPriority ordering`() {
        assertEquals(1, SearchResult(createConfig(), MatchType.DATA_ID, "", 0).getPriority())
        assertEquals(2, SearchResult(createConfig(), MatchType.GROUP, "", 0).getPriority())
        assertEquals(3, SearchResult(createConfig(), MatchType.TENANT, "", 0).getPriority())
        assertEquals(4, SearchResult(createConfig(), MatchType.CONTENT, "", 0).getPriority())
        assertEquals(0, SearchResult(createConfig(), MatchType.MULTIPLE, "", 0).getPriority())
    }

    @Test
    fun `test default score`() {
        val config = createConfig()
        val result = SearchResult(config, MatchType.DATA_ID, "highlighted")

        assertEquals(0, result.score)
    }
}
