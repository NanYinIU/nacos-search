package com.nanyin.nacos.search.models

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
    
    val appName: String? = null,
    val desc: String? = null,
    val configTags: String? = null,
    val encryptedDataKey: String? = null,
    
    val lastModified: Long = System.currentTimeMillis()
) {
    /**
     * Generates a unique key for this configuration
     */
    fun getKey(): String {
        return "${dataId}:${group}:${tenantId ?: ""}"
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
