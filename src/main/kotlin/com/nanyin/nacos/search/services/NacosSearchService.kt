package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
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
    
    // Search debouncer
    private var searchJob: Job? = null
    private val searchDelayMs = 300L
    
    // Search state
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()
    
    // Pagination state , 缺少初始化
    private val _paginationState = MutableStateFlow(PaginationState())
    val paginationState: StateFlow<PaginationState> = _paginationState.asStateFlow()

    /**
     * Search request data class
     */
    data class SearchRequest(
        val dataId: String = "",
        val group: String = "",
        val appName: String = "",
        val configTags: String = "",
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
            val pagesAvailable: Int
        ) : SearchState()
        data class Error(val message: String, val throwable: Throwable? = null) : SearchState()
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
            
            val result = nacosApiService.listConfigurations(
                namespaceId = request.namespace?.namespaceId,
                pageNo = request.pageNo,
                pageSize = request.pageSize,
                dataId = request.dataId,
                group = request.group,
                appName = request.appName,
                configTags = request.configTags,
                searchMode = request.getSearchMode(),
                useCache = false // Don't use cache for search results
            )
            
            if (result.isSuccess) {
                val response = result.getOrNull()!!
                
                // Convert ConfigItems to NacosConfigurations
                val configurations = response.pageItems.map { item ->
                    nacosApiService.getConfigurationFromItem(item, useCache = true)
                }
                
                // Update pagination state
                _paginationState.value = PaginationState(
                    currentPage = response.pageNumber,
                    pageSize = request.pageSize,
                    totalCount = response.totalCount,
                    totalPages = response.pagesAvailable
                )
                
                // Update search state
                _searchState.value = SearchState.Success(
                    configurations = configurations,
                    totalCount = response.totalCount,
                    pageNumber = response.pageNumber,
                    pageSize = request.pageSize,
                    pagesAvailable = response.pagesAvailable
                )
                
                logger.info("Search completed: ${configurations.size} configurations found (page ${response.pageNumber}/${response.pagesAvailable})")
            } else {
                val error = result.exceptionOrNull() ?: Exception("Unknown search error")
                _searchState.value = SearchState.Error("搜索失败: ${error.message}", error)
                logger.warn("Search failed", error)
            }
        } catch (e: Exception) {
            _searchState.value = SearchState.Error("搜索过程中发生错误: ${e.message}", e)
            logger.warn("Search error", e)
        }
    }
    
    /**
     * Navigates to next page
     */
    suspend fun nextPage(
        currentRequest: SearchRequest,
        nacosApiService: NacosApiService
    ) {
        val nextPageNo = _paginationState.value.nextPage()
        val nextPageSize = _paginationState.value.pageSize
        if (nextPageNo != null) {
            val newRequest = currentRequest.copy(pageNo = nextPageNo, pageSize = nextPageSize)
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
            val newRequest = currentRequest.copy(pageNo = prevPageNo, pageSize = nextPageSize)
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
        val newRequest = currentRequest.copy(pageNo = 1, pageSize = newPageSize)
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
}