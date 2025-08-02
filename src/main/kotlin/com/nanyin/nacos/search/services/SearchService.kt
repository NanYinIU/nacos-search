package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.MatchType
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.SearchResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * Service for searching through cached Nacos configurations
 */
@Service(Service.Level.APP)
class SearchService {
    private val logger = thisLogger()
    private val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
    
    companion object {
        private const val HIGHLIGHT_START = "<mark>"
        private const val HIGHLIGHT_END = "</mark>"
        private const val CONTENT_PREVIEW_LENGTH = 200
    }
    
    /**
     * Searches configurations by query string
     */
    suspend fun searchConfigurations(
        query: String,
        groupFilter: String? = null,
        tenantFilter: String? = null,
        contentOnly: Boolean = false
    ): List<SearchResult> = withContext(Dispatchers.Default) {
        if (query.isBlank()) {
            return@withContext emptyList()
        }
        
        logger.debug("Searching for: '$query' with filters - group: $groupFilter, tenant: $tenantFilter")
        
        val configurations = cacheService.getAllCachedConfigurations()
        val filteredConfigs = applyFilters(configurations, groupFilter, tenantFilter)
        
        val results = mutableListOf<SearchResult>()
        
        filteredConfigs.forEach { config ->
            val matches = findMatches(config, query, contentOnly)
            results.addAll(matches)
        }
        
        // Sort by relevance (priority, then by score)
        results.sortedWith(compareBy<SearchResult> { it.getPriority() }.thenByDescending { it.score })
    }
    
    /**
     * Searches configurations by data ID
     */
    suspend fun searchByDataId(query: String): List<SearchResult> = withContext(Dispatchers.Default) {
        val configurations = cacheService.getAllCachedConfigurations()
        val results = mutableListOf<SearchResult>()
        
        configurations.forEach { config ->
            if (config.dataId.contains(query, ignoreCase = true)) {
                val highlighted = highlightText(config.dataId, query)
                val score = calculateScore(config.dataId, query, MatchType.DATA_ID)
                results.add(SearchResult(config, MatchType.DATA_ID, highlighted, score))
            }
        }
        
        results.sortedByDescending { it.score }
    }
    
    /**
     * Searches configurations by group
     */
    suspend fun searchByGroup(query: String): List<SearchResult> = withContext(Dispatchers.Default) {
        val configurations = cacheService.getAllCachedConfigurations()
        val results = mutableListOf<SearchResult>()
        
        configurations.forEach { config ->
            if (config.group.contains(query, ignoreCase = true)) {
                val highlighted = highlightText(config.group, query)
                val score = calculateScore(config.group, query, MatchType.GROUP)
                results.add(SearchResult(config, MatchType.GROUP, highlighted, score))
            }
        }
        
        results.sortedByDescending { it.score }
    }
    
    /**
     * Searches configurations by content
     */
    suspend fun searchByContent(query: String): List<SearchResult> = withContext(Dispatchers.Default) {
        val configurations = cacheService.getAllCachedConfigurations()
        val results = mutableListOf<SearchResult>()
        
        configurations.forEach { config ->
            if (config.content.contains(query, ignoreCase = true)) {
                val highlighted = highlightContentPreview(config.content, query)
                val score = calculateScore(config.content, query, MatchType.CONTENT)
                results.add(SearchResult(config, MatchType.CONTENT, highlighted, score))
            }
        }
        
        results.sortedByDescending { it.score }
    }
    
    /**
     * Searches configurations using regex pattern
     */
    suspend fun searchByRegex(
        pattern: String,
        groupFilter: String? = null,
        tenantFilter: String? = null
    ): List<SearchResult> = withContext(Dispatchers.Default) {
        try {
            val regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
            val configurations = cacheService.getAllCachedConfigurations()
            val filteredConfigs = applyFilters(configurations, groupFilter, tenantFilter)
            val results = mutableListOf<SearchResult>()
            
            filteredConfigs.forEach { config ->
                val matches = findRegexMatches(config, regex)
                results.addAll(matches)
            }
            
            results.sortedWith(compareBy<SearchResult> { it.getPriority() }.thenByDescending { it.score })
        } catch (e: Exception) {
            logger.warn("Invalid regex pattern: $pattern", e)
            emptyList()
        }
    }
    
    /**
     * Gets search suggestions based on cached configurations
     */
    suspend fun getSearchSuggestions(query: String, limit: Int = 10): List<String> = withContext(Dispatchers.Default) {
        if (query.length < 2) return@withContext emptyList()
        
        val configurations = cacheService.getAllCachedConfigurations()
        val suggestions = mutableSetOf<String>()
        
        configurations.forEach { config ->
            // Add dataId suggestions
            if (config.dataId.contains(query, ignoreCase = true)) {
                suggestions.add(config.dataId)
            }
            
            // Add group suggestions
            if (config.group.contains(query, ignoreCase = true)) {
                suggestions.add(config.group)
            }
            
            // Add tenant suggestions
            config.tenantId?.let { tenant ->
                if (tenant.contains(query, ignoreCase = true)) {
                    suggestions.add(tenant)
                }
            }
        }
        
        suggestions.take(limit)
    }
    
    /**
     * Gets unique groups from cached configurations
     */
    suspend fun getAvailableGroups(): List<String> = withContext(Dispatchers.Default) {
        val configurations = cacheService.getAllCachedConfigurations()
        configurations.map { it.group }.distinct().sorted()
    }
    
    /**
     * Gets unique tenants from cached configurations
     */
    suspend fun getAvailableTenants(): List<String> = withContext(Dispatchers.Default) {
        val configurations = cacheService.getAllCachedConfigurations()
        configurations.mapNotNull { it.tenantId }.distinct().sorted()
    }
    
    private fun findMatches(
        config: NacosConfiguration,
        query: String,
        contentOnly: Boolean
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val matchTypes = mutableSetOf<MatchType>()
        
        if (!contentOnly) {
            // Check dataId match
            if (config.dataId.contains(query, ignoreCase = true)) {
                val highlighted = highlightText(config.dataId, query)
                val score = calculateScore(config.dataId, query, MatchType.DATA_ID)
                results.add(SearchResult(config, MatchType.DATA_ID, highlighted, score))
                matchTypes.add(MatchType.DATA_ID)
            }
            
            // Check group match
            if (config.group.contains(query, ignoreCase = true)) {
                val highlighted = highlightText(config.group, query)
                val score = calculateScore(config.group, query, MatchType.GROUP)
                results.add(SearchResult(config, MatchType.GROUP, highlighted, score))
                matchTypes.add(MatchType.GROUP)
            }
            
            // Check tenant match
            config.tenantId?.let { tenant ->
                if (tenant.contains(query, ignoreCase = true)) {
                    val highlighted = highlightText(tenant, query)
                    val score = calculateScore(tenant, query, MatchType.TENANT)
                    results.add(SearchResult(config, MatchType.TENANT, highlighted, score))
                    matchTypes.add(MatchType.TENANT)
                }
            }
        }
        
        // Check content match
        if (config.content.contains(query, ignoreCase = true)) {
            val highlighted = highlightContentPreview(config.content, query)
            val score = calculateScore(config.content, query, MatchType.CONTENT)
            results.add(SearchResult(config, MatchType.CONTENT, highlighted, score))
            matchTypes.add(MatchType.CONTENT)
        }
        
        // If multiple matches, create a combined result
        if (matchTypes.size > 1) {
            val combinedHighlight = "Multiple matches: ${matchTypes.joinToString(", ")}"
            val combinedScore = results.maxOfOrNull { it.score } ?: 0
            results.add(SearchResult(config, MatchType.MULTIPLE, combinedHighlight, combinedScore))
        }
        
        return results
    }
    
    private fun findRegexMatches(config: NacosConfiguration, regex: Pattern): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        
        // Check dataId
        if (regex.matcher(config.dataId).find()) {
            results.add(SearchResult(config, MatchType.DATA_ID, config.dataId, 100))
        }
        
        // Check group
        if (regex.matcher(config.group).find()) {
            results.add(SearchResult(config, MatchType.GROUP, config.group, 90))
        }
        
        // Check tenant
        config.tenantId?.let { tenant ->
            if (regex.matcher(tenant).find()) {
                results.add(SearchResult(config, MatchType.TENANT, tenant, 80))
            }
        }
        
        // Check content
        if (regex.matcher(config.content).find()) {
            val preview = getContentPreview(config.content)
            results.add(SearchResult(config, MatchType.CONTENT, preview, 70))
        }
        
        return results
    }
    
    private fun applyFilters(
        configurations: List<NacosConfiguration>,
        groupFilter: String?,
        tenantFilter: String?
    ): List<NacosConfiguration> {
        var filtered = configurations
        
        groupFilter?.let { group ->
            if (group.isNotBlank()) {
                filtered = filtered.filter { it.group.equals(group, ignoreCase = true) }
            }
        }
        
        tenantFilter?.let { tenant ->
            if (tenant.isNotBlank()) {
                filtered = filtered.filter { it.tenantId?.equals(tenant, ignoreCase = true) == true }
            }
        }
        
        return filtered
    }
    
    private fun highlightText(text: String, query: String): String {
        if (query.isBlank()) return text
        
        return try {
            val pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE)
            pattern.matcher(text).replaceAll("${Companion.HIGHLIGHT_START}$0${Companion.HIGHLIGHT_END}")
        } catch (e: Exception) {
            text
        }
    }
    
    private fun highlightContentPreview(content: String, query: String): String {
        if (query.isBlank()) return getContentPreview(content)
        
        val queryIndex = content.indexOf(query, ignoreCase = true)
        if (queryIndex == -1) return getContentPreview(content)
        
        // Get context around the match
        val start = maxOf(0, queryIndex - Companion.CONTENT_PREVIEW_LENGTH / 2)
        val end = minOf(content.length, queryIndex + query.length + Companion.CONTENT_PREVIEW_LENGTH / 2)
        
        var preview = content.substring(start, end)
        if (start > 0) preview = "...$preview"
        if (end < content.length) preview = "$preview..."
        
        return highlightText(preview, query)
    }
    
    private fun getContentPreview(content: String): String {
        return if (content.length <= Companion.CONTENT_PREVIEW_LENGTH) {
            content
        } else {
            content.take(Companion.CONTENT_PREVIEW_LENGTH) + "..."
        }
    }
    
    private fun calculateScore(text: String, query: String, matchType: MatchType): Int {
        var score = when (matchType) {
            MatchType.DATA_ID -> 100
            MatchType.GROUP -> 90
            MatchType.TENANT -> 80
            MatchType.CONTENT -> 70
            MatchType.MULTIPLE -> 110
        }
        
        // Boost score for exact matches
        if (text.equals(query, ignoreCase = true)) {
            score += 50
        }
        
        // Boost score for matches at the beginning
        if (text.startsWith(query, ignoreCase = true)) {
            score += 25
        }
        
        // Reduce score based on text length (shorter matches are more relevant)
        val lengthPenalty = (text.length / 100).coerceAtMost(20)
        score -= lengthPenalty
        
        return maxOf(1, score)
    }
}