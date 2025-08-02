package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.MatchType
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.SearchResult
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.SearchService
import com.nanyin.nacos.search.settings.NacosSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Main tool window for Nacos search functionality
 */
class NacosToolWindow(private val project: Project, private val toolWindow: ToolWindow) {
    
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val apiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    private val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
    private val searchService = ApplicationManager.getApplication().getService(SearchService::class.java)
    
    // UI Components
    private lateinit var searchField: JBTextField
    private lateinit var groupFilterCombo: ComboBox<String>
    private lateinit var tenantFilterCombo: ComboBox<String>
    private lateinit var searchTypeCombo: ComboBox<SearchType>
    private lateinit var resultsTable: JBTable
    private lateinit var detailsPanel: JPanel
    private lateinit var contentArea: JBTextArea
    private lateinit var statusLabel: JLabel
    private lateinit var resultCountLabel: JLabel
    
    private var searchResults: List<SearchResult> = emptyList()
    private var tableModel: SearchResultTableModel = SearchResultTableModel()
    
    enum class SearchType(val displayName: String) {
        ALL("All Fields"),
        DATA_ID("Data ID"),
        GROUP("Group"),
        CONTENT("Content"),
        REGEX("Regex")
    }
    
    fun createContent(): JComponent {
        initializeComponents()
        setupLayout()
        setupEventHandlers()
        loadInitialData()
        
        return createMainPanel()
    }
    
    private fun initializeComponents() {
        searchField = JBTextField(30).apply {
            emptyText.text = "Search configurations..."
        }
        
        groupFilterCombo = ComboBox<String>().apply {
            addItem("All Groups")
        }
        
        tenantFilterCombo = ComboBox<String>().apply {
            addItem("All Tenants")
        }
        
        searchTypeCombo = ComboBox<SearchType>().apply {
            SearchType.values().forEach { addItem(it) }
            selectedItem = SearchType.ALL
        }
        
        resultsTable = JBTable(tableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            fillsViewportHeight = true
        }
        
        contentArea = JBTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
        }
        
        detailsPanel = JPanel(BorderLayout())
        statusLabel = JLabel("Ready")
        resultCountLabel = JLabel("0 results")
        
        setupTableColumns()
    }
    
    private fun setupTableColumns() {
        val columnModel = resultsTable.columnModel
        
        // Data ID column
        columnModel.getColumn(0).apply {
            preferredWidth = 200
            minWidth = 150
        }
        
        // Group column
        columnModel.getColumn(1).apply {
            preferredWidth = 150
            minWidth = 100
        }
        
        // Tenant column
        columnModel.getColumn(2).apply {
            preferredWidth = 100
            minWidth = 80
        }
        
        // Type column
        columnModel.getColumn(3).apply {
            preferredWidth = 80
            minWidth = 60
        }
        
        // Score column
        columnModel.getColumn(4).apply {
            preferredWidth = 60
            minWidth = 50
            cellRenderer = ScoreCellRenderer()
        }
        
        // Last Modified column
        columnModel.getColumn(5).apply {
            preferredWidth = 150
            minWidth = 120
        }
    }
    
    private fun setupLayout(): JPanel {
        val searchPanel = createSearchPanel()
        val contentPanel = createContentPanel()
        val statusPanel = createStatusPanel()
        
        return JPanel(BorderLayout()).apply {
            add(searchPanel, BorderLayout.NORTH)
            add(contentPanel, BorderLayout.CENTER)
            add(statusPanel, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(5)
        }
    }
    
    private fun createSearchPanel(): JPanel {
        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 0, 5, 0)
        }
        
        val searchFieldPanel = JPanel(BorderLayout()).apply {
            add(JLabel("Search: "), BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }
        
        val filtersPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(JLabel("Type:") as Component)
            add(searchTypeCombo as Component)
            add(JLabel("Group:") as Component)
            add(groupFilterCombo as Component)
            add(JLabel("Tenant:") as Component)
            add(tenantFilterCombo as Component)
        }
        
        val actionsPanel = createActionsPanel()
        
        searchPanel.add(searchFieldPanel, BorderLayout.NORTH)
        searchPanel.add(filtersPanel, BorderLayout.CENTER)
        searchPanel.add(actionsPanel, BorderLayout.SOUTH)
        
        return searchPanel
    }
    
    private fun createActionsPanel(): JPanel {
        val actionGroup = DefaultActionGroup().apply {
            add(RefreshAction())
            add(ClearCacheAction())
            add(Separator())
            add(ExpandAllAction())
            add(CollapseAllAction())
            add(Separator())
            add(ExportAction())
        }
        
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "NacosToolWindow",
            actionGroup,
            true
        )
        
        return JPanel(BorderLayout()).apply {
            add(toolbar.component as Component, BorderLayout.WEST)
            add(resultCountLabel as Component, BorderLayout.EAST)
        }
    }
    
    private fun createContentPanel(): JComponent {
        val splitter = Splitter(false, 0.3f).apply {
            firstComponent = JBScrollPane(resultsTable).apply {
                border = IdeBorderFactory.createTitledBorder("Search Results")
            }
            secondComponent = createDetailsPanel()
        }
        
        return splitter
    }
    
    private fun createDetailsPanel(): JComponent {
        detailsPanel.apply {
            border = IdeBorderFactory.createTitledBorder("Configuration Details")
            
            val headerPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(5)
            }
            
            val contentScrollPane = JBScrollPane(contentArea).apply {
                preferredSize = Dimension(400, 100)
            }
            
            add(headerPanel, BorderLayout.NORTH)
            add(contentScrollPane, BorderLayout.CENTER)
        }
        
        return detailsPanel
    }
    
    private fun createStatusPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 0, 0, 0)
            add(statusLabel, BorderLayout.WEST)
        }
    }
    
    private fun createMainPanel(): JComponent {
        val mainPanel = SimpleToolWindowPanel(true, true)
        mainPanel.setContent(setupLayout())
        return mainPanel
    }
    
    private fun setupEventHandlers() {
        // Search field events
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    performSearch()
                } else {
                    // Debounced search for real-time filtering
                    SwingUtilities.invokeLater {
                        if (searchField.text.length >= 2) {
                            performSearch()
                        } else if (searchField.text.isEmpty()) {
                            showAllResults()
                        }
                    }
                }
            }
        })
        
        // Filter combo events
        groupFilterCombo.addActionListener { performSearch() }
        tenantFilterCombo.addActionListener { performSearch() }
        searchTypeCombo.addActionListener { performSearch() }
        
        // Table selection events
        resultsTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedRow = resultsTable.selectedRow
                if (selectedRow >= 0 && selectedRow < searchResults.size) {
                    showConfigurationDetails(searchResults[selectedRow])
                }
            }
        }
        
        // Table double-click events
        resultsTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selectedRow = resultsTable.selectedRow
                    if (selectedRow >= 0 && selectedRow < searchResults.size) {
                        openConfigurationInEditor(searchResults[selectedRow])
                    }
                }
            }
        })
    }
    
    private fun loadInitialData() {
        updateStatus("Loading configurations...")
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Nacos Configurations", true) {
            override fun run(indicator: ProgressIndicator) {
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                scope.launch {
                    try {
                        indicator.text = "Fetching configurations from cache..."
                        
                        val cachedConfigs = cacheService.getAllCachedConfigurations()
                        if (cachedConfigs.isNotEmpty()) {
                            withContext(Dispatchers.EDT) {
                                updateSearchResults(cachedConfigs.map { SearchResult(it, MatchType.MULTIPLE, "", 100) })
                                updateFilters(cachedConfigs)
                                updateStatus("Loaded ${cachedConfigs.size} configurations from cache")
                            }
                        } else {
                            indicator.text = "Fetching configurations from server..."
                            refreshFromServerAsync(indicator)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.EDT) {
                            updateStatus("Error loading configurations: ${e.message}")
                            Messages.showErrorDialog(
                                project,
                                "Failed to load configurations: ${e.message}",
                                "Load Error"
                            )
                        }
                    }
                }
            }
        })
    }
    
    private suspend fun refreshFromServerAsync(indicator: ProgressIndicator? = null) {
        try {
            indicator?.text = "Connecting to Nacos server..."
            
            val result = apiService.getAllConfigurations()
            
            if (result.isSuccess) {
                val configurations = result.getOrThrow()
                
                indicator?.text = "Caching configurations..."
                cacheService.cacheConfigurations(configurations)
                
                withContext(Dispatchers.EDT) {
                    updateSearchResults(configurations.map { SearchResult(it, MatchType.MULTIPLE, "", 100) })
                    updateFilters(configurations)
                    updateStatus("Refreshed ${configurations.size} configurations")
                }
            } else {
                withContext(Dispatchers.EDT) {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    updateStatus("Failed to refresh: $error")
                    Messages.showErrorDialog(
                        project,
                        "Failed to refresh configurations: $error",
                        "Refresh Error"
                    )
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.EDT) {
                updateStatus("Error during refresh: ${e.message}")
                Messages.showErrorDialog(
                    project,
                    "Error during refresh: ${e.message}",
                    "Refresh Error"
                )
            }
        }
    }
    
    private fun refreshFromServer(indicator: ProgressIndicator? = null) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            refreshFromServerAsync(indicator)
        }
    }
    
    private fun performSearch() {
        val query = searchField.text.trim()
        val searchType = searchTypeCombo.selectedItem as SearchType
        val groupFilter = groupFilterCombo.selectedItem as? String
        val tenantFilter = tenantFilterCombo.selectedItem as? String
        
        if (query.isEmpty()) {
            showAllResults()
            return
        }
        
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val results = when (searchType) {
                    SearchType.ALL -> searchService.searchConfigurations(query)
                    SearchType.DATA_ID -> searchService.searchByDataId(query)
                    SearchType.GROUP -> searchService.searchByGroup(query)
                    SearchType.CONTENT -> searchService.searchByContent(query)
                    SearchType.REGEX -> {
                        if (settings.enableRegexSearch) {
                            searchService.searchByRegex(query)
                        } else {
                            searchService.searchConfigurations(query)
                        }
                    }
                }
                
                val filteredResults = applyFilters(results, groupFilter, tenantFilter)
                
                withContext(Dispatchers.EDT) {
                    updateSearchResults(filteredResults)
                    updateStatus("Found ${filteredResults.size} results for '$query'")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) {
                    updateStatus("Error during search: ${e.message}")
                }
            }
        }
    }
    
    private fun showAllResults() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val allConfigs = cacheService.getAllCachedConfigurations()
                val results = allConfigs.map { SearchResult(it, MatchType.MULTIPLE, "", 100) }
                
                withContext(Dispatchers.EDT) {
                    updateSearchResults(results)
                    updateStatus("Showing all ${results.size} configurations")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) {
                    updateStatus("Error loading configurations: ${e.message}")
                }
            }
        }
    }
    
    private fun applyFilters(results: List<SearchResult>, groupFilter: String?, tenantFilter: String?): List<SearchResult> {
        var filtered = results
        
        if (groupFilter != null && groupFilter != "All Groups") {
            filtered = filtered.filter { it.configuration.group == groupFilter }
        }
        
        if (tenantFilter != null && tenantFilter != "All Tenants") {
            filtered = filtered.filter { it.configuration.tenantId == tenantFilter }
        }
        
        return filtered.take(settings.searchResultLimit)
    }
    
    private fun updateSearchResults(results: List<SearchResult>) {
        searchResults = results
        tableModel.updateResults(results)
        resultCountLabel.text = "${results.size} results"
        
        if (results.isNotEmpty()) {
            resultsTable.setRowSelectionInterval(0, 0)
        } else {
            clearDetails()
        }
    }
    
    private fun updateFilters(configurations: List<NacosConfiguration>) {
        val groups = configurations.map { it.group }.distinct().sorted()
        val tenants = configurations.mapNotNull { it.tenantId }.distinct().sorted()
        
        groupFilterCombo.removeAllItems()
        groupFilterCombo.addItem("All Groups")
        groups.forEach { groupFilterCombo.addItem(it) }
        
        tenantFilterCombo.removeAllItems()
        tenantFilterCombo.addItem("All Tenants")
        tenants.forEach { tenantFilterCombo.addItem(it) }
    }
    
    private fun showConfigurationDetails(result: SearchResult) {
        val config = result.configuration
        
        // Update details panel header
        detailsPanel.removeAll()
        
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(5)
            
            add(createDetailRow("Data ID:", config.dataId))
            add(createDetailRow("Group:", config.group))
            add(createDetailRow("Tenant:", config.tenantId ?: "N/A"))
            add(createDetailRow("Type:", config.type ?: "Unknown"))
            add(createDetailRow("MD5:", config.md5 ?: "N/A"))
            add(createDetailRow("Last Modified:", config.lastModified.toString()))
            
            add(createDetailRow("Matches:", result.getMatchSummary()))
        }
        
        contentArea.text = config.content
        contentArea.caretPosition = 0
        
        detailsPanel.add(headerPanel, BorderLayout.NORTH)
        detailsPanel.add(JBScrollPane(contentArea), BorderLayout.CENTER)
        detailsPanel.revalidate()
        detailsPanel.repaint()
    }
    
    private fun createDetailRow(label: String, value: String): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
            add(JLabel(label).apply {
                font = font.deriveFont(Font.BOLD)
                preferredSize = Dimension(100, preferredSize.height)
            })
            add(JLabel(value))
        }
    }
    
    private fun clearDetails() {
        detailsPanel.removeAll()
        detailsPanel.add(JLabel("Select a configuration to view details", SwingConstants.CENTER))
        detailsPanel.revalidate()
        detailsPanel.repaint()
    }
    
    private fun openConfigurationInEditor(result: SearchResult) {
        // TODO: Implement opening configuration in editor
        Messages.showInfoMessage(
            project,
            "Opening configuration in editor: ${result.configuration.dataId}",
            "Open Configuration"
        )
    }
    
    private fun updateStatus(message: String) {
        statusLabel.text = message
    }
    
    // Action classes
    private inner class RefreshAction : AnAction("Refresh", "Refresh configurations from server", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Refreshing Configurations", true) {
                override fun run(indicator: ProgressIndicator) {
                    refreshFromServer(indicator)
                }
            })
        }
    }
    
    private inner class ClearCacheAction : AnAction("Clear Cache", "Clear local cache", AllIcons.Actions.GC) {
        override fun actionPerformed(e: AnActionEvent) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Clearing Cache", false) {
                override fun run(indicator: ProgressIndicator) {
                    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                    scope.launch {
                        try {
                            cacheService.clearCache()
                            withContext(Dispatchers.EDT) {
                                updateSearchResults(emptyList())
                                updateStatus("Cache cleared")
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.EDT) {
                                updateStatus("Error clearing cache: ${e.message}")
                            }
                        }
                    }
                }
            })
        }
    }
    
    private inner class ExpandAllAction : AnAction("Expand All", "Expand all results", AllIcons.Actions.Expandall) {
        override fun actionPerformed(e: AnActionEvent) {
            // TODO: Implement expand all functionality
        }
    }
    
    private inner class CollapseAllAction : AnAction("Collapse All", "Collapse all results", AllIcons.Actions.Collapseall) {
        override fun actionPerformed(e: AnActionEvent) {
            // TODO: Implement collapse all functionality
        }
    }
    
    private inner class ExportAction : AnAction("Export", "Export search results", AllIcons.ToolbarDecorator.Export) {
        override fun actionPerformed(e: AnActionEvent) {
            // TODO: Implement export functionality
            Messages.showInfoMessage(
                project,
                "Export functionality will be implemented in a future version.",
                "Export"
            )
        }
    }
    
    // Table model for search results
    private class SearchResultTableModel : AbstractTableModel() {
        private var results: List<SearchResult> = emptyList()
        private val columnNames = arrayOf("Data ID", "Group", "Tenant", "Type", "Score", "Last Modified")
        
        fun updateResults(newResults: List<SearchResult>) {
            results = newResults
            fireTableDataChanged()
        }
        
        override fun getRowCount(): Int = results.size
        override fun getColumnCount(): Int = columnNames.size
        override fun getColumnName(column: Int): String = columnNames[column]
        
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val result = results[rowIndex]
            val config = result.configuration
            
            return when (columnIndex) {
                0 -> config.dataId
                1 -> config.group
                2 -> config.tenantId ?: "N/A"
                3 -> config.type ?: "Unknown"
                4 -> String.format("%.2f", result.score)
                5 -> config.lastModified.toString()
                else -> ""
            }
        }
    }
    
    // Custom cell renderer for score column
    private class ScoreCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            
            if (value is String) {
                val score = value.toDoubleOrNull() ?: 0.0
                when {
                    score >= 0.8 -> foreground = Color(0, 128, 0) // Green
                    score >= 0.5 -> foreground = Color(255, 165, 0) // Orange
                    else -> foreground = Color(128, 128, 128) // Gray
                }
            }
            
            return component
        }
    }
}