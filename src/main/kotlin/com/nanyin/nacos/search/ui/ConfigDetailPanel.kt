package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.services.LanguageService
import com.intellij.icons.AllIcons
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
import com.nanyin.nacos.search.psi.NacosKeyResolver
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
import com.nanyin.nacos.search.services.operations.EditSession
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.services.operations.PublishState
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.captureSelectedAccessIdentity
import com.nanyin.nacos.search.settings.selectedNacosProfileId
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.swing.*
import kotlin.concurrent.withLock


internal fun interface ConfigurationDetailLoader {
    suspend fun load(configuration: NacosConfiguration, forceRefresh: Boolean): Result<NacosConfiguration?>
}

/**
 * Panel for displaying configuration details with syntax highlighting
 */
class ConfigDetailPanel internal constructor(
    private val project: Project,
    private val detailLoader: ConfigurationDetailLoader,
    private val cacheService: CacheService,
    private val settings: NacosSettings
) : JPanel(BorderLayout()), Disposable, LanguageAwareComponent {
    constructor(project: Project) : this(
        project,
        ConfigurationDetailLoader { configuration, forceRefresh ->
            ApplicationManager.getApplication().getService(NacosApiService::class.java).getConfiguration(
                dataId = configuration.dataId,
                group = configuration.group,
                namespaceId = configuration.tenantId,
                useCache = true,
                forceRefresh = forceRefresh,
                operationContext = withContext(Dispatchers.IO) {
                    project.getService(NacosProjectSession::class.java)?.let { session ->
                        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
                        session.healSelection(settings)
                        settings.captureOperationContext(session.sessionState.selectedProfileId).getOrNull()
                    }
                }
            )
        },
        ApplicationManager.getApplication().getService(CacheService::class.java),
        ApplicationManager.getApplication().getService(NacosSettings::class.java)
    )

    companion object {
        private const val DETAIL_HORIZONTAL_INSET = 10
    }
    
    private val nacosApiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    private val languageService = ApplicationManager.getApplication().getService(LanguageService::class.java)
    private suspend fun selectedOperationContext() = withContext(Dispatchers.IO) {
        project.getService(NacosProjectSession::class.java)?.let { session ->
            session.healSelection(settings)
            settings.captureOperationContext(session.sessionState.selectedProfileId).getOrNull()
        }
    }

    /** Project-selected profile — not the app-wide Settings "active"/default. */
    private fun selectedProfile() = settings.getProfile(project.selectedNacosProfileId(settings))

    /**
     * Namespace for history/publish. Prefer the config's tenant; when list APIs
     * omit tenant, fall back to this project's session namespace — never assume
     * public while the tool window is browsing another namespace.
     */
    private fun operationNamespaceId(config: NacosConfiguration): String {
        config.tenantId?.takeIf { it.isNotBlank() }?.let { return it }
        project.getService(NacosProjectSession::class.java)?.sessionState?.namespaceId
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return "public"
    }
    
    // UI Components
    private lateinit var metadataPanel: JPanel
    private lateinit var contentPanel: JPanel
    private lateinit var loadingLabel: JBLabel
    private lateinit var emptyStatePanel: JPanel
    private lateinit var errorPanel: JPanel
    private lateinit var refreshButton: JButton
    private lateinit var copyButton: JButton
    private lateinit var saveButton: JButton
    private lateinit var editButton: JButton
    private lateinit var revertButton: JButton
    private lateinit var historyButton: JButton
    private lateinit var formatTagLabel: JBLabel
    private lateinit var dirtyLabel: JBLabel
    private lateinit var freshnessLabel: JBLabel
    /** Bound publish target captured when entering edit mode (never live UI). */
    private var boundEditTarget: OperationTarget? = null
    
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
    private var pendingNavigation: PendingNavigation? = null
    private var isLoading = false
    // Dirty-state tracking: original content snapshot for change detection
    private var originalContent: String = ""
    private var isDirty: Boolean = false
    private var displayGeneration: Long = 0
    
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
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC, com.intellij.ide.ui.LafManagerListener {
                editor?.let { applyThemeScheme(it) }
            })
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
       
      // Read-utility buttons (Refresh / Copy / History) are compact icon-only
        // toolbar controls — tool actions, so they carry an icon + tooltip rather
        // than a text label. This keeps the action bar scannable instead of a row
        // of equal-weight buttons. See [detailIconButton] below for the shared style.
        refreshButton = detailIconButton(
            AllIcons.Actions.Refresh,
            NacosSearchBundle.message("config.detail.refresh")
        )
        copyButton = detailIconButton(
            AllIcons.Actions.Copy,
            NacosSearchBundle.message("config.detail.copy")
        ).apply { isEnabled = false }
        historyButton = detailIconButton(
            AllIcons.Vcs.History,
            NacosSearchBundle.message("config.detail.action.history.tooltip")
        ).apply { isEnabled = false }

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
   
      /**
     * Compact icon-only toolbar button for a read-utility action (Refresh / Copy / History).
     * Mirrors the tool-window header [iconButton] idiom: flat, borderless, 26x26, with the
     * full label exposed only as a tooltip so the action bar stays scannable.
     */
    private fun detailIconButton(icon: javax.swing.Icon, tooltip: String): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            putClientProperty("JButton.buttonType", "toolbar")
            preferredSize = Dimension(26, 26)
            minimumSize = Dimension(26, 26)
            border = JBUI.Borders.empty()
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
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
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                // Read utilities: compact icon buttons for the current content
                add(refreshButton)
                add(copyButton)
                add(historyButton)
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
        refreshButton.addActionListener {
            currentConfiguration?.let { config ->
                loadConfigurationContent(config, forceRefresh = true)
            }
        }
        
        copyButton.addActionListener {
            copyContentToClipboard()
        }

        saveButton.addActionListener {
            saveConfiguration()
        }

       editButton.addActionListener {
            enterEditMode()
       }

       revertButton.addActionListener {
           revertEdits()
      }

        historyButton.addActionListener {
            openHistoryBrowser()
        }
  }


 /**
  * Reverts unsaved edits by reloading the original content.
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
    * Enters edit mode: makes the editor writable and hides the Edit button
    * (the user exits via Save or Revert). Matches the design prototype which
    * hides #editBtn while editing.
    */
  private fun enterEditMode() {
      val profile = selectedProfile()
      if (profile == null || !profile.writeIntent) {
          com.intellij.openapi.ui.Messages.showInfoMessage(
              NacosSearchBundle.message("config.detail.publish.writes.disabled"),
              NacosSearchBundle.message("config.detail.action.edit")
          )
          return
      }
      coroutineScope.launch {
          val context = selectedOperationContext()
          if (context == null) {
              withContext(Dispatchers.Main) {
                  com.intellij.openapi.ui.Messages.showErrorDialog(
                      NacosSearchBundle.message("error.connection.incomplete"),
                      NacosSearchBundle.message("common.error")
                  )
              }
              return@launch
          }
          val config = currentConfiguration ?: return@launch
          val namespaceId = operationNamespaceId(config)
          val targetResult = nacosApiService.resolveOperationTarget(context, namespaceId)
          withContext(Dispatchers.Main) {
              val target = targetResult.getOrElse {
                  com.intellij.openapi.ui.Messages.showErrorDialog(
                      it.message ?: NacosSearchBundle.message("common.error"),
                      NacosSearchBundle.message("common.error")
                  )
                  return@withContext
              }
              boundEditTarget = target
              editor?.let { ed ->
                  ed.document.setReadOnly(false)
                  // Swap the edit lifecycle: hide Edit, reveal Save/Revert (only shown while editing)
                  editButton.isVisible = false
                  revertButton.isVisible = true
                  saveButton.isVisible = true
                  checkDirtyState(ed.document.text)
              }
          }
      }
  }

    /**
     * Restores the read-only view mode: locks the editor and re-shows the Edit button.
     */
    private fun exitEditMode() {
        editor?.document?.setReadOnly(true)
        // Restore view mode: re-show Edit and hide the Save/Revert commit buttons
        editButton.isVisible = true
        editButton.text = NacosSearchBundle.message("config.detail.action.edit")
        saveButton.isVisible = false
        revertButton.isVisible = false
    }

    /**
     * Compares current editor content against the original snapshot and
     * updates dirty state if changed.
     */
    private fun checkDirtyState(currentText: String) {
        val newDirty = currentText != originalContent
        if (newDirty != isDirty) {
            updateDirtyUI(newDirty)
        }
    }

    /**
     * Updates all dirty-state indicators: title asterisk, pill, and notifies
     * the window so the list row dot can update.
     */
    private fun updateDirtyUI(dirty: Boolean) {
        isDirty = dirty
        dirtyLabel.isVisible = dirty
        saveButton.isEnabled = dirty
        revertButton.isEnabled = dirty
        // Title asterisk: append/remove '*' from dataId label text
        val config = currentConfiguration ?: return
        val baseId = config.dataId
        ApplicationManager.getApplication().invokeLater({
            dataIdLabel.text = if (dirty && !baseId.endsWith("*")) "$baseId *" else baseId
        }, ModalityState.defaultModalityState())
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
        val generation = ++displayGeneration
        currentConfiguration = configuration
        pendingNavigation = lineIndex.takeIf { it >= 0 }?.let {
            PendingNavigation(configuration.getKey(), it)
        }
        updateMetadata(configuration, generation)
        val accessIdentity = project.captureSelectedAccessIdentity(settings)
        val cachedState = cacheService.configDetailState(
            accessIdentity,
            configuration.tenantId,
            configuration.dataId,
            configuration.group
        )
        val freshness = cachedState?.freshness
        val cachedFirst = freshness != null && freshness != CacheService.DetailFreshness.FRESH
        if (cachedFirst) {
            displayConfigurationContentSafely(configuration)
            showCard("content")
        }
        if (freshness == CacheService.DetailFreshness.STALE) {
            hideFreshnessStatus()
            return
        }
        if (freshness == CacheService.DetailFreshness.DEEP_STALE) {
            showFreshnessStatus("config.detail.cache.refreshing")
        }
        loadConfigurationContent(
            configuration,
            generation,
            forceRefresh = freshness == CacheService.DetailFreshness.DEEP_STALE,
            keepCachedVisible = cachedFirst
        )
    }

    /**
     * Moves the caret to [lineIndex] (0-based) and scrolls it into view.
     * Used by @NacosValue navigation to land on the defining line. Called after
     * [showConfiguration], so the editor content is already loaded.
     */
    fun moveToLine(lineIndex: Int) {
        val ed = editor ?: return
        val document = ed.document
        val targetLine = lineIndex.coerceIn(0, document.lineCount - 1)
        ApplicationManager.getApplication().invokeLater {
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
    private fun applyKeyGutterMarkers(configuration: NacosConfiguration, ed: EditorEx) {
        val keys = ConfigKeyExtractor.extract(configuration)
        if (keys.isEmpty()) return

        // Skip during dumb mode — the FileBasedIndex is not available, so
        // usage queries would fail. Markers are applied on the next config
        // open once the IDE returns to smart mode (plan 9.4).
        if (com.intellij.openapi.project.DumbService.isDumb(project)) return

        val generation = displayGeneration
        coroutineScope.launch(Dispatchers.Default) {
            val usedKeys = NacosConfigKeyReferenceSearcher.findUsedKeys(
                project, keys.keys,
                configIdentity = configuration.getKey(),
                configMd5 = configuration.md5
            )
            if (usedKeys.isEmpty()) return@launch

            ApplicationManager.getApplication().invokeLater({
                if (generation != displayGeneration || currentConfiguration?.getKey() != configuration.getKey()) {
                    return@invokeLater
                }
                if (editor !== ed || ed.isDisposed) return@invokeLater

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
            }, ModalityState.defaultModalityState())
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

   
    private fun updateMetadata(configuration: NacosConfiguration, generation: Long) {
        ApplicationManager.getApplication().invokeLater({
            if (generation != displayGeneration || currentConfiguration?.getKey() != configuration.getKey()) {
                return@invokeLater
            }
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
            dirtyLabel.isVisible = false
            editButton.isEnabled = true
            copyButton.isEnabled = true
            historyButton.isEnabled = true
        }, ModalityState.defaultModalityState())
    }
    
    private fun loadConfigurationContent(configuration: NacosConfiguration, forceRefresh: Boolean = false) {
        loadConfigurationContent(configuration, ++displayGeneration, forceRefresh)
    }

    private fun loadConfigurationContent(
        configuration: NacosConfiguration,
        generation: Long,
        forceRefresh: Boolean = false,
        keepCachedVisible: Boolean = false
    ) {
        if (isLoading) return
        
        // Cancel previous loading operation
        currentLoadingJob?.cancel()
        
        setLoadingState(true)
        if (!keepCachedVisible) showCard("loading")
        
        currentLoadingJob = coroutineScope.launch {
            try {
                val result = detailLoader.load(configuration, forceRefresh)
                
                // Check if operation was cancelled
                if (!isActive) return@launch
                if (generation != displayGeneration || currentConfiguration?.getKey() != configuration.getKey()) {
                    setLoadingState(false)
                    return@launch
                }
                
                result.onSuccess { configWithContent ->
                    configWithContent?.let { config ->
                        if (generation != displayGeneration || currentConfiguration?.getKey() != configuration.getKey()) {
                            setLoadingState(false)
                            return@let
                        }
                        displayConfigurationContentSafely(config)
                        currentConfiguration = config
                        setLoadingState(false)
                        showCard("content")
                        hideFreshnessStatus()
                        refreshNavigationState()
                        
                        // Update size information and status bar
                        ApplicationManager.getApplication().invokeLater({
                            val contentSize = config.content.length
                            sizeLabel.text = formatSize(contentSize)
                        }, ModalityState.defaultModalityState())
                        updateStatusBar(config.content)
                    } ?: run {
                        setLoadingState(false)
                        if (keepCachedVisible) {
                            cacheService.removeConfigDetail(
                                project.captureSelectedAccessIdentity(settings),
                                configuration.tenantId,
                                configuration.dataId,
                                configuration.group
                            )
                            showFreshnessStatus("config.detail.cache.deleted")
                            refreshNavigationState()
                        } else {
                            showCard("error")
                        }
                    }
                }.onFailure { error ->
                    if (isActive) {
                        handleLoadFailure(
                            keepCachedVisible,
                            "Failed to load configuration",
                            error.message ?: "Unknown error"
                        )
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    handleLoadFailure(
                        keepCachedVisible,
                        "Error loading configuration",
                        e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    private fun handleLoadFailure(keepCachedVisible: Boolean, title: String, message: String) {
        setLoadingState(false)
        if (keepCachedVisible) {
            showFreshnessStatus("config.detail.cache.refresh.failed")
        } else {
            showCard("error")
            showError(title, message)
        }
    }
    
    private fun displayConfigurationContentSafely(configuration: NacosConfiguration) {
        ApplicationManager.getApplication().invokeLater({
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
            
            val content = configuration.content
            val fileType = determineFileType(configuration)
            
            // Create new editor safely
            val newEditor = createEditorSafely(content, fileType)
           if (newEditor != null) {
              editor = newEditor
              updateEditorUI(newEditor)
              applyKeyGutterMarkers(configuration, newEditor)
              consumePendingNavigation(configuration)
               copyButton.isEnabled = true
               editButton.isEnabled = true
               historyButton.isEnabled = true
                editButton.isVisible = true
                editButton.text = NacosSearchBundle.message("config.detail.action.edit")
               saveButton.isEnabled = false
               saveButton.isVisible = false
               revertButton.isEnabled = false
               revertButton.isVisible = false
            }
        }, ModalityState.defaultModalityState())
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

                // Track content changes for dirty-state detection
                originalContent = content
                document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
                    override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                        checkDirtyState(document.text)
                    }
                })

                // Register with Disposer for proper cleanup
                Disposer.register(this) { EditorFactory.getInstance().releaseEditor(newEditor) }
                
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
        SwingUtilities.invokeLater {
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
        ApplicationManager.getApplication().invokeLater({
            isLoading = loading
            refreshButton.isEnabled = !loading
        }, ModalityState.defaultModalityState())
    }
    
    private fun showCard(cardName: String) {
        ApplicationManager.getApplication().invokeLater({
            val cardLayout = contentPanel.layout as CardLayout
            cardLayout.show(contentPanel, cardName)
        }, ModalityState.defaultModalityState())
    }

    private fun showFreshnessStatus(messageKey: String) {
        ApplicationManager.getApplication().invokeLater {
            freshnessLabel.text = NacosSearchBundle.message(messageKey)
            freshnessLabel.isVisible = true
        }
    }

    private fun hideFreshnessStatus() {
        ApplicationManager.getApplication().invokeLater {
            freshnessLabel.isVisible = false
        }
    }

    private fun refreshNavigationState() {
        ApplicationManager.getApplication()
            .getService(NavigationIndexRefreshService::class.java)
            .refresh(project.captureSelectedAccessIdentity(settings), project)
    }
    
    private fun showEmptyState() {
        showCard("empty")
        copyButton.isEnabled = false
    }
    
    private fun showError(title: String, message: String) {
        ApplicationManager.getApplication().invokeLater({
            JOptionPane.showMessageDialog(
                this,
                message,
                title,
                JOptionPane.ERROR_MESSAGE
            )
        }, ModalityState.defaultModalityState())
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
     * Save (publish) through the controlled [PublishController] path using the
     * edit session bound when entering edit mode.
     */
    private fun saveConfiguration() {
        val config = currentConfiguration ?: return
        val textToSave = editor?.document?.text ?: config.content
        if (textToSave == originalContent) return
        val profile = selectedProfile()
        if (profile == null || !profile.writeIntent) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                NacosSearchBundle.message("config.detail.publish.writes.disabled"),
                NacosSearchBundle.message("config.detail.action.save.publish")
            )
            return
        }
        coroutineScope.launch {
            try {
                val target = boundEditTarget ?: run {
                    val context = selectedOperationContext()
                        ?: return@launch showSaveError(NacosSearchBundle.message("error.connection.incomplete"))
                    val namespaceId = operationNamespaceId(config)
                    nacosApiService.resolveOperationTarget(context, namespaceId).getOrElse {
                        return@launch showSaveError(it.message ?: it.toString())
                    }
                }
                val namespaceId = operationNamespaceId(config)
                val confirm = withContext(Dispatchers.Main) {
                    com.intellij.openapi.ui.Messages.showYesNoDialog(
                        project,
                        NacosSearchBundle.message(
                            "config.detail.publish.confirm",
                            config.dataId,
                            config.group,
                            namespaceId,
                            target.context.endpoint.value
                        ),
                        NacosSearchBundle.message("config.detail.action.save.publish"),
                        com.intellij.openapi.ui.Messages.getQuestionIcon()
                    )
                }
                if (confirm != com.intellij.openapi.ui.Messages.YES) return@launch

                val session = EditSession(
                    target = target,
                    dataId = config.dataId,
                    group = config.group,
                    namespaceId = namespaceId,
                    baselineContent = originalContent,
                    baselineMd5 = config.md5,
                    baselineType = config.type,
                    baselineAppName = config.appName,
                    baselineDesc = config.desc,
                    baselineConfigTags = config.configTags,
                    draftContent = textToSave
                )
                val publishResult = nacosApiService.controlledPublish(session)
                withContext(Dispatchers.Main) {
                    when (val state = publishResult.state) {
                        is PublishState.Verified -> {
                            val verified = publishResult.verifiedDetail
                            originalContent = verified?.content ?: textToSave
                            if (verified != null) {
                                currentConfiguration = verified
                            }
                            boundEditTarget = null
                            exitEditMode()
                            saveButton.isEnabled = false
                            revertButton.isEnabled = false
                            updateDirtyUI(false)
                            updateStatusBar(originalContent)
                            com.intellij.openapi.ui.Messages.showInfoMessage(
                                NacosSearchBundle.message("message.configuration.saved"),
                                NacosSearchBundle.message("common.success")
                            )
                        }
                        is PublishState.RemoteConflict -> {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                NacosSearchBundle.message("config.detail.publish.conflict"),
                                NacosSearchBundle.message("common.error")
                            )
                        }
                        is PublishState.TargetDeleted -> {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                NacosSearchBundle.message("config.detail.publish.deleted"),
                                NacosSearchBundle.message("common.error")
                            )
                        }
                        is PublishState.PermissionDenied -> {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                NacosSearchBundle.message("config.detail.publish.permission"),
                                NacosSearchBundle.message("common.error")
                            )
                        }
                        is PublishState.ServerStateUnknown -> {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                NacosSearchBundle.message("config.detail.publish.unknown"),
                                NacosSearchBundle.message("common.error")
                            )
                        }
                        is PublishState.ReadOnly -> {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                NacosSearchBundle.message("config.detail.publish.readonly", state.reason),
                                NacosSearchBundle.message("common.error")
                            )
                        }
                        else -> {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                NacosSearchBundle.message("error.config.save.failed") + ": $state",
                                NacosSearchBundle.message("common.error")
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                showSaveError(e.message ?: e.toString())
            }
        }
    }

    private suspend fun showSaveError(message: String) {
        withContext(Dispatchers.Main) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                NacosSearchBundle.message("error.config.save.failed") + ": $message",
                NacosSearchBundle.message("common.error")
            )
        }
    }

    private fun openHistoryBrowser() {
        val config = currentConfiguration ?: return
        coroutineScope.launch {
            val context = selectedOperationContext()
            if (context == null) {
                withContext(Dispatchers.Main) {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        NacosSearchBundle.message("error.connection.incomplete"),
                        NacosSearchBundle.message("common.error")
                    )
                }
                return@launch
            }
            val namespaceId = operationNamespaceId(config)
            val target = nacosApiService.resolveOperationTarget(context, namespaceId).getOrElse {
                withContext(Dispatchers.Main) {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        it.message ?: it.toString(),
                        NacosSearchBundle.message("common.error")
                    )
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
                    gateway = nacosApiService.operationGateway(),
                    generationProvider = {
                        project.getService(com.nanyin.nacos.search.services.ProjectSessionEpochs::class.java)
                            ?.currentEpoch()
                            ?: displayGeneration
                    }
                ).show()
            }
        }
    }

    /** Whether the detail editor has unsaved edits (for retarget guards). */
    fun isDirty(): Boolean = isDirty

    // Legacy method for backward compatibility
    private fun disposeEditor() {
        disposeEditorSafely()
    }
    
    /**
     * Clear the current configuration display
     */
    fun clearConfiguration() {
        // Cancel any ongoing loading operation
        currentLoadingJob?.cancel()
        displayGeneration++
        setLoadingState(false)
        
        currentConfiguration = null
        disposeEditorSafely()
        showEmptyState()
        editButton.isVisible = true
        saveButton.isEnabled = false
        saveButton.isVisible = false
        copyButton.isEnabled = false
        editButton.isEnabled = false
        revertButton.isEnabled = false
        revertButton.isVisible = false
        historyButton.isEnabled = false
        dirtyLabel.isVisible = false

        // Reset status bar
        statusCharsLabel.text = NacosSearchBundle.message("config.detail.status.chars.format", 0)
        statusMd5Label.text = "—"
        statusPositionLabel.text = NacosSearchBundle.message("config.detail.status.position", 1, 1)
        
        ApplicationManager.getApplication().invokeLater({
            dataIdLabel.text = ""
            if (::inlineMetaLabel.isInitialized) {
                inlineMetaLabel.text = ""
            }
            sizeLabel.text = ""
        }, ModalityState.defaultModalityState())
    }
    
    /**
     * Get the current configuration
     */
    fun getCurrentConfiguration(): NacosConfiguration? = currentConfiguration
    
    /**
     * Refresh the current configuration
     */
    fun refresh() {
        currentConfiguration?.let { config ->
            loadConfigurationContent(config)
        }
    }
    
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
    override fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage) {
        // Refresh all UI text elements
        refreshUIText()
        
        // Rebuild metadata panel with new language
        rebuildMetadataPanel()
        
        // Rebuild empty state panel with new language
        rebuildEmptyStatePanel()
        
        // Rebuild error panel with new language
        rebuildErrorPanel()
        
        // Update button tooltips
        updateButtonTooltips()
        
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
            // Update loading text
            loadingLabel.text = NacosSearchBundle.message("config.detail.loading")
            
            // Update button tooltips
            refreshButton.toolTipText = NacosSearchBundle.message("config.detail.refresh")
            copyButton.toolTipText = NacosSearchBundle.message("config.detail.copy")
            
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
        SwingUtilities.invokeLater {
            // Remove old metadata panel
            remove(metadataPanel)
            
            // Create new metadata panel with updated language
            metadataPanel = createMetadataPanel()
            
            // Add to layout
            val topPanel = getComponent(0) as? JPanel
            topPanel?.add(metadataPanel, BorderLayout.CENTER)
            
            // Update metadata with current configuration
            currentConfiguration?.let { config ->
                updateMetadata(config, displayGeneration)
            }
        }
    }
    
    /**
     * Rebuild empty state panel with new language
     */
    private fun rebuildEmptyStatePanel() {
        SwingUtilities.invokeLater {
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
        SwingUtilities.invokeLater {
            // Remove old error panel
            contentPanel.remove(errorPanel)
            
            // Create new error panel with updated language
            errorPanel = createErrorPanel()
            
            // Add back to content panel
            contentPanel.add(errorPanel, "error")
        }
    }
    
    /**
     * Update button tooltips
     */
    private fun updateButtonTooltips() {
        SwingUtilities.invokeLater {
            refreshButton.toolTipText = NacosSearchBundle.message("config.detail.refresh")
            copyButton.toolTipText = NacosSearchBundle.message("config.detail.copy")
        }
    }
}
