package com.nanyin.nacos.search.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.ui.JBUI
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.services.NacosLanguageListener
import com.nanyin.nacos.search.services.operations.DraftGuard
import com.nanyin.nacos.search.services.operations.EditSessionService
import com.nanyin.nacos.search.settings.NacosConfigurable
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.PublishedEnvironment
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
import com.intellij.openapi.application.ModalityState
import com.nanyin.nacos.search.Edt
/**
 * Header control for quickly switching the active Nacos environment
 * (dev / sit / uat ...) without opening the settings dialog.
 *
 * Renders the active environment name and opens a [ListPopup] with all
 * published environments (the selected one checked) plus a "Manage connections…"
 * entry that opens [NacosConfigurable].
 */
class EnvironmentSwitcher(
    private val project: Project,
    private val settings: NacosSettings =
        ApplicationManager.getApplication().getService(NacosSettings::class.java),
    private val projectSession: NacosProjectSession? = project.getService(NacosProjectSession::class.java)
) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)), NacosLanguageListener, Disposable {

    /** Local callback: selecting an environment must not broadcast global state. */
    var onSelectionChanged: ((String) -> Unit)? = null

    private val envButton: JButton = JButton().apply {
        putClientProperty("nacos.automation.id", "nacos.toolwindow.envSwitcher")
        putClientProperty("JButton.buttonType", "toolbar")
        setUI(BasicButtonUI())
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
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(NacosLanguageListener.TOPIC, this)
    }

    /**
     * Re-reads the project-selected published environment and updates the label.
     * Call after settings change (e.g. switching environment or applying settings).
     */
    fun refresh() {
        projectSession?.ensureInitialized(settings)
        val selectedProfileId = projectSession?.sessionState?.selectedProfileId
            ?: settings.resolveDefaultProfileId()
        val active = settings.publishedEnvironment(selectedProfileId)
        val name = when {
            active != null -> active.displayName.ifBlank {
                active.canonicalEndpoint.ifBlank { NacosSearchBundle.message("toolwindow.env.none") }
            }
            selectedProfileId.isNotBlank() ->
                selectedProfileId
            else -> NacosSearchBundle.message("toolwindow.env.none")
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
        projectSession?.ensureInitialized(settings)
        val activeId = projectSession?.sessionState?.selectedProfileId
            ?: settings.resolveDefaultProfileId()
        val entries = settings.publishedEnvironments()
            .map { EnvEntry.Environment(it, it.profileId == activeId) } + EnvEntry.Manage
        val popup = JBPopupFactory.getInstance().createListPopup(EnvListStep(entries))
        popup.setMinimumSize(Dimension(240, 0))
        popup.showUnderneathOf(envButton)
    }

    private fun handleSelectEnvironment(entry: EnvEntry.Environment) {
        if (entry.active) return
        if (!settings.isValidServerUrl(entry.environment.canonicalEndpoint)) {
            val name = entry.displayText()
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
        // ADR-0027: switching environment must not discard a dirty draft silently.
        // Ask before mutating selection so cancel leaves env and draft unchanged.
        if (!admitEnvDraftGuard(
                project.service<EditSessionService>().guardEnvironmentSwitch(entry.environment.profileId),
                "config.detail.draft.discard.environment"
            )
        ) return
        // Project-local only — never write the application-wide migration seed
        // or compatibility inputs (issue #107 / ADR-0004). Pass this
        // environment's 建议 Namespace so the previous Namespace id is not
        // reused against a different Nacos (configs would load empty/wrong).
        projectSession?.adoptEnvironment(entry.environment)
        project.getService(com.nanyin.nacos.search.services.ProjectSessionEpochs::class.java)?.bump()
        onSelectionChanged?.invoke(entry.environment.profileId)
        refresh()
    }

    /**
     * Whether a guarded action may proceed. [ConfirmDiscard] prompts with the
     * two ADR-0027 choices; asking never mutates — only an explicit discard does.
     */
    private fun admitEnvDraftGuard(guard: DraftGuard, messageKey: String): Boolean =
        DraftDiscardPrompt.admit(project, project.service<EditSessionService>(), guard, messageKey)

    private fun openSettings() {
        ShowSettingsUtil.getInstance().editConfigurable(project, NacosConfigurable(project))
    }

    private inner class EnvListStep(private val items: List<EnvEntry>) :
        BaseListPopupStep<EnvEntry>(null, items) {
        override fun getTextFor(value: EnvEntry): String = value.displayText()
        override fun getIconFor(value: EnvEntry): Icon? = when (value) {
            is EnvEntry.Environment -> if (value.active) AllIcons.Actions.Checked else null
            is EnvEntry.Manage -> AllIcons.General.Settings
        }
        // Draw a separator above the "Manage connections" entry.
        override fun getSeparatorAbove(value: EnvEntry): ListSeparator? =
            if (value is EnvEntry.Manage) ListSeparator() else null
        override fun getDefaultOptionIndex(): Int =
            items.indexOfFirst { it is EnvEntry.Environment && it.active }.coerceAtLeast(0)
        override fun onChosen(selectedValue: EnvEntry?, finalChoice: Boolean): PopupStep<*>? {
            // Defer dialogs / selection work out of handleSelect so focus is not
            // stolen while the popup is still tearing down (PopupImplUtil).
            return when (selectedValue) {
                is EnvEntry.Environment -> doFinalStep { handleSelectEnvironment(selectedValue) }
                is EnvEntry.Manage -> doFinalStep { openSettings() }
                null -> null
            }
        }
    }

    private sealed class EnvEntry {
        abstract fun displayText(): String
        class Environment(val environment: PublishedEnvironment, val active: Boolean) : EnvEntry() {
            override fun displayText(): String =
                environment.displayName.ifBlank { environment.canonicalEndpoint }
        }
        object Manage : EnvEntry() {
            override fun displayText(): String =
                NacosSearchBundle.message("toolwindow.env.manage")
        }
    }

    override fun languageChanged() {
        Edt.invokeOnEdt(ModalityState.defaultModalityState()) { refresh() }
    }

    /** Anchors the message-bus connection; the switcher holds nothing else to release. */
    override fun dispose() = Unit

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
