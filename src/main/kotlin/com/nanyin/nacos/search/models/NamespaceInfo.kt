package com.nanyin.nacos.search.models

/**
 * Nacos命名空间信息数据模型
 * 用于表示从Nacos服务器获取的命名空间数据
 */
data class NamespaceInfo(
    /**
     * 命名空间ID，唯一标识符
     */
    val namespaceId: String,
    
    /**
     * 命名空间显示名称
     */
    val namespaceName: String,
    
    /**
     * 命名空间描述信息
     */
    val namespaceDesc: String = "",
    
    /**
     * 配置数量
     */
    val configCount: Int = 0,
    
    /**
     * 命名空间类型：0-全局配置命名空间，1-用户自定义命名空间，2-保留命名空间
     */
    val type: Int = 1,
    
    /**
     * 命名空间状态：1-正常，0-禁用
     */
    val namespaceShowName: String = namespaceName
) {
    /**
     * 检查命名空间是否为公共命名空间
     */
    fun isPublicNamespace(): Boolean {
        return namespaceId.isEmpty() || namespaceId == "public"
    }
    
    /**
     * 获取显示用的命名空间名称
     * 如果是公共命名空间，返回"public"，否则返回实际名称
     */
    fun getDisplayName(): String {
        return if (isPublicNamespace()) {
            "public"
        } else {
            namespaceName.ifEmpty { namespaceId }
        }
    }
    
    /**
     * 获取命名空间的完整描述
     */
    fun getFullDescription(): String {
        return if (namespaceDesc.isNotEmpty()) {
            "${getDisplayName()} - $namespaceDesc"
        } else {
            getDisplayName()
        }
    }
    
    companion object {
        /** The one spelling of the public namespace. */
        const val PUBLIC = "public"

        /**
         * The canonical namespace id: null, blank and the literal `public` all
         * name the one public namespace (ADR-0015 / CONTEXT.md「Namespace 标识」).
         * Surrounding whitespace is trimmed first so it cannot create a
         * distinct cache coordinate across API generations.
         *
         * This is the single derivation. Cache keys, cache scopes,
         * protocol-adapter responses and the presentation judgement all go
         * through it, so a coordinate derived from a list row and one derived
         * from a detail read cannot differ by spelling alone.
         */
        fun canonicalId(namespaceId: String?): String =
            namespaceId?.trim()?.takeIf { it.isNotEmpty() && it != PUBLIC } ?: PUBLIC

        /**
         * Tenant field form used on configuration summaries, details and
         * history entries: the public Namespace is `null`, every other id is
         * the trimmed [canonicalId]. Discovery responses keep [canonicalId]
         * (`"public"`) instead.
         */
        fun canonicalTenantId(tenant: String?): String? =
            canonicalId(tenant).takeUnless { it == PUBLIC }

        /**
         * 创建公共命名空间实例
         */
        fun createPublicNamespace(): NamespaceInfo {
            return NamespaceInfo(
                namespaceId = "",
                namespaceName = "public",
                namespaceDesc = "Public namespace",
                type = 0
            )
        }
        
        /**
         * 从JSON响应创建NamespaceInfo实例
         */
        fun fromJsonMap(map: Map<String, Any?>): NamespaceInfo {
            return NamespaceInfo(
                namespaceId = map["namespace"]?.toString() ?: "",
                namespaceName = map["namespaceShowName"]?.toString() ?: "",
                namespaceDesc = map["namespaceDesc"]?.toString() ?: "",
                configCount = (map["configCount"] as? Number)?.toInt() ?: 0,
                type = (map["type"] as? Number)?.toInt() ?: 1
            )
        }
    }
}