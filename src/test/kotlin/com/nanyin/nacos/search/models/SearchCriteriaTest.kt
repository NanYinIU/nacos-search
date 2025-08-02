package com.nanyin.nacos.search.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

class SearchCriteriaTest {
    
    @Test
    fun `test SearchCriteria creation with default values`() {
        val criteria = SearchCriteria()
        
        assertEquals("", criteria.query)
        assertEquals("", criteria.namespaceId)
        assertEquals("", criteria.group)
        assertEquals("", criteria.dataId)
        assertFalse(criteria.useRegex)
        assertFalse(criteria.caseSensitive)
        assertTrue(criteria.searchContent)
        assertFalse(criteria.searchTags)
        assertEquals(100, criteria.limit)
        assertEquals(1, criteria.pageNo)
        assertEquals(20, criteria.pageSize)
    }
    
    @Test
    fun `test SearchCriteria creation with custom values`() {
        val criteria = SearchCriteria(
            query = "test query",
            namespaceId = "test-namespace",
            group = "test-group",
            dataId = "test-data-id",
            useRegex = true,
            caseSensitive = true,
            searchContent = false,
            searchTags = true,
            limit = 50,
            pageNo = 2,
            pageSize = 10
        )
        
        assertEquals("test query", criteria.query)
        assertEquals("test-namespace", criteria.namespaceId)
        assertEquals("test-group", criteria.group)
        assertEquals("test-data-id", criteria.dataId)
        assertTrue(criteria.useRegex)
        assertTrue(criteria.caseSensitive)
        assertFalse(criteria.searchContent)
        assertTrue(criteria.searchTags)
        assertEquals(50, criteria.limit)
        assertEquals(2, criteria.pageNo)
        assertEquals(10, criteria.pageSize)
    }
    
    @Test
    fun `test hasSearchCriteria with query`() {
        val criteria = SearchCriteria(query = "test")
        assertTrue(criteria.hasSearchCriteria())
    }
    
    @Test
    fun `test hasSearchCriteria with group`() {
        val criteria = SearchCriteria(group = "test-group")
        assertTrue(criteria.hasSearchCriteria())
    }
    
    @Test
    fun `test hasSearchCriteria with dataId`() {
        val criteria = SearchCriteria(dataId = "test-data-id")
        assertTrue(criteria.hasSearchCriteria())
    }
    
    @Test
    fun `test hasSearchCriteria with no criteria`() {
        val criteria = SearchCriteria()
        assertFalse(criteria.hasSearchCriteria())
    }
    
    @Test
    fun `test isEmpty with no criteria`() {
        val criteria = SearchCriteria()
        assertTrue(criteria.isEmpty())
    }
    
    @Test
    fun `test isEmpty with namespace only`() {
        val criteria = SearchCriteria(namespaceId = "test-namespace")
        assertFalse(criteria.isEmpty())
    }
    
    @Test
    fun `test isEmpty with query`() {
        val criteria = SearchCriteria(query = "test")
        assertFalse(criteria.isEmpty())
    }
    
    @Test
    fun `test toApiParams with minimal data`() {
        val criteria = SearchCriteria()
        val params = criteria.toApiParams()
        
        assertEquals("1", params["pageNo"])
        assertEquals("20", params["pageSize"])
        assertEquals(2, params.size)
    }
    
    @Test
    fun `test toApiParams with complete data`() {
        val criteria = SearchCriteria(
            query = "test query",
            namespaceId = "test-namespace",
            group = "test-group",
            dataId = "test-data-id",
            useRegex = true,
            pageNo = 2,
            pageSize = 10
        )
        
        val params = criteria.toApiParams()
        
        assertEquals("test-namespace", params["tenant"])
        assertEquals("test-data-id", params["dataId"])
        assertEquals("test-group", params["group"])
        assertEquals("accurate", params["search"])
        assertEquals("2", params["pageNo"])
        assertEquals("10", params["pageSize"])
    }
    
    @Test
    fun `test toApiParams with blur search`() {
        val criteria = SearchCriteria(
            query = "test query",
            useRegex = false
        )
        
        val params = criteria.toApiParams()
        
        assertEquals("blur", params["search"])
    }
    
    @Test
    fun `test clearSearch`() {
        val criteria = SearchCriteria(
            query = "test query",
            namespaceId = "test-namespace",
            group = "test-group",
            dataId = "test-data-id",
            pageNo = 3
        )
        
        val cleared = criteria.clearSearch()
        
        assertEquals("", cleared.query)
        assertEquals("test-namespace", cleared.namespaceId) // namespace preserved
        assertEquals("", cleared.group)
        assertEquals("", cleared.dataId)
        assertEquals(1, cleared.pageNo) // reset to first page
    }
    
    @Test
    fun `test withNamespaceOnly`() {
        val criteria = SearchCriteria(
            query = "test query",
            namespaceId = "old-namespace",
            group = "test-group",
            dataId = "test-data-id",
            limit = 50,
            pageSize = 10
        )
        
        val newCriteria = criteria.withNamespaceOnly("new-namespace")
        
        assertEquals("", newCriteria.query)
        assertEquals("new-namespace", newCriteria.namespaceId)
        assertEquals("", newCriteria.group)
        assertEquals("", newCriteria.dataId)
        assertEquals(50, newCriteria.limit) // preserved
        assertEquals(10, newCriteria.pageSize) // preserved
    }
    
    @Test
    fun `test nextPage`() {
        val criteria = SearchCriteria(pageNo = 2)
        val nextPage = criteria.nextPage()
        
        assertEquals(3, nextPage.pageNo)
        assertEquals(criteria.query, nextPage.query) // other fields preserved
    }
    
    @Test
    fun `test previousPage`() {
        val criteria = SearchCriteria(pageNo = 3)
        val previousPage = criteria.previousPage()
        
        assertEquals(2, previousPage.pageNo)
    }
    
    @Test
    fun `test previousPage at first page`() {
        val criteria = SearchCriteria(pageNo = 1)
        val previousPage = criteria.previousPage()
        
        assertEquals(1, previousPage.pageNo) // should not go below 1
    }
    
    @Test
    fun `test getDescription with no criteria`() {
        val criteria = SearchCriteria()
        assertEquals("无搜索条件", criteria.getDescription())
    }
    
    @Test
    fun `test getDescription with single criterion`() {
        val criteria = SearchCriteria(query = "test")
        assertEquals("关键词: test", criteria.getDescription())
    }
    
    @Test
    fun `test getDescription with multiple criteria`() {
        val criteria = SearchCriteria(
            query = "test",
            group = "test-group",
            dataId = "test-data-id",
            namespaceId = "test-namespace"
        )
        
        val description = criteria.getDescription()
        assertTrue(description.contains("关键词: test"))
        assertTrue(description.contains("分组: test-group"))
        assertTrue(description.contains("数据ID: test-data-id"))
        assertTrue(description.contains("命名空间: test-namespace"))
    }
    
    @Test
    fun `test default companion function`() {
        val criteria = SearchCriteria.default()
        
        assertEquals("", criteria.query)
        assertEquals("", criteria.namespaceId)
        assertEquals(1, criteria.pageNo)
        assertEquals(20, criteria.pageSize)
    }
    
    @Test
    fun `test forNamespace companion function`() {
        val criteria = SearchCriteria.forNamespace("test-namespace")
        
        assertEquals("test-namespace", criteria.namespaceId)
        assertEquals("", criteria.query)
        assertEquals("", criteria.group)
        assertEquals("", criteria.dataId)
    }
    
    @Test
    fun `test quickSearch companion function`() {
        val criteria = SearchCriteria.quickSearch("test query", "test-namespace")
        
        assertEquals("test query", criteria.query)
        assertEquals("test-namespace", criteria.namespaceId)
        assertTrue(criteria.searchContent)
    }
    
    @Test
    fun `test quickSearch companion function without namespace`() {
        val criteria = SearchCriteria.quickSearch("test query")
        
        assertEquals("test query", criteria.query)
        assertEquals("", criteria.namespaceId)
        assertTrue(criteria.searchContent)
    }
}