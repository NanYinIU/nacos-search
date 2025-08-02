package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.services.NacosApiService
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
import java.awt.FlowLayout
import javax.swing.*

/**
 * Configurable for Nacos plugin settings
 */
class NacosConfigurable : Configurable {
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val apiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
    
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
    
    private var mainPanel: JPanel? = null
    
    override fun getDisplayName(): String = "Nacos Search"
    
    override fun createComponent(): JComponent {
        initializeComponents()
        
        mainPanel = panel {
            titledRow("Server Configuration") {
                row("Server URL:") {
                    cell {
                        serverUrlField()
                        testConnectionButton()
                    }
                }
                row("Username:") { usernameField() }
                row("Password:") { passwordField() }
                row("Namespace:") { namespaceField() }
            }
            
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
                (retryDelaySpinner.value as Int) != settings.retryDelaySeconds
    }
    
    override fun apply() {
        // Validate settings before applying
        val tempSettings = settings.copy()
        updateTempSettings(tempSettings)
        
        val errors = tempSettings.validate()
        if (errors.isNotEmpty()) {
            Messages.showErrorDialog(
                "Settings validation failed:\n${errors.joinToString("\n")}",
                "Invalid Settings"
            )
            return
        }
        
        // Apply settings
        updateSettings()
        
        Messages.showInfoMessage(
            "Settings have been saved successfully.",
            "Settings Saved"
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
        
        testConnectionButton = JButton("Test Connection")
        resetButton = JButton("Reset to Defaults")
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
                "Are you sure you want to reset all settings to defaults?",
                "Reset Settings",
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
                "Cannot test connection with invalid settings:\n${errors.joinToString("\n")}",
                "Invalid Settings"
            )
            return
        }
        
        // Temporarily update settings for connection test
        val originalSettings = settings.copy()
        updateSettings()
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Testing Connection...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting to Nacos server..."
                
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                scope.launch {
                    var result: Result<Boolean>? = null
                    try {
                        result = apiService.testConnection()
                        
                        withContext(Dispatchers.EDT) {
                            if (result.isSuccess) {
                                Messages.showInfoMessage(
                                    "Connection successful!",
                                    "Connection Test"
                                )
                            } else {
                                Messages.showErrorDialog(
                                    "Connection failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                                    "Connection Test Failed"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.EDT) {
                            Messages.showErrorDialog(
                                "Connection test failed: ${e.message}",
                                "Connection Test Failed"
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