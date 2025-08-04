package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
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
class NamespacePanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
    
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
            toolTipText = "Refresh namespaces"
            preferredSize = Dimension(24, 24)
        }
        
        loadingLabel = JBLabel().apply {
            icon = AnimatedIcon.Default.INSTANCE
            isVisible = false
        }
        
        statusLabel = JBLabel("Loading namespaces...").apply {
            foreground = JBColor.GRAY
        }
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty(2, 5, 1, 5) // Reduced vertical padding for compact design
        
        val topPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("Namespace: ").apply {
                preferredSize = Dimension(preferredSize.width, 22) // Fixed compact height
            }, BorderLayout.WEST)
            
            val centerPanel = JPanel(BorderLayout()).apply {
                add(namespaceCombo.apply {
                    preferredSize = Dimension(preferredSize.width, 22) // Fixed compact height
                }, BorderLayout.CENTER)
                add(refreshButton.apply {
                    preferredSize = Dimension(preferredSize.width, 22) // Fixed compact height
                }, BorderLayout.EAST)
            }
            add(centerPanel, BorderLayout.CENTER)
            
            val rightPanel = JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply { // Reduced horizontal gap
                add(loadingLabel)
            }
            add(rightPanel, BorderLayout.EAST)
        }
        
        add(topPanel, BorderLayout.NORTH)
        add(statusLabel.apply {
            preferredSize = Dimension(preferredSize.width, 23) // Compact status label height
        }, BorderLayout.SOUTH)
    }
    
    private fun setupEventHandlers() {
        refreshButton.addActionListener {
            loadNamespaces()
        }
        
        namespaceCombo.addActionListener { e ->
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
                    updateStatus("${loadedNamespaces.size} namespaces loaded")
                }.onFailure { error ->
                    setLoadingState(false)
                    updateStatus("Failed to load namespaces: ${error.message}")
                    showError("Failed to load namespaces", error.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                setLoadingState(false)
                updateStatus("Error loading namespaces: ${e.message}")
                showError("Error loading namespaces", e.message ?: "Unknown error")
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
                updateStatus("Selected namespace: ${namespace.namespaceName}")
            } catch (e: Exception) {
                updateStatus("Failed to select namespace: ${e.message}")
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
}