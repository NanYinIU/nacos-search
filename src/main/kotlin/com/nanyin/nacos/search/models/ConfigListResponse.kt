package com.nanyin.nacos.search.models

/**
 * Persisted list-page payload for the configuration summary cache.
 *
 * Field names are the on-disk JSON contract: existing cache files continue to
 * deserialize by field name. A file that does not is handled as a cache miss.
 * Lives outside the read module so the cache format does not depend on a module
 * being retired (issue #54).
 */
data class ConfigListResponse(
    val totalCount: Int,
    val pageNumber: Int,
    val pagesAvailable: Int,
    val pageItems: List<ConfigItem>
)

data class ConfigItem(
    val id: String,
    val dataId: String,
    val group: String,
    val content: String?,
    val type: String?,
    val tenant: String?
)
