package com.nanyin.nacos.search

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.settings.ConfigurationRequired
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class NacosSearchPluginTest {

    @Test
    fun `plugin implements Disposable so the platform reclaims its coroutine scope`() {
        val plugin = NacosSearchPlugin()
        assertTrue(plugin is com.intellij.openapi.Disposable)
        assertTrue(plugin.isScopeActive())

        // The platform disposes APP-level services via the Disposable interface on
        // shutdown; disposing must cancel the plugin's coroutine scope.
        Disposer.dispose(plugin)

        assertFalse(plugin.isScopeActive())
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
            val result = plugin.refreshCache("contract-test-ns")
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
