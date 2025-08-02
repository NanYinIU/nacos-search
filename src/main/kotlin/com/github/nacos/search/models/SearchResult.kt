package com.github.nacos.search.models

/**
 * Represents a search result with highlighting information
 */
data class SearchResult(
    val configuration: NacosConfiguration,
    val matchType: MatchType,
    val highlightedContent: String,
    val score: Int = 0
) {
    /**
     * Returns a summary of the match for display
     */
    fun getMatchSummary(): String {
        return when (matchType) {
            MatchType.DATA_ID -> "Found in Data ID: ${configuration.dataId}"
            MatchType.GROUP -> "Found in Group: ${configuration.group}"
            MatchType.TENANT -> "Found in Tenant: ${configuration.tenantId}"
            MatchType.CONTENT -> "Found in Content"
            MatchType.MULTIPLE -> "Multiple matches found"
        }
    }
    
    /**
     * Returns the priority of this match type for sorting
     */
    fun getPriority(): Int {
        return when (matchType) {
            MatchType.DATA_ID -> 1
            MatchType.GROUP -> 2
            MatchType.TENANT -> 3
            MatchType.CONTENT -> 4
            MatchType.MULTIPLE -> 0
        }
    }
}

/**
 * Enum representing different types of matches in search results
 */
enum class MatchType {
    DATA_ID,
    GROUP,
    TENANT,
    CONTENT,
    MULTIPLE
}

/**
 * Represents a Nacos API response wrapper
 */
data class NacosApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T?
) {
    /**
     * Checks if the response indicates success
     */
    fun isSuccess(): Boolean = code == 0
    
    /**
     * Returns error message if response failed
     */
    fun getErrorMessage(): String {
        return if (isSuccess()) "" else "Error $code: $message"
    }
}

/**
 * Represents a cached item with expiration
 */
data class CachedItem<T>(
    val data: T,
    val timestamp: Long,
    val ttl: Long = 300_000L // 5 minutes default TTL
) {
    /**
     * Checks if this cached item has expired
     */
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - timestamp > ttl
    }
    
    /**
     * Returns the age of this cached item in milliseconds
     */
    fun getAge(): Long {
        return System.currentTimeMillis() - timestamp
    }
}