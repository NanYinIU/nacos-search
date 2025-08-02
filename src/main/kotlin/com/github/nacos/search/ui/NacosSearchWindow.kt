package com.github.nacos.search.ui

import com.github.nacos.search.managers.InitializationManager
import com.github.nacos.search.models.NacosConfiguration
import com.github.nacos.search.models.NamespaceInfo
import com.github.nacos.search.models.SearchCriteria
import com.github.nacos.search.services.NamespaceService
import com.github.nacos.search.services.NacosApiService
// import com.github.nacos.search.services.NacosConfigService // Not needed
import com.github.nacos.search.services.NacosSearchService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Main window for Nacos Search plugin
 * Integrates all UI components and manages their interactions
 */
class NacosSearchWindow(private val project: Project, private val toolWindow: ToolWindow) : JPanel(BorderLayout()) {
    
    // Services
    private val namespaceService = project.service<NamespaceService>()
    private val nacosApiService = project.service<NacosApiService>()
    // private val nacosConfigService = project.service<NacosConfigService>() // Not needed
    private val nacosSearchService = NacosSearchService()
    
    // Managers
    private lateinit var initializationManager: InitializationManager
    
    // UI Components
    private lateinit var namespacePanel: NamespacePanel
    private lateinit var searchPanel: SearchPanel
    private lateinit var configListPanel: ConfigListPanel
    private lateinit var configDetailPanel: ConfigDetailPanel
    private lateinit var paginationPanel: PaginationPanel
    
    // Layout components
    private lateinit var mainSplitter: JBSplitter
    private lateinit var rightSplitter: JBSplitter
    
    // State
    private var currentNamespace: NamespaceInfo? = null
    private var currentConfiguration: NacosConfiguration? = null
    private var searchCriteria: SearchCriteria? = null
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
        searchPanel = SearchPanel(project)
        configListPanel = ConfigListPanel(project)
        configDetailPanel = ConfigDetailPanel(project)
        paginationPanel = PaginationPanel()
        
        // Initialize managers
        initializationManager = InitializationManager(
            namespaceService,
            nacosApiService,
            nacosSearchService,
            coroutineScope
        )
        
        // Configure components
        configListPanel.preferredSize = Dimension(400, 300)
        configDetailPanel.preferredSize = Dimension(600, 300)
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty()
        
        // Create top panel with namespace and search
        val topPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            
            add(namespacePanel, BorderLayout.NORTH)
            add(searchPanel, BorderLayout.CENTER)
        }
        
        // Create config list panel with pagination
        val configListWithPagination = JPanel(BorderLayout()).apply {
            add(JBScrollPane(configListPanel).apply {
                minimumSize = Dimension(300, 200)
            }, BorderLayout.CENTER)
            add(paginationPanel, BorderLayout.SOUTH)
        }
        
        // Create right splitter for config list and detail
        rightSplitter = JBSplitter(true, 0.8f).apply {
            firstComponent = configListWithPagination
            secondComponent = JBScrollPane(configDetailPanel).apply {
                minimumSize = Dimension(400, 200)
            }
        }
        
        // Create main splitter
        mainSplitter = JBSplitter(true, 0.1f).apply {
            firstComponent = topPanel
            secondComponent = rightSplitter
        }
        
        add(mainSplitter, BorderLayout.CENTER)
    }
    
    private fun setupEventHandlers() {
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
        // Note: ConfigListPanel doesn't have onRefreshRequested property
        // This will be handled internally by the panel
        
        // Configuration detail handlers
        // Note: ConfigDetailPanel doesn't have onConfigurationUpdated and onRefreshRequested properties
        // These events will be handled internally by the panel
        
        // Search service state observation
        setupSearchServiceObservers()
        
        // Initialize UI with proper namespace and pagination using InitializationManager
        performInitialization()
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
                                     showError("Initialization failed: ${state.message}")
                                 }
                             }
                             else -> {}
                         }
                     }
                 )
            } catch (e: Exception) {
            SwingUtilities.invokeLater {
                showError("Failed to initialize: ${e.message}")
            }
        }
        }
    }
    
    private fun loadInitialData() {
        coroutineScope.launch {
            try {
                // Load namespaces first
                namespacePanel.refresh()
                
                // Get current namespace and load configurations
                val currentNs = namespaceService.getCurrentNamespace()
                if (currentNs != null) {
                    currentNamespace = currentNs
                    loadConfigurations()
                }
            } catch (e: Exception) {
                showError("Failed to load initial data: ${e.message}")
            }
        }
    }
    
    private fun handleNamespaceChanged(namespace: NamespaceInfo?) {
        currentNamespace = namespace
        currentConfiguration = null
        searchCriteria = null
        
        // Clear search
        searchPanel.clearAllCriteria()
        
        // Clear detail panel
        configDetailPanel.clearConfiguration()
        
        // Load configurations for new namespace
        loadConfigurations()
    }
    
    private fun handleSearchRequested(criteria: SearchCriteria) {
         val searchNameSpace = namespacePanel.getSelectedNamespace()
         if (searchNameSpace == null) {
             showError("Please select a namespace first")
             return
         }
         
         // Create enhanced SearchRequest for prefix asterisk fuzzy search
         val searchRequest = NacosSearchService.SearchRequest(
             dataId = criteria.dataId ?: "",
             group = criteria.group,
             namespace = searchNameSpace
         )
        currentNamespace = searchNameSpace;
         
         val processedCriteria = SearchCriteria(
             dataId = searchRequest.getProcessedDataId(),
             group = criteria.group,
             namespaceId = searchNameSpace.namespaceId,
             query = criteria.query,
             searchContent = criteria.searchContent,
             useRegex = criteria.useRegex,
             caseSensitive = criteria.caseSensitive
         )
         
         searchCriteria = processedCriteria
         loadConfigurations()
     }
    
    private fun handleSearchCleared() {
        searchCriteria = null
        loadConfigurations()
        paginationPanel.reset()
        nacosSearchService.resetSearch()
    }
    
    private fun handleRealTimeSearch(query: String) {
        if (query.isBlank()) {
            handleSearchCleared()
            return
        }
        
        val currentNamespace = namespacePanel.getSelectedNamespace()
        if (currentNamespace == null) {
            showError("请先选择命名空间")
            return
        }
        
        // Perform real-time search with debouncing using enhanced SearchRequest
         val searchRequest = NacosSearchService.SearchRequest(
            dataId = query,
            namespace = currentNamespace
        )
         
         coroutineScope.launch {
             try {
                 nacosSearchService.performSearch(searchRequest, nacosApiService)
             } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        showError("Real-time search failed: ${e.message}")
                    }
                }
         }
    }
    
    private fun handlePreviousPage() {
        coroutineScope.launch {
            val currentRequest = NacosSearchService.SearchRequest(
                 namespace = namespacePanel.getSelectedNamespace()
             )
            nacosSearchService.previousPage(currentRequest, nacosApiService)
        }
    }
    
    private fun handleNextPage() {
        coroutineScope.launch {
            val currentRequest = NacosSearchService.SearchRequest(
                 namespace = namespacePanel.getSelectedNamespace()
             )
            nacosSearchService.nextPage(currentRequest, nacosApiService)
        }
    }
    
    private fun handlePageSizeChanged(pageSize: Int) {
        coroutineScope.launch {
            val currentRequest = NacosSearchService.SearchRequest(
                 namespace = namespacePanel.getSelectedNamespace(),
                 pageSize = pageSize
             )
            nacosSearchService.changePageSize(currentRequest, pageSize, nacosApiService)
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
                            paginationPanel.setLoading(true)
                        }
                    }
                    is NacosSearchService.SearchState.Success -> {
                        SwingUtilities.invokeLater {
                            setSearching(false)
                            paginationPanel.setLoading(false)
                            updateConfigurationList(state.configurations)
                            paginationPanel.updatePagination(
                                NacosSearchService.PaginationState(
                                    currentPage = state.pageNumber,
                                    pageSize = 10,
                                    totalCount = state.totalCount,
                                    totalPages = state.pagesAvailable
                                )
                            )
                        }
                    }
                    is NacosSearchService.SearchState.Error -> {
                        SwingUtilities.invokeLater {
                            setSearching(false)
                            paginationPanel.setLoading(false)
                            showError("搜索失败: ${state.message}")
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
        if (configurations.isEmpty()) {
            configDetailPanel.clearConfiguration()
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
                // Loading state is handled internally by ConfigListPanel
                
                val configurations: List<NacosConfiguration> = if (searchCriteria != null) {
                    // Perform search
                    searchConfigurations(namespace, searchCriteria!!)
                } else {
                    // Load all configurations
                    nacosApiService.getAllConfigurations(namespace.namespaceId).getOrElse { emptyList() }
                }
                
                configListPanel.setConfigurations(configurations)
                
            } catch (e: Exception) {
                showError("Failed to load configurations: ${e.message}")
                configListPanel.setConfigurations(emptyList())
            } finally {
                // Loading state is handled internally by ConfigListPanel
            }
        }
    }
    
    private suspend fun searchConfigurations(
        namespace: NamespaceInfo,
        criteria: SearchCriteria
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
                    val content = configDetail.content ?: ""
                    
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
            JOptionPane.showMessageDialog(
                this,
                message,
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
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
     * Refresh all data
     */
    fun refreshAll() {
        coroutineScope.launch {
            try {
                namespacePanel.refresh()
                loadConfigurations()
                currentConfiguration?.let { config ->
                    loadConfigurationDetail(config)
                }
            } catch (e: Exception) {
                showError("Failed to refresh data: ${e.message}")
            }
        }
    }
    
    /**
     * Cleanup resources
     */
    fun dispose() {
        coroutineScope.cancel()
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
}