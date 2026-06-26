package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.settings.NacosSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service for handling real-time fuzzy search with pagination
 */
@Service(Service.Level.PROJECT)
class NacosSearchService {
    private val logger = thisLogger()
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
    
    // Search debouncer
    private var searchJob: Job? = null
    private val searchDelayMs = 300L
    
    // Search state
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()
    
    // Pagination state , 缺少初始化
    private val _paginationState = MutableStateFlow(PaginationState())
    val paginationState: StateFlow<PaginationState> = _paginationState.asStateFlow()
    private var currentRequest: SearchRequest? = null
    private var lastCompletedRequestKey: String? = null

    /**
     * Search request data class
     */
    data class SearchRequest(
        val dataId: String = "",
        val group: String = "",
        val appName: String = "",
        val configTags: String = "",
        val query: String = "",
        val searchContent: Boolean = false,
        val forceRefresh: Boolean = false,
        val caseSensitive: Boolean = false,
        val useRegex: Boolean = false,
        val namespace: NamespaceInfo? = null,
        val pageNo: Int = 1,
        val pageSize: Int = 10
    ) {
        /**
         * Determines if this is a fuzzy search based on dataId content
         */
        fun isFuzzySearch(): Boolean {
            return dataId.contains("*") || dataId.contains("?")
        }
        
        /**
         * Determines if this is a prefix fuzzy search (starts with *)
         */
        fun isPrefixFuzzySearch(): Boolean {
            return dataId.startsWith("*") && dataId.length > 1
        }
        
        /**
         * Determines if this is a wildcard-only search (just *)
         */
        fun isWildcardOnlySearch(): Boolean {
            return dataId.trim() == "*"
        }
        
        /**
         * Gets the processed dataId for API calls
         * For prefix searches like "*config", returns "config"
         * For wildcard-only searches, returns empty string
         */
        fun getProcessedDataId(): String {
            return when {
                isWildcardOnlySearch() -> ""
                isPrefixFuzzySearch() -> dataId.substring(1)
                else -> dataId
            }
        }
        
        /**
         * Gets the appropriate search mode for Nacos API
         */
        fun getSearchMode(): String {
            return if (isFuzzySearch()) "blur" else "accurate"
        }

        fun requiresLocalIndex(): Boolean {
            return searchContent || isWildcardOnlySearch() || isPrefixFuzzySearch() || useRegex
        }

        fun toCacheKey(): String {
            return listOf(
                "dataId=${getProcessedDataId()}",
                "group=$group",
                "appName=$appName",
                "configTags=$configTags",
                "query=$query",
                "searchContent=$searchContent",
                "caseSensitive=$caseSensitive",
                "useRegex=$useRegex",
                "search=${getSearchMode()}",
                "pageNo=$pageNo",
                "pageSize=$pageSize"
            ).joinToString("|")
        }
    }
    
    /**
     * Search state sealed class
     */
    sealed class SearchState {
        object Idle : SearchState()
        object Loading : SearchState()
        data class Success(
            val configurations: List<NacosConfiguration>,
            val totalCount: Int,
            val pageNumber: Int,
            val pageSize: Int,
            val pagesAvailable: Int,
            val source: SearchSource = SearchSource.REMOTE,
            val fromCache: Boolean = false
        ) : SearchState()
        data class Error(val message: String, val throwable: Throwable? = null) : SearchState()
    }

    enum class SearchSource {
        REMOTE,
        CACHE,
        STALE_CACHE
    }

    /**
     * Pagination state data class
     */
    data class PaginationState(
        val currentPage: Int = 1,
        val pageSize: Int = 10,
        val totalCount: Int = 0,
        val totalPages: Int = 0
    ) {
        val hasNextPage: Boolean
            get() = currentPage < totalPages
        
        val hasPreviousPage: Boolean
            get() = currentPage > 1
        
        fun nextPage(): Int? = if (hasNextPage) currentPage + 1 else null
        fun previousPage(): Int? = if (hasPreviousPage) currentPage - 1 else null
    }
    
    /**
     * Performs debounced search
     */
    fun searchWithDebounce(
        request: SearchRequest,
        nacosApiService: NacosApiService,
        coroutineScope: CoroutineScope
    ) {
        // Cancel previous search
        searchJob?.cancel()
        
        searchJob = coroutineScope.launch {
            try {
                delay(searchDelayMs)
                performSearch(request, nacosApiService)
            } catch (e: CancellationException) {
                // Search was cancelled, ignore
            }
        }
    }
    
    /**
     * Performs immediate search without debouncing
     */
    suspend fun performSearch(
        request: SearchRequest,
        nacosApiService: NacosApiService
    ) {
        try {
            _searchState.value = SearchState.Loading

            currentRequest = request
            val result = if (request.requiresLocalIndex()) {
                searchWithLocalIndex(request, nacosApiService)
            } else {
                searchWithRemoteList(request, nacosApiService)
            }
            publishResult(result, request)
        } catch (e: Exception) {
            _searchState.value = SearchState.Error("搜索过程中发生错误: ${e.message}", e)
            logger.warn("Search error", e)
        }
    }

    private suspend fun searchWithRemoteList(
        request: SearchRequest,
        nacosApiService: NacosApiService
    ): Result<SearchExecutionResult> {
        val requestKey = request.toCacheKey()
        val preferCache = !request.forceRefresh &&
                (request.pageNo > 1 || requestKey == lastCompletedRequestKey)
        if (preferCache && settings.cacheEnabled) {
            val cached = cacheService.getListPage(
                settings.serverUrl,
                request.namespace?.namespaceId,
                request.toApiListPageCacheKey()
            )
            if (cached != null) {
                val configurations = cached.pageItems.map { item ->
                    nacosApiService.getConfigurationFromItem(item, useCache = true)
                }
                lastCompletedRequestKey = requestKey
                return Result.success(SearchExecutionResult(cached, configurations, SearchSource.CACHE))
            }
        }
        val result = nacosApiService.listConfigurations(
            namespaceId = request.namespace?.namespaceId,
            pageNo = request.pageNo,
            pageSize = request.pageSize,
            dataId = request.getProcessedDataId(),
            group = request.group,
            appName = request.appName,
            configTags = request.configTags,
            searchMode = request.getSearchMode(),
            useCache = settings.cacheEnabled,
            forceRefresh = true
        )

        if (result.isSuccess) {
            val response = result.getOrNull()!!
            val configurations = response.pageItems.map { item ->
                nacosApiService.getConfigurationFromItem(item, useCache = true)
            }
            lastCompletedRequestKey = requestKey
            return Result.success(SearchExecutionResult(response, configurations, SearchSource.REMOTE))
        }

        val stale = cacheService.getListPage(
            settings.serverUrl,
            request.namespace?.namespaceId,
            request.toApiListPageCacheKey(),
            allowStale = true
        )
        if (stale != null) {
            val configurations = stale.pageItems.map { item ->
                nacosApiService.getConfigurationFromItem(item, useCache = true)
            }
            return Result.success(SearchExecutionResult(stale, configurations, SearchSource.STALE_CACHE))
        }

        return Result.failure(result.exceptionOrNull() ?: Exception("Unknown search error"))
    }

    private suspend fun searchWithLocalIndex(
        request: SearchRequest,
        nacosApiService: NacosApiService
    ): Result<SearchExecutionResult> {
        val namespaceId = request.namespace?.namespaceId
        val cachedIndex = if (!request.forceRefresh && settings.cacheEnabled) {
            cacheService.getNamespaceIndex(settings.serverUrl, namespaceId)
        } else {
            null
        }
        val source: SearchSource
        val allConfigurations = if (cachedIndex != null) {
            source = SearchSource.CACHE
            cachedIndex
        } else {
            val remote = nacosApiService.getAllConfigurations(namespaceId, useCache = true)
            if (remote.isSuccess) {
                source = SearchSource.REMOTE
                remote.getOrNull().orEmpty().also {
                    if (settings.cacheEnabled) {
                        cacheService.putNamespaceIndex(settings.serverUrl, namespaceId, it, settings.getCacheTtlMillis())
                    }
                }
            } else {
                val staleIndex = cacheService.getNamespaceIndex(settings.serverUrl, namespaceId, allowStale = true)
                if (staleIndex != null) {
                    source = SearchSource.STALE_CACHE
                    staleIndex
                } else {
                    return Result.failure(remote.exceptionOrNull() ?: Exception("Unknown search error"))
                }
            }
        }

        val filtered = allConfigurations.filter { it.matchesRequest(request) }
        val fromIndex = paginate(filtered, request.pageNo, request.pageSize)
        val response = NacosApiService.ConfigListResponse(
            totalCount = filtered.size,
            pageNumber = request.pageNo,
            pagesAvailable = calculateTotalPages(filtered.size, request.pageSize),
            pageItems = fromIndex.mapIndexed { index, config ->
                NacosApiService.ConfigItem(
                    id = "${request.pageNo}-$index",
                    dataId = config.dataId,
                    group = config.group,
                    content = config.content,
                    type = config.type,
                    tenant = config.tenantId
                )
            }
        )
        lastCompletedRequestKey = request.toCacheKey()
        return Result.success(SearchExecutionResult(response, fromIndex, source))
    }

    private fun publishResult(result: Result<SearchExecutionResult>, request: SearchRequest) {
        if (result.isSuccess) {
            val execution = result.getOrNull()!!
            _paginationState.value = PaginationState(
                currentPage = execution.response.pageNumber,
                pageSize = request.pageSize,
                totalCount = execution.response.totalCount,
                totalPages = execution.response.pagesAvailable
            )
            _searchState.value = SearchState.Success(
                configurations = execution.configurations,
                totalCount = execution.response.totalCount,
                pageNumber = execution.response.pageNumber,
                pageSize = request.pageSize,
                pagesAvailable = execution.response.pagesAvailable,
                source = execution.source,
                fromCache = execution.source != SearchSource.REMOTE
            )
            logger.info("Search completed: ${execution.configurations.size} configurations found (${execution.source})")
        } else {
            val error = result.exceptionOrNull() ?: Exception("Unknown search error")
            _searchState.value = SearchState.Error("搜索失败: ${error.message}", error)
            logger.warn("Search failed", error)
        }
    }
    
    /**
     * Navigates to next page
     */
    suspend fun nextPage(
        currentRequest: SearchRequest,
        nacosApiService: NacosApiService
    ) {
        var nextPageNo = _paginationState.value.nextPage()
        val nextPageSize = _paginationState.value.pageSize
        if (currentRequest.namespace != null) {
            if (_paginationState.value.totalCount != (currentRequest.namespace.configCount)) {
                // 刷新分页参数
                _paginationState.value = PaginationState(
                    currentPage = 1,
                    pageSize = 10,
                    totalCount = currentRequest.namespace.configCount,
                    totalPages = currentRequest.namespace.configCount / 10
                )
            }
            nextPageNo = _paginationState.value.nextPage()

        }

        if (nextPageNo != null) {
            val newRequest = (this.currentRequest ?: currentRequest).copy(pageNo = nextPageNo, pageSize = nextPageSize)
            performSearch(newRequest, nacosApiService)
        }
    }
    
    /**
     * Navigates to previous page
     */
    suspend fun previousPage(
        currentRequest: SearchRequest,
        nacosApiService: NacosApiService
    ) {
        val prevPageNo = _paginationState.value.previousPage()
        val nextPageSize = _paginationState.value.pageSize
        if (prevPageNo != null) {
            val newRequest = (this.currentRequest ?: currentRequest).copy(pageNo = prevPageNo, pageSize = nextPageSize)
            performSearch(newRequest, nacosApiService)
        }
    }
    
    /**
     * Changes page size and resets to first page
     */
    suspend fun changePageSize(
        currentRequest: SearchRequest,
        newPageSize: Int,
        nacosApiService: NacosApiService
    ) {
        val newRequest = (this.currentRequest ?: currentRequest).copy(pageNo = 1, pageSize = newPageSize)
        performSearch(newRequest, nacosApiService)
    }
    
    /**
     * Cancels current search
     */
    fun cancelSearch() {
        searchJob?.cancel()
        if (_searchState.value is SearchState.Loading) {
            _searchState.value = SearchState.Idle
        }
    }
    
    /**
     * Resets search state
     */
    fun resetSearch() {
        cancelSearch()
        _searchState.value = SearchState.Idle
        _paginationState.value = PaginationState()
        currentRequest = null
    }
    
    /**
     * Checks if search is currently active
     */
    fun isSearching(): Boolean {
        return _searchState.value is SearchState.Loading
    }
    
    /**
     * Gets current search results
     */
    fun getCurrentResults(): List<NacosConfiguration> {
        return when (val state = _searchState.value) {
            is SearchState.Success -> state.configurations
            else -> emptyList()
        }
    }

    private fun SearchRequest.toApiListPageCacheKey(): String {
        return listOf(
            "appName=$appName",
            "config_tags=$configTags",
            "dataId=${getProcessedDataId()}",
            "group=$group",
            "pageNo=$pageNo",
            "pageSize=$pageSize",
            "search=${getSearchMode()}"
        ).joinToString("|")
    }

    private fun NacosConfiguration.matchesRequest(request: SearchRequest): Boolean {
        fun String.containsPattern(pattern: String): Boolean {
            if (pattern.isBlank()) return true
            return if (request.useRegex) {
                try {
                    Regex(pattern, if (request.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
                        .containsMatchIn(this)
                } catch (_: Exception) {
                    contains(pattern, ignoreCase = !request.caseSensitive)
                }
            } else {
                contains(pattern, ignoreCase = !request.caseSensitive)
            }
        }

        val processedDataId = request.getProcessedDataId()
        if (processedDataId.isNotBlank() && !dataId.containsPattern(processedDataId)) return false
        if (request.group.isNotBlank() && !group.containsPattern(request.group)) return false
        if (request.query.isNotBlank()) {
            val targetMatches = dataId.containsPattern(request.query) ||
                    group.containsPattern(request.query) ||
                    (tenantId?.contains(request.query, ignoreCase = !request.caseSensitive) == true)
            val contentMatches = request.searchContent && content.containsPattern(request.query)
            if (!targetMatches && !contentMatches) return false
        }
        return true
    }

    private fun paginate(configurations: List<NacosConfiguration>, pageNo: Int, pageSize: Int): List<NacosConfiguration> {
        val from = ((pageNo - 1) * pageSize).coerceAtLeast(0)
        if (from >= configurations.size) return emptyList()
        return configurations.drop(from).take(pageSize)
    }

    private fun calculateTotalPages(totalCount: Int, pageSize: Int): Int {
        if (totalCount == 0) return 0
        return ((totalCount + pageSize - 1) / pageSize).coerceAtLeast(1)
    }

    private data class SearchExecutionResult(
        val response: NacosApiService.ConfigListResponse,
        val configurations: List<NacosConfiguration>,
        val source: SearchSource
    )
}
