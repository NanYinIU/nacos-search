package com.github.nacos.search.ui

import com.github.nacos.search.services.NacosSearchService
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.flow.StateFlow
import java.awt.FlowLayout
import javax.swing.*

/**
 * Pagination panel for search results
 */
class PaginationPanel : JPanel(FlowLayout(FlowLayout.CENTER, 5, 0)) {
    
    // UI Components
    private val previousButton = JButton("prev").apply {
        icon = AllIcons.Actions.Back
        isEnabled = false
    }
    
    private val nextButton = JButton("next").apply {
        icon = AllIcons.Actions.Forward
        isEnabled = false
    }
    
    private val pageInfoLabel = JBLabel("0 page，total 0 pages")
    private val totalCountLabel = JBLabel("Total 0")
    
    private val pageSizeComboBox = JComboBox(arrayOf(10, 20, 50, 100)).apply {
        selectedItem = 10
        toolTipText = ""
    }
    
    private val pageSizeLabel = JBLabel("Page size")
    
    // Event handlers
    var onPreviousPage: (() -> Unit)? = null
    var onNextPage: (() -> Unit)? = null
    var onPageSizeChanged: ((Int) -> Unit)? = null
    
    init {
        setupLayout()
        setupEventHandlers()
        border = JBUI.Borders.empty(5)
    }
    
    private fun setupLayout() {
        // Add components in order
        add(totalCountLabel)
        add(Box.createHorizontalStrut(10))
        add(previousButton)
        add(pageInfoLabel)
        add(nextButton)
        add(Box.createHorizontalStrut(10))
        add(pageSizeLabel)
        add(pageSizeComboBox)
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
            pageInfoLabel.text = "Page 1, Total 1 Pages"
            totalCountLabel.text = "Total 0"
            
            // Set default page size
            pageSizeComboBox.selectedItem = 10
            pageSizeComboBox.isEnabled = true
        }
    }
    
    /**
     * Updates pagination display based on state
     */
    fun updatePagination(state: NacosSearchService.PaginationState) {
        SwingUtilities.invokeLater {
            // Always keep panel visible once initialized
            isVisible = true
            
            // Update buttons
            previousButton.isEnabled = state.hasPreviousPage
            nextButton.isEnabled = state.hasNextPage
            
            // Update labels with better formatting
            pageInfoLabel.text = if (state.totalPages > 0) {
                "Page ${state.currentPage}, Total ${state.totalPages} Pages"
            } else {
                "Page 1, Total 1 Pages"
            }
            
            totalCountLabel.text = "Total ${state.totalCount}"
            
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
            pageInfoLabel.text = "0 page，total 0 pages"
            totalCountLabel.text = "Total 0"
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
}