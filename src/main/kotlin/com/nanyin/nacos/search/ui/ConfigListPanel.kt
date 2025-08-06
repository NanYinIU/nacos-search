package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.listeners.NamespaceChangeListener
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.nanyin.nacos.search.services.NacosSearchService
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Panel for displaying and managing configuration list with namespace filtering
 */
class ConfigListPanel(private val project: Project) : JPanel(BorderLayout()), NamespaceChangeListener, LanguageAwareComponent {
    
    private val nacosApiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    private val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
    private val languageService = ApplicationManager.getApplication().getService(LanguageService::class.java)
    
    // UI Components
    private lateinit var configTable: JBTable
    private lateinit var tableModel: ConfigTableModel
    private lateinit var scrollPane: JBScrollPane
    private lateinit var loadingLabel: JBLabel
    private lateinit var statusLabel: JBLabel
    private lateinit var refreshButton: JButton
    private lateinit var emptyStatePanel: JPanel
    
    // State
    private var isLoading = false
    private var configurations: List<NacosConfiguration> = emptyList()
    private var currentNamespace: NamespaceInfo? = null
    private var currentPage = 1
    private var pageSize = 10
    
    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Configuration selection listener
    var onConfigurationSelected: ((NacosConfiguration) -> Unit)? = null
    
    init {
        initializeComponents()
        setupLayout()
        setupEventHandlers()
        
        // Register as namespace change listener
        namespaceService.addNamespaceChangeListener(this)
        
        // Load initial data
        currentNamespace = namespaceService.getCurrentNamespace()
        loadConfigurations()
    }
    
    private fun initializeComponents() {
        tableModel = ConfigTableModel()
        configTable = JBTable(tableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            fillsViewportHeight = true
            rowHeight = 24 // Increased row height for better readability
            
            // Set column widths with better proportions
            columnModel.getColumn(0).preferredWidth = 150 // Data ID
            columnModel.getColumn(1).preferredWidth = 120 // Group
            columnModel.getColumn(2).preferredWidth = 80 // Type
            columnModel.getColumn(3).preferredWidth = 200 // Namespace
            
            // Enable alternating row colors for better readability
            showHorizontalLines = false
            showVerticalLines = false
            intercellSpacing = Dimension(0, 0)
            
            // Improve selection appearance
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            setRowSelectionAllowed(true)
            setColumnSelectionAllowed(false)
        }
        
        scrollPane = JBScrollPane(configTable)
        
        loadingLabel = JBLabel().apply {
            icon = AnimatedIcon.Default.INSTANCE
            text = NacosSearchBundle.message("config.list.loading")
            horizontalAlignment = SwingConstants.CENTER
            isVisible = false
        }
        
        statusLabel = JBLabel(NacosSearchBundle.message("common.ready")).apply {
            foreground = Color.GRAY
            border = JBUI.Borders.empty(5)
        }
        
        refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = NacosSearchBundle.message("config.list.refresh")
            preferredSize = Dimension(24, 24)
        }
        
        emptyStatePanel = createEmptyStatePanel()
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty(2, 4, 2, 4) // Minimal padding for compact design
        
        // Header with title and refresh button
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JBLabel(NacosSearchBundle.message("config.list.title")).apply {
                font = font.deriveFont(Font.BOLD, 12f)
            })
            add(refreshButton.apply {
                preferredSize = Dimension(24, 24)
                minimumSize = Dimension(24, 24)
            })
        }
        
        // Center panel with table or loading/empty state
        val centerPanel = JPanel(CardLayout()).apply {
            add(scrollPane, "table")
            add(loadingLabel, "loading")
            add(emptyStatePanel, "empty")
        }
        
        add(headerPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(statusLabel.apply {
            border = JBUI.Borders.emptyTop(2)
            font = font.deriveFont(Font.ITALIC, 10f)
        }, BorderLayout.SOUTH)
    }
    
    private fun setupEventHandlers() {
        refreshButton.addActionListener {
            loadConfigurations()
        }
        
        configTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selectedRow = configTable.selectedRow
                    if (selectedRow >= 0) {
                        val config = tableModel.getConfigurationAt(selectedRow)
                        onConfigurationSelected?.invoke(config)
                    }
                }
            }
        })
        
        configTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedRow = configTable.selectedRow
                if (selectedRow >= 0) {
                    val config = tableModel.getConfigurationAt(selectedRow)
                    onConfigurationSelected?.invoke(config)
                }
            }
        }
    }
    
    private fun createEmptyStatePanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val messageLabel = JBLabel(NacosSearchBundle.message("config.list.empty")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = Color.GRAY
            }
            
            val instructionLabel = JBLabel(NacosSearchBundle.message("config.list.empty.instruction")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = Color.GRAY
                font = font.deriveFont(Font.ITALIC)
            }
            
            val centerPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(Box.createVerticalGlue())
                add(messageLabel)
                add(Box.createVerticalStrut(5))
                add(instructionLabel)
                add(Box.createVerticalGlue())
            }
            
            add(centerPanel, BorderLayout.CENTER)
        }
    }
    
    private fun loadConfigurations() {
        if (isLoading) return
        
        setLoadingState(true)
        showCard("loading")
        
        coroutineScope.launch {
            try {
                val namespaceId = currentNamespace?.namespaceId
                val result = nacosApiService.listConfigurations(
                    namespaceId = namespaceId,
                    pageNo = currentPage,
                    pageSize = pageSize,
                    useCache = true
                )
                
                result.onSuccess { loadedConfigurations ->
                    // Convert ConfigItems to NacosConfigurations
                    val configs = loadedConfigurations.pageItems.map { item ->
                        nacosApiService.getConfigurationFromItem(item, useCache = true)
                    }
                    configurations = configs
                    updateTable()
                    setLoadingState(false)
                    
                    if (configurations.isEmpty()) {
                        showCard("empty")
                        updateStatus(NacosSearchBundle.message("config.list.empty"))
                    } else {
                        showCard("table")
                        updateStatus(NacosSearchBundle.message("config.list.loaded", configurations.size))
                    }
                }.onFailure { error ->
                    setLoadingState(false)
                    showCard("empty")
                    updateStatus(NacosSearchBundle.message("config.list.failed"))
                    showError(NacosSearchBundle.message("config.list.failed"), error.message ?: NacosSearchBundle.message("error.unknown"))
                }
            } catch (e: Exception) {
                setLoadingState(false)
                showCard("empty")
                updateStatus(NacosSearchBundle.message("config.list.error"))
                showError(NacosSearchBundle.message("config.list.error"), e.message ?: NacosSearchBundle.message("error.unknown"))
            }
        }
    }
    
    private fun updateTable() {
        SwingUtilities.invokeLater {
            tableModel.updateConfigurations(configurations)
            if (configurations.isNotEmpty()) {
                configTable.setRowSelectionInterval(0, 0)
            }
        }
    }
    
    private fun setLoadingState(loading: Boolean) {
        SwingUtilities.invokeLater {
            isLoading = loading
            refreshButton.isEnabled = !loading
        }
    }
    
    private fun showCard(cardName: String) {
        SwingUtilities.invokeLater {
            val centerPanel = (this.getComponent(1) as JPanel)
            val cardLayout = centerPanel.layout as CardLayout
            cardLayout.show(centerPanel, cardName)
        }
    }
    
    private fun updateStatus(message: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = message
        }
    }
    
    private fun showError(title: String, message: String) {
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                this,
                message,
                title,
                JOptionPane.ERROR_MESSAGE
            )
        }
    }
    
    /**
     * Get the currently selected configuration
     */
    fun getSelectedConfiguration(): NacosConfiguration? {
        val selectedRow = configTable.selectedRow
        return if (selectedRow >= 0) {
            tableModel.getConfigurationAt(selectedRow)
        } else {
            null
        }
    }
    
    /**
     * Refresh configurations from server
     */
    fun refresh() {
        loadConfigurations()
    }
    
    /**
     * Set configurations directly without loading from server
     */
    fun setConfigurations(newConfigurations: List<NacosConfiguration>) {
        configurations = newConfigurations
        updateTable()
        
        if (configurations.isEmpty()) {
            showCard("empty")
            updateStatus("No configurations found")
        } else {
            showCard("table")
            updateStatus("${configurations.size} configurations loaded")
        }
    }
    
    /**
     * Set page number for pagination
     */
    fun setPage(page: Int) {
        currentPage = page
        loadConfigurations()
    }
    
    /**
     * Get current page number
     */
    fun getCurrentPage(): Int = currentPage
    
    /**
     * Get page size
     */
    fun getPageSize(): Int = pageSize
    
    /**
     * Get total number of configurations
     */
    fun getConfigurationCount(): Int = configurations.size
    
    /**
     * Clean up resources
     */
    fun dispose() {
        namespaceService.removeNamespaceChangeListener(this)
        coroutineScope.cancel()
    }
    
    // NamespaceChangeListener implementation
    override suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
        currentNamespace = newNamespace
        currentPage = 1 // Reset to first page when namespace changes
        pageSize = 10 // Reset to first page when namespace changes
        loadConfigurations()

    }
    
    /**
     * Table model for configuration data
     */
    private class ConfigTableModel : AbstractTableModel() {
        private val columnNames = arrayOf("Data ID", "Group", "Type", "Namespace")
        private var configurations: List<NacosConfiguration> = emptyList()
        
        fun updateConfigurations(newConfigurations: List<NacosConfiguration>) {
            configurations = newConfigurations
            fireTableDataChanged()
        }
        
        fun getConfigurationAt(row: Int): NacosConfiguration {
            return configurations[row]
        }
        
        override fun getRowCount(): Int = configurations.size
        
        override fun getColumnCount(): Int = columnNames.size
        
        override fun getColumnName(column: Int): String = columnNames[column]
        
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val config = configurations[rowIndex]
            return when (columnIndex) {
                0 -> config.dataId
                1 -> config.group
                2 -> config.type ?: "text"
                3 -> config.tenantId ?: ""
                else -> ""
            }
        }
        
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }
    
    /**
     * Called when the language is changed
     */
    override fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage) {
        // Refresh all UI text elements
        refreshUIText()
        
        // Rebuild empty state panel with new language
        rebuildEmptyStatePanel()
        
        // Update table column headers
        updateTableHeaders()
        
        // Update button tooltips
        updateButtonTooltips()
        
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
    
    /**
     * Refresh all UI text elements
     */
    private fun refreshUIText() {
        SwingUtilities.invokeLater {
            // Update loading text
            loadingLabel.text = NacosSearchBundle.message("config.list.loading")
            
            // Update status text
            updateStatusText()
            
            // Update refresh button tooltip
            refreshButton.toolTipText = NacosSearchBundle.message("tooltip.config.refresh")
        }
    }

    /**
     * Rebuild empty state panel with new language
     */
    private fun rebuildEmptyStatePanel() {

        SwingUtilities.invokeLater {
            // Remove old empty state panel
            remove(emptyStatePanel)

            // Create new empty state panel with updated language
            emptyStatePanel = createEmptyStatePanel()

            // Add back to the panel if currently showing empty state
            if (configurations.isEmpty()) {
                add(emptyStatePanel, BorderLayout.CENTER)
            }
        }
    }
    
    /**
     * Update table column headers
     */
    private fun updateTableHeaders() {
        SwingUtilities.invokeLater {
            tableModel.fireTableStructureChanged()
        }
    }
    
    /**
     * Update button tooltips
     */
    private fun updateButtonTooltips() {
        SwingUtilities.invokeLater {
            refreshButton.toolTipText = NacosSearchBundle.message("tooltip.config.refresh")
        }
    }
    
    /**
     * Update status text based on current state
     */
    private fun updateStatusText() {
        SwingUtilities.invokeLater {
            when {
                isLoading -> {
                    statusLabel.text = NacosSearchBundle.message("config.list.loading")
                }
                configurations.isEmpty() -> {
                    statusLabel.text = NacosSearchBundle.message("config.list.empty")
                }
                else -> {
                    val totalCount = configurations.size
                    statusLabel.text = NacosSearchBundle.message("config.list.loaded", totalCount)
                }
            }
        }
    }
}