package com.nanyin.nacos.search.models

/**
 * 搜索条件数据类
 * 用于封装配置搜索和过滤的各种参数
 */
data class SearchCriteria(
    /**
     * 搜索关键词
     */
    val query: String = "",
    
    /**
     * 目标命名空间ID
     */
    val namespaceId: String = "",
    
    /**
     * 配置分组过滤
     */
    val group: String = "",
    
    /**
     * 数据ID过滤
     */
    val dataId: String = "",
    
    /**
     * 是否启用正则表达式搜索
     */
    val useRegex: Boolean = false,
    
    /**
     * 是否区分大小写
     */
    val caseSensitive: Boolean = false,
    
    /**
     * 是否搜索配置内容
     */
    val searchContent: Boolean = true,
    
    /**
     * 是否搜索配置标签
     */
    val searchTags: Boolean = false,
    
    /**
     * 搜索结果限制数量
     */
    val limit: Int = 100,
    
    /**
     * 分页页码（从1开始）
     */
    val pageNo: Int = 1,
    
    /**
     * 每页大小
     */
    val pageSize: Int = 20
) {
    
    /**
     * 检查是否有有效的搜索条件
     */
    fun hasSearchCriteria(): Boolean {
        return query.isNotBlank() || 
               group.isNotBlank() || 
               dataId.isNotBlank()
    }
    
    /**
     * 检查是否为空搜索（无任何过滤条件）
     */
    fun isEmpty(): Boolean {
        return query.isBlank() && 
               group.isBlank() && 
               dataId.isBlank() && 
               namespaceId.isBlank()
    }
    
    /**
     * 创建用于API调用的参数映射
     */
    fun toApiParams(): Map<String, String> {
        val params = mutableMapOf<String, String>()
        
        if (namespaceId.isNotBlank()) {
            params["tenant"] = namespaceId
        }
        
        if (dataId.isNotBlank()) {
            params["dataId"] = dataId
        }
        
        if (group.isNotBlank()) {
            params["group"] = group
        }
        
        if (query.isNotBlank()) {
            params["search"] = if (useRegex) "accurate" else "blur"
        }
        
        params["pageNo"] = pageNo.toString()
        params["pageSize"] = pageSize.toString()
        
        return params
    }
    
    /**
     * 创建清空搜索条件的副本，保留命名空间
     */
    fun clearSearch(): SearchCriteria {
        return copy(
            query = "",
            group = "",
            dataId = "",
            pageNo = 1
        )
    }
    
    /**
     * 创建仅保留命名空间的副本
     */
    fun withNamespaceOnly(newNamespaceId: String): SearchCriteria {
        return SearchCriteria(
            namespaceId = newNamespaceId,
            limit = limit,
            pageSize = pageSize
        )
    }

    /**
     * 创建下一页的搜索条件
     */
    fun nextPage(): SearchCriteria {
        return copy(pageNo = pageNo + 1)
    }
    
    /**
     * 创建上一页的搜索条件
     */
    fun previousPage(): SearchCriteria {
        return copy(pageNo = maxOf(1, pageNo - 1))
    }
    
    /**
     * 获取搜索条件的描述文本
     */
    fun getDescription(): String {
        val parts = mutableListOf<String>()
        
        if (query.isNotBlank()) {
            parts.add("关键词: $query")
        }
        
        if (group.isNotBlank()) {
            parts.add("分组: $group")
        }
        
        if (dataId.isNotBlank()) {
            parts.add("数据ID: $dataId")
        }
        
        if (namespaceId.isNotBlank()) {
            parts.add("命名空间: $namespaceId")
        }
        
        return if (parts.isEmpty()) {
            "无搜索条件"
        } else {
            parts.joinToString(", ")
        }
    }
    
    companion object {
        /**
         * 创建默认搜索条件
         */
        fun default(): SearchCriteria {
            return SearchCriteria()
        }
        
        /**
         * 创建仅包含命名空间的搜索条件
         */
        fun forNamespace(namespaceId: String): SearchCriteria {
            return SearchCriteria(namespaceId = namespaceId)
        }
        
        /**
         * 创建快速搜索条件
         */
        fun quickSearch(query: String, namespaceId: String = ""): SearchCriteria {
            return SearchCriteria(
                query = query,
                namespaceId = namespaceId,
                searchContent = true
            )
        }
    }
}