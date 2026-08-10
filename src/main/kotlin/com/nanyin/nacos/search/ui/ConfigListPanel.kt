package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.NacosLanguageListener
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.plaf.basic.BasicButtonUI
import java.util.concurrent.ConcurrentHashMap
import javax.swing.border.EmptyBorder
import com.intellij.openapi.application.ModalityState
import com.nanyin.nacos.search.invokeOnEdt
/**
 * Panel for displaying the configuration result list with file-type badges.
 *
 * Renders a closed [ConfigListViewState] set with one exhaustive branch and
 * holds no decision (issue #79). Mapping from search/access outcomes and
 * status-line wording live in [ConfigListPresentation] / [ConfigListStatusLine].
 *
 * Uses a JBList with a custom cell renderer that shows the config's
 * dataId, group, and a colored file-type badge (YAML / JSON / properties).
 */
class ConfigListPanel(private val project: Project) : JPanel(BorderLayout()), NacosLanguageListener, Disposable {

    // UI Components
    private lateinit var configList: JBList<NacosConfiguration>
    private lateinit var listModel: DefaultListModel<NacosConfiguration>
    private lateinit var scrollPane: JBScrollPane
    private lateinit var loadingLabel: JBLabel
    private lateinit var statusLabel: JBLabel
    private lateinit var headerLabel: JBLabel
    private lateinit var refreshButton: JButton
    private lateinit var emptyStatePanel: JPanel
    private lateinit var emptyMessageLabel: JBLabel
    private lateinit var emptyInstructionLabel: JBLabel
    private lateinit var failedStatePanel: JPanel
    private lateinit var failedMessageLabel: JBLabel
    private lateinit var failedInstructionLabel: JBLabel
    private lateinit var blockedStatePanel: JPanel
    private lateinit var blockedMessageLabel: JBLabel
    private lateinit var blockedInstructionLabel: JBLabel

    // State held only so language changes and selection helpers can re-apply it.
    // Decisions about which state to show are made upstream.
    private var viewState: ConfigListViewState = ConfigListViewState.Empty()
    private var configurations: List<NacosConfiguration> = emptyList()
    // Current search query for highlighting matched fragments in the list
    private var currentSearchQuery: String = ""
    // Set of config keys that have unsaved edits (for the red dot indicator)
    private val dirtyConfigKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // True while [restoreSelection] moves the highlight back, so a cancelled
    // action does not re-enter the selection handler it was cancelling.
    private var isRestoringSelection = false

    var onConfigurationSelected: ((NacosConfiguration) -> Unit)? = null
    var onRefreshRequested: (() -> Unit)? = null

    init {
        initializeComponents()
        setupLayout()
        setupEventHandlers()
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(NacosLanguageListener.TOPIC, this)
    }

    private fun initializeComponents() {
        listModel = DefaultListModel()
        configList = object : JBList<NacosConfiguration>(listModel) {
            /**
             * Names the unsaved-edit dot, which carries its meaning in colour alone.
             * The tooltip has to live here rather than on the renderer's dot: a cell
             * renderer is a rubber stamp, not a component in the hierarchy, so Swing
             * only ever consults the list's own tooltip. Row-wide rather than
             * dot-shaped — a 6px target is not a hit area worth aiming for.
             */
            override fun getToolTipText(event: java.awt.event.MouseEvent): String? {
                val index = locationToIndex(event.point)
                if (index < 0 || getCellBounds(index, index)?.contains(event.point) != true) return null
                val dirty = model.getElementAt(index)?.getKey() in dirtyConfigKeys
                return if (dirty) NacosSearchBundle.message("config.list.dirty") else null
            }
        }.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = ConfigItemRenderer()
            fixedCellHeight = 44
            border = JBUI.Borders.empty(1, 5)
            // ToolTipManager only polls components registered with it, and setToolTipText
            // is what normally registers one. Without this the override above never runs.
            ToolTipManager.sharedInstance().registerComponent(this)
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
            putClientProperty("JButton.buttonType", "toolbar")
            setUI(BasicButtonUI())
            preferredSize = Dimension(28, 24)
            minimumSize = Dimension(28, 24)
            border = JBUI.Borders.empty()
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
        }

        val empty = createMessageCard(
            icon = null,
            messageKey = "config.list.empty",
            instructionKey = "config.list.empty.instruction"
        )
        emptyStatePanel = empty.first
        emptyMessageLabel = empty.second
        emptyInstructionLabel = empty.third

        val failed = createMessageCard(
            icon = AllIcons.General.Error,
            messageKey = "config.list.failed",
            instructionKey = "config.list.failed.instruction"
        )
        failedStatePanel = failed.first
        failedMessageLabel = failed.second
        failedInstructionLabel = failed.third

        val blocked = createMessageCard(
            icon = AllIcons.General.Warning,
            messageKey = "config.list.blocked.access",
            instructionKey = "config.list.blocked.access.instruction"
        )
        blockedStatePanel = blocked.first
        blockedMessageLabel = blocked.second
        blockedInstructionLabel = blocked.third
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
            add(refreshButton, BorderLayout.EAST)
        }

        // Center: results / loading / empty / failed / blocked — one card per closed state.
        val centerPanel = JPanel(CardLayout()).apply {
            add(scrollPane, CARD_RESULTS)
            add(loadingLabel, CARD_LOADING)
            add(emptyStatePanel, CARD_EMPTY)
            add(failedStatePanel, CARD_FAILED)
            add(blockedStatePanel, CARD_BLOCKED)
        }

        add(headerPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun setupEventHandlers() {
        refreshButton.addActionListener {
            onRefreshRequested?.invoke()
        }

        // Selection is dispatched from exactly one place. A click moves the
        // selection in the list UI's own mouse handling, which fires this
        // listener before any mouse listener of ours would run, so a second
        // dispatch from a MouseListener only ever repeated what this one
        // already did. That was invisible while selecting merely reloaded a
        // detail; now that it can prompt to discard a draft, the same click
        // asked the user up to three times.
        configList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !isRestoringSelection) {
                val idx = configList.selectedIndex
                if (idx >= 0 && idx < listModel.size()) {
                    val config = listModel.getElementAt(idx)
                    onConfigurationSelected?.invoke(config)
                }
            }
        }
    }

    /**
     * Builds a centred message card. Platform icons only — no plugin SVG assets
     * are added or replaced for these states (ADR-0036).
     */
    private fun createMessageCard(
        icon: Icon?,
        messageKey: String,
        instructionKey: String
    ): Triple<JPanel, JBLabel, JBLabel> {
        val messageLabel = JBLabel(NacosSearchBundle.message(messageKey)).apply {
            this.icon = icon
            horizontalAlignment = SwingConstants.CENTER
            foreground = JBColor.GRAY
        }
        val instructionLabel = JBLabel(NacosSearchBundle.message(instructionKey)).apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC)
        }
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalGlue())
            add(messageLabel.apply { alignmentX = Component.CENTER_ALIGNMENT })
            add(Box.createVerticalStrut(5))
            add(instructionLabel.apply { alignmentX = Component.CENTER_ALIGNMENT })
            add(Box.createVerticalGlue())
        }
        val panel = JPanel(BorderLayout()).apply {
            add(centerPanel, BorderLayout.CENTER)
        }
        return Triple(panel, messageLabel, instructionLabel)
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Renders [state] with one exhaustive branch. The panel makes no decision
     * about which state applies — that is [ConfigListPresentation]'s job —
     * and does not invent status wording; [ConfigListCopy] resolves structured
     * status / failure inputs against the current bundle.
     */
    fun render(state: ConfigListViewState) {
        viewState = state
        when (state) {
            is ConfigListViewState.Empty -> {
                configurations = emptyList()
                updateList()
                headerLabel.text = NacosSearchBundle.message("config.list.title")
                emptyMessageLabel.icon = null
                emptyMessageLabel.text = NacosSearchBundle.message("config.list.empty")
                emptyInstructionLabel.text = NacosSearchBundle.message("config.list.empty.instruction")
                showCard(CARD_EMPTY)
                // Blank status is blank — never invent empty wording here.
                statusLabel.text = ConfigListCopy.statusLine(state.status, bundleMessage)
            }
            is ConfigListViewState.Loading -> {
                headerLabel.text = NacosSearchBundle.message("config.list.title")
                loadingLabel.text = NacosSearchBundle.message("config.list.loading")
                showCard(CARD_LOADING)
                statusLabel.text = NacosSearchBundle.message("config.list.loading")
            }
            is ConfigListViewState.Results -> {
                configurations = state.configurations
                updateList()
                headerLabel.text =
                    "${NacosSearchBundle.message("config.list.title")}（${state.configurations.size}）"
                showCard(CARD_RESULTS)
                statusLabel.text = ConfigListCopy.statusLine(state.status, bundleMessage)
            }
            is ConfigListViewState.Blocked -> {
                configurations = emptyList()
                updateList()
                headerLabel.text = NacosSearchBundle.message("config.list.title")
                val body = ConfigListCopy.blockedBody(state, bundleMessage)
                blockedMessageLabel.icon = AllIcons.General.Warning
                blockedMessageLabel.text = body
                blockedInstructionLabel.text = when (state.reason) {
                    BlockedReason.REFUSED_ACCESS ->
                        NacosSearchBundle.message("config.list.blocked.access.instruction")
                    BlockedReason.CONFIGURATION_REQUIRED ->
                        NacosSearchBundle.message("config.list.blocked.configuration.instruction")
                }
                showCard(CARD_BLOCKED)
                statusLabel.text = body
            }
            is ConfigListViewState.Failed -> {
                configurations = emptyList()
                updateList()
                headerLabel.text = NacosSearchBundle.message("config.list.title")
                val body = ConfigListCopy.failedBody(state, bundleMessage)
                failedMessageLabel.icon = AllIcons.General.Error
                failedMessageLabel.text = body
                failedInstructionLabel.text =
                    NacosSearchBundle.message("config.list.failed.instruction")
                showCard(CARD_FAILED)
                statusLabel.text = body
            }
        }
    }

    private val bundleMessage: (String, Array<out Any>) -> String = { key, params ->
        if (params.isEmpty()) NacosSearchBundle.message(key)
        else NacosSearchBundle.message(key, *params)
    }

    /**
     * Programmatically selects the given configuration and fires the selection
     * callback. Used by @NacosValue navigation.
     */
    fun selectConfiguration(config: NacosConfiguration) {
        val idx = configurations.indexOfFirst { it.getKey() == config.getKey() }
        if (idx >= 0) {
            configList.selectedIndex = idx
            configList.ensureIndexIsVisible(idx)
        }
    }

    /**
     * Moves the highlight back to [dataId]/[group] without firing the selection
     * callback. Used when the user cancels a guarded action: cancelling must
     * leave both the draft and the current selection exactly as they were, and
     * re-firing the handler would reload the very detail it just protected.
     */
    fun restoreSelection(dataId: String, group: String) {
        val idx = configurations.indexOfFirst { it.dataId == dataId && it.group == group }
        isRestoringSelection = true
        try {
            if (idx >= 0) {
                configList.selectedIndex = idx
                configList.ensureIndexIsVisible(idx)
            } else {
                configList.clearSelection()
            }
        } finally {
            isRestoringSelection = false
        }
    }

    override fun dispose() {
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun updateList() {
        listModel.clear()
        configurations.forEach { listModel.addElement(it) }
    }

    private fun showCard(cardName: String) {
        val centerPanel = getComponent(1) as? JPanel ?: return
        (centerPanel.layout as? CardLayout)?.show(centerPanel, cardName)
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

            val configType = value.declaredFormat().lowercase()
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
            // The dot is otherwise colour-only. A screen reader reaches a list cell
            // through its renderer's accessible context, so the state has to be named
            // here; the matching tooltip is on the list, which is the only component
            // Swing consults for one (a rubber-stamp renderer's is never shown).
            // getAccessibleContext(), not the `accessibleContext` property: inside a
            // JComponent subclass that name resolves to the protected field, which
            // stays null until the getter lazily fills it.
            getAccessibleContext().accessibleName = value.dataId
            getAccessibleContext().accessibleDescription =
                if (isDirty) NacosSearchBundle.message("config.list.dirty") else null

            // Theme-aware fallbacks so rows render correctly under Darcula / New UI / high-contrast.
            // Previously these fell back to Color.WHITE / Color.BLACK / a fixed navy, which painted a
            // white block under dark themes when the list reference was absent.
            if (isSelected) {
                background = list?.selectionBackground ?: UIUtil.getListSelectionBackground()
                dataIdLabel.foreground = list?.selectionForeground ?: UIUtil.getListSelectionForeground()
            } else {
                background = list?.background ?: UIUtil.getListBackground()
                dataIdLabel.foreground = list?.foreground ?: UIUtil.getListForeground()
            }

            return this
        }

        private fun getBadgeForType(type: String): Pair<String, Color> {
            // Distinct per-type hues, wrapped as JBColor with lighter dark-theme variants so the
            // colored badge stays legible against a dark list background and participates in the
            // platform theme model (no longer flat, theme-blind java.awt.Color values).
            return when {
                type.contains("yaml") || type.contains("yml") -> "Y" to JBColor(0xcb6b3f, 0xd98b5f)
                type.contains("json") -> "J" to JBColor(0xcaa53d, 0xd9b85f)
                type.contains("properties") -> "P" to JBColor(0x508cc4, 0x6aa3d6)
                type.contains("xml") -> "X" to JBColor(0x8c6cb4, 0xa88fc4)
                type.contains("text") -> "T" to JBColor(0x7a7e85, 0x9b9ea6)
                else -> "T" to JBColor(0x7a7e85, 0x9b9ea6)
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
    // NacosLanguageListener
    // ------------------------------------------------------------------

    override fun languageChanged() {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            refreshButton.toolTipText = NacosSearchBundle.message("tooltip.config.refresh")
            // Always refresh static labels; the held card may not be the loading
            // one, but its text still lives in the component tree.
            loadingLabel.text = NacosSearchBundle.message("config.list.loading")
            // Re-render the held state so card labels pick up the new bundle
            // without re-deciding which state applies.
            render(viewState)
            revalidate()
            repaint()
        }
    }

    companion object {
        private const val CARD_RESULTS = "results"
        private const val CARD_LOADING = "loading"
        private const val CARD_EMPTY = "empty"
        private const val CARD_FAILED = "failed"
        private const val CARD_BLOCKED = "blocked"
    }
}
