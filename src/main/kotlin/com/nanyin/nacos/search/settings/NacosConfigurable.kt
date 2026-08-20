package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.services.ProjectSessionEpochs
import com.nanyin.nacos.search.services.operations.DraftGuard
import com.nanyin.nacos.search.services.operations.EditSessionService
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.ui.confirmDraftDiscard
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.IdeFocusManager
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
import com.intellij.openapi.application.ModalityState
import com.nanyin.nacos.search.invokeOnEdt
/**
 * Master-detail settings configurable for multi-server management.
 *
 * Left panel: environment list (add / duplicate / delete / set active).
 * Right panel: detail form for the selected server (URL, credentials,
 * namespace, auth mode, advanced params).
 *
 * Intent-only draft model (ADR-0049 / issue #106): the dialog holds dual-write
 * draft rows that map 1:1 to [com.nanyin.nacos.search.models.ProfileIntent]s
 * (no profile revision, access revision, or credential slot identity). Opening,
 * Cancel, and the configurable Reset lifecycle only replace in-memory draft
 * state. Apply / OK is the sole path that publishes through the profile store
 * and the deletion lifecycle.
 *
 * Row dirty indicators and Apply enablement use the store's one change-
 * classification rule ([NacosSettings.classifyDraft]), not a hand-built field
 * comparison. The blue-dot "active" marker follows the **current project's**
 * selected environment when a project context is available and is never written
 * back on open.
 */
class NacosConfigurable @JvmOverloads constructor(
    private val project: Project? = null
) : Configurable {
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val apiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    private val languageService = ApplicationManager.getApplication().getService(LanguageService::class.java)

    // ---- Draft model (in-memory only; never written except via apply()) ----
    private lateinit var draftServers: MutableList<NacosServerConfig>
    private var draftActiveId: String = ""
    /** Active id captured when the draft was loaded — for isModified only. */
    private var openBaselineActiveId: String = ""

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
    private lateinit var usernameLabel: JLabel
    private lateinit var usernameRow: JComponent
    private lateinit var passwordField: JPasswordField
    private lateinit var passwordLabel: JLabel
    private lateinit var passwordRow: JComponent
    private lateinit var secretVisibilityToggle: JButton
    private lateinit var namespaceCombo: SuggestedNamespaceComboBox
    private lateinit var apiPolicyComboBox: JComboBox<NacosApiPolicy>
    private lateinit var authModeComboBox: JComboBox<AuthMode>
    private lateinit var defaultGroupField: JBTextField
    private lateinit var crossNamespaceNavigationCheckBox: JCheckBox
    private lateinit var navigationDetailPrefetchCheckBox: JCheckBox
    private lateinit var writeIntentCheckBox: JCheckBox
    private lateinit var detailFormPanel: JPanel

    // Test connection UI
    private lateinit var testConnectionButton: JButton
    private lateinit var testStatusLabel: JLabel
    /** Discovery list is valid only for this unsaved-form identity. */
    private var discoveredOptionKey: DiscoveryOptionKey? = null
    private var chooserDiscoveryInFlight = false
    /**
     * Isolated Namespace discovery for the chooser. Tests replace this; production
     * uses [NacosApiService.discoverSuggestedNamespaces].
     */
    internal var suggestedNamespaceDiscoverer:
        (suspend (com.nanyin.nacos.search.services.operations.DiagnosticSnapshot) ->
            Result<List<com.nanyin.nacos.search.services.operations.DiscoveredNamespace>>)? = null

    // Language
    private lateinit var languageComboBox: JComboBox<LanguageService.SupportedLanguage>

    // Selected server id currently displayed in the detail form
    private var selectedServerId: String? = null
    private var loadingForm = false
    /** Strategy currently reflected by the form (for switch-effect deltas). */
    private var displayedAuthMode: AuthMode = AuthMode.NACOS_PASSWORD

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
        // Pure read of published profiles + secrets. Never migrates, stages,
        // rewrites the seed, or realigns project selection (issue #106).
        draftServers = settings.loadSettingsDraft().onEach {
            it.authMode = AuthStrategyFormPolicy.normalizeStored(it.authMode, settings.enableTokenAuth)
        }.toMutableList()
        if (draftServers.isEmpty()) {
            val default = settings.defaultDraftRow("default", "Local")
            draftServers.add(default)
            draftActiveId = "default"
            openBaselineActiveId = draftActiveId
            selectedServerId = draftActiveId
            return
        }
        // Blue-dot = environment for the current project. Capture it only in
        // draft state — do not write activeServerId or the migration seed.
        val preferred = resolveSettingsBlueDotId(
            servers = draftServers,
            projectProfileId = resolveProjectProfileId(),
            activeServerId = settings.activeServerId
        )
        draftActiveId = preferred
        openBaselineActiveId = preferred
        selectedServerId = draftActiveId
    }

    /**
     * Profile id selected by the tool window for [project], or the focused /
     * sole open project when this configurable was constructed without one
     * (IDE Preferences → Tools → Nacos Search).
     *
     * Pure read of the session selection — does not call [NacosProjectSession.healSelection]
     * so opening Settings cannot write project state (issue #106).
     */
    private fun resolveProjectProfileId(): String? {
        val context = project
            ?: IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
            ?: ProjectManager.getInstance().openProjects.singleOrNull()
            ?: return null
        if (context.isDisposed) return null
        val session = context.getService(NacosProjectSession::class.java) ?: return null
        return session.sessionState.selectedProfileId.takeIf { it.isNotBlank() }
    }

    /**
     * Store-owned draft classification against the open baseline active id so
     * a blue-dot that differs from the app-wide seed does not look dirty until
     * the user changes the draft (issue #106).
     */
    private fun classifyCurrentDraft(): ProfileIntentClassification {
        if (!::draftServers.isInitialized) {
            return ProfileIntentClassification()
        }
        return settings.classifyDraft(
            draft = draftServers,
            draftActiveId = draftActiveId,
            previousActiveId = openBaselineActiveId
        )
    }

    /**
     * Draft rows that differ from the published state for any reason the store
     * owns.
     */
    fun dirtyDraftProfileIds(): Set<String> {
        if (!::draftServers.isInitialized) return emptySet()
        return classifyCurrentDraft().dirtyProfileIds()
    }

    /**
     * Public seam: profile intents currently held in the draft (no revisions).
     */
    fun draftIntents(): List<com.nanyin.nacos.search.models.ProfileIntent> {
        if (!::draftServers.isInitialized) return emptyList()
        commitDetailFormToDraft()
        return settings.intentsFromDraft(draftServers)
    }

    /** Public seam: draft active profile id (blue-dot). */
    fun draftActiveProfileId(): String = if (::draftServers.isInitialized) draftActiveId else ""

    private fun contextProject(): Project? {
        val candidate = project
            ?: IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
            ?: ProjectManager.getInstance().openProjects.singleOrNull()
        return candidate?.takeUnless { it.isDisposed }
    }

    /**
     * Every non-disposed open project. Write intent and the server list are
     * application-global, so ADR-0027 settings guards must consult every
     * project-scoped edit session — not only the focused one.
     */
    private fun openProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed }

    /**
     * Whether [guard] may proceed for a known [project] / [sessions] pair.
     * Asking never mutates — only an explicit discard does. Fail closed when
     * [ConfirmDiscard] is presented without a usable project/service pair.
     */
    private fun admitDraftGuard(
        project: Project?,
        sessions: EditSessionService?,
        guard: DraftGuard,
        messageKey: String
    ): Boolean {
        if (project == null || sessions == null) {
            return when (guard) {
                DraftGuard.Proceed, DraftGuard.AlreadyEditing -> true
                // Cannot prompt or discard — refuse rather than proceed without
                // honouring ConfirmDiscard / warned abandon (issue #77 review nit).
                is DraftGuard.ConfirmDiscard,
                DraftGuard.RefuseInFlight,
                is DraftGuard.RequireWarnedAbandon -> false
            }
        }
        return com.nanyin.nacos.search.ui.admitDraftGuard(project, sessions, guard, messageKey)
    }

    /**
     * Asks every open project's [EditSessionService] via [guardOf]. Cancel on
     * any project aborts the whole settings action. With no open projects the
     * loop is empty and the action proceeds (nothing to orphan).
     */
    private fun admitAcrossProjects(
        messageKey: String,
        guardOf: (EditSessionService) -> DraftGuard
    ): Boolean {
        for (open in openProjects()) {
            val sessions = open.service<EditSessionService>()
            if (!admitDraftGuard(open, sessions, guardOf(sessions), messageKey)) {
                return false
            }
        }
        return true
    }

    /**
     * Abort Apply after the user already chose "Keep editing" on a discard
     * prompt. [ProcessCanceledException] is silent — [com.intellij.openapi.options.ConfigurationException]
     * would raise a second modal (SingleConfigurableEditor ApplyAction).
     */
    private fun abortApplyAfterDiscardCancel(): Nothing {
        throw ProcessCanceledException()
    }

    /**
     * For every profile whose write intent is being turned off on this Apply,
     * ask every open project's edit session. Cancel aborts Apply without
     * committing settings.
     */
    private fun admitWriteIntentOffGuards() {
        val savedById = settings.cloneServers().associateBy { it.id }
        for (draft in draftServers) {
            val saved = savedById[draft.id] ?: continue
            if (!saved.writeIntent || draft.writeIntent) continue
            if (!admitAcrossProjects("config.detail.draft.discard.write.intent") {
                    it.guardWriteIntentOff(draft.id)
                }
            ) {
                abortApplyAfterDiscardCancel()
            }
        }
    }

    /**
     * For every profile that is about to disappear from the saved server list
     * on this Apply, ask every open project's edit session. Deletion from the
     * settings draft list alone must not discard a configuration draft — only
     * a committed Apply may (issue #77 review).
     */
    private fun admitProfileDeletionGuards() {
        val remainingIds = draftServers.map { it.id }.toSet()
        val deletedIds = settings.cloneServers().map { it.id }.filter { it !in remainingIds }
        for (profileId in deletedIds) {
            if (!admitAcrossProjects("config.detail.draft.discard.profile.delete") {
                    it.guardProfileDeletion(profileId)
                }
            ) {
                abortApplyAfterDiscardCancel()
            }
        }
    }

    /**
     * When Apply realigns the context project's selected environment to the
     * blue-dot [draftActiveId], that is an environment switch for ADR-0027.
     * Guard before commit so cancel keeps both the draft and the selection.
     */
    private fun admitEnvironmentRealignGuards() {
        val ctx = contextProject() ?: return
        val session = ctx.getService(NacosProjectSession::class.java) ?: return
        val currentId = session.sessionState.selectedProfileId
        if (currentId.isBlank() || currentId == draftActiveId) return
        val sessions = ctx.service<EditSessionService>()
        if (!admitDraftGuard(
                ctx,
                sessions,
                sessions.guardEnvironmentSwitch(draftActiveId),
                "config.detail.draft.discard.environment"
            )
        ) {
            abortApplyAfterDiscardCancel()
        }
    }

    private fun selectedDraft(): NacosServerConfig? {
        return draftServers.find { it.id == selectedServerId }
    }

    /**
     * Row dirty dots from the store-owned classification (plus dual-write extras).
     */
    private fun computeDirtyIds(): Set<String> = dirtyDraftProfileIds()

    private fun commitDetailFormToDraft() {
        if (loadingForm) return
        invalidateDiscoveredOptionsIfIdentityChanged()
        val server = selectedDraft() ?: return
        server.displayName = displayNameField.text.trim()
        server.serverUrl = serverUrlField.text.trim()
        var username = usernameField.text.trim()
        var password = String(passwordField.password)
        server.namespace = namespaceCombo.namespaceId()
        server.apiPolicy = apiPolicyComboBox.selectedItem as NacosApiPolicy
        // Strategy is chooser-only — never inferred from credential keystrokes.
        val authMode = AuthStrategyFormPolicy.normalizeStored(
            authModeComboBox.selectedItem as AuthMode? ?: AuthMode.NACOS_PASSWORD,
            settings.enableTokenAuth
        )
        when (authMode) {
            AuthMode.ANONYMOUS -> {
                username = ""
                password = ""
                if (usernameField.text.isNotEmpty() || passwordField.password.isNotEmpty()) {
                    loadingForm = true
                    try {
                        usernameField.text = ""
                        passwordField.text = ""
                    } finally {
                        loadingForm = false
                    }
                }
            }
            AuthMode.BEARER_TOKEN -> {
                // Bearer form has no username; do not persist a leftover principal.
                username = ""
                if (usernameField.text.isNotEmpty()) {
                    loadingForm = true
                    try {
                        usernameField.text = ""
                    } finally {
                        loadingForm = false
                    }
                }
            }
            else -> Unit
        }
        server.username = username
        server.password = password
        server.authMode = authMode
        server.defaultGroup = defaultGroupField.text.trim()
        server.allowCrossNamespaceNavigation = crossNamespaceNavigationCheckBox.isSelected
        server.navigationDetailPrefetchEnabled = navigationDetailPrefetchCheckBox.isSelected
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

    private fun onSuggestedNamespaceCommitted() {
        val before = selectedDraft()?.namespace
        commitDetailFormToDraft()
        if (selectedDraft()?.namespace != before) {
            clearConnectionDiagnosticHeadline()
        }
    }

    private fun clearConnectionDiagnosticHeadline() {
        if (!::testStatusLabel.isInitialized) return
        testStatusLabel.text = ""
        testStatusLabel.toolTipText = null
    }

    private fun applyStrategySwitch(from: AuthMode, to: AuthMode) {
        val effects = AuthStrategyFormPolicy.onStrategySwitch(from, to)
        if (effects.clearUsername) {
            usernameField.text = ""
        }
        if (effects.clearSecret) {
            passwordField.text = ""
        }
        applyFieldVisibility(effects.visibility)
        authModeComboBox.toolTipText = NacosSearchBundle.message(AuthStrategyFormPolicy.tooltipKey(to))
    }

    private fun applyFieldVisibility(visibility: AuthStrategyFormPolicy.FieldVisibility) {
        if (::usernameLabel.isInitialized) {
            usernameLabel.isVisible = visibility.usernameVisible
            usernameRow.isVisible = visibility.usernameVisible
        }
        if (::passwordLabel.isInitialized) {
            passwordLabel.isVisible = visibility.secretVisible
            passwordRow.isVisible = visibility.secretVisible
            when (visibility.secretFieldKind) {
                AuthStrategyFormPolicy.SecretFieldKind.TOKEN -> {
                    passwordLabel.text = NacosSearchBundle.message("settings.server.token")
                    passwordField.toolTipText = NacosSearchBundle.message("settings.server.token.tooltip")
                }
                AuthStrategyFormPolicy.SecretFieldKind.PASSWORD -> {
                    passwordLabel.text = NacosSearchBundle.message("settings.server.password")
                    passwordField.toolTipText = null
                }
            }
            refreshSecretVisibilityToggleTooltip(visibility.secretFieldKind)
        }
        // Always leave fields enabled when present — hide, do not disable.
        usernameField.isEnabled = true
        passwordField.isEnabled = true
        if (::detailFormPanel.isInitialized) {
            detailFormPanel.revalidate()
            detailFormPanel.repaint()
        }
    }

    private fun refreshSecretVisibilityToggleTooltip(
        kind: AuthStrategyFormPolicy.SecretFieldKind =
            AuthStrategyFormPolicy.fieldVisibility(displayedAuthMode).secretFieldKind
    ) {
        if (!::secretVisibilityToggle.isInitialized) return
        val isShown = passwordField.echoChar == '\u0000'
        secretVisibilityToggle.toolTipText = when (kind) {
            AuthStrategyFormPolicy.SecretFieldKind.TOKEN ->
                NacosSearchBundle.message(
                    if (isShown) "settings.server.token.hide" else "settings.server.token.show"
                )
            AuthStrategyFormPolicy.SecretFieldKind.PASSWORD ->
                NacosSearchBundle.message(
                    if (isShown) "settings.server.password.hide" else "settings.server.password.show"
                )
        }
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
        namespaceCombo = SuggestedNamespaceComboBox().apply {
            font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 13)
            onNamespaceCommitted = { onSuggestedNamespaceCommitted() }
            onPopupWillBecomeVisible = { requestSuggestedNamespaceOptions() }
        }
        apiPolicyComboBox = JComboBox(arrayOf(NacosApiPolicy.AUTO, NacosApiPolicy.V1, NacosApiPolicy.V3)).apply {
            putClientProperty("nacos.automation.id", "nacos.settings.apiPolicy")
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is NacosApiPolicy) {
                        text = NacosSearchBundle.message(apiPolicyLabelKey(value))
                        toolTipText = NacosSearchBundle.message(apiPolicyTooltipKey(value))
                    }
                    return this
                }
            }
            addActionListener {
                if (!loadingForm) {
                    selectedItem?.let { item ->
                        toolTipText = NacosSearchBundle.message(apiPolicyTooltipKey(item as NacosApiPolicy))
                    }
                    commitDetailFormToDraft()
                }
            }
        }
        authModeComboBox = JComboBox(AuthStrategyFormPolicy.chooserModes().toTypedArray()).apply {
            putClientProperty("nacos.automation.id", "nacos.settings.authStrategy")
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is AuthMode) {
                        text = NacosSearchBundle.message(AuthStrategyFormPolicy.labelKey(value))
                        toolTipText = NacosSearchBundle.message(AuthStrategyFormPolicy.tooltipKey(value))
                    }
                    return this
                }
            }
            addActionListener {
                if (loadingForm) return@addActionListener
                val mode = AuthStrategyFormPolicy.normalizeStored(
                    selectedItem as AuthMode? ?: return@addActionListener,
                    settings.enableTokenAuth
                )
                applyStrategySwitch(displayedAuthMode, mode)
                displayedAuthMode = mode
                commitDetailFormToDraft()
            }
        }
        defaultGroupField = JBTextField("DEFAULT_GROUP").apply {
            font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 13)
            document.addDocumentListener(docListener)
        }
        crossNamespaceNavigationCheckBox = JCheckBox().apply {
            putClientProperty("nacos.automation.id", "nacos.settings.crossNamespaceNavigation")
            toolTipText = NacosSearchBundle.message("settings.server.cross.namespace.navigation.tooltip")
            addActionListener { commitDetailFormToDraft() }
        }
        navigationDetailPrefetchCheckBox = JCheckBox().apply {
            putClientProperty("nacos.automation.id", "nacos.settings.navigationDetailPrefetch")
            toolTipText = NacosSearchBundle.message("settings.server.navigation.detail.prefetch.tooltip")
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
        invokeOnEdt(ModalityState.defaultModalityState()) {
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
        detailFormPanel = scrollPanel

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

        fun addRow(labelKey: String, field: JComponent): Pair<JLabel, JComponent> {
            gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            gbc.anchor = GridBagConstraints.EAST
            val label = formLabel(labelKey)
            scrollPanel.add(label, gbc)

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.anchor = GridBagConstraints.WEST
            val wrapper = JPanel(BorderLayout(8, 0))
            wrapper.add(field, BorderLayout.CENTER)
            scrollPanel.add(wrapper, gbc)
            return label to wrapper
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

        // Primary form: display name → URL → auth strategy → credentials → namespace.
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

        // Authentication strategy on the primary form (before credentials).
        addRow("settings.server.auth.strategy", authModeComboBox)

        // Conditional credential fields (hide by strategy, not disable).
        val usernamePair = addRow("settings.server.username", usernameField)
        usernameLabel = usernamePair.first
        usernameRow = usernamePair.second

        gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.EAST
        passwordLabel = formLabel("settings.server.password")
        scrollPanel.add(passwordLabel, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.WEST
        val pwdRow = JPanel(BorderLayout(4, 0))
        pwdRow.add(passwordField, BorderLayout.CENTER)
        secretVisibilityToggle = JButton(AllIcons.Actions.Preview).apply {
            preferredSize = Dimension(28, 28)
            toolTipText = NacosSearchBundle.message("settings.server.password.show")
            addActionListener {
                val isShown = passwordField.echoChar == '\u0000'
                if (isShown) {
                    passwordField.echoChar = '\u2022'
                } else {
                    passwordField.echoChar = '\u0000'
                }
                icon = AllIcons.Actions.Preview
                refreshSecretVisibilityToggleTooltip()
            }
        }
        pwdRow.add(secretVisibilityToggle, BorderLayout.EAST)
        scrollPanel.add(pwdRow, gbc)
        passwordRow = pwdRow

        // Namespace field with help text below
        addRow("settings.server.namespace", namespaceCombo)
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

        // Advanced section (collapsible) — no auth strategy here.
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
            putClientProperty("nacos.automation.id", "nacos.settings.advanced.body")
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

        addAdvRow("settings.server.api.version", apiPolicyComboBox)
        addAdvRow("settings.server.write.intent", writeIntentCheckBox)
        addAdvRow("settings.server.default.group", defaultGroupField)

        // Cross-namespace code navigation checkbox
        advGbc.gridx = 0; advGbc.gridy++; advGbc.weightx = 0.0
        advBody.add(formLabel("settings.server.cross.namespace.navigation"), advGbc)
        advGbc.gridx = 1; advGbc.weightx = 1.0; advGbc.fill = GridBagConstraints.HORIZONTAL
        advBody.add(crossNamespaceNavigationCheckBox, advGbc)

        // Navigation detail prefetch checkbox (preference-only, ADR-0042)
        advGbc.gridx = 0; advGbc.gridy++; advGbc.weightx = 0.0
        advBody.add(formLabel("settings.server.navigation.detail.prefetch"), advGbc)
        advGbc.gridx = 1; advGbc.weightx = 1.0; advGbc.fill = GridBagConstraints.HORIZONTAL
        advBody.add(navigationDetailPrefetchCheckBox, advGbc)

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
        val newServer = settings.defaultDraftRow(NacosServerConfig.generateId())
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
        if (result != Messages.YES) return
        // Only stage the deletion in the settings draft list. ADR-0027's
        // profile-deletion guard runs on Apply — discarding the configuration
        // draft here would permanently lose it if the user then Cancelled
        // Settings (P0 has no draft shelf).
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
        defaultGroupField.document.removeDocumentListener(docListener)

        try {
            displayNameField.text = server.displayName
            serverUrlField.text = server.serverUrl
            val authMode = AuthStrategyFormPolicy.normalizeStored(server.authMode, settings.enableTokenAuth)
            server.authMode = authMode
            if (authMode == AuthMode.ANONYMOUS) {
                server.username = ""
                server.password = ""
            } else if (authMode == AuthMode.BEARER_TOKEN) {
                server.username = ""
            }
            usernameField.text = server.username
            passwordField.text = server.password
            val incomingKey = DiscoveryOptionKey(
                endpoint = server.serverUrl.trim(),
                apiPolicy = server.apiPolicy.name,
                authStrategy = authMode.name,
                principal = server.username.trim(),
                secret = server.password
            )
            if (discoveredOptionKey != incomingKey) {
                namespaceCombo.clearDiscoveredOptions()
                discoveredOptionKey = null
            }
            namespaceCombo.setNamespaceId(server.namespace)
            apiPolicyComboBox.selectedItem = server.apiPolicy
            authModeComboBox.selectedItem = authMode
            defaultGroupField.text = server.defaultGroup
            crossNamespaceNavigationCheckBox.isSelected = server.allowCrossNamespaceNavigation
            navigationDetailPrefetchCheckBox.isSelected = server.navigationDetailPrefetchEnabled
            writeIntentCheckBox.isSelected = server.writeIntent
            displayedAuthMode = authMode
            applyFieldVisibility(AuthStrategyFormPolicy.fieldVisibility(authMode))
            authModeComboBox.toolTipText = NacosSearchBundle.message(AuthStrategyFormPolicy.tooltipKey(authMode))
            updateDetailHeader(server)
        } finally {
            // Re-add listeners
            displayNameField.document.addDocumentListener(docListener)
            serverUrlField.document.addDocumentListener(docListener)
            usernameField.document.addDocumentListener(docListener)
            passwordField.document.addDocumentListener(docListener)
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
        // Defaults from the complete persisted shape, in-memory only until Apply.
        val reset = settings.defaultDraftRow(keepId, keepName)
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
        if (!::draftServers.isInitialized) return false
        if (::displayNameField.isInitialized) {
            // Ensure the latest form keystrokes are in the draft before classify.
            commitDetailFormToDraft()
        }
        if (!::languageComboBox.isInitialized) {
            // Component not built yet — only draft classification matters.
            return classifyCurrentDraft().isModified()
        }
        val langChanged =
            (languageComboBox.selectedItem as LanguageService.SupportedLanguage) !=
                languageService.getCurrentLanguage()
        if (langChanged) return true
        // Store-owned semantic comparison is the sole answer for profile rows
        // (issue #106 / #156).
        return classifyCurrentDraft().isModified()
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

        // ADR-0027: settings-path actions that would destroy a draft must
        // prompt before commit. Cancel aborts Apply (silent ProcessCanceledException)
        // with the configuration draft and the settings draft model intact.
        admitWriteIntentOffGuards()
        admitProfileDeletionGuards()
        admitEnvironmentRealignGuards()

        // Sole write path: profile intents through the store (issue #106).
        // The write outcome is the sole classification for connection-vs-
        // preferences notifications — do not re-compare signatures, passwords,
        // or list positions (#103).
        val outcome = settings.applyProfileIntents(
            intents = settings.intentsFromDraft(draftServers),
            newActiveId = draftActiveId,
            dualWriteServers = draftServers
        )

        // Stage / deletion withhold failures: dual-write already kept the
        // published pair. Resync the draft from published state and tell the
        // user — never pretend a preferences-only success (ADR-0035 / #103,
        // ADR-0025 / #105).
        if (outcome.hasStageFailures() || outcome.hasWithheldDeletions()) {
            val messages = buildList {
                if (outcome.hasStageFailures()) {
                    add(
                        NacosSearchBundle.message(
                            "settings.credential.stage.failed",
                            outcome.failedStageProfileIds.joinToString(", ")
                        )
                    )
                }
                if (outcome.hasWithheldDeletions()) {
                    add(
                        NacosSearchBundle.message(
                            "settings.profile.deletion.withheld",
                            outcome.withheldDeletionProfileIds.joinToString(", ")
                        )
                    )
                }
            }
            Messages.showErrorDialog(
                messages.joinToString("\n"),
                NacosSearchBundle.message("settings.invalid.title")
            )
            // Reload draft from what actually published so the form cannot keep
            // showing a deleted environment as applied when it was withheld.
            initializeDraft()
            serverListModel.clear()
            draftServers.forEach { serverListModel.addElement(it) }
            refreshServerListDecorations()
            selectActiveServerInList()
            loadDraftIntoForm()
        } else {
            // Successful publish: re-baseline the open active id so isModified
            // is false for the active selection Apply just committed (#106).
            // Failed Adds stay in the draft and correctly remain dirty.
            openBaselineActiveId = draftActiveId
            refreshServerListDecorations()
        }

        // Align this project's session to the draft blue-dot — project-local
        // only. Do not treat settings.activeServerId as a live selection or
        // rewrite the migration seed (issue #107).
        val requestedActive = draftActiveId.takeIf {
            it.isNotBlank() && it !in outcome.failedStageProfileIds
        }
        contextProject()?.let { ctx ->
            val session = ctx.getService(NacosProjectSession::class.java) ?: return@let
            if (requestedActive != null &&
                session.sessionState.selectedProfileId != requestedActive &&
                settings.getProfile(requestedActive) != null
            ) {
                val suggestedNs = draftServers.find { it.id == requestedActive }?.namespace
                    ?.ifBlank { null }
                    ?: "public"
                session.adoptEnvironment(requestedActive, suggestedNs)
                ctx.getService(ProjectSessionEpochs::class.java)?.bump()
            }
        }

        // Apply language
        val selectedLanguage = languageComboBox.selectedItem as LanguageService.SupportedLanguage
        languageService.setLanguage(selectedLanguage.code)

        val publisher = ApplicationManager.getApplication().messageBus
            .syncPublisher(NacosSettingsListener.TOPIC)
        when {
            outcome.isOperationalChange() ||
                outcome.hasStageFailures() ||
                outcome.hasWithheldDeletions() -> {
                // Stage / deletion withhold failures need connection-facing
                // consumers to re-read the published pair, not a silent
                // preferences-only path.
                publisher.settingsChanged()
            }
            else -> {
                // Preferences, display-only, pure reorder, or no-op after Apply:
                // never treat as connection-level (ADR-0042 / #103).
                publisher.preferencesChanged()
            }
        }

        if ((outcome.hasStageFailures() || outcome.hasWithheldDeletions()) &&
            !outcome.isOperationalChange()
        ) {
            // Mark Apply as failed when the user's change did not land and
            // nothing else operational did — dialog stays open with resynced draft.
            val failed = buildList {
                if (outcome.hasStageFailures()) {
                    add("Credential staging failed for: ${outcome.failedStageProfileIds.joinToString(", ")}")
                }
                if (outcome.hasWithheldDeletions()) {
                    add("Profile deletion withheld for: ${outcome.withheldDeletionProfileIds.joinToString(", ")}")
                }
            }.joinToString("; ")
            throw java.lang.IllegalStateException(failed)
        }
    }

    override fun reset() {
        // Configurable Reset / Cancel: restore the persisted draft in memory
        // only — no settings, credential, seed, or project-selection write.
        initializeDraft()
        if (!::serverListModel.isInitialized) return
        serverListModel.clear()
        draftServers.forEach { serverListModel.addElement(it) }
        refreshServerListDecorations()
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
            testStatusLabel.toolTipText = null
            return
        }

        val authMode = AuthStrategyFormPolicy.normalizeStored(server.authMode, settings.enableTokenAuth)
        if (!AuthStrategyFormPolicy.credentialsReadyForConnectionTest(authMode, server.username, server.password)) {
            // Local gate only — never hit the network with incomplete secrets.
            testStatusLabel.text = NacosSearchBundle.message(
                AuthStrategyFormPolicy.incompleteCredentialsMessageKey(authMode)
            )
            testStatusLabel.foreground = JBColor.RED
            testStatusLabel.toolTipText = null
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
        val testedKey = currentDiscoveryKey()

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

                invokeOnEdt(ModalityState.defaultModalityState()) {
                    testConnectionButton.isEnabled = true
                    if (currentDiscoveryKey() != testedKey) {
                        testStatusLabel.text = ""
                        testStatusLabel.toolTipText = null
                        return@invokeOnEdt
                    }
                    val report = outcome.getOrNull()
                    if (report != null) {
                        acceptDiscoveredNamespaces(report.discoveredNamespaces)
                        val headline = diagnosticHeadline(report)
                        testStatusLabel.text = headline
                        testStatusLabel.foreground = if (report.connected) {
                            JBColor(0x5fb865, 0x208a3c)
                        } else {
                            JBColor.RED
                        }
                        testStatusLabel.toolTipText = diagnosticTooltip(report, headline)
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

    internal fun applyDiscoveredNamespacesIfCurrent(
        namespaces: List<com.nanyin.nacos.search.services.operations.DiscoveredNamespace>,
        testedKey: DiscoveryOptionKey
    ): Boolean {
        if (currentDiscoveryKey() != testedKey) return false
        acceptDiscoveredNamespaces(namespaces)
        return true
    }

    internal fun acceptDiscoveredNamespaces(
        namespaces: List<com.nanyin.nacos.search.services.operations.DiscoveredNamespace>
    ) {
        namespaceCombo.applyDiscovered(namespaces)
        discoveredOptionKey = currentDiscoveryKey()
    }

    internal fun requestSuggestedNamespaceOptions() {
        if (!::namespaceCombo.isInitialized) return
        if (namespaceCombo.discoveredCount() > 0 || chooserDiscoveryInFlight) return
        commitDetailFormToDraft()
        val server = selectedDraft() ?: return
        if (!server.isValidUrl()) return

        val snapshot = com.nanyin.nacos.search.services.operations.DiagnosticSnapshot(
            endpoint = server.serverUrl.trim(),
            apiPolicy = server.apiPolicy.name,
            authStrategy = server.authMode.name,
            principal = server.username.trim(),
            secret = server.password,
            namespaceId = server.namespace.trim().ifBlank { "public" }
        )
        val testedKey = currentDiscoveryKey()
        namespaceCombo.showLoadingPlaceholder()
        discoveredOptionKey = testedKey
        chooserDiscoveryInFlight = true

        fun applyResult(
            result: Result<List<com.nanyin.nacos.search.services.operations.DiscoveredNamespace>>
        ) {
            chooserDiscoveryInFlight = false
            if (currentDiscoveryKey() != testedKey) return
            result.fold(
                onSuccess = { acceptDiscoveredNamespaces(it) },
                onFailure = { namespaceCombo.showFailurePlaceholder() }
            )
        }

        if (ApplicationManager.getApplication().isUnitTestMode) {
            val discoverer = suggestedNamespaceDiscoverer
                ?: { snap -> apiService.discoverSuggestedNamespaces(snap) }
            applyResult(runBlocking { discoverer(snapshot) })
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null,
            NacosSearchBundle.message("settings.namespace.chooser.loading"),
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = NacosSearchBundle.message("settings.namespace.chooser.loading")
                val outcome = try {
                    runBlocking(Dispatchers.IO) {
                        val discoverer = suggestedNamespaceDiscoverer
                            ?: { snap -> apiService.discoverSuggestedNamespaces(snap) }
                        discoverer(snapshot)
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
                invokeOnEdt(ModalityState.defaultModalityState()) {
                    applyResult(outcome)
                }
            }
        })
    }

    private fun invalidateDiscoveredOptionsIfIdentityChanged() {
        if (!::namespaceCombo.isInitialized) return
        val current = currentDiscoveryKey()
        if (discoveredOptionKey != null && discoveredOptionKey != current) {
            namespaceCombo.clearDiscoveredOptions()
            discoveredOptionKey = null
        }
    }

    private fun currentDiscoveryKey(): DiscoveryOptionKey {
        val authMode = AuthStrategyFormPolicy.normalizeStored(
            authModeComboBox.selectedItem as AuthMode? ?: AuthMode.NACOS_PASSWORD,
            settings.enableTokenAuth
        )
        return DiscoveryOptionKey(
            endpoint = serverUrlField.text.trim(),
            apiPolicy = (apiPolicyComboBox.selectedItem as NacosApiPolicy? ?: NacosApiPolicy.AUTO).name,
            authStrategy = authMode.name,
            principal = usernameField.text.trim(),
            secret = String(passwordField.password)
        )
    }

    internal fun diagnosticHeadline(
        report: com.nanyin.nacos.search.services.operations.DiagnosticReport
    ): String {
        val summary = report.summary
        return when {
            summary.startsWith("Permission denied for namespace") ->
                NacosSearchBundle.message(
                    "settings.test.namespace.permission",
                    report.configuredNamespaceId
                )
            summary == "Authentication failed" ->
                NacosSearchBundle.message("settings.test.authentication.failed")
            summary == "Connected. Manual namespace. Discovery unavailable." ->
                NacosSearchBundle.message("settings.test.connected.manual.namespace")
            summary == "Connected" ->
                NacosSearchBundle.message("settings.test.connected")
            !report.connected ->
                NacosSearchBundle.message("settings.connection.failed")
            else -> summary
        }
    }

    internal fun diagnosticTooltip(
        report: com.nanyin.nacos.search.services.operations.DiagnosticReport,
        headline: String
    ): String =
        if (report.summary.startsWith("Permission denied for namespace")) {
            headline
        } else {
            report.stages.joinToString("\n") { stage ->
                val status = if (stage.success) "ok" else (stage.sanitizedFailure ?: "failed")
                "${stage.stage}: $status (${stage.durationMillis}ms)" +
                    (stage.resolvedGeneration?.let { " gen=$it" } ?: "")
            }
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

    private fun apiPolicyLabelKey(policy: NacosApiPolicy): String = when (policy) {
        NacosApiPolicy.AUTO -> "settings.api.generation.auto.label"
        NacosApiPolicy.V1 -> "settings.api.generation.v1.label"
        NacosApiPolicy.V3 -> "settings.api.generation.v3.label"
    }

    private fun apiPolicyTooltipKey(policy: NacosApiPolicy): String = when (policy) {
        NacosApiPolicy.AUTO -> "settings.api.generation.auto.tooltip"
        NacosApiPolicy.V1 -> "settings.api.generation.v1.tooltip"
        NacosApiPolicy.V3 -> "settings.api.generation.v3.tooltip"
    }
}

/**
 * Blue-dot id for the Settings server list: prefer the project tool-window
 * selection when it still exists in [servers], otherwise the persisted
 * app-wide active server, otherwise the first row.
 */
internal fun resolveSettingsBlueDotId(
    servers: List<NacosServerConfig>,
    projectProfileId: String?,
    activeServerId: String
): String {
    if (servers.isEmpty()) return ""
    return projectProfileId?.takeIf { id -> servers.any { it.id == id } }
        ?: activeServerId.takeIf { id -> servers.any { it.id == id } }
        ?: servers.first().id
}
