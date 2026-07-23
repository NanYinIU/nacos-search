package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NamespaceFilter
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.listeners.NamespaceChangeListener
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.plaf.basic.BasicButtonUI

/**
 * Panel for namespace selection and management.
 *
 * Replaces the previous plain combo box with a button that opens a searchable
 * popup — necessary because a company-wide Nacos server can expose one or two
 * hundred namespaces, and scrolling a combo becomes impractical.
 */
class NamespacePanel(
    private val project: Project,
    private val namespaceService: NamespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java),
    private val languageService: LanguageService = ApplicationManager.getApplication().getService(LanguageService::class.java),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : JPanel(BorderLayout()), LanguageAwareComponent, NamespaceChangeListener, Disposable {

    private val projectSession: NacosProjectSession? = project.getService(NacosProjectSession::class.java)
    private val settings: NacosSettings = ApplicationManager.getApplication().getService(NacosSettings::class.java)

    // UI Components
    private lateinit var namespaceButton: JButton
    private lateinit var refreshButton: JButton
    private lateinit var loadingLabel: JBLabel
    private lateinit var statusLabel: JBLabel

    // State
    private var isLoading = false
    private var namespaces: List<NamespaceInfo> = emptyList()
    @Volatile
    private var currentNamespace: NamespaceInfo? = null

    // Active popup (kept so refresh can update it live)
    private var activePopup: JBPopup? = null

    /** Local callback: namespace selection belongs to this project, not the app service. */
    var onSelectionChanged: ((NamespaceInfo) -> Unit)? = null

    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    init {
        initializeComponents()
        setupLayout()
        setupEventHandlers()
        loadNamespaces()
    }

    private fun initializeComponents() {
        namespaceButton = JButton().apply {
            putClientProperty("nacos.automation.id", "nacos.toolwindow.nsSwitcher")
            putClientProperty("JButton.buttonType", "toolbar")
            ui = BasicButtonUI()
            isEnabled = false // Disabled until namespaces are loaded
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            border = lightweightControlBorder()
            font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 12)
            horizontalAlignment = SwingConstants.LEFT
            horizontalTextPosition = SwingConstants.TRAILING
            verticalTextPosition = SwingConstants.CENTER
            iconTextGap = 6
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { showSearchPopup() }
        }

        refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = NacosSearchBundle.message("namespace.refresh")
            putClientProperty("JButton.buttonType", "toolbar")
            ui = BasicButtonUI()
            preferredSize = Dimension(28, 24)
            minimumSize = Dimension(28, 24)
            border = JBUI.Borders.empty()
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
        }

        loadingLabel = JBLabel().apply {
            icon = com.intellij.ui.AnimatedIcon.Default.INSTANCE
            isVisible = false
        }

        statusLabel = JBLabel(NacosSearchBundle.message("namespace.loading.namespaces")).apply {
            foreground = JBColor.GRAY
        }
    }

    private fun setupLayout() {
        border = JBUI.Borders.empty()

        val label = JBLabel(NacosSearchBundle.message("namespace.label")).apply {
            font = font.deriveFont(Font.PLAIN, 11.5f)
            foreground = JBColor(0x6f737a, 0x9b9ea6)
            preferredSize = Dimension(FORM_LABEL_WIDTH, 24)
        }

        val row = JPanel(BorderLayout(2, 0)).apply {
            add(namespaceButton.apply {
                minimumSize = Dimension(NAMESPACE_BUTTON_MIN_WIDTH, CONTROL_HEIGHT)
                preferredSize = Dimension(160, CONTROL_HEIGHT)
                maximumSize = Dimension(NAMESPACE_BUTTON_MAX_WIDTH, CONTROL_HEIGHT)
            }, BorderLayout.CENTER)
            add(refreshButton.apply {
                putClientProperty("JButton.buttonType", "toolbar")
                ui = BasicButtonUI()
                preferredSize = Dimension(28, 24)
                minimumSize = Dimension(28, 24)
                border = JBUI.Borders.empty()
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
            }, BorderLayout.EAST)
        }

        add(JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(label, BorderLayout.WEST)
            add(row, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        statusLabel.isVisible = false
    }

    private fun setupEventHandlers() {
        refreshButton.addActionListener {
            loadNamespaces()
        }
    }

    private fun loadNamespaces() {
        if (isLoading) return
        coroutineScope.launch {
            loadNamespacesAndUpdate()
        }
    }

    suspend fun refreshAndWait(): Result<List<NamespaceInfo>> {
        if (isLoading) return Result.success(namespaces)
        return loadNamespacesAndUpdate()
    }

    private suspend fun loadNamespacesAndUpdate(): Result<List<NamespaceInfo>> {
        setLoadingState(true)

        return try {
            val result = projectSession?.let { session ->
                session.seedIfNew(settings.migrationDefaults())
                namespaceService.loadNamespacesAsync(
                    settings.captureOperationContext(session.sessionState.selectedProfileId).getOrNull()
                )
            } ?: namespaceService.loadNamespacesAsync()
            val loaded = result.await()
            loaded.onSuccess { loadedNamespaces ->
                namespaces = loadedNamespaces.ifEmpty { listOf(NamespaceInfo.createPublicNamespace()) }
                updateNamespaceButton()
                setLoadingState(false)
                val status = when {
                    namespaces.size == 1 && namespaces.first().isPublicNamespace() ->
                        NacosSearchBundle.message("namespace.discovery.publicOnly")
                    else -> NacosSearchBundle.message("namespace.loaded.namespaces", namespaces.size)
                }
                updateStatus(status)
            }.onFailure { error ->
                // Keep public namespace selectable and allow manual ID entry.
                namespaces = listOf(NamespaceInfo.createPublicNamespace())
                updateNamespaceButton()
                setLoadingState(false)
                updateStatus(NacosSearchBundle.message("namespace.discovery.manual"))
                showError(NacosSearchBundle.message("namespace.failed.load"), error.message ?: NacosSearchBundle.message("error.unknown"))
            }
            loaded
        } catch (e: Exception) {
            setLoadingState(false)
            updateStatus(NacosSearchBundle.message("namespace.error.load"))
            showError(NacosSearchBundle.message("namespace.error.load"), e.message ?: NacosSearchBundle.message("error.unknown"))
            Result.failure(e)
        }
    }

    private fun updateNamespaceButton() {
        SwingUtilities.invokeLater {
            // Restore this project's selection, otherwise keep the local
            // selection, otherwise default to the first. Never adopt another
            // project's app-wide NamespaceService state.
            projectSession?.seedIfNew(settings.migrationDefaults())
            val projectSelection = projectSession?.sessionState?.namespaceId
                ?.let { selectedId -> namespaces.find { it.namespaceId == selectedId } }
            val toSelect: NamespaceInfo? = projectSelection ?: currentNamespace
            if (toSelect != null) {
                val matching = namespaces.find { it.namespaceId == toSelect.namespaceId }
                if (matching != null) {
                    selectNamespace(matching, notify = currentNamespace == null)
                } else if (namespaces.isNotEmpty()) {
                    selectNamespace(namespaces.first(), notify = currentNamespace == null)
                }
            } else if (namespaces.isNotEmpty()) {
                selectNamespace(namespaces.first(), notify = true)
            }
            namespaceButton.isEnabled = namespaces.isNotEmpty()
            // Keep an open popup's list in sync after a refresh.
            refreshPopupIfShowing()
        }
    }

    /**
     * Updates button label/icon for [ns].
     */
    private fun renderButton(ns: NamespaceInfo?) {
        if (ns == null) {
            namespaceButton.text = NacosSearchBundle.message("namespace.no.namespaces")
            namespaceButton.icon = null
            return
        }
        namespaceButton.text = formatNamespaceDisplay(ns)
        namespaceButton.icon = AllIcons.General.ArrowDown
        namespaceButton.toolTipText = if (ns.namespaceId.isBlank()) {
            "Public namespace (default)"
        } else {
            "Namespace ID: ${ns.namespaceId}"
        }
        updateNamespaceButtonWidth()
    }

    private fun selectNamespace(namespace: NamespaceInfo, notify: Boolean) {
        val changed = currentNamespace?.namespaceId != namespace.namespaceId
        currentNamespace = namespace
        renderButton(namespace)
        if (notify) {
            onNamespaceSelected(namespace)
        } else if (changed) {
            updateStatus(NacosSearchBundle.message("namespace.selected", namespace.namespaceName))
        }
    }

    private fun onNamespaceSelected(namespace: NamespaceInfo) {
        coroutineScope.launch {
            try {
                projectSession?.seedIfNew(settings.migrationDefaults())
                val profileId = projectSession?.sessionState?.selectedProfileId.orEmpty().ifBlank { settings.activeServerId }
                projectSession?.select(profileId, namespace.namespaceId)
                project.getService(com.nanyin.nacos.search.services.ProjectSessionEpochs::class.java)?.bump()
                onSelectionChanged?.invoke(namespace)
                updateStatus(NacosSearchBundle.message("namespace.selected", namespace.namespaceName))
            } catch (e: Exception) {
                updateStatus(NacosSearchBundle.message("namespace.failed.select"))
            }
        }
    }

    /**
     * Opens the searchable namespace popup. The popup filters [namespaces]
 * via [NamespaceFilter]; the public namespace is always pinned on top.
     */
    private fun showSearchPopup() {
        if (GraphicsEnvironment.isHeadless() || ApplicationManager.getApplication().isUnitTestMode) return
        if (namespaces.isEmpty()) return
        activePopup?.cancel()

        val searchField = SearchTextField().apply {
            putClientProperty("nacos.automation.id", "nacos.toolwindow.nsSearch")
            textEditor.emptyText.text = NacosSearchBundle.message("namespace.search.placeholder")
            preferredSize = Dimension(280, 28)
        }

        val listModel = CollectionListModel(NamespaceFilter.filter(namespaces, ""))
        val list = JBList(listModel).apply {
            cellRenderer = NamespaceListRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 12
        }

        // Pre-select the current namespace so the list opens scrolled to it.
        currentNamespace?.let { current ->
            val idx = listModel.items.indexOfFirst { it.namespaceId == current.namespaceId }
            if (idx >= 0) list.selectedIndex = idx
        }

        val emptyLabel = JBLabel().apply {
            isOpaque = false
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(10)
            horizontalAlignment = SwingConstants.CENTER
            isVisible = false
        }

        val scrollPane = com.intellij.ui.components.JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(300, 280)
            minimumSize = Dimension(260, 160)
        }

        val panel = JPanel(BorderLayout()).apply {
 add(searchField, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(emptyLabel, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(4, 4, 0, 4)
        }

        fun applyFilter() {
            val filtered = NamespaceFilter.filter(namespaces, searchField.text)
            listModel.replaceAll(filtered)
            if (filtered.isEmpty()) {
                scrollPane.isVisible = false
                emptyLabel.text = NacosSearchBundle.message("namespace.search.empty", searchField.text.trim())
                emptyLabel.isVisible = true
            } else {
                scrollPane.isVisible = true
                emptyLabel.isVisible = false
            }
            // Keep selection on the current namespace when still present, else first.
            val current = currentNamespace
            val idx = if (current != null) {
                listModel.items.indexOfFirst { it.namespaceId == current.namespaceId }
            } else -1
            list.selectedIndex = if (idx >= 0) idx else 0
            list.ensureIndexIsVisible(list.selectedIndex.coerceAtLeast(0))
        }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })

        // Defer focusing the search field so the popup is on screen first.
        SwingUtilities.invokeLater { searchField.requestFocusInWindow() }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .createPopup() as JBPopup

        fun chooseSelected() {
            val selected = list.selectedValue
            if (selected != null) {
                popup.closeOk(null)
                selectNamespace(selected, notify = true)
            }
        }

        fun chooseSelectedOrManualNamespace() {
            val enteredNamespaceId = searchField.text.trim()
            val matchingNamespace = namespaces.find { it.namespaceId == enteredNamespaceId }
            when {
                matchingNamespace != null -> {
                    popup.closeOk(null)
                    selectNamespace(matchingNamespace, notify = true)
                }
                enteredNamespaceId.isNotEmpty() -> {
                    popup.closeOk(null)
                    selectManualNamespace(enteredNamespaceId)
                }
                else -> chooseSelected()
            }
        }

        searchField.textEditor.addActionListener { chooseSelectedOrManualNamespace() }

       list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) chooseSelected()
            }
       })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    chooseSelected()
                }
            }
        })

        activePopup = popup
        popup.showUnderneathOf(namespaceButton)
    }

    /**
     * Re-applies the current popup filter when namespaces are refreshed.
     */
    private fun refreshPopupIfShowing() {
        val popup = activePopup ?: return
        if (!popup.isVisible || !popup.isDisposed) {
            activePopup = null
        }
        // The popup content is rebuilt from scratch on open, so a mid-refresh
        // update is best handled by closing and letting the user reopen. We keep
        // it simple and just clear the reference; a new open reflects fresh data.
    }

    private fun setLoadingState(loading: Boolean) {
        SwingUtilities.invokeLater {
            isLoading = loading
            loadingLabel.isVisible = loading
            refreshButton.isEnabled = !loading
            // Keep the control enabled whenever at least public (or a manual ID) is available.
            namespaceButton.isEnabled = !loading && namespaces.isNotEmpty()
            if (!loading && namespaces.isNotEmpty()) {
                namespaceButton.toolTipText = NacosSearchBundle.message("namespace.discovery.manual")
            }
        }
    }

    private fun updateStatus(message: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = message
        }
    }

    private fun showError(title: String, message: String) {
        if (GraphicsEnvironment.isHeadless() || ApplicationManager.getApplication().isUnitTestMode) {
            return
        }
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                this,
                message,
                title,
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    /**
     * Get the currently selected namespace
     */
    fun getSelectedNamespace(): NamespaceInfo? {
        return currentNamespace
    }

    /**
     * Set the selected namespace programmatically
     */
    fun setSelectedNamespace(namespace: NamespaceInfo) {
        val matchingNamespace = namespaces.find { it.namespaceId == namespace.namespaceId }
        if (matchingNamespace != null) {
            // notify=true so this project's window receives the selection.
            selectNamespace(matchingNamespace, notify = true)
        }
    }

    /** Selects a Namespace ID typed by the user when anonymous browsing cannot enumerate it. */
    fun selectManualNamespace(namespaceId: String) {
        val normalizedNamespaceId = namespaceId.trim()
        require(normalizedNamespaceId.isNotEmpty()) { "Namespace ID is required" }
        val matchingNamespace = namespaces.find { it.namespaceId == normalizedNamespaceId }
        selectNamespace(
            matchingNamespace ?: NamespaceInfo(normalizedNamespaceId, normalizedNamespaceId),
            notify = true
        )
    }

    override suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?) {
        // Intentionally ignored. This callback represents the legacy
        // application-wide NamespaceService and must not override this
        // project's persisted selection.
    }

    /**
     * Refresh namespaces from server
     */
    fun refresh() {
        loadNamespaces()
    }

    /**
     * Clean up resources
     */
    override fun dispose() {
        activePopup?.cancel()
        activePopup = null
        coroutineScope.cancel()
    }

    /**
     * Formats a namespace for display in the button and list: name, short id
     * and optional config count, in the same style the old combo renderer used.
     */
    private fun formatNamespaceDisplay(ns: NamespaceInfo): String {
        val countPart = if (ns.configCount > 0) " \u00b7 ${ns.configCount}" else ""
        val displayName = ns.namespaceName.ifEmpty { ns.getDisplayName() }
        return if (ns.namespaceId.isBlank()) {
            "$displayName$countPart"
        } else {
            val shortId = if (ns.namespaceId.length > 8) ns.namespaceId.take(8) + "\u2026" else ns.namespaceId
            "$displayName ($shortId)$countPart"
        }
    }

    /**
     * Custom renderer for the namespace list in the popup.
     */
    private inner class NamespaceListRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is NamespaceInfo) {
                text = formatNamespaceDisplay(value)
                font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 12)
                toolTipText = if (value.namespaceId.isBlank()) {
                    "Public namespace (default)"
                } else {
                    "Namespace ID: ${value.namespaceId}"
                }
            }
            return this
        }
    }

    /**
     * Called when the language is changed
     */
    override fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage) {
        refreshUIText()
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
            loadingLabel.text = NacosSearchBundle.message("namespace.loading.namespaces")
            updateStatusText()
            refreshButton.toolTipText = NacosSearchBundle.message("tooltip.namespace.refresh")
            renderButton(currentNamespace)
        }
    }

    /**
     * Update status text based on current state
     */
    private fun updateStatusText() {
        SwingUtilities.invokeLater {
            when {
                isLoading -> {
                    statusLabel.text = NacosSearchBundle.message("namespace.loading.namespaces")
                }
                namespaces.isEmpty() -> {
                    statusLabel.text = NacosSearchBundle.message("namespace.no.namespaces")
                }
                else -> {
                    statusLabel.text = NacosSearchBundle.message("namespace.loaded.namespaces", namespaces.size)
                }
            }
        }
    }

    private fun updateNamespaceButtonWidth() {
        val naturalWidth = namespaceButton.getPreferredSize().width
        val width = naturalWidth.coerceIn(NAMESPACE_BUTTON_MIN_WIDTH, NAMESPACE_BUTTON_MAX_WIDTH)
        namespaceButton.minimumSize = Dimension(NAMESPACE_BUTTON_MIN_WIDTH, CONTROL_HEIGHT)
        namespaceButton.preferredSize = Dimension(width, CONTROL_HEIGHT)
        namespaceButton.maximumSize = Dimension(NAMESPACE_BUTTON_MAX_WIDTH, CONTROL_HEIGHT)
        revalidate()
        repaint()
    }

    private fun lightweightControlBorder() = JBUI.Borders.empty(0, LEADING_ICON_INSET)

    companion object {
        private const val FORM_LABEL_WIDTH = 74
        private const val NAMESPACE_BUTTON_MIN_WIDTH = 140
        private const val NAMESPACE_BUTTON_MAX_WIDTH = 520
        private const val CONTROL_HEIGHT = 26
        private const val LEADING_ICON_INSET = 16
    }
}
