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
import com.nanyin.nacos.search.models.SearchCriteria
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.services.operations.Observed
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SearchCoverage
import com.nanyin.nacos.search.services.operations.searchCoverageFromCapability
import com.nanyin.nacos.search.services.visibility.AccessRefusalReason
import com.nanyin.nacos.search.services.visibility.ConfigurationVisibility
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
 * Service for handling real-time fuzzy search with pagination.
 *
 * It holds the session context every search targets (ADR-0046) and is given a
 * new one through [adoptSession] when the project session changes. Callers
 * express intent — [search], [searchAsYouType], [reload], [nextPage],
 * [previousPage], [changePageSize] — and render what [searchState] publishes.
 * Naming a target is this service's job, which is why assembling a
 * [SearchRequest] requires [SearchRequestAssembly].
 */
@Service(Service.Level.PROJECT)
@OptIn(SearchRequestAssembly::class)
class NacosSearchService(
    private val project: Project? = null,
    private val indexRequester: NamespaceIndexRequester? = null,
    private val apiProvider: () -> NacosApiService = {
        ApplicationManager.getApplication().getService(NacosApiService::class.java)
    }
) {
    /**
     * The constructor the platform instantiates this service through. Without an
     * arity-one overload it would pick the no-argument constructor Kotlin
     * generates for an all-defaults primary, leaving [project] null — and with
     * it no session epoch to fence results against (ADR-0010).
     */
    constructor(project: Project) : this(project, null)

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
    * The environment every search this service issues targets, and the
    * generation that names it. Held, never captured here: [adoptSession] hands
    * the context in from off the event dispatch thread when the project session
    * changes (ADR-0046).
    *
    * The two travel as one value because a search is judged by the generation
    * of the very session its request was built from. Reading them separately
    * would let an adoption land in between, stamping a request that names the
    * environment the user left with the generation of the one they moved to —
    * which is the misattribution this pair exists to prevent.
    */
   private class HeldSession(val generation: Long, val context: SearchSessionContext)

   @Volatile
   private var held = HeldSession(0L, SearchSessionContext.NONE)

    /**
     * Search request data class
     */
    @SearchRequestAssembly
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
         * Taken from the session context this service holds, which was captured
         * off the EDT when the project session changed (ADR-0046). Search keeps
         * using this environment even if the user switches profiles mid-flight
         * (issue #53).
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
             * observation sequence its read took (ADR-0047).
             *
             * Reported, not left for a view to act on: this state is published
             * only for a search that was still current when it finished, so a
             * view renders it rather than judging it a second time. What they
             * describe is which session the page belongs to and which server
             * read produced it.
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
     * Adopts [context] as the environment every subsequent search targets, and
     * returns true when that is a different target from the one held.
     *
     * A different target abandons what was on screen: the pending search is
     * cancelled and the published state returns to [SearchState.Idle], so a
     * result for the previous environment cannot be left standing under the new
     * one. The same target with a freshly captured operation context is not a
     * change — it is the same environment re-prepared — so it replaces the held
     * context without disturbing the current results.
     */
    @Synchronized
    fun adoptSession(context: SearchSessionContext): Boolean {
        val previous = held
        if (previous.context.addressesSameTargetAs(context)) {
            held = HeldSession(previous.generation, context)
            return false
        }
        held = HeldSession(previous.generation + 1, context)
        resetSearch()
        return true
    }

    /** The session context searches currently target. */
    fun sessionContext(): SearchSessionContext = held.context

    /**
     * Searches for [criteria] from the first page, under the held session.
     */
    suspend fun search(criteria: SearchCriteria) {
        val session = held
        performSearch(requestFor(criteria, session.context), apiProvider(), session)
    }

    /**
     * Debounced type-ahead search for [query]. Routed through the local index so
     * a partial data id or group matches — Nacos "accurate" mode only matches an
     * exact data id, which made typing a partial name return nothing.
     */
    fun searchAsYouType(query: String, coroutineScope: CoroutineScope) {
        val session = held
        val request = blankRequest(session.context)
            .copy(dataId = query, query = query, useRegex = true)
        searchWithDebounce(request, apiProvider(), coroutineScope, session)
    }

    /**
     * Drops the criteria and shows the held namespace's first page again.
     */
    suspend fun clearCriteria() {
        val session = held
        currentRequest = null
        performSearch(blankRequest(session.context), apiProvider(), session)
    }

    /**
     * Re-runs the current search under the held session, keeping the page and
     * criteria. [forceRefresh] bypasses the cache for this run only.
     */
    suspend fun reload(forceRefresh: Boolean = false) {
        val session = held
        val request = (currentRequest ?: blankRequest(session.context))
            .under(session.context)
            .copy(forceRefresh = forceRefresh)
        performSearch(request, apiProvider(), session)
    }

    /**
     * Performs debounced search
     */
   @SearchRequestAssembly
   fun searchWithDebounce(
       request: SearchRequest,
       nacosApiService: NacosApiService,
       coroutineScope: CoroutineScope
   ) = searchWithDebounce(request, nacosApiService, coroutineScope, held)

   private fun searchWithDebounce(
       request: SearchRequest,
       nacosApiService: NacosApiService,
       coroutineScope: CoroutineScope,
       session: HeldSession
   ) {
       // Cancel previous search and advance generation
       searchJob?.cancel()
       requestGeneration.incrementAndGet()

       searchJob = coroutineScope.launch {
            try {
                delay(searchDelayMs)
                performSearch(request, nacosApiService, session)
            } catch (e: CancellationException) {
                // Search was cancelled, ignore
            }
        }
    }

    /**
     * Performs immediate search without debouncing
     */
   @SearchRequestAssembly
   suspend fun performSearch(
       request: SearchRequest,
       nacosApiService: NacosApiService
   ) = performSearch(request, nacosApiService, held)

   /**
    * [session] is the held session [request] was built from, so this search is
    * judged by the generation of the environment it actually names — not by
    * whatever is held once it reaches this point.
    */
   private suspend fun performSearch(
       request: SearchRequest,
       nacosApiService: NacosApiService,
       session: HeldSession
   ) {
       // Cancel a *pending* debounced search that has not entered this method
       // yet. Do not cancel the current coroutine — searchWithDebounce calls
       // performSearch from inside searchJob after the delay, and cancelling
       // that job would abort at the next suspension (e.g. off-EDT context
       // capture on Dispatchers.IO).
       val currentJob = coroutineContext[Job]
       searchJob?.takeIf { it != currentJob }?.cancel()
       val generation = requestGeneration.incrementAndGet()
       val sessionAtStart = session.generation
       val sessionTicket = captureSessionTicket(request)
       try {
           _searchState.value = SearchState.Loading

           currentRequest = request
           val result = if (request.fullNamespaceTrigger() != null) {
               searchWithLocalIndex(request, nacosApiService)
           } else {
               searchWithRemoteList(request, nacosApiService)
           }
           publishIfCurrent(generation, sessionAtStart, result, request, sessionTicket)
       } catch (e: CancellationException) {
           throw e
       } catch (e: Exception) {
           if (generation == requestGeneration.get() &&
               sessionAtStart == held.generation &&
               sessionTicket?.isCurrent() != false
           ) {
               // Presentation owns the localised “Search failed” title; keep the
               // payload as the throwable’s message so English UI is not bilingual.
               _searchState.value = SearchState.Error(e.message ?: "Unknown search error", e)
           }
           logger.warn("Search error", e)
       }
   }

    /**
     * A first-page request under [session] with no criteria — what a namespace's
     * list shows before the user searches for anything.
     */
    private fun blankRequest(session: SearchSessionContext): SearchRequest =
        SearchRequest(pageNo = 1, pageSize = _paginationState.value.pageSize).under(session)

    private fun requestFor(
        criteria: SearchCriteria,
        session: SearchSessionContext
    ): SearchRequest = blankRequest(session).copy(
        dataId = criteria.dataId.ifBlank { criteria.query },
        group = criteria.group,
        query = criteria.query,
        searchContent = criteria.searchContent,
        caseSensitive = criteria.caseSensitive,
        useRegex = criteria.useRegex
    )

    /**
     * Re-targets a request at [session]. Every intent goes through this, so
     * criteria carried over from an earlier search can never keep pointing at
     * the environment or namespace that search was issued under.
     */
    private fun SearchRequest.under(session: SearchSessionContext): SearchRequest = copy(
        namespace = session.namespace,
        serverId = session.profileId,
        operationContext = session.operationContext
    )

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
                    coverageFor(request, context, response.totalCount, nacosApiService),
                    observed.observation
                )
            )
        }

        val remoteError = result.exceptionOrNull() ?: Exception("Unknown search error")
        // Cooperative cancel must not become SearchState.Error or fall through
        // to stale cache (issue #122 review).
        rethrowIfCancellation(remoteError)
        // A persisted access block (identity-wide auth or Namespace config-read
        // authz) outranks a later eligible transient failure: presentation stays
        // access-refused, not refresh-failed (issues #123 / #124).
        refusedAccessIfBlocked(context.identity, request.namespace?.namespaceId)
            ?.let { return Result.failure(it) }
        // ADR-0019 / issue #122: only eligible transient failures may surface
        // same-identity cache as REFRESH_FAILED. Refused access, configuration-
        // required, unsupported generation/capability, and cancellation return
        // their structured non-cache outcome.
        if (!StaleSearchFallbackPolicy.allowsStaleCache(remoteError)) {
            return Result.failure(remoteError)
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

        // Cache miss under a concurrent block (or restored block with no eligible
        // payload) must not look like an empty network failure.
        refusedAccessIfBlocked(context.identity, request.namespace?.namespaceId)
            ?.let { return Result.failure(it) }
        return Result.failure(remoteError)
    }

    private suspend fun searchWithLocalIndex(
        request: SearchRequest,
        nacosApiService: NacosApiService
    ): Result<SearchExecutionResult> {
        val context = resolveOperationContext(request).getOrElse { return Result.failure(it) }
        val namespaceId = request.namespace?.namespaceId
        val indexRequest = settings.captureNamespaceIndexRequest(namespaceId, context)
        val indexKey = indexRequest.key
        // Prefer a fresh index, then a persisted/expired STALE one, before any
        // full-namespace pagination (issue #147). forceRefresh still bypasses.
        val freshIndex = if (!request.forceRefresh && settings.cacheEnabled) {
            cacheService.getNamespaceIndexEntry(indexKey.identity, namespaceId)
        } else {
            null
        }
        val staleIndex = if (freshIndex == null && !request.forceRefresh && settings.cacheEnabled) {
            cacheService.getNamespaceIndexEntry(indexKey.identity, namespaceId, allowStale = true)
        } else {
            null
        }
        val cachedIndex = freshIndex ?: staleIndex
        val source: SearchSource
        // Carried alongside [source] so the index-backed path reports age from the
        // cached entry's own timestamp, exactly like the list-page path does
        // (issue #42 / ADR-0036). Without it a stale index rendered as WITHIN_TTL.
        val indexFetchedAtMillis: Long?
        val allConfigurations = if (cachedIndex != null) {
            source = if (freshIndex != null) SearchSource.CACHE else SearchSource.STALE_CACHE
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
                // Typed stopping cause when the index load failed or stopped mid-
                // pagination. IndexOutcome.Stale (front-end cutoff / cooldown) has
                // no remote refusal and may still use retained cache.
                val stoppingCause = when (outcome) {
                    is IndexOutcome.Failed -> outcome.error
                    is IndexOutcome.Partial -> outcome.stoppingCause
                    else -> null
                }
                if (stoppingCause != null) {
                    rethrowIfCancellation(stoppingCause)
                }
                // Identity-wide auth or Namespace config-read authz block: never
                // surface retained index data; keep structured refused-access
                // (issues #123 / #124).
                refusedAccessIfBlocked(indexKey.identity, namespaceId)?.let { return Result.failure(it) }
                val mayUseStale = stoppingCause == null ||
                    StaleSearchFallbackPolicy.allowsStaleCache(stoppingCause)
                if (mayUseStale) {
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
                        val error = stoppingCause
                            ?: IllegalStateException("Namespace index load did not produce a complete dataset")
                        rethrowIfCancellation(error)
                        refusedAccessIfBlocked(indexKey.identity, namespaceId)?.let { return Result.failure(it) }
                        return Result.failure(error)
                    }
                } else {
                    val error = stoppingCause
                        ?: IllegalStateException("Namespace index load did not produce a complete dataset")
                    rethrowIfCancellation(error)
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
                coverageForLocalIndex(request, context, filtered.size, allConfigurations.size, nacosApiService)
            )
        )
    }

   private fun publishIfCurrent(
       generation: Long,
       sessionAtStart: Long,
       result: Result<SearchExecutionResult>,
       request: SearchRequest,
       sessionTicket: OperationTicket?
   ) {
       // Drop results from superseded requests or obsolete session epochs. The
       // session generation covers the same ground for a project that has no
       // session epoch service to fence against — a headless one, or a test.
       if (generation != requestGeneration.get()) return
       if (sessionAtStart != held.generation) return
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
            // Presentation owns the localised “Search failed” title (issue #79).
            _searchState.value = SearchState.Error(error.message ?: "Unknown search error", error)
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
        totalCount: Int,
        nacosApiService: NacosApiService
    ): SearchCoverage? {
        if (!request.searchContent && request.query.isBlank()) return null
        val contentSearch = nacosApiService.protocolCapabilities(context.resolvedGeneration)?.contentSearch
            ?: return null
        return searchCoverageFromCapability(
            contentSearch,
            searched = totalCount,
            total = totalCount,
            limitedReason = "content search is coverage-limited"
        )
    }

    private fun coverageForLocalIndex(
        request: SearchRequest,
        context: NacosOperationContext,
        matched: Int,
        total: Int,
        nacosApiService: NacosApiService
    ): SearchCoverage? {
        if (!request.searchContent && request.query.isBlank()) return null
        val contentSearch = nacosApiService.protocolCapabilities(context.resolvedGeneration)?.contentSearch
            ?: return SearchCoverage.localComplete(matched, total)
        return searchCoverageFromCapability(
            contentSearch,
            searched = matched,
            total = total,
            limitedReason = "local index only"
        )
    }
    
    /**
     * Navigates to next page
     */
    suspend fun nextPage() {
        val nextPageNo = _paginationState.value.nextPage() ?: return
        goToPage(nextPageNo, _paginationState.value.pageSize)
    }

    /**
     * Navigates to previous page
     */
    suspend fun previousPage() {
        val prevPageNo = _paginationState.value.previousPage() ?: return
        goToPage(prevPageNo, _paginationState.value.pageSize)
    }

    /**
     * Changes page size and resets to first page. The new size is published
     * before the search runs, so it survives a search that fails and is the size
     * every later intent — including type-ahead — asks for.
     */
    suspend fun changePageSize(newPageSize: Int) {
        _paginationState.value = _paginationState.value.copy(currentPage = 1, pageSize = newPageSize)
        goToPage(1, newPageSize)
    }

    private suspend fun goToPage(pageNo: Int, pageSize: Int) {
        val session = held
        val request = (currentRequest ?: blankRequest(session.context)).under(session.context)
            .copy(pageNo = pageNo, pageSize = pageSize)
        performSearch(request, apiProvider(), session)
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

    /**
     * Cooperative cancel must not be published as [SearchState.Error] or fall
     * through to stale cache when a lower layer packed [CancellationException]
     * into [Result.failure] (issue #122).
     */
    private fun rethrowIfCancellation(error: Throwable) {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            if (current is CancellationException) throw current
            current = current.cause
            depth++
        }
    }

    /**
     * When a restored or still-active access block hides cache data — either
     * identity-wide authentication (#123) or Namespace-scoped configuration-read
     * authorization (#124) — searches must present structured access-refused
     * state rather than empty or refresh-failed results.
     */
    private fun refusedAccessIfBlocked(
        identity: AccessIdentity,
        namespaceId: String?
    ): RemoteOperationError? {
        return when (val decision = cacheService.configurationVisibility(identity, namespaceId)) {
            is ConfigurationVisibility.Visible -> null
            is ConfigurationVisibility.Blocked -> when (decision.reason) {
                AccessRefusalReason.AUTHENTICATION ->
                    RemoteOperationError.Authentication(401)
                AccessRefusalReason.AUTHORIZATION ->
                    RemoteOperationError.Authorization(403)
            }
        }
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
