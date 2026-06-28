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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.*

/**
 * Panel for namespace selection and management
 */
class NamespacePanel(
    private val project: Project,
    private val namespaceService: NamespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java),
    private val languageService: LanguageService = ApplicationManager.getApplication().getService(LanguageService::class.java),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : JPanel(BorderLayout()), LanguageAwareComponent {
    
    // UI Components
    private lateinit var namespaceCombo: ComboBox<NamespaceInfo>
    private lateinit var refreshButton: JButton
    private lateinit var loadingLabel: JBLabel
    private lateinit var statusLabel: JBLabel
    
    // State
    private var isLoading = false
    private var namespaces: List<NamespaceInfo> = emptyList()
    
    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(dispatcher + SupervisorJob())
    
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
            preferredSize = Dimension(28, 24)
            minimumSize = Dimension(28, 24)
            border = JBUI.Borders.empty()
            isContentAreaFilled = false
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
        border = JBUI.Borders.empty()

        val label = JBLabel(NacosSearchBundle.message("namespace.label")).apply {
            font = font.deriveFont(Font.PLAIN, 11.5f)
            foreground = JBColor(0x6f737a, 0x9b9ea6)
            preferredSize = Dimension(54, 24)
        }

        // Compact horizontal row: combo only (refresh is an icon button inside)
        val row = JPanel(BorderLayout(2, 0)).apply {
            add(namespaceCombo.apply {
                preferredSize = Dimension(160, 26)
                minimumSize = Dimension(120, 26)
                maximumSize = Dimension(200, 26)
            }, BorderLayout.CENTER)
            add(refreshButton.apply {
                preferredSize = Dimension(28, 24)
                minimumSize = Dimension(28, 24)
                border = JBUI.Borders.empty()
                isContentAreaFilled = false
            }, BorderLayout.EAST)
        }

        add(JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(label, BorderLayout.WEST)
            add(row, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        // Status label kept but hidden in compact mode (used programmatically)
        statusLabel.isVisible = false
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

        coroutineScope.launch {
            loadNamespacesAndUpdate()
        }
    }

    suspend fun refreshAndWait(): Result<List<NamespaceInfo>> {
        if (isLoading) return Result.success(namespaces)
        return loadNamespacesAndUpdate()
    }

    private suspend fun loadNamespacesAndUpdate(): Result<List<NamespaceInfo>> {
        setLoadingState(true)

        return try {
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
            result
        } catch (e: Exception) {
            setLoadingState(false)
            updateStatus(NacosSearchBundle.message("namespace.error.load"))
            showError(NacosSearchBundle.message("namespace.error.load"), e.message ?: NacosSearchBundle.message("error.unknown"))
            Result.failure(e)
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
        if (GraphicsEnvironment.isHeadless() || ApplicationManager.getApplication().isUnitTestMode) {
            return
        }
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
                val countPart = if (value.configCount > 0) " · ${value.configCount}" else ""
                val displayName = value.namespaceName.ifEmpty { value.getDisplayName() }
                text = if (value.namespaceId.isBlank()) {
                    // Public namespace has an empty id; render "name · count" without empty parens.
                    "$displayName$countPart"
                } else {
                    val shortId = if (value.namespaceId.length > 8) value.namespaceId.take(8) + "…" else value.namespaceId
                    "$displayName ($shortId)$countPart"
                }

                // Use monospace for machine-readable namespace values per design guide
                font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 12)

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
