package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.nanyin.nacos.search.listeners.NamespaceChangeListener
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
import java.awt.*
import javax.swing.*

/**
 * Pagination panel for search results
 */
class PaginationPanel : JPanel(BorderLayout()), NamespaceChangeListener, LanguageAwareComponent, Disposable {
    private val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
    private val languageService = ApplicationManager.getApplication().getService(LanguageService::class.java)
    // private val nacosSearchService = ApplicationManager.getApplication().getService(NacosSearchService::class.java)
    // UI Components
    private val previousButton = JButton(AllIcons.Actions.Back).apply {
        isEnabled = false
        toolTipText = NacosSearchBundle.message("pagination.previous")
        preferredSize = Dimension(24, 22)
        minimumSize = Dimension(24, 22)
        border = JBUI.Borders.empty()
        isContentAreaFilled = false
    }
    
    private val nextButton = JButton(AllIcons.Actions.Forward).apply {
        isEnabled = false
        toolTipText = NacosSearchBundle.message("pagination.next")
        preferredSize = Dimension(24, 22)
        minimumSize = Dimension(24, 22)
        border = JBUI.Borders.empty()
        isContentAreaFilled = false
    }
    
    private val pageInfoLabel = JBLabel(NacosSearchBundle.message("pagination.page.of.format", 1, 1))
    private val totalCountLabel = JBLabel(NacosSearchBundle.message("pagination.items.count", 0)).apply {
        font = font.deriveFont(Font.PLAIN, 11.5f)
        foreground = JBColor(0x6f737a, 0x9b9ea6)
    }
    
    private val pageSizeComboBox = JComboBox(arrayOf(10, 20, 50)).apply {
        selectedItem = 10
        toolTipText = NacosSearchBundle.message("tooltip.page.size")
    }
    
    private val pageSizeLabel = JBLabel(NacosSearchBundle.message("pagination.page.size"))
    
    // Event handlers
    var onPreviousPage: (() -> Unit)? = null
    var onNextPage: (() -> Unit)? = null
    var onPageSizeChanged: ((Int) -> Unit)? = null
    
    // State
    private var paginationState: NacosSearchService.PaginationState? = null
    
    init {
        setupLayout()
        setupEventHandlers()
        border = JBUI.Borders.empty(5)

        // Register as namespace change listener
        namespaceService.addNamespaceChangeListener(this)
    }
    
    private fun setupLayout() {
        border = JBUI.Borders.empty(2, 4) // Minimal padding for compact design
        
        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(totalCountLabel)
            add(previousButton)
            add(pageInfoLabel.apply {
                font = font.deriveFont(Font.PLAIN, 11.5f)
                foreground = JBColor(0x6f737a, 0x9b9ea6)
            })
            add(nextButton)
        }
        
        val rightGroup = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
            isOpaque = false
            add(pageSizeLabel.apply {
                font = font.deriveFont(Font.PLAIN, 11.5f)
                foreground = JBColor(0x6f737a, 0x9b9ea6)
            })
           add(pageSizeComboBox.apply {
                preferredSize = Dimension(72, 22)
                minimumSize = Dimension(64, 22)
               font = font.deriveFont(Font.PLAIN, 11f)
           })
        }
        
        val paginationRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(leftGroup, BorderLayout.WEST)
            add(rightGroup, BorderLayout.EAST)
        }
        
        add(paginationRow, BorderLayout.CENTER)
    }
    
    private fun setupEventHandlers() {
        previousButton.addActionListener {
            onPreviousPage?.invoke()
        }
        
        nextButton.addActionListener {
            onNextPage?.invoke()
        }
        
        pageSizeComboBox.addActionListener {
            val newPageSize = pageSizeComboBox.selectedItem as Int
            onPageSizeChanged?.invoke(newPageSize)
        }
    }
    
    /**
     * Sets initial state for immediate display
     */
    fun setInitialState() {
        SwingUtilities.invokeLater {
            // Enable pagination controls immediately
            isVisible = true
            
            // Set initial button states
            previousButton.isEnabled = false
            nextButton.isEnabled = false
            
            // Set initial labels
            pageInfoLabel.text = NacosSearchBundle.message("pagination.page.of.format", 1, 1)
            totalCountLabel.text = NacosSearchBundle.message("pagination.items.count", 0)
            
            // Set default page size
            pageSizeComboBox.selectedItem = 10
            pageSizeComboBox.isEnabled = true
        }
    }
    
    /**
     * Updates pagination display based on state
     */
    fun updatePagination(state: NacosSearchService.PaginationState) {
        // Store the current state for language changes
        paginationState = state
        
        SwingUtilities.invokeLater {
            // Always keep panel visible once initialized
            isVisible = true
            
            // Update buttons
            previousButton.isEnabled = state.hasPreviousPage
            nextButton.isEnabled = state.hasNextPage
            
            // Update labels with better formatting
            val totalPages = if (state.totalPages > 0) state.totalPages else 1
            pageInfoLabel.text = NacosSearchBundle.message("pagination.page.of.format", state.currentPage, totalPages)
            
            totalCountLabel.text = NacosSearchBundle.message("pagination.items.count", state.totalCount)
            
            // Update page size if different
            if (pageSizeComboBox.selectedItem as Int != state.pageSize) {
                pageSizeComboBox.selectedItem = state.pageSize
            }

            // Enable page size selector
            pageSizeComboBox.isEnabled = true
        }
    }
    
    /**
     * Resets pagination to initial state
     */
    fun reset() {
        SwingUtilities.invokeLater {
            previousButton.isEnabled = false
            nextButton.isEnabled = false
            pageInfoLabel.text = NacosSearchBundle.message("pagination.page.of.format", 1, 1)
            totalCountLabel.text = NacosSearchBundle.message("pagination.items.count", 0)
            pageSizeComboBox.selectedItem = 10
            isVisible = false
        }
    }
    
    /**
     * Sets loading state
     */
    fun setLoading(loading: Boolean) {
        SwingUtilities.invokeLater {
            previousButton.isEnabled = !loading
            nextButton.isEnabled = !loading
            pageSizeComboBox.isEnabled = !loading
        }
    }
    
    /**
     * Gets current page size
     */
    fun getCurrentPageSize(): Int {
        return pageSizeComboBox.selectedItem as Int
    }
    
    /**
     * Sets page size
     */
    fun setPageSize(pageSize: Int) {
        SwingUtilities.invokeLater {
            pageSizeComboBox.selectedItem = pageSize
        }
    }

    override suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
        val totalCountNum = newNamespace?.configCount ?: 0
        val pageSize = 10
        // Ceiling division so e.g. 9 items => 1 page (integer division previously gave 0).
        val totalPages = if (totalCountNum == 0) 0 else (totalCountNum + pageSize - 1) / pageSize
        updatePagination(NacosSearchService.PaginationState(
            currentPage = 1,
            pageSize = pageSize,
            totalCount = totalCountNum,
            totalPages = totalPages
        ))
    }

    override fun dispose() {
        namespaceService.removeNamespaceChangeListener(this)
    }
    
    /**
     * Called when the language is changed
     */
    override fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage) {
        // Refresh all UI text elements
        refreshUIText()
        
        // Update pagination labels
        updatePaginationLabels()
        
        // Update button tooltips
        updateButtonTooltips()
        
        // Update page size label
        updatePageSizeLabel()
        
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
            // Update button tooltips
            previousButton.toolTipText = NacosSearchBundle.message("pagination.previous")
            nextButton.toolTipText = NacosSearchBundle.message("pagination.next")
            pageSizeComboBox.toolTipText = NacosSearchBundle.message("tooltip.page.size")
            
            // Update labels
            pageSizeLabel.text = NacosSearchBundle.message("pagination.page.size")
            
            // Update pagination info
            updatePaginationLabels()
        }
    }
    
    /**
     * Update pagination labels
     */
    private fun updatePaginationLabels() {
        SwingUtilities.invokeLater {
            // Update page info and total count labels with current language
            val currentPage = paginationState?.currentPage ?: 1
            val totalCount = paginationState?.totalCount ?: 0
            
            val totalPages = paginationState?.totalPages ?: 1
            pageInfoLabel.text = NacosSearchBundle.message("pagination.page.of.format", currentPage, totalPages)
            totalCountLabel.text = NacosSearchBundle.message("pagination.items.count", totalCount)
        }
    }
    
    /**
     * Update button tooltips
     */
    private fun updateButtonTooltips() {
        SwingUtilities.invokeLater {
            previousButton.toolTipText = NacosSearchBundle.message("pagination.previous")
            nextButton.toolTipText = NacosSearchBundle.message("pagination.next")
            pageSizeComboBox.toolTipText = NacosSearchBundle.message("tooltip.page.size")
        }
    }
    
    /**
     * Update page size label
     */
    private fun updatePageSizeLabel() {
        SwingUtilities.invokeLater {
            pageSizeLabel.text = NacosSearchBundle.message("pagination.page.size")
        }
    }
}
