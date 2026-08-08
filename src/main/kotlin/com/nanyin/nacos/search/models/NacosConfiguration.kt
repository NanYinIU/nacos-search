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
     * 声明格式 — the type the user picked in the Nacos console, for display.
     *
     * Display surfaces only: the result list badge, the detail panel's format
     * tag and its inline metadata. It is not the 运行时格式 and must never
     * decide key extraction — no runtime reads this field, so a configuration
     * declared TEXT and named `app.properties` is parsed as properties at
     * runtime (see `RuntimeFormatDecision`, ADR-0055).
     *
     * The suffix fallback below is a display convenience for servers that
     * return no type at all (Nacos 1.4.x blur search does not select the
     * column), not a claim about how the body will be read. It deliberately
     * does not call the 运行时格式 table even though it asks a similar-looking
     * question: sharing would let a change to extraction silently move badges,
     * which is the coupling splitting the two formats removed.
     */
    fun declaredFormat(): String {
        return type ?: when {
            dataId.endsWith(".properties") -> "properties"
            dataId.endsWith(".yml") || dataId.endsWith(".yaml") -> "yaml"
            dataId.endsWith(".json") -> "json"
            dataId.endsWith(".xml") -> "xml"
            else -> "text"
        }
    }
}
