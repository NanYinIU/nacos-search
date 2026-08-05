package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.models.SearchCriteria
import com.nanyin.nacos.search.listeners.NamespaceChangeListener
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.bundle.NacosSearchBundle
// import com.nanyin.nacos.search.services.NacosConfigService // Not needed
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.IndexOutcome
import com.nanyin.nacos.search.services.NamespaceIndexCoordinator
import com.nanyin.nacos.search.services.NamespaceIndexRequest
import com.nanyin.nacos.search.services.captureNamespaceIndexRequest
import com.nanyin.nacos.search.services.requestSwitchedNamespaceIndex
import com.nanyin.nacos.search.services.NavigationIndexRefreshService
import com.nanyin.nacos.search.settings.NacosConfigurable
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.NacosSettingsListener
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosUpgradeSummary
import com.nanyin.nacos.search.settings.operationNamespaceIdFor
import com.nanyin.nacos.search.services.operations.DraftGuard
import com.nanyin.nacos.search.services.operations.EditSessionService
import com.nanyin.nacos.search.psi.NacosKeyIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI

import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import com.intellij.openapi.application.ModalityState
import com.nanyin.nacos.search.invokeOnEdt
/**
* Main window for Nacos Search plugin
 * Integrates all UI components and manages their interactions
 */
class NacosSearchWindow(private val project: Project, private val toolWindow: ToolWindow) : JPanel(BorderLayout()), NamespaceChangeListener, Disposable {

    // Services
    private val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
    private val nacosApiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    // private val nacosConfigService = project.service<NacosConfigService>() // Not needed
    private val nacosSearchService = project.service<NacosSearchService>()
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val projectSession = project.service<NacosProjectSession>()
    private val indexCoordinator = ApplicationManager.getApplication().getService(NamespaceIndexCoordinator::class.java)
    /** The project's draft. The window asks it before any action that would destroy one. */
    private val editSessions = project.service<EditSessionService>()

    // UI Components
    private lateinit var namespacePanel: NamespacePanel
    private lateinit var searchPanel: SearchPanel
    private lateinit var environmentSwitcher: EnvironmentSwitcher
    private lateinit var configListPanel: ConfigListPanel
    private lateinit var configDetailPanel: ConfigDetailPanel
    private lateinit var paginationPanel: PaginationPanel
    
    // Layout components
    private lateinit var mainSplitter: JBSplitter
    private lateinit var rightSplitter: JBSplitter
    
    // State
    private var currentNamespace: NamespaceInfo? = null
    private var currentConfiguration: NacosConfiguration? = null
    // Stashed (config, line) consumed after a namespace switch reloads the list.
    private var pendingNavigationTarget: Pair<NacosConfiguration, Int>? = null
    private var isSearching = false

    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * The window's search orchestration. Handlers express intent through it and
     * this class renders what [NacosSearchService] publishes — it assembles no
     * search request, names no environment, and judges no result a second time.
     *
     * The capture handed in here is the one place a credential is read for
     * search, and it runs on [Dispatchers.IO], never on the event dispatch
     * thread (ADR-0039).
     */
    private val searchController = ToolWindowSearchController(
        searchService = nacosSearchService,
        selectedProfileId = ::selectedProfileId,
        captureOperationContext = { profileId ->
            withContext(Dispatchers.IO) { settings.captureOperationContext(profileId).getOrNull() }
        }
    )

    private fun selectedProfileId(): String {
        projectSession.healSelection(settings)
        return projectSession.sessionState.selectedProfileId.ifBlank { settings.resolveDefaultProfileId() }
    }

    init {
        projectSession.healSelection(settings)
        NacosUpgradeSummary.showOnce(project, projectSession, settings)
        initializeComponents()
        setupLayout()
        setupTitleActions()
        setupEventHandlers()
        loadInitialData()
    }
    
    private fun initializeComponents() {
        // Initialize UI components
        namespacePanel = NamespacePanel(project)
        environmentSwitcher = EnvironmentSwitcher(project)
        searchPanel = SearchPanel(project)
        configListPanel = ConfigListPanel(project)
        configDetailPanel = ConfigDetailPanel(project)
        paginationPanel = PaginationPanel()
        Disposer.register(this, namespacePanel)
        Disposer.register(this, environmentSwitcher)
        Disposer.register(this, searchPanel)
        Disposer.register(this, configListPanel)
        Disposer.register(this, configDetailPanel)
        Disposer.register(this, paginationPanel)

        // Configure components
        configListPanel.preferredSize = Dimension(400, 200)
        configDetailPanel.preferredSize = Dimension(400, 250)
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty()

       // ===== Header bar: environment switcher =====
       // Window-level actions (Refresh / Settings / More) live in the native tool-window
       // title bar via setupTitleActions(), not as hand-rolled JButtons here. This removes the
       // BasicButtonUI overrides and lets the platform render correct toolbar styling.
       val headerBar = JPanel(BorderLayout()).apply {
           border = JBUI.Borders.empty(2, 8)
           add(environmentSwitcher, BorderLayout.WEST)
       }

        // ===== Toolbar: namespace row + search row (stacked, compact) =====
        val toolbarPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 8)
            add(namespacePanel)
            add(searchPanel)
        }

        // Combine header + toolbar into top container
        val topContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerBar)
            add(toolbarPanel)
        }

        // ===== Config list with pagination (upper split pane) =====
        val configListWithPagination = JPanel(BorderLayout()).apply {
            add(configListPanel, BorderLayout.CENTER)
            add(paginationPanel, BorderLayout.SOUTH)
        }

        // ===== Vertical splitter: list (top) / detail (bottom) =====
        rightSplitter = JBSplitter(true, 0.45f).apply {
            firstComponent = configListWithPagination
            secondComponent = configDetailPanel
            splitterProportionKey = "NacosSearchWindow.detailSplit"
        }

        add(topContainer, BorderLayout.NORTH)
        add(rightSplitter, BorderLayout.CENTER)
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().editConfigurable(project, NacosConfigurable(project))
    }

    /**
     * Registers the window-level actions into the native tool-window title bar instead of
     * hand-rolled JButtons in the content header. Refresh + Settings appear as title actions;
     * the overflow (refresh cache / clear cache) folds into the additional gear menu. This is
     * the New UI idiom and removes the old BasicButtonUI styling overrides.
     */
    private fun setupTitleActions() {
        toolWindow.setTitleActions(listOf(RefreshAllAction(), OpenSettingsAction()))
        val gearGroup = DefaultActionGroup().apply {
            add(RefreshCacheAction())
            add(ClearCacheAction())
        }
        toolWindow.setAdditionalGearActions(gearGroup)
    }

    private fun clearCache() {
        coroutineScope.launch {
            // One gesture, one clear mutation: it takes an observation sequence,
            // so a read that started before it loses and the reload started
            // afterwards lands normally (ADR-0045).
            nacosApiService.clearCache()
            handleRefreshRequested()
        }
    }
    
    private fun setupEventHandlers() {
        // NamespacePanel owns the project-local selection; do not subscribe to
        // the app-wide NamespaceService, which would leak another project's
        // selection into this window.
        namespacePanel.onSelectionChanged = { selected ->
            coroutineScope.launch { onNamespaceChanged(currentNamespace, selected) }
        }
        environmentSwitcher.onSelectionChanged = {
            handleProjectEnvironmentSelectionChanged()
        }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            NacosSettingsListener.TOPIC,
            object : NacosSettingsListener {
                override fun settingsChanged() {
                    handleSettingsChanged()
                }

                override fun preferencesChanged() {
                    handlePreferencesChanged()
                }
            }
        )

        // Namespace handlers
        // Note: NamespacePanel doesn't have onNamespaceChanged property
        // Namespace changes are handled internally by the panel
        
        // Search handlers
        searchPanel.onSearchRequested = { criteria ->
            handleSearchRequested(criteria)
        }
        
        searchPanel.onSearchCleared = {
            handleSearchCleared()
        }
        
        // Real-time search
        searchPanel.onRealTimeSearch = { query ->
            handleRealTimeSearch(query)
        }
        
        // Group filter change — re-search with the selected group
        searchPanel.onGroupFilterChanged = { _ ->
            // The search is already triggered inside SearchPanel; this callback
            // is available for any additional window-level handling if needed.
        }
        
        // Pagination events
        paginationPanel.onPreviousPage = {
            handlePreviousPage()
        }
        
        paginationPanel.onNextPage = {
            handleNextPage()
        }
        
        paginationPanel.onPageSizeChanged = { pageSize ->
            handlePageSizeChanged(pageSize)
        }
        
        // Configuration selection handler
        configListPanel.onConfigurationSelected = { config ->
            handleConfigurationSelected(config)
        }
        
        // Configuration double-click is handled in ConfigListPanel's mouse listener
        // which calls onConfigurationSelected for double-clicks
        
        // Configuration list refresh handler
        configListPanel.onRefreshRequested = {
            handleRefreshRequested()
        }
        
        // Configuration detail handlers
        // Wire dirty-state changes from detail panel to the list row red dot
        configDetailPanel.onDirtyStateChanged = { config, dirty ->
            config?.let { configListPanel.setConfigDirty(it.getKey(), dirty) }
        }
        
        // Search service state observation
        setupSearchServiceObservers()

        // NamespacePanel performs project-scoped initialization itself: a
        // manager that reads app-wide selection state must not run from a
        // project window.
    }

    /**
     * Lightweight handler for preference-only changes (e.g.
     * allowCrossNamespaceNavigation toggle). Does NOT clear caches or reload
     * the config list — just refreshes gutter markers so the highlighter pass
     * re-evaluates resolvability with the new setting.
     */
    private fun handlePreferencesChanged() {
        invokeOnEdt(ModalityState.defaultModalityState()) { environmentSwitcher.refresh() }
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
    }

    private fun handleSettingsChanged() {
        // Switcher label reflects the new active environment immediately.
        invokeOnEdt(ModalityState.defaultModalityState()) { environmentSwitcher.refresh() }
        // Settings may have changed the connection itself, so the API's own
        // in-memory responses for the previous one cannot be reused.
        coroutineScope.launch { restartUnderSelectedEnvironment(clearApiCache = true) }
    }

    private fun handleProjectEnvironmentSelectionChanged() {
        coroutineScope.launch { restartUnderSelectedEnvironment(clearApiCache = false) }
    }

    /**
     * Abandons the current environment and opens the one now selected. Leaving
     * drops the held session, which cancels the pending search and the results
     * it would have published; the namespace list is then reloaded against the
     * new environment and its selection opened.
     */
    private suspend fun restartUnderSelectedEnvironment(clearApiCache: Boolean) {
        searchController.leaveEnvironment()
        if (clearApiCache) nacosApiService.clearCache()
        currentNamespace = null
        currentConfiguration = null
        clearSearchUi()
        namespacePanel.refreshAndWait().onSuccess {
            currentNamespace = namespacePanel.getSelectedNamespace()
            openSelectedNamespace()
        }
    }

    private fun loadInitialData() {
        coroutineScope.launch {
            try {
                // Load namespaces first
                namespacePanel.refresh()

                // Get current namespace and load configurations when enabled for this server.
                val currentNs = namespacePanel.getSelectedNamespace()
                if (currentNs != null) {
                    currentNamespace = currentNs
                    openSelectedNamespace()
                }
            } catch (e: Exception) {
                showError(
                    NacosSearchBundle.message("error.config.load.failed") + ": ${e.message}",
                    e
                )
            }
        }
    }

    override suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
        // Keep the window's notion of the active namespace in sync with the service so that
        // subsequent reloads target the correct namespace.
        currentNamespace = newNamespace
        currentConfiguration = null

        clearSearchUi()

        if (newNamespace == null) return

        // Prefer cached list-page data when available — avoids a forced remote
        // API call on every namespace switch. The user can still pull fresh data
        // via the refresh button.
        searchController.openNamespace(newNamespace)

        // Preheat the full namespace index in the background so the first
        // content/regex/wildcard search over this namespace is instant. The
        // context comes from the session the service just adopted, which was
        // captured off the EDT.
        val operationContext = searchController.sessionContext().operationContext ?: return
        preheatNamespaceIndex(
            settings.captureNamespaceIndexRequest(newNamespace.namespaceId, operationContext)
        )
    }

    private fun clearSearchUi() {
        val hasPendingNav = pendingNavigationTarget != null
        val clearAction = {
            searchPanel.clearAllCriteria()
            // Preserve the detail panel when a cross-namespace navigation is
            // in flight — navigateToConfig already showed the target config —
            // and when it is showing a dirty draft, which losing the namespace
            // selection is no reason to throw away (ADR-0027).
            if (!hasPendingNav && editSessions.guardClear() == DraftGuard.Proceed) {
                configDetailPanel.clearConfiguration()
            }
            configListPanel.render(ConfigListPresentation.empty())
            paginationPanel.reset()
        }

        invokeOnEdt(ModalityState.defaultModalityState(), clearAction)
    }
   
   private fun handleSearchRequested(criteria: SearchCriteria) {
         if (!hasNamespaceToSearch()) return
         coroutineScope.launch { searchController.search(criteria) }
     }

    private fun handleSearchCleared() {
        coroutineScope.launch { searchController.clearCriteria() }
    }

    private fun handleRealTimeSearch(query: String) {
        if (query.isBlank()) {
            handleSearchCleared()
            return
        }
        if (!hasNamespaceToSearch()) return

        // Debounce so rapid typing only triggers one search after the user
        // pauses. The service cancels the previous in-flight search and launches
        // its own coroutine in this scope.
        searchController.searchAsYouType(query, coroutineScope)
    }

    /**
     * Whether there is anything to search yet. Asked of the held session rather
     * than of the namespace control, because the session is what a search
     * actually targets — a selection the session has not adopted yet would
     * otherwise search the previous namespace while naming the new one.
     */
    private fun hasNamespaceToSearch(): Boolean {
        if (searchController.sessionContext().namespace != null) return true
        // No namespace yet is empty, not a failed search. Hold the bundle key so
        // language change re-localises without the panel inventing wording.
        configListPanel.render(
            ConfigListPresentation.emptyWithBundleKey("namespace.select.first")
        )
        return false
    }

    private fun handlePreviousPage() {
        coroutineScope.launch { searchController.previousPage() }
    }

    private fun handleNextPage() {
        coroutineScope.launch { searchController.nextPage() }
    }

    private fun handlePageSizeChanged(pageSize: Int) {
        coroutineScope.launch { searchController.changePageSize(pageSize) }
    }

    private fun setupSearchServiceObservers() {
        // Observe search state changes
        coroutineScope.launch {
            nacosSearchService.searchState.collect { state ->
                when (state) {
                    is NacosSearchService.SearchState.Idle -> {
                        invokeOnEdt(ModalityState.defaultModalityState()) {
                            // Idle is cancel / session abandon, not a sixth list
                            // state. Always re-render so a Loading card cannot stick.
                            setSearching(false)
                            paginationPanel.setLoading(false)
                            configListPanel.render(ConfigListPresentation.fromSearchState(state))
                        }
                    }
                    is NacosSearchService.SearchState.Loading -> {
                        invokeOnEdt(ModalityState.defaultModalityState()) {
                            setSearching(true)
                            configListPanel.render(ConfigListPresentation.loading())
                            paginationPanel.setLoading(true)
                        }
                    }
                    is NacosSearchService.SearchState.Success -> {
                        invokeOnEdt(ModalityState.defaultModalityState()) {
                            // Rendered as published. The service already dropped
                            // results from superseded requests and from sessions
                            // the user has switched away from, so judging them
                            // again here would only be a second opinion about an
                            // ordering that has already been decided. List state
                            // and status-line wording are derived outside the panel.
                            setSearching(false)
                            paginationPanel.setLoading(false)
                            configListPanel.setSearchQuery(searchPanel.getSearchQuery())
                            configListPanel.render(ConfigListPresentation.fromSearchState(state))
                            onConfigurationListPresented(state.configurations)
                            paginationPanel.updatePagination(
                                NacosSearchService.PaginationState(
                                    currentPage = state.pageNumber,
                                    pageSize = state.pageSize,
                                    totalCount = state.totalCount,
                                    totalPages = state.pagesAvailable
                                )
                            )
                        }
                    }
                    is NacosSearchService.SearchState.Error -> {
                        invokeOnEdt(ModalityState.defaultModalityState()) {
                            setSearching(false)
                            paginationPanel.setLoading(false)
                            configListPanel.render(ConfigListPresentation.fromSearchState(state))
                        }
                    }
                }
            }
        }
        
        // Observe pagination state changes
        coroutineScope.launch {
            nacosSearchService.paginationState.collect { paginationState ->
                paginationPanel.updatePagination(paginationState)
            }
        }
    }

    /**
     * Side effects that follow a presented configuration list: group filter,
     * draft-safe detail clear, and pending navigation selection. The list
     * panel itself already rendered the closed state.
     */
    private fun onConfigurationListPresented(configurations: List<NacosConfiguration>) {
        // Populate group filter with unique groups from current results
        searchPanel.setAvailableGroups(configurations.map { it.group }.filter { it.isNotBlank() })
        // An empty result list says nothing about the configuration being
        // edited, so it never discards a draft — and it never prompts either:
        // search is debounced, and a few keystrokes that match nothing must not
        // raise a dialog mid-typing (ADR-0027).
        if (configurations.isEmpty() && pendingNavigationTarget == null &&
            editSessions.guardClear() == DraftGuard.Proceed
        ) {
            configDetailPanel.clearConfiguration()
        }

        // Consume a navigation target stashed before a namespace switch.
        // The detail panel was already shown in navigateToConfig; here we
        // only need to select the row in the freshly loaded list.
        pendingNavigationTarget?.let { (targetConfig, _) ->
            pendingNavigationTarget = null
            configListPanel.selectConfiguration(targetConfig)
            currentConfiguration = targetConfig
        }
    }
    
    private fun setSearching(searching: Boolean) {
        isSearching = searching
        searchPanel.isEnabled = !searching
        namespacePanel.isEnabled = !searching
    }
    
    /**
     * ADR-0027: selecting another configuration must not discard a dirty draft
     * silently. The user cancels — draft and selection stay exactly as they
     * were — or explicitly discards, and only then does the view retarget.
     */
    private fun handleConfigurationSelected(config: NacosConfiguration?) {
        if (!admitRetarget(retargetGuard(config), "config.detail.draft.discard.retarget")) return
        currentConfiguration = config
        if (config != null) {
            loadConfigurationDetail(config)
        } else {
            configDetailPanel.clearConfiguration()
        }
    }

    /** What the project's draft says about retargeting the detail view to [config]. */
    private fun retargetGuard(config: NacosConfiguration?): DraftGuard = editSessions.guardRetarget(
        config?.let { project.operationNamespaceIdFor(it) },
        config?.dataId,
        config?.group
    )

    /**
     * Whether the tool window may retarget its detail view, prompting when
     * [guard] says a dirty draft is at stake. Returns false when the draft must
     * be kept — either because the user cancelled, or because the target is the
     * draft's own configuration and reloading it would discard the draft just
     * as silently.
     */
    private fun admitRetarget(guard: DraftGuard, messageKey: String): Boolean = when (guard) {
        DraftGuard.Proceed -> true
        // Same configuration: keep the draft on screen; do not reload/clear it.
        DraftGuard.AlreadyEditing -> false
        is DraftGuard.ConfirmDiscard -> {
            if (confirmDraftDiscard(project, guard.draft, messageKey)) {
                editSessions.discardDraft()
                true
            } else {
                configListPanel.restoreSelection(guard.draft.dataId, guard.draft.group)
                false
            }
        }
        DraftGuard.RefuseInFlight -> {
            explainPublishInFlight(project)
            false
        }
        is DraftGuard.RequireWarnedAbandon -> confirmWarnedAbandon(project, editSessions, guard.draft)
    }
    
    private fun handleRefreshRequested() {
        coroutineScope.launch { searchController.refresh() }
    }

    /**
     * Re-targets the search session at the selected namespace and shows its
     * first page. With nothing selected there is no environment to search, so
     * the session is dropped and the list emptied.
     */
    private suspend fun openSelectedNamespace() {
        val namespace = currentNamespace
        if (namespace == null) {
            searchController.leaveEnvironment()
            configListPanel.render(ConfigListPresentation.empty())
            return
        }
        searchController.openNamespace(namespace)
    }

    private fun loadConfigurationDetail(config: NacosConfiguration) {
        configDetailPanel.showConfiguration(config)
    }

    /**
     * Surfaces a typed or free-form failure through the list's closed state set.
     * Prefer [ConfigListPresentation.fromSearchState] when a [NacosSearchService.SearchState]
     * is already available. Non-search paths pass no title key so the Failed
     * card is not stamped “Search failed”.
     */
    private fun showError(message: String, error: Throwable? = null) {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            configListPanel.render(
                ConfigListPresentation.fromFailure(
                    error = error,
                    fallbackMessage = message,
                    titleKey = null
                )
            )
        }
    }
    
    /**
     * Selects [config] in the list, shows its detail, and (when [lineIndex] >= 0)
     * positions the caret on that line. Used by @NacosValue navigation.
     *
     * When the target config lives in a different namespace from the one
     * currently shown, the namespace is switched first (same connection only).
     * The config/line is stashed and consumed once the new namespace's config
     * list finishes loading.
     */
    fun navigateToConfig(config: com.nanyin.nacos.search.models.NacosConfiguration, lineIndex: Int) {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            val guard = retargetGuard(config)
            if (guard == DraftGuard.AlreadyEditing) {
                // Already showing this draft: land on the line without
                // reloading the detail, which would discard the draft.
                if (lineIndex >= 0) configDetailPanel.moveToLine(lineIndex)
                return@invokeOnEdt
            }
            if (!admitRetarget(guard, "config.detail.draft.discard.retarget")) return@invokeOnEdt
            val targetNsId = normalizeNamespaceId(config.tenantId)
            val currentNsId = normalizeNamespaceId(currentNamespace?.namespaceId)

            if (targetNsId == currentNsId) {
                // Same namespace: select and show immediately.
                configListPanel.selectConfiguration(config)
                currentConfiguration = config
                configDetailPanel.showConfiguration(config, lineIndex)
            } else {
                // Different namespace: show the config detail immediately —
                // we already have the NacosConfiguration object from the
                // resolver — then switch the namespace in the background.
                // The list will select the target once it finishes loading.
                pendingNavigationTarget = config to lineIndex
                currentConfiguration = config
                configDetailPanel.showConfiguration(config, lineIndex)

                val target = findNamespaceForNavigation(targetNsId)
                if (target != null) {
                    namespacePanel.setSelectedNamespace(target)
                } else {
                    pendingNavigationTarget = null
                    showError("Target namespace '${targetNsId.ifBlank { "public" }}' is not available. Refresh namespaces and try again.")
                }
            }
        }
    }

    private fun normalizeNamespaceId(namespaceId: String?): String =
        namespaceId?.takeIf { it.isNotBlank() && it != "public" } ?: ""

    private fun findNamespaceForNavigation(namespaceId: String): NamespaceInfo? =
        if (namespaceId.isBlank()) {
            namespaceService.getPublicNamespace() ?: namespaceService.findNamespaceById("")
        } else {
            namespaceService.findNamespaceById(namespaceId)
        }
    
    /**
     * Refresh all data.
     *
     * A dirty draft is refreshed *around*, not through: namespaces and the
     * result list reload, but the detail view keeps the draft. Asking for fresh
     * data is not asking to throw an edit away, and reloading the detail would
     * overwrite what the user typed (ADR-0027). Revert, beside Save, is the
     * deliberate way to take the server's version.
     */
    fun refreshAll() {
        coroutineScope.launch {
            try {
                val namespaceResult = namespacePanel.refreshAndWait()
                if (namespaceResult.isSuccess) {
                    currentNamespace = namespacePanel.getSelectedNamespace()
                }
                openSelectedNamespace()
                currentConfiguration?.let { config ->
                    if (admitRetarget(retargetGuard(config), "config.detail.draft.discard.retarget")) {
                        loadConfigurationDetail(config)
                    }
                }
            } catch (e: Exception) {
                showError(
                    NacosSearchBundle.message("error.config.load.failed") + ": ${e.message}",
                    e
                )
            }
        }
    }
    
    /**
     * Cleanup resources
     */
    override fun dispose() {
        coroutineScope.cancel()
    }

    /**
     * Preheat the full namespace index for [namespaceId] in the background.
     * Best-effort: on failure the on-demand pull path in
     * [NacosSearchService.searchWithLocalIndex] remains the fallback.
     */
    private fun preheatNamespaceIndex(indexRequest: NamespaceIndexRequest) {
        if (!settings.cacheEnabled) return
        val namespaceId = indexRequest.key.namespaceId
        val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Skip the heavy full-content fetch when a fresh namespace
                // index already exists — re-preheating on every namespace
                // switch is the primary source of background IO saturation.
                val existing = cacheService.getNamespaceIndex(indexRequest.key.identity, namespaceId)
                if (existing != null) {
                    ApplicationManager.getApplication()
                        .getService(NacosKeyIndexService::class.java)
                        .ensureIndexBuilt(cacheService.snapshot(indexRequest.key.identity))
                    return@launch
                }

                val outcome = indexCoordinator.requestSwitchedNamespaceIndex(indexRequest)
                if (outcome is IndexOutcome.Complete || outcome is IndexOutcome.Partial) {
                    ApplicationManager.getApplication()
                        .getService(NavigationIndexRefreshService::class.java)
                        .refresh(indexRequest.key.identity, project)
                }
            } catch (e: Exception) {
                // Swallow: preheat is opportunistic.
            }
        }
    }

    /**
     * Enable or disable all UI components
     */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        namespacePanel.isEnabled = enabled
        searchPanel.setEnabled(enabled)
        configListPanel.isEnabled = enabled
        configDetailPanel.isEnabled = enabled
    }

    private inner class RefreshAllAction :
        AnAction(NacosSearchBundle.message("toolwindow.refresh.all"), NacosSearchBundle.message("toolwindow.refresh.all"), AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = refreshAll()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !isSearching
        }
    }

    private inner class OpenSettingsAction :
        AnAction(NacosSearchBundle.message("toolwindow.settings"), NacosSearchBundle.message("toolwindow.settings"), AllIcons.General.Settings) {
        override fun actionPerformed(e: AnActionEvent) = openSettings()
    }

    private inner class RefreshCacheAction :
        AnAction(NacosSearchBundle.message("action.refresh.cache"), NacosSearchBundle.message("action.refresh.cache"), AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = refreshAll()
    }

    private inner class ClearCacheAction :
        AnAction(NacosSearchBundle.message("action.clear.cache"), NacosSearchBundle.message("action.clear.cache"), AllIcons.Actions.GC) {
        override fun actionPerformed(e: AnActionEvent) = clearCache()
    }
}
