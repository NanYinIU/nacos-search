package com.github.nacos.search.models

import com.google.gson.annotations.SerializedName

/**
 * Represents a Nacos configuration entry
 */
data class NacosConfiguration(
    @SerializedName("dataId")
    val dataId: String,
    
    @SerializedName("group")
    val group: String,
    
    @SerializedName("tenant")
    val tenantId: String? = null,
    
    @SerializedName("content")
    val content: String,
    
    @SerializedName("type")
    val type: String? = null,
    
    @SerializedName("md5")
    val md5: String? = null,
    
    val lastModified: Long = System.currentTimeMillis()
) {
    /**
     * Generates a unique key for this configuration
     */
    fun getKey(): String {
        return "${dataId}:${group}:${tenantId ?: ""}"
    }
    
    /**
     * Checks if this configuration matches the search query
     */
    fun matches(query: String, ignoreCase: Boolean = true): Boolean {
        if (query.isBlank()) return true
        
        return dataId.contains(query, ignoreCase) ||
               group.contains(query, ignoreCase) ||
               content.contains(query, ignoreCase) ||
               (tenantId?.contains(query, ignoreCase) == true)
    }
    
    /**
     * Returns a display name for this configuration
     */
    fun getDisplayName(): String {
        return if (tenantId.isNullOrBlank()) {
            "$dataId ($group)"
        } else {
            "$dataId ($group) [$tenantId]"
        }
    }
    
    /**
     * Returns the configuration type or infers it from dataId
     */
    fun getConfigType(): String {
        return type ?: when {
            dataId.endsWith(".properties") -> "properties"
            dataId.endsWith(".yml") || dataId.endsWith(".yaml") -> "yaml"
            dataId.endsWith(".json") -> "json"
            dataId.endsWith(".xml") -> "xml"
            else -> "text"
        }
    }
}