package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.listeners.NamespaceChangeListener
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.border.EmptyBorder

/**
 * Panel for displaying configuration list with file-type badges.
 *
 * Uses a JBList with a custom cell renderer that shows the config's
 * dataId, group, and a colored file-type badge (YAML / JSON / properties).
 */
class ConfigListPanel(private val project: Project) : JPanel(BorderLayout()), NamespaceChangeListener, LanguageAwareComponent {

    private val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
    private val languageService = ApplicationManager.getApplication().getService(LanguageService::class.java)

    // UI Components
    private lateinit var configList: JBList<NacosConfiguration>
    private lateinit var listModel: DefaultListModel<NacosConfiguration>
    private lateinit var scrollPane: JBScrollPane
    private lateinit var loadingLabel: JBLabel
    private lateinit var statusLabel: JBLabel
    private lateinit var headerLabel: JBLabel
    private lateinit var refreshButton: JButton
    private lateinit var emptyStatePanel: JPanel

    // State
    private var isLoading = false
    private var configurations: List<NacosConfiguration> = emptyList()
    private var currentNamespace: NamespaceInfo? = null
    private var currentPage = 1
    private var pageSize = 10
    // Current search query for highlighting matched fragments in the list
    private var currentSearchQuery: String = ""
    // Set of config keys that have unsaved edits (for the red dot indicator)
    private val dirtyConfigKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var onConfigurationSelected: ((NacosConfiguration) -> Unit)? = null
    var onRefreshRequested: (() -> Unit)? = null

    init {
        initializeComponents()
        setupLayout()
        setupEventHandlers()
        namespaceService.addNamespaceChangeListener(this)
        currentNamespace = namespaceService.getCurrentNamespace()
    }

    private fun initializeComponents() {
        listModel = DefaultListModel()
        configList = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = ConfigItemRenderer()
            fixedCellHeight = 44
            border = JBUI.Borders.empty(1, 5)
        }

        scrollPane = JBScrollPane(configList).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }

        loadingLabel = JBLabel().apply {
            icon = AnimatedIcon.Default.INSTANCE
            text = NacosSearchBundle.message("config.list.loading")
            horizontalAlignment = SwingConstants.CENTER
            isVisible = false
        }

        statusLabel = JBLabel(NacosSearchBundle.message("common.ready")).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(5)
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = NacosSearchBundle.message("config.list.refresh")
            preferredSize = Dimension(26, 26)
        }

        emptyStatePanel = createEmptyStatePanel()
    }

    private fun setupLayout() {
        border = JBUI.Borders.empty(2, 4, 2, 4)

        // Header bar
        headerLabel = JBLabel(NacosSearchBundle.message("config.list.title")).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor(0x6f737a, 0x9b9ea6)
        }
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 6, 3, 6)
            add(headerLabel, BorderLayout.CENTER)
            add(refreshButton.apply {
                preferredSize = Dimension(24, 24)
            }, BorderLayout.EAST)
        }

        // Center: list / loading / empty
        val centerPanel = JPanel(CardLayout()).apply {
            add(scrollPane, "table")
            add(loadingLabel, "loading")
            add(emptyStatePanel, "empty")
        }

        add(headerPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun setupEventHandlers() {
        refreshButton.addActionListener {
            onRefreshRequested?.invoke()
        }

        configList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 1) {
                    val idx = configList.locationToIndex(e.point)
                    if (idx >= 0 && idx < listModel.size()) {
                        val config = listModel.getElementAt(idx)
                        configList.selectedIndex = idx
                        onConfigurationSelected?.invoke(config)
                    }
                }
            }
        })

        configList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val idx = configList.selectedIndex
                if (idx >= 0 && idx < listModel.size()) {
                    val config = listModel.getElementAt(idx)
                    onConfigurationSelected?.invoke(config)
                }
            }
        }
    }

    private fun createEmptyStatePanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val messageLabel = JBLabel(NacosSearchBundle.message("config.list.empty")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = JBColor.GRAY
            }
            val instructionLabel = JBLabel(NacosSearchBundle.message("config.list.empty.instruction")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = JBColor.GRAY
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

    // ------------------------------------------------------------------
    // Public API (kept compatible with NacosSearchWindow)
    // ------------------------------------------------------------------

    fun refresh() {
        onRefreshRequested?.invoke()
    }

    fun setConfigurations(newConfigurations: List<NacosConfiguration>) {
        setLoadingState(false)
        configurations = newConfigurations
        updateList()

        headerLabel.text = if (configurations.isEmpty()) {
            NacosSearchBundle.message("config.list.title")
        } else {
            "${NacosSearchBundle.message("config.list.title")}（${configurations.size}）"
        }

        if (configurations.isEmpty()) {
            showCard("empty")
            updateStatus(NacosSearchBundle.message("config.list.empty"))
        } else {
            showCard("table")
            updateStatus("")
        }
    }

    fun setLoading(loading: Boolean) {
        setLoadingState(loading)
        if (loading) {
            showCard("loading")
            updateStatus(NacosSearchBundle.message("config.list.loading"))
        }
    }

    fun showError(message: String) {
        setLoadingState(false)
        showCard("empty")
        updateStatus(message)
    }

    fun setPage(page: Int) {
        currentPage = page
    }

    fun getCurrentPage(): Int = currentPage
    fun getPageSize(): Int = pageSize
    fun getConfigurationCount(): Int = configurations.size

    fun dispose() {
        namespaceService.removeNamespaceChangeListener(this)
        coroutineScope.cancel()
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun setLoadingState(loading: Boolean) {
        isLoading = loading
    }

    private fun updateList() {
        listModel.clear()
        configurations.forEach { listModel.addElement(it) }
    }

    private fun showCard(cardName: String) {
        val centerPanel = getComponent(1) as? JPanel ?: return
        (centerPanel.layout as? CardLayout)?.show(centerPanel, cardName)
    }

    private fun updateStatus(text: String) {
        statusLabel.text = text
    }

    override suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
        currentNamespace = newNamespace
        currentPage = 1
        pageSize = 10
        // Clear the list immediately for feedback; NacosSearchWindow owns reloading configs
        // for the newly selected namespace (avoids a duplicate/duplicate-origin reload).
        SwingUtilities.invokeLater {
            configurations = emptyList()
            listModel.clear()
        }
    }

    // ------------------------------------------------------------------
    // Cell renderer: file-type badge + dataId + group subtitle
    // ------------------------------------------------------------------

    private inner class ConfigItemRenderer : JPanel(BorderLayout()), ListCellRenderer<NacosConfiguration> {
        private val badgeLabel = JLabel()
        private val dataIdLabel = JLabel()
        private val groupLabel = JLabel()
        private val extBadgeLabel = JLabel()
        private val dirtyDot = JLabel()

        init {
            border = EmptyBorder(4, 8, 4, 8)

            badgeLabel.preferredSize = Dimension(18, 18)
            badgeLabel.horizontalAlignment = SwingConstants.CENTER
            badgeLabel.font = badgeLabel.font.deriveFont(Font.BOLD, 9f)
            badgeLabel.isOpaque = true
            badgeLabel.foreground = Color.WHITE

            // Data ID uses monospace font per design guide (machine-readable value)
            dataIdLabel.font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 12)

            // Extension badge (e.g. "properties") — compact label on the right
            extBadgeLabel.font = extBadgeLabel.font.deriveFont(Font.PLAIN, 10f)
            extBadgeLabel.foreground = JBColor(0x6f737a, 0x9b9ea6)
            extBadgeLabel.horizontalAlignment = SwingConstants.CENTER

            val textPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = EmptyBorder(0, 6, 0, 0)
                add(dataIdLabel)
                add(groupLabel)
            }

            // Dirty dot (orange warning, right-aligned) — shown when config has unsaved edits
            dirtyDot.preferredSize = Dimension(6, 6)
            dirtyDot.horizontalAlignment = SwingConstants.CENTER

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(extBadgeLabel)
                add(dirtyDot)
            }

            add(badgeLabel, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
            isOpaque = true
        }

        override fun getListCellRendererComponent(
            list: JList<out NacosConfiguration>?,
            value: NacosConfiguration?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) return this

          // Set accessible name so the accessibility tree reports each row's
          // dataId correctly (Swing misreports text from composite JPanel renderers)
          accessibleContext?.accessibleName = "${value.dataId} | ${value.group}"

            val configType = value.getConfigType().lowercase()
            val (shortLabel, bg) = getBadgeForType(configType)
            badgeLabel.text = shortLabel
            badgeLabel.background = bg

            dataIdLabel.text = highlightDataId(value.dataId, isSelected)
            dataIdLabel.font = dataIdLabel.font.deriveFont(Font.PLAIN, 12f)

            val nsId = value.tenantId ?: ""
            groupLabel.text = if (nsId.isNotEmpty()) NacosSearchBundle.message("config.list.subtitle.format", value.group, nsId) else value.group
            groupLabel.font = groupLabel.font.deriveFont(Font.PLAIN, 11f)
            groupLabel.foreground = JBColor.GRAY

            // Extension badge
            extBadgeLabel.text = value.dataId.substringAfterLast(".", "")
                .replaceFirstChar { it.uppercase() }

            // Show orange dot if this config has unsaved edits
            val isDirty = value.getKey() in dirtyConfigKeys
            if (isDirty) {
                dirtyDot.icon = object : javax.swing.Icon {
                    override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
                        g.color = JBColor(0xe3a008, 0xb8860b)
                        g.fillOval(x + 4, y + 4, 6, 6)
                    }
                    override fun getIconWidth() = 14
                    override fun getIconHeight() = 14
                }
                dirtyDot.isVisible = true
            } else {
                dirtyDot.icon = null
                dirtyDot.isVisible = false
            }

            if (isSelected) {
                background = list?.selectionBackground ?: JBColor(0x2e436e, 0x2e436e)
                dataIdLabel.foreground = list?.selectionForeground ?: Color.WHITE
            } else {
                background = list?.background ?: Color.WHITE
                dataIdLabel.foreground = list?.foreground ?: Color.BLACK
            }

            return this
        }

        private fun getBadgeForType(type: String): Pair<String, Color> {
            return when {
                type.contains("yaml") || type.contains("yml") -> "Y" to Color(0xcb, 0x6b, 0x3f)
                type.contains("json") -> "J" to Color(0xca, 0xa5, 0x3d)
                type.contains("properties") -> "P" to Color(0x50, 0x8c, 0xc4)
                type.contains("xml") -> "X" to Color(0x8c, 0x6c, 0xb4)
                type.contains("text") -> "T" to Color(0x7a, 0x7e, 0x85)
                else -> "T" to Color(0x7a, 0x7e, 0x85)
            }
        }

        /**
         * Returns an HTML-formatted dataId with the search query match
         * highlighted in accent blue. Always returns an `<html>…</html>`
         * string so the JLabel stays in consistent HTML rendering mode.
         */
        private fun highlightDataId(dataId: String, isSelected: Boolean): String {
            val query = currentSearchQuery.trim().trimStart('*').trimStart('?')
            if (query.isEmpty() || query.length < 2) {
                return "<html>${escapeHtml(dataId)}</html>"
            }
            val idx = dataId.indexOf(query, ignoreCase = true)
            if (idx < 0) return "<html>${escapeHtml(dataId)}</html>"

            val before = escapeHtml(dataId.substring(0, idx))
            val match = escapeHtml(dataId.substring(idx, idx + query.length))
            val after = escapeHtml(dataId.substring(idx + query.length))

            // Highlight color: accent blue (#3574f0) on light selection, white text on dark selection.
            // Use bold for the matched fragment.
            val highlightColor = if (isSelected) "#ffffff" else "#3574f0"
            return "<html>$before<font color='$highlightColor'><b>$match</b></font>$after</html>"
        }

        private fun escapeHtml(text: String): String {
            return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }
    }

    /**
     * Sets the current search query so the cell renderer can highlight
     * matched fragments in each row's dataId.
     */
    fun setSearchQuery(query: String) {
        currentSearchQuery = query
        configList.repaint()
    }

    /**
     * Marks a configuration as dirty (unsaved edits) or clean.
     * Drives the red dot indicator on the list row.
     */
    fun setConfigDirty(configKey: String, dirty: Boolean) {
        if (dirty) dirtyConfigKeys.add(configKey) else dirtyConfigKeys.remove(configKey)
        configList.repaint()
    }

    // ------------------------------------------------------------------
    // LanguageAwareComponent
    // ------------------------------------------------------------------

    override fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage) {
        SwingUtilities.invokeLater {
            loadingLabel.text = NacosSearchBundle.message("config.list.loading")
            updateStatus(
                if (configurations.isEmpty()) NacosSearchBundle.message("config.list.empty")
                else NacosSearchBundle.message("config.list.loaded", configurations.size)
            )
            refreshButton.toolTipText = NacosSearchBundle.message("tooltip.config.refresh")
            revalidate()
            repaint()
        }
    }

    override fun getLanguageService(): LanguageService = languageService
}
