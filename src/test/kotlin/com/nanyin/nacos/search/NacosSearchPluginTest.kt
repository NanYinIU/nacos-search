package com.nanyin.nacos.search

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
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
}
