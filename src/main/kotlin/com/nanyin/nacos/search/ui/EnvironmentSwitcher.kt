package com.nanyin.nacos.search.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.ui.JBUI
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.settings.NacosConfigurable
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.NacosProjectSession
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.plaf.basic.BasicButtonUI

/**
 * Header control for quickly switching the active Nacos environment
 * (dev / sit / uat ...) without opening the settings dialog.
 *
 * Renders the active environment name and opens a [ListPopup] with all
 * configured servers (the active one checked) plus a "Manage connections…"
 * entry that opens [NacosConfigurable].
 */
class EnvironmentSwitcher(
    private val project: Project,
    private val settings: NacosSettings =
        ApplicationManager.getApplication().getService(NacosSettings::class.java),
    private val projectSession: NacosProjectSession? = project.getService(NacosProjectSession::class.java),
    private val languageService: LanguageService =
        ApplicationManager.getApplication().getService(LanguageService::class.java)
) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)), LanguageAwareComponent {

    /** Local callback: selecting an environment must not broadcast global state. */
    var onSelectionChanged: ((String) -> Unit)? = null

    private val envButton: JButton = JButton().apply {
        putClientProperty("nacos.automation.id", "nacos.toolwindow.envSwitcher")
        putClientProperty("JButton.buttonType", "toolbar")
        ui = BasicButtonUI()
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        border = JBUI.Borders.empty(2, 4, 2, 2)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.LEFT
        horizontalTextPosition = SwingConstants.TRAILING
        verticalTextPosition = SwingConstants.CENTER
        iconTextGap = 4
        font = com.intellij.util.ui.UIUtil.getFontWithFallback("JetBrains Mono", Font.PLAIN, 12)
        addActionListener { showPopup() }
    }

    private val caretLabel: JLabel = JLabel(AllIcons.General.ArrowDown).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(0, 0, 0, 2)
        preferredSize = Dimension(16, ENV_BUTTON_HEIGHT)
        minimumSize = Dimension(16, ENV_BUTTON_HEIGHT)
        maximumSize = Dimension(16, ENV_BUTTON_HEIGHT)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = showPopup()
        })
    }

    init {
        isOpaque = false
        add(envButton)
        add(caretLabel)
        refresh()
    }

    /**
     * Re-reads the active server from settings and updates the button label.
     * Call after settings change (e.g. switching environment or applying settings).
     */
    fun refresh() {
        projectSession?.seedIfNew(settings.migrationDefaults())
        val selectedProfileId = projectSession?.sessionState?.selectedProfileId.orEmpty().ifBlank { settings.activeServerId }
        val active = settings.cloneServers().firstOrNull { it.id == selectedProfileId } ?: settings.getActiveServer()
        val name = active.displayName.ifBlank {
            active.serverUrl.ifBlank { NacosSearchBundle.message("toolwindow.env.none") }
        }
        envButton.icon = null
        envButton.text = name
        envButton.toolTipText = NacosSearchBundle.message("toolwindow.env.switcher.tooltip", name)
        updateButtonWidthForContent()
        revalidate()
        repaint()
    }

    private fun showPopup() {
        if (!envButton.isShowing) return
        projectSession?.seedIfNew(settings.migrationDefaults())
        val activeId = projectSession?.sessionState?.selectedProfileId.orEmpty().ifBlank { settings.activeServerId }
        val entries = settings.cloneServers()
            .map { EnvEntry.Server(it, it.id == activeId) } + EnvEntry.Manage
        val popup = JBPopupFactory.getInstance().createListPopup(EnvListStep(entries))
        popup.setMinimumSize(Dimension(240, 0))
        popup.showUnderneathOf(envButton)
    }

    private fun handleSelectServer(entry: EnvEntry.Server) {
        if (entry.active) return
        if (!entry.config.isValidUrl()) {
            val name = entry.config.displayName.ifBlank { entry.config.serverUrl }
            val choice = Messages.showDialog(
                this,
                NacosSearchBundle.message("toolwindow.env.invalid", name),
                NacosSearchBundle.message("settings.invalid.title"),
                arrayOf(
                    NacosSearchBundle.message("common.settings"),
                    NacosSearchBundle.message("common.cancel")
                ),
                1,
                Messages.getErrorIcon()
            )
            if (choice == 0) openSettings()
            return
        }
        val namespace = projectSession?.sessionState?.namespaceId.orEmpty().ifBlank { "public" }
        projectSession?.select(entry.config.id, namespace)
        onSelectionChanged?.invoke(entry.config.id)
        refresh()
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().editConfigurable(project, NacosConfigurable())
    }

    private inner class EnvListStep(private val items: List<EnvEntry>) :
        BaseListPopupStep<EnvEntry>(null, items) {
        override fun getTextFor(value: EnvEntry): String = value.displayText()
        override fun getIconFor(value: EnvEntry): Icon? = when (value) {
            is EnvEntry.Server -> if (value.active) AllIcons.Actions.Checked else null
            is EnvEntry.Manage -> AllIcons.General.Settings
        }
        // Draw a separator above the "Manage connections" entry.
        override fun getSeparatorAbove(value: EnvEntry): ListSeparator? =
            if (value is EnvEntry.Manage) ListSeparator() else null
        override fun getDefaultOptionIndex(): Int =
            items.indexOfFirst { it is EnvEntry.Server && it.active }.coerceAtLeast(0)
        override fun onChosen(selectedValue: EnvEntry?, finalChoice: Boolean): PopupStep<*>? {
            return when (selectedValue) {
                is EnvEntry.Server -> { handleSelectServer(selectedValue); null }
                is EnvEntry.Manage -> { openSettings(); null }
                null -> null
            }
        }
    }

    private sealed class EnvEntry {
        abstract fun displayText(): String
        class Server(val config: NacosServerConfig, val active: Boolean) : EnvEntry() {
            override fun displayText(): String =
                config.displayName.ifBlank { config.serverUrl }
        }
        object Manage : EnvEntry() {
            override fun displayText(): String =
                NacosSearchBundle.message("toolwindow.env.manage")
        }
    }

    override fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage) = refresh()
    override fun getLanguageService(): LanguageService = languageService

    private fun updateButtonWidthForContent() {
        val metrics = envButton.getFontMetrics(envButton.font)
        val naturalWidth = metrics.stringWidth(envButton.text) + 10
        val width = naturalWidth.coerceIn(ENV_BUTTON_MIN_WIDTH, ENV_BUTTON_MAX_WIDTH)
        envButton.minimumSize = Dimension(ENV_BUTTON_MIN_WIDTH, ENV_BUTTON_HEIGHT)
        envButton.preferredSize = Dimension(width, ENV_BUTTON_HEIGHT)
        envButton.maximumSize = Dimension(ENV_BUTTON_MAX_WIDTH, ENV_BUTTON_HEIGHT)
    }

    companion object {
        private const val ENV_BUTTON_MIN_WIDTH = 34
        private const val ENV_BUTTON_MAX_WIDTH = 240
        private const val ENV_BUTTON_HEIGHT = 28
    }
}
