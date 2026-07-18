package com.nanyin.nacos.search.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.services.captureAccessIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConfigDetailPanelStaleTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `deep-stale navigation shows cached target and forces single-detail refresh`() = runBlocking {
        var now = 6_000_000L
        val cacheService = CacheService { now }
        cacheService.clearAll()
        val configuration = NacosConfiguration(
            "app.properties",
            "DEFAULT_GROUP",
            "dev",
            "# cached",
            "properties"
        )
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        settings.serverUrl = "http://localhost:8848"
        cacheService.putConfigDetail(
            settings.captureAccessIdentity(),
            "dev",
            configuration,
            ttl = 100L
        )
        now += 8L * 24 * 60 * 60 * 1000
        val refreshRequested = CompletableDeferred<Boolean>()
        val neverCompletes = CompletableDeferred<Result<NacosConfiguration?>>()
        val loader = ConfigurationDetailLoader { _, forceRefresh ->
            refreshRequested.complete(forceRefresh)
            neverCompletes.await()
        }
        val panel = ConfigDetailPanel(ProjectManager.getInstance().defaultProject, loader, cacheService, settings)

        panel.showConfiguration(configuration, lineIndex = 0)

        assertEquals(configuration, panel.getCurrentConfiguration())
        assertTrue(withTimeout(1_000L) { refreshRequested.await() })
        ApplicationManager.getApplication().invokeAndWait { Disposer.dispose(panel) }
    }

    @Test
    fun `explicit not-found removes stale navigation target`() = runBlocking {
        var now = 7_000_000L
        val cacheService = CacheService { now }
        cacheService.clearAll()
        val configuration = NacosConfiguration(
            "deleted.properties",
            "DEFAULT_GROUP",
            "dev",
            "# cached",
            "properties"
        )
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        settings.serverUrl = "http://localhost:8848"
        cacheService.putConfigDetail(
            settings.captureAccessIdentity(),
            "dev",
            configuration,
            ttl = 100L
        )
        now += 8L * 24 * 60 * 60 * 1000
        val notFoundReturned = CompletableDeferred<Unit>()
        val loader = ConfigurationDetailLoader { _, _ ->
            notFoundReturned.complete(Unit)
            Result.success(null)
        }
        val panel = ConfigDetailPanel(
            ProjectManager.getInstance().defaultProject,
            loader,
            cacheService,
            settings
        )

        panel.showConfiguration(configuration)

        withTimeout(5_000L) {
            notFoundReturned.await()
            while (cacheService.configDetailState(
                    settings.captureAccessIdentity(),
                    "dev",
                    "deleted.properties",
                    "DEFAULT_GROUP"
                ) != null
            ) {
                kotlinx.coroutines.yield()
            }
        }
        ApplicationManager.getApplication().invokeAndWait { Disposer.dispose(panel) }
    }
}
