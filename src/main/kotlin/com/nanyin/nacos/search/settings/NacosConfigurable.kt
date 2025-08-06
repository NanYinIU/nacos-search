package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.*
import com.intellij.ui.layout.panel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.*

/**
 * Configurable for Nacos plugin settings
 */
class NacosConfigurable : Configurable {
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val apiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    private val languageService = ApplicationManager.getApplication().getService(LanguageService::class.java)
    
    // UI Components
    private lateinit var serverUrlField: JBTextField
    private lateinit var usernameField: JBTextField
    private lateinit var passwordField: JBPasswordField
    private lateinit var namespaceField: JBTextField
    
    // Authentication Components
    private lateinit var authModeComboBox: JComboBox<AuthMode>
    private lateinit var enableTokenAuthCheckBox: JBCheckBox
    private lateinit var tokenCacheDurationSpinner: JSpinner
    private lateinit var autoTokenRefreshCheckBox: JBCheckBox
    
    private lateinit var cacheEnabledCheckBox: JBCheckBox
    private lateinit var cacheTtlSpinner: JSpinner
    private lateinit var maxCacheSizeSpinner: JSpinner
    private lateinit var autoRefreshCheckBox: JBCheckBox
    private lateinit var autoRefreshIntervalSpinner: JSpinner
    
    private lateinit var searchResultLimitSpinner: JSpinner
    private lateinit var enableRegexCheckBox: JBCheckBox
    private lateinit var caseSensitiveCheckBox: JBCheckBox
    private lateinit var highlightMatchesCheckBox: JBCheckBox
    
    private lateinit var connectionTimeoutSpinner: JSpinner
    private lateinit var readTimeoutSpinner: JSpinner
    private lateinit var retryAttemptsSpinner: JSpinner
    private lateinit var retryDelaySpinner: JSpinner
    
    private lateinit var testConnectionButton: JButton
    private lateinit var resetButton: JButton
    
    // Language selection components
    private lateinit var languageComboBox: JComboBox<LanguageService.SupportedLanguage>
    
    private var mainPanel: JPanel? = null
    
    override fun getDisplayName(): String = NacosSearchBundle.message("settings.title")
    
    override fun createComponent(): JComponent {
        initializeComponents()
        
        mainPanel = panel {
            titledRow(NacosSearchBundle.message("settings.server.config")) {
                row(NacosSearchBundle.message("settings.server.url")) {
                    cell {
                        serverUrlField()
                        testConnectionButton()
                    }
                }
                row(NacosSearchBundle.message("settings.server.username")) { usernameField() }
                row(NacosSearchBundle.message("settings.server.password")) { passwordField() }
                row(NacosSearchBundle.message("settings.server.namespace")) { namespaceField() }
            }
            
//            titledRow(NacosSearchBundle.message("settings.language.config")) {
//                row(NacosSearchBundle.message("settings.language.selection")) { languageComboBox() }
//            }
            
//            titledRow("Authentication Configuration") {
//                row("Authentication Mode:") { authModeComboBox() }
//                row { enableTokenAuthCheckBox() }
//                row("Token Cache Duration (minutes):") { tokenCacheDurationSpinner() }
//                row { autoTokenRefreshCheckBox() }
//            }
//
//            titledRow("Cache Configuration") {
//                row { cacheEnabledCheckBox() }
//                row("Cache TTL (minutes):") { cacheTtlSpinner() }
//                row("Max Cache Size:") { maxCacheSizeSpinner() }
//                row { autoRefreshCheckBox() }
//                row("Auto Refresh Interval (minutes):") { autoRefreshIntervalSpinner() }
//            }
//
//            titledRow("Search Configuration") {
//                row("Search Result Limit:") { searchResultLimitSpinner() }
//                row { enableRegexCheckBox() }
//                row { caseSensitiveCheckBox() }
//                row { highlightMatchesCheckBox() }
//            }
            
//            titledRow("Connection Configuration") {
//                row("Connection Timeout (seconds):") { connectionTimeoutSpinner() }
//                row("Read Timeout (seconds):") { readTimeoutSpinner() }
//                row("Retry Attempts:") { retryAttemptsSpinner() }
//                row("Retry Delay (seconds):") { retryDelaySpinner() }
//            }
//
            row {
                cell {
                    resetButton()
                }
            }
        }
        
        loadSettings()
        setupEventHandlers()
        
        return JBScrollPane(mainPanel).apply {
            border = JBUI.Borders.empty(10)
        }
    }
    
    override fun isModified(): Boolean {
        val selectedLanguage = languageComboBox.selectedItem as LanguageService.SupportedLanguage
        return serverUrlField.text != settings.serverUrl ||
                usernameField.text != settings.username ||
                String(passwordField.password) != settings.password ||
                namespaceField.text != settings.namespace ||
                authModeComboBox.selectedItem != settings.authMode ||
                enableTokenAuthCheckBox.isSelected != settings.enableTokenAuth ||
                (tokenCacheDurationSpinner.value as Int) != settings.tokenCacheDurationMinutes ||
                autoTokenRefreshCheckBox.isSelected != settings.autoTokenRefresh ||
                cacheEnabledCheckBox.isSelected != settings.cacheEnabled ||
                (cacheTtlSpinner.value as Int) != settings.cacheTtlMinutes ||
                (maxCacheSizeSpinner.value as Int) != settings.maxCacheSize ||
                autoRefreshCheckBox.isSelected != settings.autoRefreshEnabled ||
                (autoRefreshIntervalSpinner.value as Int) != settings.autoRefreshIntervalMinutes ||
                (searchResultLimitSpinner.value as Int) != settings.searchResultLimit ||
                enableRegexCheckBox.isSelected != settings.enableRegexSearch ||
                caseSensitiveCheckBox.isSelected != settings.caseSensitiveSearch ||
                highlightMatchesCheckBox.isSelected != settings.highlightMatches ||
                (connectionTimeoutSpinner.value as Int) != settings.connectionTimeoutSeconds ||
                (readTimeoutSpinner.value as Int) != settings.readTimeoutSeconds ||
                (retryAttemptsSpinner.value as Int) != settings.retryAttempts ||
                (retryDelaySpinner.value as Int) != settings.retryDelaySeconds ||
                selectedLanguage.code != settings.language
    }
    
    override fun apply() {
        // Validate settings before applying
        val tempSettings = settings.copy()
        updateTempSettings(tempSettings)
        
        val errors = tempSettings.validate()
        if (errors.isNotEmpty()) {
            Messages.showErrorDialog(
                NacosSearchBundle.message("settings.validation.failed", errors.joinToString("\n")),
                NacosSearchBundle.message("settings.invalid.title")
            )
            return
        }
        
        // Apply settings
        updateSettings()
        
        Messages.showInfoMessage(
            NacosSearchBundle.message("settings.saved.success"),
            NacosSearchBundle.message("settings.saved.title")
        )
    }
    
    override fun reset() {
        loadSettings()
    }
    
    private fun initializeComponents() {
        serverUrlField = JBTextField(30)
        usernameField = JBTextField(20)
        passwordField = JBPasswordField()
        namespaceField = JBTextField(20)
        
        // Authentication components
        authModeComboBox = JComboBox(AuthMode.values())
        enableTokenAuthCheckBox = JBCheckBox("Enable Token Authentication")
        tokenCacheDurationSpinner = JSpinner(SpinnerNumberModel(30, 1, 1440, 5))
        autoTokenRefreshCheckBox = JBCheckBox("Auto refresh tokens")
        
        cacheEnabledCheckBox = JBCheckBox("Enable caching")
        cacheTtlSpinner = JSpinner(SpinnerNumberModel(5, 1, 60, 1))
        maxCacheSizeSpinner = JSpinner(SpinnerNumberModel(1000, 10, 10000, 100))
        autoRefreshCheckBox = JBCheckBox("Enable auto refresh")
        autoRefreshIntervalSpinner = JSpinner(SpinnerNumberModel(10, 1, 120, 1))
        
        searchResultLimitSpinner = JSpinner(SpinnerNumberModel(100, 1, 1000, 10))
        enableRegexCheckBox = JBCheckBox("Enable regex search")
        caseSensitiveCheckBox = JBCheckBox("Case sensitive search")
        highlightMatchesCheckBox = JBCheckBox("Highlight search matches")
        
        connectionTimeoutSpinner = JSpinner(SpinnerNumberModel(10, 1, 60, 1))
        readTimeoutSpinner = JSpinner(SpinnerNumberModel(30, 1, 120, 1))
        retryAttemptsSpinner = JSpinner(SpinnerNumberModel(3, 0, 10, 1))
        retryDelaySpinner = JSpinner(SpinnerNumberModel(2, 0, 10, 1))
        
        testConnectionButton = JButton(NacosSearchBundle.message("settings.test.connection"))
        resetButton = JButton(NacosSearchBundle.message("settings.reset.defaults"))
        
        // Language selection components
        languageComboBox = JComboBox(languageService.getSupportedLanguages().toTypedArray()).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is LanguageService.SupportedLanguage) {
                        text = value.displayName
                    }
                    return this
                }
            }
        }
    }
    
    private fun loadSettings() {
        serverUrlField.text = settings.serverUrl
        usernameField.text = settings.username
        passwordField.text = settings.password
        namespaceField.text = settings.namespace
        
        // Authentication settings
        authModeComboBox.selectedItem = settings.authMode
        enableTokenAuthCheckBox.isSelected = settings.enableTokenAuth
        tokenCacheDurationSpinner.value = settings.tokenCacheDurationMinutes
        autoTokenRefreshCheckBox.isSelected = settings.autoTokenRefresh
        
        cacheEnabledCheckBox.isSelected = settings.cacheEnabled
        cacheTtlSpinner.value = settings.cacheTtlMinutes
        maxCacheSizeSpinner.value = settings.maxCacheSize
        autoRefreshCheckBox.isSelected = settings.autoRefreshEnabled
        autoRefreshIntervalSpinner.value = settings.autoRefreshIntervalMinutes
        
        searchResultLimitSpinner.value = settings.searchResultLimit
        enableRegexCheckBox.isSelected = settings.enableRegexSearch
        caseSensitiveCheckBox.isSelected = settings.caseSensitiveSearch
        highlightMatchesCheckBox.isSelected = settings.highlightMatches
        
        connectionTimeoutSpinner.value = settings.connectionTimeoutSeconds
        readTimeoutSpinner.value = settings.readTimeoutSeconds
        retryAttemptsSpinner.value = settings.retryAttempts
        retryDelaySpinner.value = settings.retryDelaySeconds
        
        // Load language setting
        val currentLanguage = languageService.getCurrentLanguage()
        languageComboBox.selectedItem = currentLanguage
        
        // Update authentication components state
        updateAuthenticationComponentsState()
    }
    
    private fun updateSettings() {
        settings.serverUrl = serverUrlField.text.trim()
        settings.username = usernameField.text.trim()
        settings.password = String(passwordField.password)
        settings.namespace = namespaceField.text.trim()
        
        // Authentication settings
        settings.authMode = authModeComboBox.selectedItem as AuthMode
        settings.enableTokenAuth = enableTokenAuthCheckBox.isSelected
        settings.tokenCacheDurationMinutes = tokenCacheDurationSpinner.value as Int
        settings.autoTokenRefresh = autoTokenRefreshCheckBox.isSelected
        
        settings.cacheEnabled = cacheEnabledCheckBox.isSelected
        settings.cacheTtlMinutes = cacheTtlSpinner.value as Int
        settings.maxCacheSize = maxCacheSizeSpinner.value as Int
        settings.autoRefreshEnabled = autoRefreshCheckBox.isSelected
        settings.autoRefreshIntervalMinutes = autoRefreshIntervalSpinner.value as Int
        
        settings.searchResultLimit = searchResultLimitSpinner.value as Int
        settings.enableRegexSearch = enableRegexCheckBox.isSelected
        settings.caseSensitiveSearch = caseSensitiveCheckBox.isSelected
        settings.highlightMatches = highlightMatchesCheckBox.isSelected
        
        settings.connectionTimeoutSeconds = connectionTimeoutSpinner.value as Int
        settings.readTimeoutSeconds = readTimeoutSpinner.value as Int
        settings.retryAttempts = retryAttemptsSpinner.value as Int
        settings.retryDelaySeconds = retryDelaySpinner.value as Int
        
        // Update language setting
        val selectedLanguage = languageComboBox.selectedItem as LanguageService.SupportedLanguage
        languageService.setLanguage(selectedLanguage.code)
    }
    
    private fun updateTempSettings(tempSettings: NacosSettings) {
        tempSettings.serverUrl = serverUrlField.text.trim()
        tempSettings.username = usernameField.text.trim()
        tempSettings.password = String(passwordField.password)
        tempSettings.namespace = namespaceField.text.trim()
        
        // Authentication settings
        tempSettings.authMode = authModeComboBox.selectedItem as AuthMode
        tempSettings.enableTokenAuth = enableTokenAuthCheckBox.isSelected
        tempSettings.tokenCacheDurationMinutes = tokenCacheDurationSpinner.value as Int
        tempSettings.autoTokenRefresh = autoTokenRefreshCheckBox.isSelected
        tempSettings.cacheTtlMinutes = cacheTtlSpinner.value as Int
        tempSettings.maxCacheSize = maxCacheSizeSpinner.value as Int
        tempSettings.autoRefreshIntervalMinutes = autoRefreshIntervalSpinner.value as Int
        tempSettings.searchResultLimit = searchResultLimitSpinner.value as Int
        tempSettings.connectionTimeoutSeconds = connectionTimeoutSpinner.value as Int
        tempSettings.readTimeoutSeconds = readTimeoutSpinner.value as Int
        tempSettings.retryAttempts = retryAttemptsSpinner.value as Int
        tempSettings.retryDelaySeconds = retryDelaySpinner.value as Int
    }
    
    private fun setupEventHandlers() {
        testConnectionButton.addActionListener {
            testConnection()
        }
        
        resetButton.addActionListener {
            val result = Messages.showYesNoDialog(
                NacosSearchBundle.message("settings.reset.confirm"),
                NacosSearchBundle.message("settings.reset.title"),
                Messages.getQuestionIcon()
            )
            
            if (result == Messages.YES) {
                settings.resetToDefaults()
                loadSettings()
            }
        }
        
        // Enable/disable dependent components
        cacheEnabledCheckBox.addActionListener {
            val enabled = cacheEnabledCheckBox.isSelected
            cacheTtlSpinner.isEnabled = enabled
            maxCacheSizeSpinner.isEnabled = enabled
            autoRefreshCheckBox.isEnabled = enabled
            autoRefreshIntervalSpinner.isEnabled = enabled && autoRefreshCheckBox.isSelected
        }
        
        autoRefreshCheckBox.addActionListener {
            autoRefreshIntervalSpinner.isEnabled = autoRefreshCheckBox.isSelected && cacheEnabledCheckBox.isSelected
        }
        
        // Authentication event handlers
        authModeComboBox.addActionListener {
            updateAuthenticationComponentsState()
        }
        
        enableTokenAuthCheckBox.addActionListener {
            updateAuthenticationComponentsState()
        }
    }
    
    private fun updateAuthenticationComponentsState() {
        val authMode = authModeComboBox.selectedItem as AuthMode
        val tokenAuthEnabled = enableTokenAuthCheckBox.isSelected
        
        when (authMode) {
            AuthMode.BASIC -> {
                enableTokenAuthCheckBox.isEnabled = false
                enableTokenAuthCheckBox.isSelected = false
                tokenCacheDurationSpinner.isEnabled = false
                autoTokenRefreshCheckBox.isEnabled = false
            }
            AuthMode.TOKEN -> {
                enableTokenAuthCheckBox.isEnabled = false
                enableTokenAuthCheckBox.isSelected = true
                tokenCacheDurationSpinner.isEnabled = true
                autoTokenRefreshCheckBox.isEnabled = true
            }
            AuthMode.HYBRID -> {
                enableTokenAuthCheckBox.isEnabled = true
                tokenCacheDurationSpinner.isEnabled = tokenAuthEnabled
                autoTokenRefreshCheckBox.isEnabled = tokenAuthEnabled
            }
        }
    }
    
    private fun testConnection() {
        val tempSettings = settings.copy()
        updateTempSettings(tempSettings)
        
        val errors = tempSettings.validate()
        if (errors.isNotEmpty()) {
            Messages.showErrorDialog(
                NacosSearchBundle.message("settings.test.invalid", errors.joinToString("\n")),
                NacosSearchBundle.message("settings.invalid.title")
            )
            return
        }
        
        // Temporarily update settings for connection test
        val originalSettings = settings.copy()
        updateSettings()
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, NacosSearchBundle.message("settings.test.progress"), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = NacosSearchBundle.message("settings.test.connecting")
                
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                scope.launch {
                    var result: Result<Boolean>? = null
                    try {
                        result = apiService.testConnection()
                        
                        withContext(Dispatchers.EDT) {
                            if (result.isSuccess) {
                                Messages.showInfoMessage(
                                    NacosSearchBundle.message("settings.test.success"),
                                    NacosSearchBundle.message("settings.test.title")
                                )
                            } else {
                                Messages.showErrorDialog(
                                    NacosSearchBundle.message("settings.test.failed", result.exceptionOrNull()?.message ?: NacosSearchBundle.message("error.unknown")),
                                    NacosSearchBundle.message("settings.test.failed.title")
                                )
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.EDT) {
                            Messages.showErrorDialog(
                                NacosSearchBundle.message("settings.test.error", e.message ?: NacosSearchBundle.message("error.unknown")),
                                NacosSearchBundle.message("settings.test.failed.title")
                            )
                        }
                    } finally {
                        // Restore original settings if test failed
                        if (result?.isSuccess != true) {
                            withContext(Dispatchers.EDT) {
                                settings.loadState(originalSettings)
                            }
                        }
                    }
                }
            }
        })
    }
}