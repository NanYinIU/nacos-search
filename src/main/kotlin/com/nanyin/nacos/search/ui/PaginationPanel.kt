package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.nanyin.nacos.search.listeners.NamespaceChangeListener
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
import java.awt.*
import javax.swing.*
import javax.swing.plaf.basic.BasicButtonUI

/**
 * Compact pagination strip for the config list.
 *
 * IntelliJ tool-window convention: icon prev/next around a short page label,
 * total count on the left, and a narrow page-size combo on the right
 * (fixed width so BorderLayout.EAST does not stretch it).
 */
class PaginationPanel : JPanel(BorderLayout()), NamespaceChangeListener, LanguageAwareComponent, Disposable {
    private val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
    private val languageService = ApplicationManager.getApplication().getService(LanguageService::class.java)

    private val previousButton = toolbarIconButton(AllIcons.Actions.Back, NacosSearchBundle.message("pagination.previous"))
    private val nextButton = toolbarIconButton(AllIcons.Actions.Forward, NacosSearchBundle.message("pagination.next"))

    private val pageInfoLabel = JBLabel(NacosSearchBundle.message("pagination.page.of.format", 1, 1)).apply {
        font = font.deriveFont(Font.PLAIN, 11.5f)
        foreground = JBColor(0x6f737a, 0x9b9ea6)
    }
    private val totalCountLabel = JBLabel(NacosSearchBundle.message("pagination.items.count", 0)).apply {
        font = font.deriveFont(Font.PLAIN, 11.5f)
        foreground = JBColor(0x6f737a, 0x9b9ea6)
    }

    private val pageSizeComboBox = ComboBox(arrayOf(10, 20, 50)).apply {
        selectedItem = 10
        toolTipText = NacosSearchBundle.message("tooltip.page.size")
        // Lock width to the widest item + arrow; otherwise EAST grows with the panel.
        prototypeDisplayValue = 50
        preferredSize = Dimension(PAGE_SIZE_WIDTH, 22)
        minimumSize = Dimension(PAGE_SIZE_WIDTH, 22)
        maximumSize = Dimension(PAGE_SIZE_WIDTH, 22)
        font = font.deriveFont(Font.PLAIN, 11f)
    }

    private val pageSizeLabel = JBLabel(NacosSearchBundle.message("pagination.page.size.prefix")).apply {
        font = font.deriveFont(Font.PLAIN, 11.5f)
        foreground = JBColor(0x6f737a, 0x9b9ea6)
    }

    var onPreviousPage: (() -> Unit)? = null
    var onNextPage: (() -> Unit)? = null
    var onPageSizeChanged: ((Int) -> Unit)? = null

    private var paginationState: NacosSearchService.PaginationState? = null

    init {
        setupLayout()
        setupEventHandlers()
        border = JBUI.Borders.empty(2, 4)
        namespaceService.addNamespaceChangeListener(this)
    }

    private fun setupLayout() {
        // Left: total · [<] page · [>]   Right: short label + fixed-width combo
        val navGroup = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(previousButton)
            add(pageInfoLabel)
            add(nextButton)
        }

        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            isOpaque = false
            add(totalCountLabel)
            add(navGroup)
        }

        val rightGroup = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            isOpaque = false
            add(pageSizeLabel)
            add(pageSizeComboBox)
        }

        val paginationRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(leftGroup, BorderLayout.WEST)
            add(rightGroup, BorderLayout.EAST)
        }

        add(paginationRow, BorderLayout.CENTER)
    }

    private fun setupEventHandlers() {
        previousButton.addActionListener { onPreviousPage?.invoke() }
        nextButton.addActionListener { onNextPage?.invoke() }
        pageSizeComboBox.addActionListener {
            val newPageSize = pageSizeComboBox.selectedItem as Int
            onPageSizeChanged?.invoke(newPageSize)
        }
    }

    fun setInitialState() {
        SwingUtilities.invokeLater {
            isVisible = true
            previousButton.isEnabled = false
            nextButton.isEnabled = false
            pageInfoLabel.text = NacosSearchBundle.message("pagination.page.of.format", 1, 1)
            totalCountLabel.text = NacosSearchBundle.message("pagination.items.count", 0)
            pageSizeComboBox.selectedItem = 10
            pageSizeComboBox.isEnabled = true
        }
    }

    fun updatePagination(state: NacosSearchService.PaginationState) {
        paginationState = state
        SwingUtilities.invokeLater {
            isVisible = true
            previousButton.isEnabled = state.hasPreviousPage
            nextButton.isEnabled = state.hasNextPage
            val totalPages = if (state.totalPages > 0) state.totalPages else 1
            pageInfoLabel.text = NacosSearchBundle.message("pagination.page.of.format", state.currentPage, totalPages)
            totalCountLabel.text = NacosSearchBundle.message("pagination.items.count", state.totalCount)
            if (pageSizeComboBox.selectedItem as Int != state.pageSize) {
                pageSizeComboBox.selectedItem = state.pageSize
            }
            pageSizeComboBox.isEnabled = true
        }
    }

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

    fun setLoading(loading: Boolean) {
        SwingUtilities.invokeLater {
            previousButton.isEnabled = !loading
            nextButton.isEnabled = !loading
            pageSizeComboBox.isEnabled = !loading
        }
    }

    fun getCurrentPageSize(): Int = pageSizeComboBox.selectedItem as Int

    fun setPageSize(pageSize: Int) {
        SwingUtilities.invokeLater {
            pageSizeComboBox.selectedItem = pageSize
        }
    }

    override suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
        val totalCountNum = newNamespace?.configCount ?: 0
        val pageSize = 10
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

    override fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage) {
        refreshUIText()
        revalidate()
        repaint()
    }

    override fun getLanguageService(): LanguageService = languageService

    private fun refreshUIText() {
        SwingUtilities.invokeLater {
            previousButton.toolTipText = NacosSearchBundle.message("pagination.previous")
            nextButton.toolTipText = NacosSearchBundle.message("pagination.next")
            pageSizeComboBox.toolTipText = NacosSearchBundle.message("tooltip.page.size")
            pageSizeLabel.text = NacosSearchBundle.message("pagination.page.size.prefix")
            val currentPage = paginationState?.currentPage ?: 1
            val totalCount = paginationState?.totalCount ?: 0
            val totalPages = paginationState?.totalPages ?: 1
            pageInfoLabel.text = NacosSearchBundle.message("pagination.page.of.format", currentPage, totalPages)
            totalCountLabel.text = NacosSearchBundle.message("pagination.items.count", totalCount)
        }
    }

    companion object {
        private const val PAGE_SIZE_WIDTH = 56

        private fun toolbarIconButton(icon: Icon, tooltip: String): JButton {
            return JButton(icon).apply {
                isEnabled = false
                toolTipText = tooltip
                putClientProperty("JButton.buttonType", "toolbar")
                ui = BasicButtonUI()
                preferredSize = Dimension(24, 22)
                minimumSize = Dimension(24, 22)
                maximumSize = Dimension(24, 22)
                border = JBUI.Borders.empty()
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
            }
        }
    }
}
