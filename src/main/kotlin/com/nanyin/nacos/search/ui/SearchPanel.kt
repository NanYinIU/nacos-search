package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.SearchCriteria
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Panel for search functionality with advanced options
 */
class SearchPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    // UI Components
    private lateinit var searchField: JBTextField
    private lateinit var searchButton: JButton
    private lateinit var clearButton: JButton
    private lateinit var advancedToggle: JButton
    private lateinit var advancedPanel: JPanel
    // private lateinit var searchModeLabel: JBLabel
    
    // Advanced search components
    private lateinit var dataIdField: JBTextField
    private lateinit var groupField: JBTextField
    private lateinit var contentField: JBTextField
    private lateinit var exactMatchCheckBox: JCheckBox
    private lateinit var caseSensitiveCheckBox: JCheckBox
    
    // State
    private var isAdvancedVisible = false
    private var searchCriteria = SearchCriteria()
    
    // Search listener
    var onSearchRequested: ((SearchCriteria) -> Unit)? = null
    var onSearchCleared: (() -> Unit)? = null
    var onRealTimeSearch: ((String) -> Unit)? = null
    
    init {
        initializeComponents()
        setupLayout()
        setupEventHandlers()
    }
    
    private fun initializeComponents() {
        // Main search field
        searchField = JBTextField().apply {
            emptyText.text = "Search configurations..."
            columns = 20
        }
        
        // Action buttons
        searchButton = JButton(AllIcons.Actions.Search).apply {
            toolTipText = "Search configurations"
            preferredSize = Dimension(24, 24)
        }
        
        clearButton = JButton(AllIcons.Actions.GC).apply {
            toolTipText = "Clear search"
            preferredSize = Dimension(24, 24)
            isEnabled = false
        }
        
        advancedToggle = JButton("Advanced").apply {
            toolTipText = "Toggle advanced search options"
            font = font.deriveFont(Font.PLAIN, 11f)
        }
        
//        searchModeLabel = JBLabel("精确搜索").apply {
//            toolTipText = "当前搜索模式"
//            foreground = java.awt.Color.GRAY
//        }
        
        // Advanced search components
        dataIdField = JBTextField().apply {
            emptyText.text = "Data ID pattern..."
            columns = 15
        }
        
        groupField = JBTextField().apply {
            emptyText.text = "Group pattern..."
            columns = 15
        }
        
        contentField = JBTextField().apply {
            emptyText.text = "Content contains..."
            columns = 15
        }
        
        exactMatchCheckBox = JCheckBox("Exact match").apply {
            toolTipText = "Use exact string matching instead of pattern matching"
        }
        
        caseSensitiveCheckBox = JCheckBox("Case sensitive").apply {
            toolTipText = "Enable case-sensitive search"
        }
        
        // Advanced panel
        advancedPanel = createAdvancedPanel()
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty(5)
        
        // Main search panel
        val mainPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            
            add(JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                add(searchField.apply { preferredSize = Dimension(300, 25) })
                // add(searchModeLabel)
                add(searchButton)
                add(clearButton)
                add(advancedToggle)
            }, BorderLayout.CENTER)
        }
        
        add(mainPanel, BorderLayout.NORTH)
        add(advancedPanel, BorderLayout.CENTER)
        
        // Initially hide advanced panel
        advancedPanel.isVisible = false
    }
    
    private fun createAdvancedPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(Color.LIGHT_GRAY, 1, 0, 0, 0),
                JBUI.Borders.empty(10, 0, 0, 0)
            )
            
            // Field search section
            add(JBLabel("Field Search:").apply {
                font = font.deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            
            add(Box.createVerticalStrut(5))
            
            // Data ID row
            add(createFieldRow("Data ID:", dataIdField))
            add(Box.createVerticalStrut(3))
            
            // Group row
            add(createFieldRow("Group:", groupField))
            add(Box.createVerticalStrut(3))
            
            // Content row
            add(createFieldRow("Content:", contentField))
            add(Box.createVerticalStrut(10))
            
            // Options section
            add(JBLabel("Options:").apply {
                font = font.deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            
            add(Box.createVerticalStrut(5))
            
            val optionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(exactMatchCheckBox)
                add(Box.createHorizontalStrut(15))
                add(caseSensitiveCheckBox)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(optionsPanel)
            
            // Apply/Reset buttons
            add(Box.createVerticalStrut(10))
            
            val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                val applyButton = JButton("Apply").apply {
                    addActionListener { performAdvancedSearch() }
                }
                
                val resetButton = JButton("Reset").apply {
                    addActionListener { resetAdvancedSearch() }
                }
                
                add(applyButton)
                add(Box.createHorizontalStrut(5))
                add(resetButton)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(actionPanel)
        }
    }
    
    private fun createFieldRow(labelText: String, field: JBTextField): JPanel {
        return JPanel(BorderLayout()).apply {
            add(JBLabel(labelText).apply {
                preferredSize = Dimension(70, preferredSize.height)
            }, BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }
    
    private fun setupEventHandlers() {
        // Search button
        searchButton.addActionListener {
            performSearch()
        }
        
        // Clear button
        clearButton.addActionListener {
            clearSearch()
        }
        
        // Advanced toggle
        advancedToggle.addActionListener {
            toggleAdvancedPanel()
        }
        
        // Enter key in search field
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    performSearch()
                }
            }
        })
        
        // Enter key in advanced fields
        val enterKeyListener = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    performAdvancedSearch()
                }
            }
        }
        
        dataIdField.addKeyListener(enterKeyListener)
        groupField.addKeyListener(enterKeyListener)
        contentField.addKeyListener(enterKeyListener)
        
        // Document listener for search field to enable/disable clear button and real-time search
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                updateClearButtonState()
                handleTextChange()
            }
            override fun removeUpdate(e: DocumentEvent) {
                updateClearButtonState()
                handleTextChange()
            }
            override fun changedUpdate(e: DocumentEvent) {
                updateClearButtonState()
                handleTextChange()
            }
        })
        
        // Document listeners for advanced fields
        val advancedFieldListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateAdvancedSearchState()
            override fun removeUpdate(e: DocumentEvent) = updateAdvancedSearchState()
            override fun changedUpdate(e: DocumentEvent) = updateAdvancedSearchState()
        }
        
        dataIdField.document.addDocumentListener(advancedFieldListener)
        groupField.document.addDocumentListener(advancedFieldListener)
        contentField.document.addDocumentListener(advancedFieldListener)
    }
    
    private fun handleTextChange() {
        val text = searchField.text.trim()
        
        // Update search mode display
        updateSearchModeDisplay(text)
        
        // Trigger real-time search with validation
        if (text.isNotEmpty() && isValidSearchInput(text)) {
            onRealTimeSearch?.invoke(text)
        } else if (text.isEmpty()) {
            // Clear search when input is empty
            onRealTimeSearch?.invoke(text)
        }
    }
    
    /**
     * Validates if the search input is valid for prefix search
     */
    private fun isValidPrefixSearch(input: String): Boolean {
        // Valid prefix search: starts with * and has content after
        return input.startsWith("*") && input.length > 1
    }
    
    /**
     * Validates if the search input is valid
     */
    private fun isValidSearchInput(input: String): Boolean {
        return when {
            input == "*" -> true // Wildcard-only search
            input.startsWith("*") -> isValidPrefixSearch(input)
            else -> true // Regular search
        }
    }
    
    /**
     * Updates search mode display with color coding
     */
    private fun updateSearchModeDisplay(searchText: String) {
        // This method can be used to show search mode hints
        // For now, we'll update the search field's tooltip
        SwingUtilities.invokeLater {
            val tooltip = when {
                searchText.isEmpty() -> "Search configurations..."
                searchText == "*" -> "Wildcard search: Show all configurations"
                searchText.startsWith("*") && searchText.length > 1 -> "Prefix fuzzy search: Find configurations ending with '${searchText.substring(1)}'"
                searchText.contains("*") || searchText.contains("?") -> "Fuzzy search: Pattern matching enabled"
                else -> "Exact search: Find configurations containing '$searchText'"
            }
            searchField.toolTipText = tooltip
        }
    }
    
//    private fun updateSearchModeLabel(searchText: String) {
//        SwingUtilities.invokeLater {
//            searchModeLabel.text = ""
//            }
//        }
//    }
    
    private fun performSearch() {
        val query = searchField.text.trim()
        if (query.isNotEmpty()) {
            searchCriteria = SearchCriteria(
            query = query,
            useRegex = true,
            caseSensitive = false
        )
            onSearchRequested?.invoke(searchCriteria)
        }
    }
    
    private fun performAdvancedSearch() {
        val dataIdPattern = dataIdField.text.trim()
        val groupPattern = groupField.text.trim()
        val contentPattern = contentField.text.trim()
        
        if (dataIdPattern.isNotEmpty() || groupPattern.isNotEmpty() || contentPattern.isNotEmpty()) {
            searchCriteria = SearchCriteria(
                dataId = dataIdPattern.takeIf { it.isNotEmpty() } ?: "",
                group = groupPattern.takeIf { it.isNotEmpty() } ?: "",
                query = contentPattern.takeIf { it.isNotEmpty() } ?: "",
                useRegex = !exactMatchCheckBox.isSelected,
                caseSensitive = caseSensitiveCheckBox.isSelected
            )
            onSearchRequested?.invoke(searchCriteria)
        }
    }
    
    private fun clearSearch() {
        searchField.text = ""
        resetAdvancedSearch()
        searchCriteria = SearchCriteria()
        onSearchCleared?.invoke()
    }
    
    private fun resetAdvancedSearch() {
        dataIdField.text = ""
        groupField.text = ""
        contentField.text = ""
        exactMatchCheckBox.isSelected = false
        caseSensitiveCheckBox.isSelected = false
    }
    
    private fun toggleAdvancedPanel() {
        isAdvancedVisible = !isAdvancedVisible
        advancedPanel.isVisible = isAdvancedVisible
        advancedToggle.text = if (isAdvancedVisible) "Simple" else "Advanced"
        
        // Trigger layout update
        revalidate()
        repaint()
    }
    
    private fun updateClearButtonState() {
        clearButton.isEnabled = searchField.text.isNotEmpty() || hasAdvancedSearchCriteria()
    }
    
    private fun updateAdvancedSearchState() {
        updateClearButtonState()
    }
    
    private fun hasAdvancedSearchCriteria(): Boolean {
        return dataIdField.text.isNotEmpty() || 
               groupField.text.isNotEmpty() || 
               contentField.text.isNotEmpty() ||
               exactMatchCheckBox.isSelected ||
               caseSensitiveCheckBox.isSelected
    }
    
    /**
     * Set search query programmatically
     */
    fun setSearchQuery(query: String) {
        searchField.text = query
        updateClearButtonState()
    }
    
    /**
     * Get current search query
     */
    fun getSearchQuery(): String = searchField.text.trim()
    
    /**
     * Get current search criteria
     */
    fun getSearchCriteria(): SearchCriteria = searchCriteria
    
    /**
     * Clear all search criteria
     */
    fun clearAllCriteria() {
        clearSearch()
    }
    
    /**
     * Check if search is active
     */
    fun hasActiveSearch(): Boolean {
        return searchField.text.isNotEmpty() || hasAdvancedSearchCriteria()
    }
    
    /**
     * Focus the search field
     */
    fun focusSearchField() {
        searchField.requestFocusInWindow()
    }
    
    /**
     * Enable or disable the search functionality
     */
    fun setSearchEnabled(enabled: Boolean) {
        searchField.isEnabled = enabled
        searchButton.isEnabled = enabled
        clearButton.isEnabled = enabled && hasActiveSearch()
        advancedToggle.isEnabled = enabled
        
        dataIdField.isEnabled = enabled
        groupField.isEnabled = enabled
        contentField.isEnabled = enabled
        exactMatchCheckBox.isEnabled = enabled
        caseSensitiveCheckBox.isEnabled = enabled
    }
}