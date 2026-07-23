package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionListener

/**
 * Master-detail settings configurable for multi-server management.
 *
 * Left panel: environment list (add / duplicate / delete / set active).
 * Right panel: detail form for the selected server (URL, credentials,
 * namespace, auth mode, advanced params).
 *
 * Uses a draft model: edits go into an in-memory draft list; Apply/OK
 * commit to NacosSettings, Cancel rolls back.
 */
class NacosConfigurable : Configurable {
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val apiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    private val languageService = ApplicationManager.getApplication().getService(LanguageService::class.java)

    // ---- Draft model ----
    private lateinit var draftServers: MutableList<NacosServerConfig>
    private var draftActiveId: String = ""

   // ---- Left panel (master) ----
   private lateinit var serverList: JBList<NacosServerConfig>
   private lateinit var serverListModel: DefaultListModel<NacosServerConfig>
   private lateinit var footerLabel: JLabel
    private lateinit var deleteServerButton: JButton
    private lateinit var setActiveServerButton: JButton

    // ---- Right panel (detail) form fields ----
    private lateinit var detailTitleLabel: JLabel
    private lateinit var activeBadgeLabel: JLabel
    private lateinit var displayNameField: JBTextField
    private lateinit var serverUrlField: JBTextField
    private lateinit var usernameField: JBTextField
    private lateinit var passwordField: JPasswordField
    private lateinit var namespaceField: JBTextField
    private lateinit var apiPolicyComboBox: JComboBox<NacosApiPolicy>
    private lateinit var authModeComboBox: JComboBox<AuthMode>
    private lateinit var defaultGroupField: JBTextField
    private lateinit var connectionTimeoutSpinner: JSpinner
    private lateinit var crossNamespaceNavigationCheckBox: JCheckBox
    private lateinit var writeIntentCheckBox: JCheckBox

    // Test connection UI
    private lateinit var testConnectionButton: JButton
    private lateinit var testStatusLabel: JLabel

    // Language
    private lateinit var languageComboBox: JComboBox<LanguageService.SupportedLanguage>

    // Selected server id currently displayed in the detail form
    private var selectedServerId: String? = null
    private var loadingForm = false

    private var mainPanel: JComponent? = null
    private val docListener = SimpleDocumentListener { commitDetailFormToDraft() }
    private val listSelectionListener = ListSelectionListener { e ->
        if (!e.valueIsAdjusting) onServerSelected()
    }

    override fun getDisplayName(): String = NacosSearchBundle.message("settings.title")

    override fun createComponent(): JComponent {
        initializeDraft()
        buildComponents()
        mainPanel = buildPanel()
        selectActiveServerInList()
        loadDraftIntoForm()
        // Initialize toolbar button enabled states (Delete / Set Active) to match
        // the design prototype's disabled rules from the first render.
        refreshServerListDecorations()
        updateApplyEnabledState()
        return mainPanel!!
    }

    // ------------------------------------------------------------------
    // Draft model
    // ------------------------------------------------------------------

    private fun initializeDraft() {
        draftServers = settings.cloneServers()
        draftActiveId = settings.activeServerId
        if (draftServers.isEmpty()) {
            val default = NacosServerConfig(id = "default", displayName = "Local")
            draftServers.add(default)
            draftActiveId = "default"
        }
        selectedServerId = draftActiveId
    }

    private fun selectedDraft(): NacosServerConfig? {
        return draftServers.find { it.id == selectedServerId }
    }

    /**
     * Computes the set of server ids that differ from their saved state.
     * Used to show the orange dirty dot per-row per design guide.
     */
    private fun computeDirtyIds(): Set<String> {
        val saved = settings.cloneServers().associateBy { it.id }
        return draftServers.filter { draft ->
            val s = saved[draft.id]
            s == null || s != draft
        }.map { it.id }.toSet()
    }

    private fun commitDetailFormToDraft() {
        if (loadingForm) return
        val server = selectedDraft() ?: return
        server.displayName = displayNameField.text.trim()
        server.serverUrl = serverUrlField.text.trim()
        server.username = usernameField.text.trim()
        server.password = String(passwordField.password)
        server.namespace = namespaceField.text.trim()
        server.apiPolicy = apiPolicyComboBox.selectedItem as NacosApiPolicy
        server.authMode = authModeComboBox.selectedItem as AuthMode
        server.defaultGroup = defaultGroupField.text.trim()
        server.connectionTimeoutMs = connectionTimeoutSpinner.value as Int
        server.allowCrossNamespaceNavigation = crossNamespaceNavigationCheckBox.isSelected
        server.writeIntent = writeIntentCheckBox.isSelected
        // Refresh the list display so name/host changes show immediately
        val idx = serverListModel.indexOf(server)
        if (idx >= 0) {
            serverListModel.setElementAt(server, idx)
        }
        refreshServerListDecorations()
        updateDetailHeader(server)
        updateApplyEnabledState()
    }

    private fun updateFooter() {
        if (!::footerLabel.isInitialized) return
        val activeName = draftServers.find { it.id == draftActiveId }?.displayName?.takeIf { it.isNotBlank() } ?: "—"
        footerLabel.text = NacosSearchBundle.message("settings.servers.stats", draftServers.size, activeName)
    }

   private fun refreshServerListDecorations() {
       if (!::serverList.isInitialized) return
       serverList.cellRenderer = ServerListRenderer(draftActiveId, computeDirtyIds())
       updateFooter()
       serverList.repaint()
        // Toolbar button enabled states (match design prototype):
        //  - Delete disabled when only one server remains
        //  - Set Active disabled when the selected server is already active
        if (::deleteServerButton.isInitialized) {
            deleteServerButton.isEnabled = draftServers.size > 1
        }
        if (::setActiveServerButton.isInitialized) {
            setActiveServerButton.isEnabled = selectedServerId != draftActiveId
        }
   }

    private fun updateDetailHeader(server: NacosServerConfig? = selectedDraft()) {
        if (!::detailTitleLabel.isInitialized || server == null) return
        detailTitleLabel.text = server.displayName.ifBlank { server.serverUrl.ifBlank { NacosSearchBundle.message("settings.server.config") } }
        activeBadgeLabel.isVisible = server.id == draftActiveId
    }

    private fun selectActiveServerInList() {
        val activeIdx = draftServers.indexOfFirst { it.id == draftActiveId }
        when {
            activeIdx >= 0 -> serverList.selectedIndex = activeIdx
            draftServers.isNotEmpty() -> serverList.selectedIndex = 0
        }
    }

    // ------------------------------------------------------------------
    // Component construction
    // ------------------------------------------------------------------

    private fun buildComponents() {
        // --- Left panel list ---
        serverListModel = DefaultListModel()
        draftServers.forEach { serverListModel.addElement(it) }
        serverList = JBList(serverListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = ServerListRenderer(draftActiveId, computeDirtyIds())
            fixedCellHeight = 46
            addListSelectionListener(listSelectionListener)
        }

        // --- Right panel form ---
        displayNameField = JBTextField().apply {
            emptyText.text = "e.g. Local, Dev, Prod"
            document.addDocumentListener(docListener)
        }
        serverUrlField = JBTextField().apply {
            emptyText.text = "http://localhost:8848"
            preferredSize = Dimension(240, preferredSize.height)
            minimumSize = Dimension(120, minimumSize.height)
            document.addDocumentListener(docListener)
        }
        usernameField = JBTextField().apply {
            font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 13)
            document.addDocumentListener(docListener)
        }
        passwordField = JPasswordField().apply {
            document.addDocumentListener(docListener)
        }
        namespaceField = JBTextField().apply {
            emptyText.text = "public"
            font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 13)
            document.addDocumentListener(docListener)
        }
        apiPolicyComboBox = JComboBox(arrayOf(NacosApiPolicy.AUTO, NacosApiPolicy.V1, NacosApiPolicy.V3)).apply {
            addActionListener { commitDetailFormToDraft() }
        }
        authModeComboBox = JComboBox(AuthMode.values()).apply {
            addActionListener { commitDetailFormToDraft() }
        }
        defaultGroupField = JBTextField("DEFAULT_GROUP").apply {
            font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 13)
            document.addDocumentListener(docListener)
        }
        connectionTimeoutSpinner = JSpinner(SpinnerNumberModel(30000, 1000, 120000, 1000)).apply {
            addChangeListener { commitDetailFormToDraft() }
        }
        crossNamespaceNavigationCheckBox = JCheckBox().apply {
            putClientProperty("nacos.automation.id", "nacos.settings.crossNamespaceNavigation")
            toolTipText = NacosSearchBundle.message("settings.server.cross.namespace.navigation.tooltip")
            addActionListener { commitDetailFormToDraft() }
        }
        writeIntentCheckBox = JCheckBox().apply {
            putClientProperty("nacos.automation.id", "nacos.settings.writeIntent")
            toolTipText = NacosSearchBundle.message("settings.server.write.intent.tooltip")
            addActionListener { commitDetailFormToDraft() }
        }

        testConnectionButton = JButton(NacosSearchBundle.message("settings.test.connection")).apply {
            preferredSize = Dimension(132, 32)
            minimumSize = Dimension(118, 32)
            addActionListener { testConnection() }
        }
        testStatusLabel = JLabel("").apply {
            foreground = JBColor.GRAY
        }

        detailTitleLabel = JLabel().apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        activeBadgeLabel = JLabel(NacosSearchBundle.message("settings.active.connection")).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x3574f0, 0x3574f0)
            isVisible = false
        }

        languageComboBox = JComboBox(LanguageService.SupportedLanguage.values()).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is LanguageService.SupportedLanguage) text = value.displayName
                    return this
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun buildPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 0))
        panel.border = JBUI.Borders.empty()

        // --- Splitter: left (master) | right (detail) ---
        val splitter = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true)
        splitter.dividerSize = 1
        splitter.isOneTouchExpandable = false
        splitter.border = null
        splitter.leftComponent = buildMasterPanel()
        splitter.rightComponent = buildDetailPanel()
        splitter.resizeWeight = 0.0
        splitter.setDividerLocation(256)
        SwingUtilities.invokeLater {
            splitter.setDividerLocation(256)
        }

        panel.add(splitter, BorderLayout.CENTER)

        return panel
    }

    private fun buildMasterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8, 8, 4, 4)
        panel.preferredSize = Dimension(256, 400)
        panel.minimumSize = Dimension(240, 280)
        panel.maximumSize = Dimension(280, Int.MAX_VALUE)

        // Header: title + toolbar buttons (single NORTH component)
        val headerPanel = buildMasterToolbar()
        panel.add(headerPanel, BorderLayout.NORTH)

        // List
        panel.add(JBScrollPane(serverList).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 8
        }, BorderLayout.CENTER)

        // Footer stats
        footerLabel = JLabel().apply {
            foreground = JBColor(0x6f737a, 0x9b9ea6)
            font = font.deriveFont(Font.PLAIN, 11f)
        }
        updateFooter()

        // Wrap footer so we can update it when the draft changes.
        // We store a reference via a simple wrapper.
        val footerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
            add(footerLabel, BorderLayout.CENTER)
        }
        panel.add(footerPanel, BorderLayout.SOUTH)

        // Periodically refresh footer + list renderer on draft changes
        serverListModel.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) { updateFooter() }
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) { updateFooter() }
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) { updateFooter() }
        })

        return panel
    }

    private fun buildMasterToolbar(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.emptyTop(4)

        // Title row
        panel.add(JLabel(NacosSearchBundle.message("settings.servers.title")).apply {
            font = font.deriveFont(Font.BOLD, 13f)
        }, BorderLayout.NORTH)

        val buttonBar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2))
        buttonBar.border = JBUI.Borders.emptyTop(6)

        // Use IntelliJ monoline icons per design guide (no emoji/text symbols)
        val addButton = iconButton(AllIcons.General.Add, NacosSearchBundle.message("settings.servers.add"), "nacos.settings.server.add") { addServer() }
        val duplicateButton = iconButton(AllIcons.Actions.Copy, NacosSearchBundle.message("settings.servers.duplicate"), "nacos.settings.server.duplicate") { duplicateServer() }
       val deleteButton = iconButton(AllIcons.General.Remove, NacosSearchBundle.message("settings.servers.delete"), "nacos.settings.server.delete") { deleteServer() }
       val setActiveButton = iconButton(AllIcons.Actions.Checked, NacosSearchBundle.message("settings.servers.set.active"), "nacos.settings.server.setActive") { setActiveServer() }
        deleteServerButton = deleteButton
        setActiveServerButton = setActiveButton

       buttonBar.add(addButton)
        buttonBar.add(duplicateButton)
        buttonBar.add(deleteButton)
        buttonBar.add(setActiveButton)

        panel.add(buttonBar, BorderLayout.CENTER)
        return panel
    }

    private fun iconButton(icon: Icon, tooltip: String, automationId: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            putClientProperty("nacos.automation.id", automationId)
            preferredSize = Dimension(26, 26)
            minimumSize = Dimension(26, 26)
            border = JBUI.Borders.empty()
            isContentAreaFilled = false
            isFocusable = true
            addActionListener { action() }
        }
    }

    private fun buildDetailPanel(): JComponent {
        val scrollPanel = JPanel(GridBagLayout())
        scrollPanel.border = JBUI.Borders.empty(8, 12)

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 4)
            weightx = 1.0
            gridx = 0
            gridy = 0
        }

        fun formLabel(labelKey: String): JLabel {
            return JLabel(NacosSearchBundle.message(labelKey)).apply {
                font = font.deriveFont(Font.PLAIN, 13f)
                foreground = JBColor(0xa8adbd, 0x5a5d63)
                preferredSize = Dimension(118, preferredSize.height)
                minimumSize = Dimension(118, minimumSize.height)
                horizontalAlignment = SwingConstants.RIGHT
            }
        }

        fun addRow(labelKey: String, field: JComponent) {
            gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            gbc.anchor = GridBagConstraints.EAST
            scrollPanel.add(formLabel(labelKey), gbc)

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.anchor = GridBagConstraints.WEST
            val wrapper = JPanel(BorderLayout(8, 0))
            wrapper.add(field, BorderLayout.CENTER)
            scrollPanel.add(wrapper, gbc)
        }

        // Detail title row: selected environment + active badge.
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        scrollPanel.add(JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            border = JBUI.Borders.emptyBottom(10)
            add(detailTitleLabel)
            add(activeBadgeLabel)
        }, gbc)
        gbc.gridwidth = 1

        // Section title
        gbc.gridx = 0; gbc.gridy++; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        scrollPanel.add(JLabel(NacosSearchBundle.message("settings.server.config")).apply {
            font = font.deriveFont(Font.BOLD, 11.5f)
            foreground = JBColor(0xa8adbd, 0x5a5d63)
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }, gbc)
        gbc.gridwidth = 1

        // Fields
        addRow("settings.server.display.name", displayNameField)

        // URL with inline test connection
        gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.EAST
        scrollPanel.add(formLabel("settings.server.url"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.WEST
        val urlRow = JPanel(BorderLayout(8, 0))
        urlRow.add(serverUrlField, BorderLayout.CENTER)
        urlRow.add(testConnectionButton, BorderLayout.EAST)
        scrollPanel.add(urlRow, gbc)

        // Test status
        gbc.gridx = 1; gbc.gridy++; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        scrollPanel.add(testStatusLabel, gbc)

        addRow("settings.server.username", usernameField)
        // Password with eye toggle (show/hide) per design guide
        gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.EAST
        scrollPanel.add(formLabel("settings.server.password"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.WEST
        val pwdRow = JPanel(BorderLayout(4, 0))
        pwdRow.add(passwordField, BorderLayout.CENTER)
        val eyeToggle = JButton(AllIcons.Actions.Preview).apply {
            toolTipText = NacosSearchBundle.message("settings.server.password.show")
            preferredSize = Dimension(28, 28)
            addActionListener {
                val isShown = passwordField.echoChar == '\u0000'
                if (isShown) {
                    passwordField.echoChar = '\u2022'
                    toolTipText = NacosSearchBundle.message("settings.server.password.show")
                    icon = AllIcons.Actions.Preview
                } else {
                    passwordField.echoChar = '\u0000'
                    toolTipText = NacosSearchBundle.message("settings.server.password.hide")
                    icon = AllIcons.Actions.Preview
                }
            }
        }
        pwdRow.add(eyeToggle, BorderLayout.EAST)
        scrollPanel.add(pwdRow, gbc)

        // Namespace field with help text below
        addRow("settings.server.namespace", namespaceField)
        gbc.gridx = 1; gbc.gridy++; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        scrollPanel.add(JTextArea(NacosSearchBundle.message("settings.server.help.namespace")).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 2
            border = JBUI.Borders.empty()
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x6f737a, 0x9b9ea6)
            preferredSize = Dimension(360, preferredSize.height)
            minimumSize = Dimension(120, minimumSize.height)
        }, gbc)
        addRow("settings.server.api.policy", apiPolicyComboBox)
        addRow("settings.server.auth.mode", authModeComboBox)
        addRow("settings.server.write.intent", writeIntentCheckBox)

        // Reset to defaults (keeps the display name).
        gbc.gridx = 1; gbc.gridy++; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.WEST
        scrollPanel.add(JButton(NacosSearchBundle.message("settings.server.reset.to.default")).apply {
            putClientProperty("nacos.automation.id", "nacos.settings.server.resetDefaults")
            preferredSize = Dimension(preferredSize.width, 30)
            minimumSize = Dimension(80, 30)
            addActionListener { resetSelectedServerToDefaults() }
        }, gbc)

        // Spacer
        gbc.gridy++; gbc.weighty = 0.0
        scrollPanel.add(Box.createVerticalStrut(8), gbc)

        // Advanced section (collapsible per design guide)
        gbc.gridx = 0; gbc.gridy++; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        val advToggleButton = JButton(NacosSearchBundle.message("settings.advanced.parameters")).apply {
            putClientProperty("nacos.automation.id", "nacos.settings.advanced.toggle")
            font = font.deriveFont(Font.BOLD, 12f)
            border = JBUI.Borders.empty(8, 0, 4, 0)
            isContentAreaFilled = false
            isBorderPainted = false
            horizontalAlignment = SwingConstants.LEFT
            icon = AllIcons.General.ArrowRight
        }
        scrollPanel.add(advToggleButton, gbc)
        gbc.gridwidth = 1

        // Advanced body container (toggled visible/hidden)
        val advBody = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(2, 0)
        }
        val advGbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 4)
            weightx = 1.0
            gridx = 0
            gridy = 0
        }

        fun addAdvRow(labelKey: String, field: JComponent) {
            advGbc.gridx = 0; advGbc.gridy++; advGbc.weightx = 0.0; advGbc.fill = GridBagConstraints.NONE
            advGbc.anchor = GridBagConstraints.EAST
            advBody.add(formLabel(labelKey), advGbc)

            advGbc.gridx = 1; advGbc.weightx = 1.0; advGbc.fill = GridBagConstraints.HORIZONTAL
            advGbc.anchor = GridBagConstraints.WEST
            val wrapper = JPanel(BorderLayout(4, 0))
            wrapper.add(field, BorderLayout.CENTER)
            advBody.add(wrapper, advGbc)
        }

        addAdvRow("settings.server.default.group", defaultGroupField)

        // Connection timeout with "ms" unit label
        advGbc.gridx = 0; advGbc.gridy++; advGbc.weightx = 0.0; advGbc.fill = GridBagConstraints.NONE
        advGbc.anchor = GridBagConstraints.EAST
        advBody.add(formLabel("settings.server.timeout"), advGbc)
        advGbc.gridx = 1; advGbc.weightx = 1.0; advGbc.fill = GridBagConstraints.HORIZONTAL; advGbc.anchor = GridBagConstraints.WEST
        val timeoutPanel = JPanel(BorderLayout(4, 0))
        timeoutPanel.add(connectionTimeoutSpinner, BorderLayout.WEST)
        timeoutPanel.add(JLabel("ms").apply { foreground = JBColor.GRAY }, BorderLayout.CENTER)
        advBody.add(timeoutPanel, advGbc)

        // Cross-namespace code navigation checkbox
        advGbc.gridx = 0; advGbc.gridy++; advGbc.weightx = 0.0
        advBody.add(formLabel("settings.server.cross.namespace.navigation"), advGbc)
        advGbc.gridx = 1; advGbc.weightx = 1.0; advGbc.fill = GridBagConstraints.HORIZONTAL
        advBody.add(crossNamespaceNavigationCheckBox, advGbc)

        // Default to collapsed
        advBody.isVisible = false

        advToggleButton.addActionListener {
            advBody.isVisible = !advBody.isVisible
            advToggleButton.icon = if (advBody.isVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            scrollPanel.revalidate()
            scrollPanel.repaint()
        }

        // Add advanced body to the scroll panel
        gbc.gridx = 0; gbc.gridy++; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        scrollPanel.add(advBody, gbc)
        gbc.gridwidth = 1

        // Push remaining space to the top
        gbc.gridy++; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        scrollPanel.add(Box.createVerticalGlue(), gbc)

        val scroll = JBScrollPane(scrollPanel)
        scroll.border = JBUI.Borders.empty()
        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        return scroll
    }

    // ------------------------------------------------------------------
    // List actions
    // ------------------------------------------------------------------

    private fun addServer() {
        val newServer = NacosServerConfig.createDefault()
        draftServers.add(newServer)
        serverListModel.addElement(newServer)
        serverList.selectedIndex = serverListModel.size() - 1
        refreshServerListDecorations()
        displayNameField.requestFocusInWindow()
    }

    private fun duplicateServer() {
        val current = selectedDraft() ?: return
        val copy = current.copyConfig()
        draftServers.add(copy)
        serverListModel.addElement(copy)
        serverList.selectedIndex = serverListModel.size() - 1
        refreshServerListDecorations()
    }

    private fun deleteServer() {
        val current = selectedDraft() ?: return
        if (draftServers.size <= 1) {
            Messages.showWarningDialog(
                NacosSearchBundle.message("settings.servers.cannot.delete.last"),
                NacosSearchBundle.message("settings.invalid.title")
            )
            return
        }
        val result = Messages.showYesNoDialog(
            NacosSearchBundle.message("settings.servers.delete.confirm"),
            NacosSearchBundle.message("settings.servers.delete"),
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            val idx = draftServers.indexOf(current)
            draftServers.removeAt(idx)
            serverListModel.removeElementAt(idx)
            if (draftActiveId == current.id) {
                draftActiveId = draftServers.firstOrNull()?.id ?: ""
            }
            val newIdx = minOf(idx, draftServers.size - 1).coerceAtLeast(0)
            serverList.selectedIndex = newIdx
            refreshServerListDecorations()
            updateApplyEnabledState()
        }
    }

    private fun setActiveServer() {
        val current = selectedDraft() ?: return
        draftActiveId = current.id
        refreshServerListDecorations()
        updateDetailHeader(current)
        updateApplyEnabledState()
    }

    private fun onServerSelected() {
        val server = serverList.selectedValue ?: return
        selectedServerId = server.id
        loadDraftIntoForm()
    }

    private fun loadDraftIntoForm() {
        val server = selectedDraft() ?: return
        loadingForm = true
        // Remove listener while programmatically setting fields
        displayNameField.document.removeDocumentListener(docListener)
        serverUrlField.document.removeDocumentListener(docListener)
        usernameField.document.removeDocumentListener(docListener)
        passwordField.document.removeDocumentListener(docListener)
        namespaceField.document.removeDocumentListener(docListener)
        defaultGroupField.document.removeDocumentListener(docListener)

        try {
            displayNameField.text = server.displayName
            serverUrlField.text = server.serverUrl
            usernameField.text = server.username
            passwordField.text = server.password
            namespaceField.text = server.namespace
            apiPolicyComboBox.selectedItem = server.apiPolicy
            authModeComboBox.selectedItem = server.authMode
            defaultGroupField.text = server.defaultGroup
            connectionTimeoutSpinner.value = server.connectionTimeoutMs
            crossNamespaceNavigationCheckBox.isSelected = server.allowCrossNamespaceNavigation
            writeIntentCheckBox.isSelected = server.writeIntent
            updateDetailHeader(server)
        } finally {
            // Re-add listeners
            displayNameField.document.addDocumentListener(docListener)
            serverUrlField.document.addDocumentListener(docListener)
            usernameField.document.addDocumentListener(docListener)
            passwordField.document.addDocumentListener(docListener)
            namespaceField.document.addDocumentListener(docListener)
            defaultGroupField.document.addDocumentListener(docListener)
            loadingForm = false
        }

        // Load language
        languageComboBox.selectedItem = languageService.getCurrentLanguage()
    }

    private fun resetSelectedServerToDefaults() {
        val server = selectedDraft() ?: return
        val keepId = server.id
        val keepName = server.displayName
        val reset = NacosServerConfig.createDefault(keepId).apply {
            displayName = keepName
        }
        val idx = draftServers.indexOfFirst { it.id == keepId }
        if (idx >= 0) {
            draftServers[idx] = reset
            serverListModel.setElementAt(reset, idx)
            loadDraftIntoForm()
            refreshServerListDecorations()
            updateApplyEnabledState()
        }
    }

    // ------------------------------------------------------------------
    // Configurable overrides
    // ------------------------------------------------------------------

    override fun isModified(): Boolean {
        val langChanged = (languageComboBox.selectedItem as LanguageService.SupportedLanguage) != languageService.getCurrentLanguage()

        if (draftActiveId != settings.activeServerId) return true
        if (draftServers.size != settings.servers.size) return true

        for (i in draftServers.indices) {
            val d = draftServers[i]
            val s = settings.servers.getOrNull(i) ?: return true
            if (d.id != s.id || d.displayName != s.displayName ||
                d.serverUrl != s.serverUrl || d.username != s.username ||
                d.password != s.password || d.namespace != s.namespace ||
                d.authMode != s.authMode || d.defaultGroup != s.defaultGroup ||
                d.connectionTimeoutMs != s.connectionTimeoutMs ||
                d.allowCrossNamespaceNavigation != s.allowCrossNamespaceNavigation ||
                d.writeIntent != s.writeIntent
            ) return true
        }
        return langChanged
    }

    override fun apply() {
        // Commit current form state to draft one last time
        commitDetailFormToDraft()

        // Validate all draft servers
        val activeServer = draftServers.find { it.id == draftActiveId } ?: draftServers.first()
        if (!activeServer.isValidUrl()) {
            Messages.showErrorDialog(
                NacosSearchBundle.message("settings.server.url.invalid", activeServer.displayName),
                NacosSearchBundle.message("settings.invalid.title")
            )
            // Select the offending server
            val idx = draftServers.indexOf(activeServer)
            if (idx >= 0) serverList.selectedIndex = idx
            throw java.lang.IllegalStateException("Invalid server URL")
        }

        // Capture connection-affecting fields of the active server BEFORE apply,
        // so we can distinguish a full connection change from a preference-only
        // change (e.g. toggling allowCrossNamespaceNavigation).
        val oldActive = settings.getActiveServer()
        val oldConnectionSig = connectionSignature(oldActive)
        val oldActiveId = settings.activeServerId

        // Apply draft to settings
        settings.applyServers(draftServers, draftActiveId)

        // Apply language
        val selectedLanguage = languageComboBox.selectedItem as LanguageService.SupportedLanguage
        languageService.setLanguage(selectedLanguage.code)

        val newActive = settings.getActiveServer()
        val connectionChanged = oldActiveId != settings.activeServerId ||
            connectionSignature(newActive) != oldConnectionSig

        val publisher = ApplicationManager.getApplication().messageBus
            .syncPublisher(NacosSettingsListener.TOPIC)
        if (connectionChanged) {
            publisher.settingsChanged()
        } else {
            publisher.preferencesChanged()
        }
    }

    override fun reset() {
        initializeDraft()
        // Rebuild list model
        serverListModel.clear()
        draftServers.forEach { serverListModel.addElement(it) }
        refreshServerListDecorations()
        // Select active server
        selectActiveServerInList()
        loadDraftIntoForm()
    }

    private fun updateApplyEnabledState() {
        // Intellij Configurable handles Apply button enable/disable based on isModified()
        // We just trigger a re-check by firing a dummy change
        serverList.repaint()
    }

    // ------------------------------------------------------------------
    // Test connection
    // ------------------------------------------------------------------

    private fun testConnection() {
        commitDetailFormToDraft()
        val server = selectedDraft() ?: return

        if (!server.isValidUrl()) {
            testStatusLabel.text = NacosSearchBundle.message("settings.connection.failed")
            testStatusLabel.foreground = JBColor.RED
            return
        }

        // Diagnose from the unapplied draft only — never mutate persisted settings.
        val snapshot = com.nanyin.nacos.search.services.operations.DiagnosticSnapshot(
            endpoint = server.serverUrl.trim(),
            apiPolicy = server.apiPolicy.name,
            authStrategy = server.authMode.name,
            principal = server.username.trim(),
            secret = server.password,
            namespaceId = server.namespace.trim().ifBlank { "public" }
        )

        testConnectionButton.isEnabled = false
        testStatusLabel.text = NacosSearchBundle.message("settings.test.connecting")
        testStatusLabel.foreground = JBColor.GRAY

        ProgressManager.getInstance().run(object : Task.Backgroundable(null, NacosSearchBundle.message("settings.test.progress"), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = NacosSearchBundle.message("settings.test.connecting")
                val outcome: Result<com.nanyin.nacos.search.services.operations.DiagnosticReport> = try {
                    Result.success(
                        runBlocking(Dispatchers.IO) {
                            apiService.diagnoseConnection(snapshot)
                        }
                    )
                } catch (e: Exception) {
                    Result.failure(e)
                }

                ApplicationManager.getApplication().invokeLater {
                    testConnectionButton.isEnabled = true
                    val report = outcome.getOrNull()
                    if (report != null) {
                        testStatusLabel.text = report.summary
                        testStatusLabel.foreground = if (report.connected) {
                            JBColor(0x5fb865, 0x208a3c)
                        } else {
                            JBColor.RED
                        }
                        testStatusLabel.toolTipText = report.stages.joinToString("\n") { stage ->
                            val status = if (stage.success) "ok" else (stage.sanitizedFailure ?: "failed")
                            "${stage.stage}: $status (${stage.durationMillis}ms)" +
                                (stage.resolvedGeneration?.let { " gen=$it" } ?: "")
                        }
                    } else {
                        val msg = outcome.exceptionOrNull()?.message ?: NacosSearchBundle.message("error.unknown")
                        testStatusLabel.text = NacosSearchBundle.message("settings.test.failed", msg)
                        testStatusLabel.foreground = JBColor.RED
                        testStatusLabel.toolTipText = null
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // List cell renderer
    // ------------------------------------------------------------------

    private class ServerListRenderer(
        private val activeId: String,
        private val dirtyIds: Set<String> = emptySet()
    ) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is NacosServerConfig) {
                val isActive = value.id == activeId
                val isDirty = value.id in dirtyIds
                val name = value.displayName.ifEmpty { value.serverUrl }
                val host = value.serverUrl.replace(Regex("^https?://"), "")

                // Active = blue dot (●) + bold; dirty = orange dot (◆)
                val leadDot = if (isActive) "<font color='#3574f0'>●</font>" else "&nbsp;&nbsp;"
                val dirtyDot = if (isDirty) " <font color='#e3a008'>●</font>" else ""
                val boldOpen = if (isActive) "<b>" else ""
                val boldClose = if (isActive) "</b>" else ""
                val activeSuffix = if (isActive) " &middot; " + NacosSearchBundle.message("settings.active.connection.short") else ""

                this.text = "<html>$leadDot $boldOpen$name$boldClose$dirtyDot<br>" +
                    "<font size='2' color='#a8adbd'>$host$activeSuffix</font></html>"
                this.toolTipText = value.serverUrl
                this.icon = null
                border = JBUI.Borders.empty(4, 8)
            }
            return this
        }
    }

    // ------------------------------------------------------------------
    // Document listener helper
    // ------------------------------------------------------------------

    private class SimpleDocumentListener(private val callback: () -> Unit) : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = callback()
        override fun removeUpdate(e: DocumentEvent?) = callback()
        override fun changedUpdate(e: DocumentEvent?) = callback()
    }

    private fun connectionSignature(server: NacosServerConfig): String =
        "${server.serverUrl}|${server.username}|${server.authMode}|${server.namespace}|${server.connectionTimeoutMs}"
}
