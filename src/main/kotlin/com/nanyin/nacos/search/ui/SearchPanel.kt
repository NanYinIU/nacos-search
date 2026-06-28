package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.SearchCriteria
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.services.LanguageService
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
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
class SearchPanel(private val project: Project) : JPanel(BorderLayout()), LanguageAwareComponent {
    
    // UI Components
    private lateinit var searchField: JBTextField
    private lateinit var searchButton: JButton
    private lateinit var clearButton: JButton
    // private lateinit var advancedToggle: JButton
    // private lateinit var advancedPanel: JPanel
     private lateinit var searchModeLabel: JBLabel
    private lateinit var searchLabel: JBLabel
    private lateinit var groupFilterButton: JButton
    // Advanced search components
    private lateinit var dataIdField: JBTextField
    private lateinit var groupField: JBTextField
    private lateinit var contentField: JBTextField
    private lateinit var exactMatchCheckBox: JCheckBox
    private lateinit var caseSensitiveCheckBox: JCheckBox
    
    // State
    private var isAdvancedVisible = false
    private var searchCriteria = SearchCriteria()
    
    // Services
    private val languageService = com.intellij.openapi.application.ApplicationManager.getApplication().getService(LanguageService::class.java)
    
    // Search listener
    var onSearchRequested: ((SearchCriteria) -> Unit)? = null
    var onSearchCleared: (() -> Unit)? = null
    var onRealTimeSearch: ((String) -> Unit)? = null
    var onGroupFilterChanged: ((String) -> Unit)? = null

    // Available groups for the filter popup
    private var availableGroups: List<String> = listOf(NacosSearchBundle.message("search.group.filter.all"))
    private var selectedGroup: String = NacosSearchBundle.message("search.group.filter.all")
    
    init {
        initializeComponents()
        setupLayout()
        setupEventHandlers()
    }
    
    private fun initializeComponents() {
        // Main search field
        searchField = JBTextField().apply {
            emptyText.text = NacosSearchBundle.message("search.placeholder")
            columns = 20
            font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 12)
        }
        
        // Action buttons
        searchButton = JButton(AllIcons.Actions.Search).apply {
            toolTipText = NacosSearchBundle.message("search.tooltip")
            preferredSize = Dimension(28, 24)
            minimumSize = Dimension(28, 24)
        }
        
        clearButton = JButton(AllIcons.Actions.Close).apply {
            toolTipText = NacosSearchBundle.message("search.clear.tooltip")
            preferredSize = Dimension(24, 24)
            isEnabled = false
        }
        
//        advancedToggle = JButton("Advanced").apply {
//            toolTipText = "Toggle advanced search options"
//            font = font.deriveFont(Font.PLAIN, 11f)
//        }
        
        searchModeLabel = JBLabel(NacosSearchBundle.message("search.mode.label")).apply {
            foreground = JBColor.GRAY
        }

        searchLabel = JBLabel(NacosSearchBundle.message("search.mode.label")).apply {
            foreground = JBColor.GRAY
        }

        // Group filter pill — compact bordered button per design guide
        groupFilterButton = JButton(NacosSearchBundle.message("search.group.filter.label") + " " + NacosSearchBundle.message("search.group.filter.all")).apply {
            toolTipText = NacosSearchBundle.message("tooltip.group.filter")
            preferredSize = Dimension(90, 26)
            margin = JBUI.insets(0, 9, 0, 9)
            isContentAreaFilled = false
            border = JBUI.Borders.empty(0, 9)
            addActionListener { showGroupFilterPopup() }
        }
        
        // Advanced search components
        dataIdField = JBTextField().apply {
            emptyText.text = NacosSearchBundle.message("search.data.id.placeholder")
            columns = 15
        }
        
        groupField = JBTextField().apply {
            emptyText.text = NacosSearchBundle.message("search.group.placeholder")
            columns = 15
        }
        
        contentField = JBTextField().apply {
            emptyText.text = NacosSearchBundle.message("search.content.placeholder")
            columns = 15
        }
        
        exactMatchCheckBox = JCheckBox(NacosSearchBundle.message("search.exact.match")).apply {
            toolTipText = NacosSearchBundle.message("search.exact.match.tooltip")
        }
        
        caseSensitiveCheckBox = JCheckBox(NacosSearchBundle.message("search.case.sensitive")).apply {
            toolTipText = NacosSearchBundle.message("search.case.sensitive.tooltip")
        }
        
//        // Advanced panel
//        advancedPanel = createAdvancedPanel()
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty()
        setLayout(BorderLayout(6, 0))

        val searchLabelComp = JBLabel(NacosSearchBundle.message("search.label")).apply {
            font = font.deriveFont(Font.PLAIN, 11.5f)
            foreground = JBColor(0x6f737a, 0x9b9ea6)
            preferredSize = Dimension(54, 24)
        }

        searchField.apply {
            preferredSize = Dimension(200, 26)
            minimumSize = Dimension(120, 26)
            border = JBUI.Borders.empty(0, 22, 0, 0)
        }

        // Search icon inside the field area
        val searchIcon = JBLabel(AllIcons.Actions.Search).apply {
            foreground = JBColor(0x6f737a, 0x9b9ea6)
        }
        val fieldWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(searchIcon, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }

        clearButton.apply {
            preferredSize = Dimension(20, 20)
            minimumSize = Dimension(20, 20)
            border = JBUI.Borders.empty(2)
            isContentAreaFilled = false
        }

        // Group filter pill: bordered compact button per design
        groupFilterButton.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(0x4e5157, 0xb9bdc9), 1),
            JBUI.Borders.empty(0, 8)
        )
        groupFilterButton.isContentAreaFilled = false
        groupFilterButton.margin = JBUI.insets(0, 8, 0, 8)

        // East wrapper: clear button + group filter pill
        val eastPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(clearButton)
            add(groupFilterButton)
        }

        add(searchLabelComp, BorderLayout.WEST)
        add(fieldWrapper, BorderLayout.CENTER)
        add(eastPanel, BorderLayout.EAST)

        // Search button is hidden — Enter in field triggers search (real-time)
        searchButton.isVisible = false
        searchLabel.isVisible = false
    }
    
    private fun createAdvancedPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.LIGHT_GRAY, 1, 0, 0, 0),
                JBUI.Borders.emptyTop(10)
            )
            
            // Field search section
            add(JBLabel(NacosSearchBundle.message("search.field.search")).apply {
                font = font.deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            
            add(Box.createVerticalStrut(5))
            
            // Data ID row
            add(createFieldRow(NacosSearchBundle.message("search.data.id"), dataIdField))
            add(Box.createVerticalStrut(3))
            
            // Group row
            add(createFieldRow(NacosSearchBundle.message("search.group"), groupField))
            add(Box.createVerticalStrut(3))
            
            // Content row
            add(createFieldRow(NacosSearchBundle.message("search.content"), contentField))
            add(Box.createVerticalStrut(10))
            
            // Options section
            add(JBLabel(NacosSearchBundle.message("search.options")).apply {
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
                val applyButton = JButton(NacosSearchBundle.message("common.apply")).apply {
                    addActionListener { performAdvancedSearch() }
                }
                
                val resetButton = JButton(NacosSearchBundle.message("common.reset")).apply {
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
//        advancedToggle.addActionListener {
//            toggleAdvancedPanel()
//        }
        
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
                searchText.isEmpty() -> NacosSearchBundle.message("search.placeholder")
                searchText == "*" -> NacosSearchBundle.message("search.wildcard.tooltip")
                searchText.startsWith("*") && searchText.length > 1 -> NacosSearchBundle.message("search.prefix.tooltip", searchText.substring(1))
                searchText.contains("*") || searchText.contains("?") -> NacosSearchBundle.message("search.fuzzy.tooltip")
                else -> NacosSearchBundle.message("search.exact.tooltip", searchText)
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
        // advancedPanel.isVisible = isAdvancedVisible
        // advancedToggle.text = if (isAdvancedVisible) "Simple" else "Advanced"
        
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
        // advancedToggle.isEnabled = enabled
        
        dataIdField.isEnabled = enabled
        groupField.isEnabled = enabled
        contentField.isEnabled = enabled
        exactMatchCheckBox.isEnabled = enabled
        caseSensitiveCheckBox.isEnabled = enabled
    }
    
    /**
     * Updates the list of available groups (called by the window after data loads).
     */
    fun setAvailableGroups(groups: List<String>) {
        val allLabel = NacosSearchBundle.message("search.group.filter.all")
        availableGroups = if (groups.isEmpty()) listOf(allLabel) else listOf(allLabel) + groups.distinct().sorted()
        // Reset selection if the previously selected group is no longer available
        if (selectedGroup != allLabel && selectedGroup !in groups) {
            selectedGroup = allLabel
            updateGroupFilterLabel()
        }
    }

    /**
     * Shows a popup menu of available groups above the filter button.
     */
    private fun showGroupFilterPopup() {
        val popup = JPopupMenu()
        val buttonGroup = javax.swing.ButtonGroup()
        availableGroups.forEach { group ->
            val item = JCheckBoxMenuItem(group).apply {
                isSelected = group == selectedGroup
                addActionListener {
                    selectedGroup = group
                    updateGroupFilterLabel()
                    val allLabel = NacosSearchBundle.message("search.group.filter.all")
                    val groupValue = if (group == allLabel) "" else group
                    // Trigger search with the new group filter
                    val query = searchField.text.trim()
                    val criteria = SearchCriteria(
                        query = query,
                        group = groupValue,
                        useRegex = true,
                        caseSensitive = false
                    )
                    searchCriteria = criteria
                    onGroupFilterChanged?.invoke(groupValue)
                    // Always re-run the search so group filtering works even with an empty query.
                    onSearchRequested?.invoke(criteria)
                }
            }
            buttonGroup.add(item)
            popup.add(item)
        }
        popup.preferredSize = Dimension(maxOf(groupFilterButton.width, 140), popup.preferredSize.height)
        popup.show(groupFilterButton, 0, groupFilterButton.height)
    }

    /**
     * Updates the group filter button label to show the current selection.
     */
    private fun updateGroupFilterLabel() {
        groupFilterButton.text = NacosSearchBundle.message("search.group.filter.label") + " " + selectedGroup
    }

    /**
     * Called when the language is changed
     */
    override fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage) {
        // Update placeholder text
        searchField.emptyText.text = NacosSearchBundle.message("search.placeholder")
        
        // Update button tooltips
        searchButton.toolTipText = NacosSearchBundle.message("search.button.tooltip")
        clearButton.toolTipText = NacosSearchBundle.message("search.clear.tooltip")
        
        // Update labels
        searchModeLabel.text = NacosSearchBundle.message("search.mode.label")
        searchLabel.text = NacosSearchBundle.message("search.label") + ": "
        
        // Update checkbox text
        exactMatchCheckBox.text = NacosSearchBundle.message("search.exact.match")
        caseSensitiveCheckBox.text = NacosSearchBundle.message("search.case.sensitive")
        
        // Revalidate and repaint
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
