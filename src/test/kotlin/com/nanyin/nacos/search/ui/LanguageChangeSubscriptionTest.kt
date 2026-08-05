package com.nanyin.nacos.search.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.NacosLanguageListener
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.operations.EditEnvironment
import com.nanyin.nacos.search.services.operations.EditSessionService
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.settings.NacosSettings
import com.intellij.ui.components.JBLabel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import javax.swing.JButton
import javax.swing.SwingUtilities

/**
 * Every tool-window component subscribes to [NacosLanguageListener.TOPIC] itself.
 *
 * The previous design dispatched to the top-level window, which then fanned out
 * manually to five children — [EnvironmentSwitcher] was absent from that list and
 * never refreshed. Subscribing per component removes the fan-out and the omission
 * it allowed.
 *
 * Each case clobbers a label, publishes, and asserts the component put the bundle
 * value back. What that pins is that the notification reaches the component and it
 * re-reads its text — not that the text is translated: [NacosSearchBundle] resolves
 * against the IDE locale, so the same key yields the same string within one run.
 */
@TestApplication
class LanguageChangeSubscriptionTest {

    private val mockProject: Project = mock<Project>().also {
        whenever(it.getService(EditSessionService::class.java)).thenReturn(standInEditSessions())
    }

    private fun standInEditSessions() = EditSessionService(
        { OperationGateway(emptyMap()) },
        object : EditEnvironment {
            override fun selectedProfile(): EnvironmentProfile? = null
            override fun profile(profileId: String): EnvironmentProfile? = null
            override suspend fun captureTarget(profileId: String, namespaceId: String) =
                Result.failure<OperationTarget>(IllegalStateException("no environment"))
        }
    )
    @Test
    fun `environment switcher refreshes on a language change`() {
        val settings = NacosSettings().apply {
            servers = mutableListOf(
                NacosServerConfig(
                    id = "qa",
                    displayName = "QA Integration",
                    serverUrl = "http://qa.example.com:8848"
                )
            )
            activeServerId = "qa"
        }
        val switcher = EnvironmentSwitcher(mockProject, settings)
        try {
            val envButton = privateField<JButton>(switcher, "envButton")
            runOnEdt { envButton.text = STALE }

            publishLanguageChange()

            assertEquals("QA Integration", envButton.text)
        } finally {
            Disposer.dispose(switcher)
        }
    }

    @Test
    fun `search panel refreshes on a language change`() {
        val panel = SearchPanel(mockProject)
        try {
            val clearButton = privateField<JButton>(panel, "clearButton")
            runOnEdt { clearButton.toolTipText = STALE }

            publishLanguageChange()

            assertEquals(NacosSearchBundle.message("search.clear.tooltip"), clearButton.toolTipText)
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `pagination panel refreshes on a language change`() {
        val panel = PaginationPanel()
        try {
            val pageSizeLabel = privateField<JBLabel>(panel, "pageSizeLabel")
            runOnEdt { pageSizeLabel.text = STALE }

            publishLanguageChange()

            assertEquals(
                NacosSearchBundle.message("pagination.page.size.prefix"),
                pageSizeLabel.text
            )
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `config list panel refreshes on a language change`() {
        val panel = ConfigListPanel(mockProject)
        try {
            val loadingLabel = privateField<JBLabel>(panel, "loadingLabel")
            runOnEdt { loadingLabel.text = STALE }

            publishLanguageChange()

            assertEquals(NacosSearchBundle.message("config.list.loading"), loadingLabel.text)
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `namespace panel refreshes on a language change`() {
        val namespaceService = mock<NamespaceService> {
            whenever(it.loadNamespacesAsync()).thenReturn(CompletableDeferred(Result.success(emptyList())))
            whenever(it.getCurrentNamespace()).thenReturn(null)
        }
        val panel = NamespacePanel(
            mockProject,
            namespaceService = namespaceService,
            dispatcher = Dispatchers.Unconfined
        )
        try {
            val loadingLabel = privateField<JBLabel>(panel, "loadingLabel")
            runOnEdt { loadingLabel.text = STALE }

            publishLanguageChange()

            assertEquals(
                NacosSearchBundle.message("namespace.loading.namespaces"),
                loadingLabel.text
            )
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `config detail panel refreshes on a language change`() {
        val panel = ConfigDetailPanel(mockProject)
        try {
            val loadingLabel = privateField<JBLabel>(panel, "loadingLabel")
            runOnEdt { loadingLabel.text = STALE }

            publishLanguageChange()

            assertEquals(NacosSearchBundle.message("config.detail.loading"), loadingLabel.text)
        } finally {
            Disposer.dispose(panel)
        }
    }

    /**
     * A component subscribes inside its own constructor, so it enters the Disposer
     * tree as a root; [NacosSearchWindow] then re-parents it under the window. This
     * pins that ordering: disposing the window must release the subscription.
     */
    @Test
    fun `disposing the parent releases a component subscription`() {
        val window = Disposer.newDisposable("test-tool-window")
        val panel = PaginationPanel()
        Disposer.register(window, panel)
        val pageSizeLabel = privateField<JBLabel>(panel, "pageSizeLabel")

        Disposer.dispose(window)
        runOnEdt { pageSizeLabel.text = STALE }
        publishLanguageChange()

        assertEquals(STALE, pageSizeLabel.text)
    }

    private fun publishLanguageChange() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(NacosLanguageListener.TOPIC)
            .languageChanged()
        waitForUi()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    private fun runOnEdt(action: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) {
            action()
        } else {
            SwingUtilities.invokeAndWait(action)
        }
    }

    private fun waitForUi() {
        runOnEdt {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    private companion object {
        const val STALE = "stale-text"
    }
}
