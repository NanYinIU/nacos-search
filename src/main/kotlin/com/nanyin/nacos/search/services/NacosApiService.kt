package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NamespaceLoadResult
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.network.RequestPolicy
import com.nanyin.nacos.search.services.operations.CacheServiceOperationCache
import com.nanyin.nacos.search.services.operations.ConfigurationCoordinate
import com.nanyin.nacos.search.services.operations.ConnectionDiagnostic
import com.nanyin.nacos.search.services.operations.DiagnosticReport
import com.nanyin.nacos.search.services.operations.DiagnosticSnapshot
import com.nanyin.nacos.search.services.operations.HistoryDetail
import com.nanyin.nacos.search.services.operations.HistoryPage
import com.nanyin.nacos.search.services.operations.HistoryQuery
import com.nanyin.nacos.search.services.operations.NacosRequestExecutorProtocolTransport
import com.nanyin.nacos.search.services.operations.Observed
import com.nanyin.nacos.search.services.operations.ObservationSequence
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.services.operations.ProtocolCapabilities
import com.nanyin.nacos.search.services.operations.SummaryPage
import com.nanyin.nacos.search.services.operations.SummaryQuery
import com.nanyin.nacos.search.services.operations.V1ProtocolAdapter
import com.nanyin.nacos.search.services.operations.GenerationProbeFlight
import com.nanyin.nacos.search.services.operations.GenerationProbeKey
import com.nanyin.nacos.search.services.operations.GenerationResolver
import com.nanyin.nacos.search.services.operations.GenerationSessionCapture
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.services.operations.V3ProtocolAdapter
import com.nanyin.nacos.search.services.visibility.NoOpVisibilityReporter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.*

/**
 * Application-level facade over the operation gateway for Nacos reads and writes.
 *
 * Every generation (locked V1/V3 and AUTO) resolves through [resolvedReadTarget]
 * and dispatches through [OperationGateway]. There is no parallel wire path:
 * authentication, retry, observation sequence, and cache writes all live at the
 * gateway/adapter seam (issue #54).
 */
@Service(Service.Level.APP)
class NacosApiService(
    private val v1GatewayOverride: OperationGateway? = null,
    /**
     * Test seam: override the request executor so tests can observe every
     * outgoing HTTP request through the gateway path. Production uses the
     * default transport.
     */
    private val executorOverride: NacosRequestExecutor? = null,
    /**
     * Test seam: inject a project-session generation owner. Production looks
     * up [ProjectSessionEpochs] for the active profile, or falls back to an
     * in-memory default session for app-level callers without an open project.
     */
    private val sessionGenerationOverride: SessionGenerationState? = null,
    /** Test seam: shared formal probe flight. Isolated diagnostics never use it. */
    private val probeFlightOverride: GenerationProbeFlight? = null
) {
    private val logger = thisLogger()
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val authenticationSessions = ApplicationManager.getApplication()
        .getService(AuthenticationSessionRegistry::class.java)
    private val executor = executorOverride ?: NacosRequestExecutor()

    private val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)

    /**
     * The V1 and V3 adapters are shared by the [v1Gateway] so formal operations
     * use one transport and the application authentication-session registry.
     * AUTO detection uses a separate throwaway session registry (ADR-0034) so
     * candidate-probe tokens never enter the application registry.
     */
    private val v1Adapter by lazy {
        V1ProtocolAdapter(
            NacosRequestExecutorProtocolTransport(executor),
            authenticationSessions
        )
    }
    private val v3Adapter by lazy {
        V3ProtocolAdapter(
            NacosRequestExecutorProtocolTransport(executor),
            authenticationSessions
        )
    }
    private val v1Gateway by lazy {
        v1GatewayOverride ?: OperationGateway(
            adapters = mapOf(
                NacosApiGeneration.V1 to v1Adapter,
                NacosApiGeneration.V3 to v3Adapter
            ),
            cache = CacheServiceOperationCache(cacheService) { settings.getCacheTtlMillis() },
            // Production gateway reports every formal completion to the same
            // visibility module the cache enforces on reads (issue #123).
            visibility = cacheService.visibilityReporter()
        )
    }
    /** Resolves AUTO to a concrete generation by probing V3 first, then V1. */
    private val generationResolver by lazy {
        val transport = NacosRequestExecutorProtocolTransport(executor)
        val probeSessions = AuthenticationSessionRegistry()
        GenerationResolver(
            v3Adapter = V3ProtocolAdapter(transport, probeSessions),
            v1Adapter = V1ProtocolAdapter(transport, probeSessions)
        )
    }

    /**
     * Graded capabilities of the dialect selected for [generation]. Null when
     * no adapter is registered (e.g. UNKNOWN). Upper layers use this for
     * coverage reporting instead of switching on API generation (ADR-0048).
     */
    fun protocolCapabilities(generation: NacosApiGeneration): ProtocolCapabilities? =
        v1Gateway.capabilities(generation)

    /**
     * Builds a throwaway adapter stack for one diagnostic run.
     *
     * Budget: [RequestPolicy.DIAGNOSTIC] raises only the per-request total budget
     * (30s vs 15s) — attempt count and connect/read timeouts are identical to
     * [RequestPolicy.INTERACTIVE], so this selects no retry behaviour that
     * ADR-0021 reserves for the transport seam. A diagnostic is worth the longer
     * budget because ADR-0022 makes it up to four sequential stages whose job is
     * to characterise the connection and report per-stage timing; reporting a
     * slow-but-working server as a connection failure sends the user to debug the
     * wrong thing. The settings UI runs it in a cancellable background task, so
     * nothing blocks on the wider ceiling.
     *
     * Isolation: ADR-0022 forbids diagnostics from touching the cache, the shared
     * probe flight, the session, or the authentication registry. Building the
     * stack here rather than sharing the formal one gets all four structurally —
     * this [OperationGateway] takes a no-op cache and an observation sequence of
     * its own rather than the process-wide one, a fresh [GenerationResolver]
     * cannot join the formal probe flight, and a throwaway
     * [AuthenticationSessionRegistry] holds any Nacos-password token so the
     * application registry stays untouched.
     *
     * Per call, not cached: a token acquired for one unapplied draft must never
     * serve the next one, because the user may have edited the credentials
     * between two clicks of Test Connection.
     */
    private fun newDiagnosticStack(): Pair<GenerationResolver, OperationGateway> {
        val transport = NacosRequestExecutorProtocolTransport(executor, RequestPolicy.DIAGNOSTIC)
        val sessions = AuthenticationSessionRegistry()
        val v1 = V1ProtocolAdapter(transport, sessions)
        val v3 = V3ProtocolAdapter(transport, sessions)
        return GenerationResolver(v3, v1) to OperationGateway(
            mapOf(
                NacosApiGeneration.V1 to v1,
                NacosApiGeneration.V3 to v3
            ),
            observationSequence = ObservationSequence(),
            // Isolated diagnostics never report into production visibility
            // (issue #125 / ADR-0050): an explicit no-op reporter, so a
            // diagnostic refusal can neither block nor unblock production data.
            visibility = NoOpVisibilityReporter
        )
    }

    /** Formal AUTO probes share this flight; diagnostics do not. */
    private val probeFlight = probeFlightOverride ?: sharedProbeFlight
    private val fallbackEpoch = java.util.concurrent.atomic.AtomicLong(0)
    /**
     * Fallback session used when no open project owns the profile. Still
     * in-memory only and never persisted — satisfies sequential reuse for
     * app-level callers (plugin init, unit tests without a Project).
     */
    private val fallbackSessionGeneration = SessionGenerationState(
        currentEpoch = { fallbackEpoch.get() },
        onGenerationChanged = { fallbackEpoch.incrementAndGet(); Unit }
    )

    companion object {
        /** Process-wide formal probe flight shared by all NacosApiService instances. */
        private val sharedProbeFlight = GenerationProbeFlight()

        /**
         * Upper bound on how many summary pages [loadNamespace] may request.
         * Uses the tighter of the server's [pagesAvailable] and
         * ceil([totalCount] / [pageSize]) when both are present, so an inflated
         * pagesAvailable cannot defeat the [totalCount] bound. When neither is
         * usable, allow exactly one page so a full-page proxy response cannot
         * loop forever (issue #145).
         */
        internal fun reportedNamespacePageCount(
            pagesAvailable: Int,
            totalCount: Int,
            pageSize: Int
        ): Int {
            val fromPages = pagesAvailable.takeIf { it > 0 }
            val fromTotal = if (totalCount > 0 && pageSize > 0) {
                ((totalCount + pageSize - 1) / pageSize).coerceAtLeast(1)
            } else {
                null
            }
            return when {
                fromPages != null && fromTotal != null -> minOf(fromPages, fromTotal)
                fromPages != null -> fromPages
                fromTotal != null -> fromTotal
                else -> 1
            }
        }
    }

    /**
     * Tests connection using an already-captured [operationContext].
     * Never reads current settings, the credential store, or the project session
     * (issue #50 / ADR-0010) — the caller must supply a valid context.
     */
    suspend fun testConnection(operationContext: NacosOperationContext): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val target = resolvedReadTarget(operationContext, null).getOrElse { error ->
                return@withContext Result.failure(error)
            }
            return@withContext v1Gateway.probe(target).map { true }
        } catch (e: Exception) {
            logger.warn("Connection test failed", e)
            Result.failure(e)
        }
    }

    /**
     * Retrieves a specific configuration from Nacos, together with the
     * observation sequence the read took (ADR-0047). This read caches what it
     * returned; a caller that derives a *further* cache mutation from it — the
     * detail panel's authoritative not-found is the one that remains — must pass
     * that same number, so the read and the write it produced are ordered as one
     * observation.
     *
     * @param dataId Configuration data ID
     * @param group Configuration group
     * @param namespaceId Namespace ID (tenant), null for public namespace
     * @param useCache Whether to use cache (default: true)
     */
    suspend fun getConfiguration(
        dataId: String,
        group: String,
        namespaceId: String? = null,
        useCache: Boolean = true,
        forceRefresh: Boolean = false,
        operationContext: NacosOperationContext? = null
    ): Result<Observed<NacosConfiguration?>> = withContext(Dispatchers.IO) {
        try {
            val context = operationContext ?: operationContextOrFailure() ?: return@withContext Result.failure(
                ConfigurationRequired(listOf("Connection configuration is incomplete"))
            )
            val target = resolvedReadTarget(context, namespaceId).getOrElse { error ->
                return@withContext Result.failure(error)
            }
            return@withContext v1Gateway.readDetail(
                target,
                ConfigurationCoordinate(dataId, group),
                forceRefresh = forceRefresh,
                useCache = useCache
            )
        } catch (e: Exception) {
            // Not-found is mapped to Result.success(null) inside the adapters;
            // anything that escapes the gateway is a real failure.
            logger.warn("Error getting configuration", e)
            Result.failure(e)
        }
    }

    /**
     * Lists all configurations from Nacos, together with the observation
     * sequence the read took (ADR-0047). A caller that paints the page orders
     * it by that same number, so a late-arriving earlier read cannot replace a
     * later one; a read served from cache observed nothing and reports
     * [Observed.NO_OBSERVATION].
     *
     * @param namespaceId Namespace ID (tenant), null for public namespace
     * @param pageNo Page number for pagination
     * @param pageSize Number of items per page
     * @param dataId Data ID for search (supports wildcards for fuzzy search)
     * @param group Group for search
     * @param appName Application name for search
     * @param configTags Configuration tags for search
     * @param searchMode Search mode ("accurate" for exact match, "blur" for fuzzy search)
     * @param useCache Whether to use cache for individual configurations (default: true)
     */
    suspend fun listConfigurations(
        namespaceId: String? = null,
        pageNo: Int = 1,
        pageSize: Int = 200,
        dataId: String = "",
        group: String = "",
        appName: String = "",
        configTags: String = "",
        searchMode: String = "accurate",
        useCache: Boolean = true,
        forceRefresh: Boolean = false,
        operationContext: NacosOperationContext? = null
    ): Result<Observed<ConfigListResponse>> = withContext(Dispatchers.IO) {
        try {
            val context = operationContext ?: operationContextOrFailure() ?: return@withContext Result.failure(
                ConfigurationRequired(listOf("Connection configuration is incomplete"))
            )
            val target = resolvedReadTarget(context, namespaceId).getOrElse { error ->
                return@withContext Result.failure(error)
            }
            return@withContext v1Gateway.listSummaries(
                target,
                SummaryQuery(pageNo, pageSize, dataId, group, appName, configTags, searchMode),
                forceRefresh = forceRefresh,
                useCache = useCache
            ).map { Observed(it.value.toConfigListResponse(), it.observation) }
        } catch (e: Exception) {
            logger.warn("Error listing configurations", e)
            Result.failure(e)
        }
    }

    /**
     * Loads configuration **summaries** for a namespace (ADR-0016 / ADR-0041).
     * Issues one list request per summary page and never issues per-item detail
     * requests — completeness depends only on summary pagination. Some dialects
     * already embed bodies in those list rows; [NamespaceIndexCoordinator] may
     * seed ordinary detail coordinates from them on a COMPLETE load (issue #146).
     * Detail content for code navigation on dialects whose lists omit bodies is
     * supplied by the independent navigation detail prefetch (issue #51).
     *
     * Requires an already-captured [operationContext]; never reads settings or the
     * credential store (issue #50). Every generation is resolved and dispatched
     * through the operation gateway.
     *
     * Retry budget is not a parameter: per ADR-0021 every request crosses one
     * transport seam that classifies retries by operation kind (idempotent read /
     * write / login), not by who asked. A background preheat and a foreground
     * search of the same namespace therefore get the same bounded budget.
     */
    suspend fun loadNamespace(
        namespaceId: String? = null,
        useCache: Boolean = true,
        operationContext: NacosOperationContext
    ): Result<NamespaceLoadResult> {
        return try {
            val allSummaries = mutableListOf<NacosConfiguration>()
            var expectedCount = 0
            var pageNo = 1
            val pageSize = 100
            var endedOnShortPage = false

            do {
                val result = listConfigurations(
                    namespaceId, pageNo, pageSize,
                    useCache = useCache,
                    operationContext = operationContext
                )
                if (result.isFailure) {
                    // First page failure with nothing loaded → FAILED; a later
                    // page failure with some rows → PARTIAL (summary pagination
                    // incomplete). Detail failures cannot occur here.
                    // Preserve the original typed cause so stale-fallback policy
                    // and presentation see authentication/permission rather than
                    // a generic connection failure (issue #122 / ADR-0019).
                    val completeness = if (allSummaries.isEmpty()) {
                        DatasetCompleteness.FAILED
                    } else {
                        DatasetCompleteness.PARTIAL
                    }
                    return Result.success(
                        NamespaceLoadResult(
                            completeness = completeness,
                            expectedCount = expectedCount,
                            configurations = allSummaries,
                            stoppingCause = result.exceptionOrNull()
                        )
                    )
                }

                val response = result.getOrNull()?.value ?: break
                expectedCount = response.totalCount

                // Keep list-carried bodies on the load result so a COMPLETE
                // index write can seed ordinary detail coordinates when the
                // dialect declares listCarriesBodies (issue #146). The index
                // store itself still strips content (issue #52 / ADR-0041).
                response.pageItems.forEach { item ->
                    // Shared with the search path (ConfigItem.toMetadataConfiguration):
                    // stamp the requested Namespace when the response carries none
                    // so a non-public load is not treated as public (issue #191).
                    allSummaries.add(item.toMetadataConfiguration(namespaceId))
                }
                // Bound by the tighter of pagesAvailable and totalCount/pageSize
                // so a proxy that ignores paging (or inflates pagesAvailable)
                // cannot fetch forever (issue #145).
                val reportedPages = reportedNamespacePageCount(
                    pagesAvailable = response.pagesAvailable,
                    totalCount = response.totalCount,
                    pageSize = pageSize
                )
                val shortPage = response.pageItems.size < pageSize
                if (shortPage) {
                    endedOnShortPage = true
                    break
                }
                if (pageNo >= reportedPages) break
                pageNo++
            } while (true)

            // Completeness from summary pagination alone: we got every expected
            // summary row (or the server reported zero). A detail failure can no
            // longer mark the index partial because we never request details.
            // A safety-cap stop with no usable totalCount is not a proven end —
            // treat it as PARTIAL rather than claiming COMPLETE (issue #145).
            val completeness = when {
                expectedCount > 0 && allSummaries.size < expectedCount -> DatasetCompleteness.PARTIAL
                !endedOnShortPage && expectedCount <= 0 -> DatasetCompleteness.PARTIAL
                else -> DatasetCompleteness.COMPLETE
            }
            Result.success(
                NamespaceLoadResult(
                    completeness = completeness,
                    expectedCount = expectedCount,
                    configurations = allSummaries,
                    stoppingCause = null
                )
            )
        } catch (e: Exception) {
            logger.warn("Error loading namespace", e)
            Result.failure(e)
        }
    }

    /**
     * Retrieves all namespaces from Nacos using an already-captured
     * [operationContext]. Never reads current settings, the credential store,
     * or the project session (issue #50 / ADR-0010).
     */
    suspend fun getNamespaces(operationContext: NacosOperationContext): Result<List<NamespaceInfo>> = withContext(Dispatchers.IO) {
        try {
            val resolvedTarget = resolvedReadTarget(operationContext, null).getOrElse { error ->
                logger.warn("Failed to resolve generation for namespace discovery", error)
                // Discovery failure must not hide a manually readable public namespace.
                return@withContext Result.success(listOf(NamespaceInfo.createPublicNamespace()))
            }
            logger.debug("Discovering namespaces via gateway for ${resolvedTarget.context.resolvedGeneration}")
            val discovered = v1Gateway.discoverNamespaces(resolvedTarget)
            discovered.fold(
                onSuccess = { namespaces ->
                    val mapped = namespaces.map { ns ->
                        NamespaceInfo(
                            namespaceId = if (ns.namespaceId == "public") "" else ns.namespaceId,
                            namespaceName = ns.displayName,
                            namespaceDesc = ns.description.orEmpty(),
                            configCount = ns.configCount?.toInt() ?: 0
                        )
                    }
                    val hasPublic = mapped.any { it.isPublicNamespace() }
                    Result.success(
                        if (hasPublic) mapped else listOf(NamespaceInfo.createPublicNamespace()) + mapped
                    )
                },
                onFailure = { error ->
                    logger.warn("Namespace discovery unavailable: ${error.message}")
                    // Capability/auth denial → public + manual entry UX; not a hard failure.
                    Result.success(listOf(NamespaceInfo.createPublicNamespace()))
                }
            )
        } catch (e: Exception) {
            logger.warn("Error getting namespaces", e)
            if (e is ConfigurationRequired) return@withContext Result.failure(e)
            Result.success(listOf(NamespaceInfo.createPublicNamespace()))
        }
    }

    /**
     * Clears the cache for a specific namespace or all namespaces.
     *
     * Both are cache mutations and take an observation sequence, so a read that
     * started before the gesture loses to it while one started afterwards lands
     * normally — clear-then-reload behaves as the obvious gesture (ADR-0045).
     */
    @OptIn(CacheWriteAccess::class)
    suspend fun clearCache(namespace: String? = null) {
        val observation = ObservationSequence.process.next()
        if (namespace == null) {
            cacheService.applyMutation(CacheMutation.Clear, observation)
            logger.debug("Cleared all configuration cache")
        } else {
            cacheService.applyMutation(
                CacheMutation.InvalidateNamespace(settings.captureAccessIdentity(), namespace),
                observation
            )
            logger.debug("Cleared cache for namespace: $namespace")
        }
    }

    /**
     * Resolves a locked [OperationTarget] for the given context/namespace,
     * including AUTO → V3-first generation resolution. UI layers must use this
     * (or an equivalent) so history/publish never dispatch against UNKNOWN.
     */
    suspend fun resolveOperationTarget(
        context: NacosOperationContext,
        namespaceId: String?
    ): Result<OperationTarget> = withContext(Dispatchers.IO) {
        resolvedReadTarget(context, namespaceId)
    }

    /** Identity-scoped gateway used by history browsing and controlled publish. */
    fun operationGateway(): OperationGateway = v1Gateway

    suspend fun listConfigurationHistory(
        target: OperationTarget,
        query: HistoryQuery
    ): Result<HistoryPage> = withContext(Dispatchers.IO) {
        v1Gateway.listHistory(target, query, forceRefresh = true, useCache = false).map { it.value }
    }

    suspend fun readConfigurationHistoryDetail(
        target: OperationTarget,
        historyId: String
    ): Result<HistoryDetail> = withContext(Dispatchers.IO) {
        v1Gateway.readHistoryDetail(target, historyId, forceRefresh = true, useCache = false).map { it.value }
    }

    /**
     * Isolated connection diagnostic from an unapplied settings snapshot.
     * Never mutates persisted profiles, sessions, cache, or the auth registry.
     */
    suspend fun diagnoseConnection(snapshot: DiagnosticSnapshot): DiagnosticReport = withContext(Dispatchers.IO) {
        val (resolver, gateway) = newDiagnosticStack()
        ConnectionDiagnostic(resolver = resolver, gateway = gateway).diagnose(snapshot)
    }

    /**
     * Isolated Namespace list for the settings 建议 Namespace chooser.
     * Not a 连接诊断: it does not read the configured Namespace or headline
     * connection success/failure.
     */
    suspend fun discoverSuggestedNamespaces(
        snapshot: DiagnosticSnapshot
    ): Result<List<com.nanyin.nacos.search.services.operations.DiscoveredNamespace>> =
        withContext(Dispatchers.IO) {
            val (resolver, gateway) = newDiagnosticStack()
            ConnectionDiagnostic(resolver = resolver, gateway = gateway).discover(snapshot)
        }

    /** Guards every operation before cache reads, token lookup, or remote transport. */
    private fun operationContextOrFailure() = settings.captureOperationContext().getOrNull()

    private fun usesLockedGeneration(context: NacosOperationContext): Boolean =
        context.resolvedGeneration == NacosApiGeneration.V1 ||
        context.resolvedGeneration == NacosApiGeneration.V3

    private fun v1Target(context: NacosOperationContext, namespaceId: String? = null): OperationTarget =
        OperationTarget(context, namespaceId ?: "public")

    /**
     * Returns a target whose generation is locked to a concrete V1 or V3.
     * Locked profiles (V1/V3) keep their captured generation and never probe.
     * AUTO is resolved **once per project session** (ADR-0006 / ADR-0034): the
     * in-memory session result is reused; concurrent formal detections share a
     * single flight keyed by the complete non-secret probe inputs; a consumer
     * whose capture no longer matches does not commit the shared result.
     * Isolated diagnostics never enter this path.
     */
    private suspend fun resolvedReadTarget(
        context: NacosOperationContext,
        namespaceId: String?
    ): Result<OperationTarget> {
        if (usesLockedGeneration(context)) return Result.success(v1Target(context, namespaceId))

        val session = sessionOwner(context)
        session.resolvedIfCompatible(
            profileId = context.identity.profileId,
            profileRevision = context.profileRevision,
            accessRevision = context.accessRevision
        )?.let { cached ->
            return Result.success(v1Target(lockedContext(context, cached), namespaceId))
        }

        val capture = GenerationSessionCapture(
            profileId = context.identity.profileId,
            profileRevision = context.profileRevision,
            accessRevision = context.accessRevision,
            sessionEpoch = session.snapshotEpoch(),
            canonicalEndpoint = context.endpoint.value,
            authMode = context.authMode,
            principal = context.identity.principal
        )
        val probeKey = GenerationProbeKey.from(capture)
        val generation = probeFlight.joinOrProbe(probeKey) {
            generationResolver.resolve(v1Target(context, namespaceId))
        }.getOrElse { error ->
            return Result.failure(error)
        }

        // Commit only when the consumer's capture still matches live identity
        // keys, epoch, and tombstone (ADR-0034). A rejected commit still lets
        // this operation use the probed generation, but neither the session nor
        // last-known generation is updated.
        val live = liveIdentityKeys(context)
        if (live != null && session.tryCommit(generation, capture, live)) {
            persistLastKnownGeneration(context, generation)
        }
        return Result.success(v1Target(lockedContext(context, generation), namespaceId))
    }

    private fun sessionOwner(context: NacosOperationContext): SessionGenerationState {
        sessionGenerationOverride?.let { return it }
        ProjectSessionEpochs.findForProfile(context.identity.profileId)
            ?.generationState()
            ?.let { return it }
        return fallbackSessionGeneration
    }

    /**
     * Re-reads the live profile's non-secret identity keys for the ADR-0034
     * commit recheck. Returns null when the profile is gone in production —
     * commit is refused. Injected test sessions fall back to the capture keys.
     */
    private fun liveIdentityKeys(context: NacosOperationContext): SessionGenerationState.LiveIdentityKeys? {
        val fromCapture = SessionGenerationState.LiveIdentityKeys(
            profileId = context.identity.profileId,
            profileRevision = context.profileRevision,
            accessRevision = context.accessRevision
        )
        return try {
            val profile = settings.getProfile(context.identity.profileId)
            if (profile != null) {
                SessionGenerationState.LiveIdentityKeys(
                    profileId = profile.id,
                    profileRevision = profile.profileRevision,
                    accessRevision = profile.accessRevision
                )
            } else if (sessionGenerationOverride != null) {
                // Harness profiles are not registered in NacosSettings.
                fromCapture
            } else {
                null
            }
        } catch (_: Exception) {
            if (sessionGenerationOverride != null) fromCapture else null
        }
    }

    /**
     * Offline bootstrap: rebuild the access identity using the persisted
     * last-known generation so the same identity's cache can be located.
     * Marks nothing as remote-authorised — callers must treat restored data as
     * unconfirmed and must still run formal detection before any remote op
     * (ADR-0034). Returns null when no last-known value exists.
     */
    fun offlineCacheIdentity(context: NacosOperationContext): com.nanyin.nacos.search.models.AccessIdentity? {
        val lastKnown = lastKnownGeneration(context) ?: return null
        if (lastKnown != NacosApiGeneration.V1 && lastKnown != NacosApiGeneration.V3) return null
        return context.identity.copy(resolvedGeneration = lastKnown)
    }

    private fun persistLastKnownGeneration(context: NacosOperationContext, generation: NacosApiGeneration) {
        if (generation != NacosApiGeneration.V1 && generation != NacosApiGeneration.V3) return
        try {
            ApplicationManager.getApplication()
                .getService(LastKnownGenerationStore::class.java)
                ?.put(
                    LastKnownGenerationStore.Key(
                        profileId = context.identity.profileId,
                        accessRevision = context.accessRevision,
                        canonicalEndpoint = context.endpoint.value
                    ),
                    generation
                )
        } catch (_: Exception) {
            // Persistence is best-effort outside a fully initialised application.
        }
    }

    /**
     * Offline bootstrap helper: last successful AUTO resolution for this access
     * key. Goes through the same lookup the cache hot paths use (ADR-0053), so
     * there is one owner of what counts as a match for an access key.
     */
    fun lastKnownGeneration(context: NacosOperationContext): NacosApiGeneration? =
        ResolvedGenerationLocator.persistedLastKnownGeneration(
            ProfileAccessKeys(
                profileId = context.identity.profileId,
                profileRevision = context.profileRevision,
                accessRevision = context.accessRevision,
                canonicalEndpoint = context.endpoint.value
            )
        )

    private fun lockedContext(
        context: NacosOperationContext,
        generation: NacosApiGeneration
    ): NacosOperationContext {
        if (context.resolvedGeneration == generation) return context
        return context.copy(
            identity = context.identity.copy(resolvedGeneration = generation),
            resolvedGeneration = generation
        )
    }

    private fun SummaryPage.toConfigListResponse(): ConfigListResponse = ConfigListResponse(
        totalCount = totalCount,
        pageNumber = pageNumber,
        pagesAvailable = pagesAvailable,
        pageItems = items.mapIndexed { index, item ->
            ConfigItem(index.toString(), item.dataId, item.group, item.content, item.type, item.tenantId)
        }
    )
}
