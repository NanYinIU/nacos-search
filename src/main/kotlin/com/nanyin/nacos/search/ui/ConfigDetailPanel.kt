package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.services.LanguageService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.Disposable
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.NacosApiService
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.swing.*
import kotlin.concurrent.withLock


/**
 * Panel for displaying configuration details with syntax highlighting
 */
class ConfigDetailPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, LanguageAwareComponent {
    
    private val nacosApiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    private val languageService = ApplicationManager.getApplication().getService(LanguageService::class.java)
    
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
    private lateinit var formatTagLabel: JBLabel
    private lateinit var dirtyLabel: JBLabel
    
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
        showEmptyState()
    }
    
    private fun initializeComponents() {
        // Metadata panel components
        dataIdLabel = JTextField()
        dataIdLabel.isEditable = false
        dataIdLabel.border = null
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
        
        // Action buttons
        refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = NacosSearchBundle.message("config.detail.refresh")
            preferredSize = Dimension(26, 26)
            minimumSize = Dimension(26, 26)
            border = JBUI.Borders.empty()
            isContentAreaFilled = false
        }
        
        copyButton = JButton(NacosSearchBundle.message("config.detail.action.copy")).apply {
            toolTipText = NacosSearchBundle.message("config.detail.copy")
            icon = AllIcons.Actions.Copy
            preferredSize = Dimension(68, 26)
            minimumSize = Dimension(60, 26)
            isEnabled = false
        }

       // Save action button (publishes content back to Nacos)
       saveButton = JButton(NacosSearchBundle.message("config.detail.action.save.publish")).apply {
          toolTipText = NacosSearchBundle.message("config.detail.save")
          putClientProperty("JButton.buttonType", "primary")
           preferredSize = Dimension(120, 26)
            minimumSize = Dimension(100, 26)
           isEnabled = false
       }

       // Edit button — toggles editor editable mode
       editButton = JButton(NacosSearchBundle.message("config.detail.action.edit")).apply {
           toolTipText = NacosSearchBundle.message("config.detail.action.edit")
            preferredSize = Dimension(72, 26)
            minimumSize = Dimension(60, 26)
           isEnabled = false
       }

       // Revert button — discards unsaved edits
       revertButton = JButton(NacosSearchBundle.message("config.detail.action.revert")).apply {
           toolTipText = NacosSearchBundle.message("config.detail.action.revert")
            preferredSize = Dimension(72, 26)
            minimumSize = Dimension(60, 26)
           isEnabled = false
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
            border = JBUI.Borders.empty(6, 10, 5, 10)

            // Title row: dataId (bold 13px) + dirty pill on right
            val titleRow = JPanel(BorderLayout()).apply {
                add(dataIdLabel.apply {
                    font = font.deriveFont(Font.BOLD, 13f)
                    border = null
                }, BorderLayout.CENTER)
                add(dirtyLabel, BorderLayout.EAST)
            }
            add(titleRow)

            // Metadata row (group, namespace, type, size) - compact inline
            add(metadataPanel)
        }

        // ===== Action bar: format tag | edit + save + revert | copy =====
        val actionBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 10)
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(formatTagLabel)
            }
            // Design order: Edit / Save & Publish / Revert ... Copy
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(refreshButton)
                add(editButton)
                add(saveButton)
                add(revertButton)
                add(copyButton)
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
      editor?.let { ed ->
          ed.document.setReadOnly(false)
          editButton.isVisible = false
          checkDirtyState(ed.document.text)
      }
  }

    /**
     * Restores the read-only view mode: locks the editor and re-shows the Edit button.
     */
    private fun exitEditMode() {
        editor?.document?.setReadOnly(true)
        editButton.isVisible = true
        editButton.text = NacosSearchBundle.message("config.detail.action.edit")
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
        val generation = ++displayGeneration
        currentConfiguration = configuration
        updateMetadata(configuration, generation)
        loadConfigurationContent(configuration, generation)
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
        }, ModalityState.defaultModalityState())
    }
    
    private fun loadConfigurationContent(configuration: NacosConfiguration, forceRefresh: Boolean = false) {
        loadConfigurationContent(configuration, ++displayGeneration, forceRefresh)
    }

    private fun loadConfigurationContent(configuration: NacosConfiguration, generation: Long, forceRefresh: Boolean = false) {
        if (isLoading) return
        
        // Cancel previous loading operation
        currentLoadingJob?.cancel()
        
        setLoadingState(true)
        showCard("loading")
        
        currentLoadingJob = coroutineScope.launch {
            try {
                val result = nacosApiService.getConfiguration(
                    dataId = configuration.dataId,
                    group = configuration.group,
                    namespaceId = configuration.tenantId,
                    useCache = true,
                    forceRefresh = forceRefresh
                )
                
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
                        setLoadingState(false)
                        showCard("content")
                        
                        // Update size information and status bar
                        ApplicationManager.getApplication().invokeLater({
                            val contentSize = config.content.length
                            sizeLabel.text = formatSize(contentSize)
                        }, ModalityState.defaultModalityState())
                        updateStatusBar(config.content)
                    } ?: run {
                        setLoadingState(false)
                        showCard("error")
                    }
                }.onFailure { error ->
                    if (isActive) {
                        setLoadingState(false)
                        showCard("error")
                        showError("Failed to load configuration", error.message ?: "Unknown error")
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    setLoadingState(false)
                    showCard("error")
                    showError("Error loading configuration", e.message ?: "Unknown error")
                }
            }
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
               copyButton.isEnabled = true
               editButton.isEnabled = true
                editButton.isVisible = true
                editButton.text = NacosSearchBundle.message("config.detail.action.edit")
               saveButton.isEnabled = false
               revertButton.isEnabled = false
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
        // Configure editor settings
        val settings = ed.settings
        settings.isLineNumbersShown = true
        settings.isLineMarkerAreaShown = false
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
     * Save (publish) the current configuration content back to Nacos.
     */
    private fun saveConfiguration() {
        val config = currentConfiguration ?: return
        val textToSave = editor?.document?.text ?: config.content
        if (textToSave == originalContent) return
        coroutineScope.launch {
            try {
                val namespaceId = if (config.tenantId.isNullOrBlank()) null else config.tenantId
                val result = nacosApiService.publishConfiguration(
                    config.dataId, config.group, textToSave, config.type ?: "text", namespaceId
                )
                withContext(Dispatchers.Main) {
                    if (result.isSuccess && result.getOrNull() == true) {
                       originalContent = textToSave
                        exitEditMode()
                        saveButton.isEnabled = false
                        revertButton.isEnabled = false
                        updateDirtyUI(false)
                        updateStatusBar(textToSave)
                        com.intellij.openapi.ui.Messages.showInfoMessage(
                            NacosSearchBundle.message("message.configuration.saved"),
                            NacosSearchBundle.message("common.success")
                        )
                    } else {
                        com.intellij.openapi.ui.Messages.showErrorDialog(
                            NacosSearchBundle.message("error.config.save.failed") + ": " +
                                (result.exceptionOrNull()?.message ?: ""),
                            NacosSearchBundle.message("common.error")
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        NacosSearchBundle.message("error.config.save.failed") + ": ${e.message}",
                        NacosSearchBundle.message("common.error")
                    )
                }
            }
        }
    }

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
        saveButton.isEnabled = false
        copyButton.isEnabled = false
        editButton.isEnabled = false
        revertButton.isEnabled = false
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
