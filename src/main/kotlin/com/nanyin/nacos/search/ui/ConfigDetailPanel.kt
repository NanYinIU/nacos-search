package com.nanyin.nacos.search.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.NacosApiService
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.ActionEvent
import javax.swing.*


/**
 * Panel for displaying configuration details with syntax highlighting
 */
class ConfigDetailPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val nacosApiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    
    // UI Components
    private lateinit var metadataPanel: JPanel
    private lateinit var contentPanel: JPanel
    private lateinit var loadingLabel: JBLabel
    private lateinit var emptyStatePanel: JPanel
    private lateinit var errorPanel: JPanel
    private lateinit var refreshButton: JButton
    private lateinit var copyButton: JButton
    
    // Editor components
    private var editor: EditorEx? = null
    private var editorPanel: JPanel? = null
    
    // Metadata labels
    private lateinit var dataIdLabel: JTextPane
    private lateinit var groupLabel: JTextPane
    private lateinit var namespaceLabel: JTextPane
    private lateinit var typeLabel: JTextPane
    private lateinit var sizeLabel: JTextPane
    
    // State
    private var currentConfiguration: NacosConfiguration? = null
    private var isLoading = false
    
    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    init {
        initializeComponents()
        setupLayout()
        setupEventHandlers()
        showEmptyState()
    }
    
    private fun initializeComponents() {
        // Metadata panel components
        dataIdLabel = JTextPane()
        dataIdLabel.isEditable = false
        groupLabel = JTextPane()
        groupLabel.isEditable = false
        namespaceLabel = JTextPane()
        namespaceLabel.isEditable = false
        typeLabel = JTextPane()
        typeLabel.isEditable = false
        sizeLabel = JTextPane()
        sizeLabel.isEditable = false

        
        metadataPanel = createMetadataPanel()
        
        // Content panel
        contentPanel = JPanel(CardLayout())
        
        // Loading state
        loadingLabel = JBLabel().apply {
            icon = AnimatedIcon.Default.INSTANCE
            text = "Loading configuration..."
            horizontalAlignment = SwingConstants.CENTER
        }
        
        // Empty state
        emptyStatePanel = createEmptyStatePanel()
        
        // Error state
        errorPanel = createErrorPanel()
        
        // Action buttons
        refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Refresh configuration"
            preferredSize = Dimension(24, 24)
        }
        
        copyButton = JButton(AllIcons.Actions.Copy).apply {
            toolTipText = "Copy configuration content"
            preferredSize = Dimension(24, 24)
            isEnabled = false
        }
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty(5)
        
        // Top panel with metadata and actions
        val topPanel = JPanel(BorderLayout()).apply {
            add(metadataPanel, BorderLayout.CENTER)
            
            val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                // add(copyButton)
                add(refreshButton)
            }
            add(actionPanel, BorderLayout.EAST)
            
            border = JBUI.Borders.emptyBottom(5)
        }
        
        // Add cards to content panel
        contentPanel.add(emptyStatePanel, "empty")
        contentPanel.add(loadingLabel, "loading")
        contentPanel.add(errorPanel, "error")
        
        add(topPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }
    
    private fun setupEventHandlers() {
        refreshButton.addActionListener {
            currentConfiguration?.let { config ->
                loadConfigurationContent(config)
            }
        }
        
        copyButton.addActionListener {
            copyContentToClipboard()
        }
    }
    
    private fun createMetadataPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(Color.LIGHT_GRAY, 0, 0, 1, 0),
                JBUI.Borders.empty(5)
            )
            
            add(createMetadataRow("Data ID:", dataIdLabel))
            // add(createMetadataRow("Group:", groupLabel))
            add(createMetadataRow("Namespace:", namespaceLabel))
            // add(createMetadataRow("Type:", typeLabel))
            // add(createMetadataRow("Size:", sizeLabel))
        }
    }
    
    private fun createMetadataRow(labelText: String, valueLabel: JTextPane): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
            add(JBLabel(labelText).apply {
                font = font.deriveFont(Font.BOLD)
                preferredSize = Dimension(80, preferredSize.height)
            })
            add(valueLabel.apply { font = font.deriveFont(Font.ITALIC) })
        }
    }
    
    private fun createEmptyStatePanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val messageLabel = JBLabel("No configuration selected").apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = Color.GRAY
            }
            
            val instructionLabel = JBLabel("Select a configuration from the list to view details").apply {
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
    
    private fun createErrorPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val errorLabel = JBLabel("Failed to load configuration").apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = Color.RED
                icon = AllIcons.General.Error
            }
            
            val retryButton = JButton("Retry").apply {
                addActionListener {
                    currentConfiguration?.let { config ->
                        loadConfigurationContent(config)
                    }
                }
            }
            
            val centerPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(Box.createVerticalGlue())
                add(errorLabel)
                add(Box.createVerticalStrut(10))
                add(retryButton)
                add(Box.createVerticalGlue())
            }
            
            add(centerPanel, BorderLayout.CENTER)
        }
    }
    
    /**
     * Display configuration details
     */
    fun showConfiguration(configuration: NacosConfiguration) {
        currentConfiguration = configuration
        updateMetadata(configuration)
        loadConfigurationContent(configuration)
    }
    
    private fun updateMetadata(configuration: NacosConfiguration) {
        SwingUtilities.invokeLater {
            dataIdLabel.text = configuration.dataId
            groupLabel.text = configuration.group
            namespaceLabel.text = configuration.tenantId ?: ""
            typeLabel.text = configuration.type ?: "text"
            sizeLabel.text = "Loading..."
        }
    }
    
    private fun loadConfigurationContent(configuration: NacosConfiguration) {
        if (isLoading) return
        
        setLoadingState(true)
        showCard("loading")
        
        coroutineScope.launch {
            try {
                val result = nacosApiService.getConfiguration(
                    dataId = configuration.dataId,
                    group = configuration.group,
                    namespaceId = configuration.tenantId,
                    useCache = true
                )
                
                result.onSuccess { configWithContent ->
                    configWithContent?.let { config ->
                        displayConfigurationContent(config)
                        setLoadingState(false)
                        showCard("content")
                        
                        // Update size information
                        SwingUtilities.invokeLater {
                            val contentSize = config.content?.length ?: 0
                            sizeLabel.text = formatSize(contentSize)
                        }
                    } ?: run {
                        setLoadingState(false)
                        showCard("error")
                    }
                }.onFailure { error ->
                    setLoadingState(false)
                    showCard("error")
                    showError("Failed to load configuration", error.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                setLoadingState(false)
                showCard("error")
                showError("Error loading configuration", e.message ?: "Unknown error")
            }
        }
    }
    
    private fun displayConfigurationContent(configuration: NacosConfiguration) {
        SwingUtilities.invokeLater {
            // Dispose previous editor if exists
            disposeEditor()
            
            val content = configuration.content ?: ""
            val fileType = determineFileType(configuration)
            
            // Create new editor
            val document = EditorFactory.getInstance().createDocument(content)
            editor = EditorFactory.getInstance().createEditor(document, project, fileType, true) as EditorEx
            
            editor?.let { ed ->
                // Configure editor settings
                val settings = ed.settings
                settings.isLineNumbersShown = true
                settings.isLineMarkerAreaShown = false
                settings.isFoldingOutlineShown = true
                settings.isRightMarginShown = false
                settings.isWhitespacesShown = false
                settings.isIndentGuidesShown = true
                
                // Create editor panel
                editorPanel = JPanel(BorderLayout()).apply {
                    add(ed.component, BorderLayout.CENTER)
                }
                
                // Add to content panel
                contentPanel.add(editorPanel, "content")
                copyButton.isEnabled = true
            }
        }
    }
    
    private fun determineFileType(configuration: NacosConfiguration): FileType {
        val type = configuration.type?.lowercase()
        val dataId = configuration.dataId.lowercase()
        
        return when {
            type == "json" || dataId.endsWith(".json") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("json")
            type == "yaml" || type == "yml" || dataId.endsWith(".yaml") || dataId.endsWith(".yml") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("yaml")
            type == "properties" || dataId.endsWith(".properties") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("properties")
            type == "xml" || dataId.endsWith(".xml") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("xml")
            type == "html" || dataId.endsWith(".html") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("html")
            type == "sql" || dataId.endsWith(".sql") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("sql")
            else -> FileTypeManager.getInstance().getFileTypeByExtension("txt")
        }
    }
    
    private fun formatSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
    
    private fun copyContentToClipboard() {
        editor?.document?.text?.let { content ->
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val stringSelection = java.awt.datatransfer.StringSelection(content)
            clipboard.setContents(stringSelection, null)
            
            // Show brief notification
            JOptionPane.showMessageDialog(
                this,
                "Configuration content copied to clipboard",
                "Copy Successful",
                JOptionPane.INFORMATION_MESSAGE
            )
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
            val cardLayout = contentPanel.layout as CardLayout
            cardLayout.show(contentPanel, cardName)
        }
    }
    
    private fun showEmptyState() {
        showCard("empty")
        copyButton.isEnabled = false
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
    
    private fun disposeEditor() {
        editor?.let { ed ->
            EditorFactory.getInstance().releaseEditor(ed)
            editor = null
        }
        editorPanel?.let { panel ->
            contentPanel.remove(panel)
            editorPanel = null
        }
    }
    
    /**
     * Clear the current configuration display
     */
    fun clearConfiguration() {
        currentConfiguration = null
        disposeEditor()
        showEmptyState()
        
        SwingUtilities.invokeLater {
            dataIdLabel.text = ""
            groupLabel.text = ""
            namespaceLabel.text = ""
            typeLabel.text = ""
            sizeLabel.text = ""
        }
    }
    
    /**
     * Get the current configuration
     */
    fun getCurrentConfiguration(): NacosConfiguration? = currentConfiguration
    
    /**
     * Refresh the current configuration
     */
    fun refresh() {
        currentConfiguration?.let { config ->
            loadConfigurationContent(config)
        }
    }
    
    /**
     * Clean up resources
     */
    fun dispose() {
        disposeEditor()
        coroutineScope.cancel()
    }
}