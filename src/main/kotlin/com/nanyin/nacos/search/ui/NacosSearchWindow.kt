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
import com.nanyin.nacos.search.settings.NacosConfigurable
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.NacosSettingsListener
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
    
    init {
        initializeComponents()
        setupLayout()
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
        // Reload the configuration list whenever the selected namespace changes.
        namespaceService.addNamespaceChangeListener(this)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            NacosSettingsListener.TOPIC,
            object : NacosSettingsListener {
                override fun settingsChanged() {
                    handleSettingsChanged()
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
        
        // Initialize UI with proper namespace and pagination using InitializationManager
        performInitialization()
    }

    private fun handleSettingsChanged() {
        // Switcher label reflects the new active environment immediately.
        SwingUtilities.invokeLater { environmentSwitcher.refresh() }
        coroutineScope.launch {
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
            if (namespaceResult.isSuccess && settings.getActiveServer().autoRefreshOnOpen) {
                currentNamespace = namespaceService.getCurrentNamespace()
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
                val currentNs = namespaceService.getCurrentNamespace()
                if (currentNs != null && settings.getActiveServer().autoRefreshOnOpen) {
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
            // Fresh listing for the new namespace — no stale search criteria carried over.
            val request = NacosSearchService.SearchRequest(
                namespace = newNamespace,
                pageNo = 1,
                pageSize = paginationPanel.getCurrentPageSize(),
                forceRefresh = true,
                serverId = settings.activeServerId
            )
            currentSearchRequest = request
            loadConfigurations()

            // Preheat the full namespace index in the background so the first
            // content/regex/wildcard search over this namespace is instant.
            preheatNamespaceIndex(newNamespace.namespaceId)
        } else {
            clearSearchUi()
        }
    }

    private fun clearSearchUi() {
        val clearAction = {
            searchPanel.clearAllCriteria()
            configDetailPanel.clearConfiguration()
            configListPanel.setConfigurations(emptyList())
            paginationPanel.reset()
        }

        if (SwingUtilities.isEventDispatchThread()) {
            clearAction()
        } else {
            SwingUtilities.invokeAndWait(clearAction)
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
             serverId = settings.activeServerId
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
            serverId = settings.activeServerId
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
                 namespace = namespacePanel.getSelectedNamespace()
             )
            nacosSearchService.previousPage(currentSearchRequest ?: currentRequest, nacosApiService)
        }
    }
    
    private fun handleNextPage() {
        coroutineScope.launch {
            val currentRequest = NacosSearchService.SearchRequest(
                 namespace = namespacePanel.getSelectedNamespace()
             )
            nacosSearchService.nextPage(currentSearchRequest ?: currentRequest, nacosApiService)
        }
    }
    
    private fun handlePageSizeChanged(pageSize: Int) {
        coroutineScope.launch {
            val currentRequest = NacosSearchService.SearchRequest(
                 namespace = namespacePanel.getSelectedNamespace(),
                 pageSize = pageSize
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
                            if (requestServerId.isNotEmpty() && requestServerId != settings.activeServerId) {
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
        if (configurations.isEmpty()) {
            configDetailPanel.clearConfiguration()
        }

        // Consume a navigation target stashed before a namespace switch.
        pendingNavigationTarget?.let { (targetConfig, lineIndex) ->
            pendingNavigationTarget = null
            configListPanel.selectConfiguration(targetConfig)
            currentConfiguration = targetConfig
            configDetailPanel.showConfiguration(targetConfig, lineIndex)
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
            serverId = settings.activeServerId
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
                    serverId = settings.activeServerId
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
            val listConfigurations = nacosApiService.listConfigurations(namespace.namespaceId)
            val configurations = listConfigurations.getOrNull()?.pageItems?.map { item ->
                nacosApiService.getConfigurationFromItem(item, useCache = true)
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
                        currentNamespace?.namespaceId
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
            val targetNsId = config.tenantId ?: ""
            val currentNsId = currentNamespace?.namespaceId

            if (targetNsId == currentNsId) {
                // Same namespace: select and show immediately.
                configListPanel.selectConfiguration(config)
                currentConfiguration = config
                configDetailPanel.showConfiguration(config, lineIndex)
            } else {
                // Different namespace: stash the target and switch.
                // The result is consumed in updateConfigurationList after the reload.
                pendingNavigationTarget = config to lineIndex
                val target = namespaceService.findNamespaceById(targetNsId)
                if (target != null) {
                    namespaceService.setCurrentNamespace(target)
                } else {
                    // Namespace not in the available list (e.g. not loaded yet).
                    // Fall back: show the detail directly with the original config.
                    pendingNavigationTarget = null
                    currentConfiguration = config
                    configDetailPanel.showConfiguration(config, lineIndex)
                }
            }
        }
    }
    
    /**
     * Refresh all data
     */
    fun refreshAll() {
        coroutineScope.launch {
            try {
                val namespaceResult = namespacePanel.refreshAndWait()
                if (namespaceResult.isSuccess) {
                    currentNamespace = namespaceService.getCurrentNamespace()
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
        namespaceService.removeNamespaceChangeListener(this)
        coroutineScope.cancel()
    }

    /**
     * Preheat the full namespace index for [namespaceId] in the background.
     * Best-effort: on failure the on-demand pull path in
     * [NacosSearchService.searchWithLocalIndex] remains the fallback.
     */
    private fun preheatNamespaceIndex(namespaceId: String?) {
        if (!settings.cacheEnabled) return
        val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val result = nacosApiService.getAllConfigurations(namespaceId, useCache = true)
                if (result.isSuccess) {
                    val configs = result.getOrNull().orEmpty()
                    cacheService.putNamespaceIndex(
                        settings.serverUrl,
                        namespaceId,
                        configs,
                        settings.getCacheTtlMillis()
                    )
                    NacosKeyResolver.ensureIndexBuilt(cacheService)
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
