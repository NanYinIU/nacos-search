package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.*

/**
 * Panel for namespace selection and management
 */
class NamespacePanel(private val project: Project) : JPanel(BorderLayout()), LanguageAwareComponent {
    
    private val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
    private val languageService = ApplicationManager.getApplication().getService(LanguageService::class.java)
    
    // UI Components
    private lateinit var namespaceCombo: ComboBox<NamespaceInfo>
    private lateinit var refreshButton: JButton
    private lateinit var loadingLabel: JBLabel
    private lateinit var statusLabel: JBLabel
    
    // State
    private var isLoading = false
    private var namespaces: List<NamespaceInfo> = emptyList()
    
    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    init {
        initializeComponents()
        setupLayout()
        setupEventHandlers()
        loadNamespaces()
    }
    
    private fun initializeComponents() {
        namespaceCombo = ComboBox<NamespaceInfo>().apply {
            renderer = NamespaceComboRenderer()
            isEnabled = false // Disabled until namespaces are loaded
        }
        
        refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = NacosSearchBundle.message("namespace.refresh")
            preferredSize = Dimension(24, 24)
        }
        
        loadingLabel = JBLabel().apply {
            icon = AnimatedIcon.Default.INSTANCE
            isVisible = false
        }
        
        statusLabel = JBLabel(NacosSearchBundle.message("namespace.loading.namespaces")).apply {
            foreground = JBColor.GRAY
        }
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty(2, 4, 0, 4) // Minimal padding for compact design
        
        val mainPanel = JPanel(BorderLayout()).apply {
            // Namespace label and combo row
            val namespaceRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(JBLabel(NacosSearchBundle.message("namespace.label") + ":").apply {
                    font = font.deriveFont(Font.BOLD, 11f)
                    preferredSize = Dimension(100, 24)
                })
                add(namespaceCombo.apply {
                    preferredSize = Dimension(180, 24)
                    minimumSize = Dimension(150, 24)
                })
                add(refreshButton.apply {
                    preferredSize = Dimension(24, 24)
                    minimumSize = Dimension(24, 24)
                })
                add(loadingLabel)
            }
            add(namespaceRow, BorderLayout.NORTH)
            add(statusLabel.apply {
                border = JBUI.Borders.emptyTop(2)
                font = font.deriveFont(Font.ITALIC, 10f)
            }, BorderLayout.SOUTH)
        }
        
        add(mainPanel, BorderLayout.CENTER)
    }
    
    private fun setupEventHandlers() {
        refreshButton.addActionListener {
            loadNamespaces()
        }
        
        namespaceCombo.addActionListener { _ ->
            if (!isLoading && namespaceCombo.selectedItem != null) {
                val selectedNamespace = namespaceCombo.selectedItem as NamespaceInfo
                onNamespaceSelected(selectedNamespace)
            }
        }
    }
    
    private fun loadNamespaces() {
        if (isLoading) return
        
        setLoadingState(true)
        
        coroutineScope.launch {
            try {
                val result = namespaceService.loadNamespacesAsync().await()
                result.onSuccess { loadedNamespaces ->
                    namespaces = loadedNamespaces
                    updateNamespaceCombo()
                    setLoadingState(false)
                    updateStatus(NacosSearchBundle.message("namespace.loaded.namespaces", loadedNamespaces.size))
                }.onFailure { error ->
                    setLoadingState(false)
                    updateStatus(NacosSearchBundle.message("namespace.failed.load"))
                    showError(NacosSearchBundle.message("namespace.failed.load"), error.message ?: NacosSearchBundle.message("error.unknown"))
                }
            } catch (e: Exception) {
                setLoadingState(false)
                updateStatus(NacosSearchBundle.message("namespace.error.load"))
                showError(NacosSearchBundle.message("namespace.error.load"), e.message ?: NacosSearchBundle.message("error.unknown"))
            }
        }
    }
    
    private fun updateNamespaceCombo() {
        SwingUtilities.invokeLater {
            namespaceCombo.removeAllItems()
            
            namespaces.forEach { namespace ->
                namespaceCombo.addItem(namespace)
            }
            
            // Select current namespace if available
            val currentNamespace = namespaceService.getCurrentNamespace()
            if (currentNamespace != null) {
                val matchingNamespace = namespaces.find { it.namespaceId == currentNamespace.namespaceId }
                if (matchingNamespace != null) {
                    namespaceCombo.selectedItem = matchingNamespace
                }
            } else if (namespaces.isNotEmpty()) {
                // Select first namespace if no current namespace
                namespaceCombo.selectedIndex = 0
                onNamespaceSelected(namespaces[0])
            }
            
            namespaceCombo.isEnabled = namespaces.isNotEmpty()
        }
    }
    
    private fun onNamespaceSelected(namespace: NamespaceInfo) {
        coroutineScope.launch {
            try {
                namespaceService.setCurrentNamespace(namespace)
                updateStatus(NacosSearchBundle.message("namespace.selected", namespace.namespaceName))
            } catch (e: Exception) {
                updateStatus(NacosSearchBundle.message("namespace.failed.select"))
            }
        }
    }
    
    private fun setLoadingState(loading: Boolean) {
        SwingUtilities.invokeLater {
            isLoading = loading
            loadingLabel.isVisible = loading
            refreshButton.isEnabled = !loading
            namespaceCombo.isEnabled = !loading && namespaces.isNotEmpty()
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
     * Get the currently selected namespace
     */
    fun getSelectedNamespace(): NamespaceInfo? {
        return namespaceCombo.selectedItem as? NamespaceInfo
    }
    
    /**
     * Set the selected namespace programmatically
     */
    fun setSelectedNamespace(namespace: NamespaceInfo) {
        val matchingNamespace = namespaces.find { it.namespaceId == namespace.namespaceId }
        if (matchingNamespace != null) {
            namespaceCombo.selectedItem = matchingNamespace
        }
    }
    
    /**
     * Refresh namespaces from server
     */
    fun refresh() {
        loadNamespaces()
    }
    
    /**
     * Clean up resources
     */
    fun dispose() {
        coroutineScope.cancel()
    }
    
    /**
     * Custom renderer for namespace combo box
     */
    private class NamespaceComboRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            
            if (value is NamespaceInfo) {
                text = if (value.namespaceName.isNotEmpty()) {
                    "${value.namespaceName} (${value.namespaceId})"
                } else {
                    value.namespaceId
                }
                
                toolTipText = "Namespace ID: ${value.namespaceId}"
            }
            
            return this
        }
    }
    
    /**
     * Called when the language is changed
     */
    override fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage) {
        // Refresh all UI text elements
        refreshUIText()
        
        // Update combo box renderer
        updateComboRenderer()
        
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
            loadingLabel.text = NacosSearchBundle.message("namespace.loading.namespaces")
            
            // Update status text
            updateStatusText()
            
            // Update refresh button tooltip
            refreshButton.toolTipText = NacosSearchBundle.message("tooltip.namespace.refresh")
        }
    }
    
    /**
     * Update combo box renderer
     */
    private fun updateComboRenderer() {
        SwingUtilities.invokeLater {
            // Force combo box to re-render with new language
            namespaceCombo.repaint()
        }
    }
    
    /**
     * Update button tooltips
     */
    private fun updateButtonTooltips() {
        SwingUtilities.invokeLater {
            refreshButton.toolTipText = NacosSearchBundle.message("tooltip.namespace.refresh")
        }
    }
    
    /**
     * Update status text based on current state
     */
    private fun updateStatusText() {
        SwingUtilities.invokeLater {
            when {
                isLoading -> {
                    statusLabel.text = NacosSearchBundle.message("namespace.loading.namespaces")
                }
                namespaces.isEmpty() -> {
                    statusLabel.text = NacosSearchBundle.message("namespace.no.namespaces")
                }
                else -> {
                    statusLabel.text = NacosSearchBundle.message("namespace.loaded.namespaces", namespaces.size)
                }
            }
        }
    }
}