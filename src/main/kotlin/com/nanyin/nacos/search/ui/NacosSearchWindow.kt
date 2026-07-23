package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.managers.InitializationManager
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.models.SearchCriteria
import com.nanyin.nacos.search.listeners.NamespaceChangeListener
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.bundle.NacosSearchBundle
// import com.nanyin.nacos.search.services.NacosConfigService // Not needed
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.IndexOutcome
import com.nanyin.nacos.search.services.NamespaceIndexCoordinator
import com.nanyin.nacos.search.services.NamespaceIndexRequest
import com.nanyin.nacos.search.services.captureNamespaceIndexRequest
import com.nanyin.nacos.search.services.captureServerSnapshot
import com.nanyin.nacos.search.services.requestSwitchedNamespaceIndex
import com.nanyin.nacos.search.services.NavigationIndexRefreshService
import com.nanyin.nacos.search.settings.NacosConfigurable
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.NacosSettingsListener
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.NacosUpgradeSummary
import com.nanyin.nacos.search.psi.NacosKeyResolver
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.icons.AllIcons
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI

import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.plaf.basic.BasicButtonUI

/**
* Main window for Nacos Search plugin
 * Integrates all UI components and manages their interactions
 */
class NacosSearchWindow(private val project: Project, private val toolWindow: ToolWindow) : JPanel(BorderLayout()), LanguageAwareComponent, NamespaceChangeListener, Disposable {
    
    // Services
    private val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
    private val nacosApiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    private val languageService = com.intellij.openapi.application.ApplicationManager.getApplication().getService(LanguageService::class.java)
    // private val nacosConfigService = project.service<NacosConfigService>() // Not needed
    private val nacosSearchService = project.service<NacosSearchService>()
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val projectSession = project.service<NacosProjectSession>()
    private val indexCoordinator = ApplicationManager.getApplication().getService(NamespaceIndexCoordinator::class.java)
    
    // Managers
    private lateinit var initializationManager: InitializationManager
    
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
    private var searchCriteria: SearchCriteria? = null
    private var currentSearchRequest: NacosSearchService.SearchRequest? = null
    private var currentConfigurations = listOf<NacosConfiguration>()
    private var isSearching = false
    
    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * EDT-safe cached operation context. [captureOperationContext] reads
     * PasswordSafe, which asserts against slow operations on the EDT. Swing
     * handlers consume this prepared immutable snapshot instead of capturing
     * fresh on the EDT (design §11/§19.7). It is refreshed off-EDT whenever the
     * selected profile, namespace, or settings change.
     */
    @Volatile
    private var operationContextSnapshot: NacosOperationContext? = null

    private fun selectedProfileId(): String {
        projectSession.healSelection(settings)
        return projectSession.sessionState.selectedProfileId.ifBlank { settings.resolveDefaultProfileId() }
    }

    private fun selectedOperationContext(): NacosOperationContext? = operationContextSnapshot

    private suspend fun refreshOperationContextSnapshot() {
        val profileId = selectedProfileId()
        operationContextSnapshot = withContext(Dispatchers.IO) {
            settings.captureOperationContext(profileId).getOrNull()
        }
    }
    
    init {
        projectSession.healSelection(settings)
        NacosUpgradeSummary.showOnce(project, projectSession, settings)
        initializeComponents()
        setupLayout()
        setupEventHandlers()
        coroutineScope.launch { refreshOperationContextSnapshot() }
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
        Disposer.register(this, configListPanel)
        Disposer.register(this, configDetailPanel)
        Disposer.register(this, paginationPanel)
        
        // Initialize managers
        initializationManager = InitializationManager(
            namespaceService,
            nacosApiService,
            nacosSearchService,
            coroutineScope
        )
        
        // Configure components
        configListPanel.preferredSize = Dimension(400, 200)
        configDetailPanel.preferredSize = Dimension(400, 250)
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty()

       // ===== Header bar: title + icon toolbar =====
       val headerBar = JPanel(BorderLayout()).apply {
           border = JBUI.Borders.empty(2, 8)
           add(environmentSwitcher, BorderLayout.WEST)
           val iconBar = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(iconButton(AllIcons.Actions.Refresh, NacosSearchBundle.message("toolwindow.refresh.all"), "nacos.toolwindow.refreshAll") { refreshAll() })
                add(iconButton(AllIcons.General.Settings, NacosSearchBundle.message("toolwindow.settings"), "nacos.toolwindow.settings") { openSettings() })
                add(iconButton(AllIcons.Actions.More, NacosSearchBundle.message("toolwindow.more"), "nacos.toolwindow.more") { showMoreMenu(this) })
            }
            add(iconBar, BorderLayout.EAST)
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

    private fun iconButton(icon: javax.swing.Icon, tooltip: String, automationId: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            putClientProperty("nacos.automation.id", automationId)
            putClientProperty("JButton.buttonType", "toolbar")
            ui = BasicButtonUI()
            preferredSize = Dimension(28, 24)
            minimumSize = Dimension(28, 24)
            border = JBUI.Borders.empty()
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            addActionListener { action() }
        }
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().editConfigurable(project, NacosConfigurable())
    }

    private fun showMoreMenu(invoker: JComponent) {
        val popup = JPopupMenu()
        popup.add(JMenuItem(NacosSearchBundle.message("action.refresh.cache")).apply {
            addActionListener { refreshAll() }
        })
        popup.add(JMenuItem(NacosSearchBundle.message("action.clear.cache")).apply {
            addActionListener { clearCache() }
        })
        popup.show(invoker, 0, invoker.height)
    }

    private fun clearCache() {
        coroutineScope.launch {
            ApplicationManager.getApplication().getService(CacheService::class.java).clearCache()
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
        
        // NamespacePanel performs project-scoped initialization itself. The
        // legacy InitializationManager reads app-wide selection state, so it
        // must not run from a project window.
    }

    /**
     * Lightweight handler for preference-only changes (e.g.
     * allowCrossNamespaceNavigation toggle). Does NOT clear caches or reload
     * the config list — just refreshes gutter markers so the highlighter pass
     * re-evaluates resolvability with the new setting.
     */
    private fun handlePreferencesChanged() {
        SwingUtilities.invokeLater { environmentSwitcher.refresh() }
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
    }

    private fun handleSettingsChanged() {
        // Switcher label reflects the new active environment immediately.
        SwingUtilities.invokeLater { environmentSwitcher.refresh() }
        coroutineScope.launch {
            refreshOperationContextSnapshot()
            nacosApiService.clearCache()
            nacosSearchService.resetSearch()
            currentNamespace = null
            currentConfiguration = null
            currentSearchRequest = null
            SwingUtilities.invokeLater {
                searchPanel.clearAllCriteria()
                configDetailPanel.clearConfiguration()
                configListPanel.setConfigurations(emptyList())
                paginationPanel.reset()
            }

           val namespaceResult = namespacePanel.refreshAndWait()
            if (namespaceResult.isSuccess) {
                currentNamespace = namespacePanel.getSelectedNamespace()
                loadConfigurations()
            }
        }
    }

    private fun handleProjectEnvironmentSelectionChanged() {
        coroutineScope.launch {
            refreshOperationContextSnapshot()
            nacosSearchService.resetSearch()
            currentNamespace = null
            currentConfiguration = null
            currentSearchRequest = null
            SwingUtilities.invokeLater {
                searchPanel.clearAllCriteria()
                configDetailPanel.clearConfiguration()
                configListPanel.setConfigurations(emptyList())
                paginationPanel.reset()
            }
            namespacePanel.refreshAndWait().onSuccess {
                currentNamespace = namespacePanel.getSelectedNamespace()
                loadConfigurations()
            }
        }
    }
    
    /**
     * Performs initial setup using InitializationManager
     */
    private fun performInitialization() {
        coroutineScope.launch {
            try {
                initializationManager.initialize(
                     namespacePanel = namespacePanel,
                     paginationPanel = paginationPanel,
                     onInitializationComplete = { state ->
                         when (state) {
                             is InitializationManager.InitializationState.Success -> {
                                 SwingUtilities.invokeLater {
                                     // Initialization completed successfully
                                 }
                             }
                             is InitializationManager.InitializationState.Error -> {
                                 SwingUtilities.invokeLater {
                                     showError(NacosSearchBundle.message("error.config.load.failed") + ": ${state.message}")
                                 }
                             }
                             else -> {}
                         }
                     }
                 )
            } catch (e: Exception) {
            SwingUtilities.invokeLater {
                showError(NacosSearchBundle.message("error.config.load.failed") + ": ${e.message}")
            }
        }
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
                    loadConfigurations()
                }
            } catch (e: Exception) {
                showError(NacosSearchBundle.message("error.config.load.failed") + ": ${e.message}")
            }
        }
    }
    
    override suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
        // Keep the window's notion of the active namespace in sync with the service so that
        // subsequent reloads target the correct namespace.
        currentNamespace = newNamespace
        currentConfiguration = null
        searchCriteria = null
        currentSearchRequest = null

        clearSearchUi()

        if (newNamespace != null) {
            // Prefer cached list-page data when available — avoids a forced
            // remote API call on every namespace switch. The user can still
            // pull fresh data via the refresh button.
            refreshOperationContextSnapshot()
            val profileId = selectedProfileId()
            val operationContext = selectedOperationContext()
            val serverSnapshot = settings.captureServerSnapshot(profileId, operationContext)
            val request = NacosSearchService.SearchRequest(
                namespace = newNamespace,
                pageNo = 1,
                pageSize = paginationPanel.getCurrentPageSize(),
                forceRefresh = false,
                serverId = profileId,
                serverSnapshot = serverSnapshot,
                operationContext = operationContext
            )
            currentSearchRequest = request
            loadConfigurations()

            // Preheat the full namespace index in the background so the first
            // content/regex/wildcard search over this namespace is instant.
            preheatNamespaceIndex(
                settings.captureNamespaceIndexRequest(
                    newNamespace.namespaceId,
                    serverSnapshot,
                    operationContext
                )
            )
        } else {
            clearSearchUi()
        }
    }

    private fun clearSearchUi() {
        val hasPendingNav = pendingNavigationTarget != null
        val clearAction = {
            searchPanel.clearAllCriteria()
            // Preserve the detail panel when a cross-namespace navigation is
            // in flight — navigateToConfig already showed the target config.
            if (!hasPendingNav) {
                configDetailPanel.clearConfiguration()
            }
            configListPanel.setConfigurations(emptyList())
            paginationPanel.reset()
        }

        if (SwingUtilities.isEventDispatchThread()) {
            clearAction()
        } else {
            SwingUtilities.invokeLater(clearAction)
        }
    }
   
   private fun handleSearchRequested(criteria: SearchCriteria) {
         val searchNameSpace = namespacePanel.getSelectedNamespace()
         if (searchNameSpace == null) {
             showError(NacosSearchBundle.message("namespace.select.first"))
             return
         }
         
         // Create enhanced SearchRequest for prefix asterisk fuzzy search
         val searchRequest = NacosSearchService.SearchRequest(
             dataId = (criteria.dataId.ifBlank { criteria.query }),
             group = criteria.group,
             query = criteria.query,
             searchContent = criteria.searchContent,
             caseSensitive = criteria.caseSensitive,
             useRegex = criteria.useRegex,
             namespace = searchNameSpace,
             pageNo = 1,
             pageSize = paginationPanel.getCurrentPageSize(),
             serverId = selectedProfileId(),
             serverSnapshot = settings.captureServerSnapshot(selectedProfileId()),
             operationContext = selectedOperationContext()
         )
         currentNamespace = searchNameSpace;
         
         val processedCriteria = SearchCriteria(
             dataId = searchRequest.getProcessedDataId(),
             group = criteria.group ?: "",
             namespaceId = searchNameSpace.namespaceId,
             query = criteria.query,
             searchContent = criteria.searchContent,
             useRegex = criteria.useRegex,
             caseSensitive = criteria.caseSensitive
         )
         
         searchCriteria = processedCriteria
         currentSearchRequest = searchRequest
         coroutineScope.launch {
             nacosSearchService.performSearch(searchRequest, nacosApiService)
         }
     }
    
    private fun handleSearchCleared() {
        searchCriteria = null
        loadConfigurations()
        paginationPanel.reset()
        nacosSearchService.resetSearch()
        currentSearchRequest = null
    }
    
    private fun handleRealTimeSearch(query: String) {
        if (query.isBlank()) {
            handleSearchCleared()
            return
        }

        currentNamespace = namespacePanel.getSelectedNamespace()
        if (currentNamespace == null) {
            showError(NacosSearchBundle.message("namespace.select.first"))
            return
        }

        // useRegex routes the request through the local index so partial dataId/group values
        // match (Nacos "accurate" mode only matches exact dataIds, which made typing a partial
        // name return zero results).
        val searchRequest = NacosSearchService.SearchRequest(
            dataId = query,
            query = query,
            namespace = currentNamespace,
            useRegex = true,
            pageNo = 1,
            pageSize = paginationPanel.getCurrentPageSize(),
            serverId = selectedProfileId(),
            serverSnapshot = settings.captureServerSnapshot(selectedProfileId()),
            operationContext = selectedOperationContext()
        )
       currentSearchRequest = searchRequest

        // Debounce so rapid typing only triggers one search after the user
        // pauses. searchWithDebounce cancels the previous in-flight search and
        // launches its own coroutine in this scope.
        nacosSearchService.searchWithDebounce(searchRequest, nacosApiService, coroutineScope)
    }

    private fun handlePreviousPage() {
        coroutineScope.launch {
            val currentRequest = NacosSearchService.SearchRequest(
                 namespace = namespacePanel.getSelectedNamespace(),
                 serverId = selectedProfileId(),
                 serverSnapshot = settings.captureServerSnapshot(selectedProfileId()),
                 operationContext = selectedOperationContext()
             )
            nacosSearchService.previousPage(currentSearchRequest ?: currentRequest, nacosApiService)
        }
    }
    
    private fun handleNextPage() {
        coroutineScope.launch {
            val currentRequest = NacosSearchService.SearchRequest(
                 namespace = namespacePanel.getSelectedNamespace(),
                 serverId = selectedProfileId(),
                 serverSnapshot = settings.captureServerSnapshot(selectedProfileId()),
                 operationContext = selectedOperationContext()
             )
            nacosSearchService.nextPage(currentSearchRequest ?: currentRequest, nacosApiService)
        }
    }
    
    private fun handlePageSizeChanged(pageSize: Int) {
        coroutineScope.launch {
            val currentRequest = NacosSearchService.SearchRequest(
                 namespace = namespacePanel.getSelectedNamespace(),
                 pageSize = pageSize,
                 serverId = selectedProfileId(),
                 serverSnapshot = settings.captureServerSnapshot(selectedProfileId()),
                 operationContext = selectedOperationContext()
             )
            currentSearchRequest = (currentSearchRequest ?: currentRequest).copy(pageNo = 1, pageSize = pageSize)
            nacosSearchService.changePageSize(currentSearchRequest ?: currentRequest, pageSize, nacosApiService)
        }
    }
    
    private fun setupSearchServiceObservers() {
        // Observe search state changes
        coroutineScope.launch {
            nacosSearchService.searchState.collect { state ->
                when (state) {
                    is NacosSearchService.SearchState.Idle -> {
                        SwingUtilities.invokeLater {
                            setSearching(false)
                        }
                    }
                    is NacosSearchService.SearchState.Loading -> {
                        SwingUtilities.invokeLater {
                            setSearching(true)
                            configListPanel.setLoading(true)
                            paginationPanel.setLoading(true)
                        }
                    }
                    is NacosSearchService.SearchState.Success -> {
                        SwingUtilities.invokeLater {
                            // Drop results that were produced for a different environment
                            // (e.g. an in-flight search that completes after switching servers).
                            val requestServerId = state.request?.serverId.orEmpty()
                            if (requestServerId.isNotEmpty() && requestServerId != selectedProfileId()) {
                                return@invokeLater
                            }
                            val resultNamespaceId = state.request?.namespace?.namespaceId.orEmpty()
                            val activeNamespaceId = currentNamespace?.namespaceId.orEmpty()
                            if (resultNamespaceId != activeNamespaceId) {
                                return@invokeLater
                            }
                            setSearching(false)
                            paginationPanel.setLoading(false)
                            configListPanel.setSearchQuery(searchPanel.getSearchQuery())
                            updateConfigurationList(state.configurations)
                            configListPanel.setConfidenceStatus(state.confidence, state.coverage)
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
                        SwingUtilities.invokeLater {
                            setSearching(false)
                            configListPanel.setLoading(false)
                            paginationPanel.setLoading(false)
                            showError(NacosSearchBundle.message("error.search.failed") + ": ${state.message}")
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
    
    private fun updateConfigurationList(configurations: List<NacosConfiguration>) {
        currentConfigurations = configurations
        configListPanel.setConfigurations(configurations)
        // Populate group filter with unique groups from current results
        searchPanel.setAvailableGroups(configurations.map { it.group }.filter { it.isNotBlank() })
        if (configurations.isEmpty() && pendingNavigationTarget == null) {
            configDetailPanel.clearConfiguration()
        }

        // Consume a navigation target stashed before a namespace switch.
        // The detail panel was already shown in navigateToConfig; here we
        // only need to select the row in the freshly loaded list.
        pendingNavigationTarget?.let { (targetConfig, lineIndex) ->
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
    
    private fun handleConfigurationSelected(config: NacosConfiguration?) {
        currentConfiguration = config
        if (config != null) {
            loadConfigurationDetail(config)
        } else {
            configDetailPanel.clearConfiguration()
        }
    }
    
    private fun handleConfigurationDoubleClicked(config: NacosConfiguration) {
        // Handle double-click on configuration
        // This could open the configuration in an editor or show detailed view
        currentConfiguration = config
        loadConfigurationDetail(config)
        
        // Focus on the detail panel to show the configuration content
        configDetailPanel.requestFocus()
    }
    
    private fun handleRefreshRequested() {
        currentSearchRequest = (currentSearchRequest ?: NacosSearchService.SearchRequest(
            namespace = namespacePanel.getSelectedNamespace(),
            pageSize = paginationPanel.getCurrentPageSize(),
            serverId = selectedProfileId(),
            serverSnapshot = settings.captureServerSnapshot(selectedProfileId()),
            operationContext = selectedOperationContext()
        )).copy(forceRefresh = true)
        loadConfigurations()
    }
    
    private fun handleDetailRefreshRequested() {
        currentConfiguration?.let { config ->
            loadConfigurationDetail(config)
        }
    }
    
    private fun handleConfigurationUpdated(config: NacosConfiguration) {
        // Refresh the configuration list to reflect changes
        configListPanel.refresh()
        
        // Update current configuration
        currentConfiguration = config
    }
    
    private fun loadConfigurations() {
        val namespace = currentNamespace
        if (namespace == null) {
            configListPanel.setConfigurations(emptyList())
            return
        }
        
        coroutineScope.launch {
            try {
                val request = (currentSearchRequest ?: NacosSearchService.SearchRequest(
                    namespace = namespace,
                    pageNo = 1,
                    pageSize = paginationPanel.getCurrentPageSize(),
                    serverId = selectedProfileId(),
                    serverSnapshot = settings.captureServerSnapshot(selectedProfileId()),
                    operationContext = selectedOperationContext()
                )).copy(namespace = namespace)
                currentSearchRequest = request
                nacosSearchService.performSearch(request, nacosApiService)
                currentSearchRequest = request.copy(forceRefresh = false)
                
            } catch (e: Exception) {
                showError(NacosSearchBundle.message("error.config.load.failed") + ": ${e.message}")
                configListPanel.setConfigurations(emptyList())
            }
        }
    }
    
    private suspend fun searchConfigurations(
        namespace: NamespaceInfo,
        _criteria: SearchCriteria
    ): List<NacosConfiguration> {
        return withContext(Dispatchers.IO) {
            val context = selectedOperationContext()
            val listConfigurations = nacosApiService.listConfigurations(
                namespace.namespaceId,
                operationContext = context
            )
            val configurations = listConfigurations.getOrNull()?.pageItems?.map { item ->
                nacosApiService.getConfigurationFromItem(item, useCache = true, operationContext = context)
            }
            if(configurations == null) {
                emptyList<NacosConfiguration>()
            }
            configurations!!
        }
    }
    
    private suspend fun matchesSearchCriteria(
        config: NacosConfiguration,
        criteria: SearchCriteria
    ): Boolean {
        return withContext(Dispatchers.IO) {
            // Check general query
            if (!criteria.query.isNullOrEmpty()) {
                val query = if (criteria.caseSensitive) criteria.query else criteria.query.lowercase()
                val dataId = if (criteria.caseSensitive) config.dataId else config.dataId.lowercase()
                val group = if (criteria.caseSensitive) config.group else config.group.lowercase()
                
                val matches = if (criteria.useRegex) {
                    try {
                        val regex = Regex(query)
                        regex.containsMatchIn(dataId) || regex.containsMatchIn(group)
                    } catch (e: Exception) {
                        dataId.contains(query) || group.contains(query)
                    }
                } else {
                    dataId.contains(query) || group.contains(query)
                }
                
                if (!matches) return@withContext false
            }
            
            // Check specific field patterns
            if (!criteria.dataId.isNullOrEmpty()) {
                val pattern = if (criteria.caseSensitive) criteria.dataId else criteria.dataId.lowercase()
                val dataId = if (criteria.caseSensitive) config.dataId else config.dataId.lowercase()
                
                val matches = if (criteria.useRegex) {
                    try {
                        val regex = Regex(pattern)
                        regex.containsMatchIn(dataId)
                    } catch (e: Exception) {
                        dataId.contains(pattern)
                    }
                } else {
                    dataId.contains(pattern)
                }
                
                if (!matches) return@withContext false
            }
            
            if (!criteria.group.isNullOrEmpty()) {
                val pattern = if (criteria.caseSensitive) criteria.group else criteria.group.lowercase()
                val group = if (criteria.caseSensitive) config.group else config.group.lowercase()
                
                val matches = if (criteria.useRegex) {
                    try {
                        val regex = Regex(pattern)
                        regex.containsMatchIn(group)
                    } catch (e: Exception) {
                        group.contains(pattern)
                    }
                } else {
                    group.contains(pattern)
                }
                
                if (!matches) return@withContext false
            }
            
            // Check content pattern (requires loading configuration content)
            if (criteria.searchContent && !criteria.query.isNullOrEmpty()) {
                try {
                    val configResult = nacosApiService.getConfiguration(
                        config.dataId,
                        config.group,
                        currentNamespace?.namespaceId,
                        operationContext = selectedOperationContext()
                    )
                    
                    val configDetail = configResult.getOrNull()
                    if (configDetail == null) {
                        return@withContext false
                    }
                    
                    val pattern = criteria.query
                    val content = configDetail.content
                    
                    val matches = if (criteria.useRegex) {
                        try {
                            val regex = if (criteria.caseSensitive) {
                                Regex(pattern)
                            } else {
                                Regex(pattern, RegexOption.IGNORE_CASE)
                            }
                            regex.containsMatchIn(content)
                        } catch (e: Exception) {
                            content.contains(pattern, ignoreCase = !criteria.caseSensitive)
                        }
                    } else {
                        content.contains(pattern, ignoreCase = !criteria.caseSensitive)
                    }
                    
                    if (!matches) return@withContext false
                } catch (e: Exception) {
                    // If we can't load content, exclude from results
                    return@withContext false
                }
            }
            
            true
        }
    }
    
    private fun loadConfigurationDetail(config: NacosConfiguration) {
        configDetailPanel.showConfiguration(config)
    }
    
    private fun showError(message: String) {
        SwingUtilities.invokeLater {
            configListPanel.showError(message)
        }
    }
    
    /**
     * Get current namespace
     */
    fun getCurrentNamespace(): NamespaceInfo? = currentNamespace
    
    /**
     * Get current configuration
     */
    fun getCurrentConfiguration(): NacosConfiguration? = currentConfiguration
    
    /**
     * Set focus to search field
     */
    fun focusSearch() {
        searchPanel.focusSearchField()
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
        ApplicationManager.getApplication().invokeLater {
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
     * Refresh all data
     */
    fun refreshAll() {
        coroutineScope.launch {
            try {
                val namespaceResult = namespacePanel.refreshAndWait()
                if (namespaceResult.isSuccess) {
                    currentNamespace = namespacePanel.getSelectedNamespace()
                }
                loadConfigurations()
                currentConfiguration?.let { config ->
                    loadConfigurationDetail(config)
                }
            } catch (e: Exception) {
                showError(NacosSearchBundle.message("error.config.load.failed") + ": ${e.message}")
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
                    NacosKeyResolver.ensureIndexBuilt(cacheService, indexRequest.key.identity)
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
     * Get the tool window
     */
    fun getToolWindow(): ToolWindow = toolWindow
    
    /**
     * Check if window is ready
     */
    fun isReady(): Boolean {
        return ::namespacePanel.isInitialized &&
               ::searchPanel.isInitialized &&
               ::configListPanel.isInitialized &&
               ::configDetailPanel.isInitialized
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
    
    /**
     * Called when the language is changed
     */
    override fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage) {
        // Refresh all child components
        if (::namespacePanel.isInitialized) {
            (namespacePanel as? LanguageAwareComponent)?.onLanguageChanged(newLanguage)
        }
        if (::searchPanel.isInitialized) {
            (searchPanel as? LanguageAwareComponent)?.onLanguageChanged(newLanguage)
        }
        if (::configListPanel.isInitialized) {
            (configListPanel as? LanguageAwareComponent)?.onLanguageChanged(newLanguage)
        }
        if (::configDetailPanel.isInitialized) {
            (configDetailPanel as? LanguageAwareComponent)?.onLanguageChanged(newLanguage)
        }
        if (::paginationPanel.isInitialized) {
            (paginationPanel as? LanguageAwareComponent)?.onLanguageChanged(newLanguage)
        }
        
        // Revalidate and repaint the UI
        revalidate()
        repaint()
    }
    
    /**
     * Get the current language service
     */
    override fun getLanguageService(): LanguageService {
        return languageService
    }
}
