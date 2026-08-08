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
) {
    /**
     * Configuration metadata from a list item, carrying the Namespace the item
     * was actually read under. Prefer a non-blank [tenant] from the response;
     * when the body carries none, stamp [requestNamespaceId] so a non-public
     * load is not treated as public (issue #191). Public / blank request yields
     * null so the configuration field contract stays stable.
     *
     * Shared by search and namespace-load so the two paths cannot drift.
     */
    fun toMetadataConfiguration(requestNamespaceId: String? = null): NacosConfiguration {
        val fromItem = tenant?.takeUnless { it.isBlank() }
        val fromRequest = requestNamespaceId?.takeUnless {
            it.isBlank() || it == NamespaceInfo.PUBLIC
        }
        return NacosConfiguration(
            dataId = dataId,
            group = group,
            tenantId = fromItem ?: fromRequest,
            content = content.orEmpty(),
            type = type
        )
    }
}
