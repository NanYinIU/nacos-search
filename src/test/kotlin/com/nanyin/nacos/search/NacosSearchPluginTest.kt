package com.nanyin.nacos.search

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@TestApplication
class NacosSearchPluginTest {

    @BeforeEach
    fun publishAnonymousProfile() {
        // captureNamespaceIndexRequest reads the published EnvironmentProfile
        // (ADR-0049): republish an ANONYMOUS profile so the refresh fails with
        // a typed connection error instead of ConfigurationRequired, which
        // would be logged as an error and fail the test via TestLogger.
        ApplicationManager.getApplication().getService(NacosSettings::class.java).apply {
            resetToDefaults()
            applyProfileIntents(
                listOf(
                    com.nanyin.nacos.search.settings.profileIntentFixture(
                        id = "s_local",
                        displayName = "Local",
                        serverUrl = "https://nacos.example",
                        authMode = AuthMode.ANONYMOUS,
                        apiPolicy = com.nanyin.nacos.search.models.NacosApiPolicy.V1
                    )
                ),
                "s_local"
            )
        }
    }

    @Test
    fun `plugin implements Disposable so the platform reclaims its coroutine scope`() {
        val plugin = NacosSearchPlugin()
        assertTrue(plugin is com.intellij.openapi.Disposable)

        // The platform disposes APP-level services via the Disposable interface on
        // shutdown; disposing must not throw.
        Disposer.dispose(plugin)
    }

    @Test
    fun `plugin does not schedule periodic refresh jobs`() {
        val source = java.io.File("src/main/kotlin/com/nanyin/nacos/search/NacosSearchPlugin.kt").readText()
        val forbidden = listOf(
            "scheduleAtFixedRate", "scheduleWithFixedDelay",
            "setupAutoRefresh", "autoRefreshJob", ".period"
        )
        forbidden.forEach { pattern ->
            assertFalse(source.contains(pattern), "Plugin source must not contain '$pattern'")
        }
    }

    @Test
    fun `manual refresh routes through coordinator error classification`() = runBlocking {
        val plugin = NacosSearchPlugin()
        try {
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            val context = settings.captureOperationContext("s_local").getOrThrow()
            val result = plugin.refreshCache(
                "contract-test-ns",
                context,
                ProjectManager.getInstance().defaultProject
            )
            // Without a real server, the refresh fails. After issue #122 the
            // coordinator preserves typed remote causes (e.g. Connection) rather
            // than collapsing them to NacosRequestError — that is the contract.
            // ConfigurationRequired / IllegalStateException remain valid when
            // capture or Partial without a stopping cause fails closed.
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                assertTrue(
                    error is RemoteOperationError ||
                        error is NacosRequestError ||
                        error is ConfigurationRequired ||
                        error is IllegalStateException,
                    "Expected typed coordinator/gateway failure, got: $error"
                )
            }
        } finally {
            Disposer.dispose(plugin)
        }
    }
}
