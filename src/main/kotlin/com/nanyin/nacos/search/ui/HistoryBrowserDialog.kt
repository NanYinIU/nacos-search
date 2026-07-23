package com.nanyin.nacos.search.ui

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.CommonBundle
import com.intellij.ui.JBSplitter
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
import com.nanyin.nacos.search.services.operations.HistoryTimestamps
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Read-only history browser for one configuration coordinate.
 *
 * Left: revision list. Right: embedded IntelliJ Diff.
 * - One selection → diff that revision against the current content.
 * - Two selections → diff the two revisions against each other.
 * No restore/publish. Open-body is intentionally omitted; the embedded diff is the primary view.
 *
 * Network work runs on [Dispatchers.IO]. UI updates are posted with
 * [ModalityState.any] so they run while this modal [DialogWrapper] is open.
 */
class HistoryBrowserDialog(
    private val project: Project,
    private val target: OperationTarget,
    private val configuration: NacosConfiguration,
    private val currentContent: String,
    private val gateway: OperationGateway,
    private val generationProvider: () -> Long
) : DialogWrapper(project) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller = HistoryBrowserController(gateway, generationProvider)
    private val listModel = DefaultListModel<HistoryEntry>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        emptyText.text = ""
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is HistoryEntry) {
                    val time = HistoryTimestamps.formatForDisplay(value.lastModified)
                    val op = HistoryTimestamps.formatOpType(value.opType)
                    text = "$time  ·  $op  ·  #${value.id}"
                }
                return this
            }
        }
    }
    private val statusLabel = JBLabel(NacosSearchBundle.message("history.loading"))
    private lateinit var diffPanel: DiffRequestPanel
    private val expectedGeneration = generationProvider()
    private var entries: List<HistoryEntry> = emptyList()
    private var selectionEpoch = 0L

    init {
        title = NacosSearchBundle.message(
            "history.dialog.title",
            configuration.dataId,
            configuration.group
        )
        setOKButtonText(CommonBundle.getCloseButtonText())
        init()
        list.addListSelectionListener { refreshDiffForSelection() }
        loadFirstPage()
    }

    /**
     * Posts pure Swing updates so they run under the open modal dialog.
     * [ModalityState.any] is required: IO-launched coroutines have no modality
     * context, which the platform treats as NON_MODAL (blocked by this dialog).
     */
    private fun onDialogUi(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!isDisposed) action()
        }, ModalityState.any())
    }

    override fun createCenterPanel(): JComponent {
        diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, window)
        HistoryDiffPresenter.showEmpty(
            diffPanel,
            NacosSearchBundle.message("history.diff.hint")
        )

        val left = JPanel(BorderLayout()).apply {
            add(JBScrollPane(list), BorderLayout.CENTER)
            preferredSize = Dimension(280, 480)
        }

        val splitter = JBSplitter(false, 0.28f).apply {
            firstComponent = left
            secondComponent = diffPanel.component
            setAndLoadSplitterProportionKey("nacos.search.history.splitter")
        }

        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(960, 560)
            border = JBUI.Borders.empty(8)
            add(statusLabel, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    override fun createActions() = arrayOf(okAction)

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    private fun loadFirstPage() {
        statusLabel.text = NacosSearchBundle.message("history.loading")
        list.emptyText.text = ""
        listModel.clear()
        scope.launch {
            val outcome = try {
                controller.loadPage(
                    target,
                    HistoryQuery(ConfigurationCoordinate(configuration.dataId, configuration.group)),
                    expectedGeneration
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                HistoryBrowserController.Outcome.Failed(error.message ?: error.toString())
            }
            onDialogUi { applyOutcome(outcome) }
        }
    }

    private fun applyOutcome(outcome: HistoryBrowserController.Outcome) {
        when (outcome) {
            is HistoryBrowserController.Outcome.Stale -> {
                statusLabel.text = NacosSearchBundle.message("history.stale")
                list.emptyText.text = statusLabel.text
            }
            is HistoryBrowserController.Outcome.Empty -> {
                statusLabel.text = NacosSearchBundle.message("history.empty")
                list.emptyText.text = statusLabel.text
            }
            is HistoryBrowserController.Outcome.PermissionDenied -> {
                statusLabel.text = NacosSearchBundle.message("history.permission.denied")
                list.emptyText.text = statusLabel.text
            }
            is HistoryBrowserController.Outcome.Unsupported -> {
                statusLabel.text = NacosSearchBundle.message("history.unsupported")
                list.emptyText.text = statusLabel.text
            }
            is HistoryBrowserController.Outcome.Failed -> {
                statusLabel.text = NacosSearchBundle.message("history.failed", outcome.message)
                list.emptyText.text = statusLabel.text
            }
            is HistoryBrowserController.Outcome.Body -> {
                entries = outcome.page.items
                listModel.clear()
                entries.forEach { listModel.addElement(it) }
                list.emptyText.text = ""
                statusLabel.text = NacosSearchBundle.message(
                    "history.loaded",
                    outcome.page.totalCount
                )
                if (entries.isNotEmpty()) {
                    list.selectedIndex = 0
                }
            }
            HistoryBrowserController.Outcome.Loading -> Unit
        }
    }

    private fun refreshDiffForSelection() {
        if (!::diffPanel.isInitialized) return
        val selected = controller.selectedEntries(entries, list.selectedIndices)
        val epoch = ++selectionEpoch
        when (selected.size) {
            1 -> loadHistoryVsCurrent(selected[0], epoch)
            2 -> loadHistoryVsHistory(selected[0], selected[1], epoch)
            else -> HistoryDiffPresenter.showEmpty(
                diffPanel,
                NacosSearchBundle.message("history.diff.hint")
            )
        }
    }

    private fun loadHistoryVsCurrent(entry: HistoryEntry, epoch: Long) {
        HistoryDiffPresenter.showLoading(diffPanel, NacosSearchBundle.message("history.diff.loading"))
        scope.launch {
            val detail = controller.loadDetail(target, entry.id, expectedGeneration).getOrElse { error ->
                onDialogUi {
                    if (epoch != selectionEpoch) return@onDialogUi
                    HistoryDiffPresenter.showError(
                        diffPanel,
                        error.message ?: NacosSearchBundle.message("history.failed", error.toString())
                    )
                }
                return@launch
            }
            onDialogUi {
                if (epoch != selectionEpoch) return@onDialogUi
                HistoryDiffPresenter.showIn(
                    diffPanel,
                    HistoryDiffPresenter.historyToCurrent(detail, currentContent, configuration.type)
                )
            }
        }
    }

    private fun loadHistoryVsHistory(left: HistoryEntry, right: HistoryEntry, epoch: Long) {
        HistoryDiffPresenter.showLoading(diffPanel, NacosSearchBundle.message("history.diff.loading"))
        scope.launch {
            val leftDetail = controller.loadDetail(target, left.id, expectedGeneration).getOrElse {
                onDialogUi {
                    if (epoch != selectionEpoch) return@onDialogUi
                    HistoryDiffPresenter.showError(diffPanel, it.message ?: it.toString())
                }
                return@launch
            }
            val rightDetail = controller.loadDetail(target, right.id, expectedGeneration).getOrElse {
                onDialogUi {
                    if (epoch != selectionEpoch) return@onDialogUi
                    HistoryDiffPresenter.showError(diffPanel, it.message ?: it.toString())
                }
                return@launch
            }
            onDialogUi {
                if (epoch != selectionEpoch) return@onDialogUi
                HistoryDiffPresenter.showIn(
                    diffPanel,
                    HistoryDiffPresenter.historyToHistory(leftDetail, rightDetail)
                )
            }
        }
    }
}
