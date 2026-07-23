package com.nanyin.nacos.search.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.operations.ConfigurationCoordinate
import com.nanyin.nacos.search.services.operations.HistoryDiffPresenter
import com.nanyin.nacos.search.services.operations.HistoryEntry
import com.nanyin.nacos.search.services.operations.HistoryQuery
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

/**
 * Read-only history browser for one configuration coordinate.
 * Supports open-body and Diff (history↔history, history↔current). No restore/publish.
 */
class HistoryBrowserDialog(
    private val project: Project,
    private val target: OperationTarget,
    private val configuration: NacosConfiguration,
    private val currentContent: String,
    private val gateway: OperationGateway,
    private val generationProvider: () -> Long
) : DialogWrapper(project) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val controller = HistoryBrowserController(gateway, generationProvider)
    private val listModel = DefaultListModel<HistoryEntry>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is HistoryEntry) {
                    text = "${value.id} · ${value.opType ?: "-"} · ${formatTs(value.lastModified)} · ${value.md5 ?: "-"}"
                }
                return this
            }
        }
    }
    private val statusLabel = JBLabel(NacosSearchBundle.message("history.loading"))
    private val openButton = JButton(NacosSearchBundle.message("history.action.open")).apply { isEnabled = false }
    private val diffCurrentButton = JButton(NacosSearchBundle.message("history.action.diff.current")).apply { isEnabled = false }
    private val diffSelectedButton = JButton(NacosSearchBundle.message("history.action.diff.selected")).apply { isEnabled = false }
    private val expectedGeneration = generationProvider()
    private var entries: List<HistoryEntry> = emptyList()

    init {
        title = NacosSearchBundle.message(
            "history.dialog.title",
            configuration.dataId,
            configuration.group
        )
        init()
        list.addListSelectionListener { updateActionEnabled() }
        openButton.addActionListener { openSelectedBody() }
        diffCurrentButton.addActionListener { diffWithCurrent() }
        diffSelectedButton.addActionListener { diffSelectedPair() }
        loadFirstPage()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(640, 360)
        panel.border = JBUI.Borders.empty(8)
        panel.add(statusLabel, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        val actions = JPanel().apply {
            add(openButton)
            add(diffCurrentButton)
            add(diffSelectedButton)
        }
        panel.add(actions, BorderLayout.SOUTH)
        return panel
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    private fun loadFirstPage() {
        statusLabel.text = NacosSearchBundle.message("history.loading")
        listModel.clear()
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    controller.loadPage(
                        target,
                        HistoryQuery(ConfigurationCoordinate(configuration.dataId, configuration.group)),
                        expectedGeneration
                    )
                }
                when (outcome) {
                    is HistoryBrowserController.Outcome.Stale -> {
                        statusLabel.text = NacosSearchBundle.message("history.stale")
                    }
                    is HistoryBrowserController.Outcome.Empty -> {
                        statusLabel.text = NacosSearchBundle.message("history.empty")
                    }
                    is HistoryBrowserController.Outcome.PermissionDenied -> {
                        statusLabel.text = NacosSearchBundle.message("history.permission.denied")
                    }
                    is HistoryBrowserController.Outcome.Unsupported -> {
                        statusLabel.text = NacosSearchBundle.message("history.unsupported")
                    }
                    is HistoryBrowserController.Outcome.Failed -> {
                        statusLabel.text = NacosSearchBundle.message("history.failed", outcome.message)
                    }
                    is HistoryBrowserController.Outcome.Body -> {
                        entries = outcome.page.items
                        listModel.clear()
                        entries.forEach { listModel.addElement(it) }
                        statusLabel.text = NacosSearchBundle.message(
                            "history.loaded",
                            outcome.page.totalCount
                        )
                        updateActionEnabled()
                    }
                    HistoryBrowserController.Outcome.Loading -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                statusLabel.text = NacosSearchBundle.message(
                    "history.failed",
                    error.message ?: error.toString()
                )
            }
        }
    }

    private fun updateActionEnabled() {
        val selected = list.selectedIndices.size
        openButton.isEnabled = selected == 1
        diffCurrentButton.isEnabled = selected == 1
        diffSelectedButton.isEnabled = selected == 2
    }

    private fun openSelectedBody() {
        val entry = controller.selectedEntries(entries, list.selectedIndices).singleOrNull() ?: return
        scope.launch {
            val detail = controller.loadDetail(target, entry.id, expectedGeneration).getOrElse { error ->
                Messages.showErrorDialog(
                    project,
                    error.message ?: NacosSearchBundle.message("history.failed", error.toString()),
                    NacosSearchBundle.message("history.dialog.title", configuration.dataId, configuration.group)
                )
                return@launch
            }
            val area = JTextArea(detail.content).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            Messages.showMessageDialog(
                project,
                area.text,
                NacosSearchBundle.message("history.body.title", detail.id),
                Messages.getInformationIcon()
            )
        }
    }

    private fun diffWithCurrent() {
        val entry = controller.selectedEntries(entries, list.selectedIndices).singleOrNull() ?: return
        scope.launch {
            val detail = controller.loadDetail(target, entry.id, expectedGeneration).getOrElse { error ->
                Messages.showErrorDialog(project, error.message ?: error.toString(), title)
                return@launch
            }
            HistoryDiffPresenter.show(
                project,
                HistoryDiffPresenter.historyToCurrent(detail, currentContent, configuration.type)
            )
        }
    }

    private fun diffSelectedPair() {
        val selected = controller.selectedEntries(entries, list.selectedIndices)
        if (selected.size != 2) return
        scope.launch {
            val left = controller.loadDetail(target, selected[0].id, expectedGeneration).getOrElse {
                Messages.showErrorDialog(project, it.message ?: it.toString(), title)
                return@launch
            }
            val right = controller.loadDetail(target, selected[1].id, expectedGeneration).getOrElse {
                Messages.showErrorDialog(project, it.message ?: it.toString(), title)
                return@launch
            }
            HistoryDiffPresenter.show(project, HistoryDiffPresenter.historyToHistory(left, right))
        }
    }

    private fun formatTs(millis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
}
