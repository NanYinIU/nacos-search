package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.SearchCriteria
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.services.NacosLanguageListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.basic.BasicButtonUI
import javax.swing.plaf.basic.BasicTextFieldUI
import com.intellij.openapi.application.ModalityState
import com.nanyin.nacos.search.Edt
/**
 * Panel for search functionality
 */
class SearchPanel(private val project: Project) : JPanel(BorderLayout()), NacosLanguageListener, Disposable {

    // UI Components
    private lateinit var searchField: PlaceholderTextField
    private lateinit var searchFieldPanel: JPanel
    private lateinit var clearButton: JButton
    private lateinit var groupFilterButton: JButton

    // State
    private var searchCriteria = SearchCriteria()

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
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(NacosLanguageListener.TOPIC, this)
    }
    
    private fun initializeComponents() {
        // Main search field
        searchField = PlaceholderTextField().apply {
            putClientProperty("JTextField.Search.noBorderRing", true)
            setUI(BasicTextFieldUI())
            placeholder = NacosSearchBundle.message("search.placeholder")
            columns = 20
            font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 12)
            border = JBUI.Borders.empty(0, 6)
            isOpaque = false
            isBorderless = true
        }
        searchFieldPanel = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            border = lightweightControlBorder(horizontalInset = LEADING_ICON_INSET)
            add(JBLabel(AllIcons.Actions.Search).apply {
                foreground = JBColor(0x6f737a, 0x9b9ea6)
                preferredSize = Dimension(16, CONTROL_HEIGHT)
                horizontalAlignment = SwingConstants.CENTER
            }, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }
        
        // Action buttons
        clearButton = JButton(AllIcons.Actions.Close).apply {
            putClientProperty("JButton.buttonType", "toolbar")
            setUI(BasicButtonUI())
            toolTipText = NacosSearchBundle.message("search.clear.tooltip")
            preferredSize = Dimension(26, 26)
            minimumSize = Dimension(26, 26)
            isEnabled = false
            isBorderPainted = false
            isFocusPainted = false
        }

        // Group filter pill — compact bordered button per design guide
        groupFilterButton = JButton(NacosSearchBundle.message("search.group.filter.label") + " " + NacosSearchBundle.message("search.group.filter.all")).apply {
            putClientProperty("JButton.buttonType", "toolbar")
            setUI(BasicButtonUI())
            toolTipText = NacosSearchBundle.message("tooltip.group.filter")
            margin = JBUI.insets(0, 9, 0, 9)
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 12)
            border = lightweightControlBorder(horizontalInset = 10)
            addActionListener { showGroupFilterPopup() }
        }
        updateGroupFilterLabel()
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty()
        setLayout(BorderLayout(6, 0))

        val searchLabelComp = JBLabel(NacosSearchBundle.message("search.label")).apply {
            font = font.deriveFont(Font.PLAIN, 11.5f)
            foreground = JBColor(0x6f737a, 0x9b9ea6)
            preferredSize = Dimension(FORM_LABEL_WIDTH, 24)
        }

        searchFieldPanel.apply {
            preferredSize = Dimension(200, 26)
            minimumSize = Dimension(120, 26)
            maximumSize = Dimension(Int.MAX_VALUE, CONTROL_HEIGHT)
        }

        clearButton.apply {
            preferredSize = Dimension(26, 26)
            minimumSize = Dimension(26, 26)
            maximumSize = Dimension(26, 26)
            border = JBUI.Borders.empty()
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            horizontalAlignment = SwingConstants.CENTER
        }

        // Group filter uses the same lightweight control treatment as search and namespace.
        groupFilterButton.border = lightweightControlBorder(horizontalInset = 10)
        groupFilterButton.isContentAreaFilled = false
        groupFilterButton.isBorderPainted = false
        groupFilterButton.isFocusPainted = false
        groupFilterButton.margin = JBUI.insets(0, 6, 0, 6)

        // East wrapper: clear button + group filter pill
        val eastPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(clearButton)
            add(groupFilterButton)
        }

        add(searchLabelComp, BorderLayout.WEST)
        add(searchFieldPanel, BorderLayout.CENTER)
        add(eastPanel, BorderLayout.EAST)
    }

    private fun setupEventHandlers() {
        // Clear button
        clearButton.addActionListener {
            clearSearch()
        }

        // Enter key in search field
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    performSearch()
                }
            }
        })

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
        Edt.invokeOnEdt(ModalityState.defaultModalityState()) {
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
    
    private fun clearSearch() {
        searchField.text = ""
        searchCriteria = SearchCriteria()
        onSearchCleared?.invoke()
    }

    private fun updateClearButtonState() {
        clearButton.isEnabled = searchField.text.isNotEmpty()
    }

    /**
     * Get current search query
     */
    fun getSearchQuery(): String = searchField.text.trim()

    /**
     * Clear all search criteria
     */
    fun clearAllCriteria() {
        clearSearch()
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
        groupFilterButton.toolTipText = groupFilterButton.text
        updateButtonWidthForText(
            groupFilterButton,
            GROUP_BUTTON_MIN_WIDTH,
            GROUP_BUTTON_MAX_WIDTH,
            CONTROL_HEIGHT
        )
        revalidate()
        repaint()
    }

    /**
     * Called when the language is changed
     */
    override fun languageChanged() {
        Edt.invokeOnEdt(ModalityState.defaultModalityState()) {
            searchField.placeholder = NacosSearchBundle.message("search.placeholder")
            clearButton.toolTipText = NacosSearchBundle.message("search.clear.tooltip")
            revalidate()
            repaint()
        }
    }

    /** Anchors the message-bus connection; the panel holds nothing else to release. */
    override fun dispose() = Unit

    private fun updateButtonWidthForText(button: JButton, minWidth: Int, maxWidth: Int, height: Int) {
        val naturalWidth = button.getPreferredSize().width
        val width = naturalWidth.coerceIn(minWidth, maxWidth)
        button.minimumSize = Dimension(minWidth, height)
        button.preferredSize = Dimension(width, height)
        button.maximumSize = Dimension(maxWidth, height)
    }

    private fun lightweightControlBorder(horizontalInset: Int) = JBUI.Borders.empty(0, horizontalInset)

    companion object {
        private const val FORM_LABEL_WIDTH = 74
        private const val GROUP_BUTTON_MIN_WIDTH = 112
        private const val GROUP_BUTTON_MAX_WIDTH = 260
        private const val CONTROL_HEIGHT = 26
        private const val LEADING_ICON_INSET = 16
    }

    private class PlaceholderTextField : JTextField() {
        var placeholder: String = ""
            set(value) {
                field = value
                repaint()
            }

        var isBorderless: Boolean = false
            set(value) {
                field = value
                if (value) {
                    border = JBUI.Borders.empty(0, 6)
                    isOpaque = false
                }
            }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isNotEmpty() || placeholder.isEmpty()) return

            val g2 = g.create() as Graphics2D
            try {
                g2.font = font
                g2.color = JBColor(0x6f737a, 0x8f939c)
                val insets = insets
                val metrics = g2.fontMetrics
                val y = (height - metrics.height) / 2 + metrics.ascent
                g2.drawString(placeholder, insets.left, y)
            } finally {
                g2.dispose()
            }
        }
    }
}
