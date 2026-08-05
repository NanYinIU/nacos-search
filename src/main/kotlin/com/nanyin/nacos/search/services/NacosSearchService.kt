package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.nanyin.nacos.search.models.CacheAgeCalculator
import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.operations.Observed
import com.nanyin.nacos.search.services.operations.SearchCoverage
import com.nanyin.nacos.search.services.operations.contentSearchCapability
import com.nanyin.nacos.search.services.operations.SearchCapability
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Service for handling real-time fuzzy search with pagination
 */
@Service(Service.Level.PROJECT)
class NacosSearchService(
    private val project: Project? = null,
    private val indexRequester: NamespaceIndexRequester? = null
) {
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

   // Latest-request-wins: every search increments this; late results from
   // older generations are silently dropped instead of overwriting current state.
   private val requestGeneration = AtomicLong(0)

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
        val pageSize: Int = 10,
        val serverId: String = "",
        /**
         * Captured by the project UI (off the EDT) before any cache or transport
         * operation. Search keeps using this environment even if the user switches
         * profiles mid-flight (issue #53).
         */
        val operationContext: NacosOperationContext? = null
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

        internal fun fullNamespaceTrigger(): IndexTrigger? =
            if (requiresLocalIndex()) IndexTrigger.SEARCH else null

        fun toCacheKey(): String {
            return listOf(
                "namespace=${namespace?.namespaceId.orEmpty()}",
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
            val confidence: CacheConfidence = CacheConfidence.remoteConfirmed(
                System.currentTimeMillis(),
                DatasetCompleteness.COMPLETE
            ),
            val coverage: SearchCoverage? = null,
            val fromCache: Boolean = false,
            /**
             * The project session's epoch when this search started, and the
             * observation sequence its read took. A view judges both before
             * painting the page (ADR-0047). Both this service and the view read
             * the epoch from the same project, so a search with no project
             * session to fence against reports 0 to a view that reads 0 too.
             */
            val sessionEpoch: Long = 0L,
            val observation: Long = Observed.NO_OBSERVATION
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
       // Cancel previous search and advance generation
       searchJob?.cancel()
       requestGeneration.incrementAndGet()

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
       // Cancel a *pending* debounced search that has not entered this method
       // yet. Do not cancel the current coroutine — searchWithDebounce calls
       // performSearch from inside searchJob after the delay, and cancelling
       // that job would abort at the next suspension (e.g. off-EDT context
       // capture on Dispatchers.IO).
       val currentJob = coroutineContext[Job]
       searchJob?.takeIf { it != currentJob }?.cancel()
       val generation = requestGeneration.incrementAndGet()
       val sessionTicket = captureSessionTicket(request)
       try {
           _searchState.value = SearchState.Loading

           currentRequest = request
           val result = if (request.fullNamespaceTrigger() != null) {
               searchWithLocalIndex(request, nacosApiService)
           } else {
               searchWithRemoteList(request, nacosApiService)
           }
           publishIfCurrent(generation, result, request, sessionTicket)
       } catch (e: CancellationException) {
           throw e
       } catch (e: Exception) {
           if (generation == requestGeneration.get() && sessionTicket?.isCurrent() != false) {
               _searchState.value = SearchState.Error("搜索过程中发生错误: ${e.message}", e)
           }
           logger.warn("Search error", e)
       }
   }

    private fun captureSessionTicket(request: SearchRequest): OperationTicket? {
        val owningProject = project ?: return null
        val epochs = owningProject.getService(ProjectSessionEpochs::class.java) ?: return null
        // Prefer the prepared context; fall back to credential-free identity
        // derivation so a missing snapshot never touches PasswordSafe on the
        // EDT (issue #53 / ADR-0039). When serverId is blank, use the project
        // session's selected profile rather than the app-wide activeServerId so
        // multi-project sessions stay isolated — and for the same reason the
        // resolved generation comes from this project's session (ADR-0053).
        val profileId = request.serverId.takeIf { it.isNotBlank() }
            ?: try {
                owningProject.getService(com.nanyin.nacos.search.settings.NacosProjectSession::class.java)
                    ?.sessionState?.selectedProfileId?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        val identity = request.operationContext?.identity
            ?: settings.captureAccessIdentity(profileId, ResolvedGenerationLocator.forProject(owningProject))
        return epochs.capture(identity)
    }

    /**
     * Resolves the operation context for a search. Prefers the prepared
     * [SearchRequest.operationContext] so mid-flight environment switches
     * cannot retarget the request — but only when that context's profile id
     * matches [SearchRequest.serverId]. A stale UI snapshot that still names
     * environment A while [serverId] is B is discarded and re-captured on
     * [Dispatchers.IO] for B (issue #53 race fix).
     */
    private suspend fun resolveOperationContext(request: SearchRequest): Result<NacosOperationContext> {
        val prepared = request.operationContext
        val expectedProfileId = request.serverId.takeIf { it.isNotBlank() }
        if (prepared != null &&
            (expectedProfileId == null || prepared.identity.profileId == expectedProfileId)
        ) {
            return Result.success(prepared)
        }
        if (prepared != null && expectedProfileId != null) {
            logger.warn(
                "Discarding prepared operation context for profile " +
                    "${prepared.identity.profileId}; search targets $expectedProfileId"
            )
        }
        return withContext(Dispatchers.IO) {
            settings.captureOperationContext(expectedProfileId)
        }
    }

    private suspend fun searchWithRemoteList(
        request: SearchRequest,
        nacosApiService: NacosApiService
    ): Result<SearchExecutionResult> {
        val context = resolveOperationContext(request).getOrElse { return Result.failure(it) }
        val requestKey = request.toCacheKey()
        val preferCache = !request.forceRefresh && settings.cacheEnabled
        if (preferCache) {
            val cached = cacheService.getListPageEntry(
                context.identity,
                request.namespace?.namespaceId,
                request.toApiListPageCacheKey()
            )
            if (cached != null) {
                val configurations = cached.data.pageItems.map {
                    it.toMetadataConfiguration(request.namespace?.namespaceId)
                }
                lastCompletedRequestKey = requestKey
                return Result.success(
                    SearchExecutionResult(
                        cached.data,
                        configurations,
                        SearchSource.CACHE,
                        confidenceFor(
                            SearchSource.CACHE,
                            configurations.size,
                            fetchedAtMillis = cached.createdAtMillis
                        )
                    )
                )
            }
        }
        val result = if (request.operationContext == null && request.serverId.isBlank()) {
            // Preserve the legacy API seam for callers that have not supplied
            // a project context; it still preflights above before this call.
            nacosApiService.listConfigurations(
                namespaceId = request.namespace?.namespaceId,
                pageNo = request.pageNo,
                pageSize = request.pageSize,
                dataId = request.getProcessedDataId(),
                group = request.group,
                appName = request.appName,
                configTags = request.configTags,
                searchMode = request.getSearchMode(),
                useCache = settings.cacheEnabled,
                forceRefresh = request.forceRefresh
            )
        } else {
            nacosApiService.listConfigurations(
                namespaceId = request.namespace?.namespaceId,
                pageNo = request.pageNo,
                pageSize = request.pageSize,
                dataId = request.getProcessedDataId(),
                group = request.group,
                appName = request.appName,
                configTags = request.configTags,
                searchMode = request.getSearchMode(),
                useCache = settings.cacheEnabled,
                forceRefresh = request.forceRefresh,
                operationContext = context
            )
        }

        if (result.isSuccess) {
            val observed = result.getOrNull()!!
            val response = observed.value
            val configurations = response.pageItems.map {
                it.toMetadataConfiguration(request.namespace?.namespaceId)
            }
            lastCompletedRequestKey = requestKey
            return Result.success(
                SearchExecutionResult(
                    response,
                    configurations,
                    SearchSource.REMOTE,
                    confidenceFor(SearchSource.REMOTE, configurations.size, fetchedAtMillis = null),
                    coverageFor(request, context, response.totalCount),
                    observed.observation
                )
            )
        }

        val stale = cacheService.getListPageEntry(
            context.identity,
            request.namespace?.namespaceId,
            request.toApiListPageCacheKey(),
            allowStale = true
        )
        if (stale != null) {
            val configurations = stale.data.pageItems.map {
                it.toMetadataConfiguration(request.namespace?.namespaceId)
            }
            return Result.success(
                SearchExecutionResult(
                    stale.data,
                    configurations,
                    SearchSource.STALE_CACHE,
                    confidenceFor(
                        SearchSource.STALE_CACHE,
                        configurations.size,
                        fetchedAtMillis = stale.createdAtMillis
                    )
                )
            )
        }

        return Result.failure(result.exceptionOrNull() ?: Exception("Unknown search error"))
    }

    private suspend fun searchWithLocalIndex(
        request: SearchRequest,
        nacosApiService: NacosApiService
    ): Result<SearchExecutionResult> {
        val context = resolveOperationContext(request).getOrElse { return Result.failure(it) }
        val namespaceId = request.namespace?.namespaceId
        val indexRequest = settings.captureNamespaceIndexRequest(namespaceId, context)
        val indexKey = indexRequest.key
        val cachedIndex = if (!request.forceRefresh && settings.cacheEnabled) {
            cacheService.getNamespaceIndexEntry(indexKey.identity, namespaceId)
        } else {
            null
        }
        val source: SearchSource
        // Carried alongside [source] so the index-backed path reports age from the
        // cached entry's own timestamp, exactly like the list-page path does
        // (issue #42 / ADR-0036). Without it a stale index rendered as WITHIN_TTL.
        val indexFetchedAtMillis: Long?
        val allConfigurations = if (cachedIndex != null) {
            source = SearchSource.CACHE
            indexFetchedAtMillis = cachedIndex.createdAtMillis
            cachedIndex.data
        } else {
            val coordinator = indexRequester
                ?: ApplicationManager.getApplication().getService(NamespaceIndexCoordinator::class.java)
            val outcome = coordinator.requestIndex(indexRequest, request.fullNamespaceTrigger()!!)
            if (outcome is IndexOutcome.Complete || outcome is IndexOutcome.Partial) {
                ApplicationManager.getApplication()
                    .getService(NavigationIndexRefreshService::class.java)
                    .refresh(indexKey.identity, null)
            }
            val loadedIndex = cacheService.getNamespaceIndexEntry(indexKey.identity, namespaceId)
            if (outcome is IndexOutcome.Complete && loadedIndex != null) {
                source = SearchSource.REMOTE
                indexFetchedAtMillis = loadedIndex.createdAtMillis
                loadedIndex.data
            } else {
                val staleIndex = cacheService.getNamespaceIndexEntry(
                    indexKey.identity,
                    namespaceId,
                    allowStale = true
                )
                if (staleIndex != null) {
                    source = SearchSource.STALE_CACHE
                    indexFetchedAtMillis = staleIndex.createdAtMillis
                    staleIndex.data
                } else {
                    val error = (outcome as? IndexOutcome.Failed)?.error
                        ?: IllegalStateException("Namespace index load did not produce a complete dataset")
                    return Result.failure(error)
                }
            }
        }

        val filtered = allConfigurations.filter { it.matchesRequest(request) }
        val fromIndex = paginate(filtered, request.pageNo, request.pageSize)
        val response = ConfigListResponse(
            totalCount = filtered.size,
            pageNumber = request.pageNo,
            pagesAvailable = calculateTotalPages(filtered.size, request.pageSize),
            pageItems = fromIndex.mapIndexed { index, config ->
                ConfigItem(
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
        return Result.success(
            SearchExecutionResult(
                response,
                fromIndex,
                source,
                confidenceFor(
                    source,
                    fromIndex.size,
                    filtered.size,
                    fetchedAtMillis = indexFetchedAtMillis
                ),
                coverageForLocalIndex(request, context, filtered.size, allConfigurations.size)
            )
        )
    }

   private fun publishIfCurrent(
       generation: Long,
       result: Result<SearchExecutionResult>,
       request: SearchRequest,
       sessionTicket: OperationTicket?
   ) {
       // Drop results from superseded requests or obsolete session epochs.
       if (generation != requestGeneration.get()) return
       if (sessionTicket != null && !sessionTicket.isCurrent()) return
       val sessionEpoch = sessionTicket?.capturedEpoch ?: 0L
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
                confidence = execution.confidence,
                coverage = execution.coverage,
                fromCache = execution.source != SearchSource.REMOTE,
                sessionEpoch = sessionEpoch,
                observation = execution.observation
            )
            logger.info("Search completed: ${execution.configurations.size} configurations found (${execution.source})")
        } else {
            val error = result.exceptionOrNull() ?: Exception("Unknown search error")
            _searchState.value = SearchState.Error("搜索失败: ${error.message}", error)
            logger.warn("Search failed", error)
        }
    }

    /**
     * [fetchedAtMillis] deliberately has no default: a cache-backed source that
     * forgets it would silently report WITHIN_TTL, which is the defect issue #42
     * exists to remove. Omitting it is a compile error; REMOTE passes null.
     */
    private fun confidenceFor(
        source: SearchSource,
        pageCount: Int,
        totalCount: Int = pageCount,
        fetchedAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): CacheConfidence {
        val completeness = if (totalCount > 0 && pageCount < totalCount) {
            DatasetCompleteness.PARTIAL
        } else {
            DatasetCompleteness.COMPLETE
        }
        // Age is derived from the cached entry's own creation time, not from
        // the moment this result is assembled (issue #42 / ADR-0036).
        val age = CacheAgeCalculator.compute(
            fetchedAtMillis,
            nowMillis,
            settings.getCacheTtlMillis()
        )
        return when (source) {
            SearchSource.REMOTE -> CacheConfidence.remoteConfirmed(nowMillis, completeness)
            SearchSource.CACHE -> CacheConfidence.restoredUnconfirmed(
                completeness,
                age,
                fetchedAtMillis
            )
            SearchSource.STALE_CACHE -> CacheConfidence.refreshFailed(
                completeness,
                age,
                fetchedAtMillis
            )
        }
    }

    private fun coverageFor(
        request: SearchRequest,
        context: NacosOperationContext,
        totalCount: Int
    ): SearchCoverage? {
        if (!request.searchContent && request.query.isBlank()) return null
        return when (contentSearchCapability(context.resolvedGeneration)) {
            SearchCapability.SERVER_SIDE -> SearchCoverage.complete(totalCount, totalCount)
            SearchCapability.COVERAGE_LIMITED -> SearchCoverage.partial(
                totalCount,
                totalCount,
                "V1 content search is coverage-limited"
            )
            SearchCapability.UNKNOWN -> null
        }
    }

    private fun coverageForLocalIndex(
        request: SearchRequest,
        context: NacosOperationContext,
        matched: Int,
        total: Int
    ): SearchCoverage? {
        if (!request.searchContent && request.query.isBlank()) return null
        return when (contentSearchCapability(context.resolvedGeneration)) {
            SearchCapability.SERVER_SIDE -> SearchCoverage.complete(matched, total)
            SearchCapability.COVERAGE_LIMITED -> SearchCoverage.partial(
                matched,
                total,
                "V1 local index only"
            )
            SearchCapability.UNKNOWN -> SearchCoverage.localComplete(matched, total)
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
        val response: ConfigListResponse,
        val configurations: List<NacosConfiguration>,
        val source: SearchSource,
        val confidence: CacheConfidence,
        val coverage: SearchCoverage? = null,
        /**
         * The observation sequence the read took, or [Observed.NO_OBSERVATION]
         * when this page came from the cache or the namespace index — neither
         * of which observed the server on this caller's behalf.
         */
        val observation: Long = Observed.NO_OBSERVATION
    )

    private fun ConfigItem.toMetadataConfiguration(
        requestNamespaceId: String? = null
    ): NacosConfiguration {
        val fromItem = tenant?.takeUnless { it.isBlank() }
        val fromRequest = requestNamespaceId?.takeUnless {
            it.isBlank() || it == "public"
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
