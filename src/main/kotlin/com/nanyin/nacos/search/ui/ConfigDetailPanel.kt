package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.services.NacosLanguageListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.nanyin.nacos.search.NacosIcons
import com.nanyin.nacos.search.psi.ConfigKeyExtractor
import com.nanyin.nacos.search.psi.NacosCodeContextExtractor
import com.nanyin.nacos.search.psi.NacosConfigKeyElement
import com.nanyin.nacos.search.psi.NacosConfigKeyReferenceSearcher
import com.nanyin.nacos.search.psi.NacosUsageChoiceItem
import com.nanyin.nacos.search.psi.NacosUsagePresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.Disposable
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.NavigationIndexRefreshService
import com.nanyin.nacos.search.services.currentSessionEpoch
import com.nanyin.nacos.search.services.operations.ConfigurationCoordinate
import com.nanyin.nacos.search.services.operations.ObservedDetailRecorder
import com.nanyin.nacos.search.services.operations.DraftDiscardListener
import com.nanyin.nacos.search.services.operations.EditSessionService
import com.nanyin.nacos.search.services.operations.PublishState
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.captureSelectedAccessIdentity
import com.nanyin.nacos.search.settings.operationNamespaceIdFor
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.swing.*
import kotlin.concurrent.withLock
import com.nanyin.nacos.search.invokeOnEdt
/**
 * Renders a closed [DetailViewState] set with one exhaustive branch and holds
 * no decision (issue #81). Mapping from load / edit / publish outcomes lives in
 * [DetailController] / [DetailPresentation]; this panel receives its
 * collaborators in the constructor rather than locating them.
 */
class ConfigDetailPanel internal constructor(
    private val project: Project,
    private val apiService: NacosApiService,
    private val cacheService: CacheService,
    private val settings: NacosSettings,
    private val editSessions: EditSessionService,
    private val navigationRefresh: NavigationIndexRefreshService,
    private val observedDetailRecorder: ObservedDetailRecorder,
    private val captureOperationContext: suspend () -> NacosOperationContext?
) : JPanel(BorderLayout()), Disposable, NacosLanguageListener {
    constructor(project: Project) : this(
        project = project,
        apiService = ApplicationManager.getApplication().getService(NacosApiService::class.java),
        cacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java),
        editSessions = project.getService(EditSessionService::class.java),
        navigationRefresh = ApplicationManager.getApplication()
            .getService(NavigationIndexRefreshService::class.java),
        observedDetailRecorder = ObservedDetailRecorder(
            ApplicationManager.getApplication().getService(CacheService::class.java)
        ),
        captureOperationContext = {
            withContext(Dispatchers.IO) {
                project.getService(NacosProjectSession::class.java)?.let { session ->
                    val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
                    // Do not heal: deleted explicit profiles must fail closed (ADR 0025).
                    settings.captureOperationContext(session.sessionState.selectedProfileId).getOrNull()
                }
            }
        }
    )

    companion object {
        private const val DETAIL_HORIZONTAL_INSET = 10
    }

    /** Namespace for history and publish — the one derivation, shared with the window. */
    private fun operationNamespaceId(config: NacosConfiguration): String =
        project.operationNamespaceIdFor(config)

    // UI Components
    private lateinit var metadataPanel: JPanel
    private lateinit var contentPanel: JPanel
    private lateinit var loadingLabel: JBLabel
    private lateinit var emptyStatePanel: JPanel
    private lateinit var errorPanel: JPanel
    // Read-utility actions (Refresh / Copy / History) live in a native ActionToolbar
    // instead of hand-rolled icon JButtons. Kept as fields so their enabled state can
    // be refreshed in [updateActionsEnabled]; the toolbar component is [detailToolbar].
    private val refreshAction = RefreshDetailAction()
    private val copyAction = CopyDetailAction()
    private val historyAction = HistoryDetailAction()
    private lateinit var detailToolbar: com.intellij.openapi.actionSystem.ActionToolbar
    private lateinit var saveButton: JButton
    private lateinit var editButton: JButton
    private lateinit var revertButton: JButton
    private lateinit var formatTagLabel: JBLabel
    private lateinit var dirtyLabel: JBLabel
    private lateinit var freshnessLabel: JBLabel

    /**
     * The project's draft is injected via the constructor — the panel renders
     * it and reports what the user types; it does not own it (ADR-0046).
     */

    /**
     * Unregisters the external-discard listener. Settings (and other non-panel
     * sites) can discard without going through [exitEditMode]; this is how the
     * panel hears about that and leaves edit mode (issue #77 review).
     */
    private var discardListenerHandle: AutoCloseable? = null

    // Editor status bar components (UTF-8 · LF · pos · chars · md5)
    private lateinit var statusBar: JPanel
    private lateinit var statusEncodingLabel: JBLabel
    private lateinit var statusLineEndingLabel: JBLabel
    private lateinit var statusPositionLabel: JBLabel
    private lateinit var statusCharsLabel: JBLabel
    private lateinit var statusMd5Label: JBLabel
    
    // Editor components with thread-safe state management
    private var editor: EditorEx? = null
    private var editorPanel: JPanel? = null
    
    // Thread safety for editor operations
    private enum class EditorState {
        NONE,
        CREATING,
        ACTIVE,
        DISPOSING,
        DISPOSED
    }

    private data class PendingNavigation(
        val configKey: String,
        val lineIndex: Int
    )
    
    private val editorState = AtomicReference(EditorState.NONE)
    private val editorLock = ReentrantLock()
    private val logger = thisLogger()
    
    // Metadata labels
    private lateinit var dataIdLabel: JTextField
    private lateinit var groupLabel: JTextField
    private lateinit var namespaceLabel: JTextField
    private lateinit var typeLabel: JTextField
    private lateinit var sizeLabel: JTextField

    // Inline metadata label (Group · namespace · type · updated) per design guide §3.3
    private lateinit var inlineMetaLabel: JBLabel
    
    // State
    private var currentConfiguration: NacosConfiguration? = null
    /**
     * The configuration coordinate the user selected, which is what an
     * asynchronous result is judged against — not [currentConfiguration], which
     * is replaced by each loaded detail and whose namespace a server may omit
     * from the response body.
     */
    private var selectedCoordinate: PresentedCoordinate? = null
    private var pendingNavigation: PendingNavigation? = null
    private var isLoading = false
    /** What the dirty indicators currently show, so they are only repainted on a change. */
    private var renderedDirty = false
    /** Last closed view state rendered — language change re-derives copy from it. */
    private var viewState: DetailViewState = DetailViewState.Empty
    /** Cached body kept visible across a deep-stale refresh. */
    private var keptCachedBody: DetailViewState.Body? = null

    /**
     * The one judgement of whether an asynchronous result may still update this
     * panel (ADR-0047). Shared with [detailController] so load and editor paint
     * raise the same mark.
     */
    private val presentation = PresentationGate(
        currentSessionEpoch = { project.currentSessionEpoch() },
        currentCoordinate = { selectedCoordinate }
    )

    private val detailController = DetailController(
        gateway = apiService.operationGateway(),
        presentation = presentation,
        onAuthoritativeNotFound = { identity, namespaceId, dataId, group, observation ->
            observedDetailRecorder.recordMissing(identity, namespaceId, dataId, group, observation)
        }
    )

    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentLoadingJob: Job? = null

    // Callback fired when dirty state changes (so the window can update the list row dot)
    var onDirtyStateChanged: ((NacosConfiguration?, Boolean) -> Unit)? = null
    
    init {
        initializeComponents()
        setupLayout()
        setupEventHandlers()
        // Keep the in-memory editor's color scheme in sync with the active IDE
        // theme, so switching dark/light updates the detail panel too.
        val busConnection = ApplicationManager.getApplication().messageBus.connect(this)
        busConnection.subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC, com.intellij.ide.ui.LafManagerListener {
            editor?.let { applyThemeScheme(it) }
        })
        busConnection.subscribe(NacosLanguageListener.TOPIC, this)
        // When Settings (or any other site) discards the project draft, leave
        // edit mode without the panel itself owning that discard.
        discardListenerHandle = editSessions.addDiscardListener(DraftDiscardListener {
            invokeOnEdt(ModalityState.any()) {
                if (!project.isDisposed) applyViewModeUi()
            }
        })
        Disposer.register(this) {
            discardListenerHandle?.close()
            discardListenerHandle = null
        }
        showEmptyState()
    }
    
    private fun initializeComponents() {
        // Metadata panel components
        // Keep a JTextField so the dataId can be selected/copied, but use a
        // non-UIResource empty border (a null border gets replaced by the
        // Darcula text border on UI (re)install, which reintroduces a left
        // inset) plus zero margin, so the text aligns flush-left with the
        // metadata JBLabel below.
        dataIdLabel = JTextField()
        dataIdLabel.isEditable = false
        dataIdLabel.border = JBUI.Borders.empty()
        dataIdLabel.margin = Insets(0, 0, 0, 0)
        dataIdLabel.isOpaque = false
        dataIdLabel.putClientProperty("JComponent.outline", "none")

        groupLabel = JTextField()
        groupLabel.isEditable = false
        groupLabel.border = null
        groupLabel.isOpaque = false
        groupLabel.putClientProperty("JComponent.outline", "none")
//        groupLabel.isEditable = false
        namespaceLabel = JTextField()
        namespaceLabel.isEditable = false
        namespaceLabel.border = null
        namespaceLabel.isOpaque = false
        namespaceLabel.putClientProperty("JComponent.outline", "none")
//        namespaceLabel.isEditable = false
        typeLabel = JTextField()
        typeLabel.isEditable = false
        typeLabel.border = null
        typeLabel.isOpaque = false
        typeLabel.putClientProperty("JComponent.outline", "none")
//        typeLabel.isEditable = false
        sizeLabel = JTextField()
//        sizeLabel.isEditable = false

        
        metadataPanel = createMetadataPanel()
        
        // Content panel
        contentPanel = JPanel(CardLayout())
        
        // Loading state
        loadingLabel = JBLabel().apply {
            icon = AnimatedIcon.Default.INSTANCE
            text = NacosSearchBundle.message("config.detail.loading")
            horizontalAlignment = SwingConstants.CENTER
        }
        
        // Empty state
        emptyStatePanel = createEmptyStatePanel()
        
       // Error state
       errorPanel = createErrorPanel()
      
       // Edit lifecycle buttons keep text labels — they are the clear commit
        // commands and adapt to the current mode (see enterEditMode / exitEditMode):
        //   view mode  -> [Edit]          (primary CTA to start editing)
        //   edit mode  -> [Revert] [Save] (Save is the accent primary)
        // Save & Revert are hidden until the editor enters edit mode.
        saveButton = JButton(NacosSearchBundle.message("config.detail.action.save.publish")).apply {
            toolTipText = NacosSearchBundle.message("config.detail.save")
            putClientProperty("JButton.buttonType", "primary")
            preferredSize = Dimension(72, 26)
            minimumSize = Dimension(60, 26)
            isEnabled = false
            isVisible = false
        }

        // Edit button — primary CTA in view mode; hidden while editing.
        editButton = JButton(NacosSearchBundle.message("config.detail.action.edit")).apply {
            toolTipText = NacosSearchBundle.message("config.detail.action.edit")
            putClientProperty("JButton.buttonType", "primary")
            preferredSize = Dimension(72, 26)
            minimumSize = Dimension(60, 26)
            isEnabled = false
        }

        // Revert button — discards unsaved edits; only shown in edit mode.
        revertButton = JButton(NacosSearchBundle.message("config.detail.action.revert")).apply {
            toolTipText = NacosSearchBundle.message("config.detail.action.revert")
            preferredSize = Dimension(72, 26)
            minimumSize = Dimension(60, 26)
            isEnabled = false
            isVisible = false
        }

        // Format tag label
       formatTagLabel = JBLabel("").apply {
           foreground = JBColor.GRAY
           font = font.deriveFont(Font.PLAIN, 11f)
       }

        // Dirty pill
        dirtyLabel = JBLabel(NacosSearchBundle.message("config.detail.modified")).apply {
            foreground = JBColor(0xe3a008, 0xb8860b)
            font = font.deriveFont(Font.PLAIN, 10f)
            isVisible = false
        }
        freshnessLabel = JBLabel().apply {
            foreground = JBColor(0xe08800, 0xf2c55c)
            font = font.deriveFont(Font.PLAIN, 10f)
            isVisible = false
        }

        // Editor status bar labels
        statusEncodingLabel = JBLabel(NacosSearchBundle.message("config.detail.status.encoding")).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x6f737a, 0x9b9ea6)
        }
        statusLineEndingLabel = JBLabel(NacosSearchBundle.message("config.detail.status.line.ending")).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x6f737a, 0x9b9ea6)
        }
        statusPositionLabel = JBLabel(NacosSearchBundle.message("config.detail.status.position", 1, 1)).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x6f737a, 0x9b9ea6)
        }
        statusCharsLabel = JBLabel(NacosSearchBundle.message("config.detail.status.chars.format", 0)).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x6f737a, 0x9b9ea6)
        }
       statusMd5Label = JBLabel("—").apply {
           font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 11)
           foreground = JBColor(0x6f737a, 0x9b9ea6)
       }
   }
   
      private fun setupLayout() {
        border = JBUI.Borders.empty()

        // ===== Header: dataId title (bold) + dirty pill + metadata row =====
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, DETAIL_HORIZONTAL_INSET, 5, DETAIL_HORIZONTAL_INSET)

            // Title row: dataId (bold 13px) + dirty pill on right
            val titleRow = JPanel(BorderLayout()).apply {
                add(dataIdLabel.apply {
                    font = font.deriveFont(Font.BOLD, 13f)
                    border = JBUI.Borders.empty()
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    add(freshnessLabel)
                    add(dirtyLabel)
                }, BorderLayout.EAST)
            }
            add(titleRow)

            // Metadata row (group, namespace, type, size) - compact inline
            add(metadataPanel)
        }

        // ===== Action bar: format tag (left) | utility icons + edit lifecycle (right) =====
        val actionBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, DETAIL_HORIZONTAL_INSET)
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(formatTagLabel)
            }
            // Build the native ActionToolbar for the read utilities (Refresh/Copy/History).
            // Horizontal layout; the toolbar's component replaces the three icon JButtons.
            val actionGroup = DefaultActionGroup().apply {
                add(refreshAction)
                add(copyAction)
                add(historyAction)
            }
            detailToolbar = ActionManager.getInstance().createActionToolbar(
                "NacosConfigDetail",
                actionGroup,
                true // horizontal
            )
            detailToolbar.targetComponent = this
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(detailToolbar.component)
                // Gap separates the always-on utilities from the commit group
                add(Box.createHorizontalStrut(8))
                // Edit lifecycle: [Edit] in view mode, [Revert] [Save] in edit mode.
                // Save (primary) sits rightmost; only the relevant buttons are visible.
                add(editButton)
                add(revertButton)
                add(saveButton)
            }
            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }

        // Add cards to content panel
        contentPanel.add(emptyStatePanel, "empty")
        contentPanel.add(loadingLabel, "loading")
        contentPanel.add(errorPanel, "error")

        add(headerPanel, BorderLayout.NORTH)

        // Use a vertical container for action bar + content so the action bar sits below header
        val centerContainer = JPanel(BorderLayout())
        centerContainer.add(actionBar, BorderLayout.NORTH)
        centerContainer.add(contentPanel, BorderLayout.CENTER)
        add(centerContainer, BorderLayout.CENTER)

        // ===== Editor status bar (bottom): UTF-8 · LF · pos | chars · md5 =====
        statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 12)
            val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 14, 0)).apply {
                add(statusEncodingLabel)
                add(statusLineEndingLabel)
                add(statusPositionLabel)
            }
            val rightGroup = JPanel(FlowLayout(FlowLayout.RIGHT, 14, 0)).apply {
                add(statusCharsLabel)
                add(statusMd5Label)
            }
            add(leftGroup, BorderLayout.WEST)
            add(rightGroup, BorderLayout.EAST)
        }
        // Add a top border to the status bar for visual separation
        statusBar.border = JBUI.Borders.empty(2, 12, 2, 12)
        add(statusBar, BorderLayout.SOUTH)
    }
    
private fun setupEventHandlers() {
        saveButton.addActionListener {
            saveConfiguration()
        }

       editButton.addActionListener {
            enterEditMode()
       }

       revertButton.addActionListener {
           revertEdits()
      }

  }


 /**
  * Reverts unsaved edits by discarding the draft and reloading from the server.
  * This is the user's explicit discard, so it needs no prompt.
  */
  private fun revertEdits() {
      currentConfiguration?.let { config ->
          exitEditMode()
          loadConfigurationContent(config, forceRefresh = true)
          revertButton.isEnabled = false
          saveButton.isEnabled = false
          updateDirtyUI(false)
      }
  }
   /**
    * Enters edit mode: asks the project's [EditSessionService] to start a
    * session, and makes the editor writable only if it did. The write-intent
    * check lives there and is the same one the save path consults, so an edit
    * that could never be published is explained here instead of failing later.
    */
  private fun enterEditMode() {
      val config = currentConfiguration ?: return
      val editor = this.editor ?: return
      coroutineScope.launch {
          val start = editSessions.beginEdit(
              configuration = config,
              namespaceId = operationNamespaceId(config),
              baselineContent = editor.document.text
          )
          withContext(Dispatchers.Main) {
              // Always go through the closed set — even Started is a Body with
              // editing=true so render owns the edit-mode chrome.
              when (val state = DetailPresentation.fromEditStart(start)) {
                  is DetailViewState.Body -> {
                      // Keep the live editor; recreating it would drop undo/caret.
                      currentConfiguration = state.configuration
                      editor.document.setReadOnly(false)
                      editButton.isVisible = false
                      revertButton.isVisible = true
                      saveButton.isVisible = true
                      viewState = state
                      checkDirtyState(editor.document.text)
                  }
                  else -> render(state)
              }
          }
      }
  }

    /**
     * Restores the read-only view mode: locks the editor and re-shows the Edit
     * button. The draft ends here — a caller that must not lose it asks
     * [EditSessionService.guardRetarget] or `guardDestroy` first.
     *
     * When the session is discarded elsewhere (Settings write-intent / profile
     * delete / project close), [DraftDiscardListener] drives [applyViewModeUi]
     * without going through this method — do not require the panel to own every
     * discard.
     */
    private fun exitEditMode() {
        editSessions.discardDraft()
        applyViewModeUi()
    }

    /**
     * Swing-only half of leaving edit mode. Safe when the session is already
     * null (external discard notified us) and idempotent when [exitEditMode]
     * both discards and applies UI.
     */
    private fun applyViewModeUi() {
        editor?.document?.setReadOnly(true)
        // Restore view mode: re-show Edit and hide the Save/Revert commit buttons
        if (::editButton.isInitialized) {
            editButton.isVisible = true
            editButton.text = NacosSearchBundle.message("config.detail.action.edit")
        }
        if (::saveButton.isInitialized) {
            saveButton.isVisible = false
            saveButton.isEnabled = false
            saveButton.text = NacosSearchBundle.message("config.detail.action.save.publish")
        }
        if (::revertButton.isInitialized) {
            revertButton.isVisible = false
            revertButton.isEnabled = false
        }
        if (renderedDirty) {
            updateDirtyUI(false)
        }
    }

    /**
     * Reports what the user typed to the project's draft and renders whatever
     * dirty state that produces. The panel keeps no baseline of its own: the
     * session it is rendering is held outside the tool window (ADR-0046).
     */
    private fun checkDirtyState(currentText: String) {
        editSessions.updateDraft(currentText)
        val newDirty = editSessions.isDirty()
        if (newDirty != renderedDirty) {
            updateDirtyUI(newDirty)
        }
    }

    /**
     * Updates all dirty-state indicators: title asterisk, pill, and notifies
     * the window so the list row dot can update.
     */
    private fun updateDirtyUI(dirty: Boolean) {
        renderedDirty = dirty
        dirtyLabel.isVisible = dirty
        saveButton.isEnabled = dirty
        revertButton.isEnabled = dirty
        // Refresh is disabled while a draft is dirty, so its enablement has to
        // be re-evaluated here rather than waiting for the toolbar's own pass.
        updateActionsEnabled()
        // Title asterisk: append/remove '*' from dataId label text
        val config = currentConfiguration ?: return
        val baseId = config.dataId
        invokeOnEdt(ModalityState.defaultModalityState()) {
            dataIdLabel.text = if (dirty && !baseId.endsWith("*")) "$baseId *" else baseId
        }
        // Notify window for list-row dot update
        onDirtyStateChanged?.invoke(config, dirty)
    }
    
    private fun createMetadataPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(3)
            inlineMetaLabel = JBLabel().apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor(0xa8adbd, 0x5a5d63)
            }
            add(inlineMetaLabel, BorderLayout.CENTER)
        }
    }
    
    private fun createEmptyStatePanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val messageLabel = JBLabel(NacosSearchBundle.message("config.detail.empty")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = Color.GRAY
            }
            
            val instructionLabel = JBLabel(NacosSearchBundle.message("config.detail.empty.instruction")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = Color.GRAY
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
    
    private fun createErrorPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val errorLabel = JBLabel(NacosSearchBundle.message("config.detail.failed")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = Color.RED
                icon = AllIcons.General.Error
            }
            
            val retryButton = JButton(NacosSearchBundle.message("common.retry")).apply {
                addActionListener {
                    currentConfiguration?.let { config ->
                        loadConfigurationContent(config)
                    }
                }
            }
            
            val centerPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(Box.createVerticalGlue())
                add(errorLabel)
                add(Box.createVerticalStrut(10))
                add(retryButton)
                add(Box.createVerticalGlue())
            }
            
            add(centerPanel, BorderLayout.CENTER)
        }
    }
    
    /**
     * Display configuration details
     */
    fun showConfiguration(configuration: NacosConfiguration) {
        showConfiguration(configuration, -1)
    }

    fun showConfiguration(configuration: NacosConfiguration, lineIndex: Int) {
        // Retargeting the detail view ends any draft, and the edit controls go
        // with it — a visible Save over a session that no longer exists would
        // do nothing. Callers that must not lose a draft ask the project's
        // guard before getting here: this panel is a renderer and does not
        // decide whether a draft may be discarded.
        exitEditMode()
        currentConfiguration = configuration
        selectedCoordinate = PresentedCoordinate.of(configuration)
        pendingNavigation = lineIndex.takeIf { it >= 0 }?.let {
            PendingNavigation(configuration.getKey(), it)
        }
        // The cached content and the metadata this method paints observed
        // nothing remotely, so they carry no observation sequence.
        val fromCache = issue()
        updateMetadata(configuration, fromCache)
        val accessIdentity = project.captureSelectedAccessIdentity(settings)
        val cachedState = cacheService.snapshot(accessIdentity).detail(
            configuration.tenantId,
            configuration.dataId,
            configuration.group
        )
        val plan = detailController.planSelection(cachedState)
        keptCachedBody = plan.immediate
        plan.immediate?.let { render(it) }
        if (!plan.shouldLoad) return
        loadConfigurationContent(
            configuration,
            fromCache,
            forceRefresh = plan.forceRefresh,
            keepCachedVisible = plan.keepCachedVisible
        )
    }

    /**
     * What an operation starting now would present: this project session's
     * epoch and the coordinate currently selected, with no observation until a
     * read reports one. Callers capture this **before** the operation and
     * re-offer it (with the read's sequence) when the result arrives.
     *
     * The coordinate is [selectedCoordinate] rather than any configuration
     * object, because a loaded detail replaces [currentConfiguration] and a
     * server may omit its namespace from the response body.
     */
    private fun issue(): PresentedResult =
        PresentedResult(project.currentSessionEpoch(), selectedCoordinate)

    /**
     * Moves the caret to [lineIndex] (0-based) and scrolls it into view.
     * Used by @NacosValue navigation to land on the defining line. Called after
     * [showConfiguration], so the editor content is already loaded.
     */
    fun moveToLine(lineIndex: Int) {
        val ed = editor ?: return
        if (ed.isDisposed) return
        val document = ed.document
        if (document.lineCount <= 0) return
        val targetLine = lineIndex.coerceIn(0, document.lineCount - 1)
        // invokeOnEdt always defers (#82). The editor can be released before
        // this runs — selection change, clear, or tool-window dispose — so
        // re-check identity and disposal the same way applyKeyGutterMarkers does.
        invokeOnEdt(ModalityState.defaultModalityState()) {
            if (editor !== ed || ed.isDisposed) return@invokeOnEdt
            val offset = document.getLineStartOffset(targetLine)
            ed.caretModel.moveToOffset(offset)
            ed.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        }
    }

    /**
     * Places a gutter icon on each line that defines a configuration key.
     * Clicking the icon opens "Find Usages" for that key, scanning the project
     * for `${key}` placeholders in @NacosValue / @Value annotations.
     */
    private fun applyKeyGutterMarkers(
        configuration: NacosConfiguration,
        ed: EditorEx,
        presented: PresentedResult
    ) {
        val keys = ConfigKeyExtractor.extract(configuration)
        if (keys.isEmpty()) return

        // Skip during dumb mode — the FileBasedIndex is not available, so
        // usage queries would fail. Markers are applied on the next config
        // open once the IDE returns to smart mode (plan 9.4).
        if (com.intellij.openapi.project.DumbService.isDumb(project)) return

        coroutineScope.launch(Dispatchers.Default) {
            val usedKeys = NacosConfigKeyReferenceSearcher.findUsedKeys(
                project, keys.keys,
                configIdentity = configuration.getKey(),
                configMd5 = configuration.md5
            )
            if (usedKeys.isEmpty()) return@launch

            invokeOnEdt(ModalityState.defaultModalityState()) {
                if (!presentation.admitAndRecord(presented)) return@invokeOnEdt
                if (editor !== ed || ed.isDisposed) return@invokeOnEdt

                val document = ed.document
                val markup = ed.markupModel
                keys.values
                    .asSequence()
                    .filter { it.key in usedKeys }
                    .filter { it.lineIndex >= 0 && it.lineIndex < document.lineCount }
                    .forEach { loc ->
                        val keyElement = NacosConfigKeyElement(project, configuration, loc.key, loc.value, loc.lineIndex)
                        val highlighter = markup.addLineHighlighter(
                            loc.lineIndex,
                            com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION,
                            null as com.intellij.openapi.editor.markup.TextAttributes?
                        )
                        highlighter.gutterIconRenderer = KeyGutterRenderer(loc.key, keyElement)
                    }
            }
        }
    }

    /**
     * Gutter icon shown on each configuration-key line. Clicking it triggers
     * Find Usages via [KeyFindUsagesAction].
     */
    private class KeyGutterRenderer(
        private val key: String,
        private val element: NacosConfigKeyElement
    ) : GutterIconRenderer() {
        override fun getIcon() = NacosIcons.GutterCodeUsage
        override fun getTooltipText(): String = "Find code usages of \${$key}"
        override fun getClickAction() = KeyFindUsagesAction(element)
        override fun getAlignment() = GutterIconRenderer.Alignment.RIGHT
        override fun equals(other: Any?): Boolean =
            other is KeyGutterRenderer && key == other.key && element == other.element
        override fun hashCode(): Int = key.hashCode() * 31 + element.hashCode()
    }

    /**
     * Action attached to the gutter icon: launches Find Usages for the
     * [NacosConfigKeyElement], which the referencesSearch executor handles.
     */
    private class KeyFindUsagesAction(
        private val keyElement: NacosConfigKeyElement
    ) : com.intellij.openapi.actionSystem.AnAction() {
        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
            val elements = NacosConfigKeyReferenceSearcher
                .findUsages(keyElement.project, keyElement.key)
                .mapNotNull { it.element }
                .distinctBy { "${it.containingFile?.virtualFile?.path}:${it.textOffset}" }
                .sortedBy { usageRank(it, keyElement.config) }

            when (elements.size) {
                0 -> com.intellij.openapi.ui.Messages.showInfoMessage(
                    keyElement.project,
                    NacosSearchBundle.message("nacosvalue.findusages.no.references", keyElement.key),
                    NacosSearchBundle.message("nacosvalue.findusages.title", keyElement.key)
                )
                1 -> (elements.single() as? com.intellij.pom.Navigatable)?.navigate(true)
                else -> {
                    val items = elements.map { NacosUsageChoiceItem(it) }
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(items)
                        .setTitle(NacosSearchBundle.message("nacosvalue.findusages.title", keyElement.key))
                        .setRenderer(CodeUsageRenderer())
                        .setItemChosenCallback { it.navigate(true) }
                        .createPopup()
                        .showInBestPositionFor(e.dataContext)
                }
            }
        }

        private fun usageRank(element: com.intellij.psi.PsiElement, config: NacosConfiguration): Int {
            val literal = element as? com.intellij.psi.PsiLiteralExpression ?: return 3
            val context = NacosCodeContextExtractor.fromLiteral(literal)
            val namespaceMatches = context.namespaceId != null && context.namespaceId == (config.tenantId ?: "public")
            val groupMatches = context.group != null && context.group == config.group
            return when {
                namespaceMatches && groupMatches -> 0
                groupMatches -> 1
                namespaceMatches -> 2
                else -> 3
            }
        }

        private class CodeUsageRenderer : JPanel(BorderLayout()), ListCellRenderer<NacosUsageChoiceItem> {
            private val primary = JLabel()
            private val secondary = JLabel()
            private val location = JLabel()

            init {
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                primary.font = primary.font.deriveFont(Font.PLAIN)
                secondary.font = secondary.font.deriveFont(Font.PLAIN, secondary.font.size2D - 1f)
                location.font = location.font.deriveFont(Font.PLAIN, location.font.size2D - 1f)

                val headerRow = JPanel(BorderLayout(8, 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(primary, BorderLayout.CENTER)
                    add(this@CodeUsageRenderer.location, BorderLayout.EAST)
                }
                secondary.alignmentX = Component.LEFT_ALIGNMENT
                val textPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(headerRow)
                    add(secondary)
                }
                add(textPanel, BorderLayout.CENTER)
                isOpaque = true
            }

            override fun getListCellRendererComponent(
                list: JList<out NacosUsageChoiceItem>,
                value: NacosUsageChoiceItem,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val presentation = value.presentation
                primary.text = presentation.primaryText
                secondary.text = presentation.secondaryText
                location.text = presentation.locationText

                background = if (isSelected) list.selectionBackground else list.background
                foreground = if (isSelected) list.selectionForeground else list.foreground
                primary.foreground = foreground
                secondary.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY
                location.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY
                return this
            }
        }
    }

   
    private fun updateMetadata(configuration: NacosConfiguration, presented: PresentedResult) {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            if (!presentation.admitAndRecord(presented)) return@invokeOnEdt
            dataIdLabel.text = configuration.dataId
            val nsDisplay = configuration.tenantId?.takeIf { it.isNotBlank() } ?: "public"
            val typeDisplay = configuration.type ?: "text"
            // Inline metadata: Group · namespace · type (compact, machine-readable values)
            if (::inlineMetaLabel.isInitialized) {
                inlineMetaLabel.text = NacosSearchBundle.message(
                    "config.detail.metadata.inline.format",
                    configuration.group,
                    nsDisplay,
                    typeDisplay
                )
            }
            sizeLabel.text = NacosSearchBundle.message("config.detail.loading.size")
            formatTagLabel.text = configuration.getConfigType().uppercase()
            renderedDirty = false
            dirtyLabel.isVisible = false
            editButton.isEnabled = true
            updateActionsEnabled()
        }
    }
    
    /** Refresh and Revert: both replace the editor's content, so both end the draft. */
    private fun loadConfigurationContent(configuration: NacosConfiguration, forceRefresh: Boolean = false) {
        exitEditMode()
        loadConfigurationContent(configuration, issue(), forceRefresh)
    }

    private fun loadConfigurationContent(
        configuration: NacosConfiguration,
        issued: PresentedResult,
        forceRefresh: Boolean = false,
        keepCachedVisible: Boolean = false
    ) {
        if (isLoading) return

        // Cancel previous loading operation
        currentLoadingJob?.cancel()

        setLoadingState(true)
        if (!keepCachedVisible) {
            render(DetailViewState.Loading)
        }

        currentLoadingJob = coroutineScope.launch {
            try {
                val context = captureOperationContext()
                if (context == null) {
                    if (!isActive) return@launch
                    if (!presentation.admitAndRecord(issued)) {
                        setLoadingState(false)
                        render(DetailViewState.Stale)
                        return@launch
                    }
                    setLoadingState(false)
                    render(DetailViewState.ConfigurationRequired())
                    return@launch
                }
                val namespaceId = operationNamespaceId(configuration)
                val target = apiService.resolveOperationTarget(context, namespaceId).getOrElse { error ->
                    if (!isActive) return@launch
                    if (!presentation.admitAndRecord(issued)) {
                        setLoadingState(false)
                        render(DetailViewState.Stale)
                        return@launch
                    }
                    setLoadingState(false)
                    if (keepCachedVisible && keptCachedBody != null) {
                        render(
                            DetailPresentation.fromRefreshFailure(
                                keptCachedBody!!.configuration,
                                keptCachedBody!!.confidence
                            )
                        )
                    } else {
                        render(DetailPresentation.fromFailure(error, error.message ?: "Unknown error"))
                    }
                    return@launch
                }

                val state = detailController.load(
                    target = target,
                    coordinate = ConfigurationCoordinate(configuration.dataId, configuration.group),
                    sessionEpoch = issued.sessionEpoch,
                    forceRefresh = forceRefresh,
                    useCache = true,
                    keepCachedVisible = keepCachedVisible,
                    cachedBody = keptCachedBody
                )

                if (!isActive) return@launch
                setLoadingState(false)
                render(state)
                if (state is DetailViewState.Body && state.overlay == DetailOverlay.None) {
                    refreshNavigationState()
                } else if (state is DetailViewState.Body && state.overlay == DetailOverlay.Deleted) {
                    refreshNavigationState()
                }
            } catch (e: Exception) {
                if (isActive && presentation.admitAndRecord(issued)) {
                    setLoadingState(false)
                    if (keepCachedVisible && keptCachedBody != null) {
                        render(
                            DetailPresentation.fromRefreshFailure(
                                keptCachedBody!!.configuration,
                                keptCachedBody!!.confidence
                            )
                        )
                    } else {
                        render(
                            DetailPresentation.fromFailure(
                                e,
                                e.message ?: "Unknown error"
                            )
                        )
                    }
                }
            }
        }
    }
    
    private fun displayConfigurationContentSafely(
        configuration: NacosConfiguration,
        presented: PresentedResult
    ) {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            if (!presentation.admitAndRecord(presented)) return@invokeOnEdt
            // Dispose previous editor safely
            disposeEditorSafely()
            
            // Wait for disposal to complete
            editorLock.withLock {
                while (editorState.get() == EditorState.DISPOSING) {
                    Thread.sleep(10) // Brief wait for disposal completion
                }
                
                // Reset state for new editor
                editorState.set(EditorState.NONE)
            }

            // The disposal wait above can span a selection or session change.
            if (!presentation.admitAndRecord(presented)) return@invokeOnEdt

            val content = configuration.content
            val fileType = determineFileType(configuration)

            // Create new editor safely
            val newEditor = createEditorSafely(content, fileType)
           if (newEditor != null) {
              editor = newEditor
              updateEditorUI(newEditor)
              // Content card exists only after updateEditorUI — reveal it here so
              // CardLayout.show("content") is never a no-op on first load.
              if (::loadingLabel.isInitialized) {
                  loadingLabel.isVisible = false
              }
              val cardLayout = contentPanel.layout as CardLayout
              cardLayout.show(contentPanel, "content")
              applyKeyGutterMarkers(configuration, newEditor, presented)
              consumePendingNavigation(configuration)
               editButton.isEnabled = true
               updateActionsEnabled()
                editButton.isVisible = true
                editButton.text = NacosSearchBundle.message("config.detail.action.edit")
               saveButton.isEnabled = false
               saveButton.isVisible = false
               revertButton.isEnabled = false
               revertButton.isVisible = false
            }
        }
    }
    
    private fun createEditorSafely(content: String, fileType: FileType): EditorEx? {
        return editorLock.withLock {
            if (!editorState.compareAndSet(EditorState.NONE, EditorState.CREATING)) {
                logger.warn("Editor creation skipped - another operation in progress")
                return null // Another operation in progress
            }
            
            try {
                val document = EditorFactory.getInstance().createDocument(content)
                val newEditor = EditorFactory.getInstance().createEditor(document, project, fileType, false) as EditorEx

                // Start in read-only mode; the Edit button toggles to writable
                document.setReadOnly(true)

                // Report content changes to the project's draft, if one exists
                document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
                    override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                        checkDirtyState(document.text)
                    }
                })

                // disposeEditorSafely() releases on swap and panel dispose; avoid
                // stacking Disposer callbacks that retain every past editor.
                
                editorState.set(EditorState.ACTIVE)
                newEditor
            } catch (e: Exception) {
                logger.error("Failed to create editor", e)
                editorState.set(EditorState.NONE)
                null
            }
        }
    }
    
    private fun updateEditorUI(ed: EditorEx) {
        // Bind the in-memory editor to the current IDE theme's color scheme so
        // it follows dark/light instead of defaulting to a light scheme.
        applyThemeScheme(ed)
        // Configure editor settings
        val settings = ed.settings
        settings.isLineNumbersShown = true
        settings.isLineMarkerAreaShown = true
        settings.isFoldingOutlineShown = true
        settings.isRightMarginShown = false
        settings.isWhitespacesShown = false
        settings.isIndentGuidesShown = true
        
        // Create editor panel
        editorPanel = JPanel(BorderLayout()).apply {
            add(ed.component, BorderLayout.CENTER)
        }
        
        // Add to content panel
        contentPanel.add(editorPanel, "content")
    }

    /**
     * Applies the color scheme that matches the current IDE UI theme to [ed].
     * The in-memory editor created via EditorFactory otherwise defaults to a
     * light scheme regardless of the active dark theme.
     */
    private fun applyThemeScheme(ed: EditorEx) {
        val scheme = EditorColorsManager.getInstance().schemeForCurrentUITheme
        ed.colorsScheme = scheme
    }

    private fun consumePendingNavigation(configuration: NacosConfiguration) {
        val pending = pendingNavigation ?: return
        if (pending.configKey != configuration.getKey()) return
        pendingNavigation = null
        moveToLine(pending.lineIndex)
    }
    
    private fun determineFileType(configuration: NacosConfiguration): FileType {
        val type = configuration.type?.lowercase()
        val dataId = configuration.dataId.lowercase()
        
        return when {
            type == "json" || dataId.endsWith(".json") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("json")
            type == "yaml" || type == "yml" || dataId.endsWith(".yaml") || dataId.endsWith(".yml") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("yaml")
            type == "properties" || dataId.endsWith(".properties") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("properties")
            type == "xml" || dataId.endsWith(".xml") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("xml")
            type == "html" || dataId.endsWith(".html") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("html")
            type == "sql" || dataId.endsWith(".sql") -> 
                FileTypeManager.getInstance().getFileTypeByExtension("sql")
            else -> FileTypeManager.getInstance().getFileTypeByExtension("txt")
        }
    }
    
    private fun formatSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    /**
     * Computes a short 16-hex-char MD5 hash for the editor status bar.
     */
    private fun computeShortMd5(text: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(text.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            "—"
        }
    }

    /**
     * Updates the editor status bar with current content stats.
     */
    private fun updateStatusBar(content: String) {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            val charCount = content.length
            val lineCount = if (content.isEmpty()) 1 else content.split("\n").size
            statusCharsLabel.text = NacosSearchBundle.message("config.detail.status.chars.format", charCount)
            statusMd5Label.text = NacosSearchBundle.message("config.detail.status.md5", computeShortMd5(content))
            statusPositionLabel.text = NacosSearchBundle.message("config.detail.status.position", 1, 1)
        }
    }
    
    private fun copyContentToClipboard() {
        editor?.document?.text?.let { content ->
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val stringSelection = java.awt.datatransfer.StringSelection(content)
            clipboard.setContents(stringSelection, null)
            
            // Show brief notification
            JOptionPane.showMessageDialog(
                this,
                NacosSearchBundle.message("config.detail.copy.success"),
                NacosSearchBundle.message("common.success"),
                JOptionPane.INFORMATION_MESSAGE
            )
        }
    }
    
    private fun setLoadingState(loading: Boolean) {
        // Gate [loadConfigurationContent] synchronously — deferring only the
        // flag to invokeLater left a window where a second selection started
        // another load while the first was still in flight.
        isLoading = loading
        invokeOnEdt(ModalityState.defaultModalityState()) {
            updateActionsEnabled()
        }
    }

    /**
     * Refreshes the enabled state of the read-utility actions (Refresh / Copy / History)
     * from the current panel state, then asks the ActionToolbar to re-render.
     * Centralising this replaces the previously scattered copyButton/historyButton/refreshButton
     * isEnabled mutations across updateMetadata/displayConfigurationContentSafely/clearConfiguration.
     */
    private fun updateActionsEnabled() {
        // updateActionsImmediately() touches the action system and must run on the EDT; this is
        // reached from both EDT contexts and construct-time paths, so dispatch defensively.
        val run = {
            if (::detailToolbar.isInitialized) {
                detailToolbar.updateActionsImmediately()
            }
        }
        invokeOnEdt(ModalityState.defaultModalityState(), run)
    }
    
    private fun showCard(cardName: String) {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            val cardLayout = contentPanel.layout as CardLayout
            cardLayout.show(contentPanel, cardName)
        }
    }

    /** Reveal the editor card and ensure the Loading label is not left painted over it. */
    private fun showContentCard() {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            if (::loadingLabel.isInitialized) {
                loadingLabel.isVisible = false
            }
            val cardLayout = contentPanel.layout as CardLayout
            cardLayout.show(contentPanel, "content")
        }
    }

    private val bundleMessage: (String, Array<out Any>) -> String = { key, params ->
        if (params.isEmpty()) NacosSearchBundle.message(key)
        else NacosSearchBundle.message(key, *params)
    }

    /**
     * Renders one closed [DetailViewState] with an exhaustive branch — no
     * catch-all. Decisions live in [DetailController] / [DetailPresentation].
     */
    fun render(state: DetailViewState) {
        viewState = state
        when (state) {
            is DetailViewState.Empty -> {
                currentConfiguration = null
                keptCachedBody = null
                showCard("empty")
                hideFreshnessStatus()
                updateActionsEnabled()
            }
            is DetailViewState.Loading -> {
                if (::loadingLabel.isInitialized) {
                    loadingLabel.text = NacosSearchBundle.message("config.detail.loading")
                }
                // Let CardLayout alone own visibility — setting the label visible
                // here before showCard runs leaves it painted over the previous card.
                showCard("loading")
                hideFreshnessStatus()
            }
            is DetailViewState.Body -> {
                val sameEditor = editor != null &&
                    currentConfiguration?.getKey() == state.configuration.getKey() &&
                    state.editing
                currentConfiguration = state.configuration
                keptCachedBody = state
                val presented = PresentedResult(
                    project.currentSessionEpoch(),
                    selectedCoordinate,
                    state.observation
                )
                if (!sameEditor) {
                    // showCard("content") runs inside displayConfigurationContentSafely
                    // only after the editor is added — showing it earlier is a no-op
                    // when the content card does not exist yet (first load), and can
                    // leave the Loading label painted over the editor.
                    displayConfigurationContentSafely(state.configuration, presented)
                } else {
                    showContentCard()
                }
                val overlayText = DetailCopy.overlayMessage(state.overlay, bundleMessage)
                if (overlayText != null) {
                    invokeOnEdt(ModalityState.defaultModalityState()) {
                        freshnessLabel.text = overlayText
                        freshnessLabel.isVisible = true
                    }
                } else {
                    hideFreshnessStatus()
                }
                if (state.editing) {
                    editor?.document?.setReadOnly(false)
                    if (::editButton.isInitialized) editButton.isVisible = false
                    if (::revertButton.isInitialized) revertButton.isVisible = true
                    if (::saveButton.isInitialized) saveButton.isVisible = true
                }
                if (state.dirty != renderedDirty) {
                    updateDirtyUI(state.dirty)
                }
                invokeOnEdt(ModalityState.defaultModalityState()) {
                    sizeLabel.text = formatSize(state.configuration.content.length)
                }
                updateStatusBar(state.configuration.content)
            }
            is DetailViewState.WriteIntentDisabled -> {
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    DetailCopy.writeIntentMessage(state.cause, bundleMessage),
                    NacosSearchBundle.message("config.detail.action.edit")
                )
            }
            is DetailViewState.ConfigurationRequired -> {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    state.detail?.takeIf { it.isNotBlank() }
                        ?: NacosSearchBundle.message("error.connection.incomplete"),
                    NacosSearchBundle.message("common.error")
                )
            }
            is DetailViewState.AwaitingConfirmation -> {
                // Confirmation is a ceremony gesture: the save path shows the
                // dialog and waits. Render keeps the named target/diff on the
                // state for tests and for any future non-modal UI.
            }
            is DetailViewState.Publishing -> {
                if (::loadingLabel.isInitialized) {
                    loadingLabel.text = NacosSearchBundle.message("config.detail.publish.publishing")
                    loadingLabel.isVisible = true
                }
            }
            is DetailViewState.Verifying -> {
                if (::loadingLabel.isInitialized) {
                    loadingLabel.text = NacosSearchBundle.message("config.detail.publish.verifying")
                    loadingLabel.isVisible = true
                }
            }
            is DetailViewState.Verified -> {
                if (::loadingLabel.isInitialized) loadingLabel.isVisible = false
                val verified = state.configuration
                if (verified != null) {
                    currentConfiguration = verified
                }
                exitEditMode()
                if (::saveButton.isInitialized) saveButton.isEnabled = false
                if (::revertButton.isInitialized) revertButton.isEnabled = false
                updateDirtyUI(false)
                updateStatusBar(verified?.content ?: currentConfiguration?.content.orEmpty())
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    NacosSearchBundle.message("message.configuration.saved"),
                    NacosSearchBundle.message("common.success")
                )
            }
            is DetailViewState.Conflict -> {
                if (::loadingLabel.isInitialized) loadingLabel.isVisible = false
                openConflictDiff(state.remoteContent, state.draftContent)
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    NacosSearchBundle.message("config.detail.publish.conflict"),
                    NacosSearchBundle.message("common.error")
                )
            }
            is DetailViewState.ServerStateUnknown -> {
                if (::loadingLabel.isInitialized) loadingLabel.isVisible = false
                if (::saveButton.isInitialized) {
                    saveButton.text = NacosSearchBundle.message("config.detail.publish.reconcile")
                    saveButton.isEnabled = true
                }
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    NacosSearchBundle.message("config.detail.publish.unknown"),
                    NacosSearchBundle.message("common.error")
                )
            }
            is DetailViewState.Failed -> {
                if (::loadingLabel.isInitialized) loadingLabel.isVisible = false
                if (state.asErrorCard) {
                    showCard("error")
                }
                val body = DetailCopy.failedBody(state, bundleMessage)
                invokeOnEdt(ModalityState.defaultModalityState()) {
                    JOptionPane.showMessageDialog(
                        this,
                        body,
                        NacosSearchBundle.message("common.error"),
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
            is DetailViewState.Stale -> {
                // A discarded late arrival must not change what is already shown.
            }
        }
    }

    private fun showFreshnessStatus(messageKey: String) {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            freshnessLabel.text = NacosSearchBundle.message(messageKey)
            freshnessLabel.isVisible = true
        }
    }

    private fun hideFreshnessStatus() {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            freshnessLabel.isVisible = false
        }
    }

    private fun refreshNavigationState() {
        navigationRefresh.refresh(project.captureSelectedAccessIdentity(settings), project)
    }
    
    private fun showEmptyState() {
        render(DetailViewState.Empty)
    }
    
    private fun disposeEditorSafely() {
        editorLock.withLock {
            val currentState = editorState.get()
            if (currentState == EditorState.DISPOSING || currentState == EditorState.DISPOSED) {
                return // Already disposing or disposed
            }
            
            if (!editorState.compareAndSet(currentState, EditorState.DISPOSING)) {
                return // State changed, abort
            }
            
            try {
                editor?.let { ed ->
                    if (!ed.isDisposed) {
                        EditorFactory.getInstance().releaseEditor(ed)
                    }
                }
                editor = null
                
                editorPanel?.let { panel ->
                    contentPanel.remove(panel)
                }
                editorPanel = null
                
                editorState.set(EditorState.DISPOSED)
            } catch (e: Exception) {
                // Log error but don't rethrow to prevent cascading failures
                logger.warn("Error disposing editor", e)
                editorState.set(EditorState.DISPOSED)
            }
        }
    }
    
    /**
     * Save (publish) the draft the project holds. Preflight parks at
     * awaiting-confirmation with the diff and the named target from the
     * session binding; only an explicit confirm sends the write (ADR-0027 /
     * ADR-0028). A selection change between starting the edit and pressing
     * save cannot retarget where it publishes.
     */
    private fun saveConfiguration() {
        val session = editSessions.currentSession() ?: return
        if (!session.isDirty) return
        // Server-state-unknown: only reconcile (or abandon elsewhere), never a second write.
        if (editSessions.isServerStateUnknown()) {
            coroutineScope.launch {
                val presented = issue()
                val result = editSessions.reconcilePublish() ?: return@launch
                withContext(Dispatchers.Main) {
                    if (!presentation.admitAndRecord(presented)) {
                        render(DetailViewState.Stale)
                        return@withContext
                    }
                    render(detailController.fromPublishResult(result, session.draftContent))
                }
            }
            return
        }
        coroutineScope.launch {
            try {
                val presented = issue()
                val prepareResult = editSessions.requestPublish() ?: return@launch
                when (val state = prepareResult.state) {
                    is PublishState.AwaitingConfirmation -> {
                        render(detailController.fromPublishState(state, session.draftContent))
                        val target = state.namedTarget
                        val confirm = withContext(Dispatchers.Main) {
                            com.intellij.openapi.ui.Messages.showYesNoDialog(
                                project,
                                NacosSearchBundle.message(
                                    "config.detail.publish.confirm",
                                    target.dataId,
                                    target.group,
                                    target.profileDisplayName,
                                    target.namespaceId,
                                    target.endpoint
                                ),
                                NacosSearchBundle.message("config.detail.action.save.publish"),
                                com.intellij.openapi.ui.Messages.getQuestionIcon()
                            )
                        }
                        if (confirm != com.intellij.openapi.ui.Messages.YES) {
                            editSessions.cancelPublish()
                            return@launch
                        }
                        val progressJob = launch {
                            editSessions.publishState.collect { phase ->
                                when (phase) {
                                    is PublishState.Publishing,
                                    is PublishState.Verifying -> {
                                        withContext(Dispatchers.Main) {
                                            render(detailController.fromPublishState(phase))
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                        val publishResult = try {
                            editSessions.confirmPublish()
                        } finally {
                            progressJob.cancel()
                            withContext(Dispatchers.Main) {
                                if (::loadingLabel.isInitialized) loadingLabel.isVisible = false
                            }
                        }
                        if (publishResult == null) {
                            withContext(Dispatchers.Main) {
                                render(
                                    DetailViewState.Failed(
                                        detailKey = "config.detail.publish.confirmation.expired",
                                        asErrorCard = false
                                    )
                                )
                            }
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            if (!presentation.admitAndRecord(presented)) {
                                render(DetailViewState.Stale)
                                return@withContext
                            }
                            render(detailController.fromPublishResult(publishResult, session.draftContent))
                        }
                    }
                    is PublishState.Dirty -> {
                        withContext(Dispatchers.Main) {
                            if (!presentation.admitAndRecord(presented)) {
                                render(DetailViewState.Stale)
                                return@withContext
                            }
                            render(
                                DetailViewState.Failed(
                                    detailKey = "config.detail.publish.confirmation.expired",
                                    asErrorCard = false
                                )
                            )
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            if (!presentation.admitAndRecord(presented)) {
                                render(DetailViewState.Stale)
                                return@withContext
                            }
                            render(detailController.fromPublishResult(prepareResult, session.draftContent))
                        }
                    }
                }
            } catch (e: Exception) {
                showSaveError(e.message ?: e.toString())
            }
        }
    }

    private fun openConflictDiff(remoteContent: String, draftContent: String) {
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension("txt")
        val leftDoc = com.intellij.openapi.editor.impl.DocumentImpl(remoteContent).apply { setReadOnly(true) }
        val rightDoc = com.intellij.openapi.editor.impl.DocumentImpl(draftContent).apply { setReadOnly(true) }
        val left = com.intellij.diff.contents.DocumentContentImpl(null, leftDoc, fileType)
        val right = com.intellij.diff.contents.DocumentContentImpl(null, rightDoc, fileType)
        val request = com.intellij.diff.requests.SimpleDiffRequest(
            NacosSearchBundle.message("config.detail.publish.conflict"),
            left,
            right,
            "Remote",
            "Draft"
        )
        request.putUserData(com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY, true)
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request)
    }

    private suspend fun showSaveError(message: String) {
        withContext(Dispatchers.Main) {
            render(
                DetailViewState.Failed(
                    titleKey = DetailPresentation.SAVE_FAILED_TITLE_KEY,
                    detail = message,
                    asErrorCard = false
                )
            )
        }
    }

    private fun openHistoryBrowser() {
        val config = currentConfiguration ?: return
        coroutineScope.launch {
            val context = captureOperationContext()
            if (context == null) {
                withContext(Dispatchers.Main) {
                    render(DetailViewState.ConfigurationRequired())
                }
                return@launch
            }
            val namespaceId = operationNamespaceId(config)
            val target = apiService.resolveOperationTarget(context, namespaceId).getOrElse {
                withContext(Dispatchers.Main) {
                    render(DetailPresentation.fromFailure(it, it.message ?: it.toString()))
                }
                return@launch
            }
            val currentText = editor?.document?.text ?: config.content
            withContext(Dispatchers.Main) {
                HistoryBrowserDialog(
                    project = project,
                    target = target,
                    configuration = config,
                    currentContent = currentText,
                    gateway = apiService.operationGateway(),
                    sessionEpochProvider = { project.currentSessionEpoch() }
                ).show()
            }
        }
    }

    /**
     * Clear the current configuration display
     */
    fun clearConfiguration() {
        // Cancel any ongoing loading operation
        currentLoadingJob?.cancel()
        setLoadingState(false)

        // Nothing is selected any more, so every result still in flight now
        // names a coordinate this panel does not present, and is discarded.
        currentConfiguration = null
        selectedCoordinate = null
        keptCachedBody = null
        exitEditMode()
        disposeEditorSafely()
        showEmptyState()
        editButton.isVisible = true
        saveButton.isEnabled = false
        saveButton.isVisible = false
        editButton.isEnabled = false
        revertButton.isEnabled = false
        revertButton.isVisible = false
        updateActionsEnabled()
        renderedDirty = false
        dirtyLabel.isVisible = false

        // Reset status bar
        statusCharsLabel.text = NacosSearchBundle.message("config.detail.status.chars.format", 0)
        statusMd5Label.text = "—"
        statusPositionLabel.text = NacosSearchBundle.message("config.detail.status.position", 1, 1)
        
        invokeOnEdt(ModalityState.defaultModalityState()) {
            dataIdLabel.text = ""
            if (::inlineMetaLabel.isInitialized) {
                inlineMetaLabel.text = ""
            }
            sizeLabel.text = ""
        }
    }
    
    /**
     * Get the current configuration
     */
    fun getCurrentConfiguration(): NacosConfiguration? = currentConfiguration
    
    /**
     * Clean up resources
     */
    override fun dispose() {
        // Cancel any ongoing operations
        currentLoadingJob?.cancel()
        coroutineScope.cancel()
        
        // Dispose editor safely
        disposeEditorSafely()
        
        // Note: Registered disposables will be automatically cleaned up by Disposer
    }
    
    /**
     * Called when the language is changed
     */
    override fun languageChanged() {
        // Refresh all UI text elements
        refreshUIText()

        // Rebuild metadata panel with new language
        rebuildMetadataPanel()

        // Rebuild empty state panel with new language
        rebuildEmptyStatePanel()

        // Rebuild error panel with new language
        rebuildErrorPanel()

        // Re-derive structured copy from the closed state (ADR-0036) without
        // re-firing one-shot dialogs for Failed / Verified / Conflict.
        when (val state = viewState) {
            is DetailViewState.Body -> {
                val overlayText = DetailCopy.overlayMessage(state.overlay, bundleMessage)
                if (overlayText != null) {
                    freshnessLabel.text = overlayText
                    freshnessLabel.isVisible = true
                }
            }
            is DetailViewState.Loading -> {
                loadingLabel.text = NacosSearchBundle.message("config.detail.loading")
            }
            is DetailViewState.Publishing -> {
                loadingLabel.text = NacosSearchBundle.message("config.detail.publish.publishing")
            }
            is DetailViewState.Verifying -> {
                loadingLabel.text = NacosSearchBundle.message("config.detail.publish.verifying")
            }
            else -> Unit
        }

        // Revalidate and repaint the UI
        revalidate()
        repaint()
    }

    /**
     * Refresh all UI text elements
     */
    private fun refreshUIText() {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            // Update loading text
            loadingLabel.text = NacosSearchBundle.message("config.detail.loading")
            
            // Update button tooltips
            
            // Update size label if there's content
            currentConfiguration?.let { config ->
                val contentSize = config.content.length
                sizeLabel.text = formatSize(contentSize)
            }
        }
    }
    
    /**
     * Rebuild metadata panel with new language
     */
    private fun rebuildMetadataPanel() {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            // Remove old metadata panel
            remove(metadataPanel)
            
            // Create new metadata panel with updated language
            metadataPanel = createMetadataPanel()
            
            // Add to layout
            val topPanel = getComponent(0) as? JPanel
            topPanel?.add(metadataPanel, BorderLayout.CENTER)
            
            // Update metadata with current configuration
            currentConfiguration?.let { config ->
                updateMetadata(config, issue())
            }
        }
    }
    
    /**
     * Rebuild empty state panel with new language
     */
    private fun rebuildEmptyStatePanel() {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            // Remove old empty state panel
            contentPanel.remove(emptyStatePanel)
            
            // Create new empty state panel with updated language
            emptyStatePanel = createEmptyStatePanel()
            
            // Add back to content panel
            contentPanel.add(emptyStatePanel, "empty")
        }
    }
    
    /**
     * Rebuild error panel with new language
     */
    private fun rebuildErrorPanel() {
        invokeOnEdt(ModalityState.defaultModalityState()) {
            // Remove old error panel
            contentPanel.remove(errorPanel)
            
            // Create new error panel with updated language
            errorPanel = createErrorPanel()
            
            // Add back to content panel
            contentPanel.add(errorPanel, "error")
        }
    }
    
    private inner class RefreshDetailAction :
        AnAction(NacosSearchBundle.message("config.detail.refresh"),
                 NacosSearchBundle.message("config.detail.refresh"),
                 AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            currentConfiguration?.let { config ->
                loadConfigurationContent(config, forceRefresh = true)
            }
        }
        override fun update(e: AnActionEvent) {
            // With a dirty draft, refreshing replaces the editor's content —
            // which is exactly what Revert does, deliberately, right beside
            // Save. Two buttons for one destructive act is how a draft gets
            // thrown away by accident (ADR-0027), so only Revert offers it.
            e.presentation.isEnabled = currentConfiguration != null && !isLoading &&
                !editSessions.isDirty()
        }
    }

    private inner class CopyDetailAction :
        AnAction(NacosSearchBundle.message("config.detail.action.copy"),
                 NacosSearchBundle.message("config.detail.copy"),
                 AllIcons.Actions.Copy) {
        override fun actionPerformed(e: AnActionEvent) = copyContentToClipboard()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentConfiguration != null
        }
    }

    private inner class HistoryDetailAction :
        AnAction(NacosSearchBundle.message("config.detail.action.history"),
                 NacosSearchBundle.message("config.detail.action.history.tooltip"),
                 AllIcons.Vcs.History) {
        override fun actionPerformed(e: AnActionEvent) = openHistoryBrowser()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentConfiguration != null
        }
    }
}
