package com.nanyin.nacos.search.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.services.operations.EditEnvironment
import com.nanyin.nacos.search.services.operations.EditSessionService
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.settings.CredentialSlotStore
import com.nanyin.nacos.search.settings.NacosCredentialStore
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosProjectSessionState
import com.nanyin.nacos.search.settings.NacosSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import javax.swing.JButton

@TestApplication
class EnvironmentSwitcherTest {

    @Test
    fun `switcher label reads the published environment without touching credentials or legacy rows`() {
        val profileId = "switcher-${System.nanoTime()}"
        val credentialKey = CredentialSlotStore.slotKey(profileId, 23)
        NacosCredentialStore.remove(credentialKey)
        val settings = NacosSettings().apply {
            profiles = mutableListOf(
                EnvironmentProfile(
                    id = profileId,
                    displayName = "Published Production",
                    canonicalEndpoint = "https://prod.example",
                    credentialSlotVersion = 23
                )
            )
            environmentPreferences = mutableListOf(
                EnvironmentPreferences(profileId = profileId, suggestedNamespace = "prod-team")
            )
            migratedDefaultProfileId = profileId
        }
        val switcher = EnvironmentSwitcher(project(), settings)

        try {
            val button = privateField<JButton>(switcher, "envButton")
            assertEquals("Published Production", button.text)
            assertEquals(
                NacosSearchBundle.message(
                    "toolwindow.env.switcher.tooltip",
                    "Published Production"
                ),
                button.toolTipText
            )
            assertFalse(
                credentialKey in NacosCredentialStore.cachedKeys(),
                "constructing or refreshing the switcher must not read PasswordSafe"
            )
        } finally {
            Disposer.dispose(switcher)
        }
    }

    @Test
    fun `switcher keeps a deleted project selection unavailable instead of showing the default`() {
        val settings = NacosSettings().apply {
            profiles = mutableListOf(
                EnvironmentProfile(
                    id = "live",
                    displayName = "Live Default",
                    canonicalEndpoint = "https://live.example"
                )
            )
            environmentPreferences = mutableListOf(EnvironmentPreferences.defaultsFor("live"))
            migratedDefaultProfileId = "live"
        }
        val session = NacosProjectSession().also {
            it.loadState(
                NacosProjectSessionState(
                    selectedProfileId = "deleted",
                    namespaceId = "team-a",
                    selectionWasExplicit = true,
                    sessionInitialized = true
                )
            )
        }
        val switcher = EnvironmentSwitcher(project(), settings, session)

        try {
            assertEquals("deleted", privateField<JButton>(switcher, "envButton").text)
            assertEquals("deleted", session.sessionState.selectedProfileId)
            assertEquals("team-a", session.sessionState.namespaceId)
        } finally {
            Disposer.dispose(switcher)
        }
    }

    private fun project(): Project = mock<Project>().also {
        whenever(it.getService(EditSessionService::class.java)).thenReturn(
            EditSessionService({ OperationGateway(emptyMap()) }, object : EditEnvironment {
                override fun selectedProfile(): EnvironmentProfile? = null
                override fun profile(profileId: String): EnvironmentProfile? = null
                override suspend fun captureTarget(profileId: String, namespaceId: String) =
                    Result.failure<OperationTarget>(IllegalStateException("no environment"))
            })
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }
}
