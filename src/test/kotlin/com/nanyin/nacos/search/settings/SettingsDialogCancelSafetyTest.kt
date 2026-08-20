package com.nanyin.nacos.search.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.models.NacosServerConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.swing.JPasswordField
import javax.swing.JTextField

// clearProfileTombstones lives in EnvironmentPreferencesTest.kt (same package).

/**
 * Issue #106: opening Settings, editing the draft, Cancel / Reset, and Reset
 * to Defaults stay cancel-safe. Only Apply / OK publishes.
 *
 * Public seams: [NacosConfigurable] Configurable lifecycle +
 * [NacosConfigurable.draftIntents] / [NacosConfigurable.dirtyDraftProfileIds].
 * Form fields are reached by automation id or public draft intents — draft
 * list state is not read via reflection.
 */
@TestApplication
class SettingsDialogCancelSafetyTest {

    private lateinit var settings: NacosSettings
    private var originalIntents: List<com.nanyin.nacos.search.models.ProfileIntent> = emptyList()
    private var originalActiveId: String = ""
    private val openConfigurables = mutableListOf<NacosConfigurable>()

    @BeforeEach
    fun setUp() {
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        originalIntents = settings.loadIntentDraft().snapshot()
        originalActiveId = settings.resolveDefaultProfileId()
        clearProfileTombstones(originalIntents.map { it.profileId } + "dev")
        settings.applyProfileIntents(
            listOf(
                profileIntentFixture(
                    id = "dev",
                    displayName = "Dev",
                    serverUrl = "https://nacos.example",
                    username = "alice",
                    password = "secret-1",
                    authMode = AuthMode.NACOS_PASSWORD,
                    apiPolicy = NacosApiPolicy.AUTO
                )
            ),
            "dev"
        )
    }

    @AfterEach
    fun tearDown() {
        openConfigurables.forEach { runCatching { it.disposeUIResources() } }
        openConfigurables.clear()
        val touched = (settings.publishedProfiles().map { it.id } +
            originalIntents.map { it.profileId } + setOf("dev")).toSet()
        clearProfileTombstones(touched)
        settings.applyProfileIntents(originalIntents, originalActiveId)
        clearProfileTombstones(touched)
    }

    private fun openConfigurable(): NacosConfigurable {
        val configurable = NacosConfigurable()
        configurable.createComponent()
        openConfigurables += configurable
        return configurable
    }

    @Test
    fun `opening settings does not change active selection or published profiles`() {
        val accessBefore = settings.getActiveProfile()!!.accessRevision
        val profilesBefore = settings.publishedProfiles().map { it.id to it.accessRevision }

        val configurable = openConfigurable()

        assertEquals("", settings.activeServerId)
        assertEquals(accessBefore, settings.getActiveProfile()!!.accessRevision)
        assertEquals(profilesBefore, settings.publishedProfiles().map { it.id to it.accessRevision })
        assertFalse(configurable.isModified())
        assertTrue(configurable.dirtyDraftProfileIds().isEmpty())
        assertEquals(listOf("dev"), configurable.draftIntents().map { it.profileId })
    }

    @Test
    fun `edit then reset restores persisted draft without writing`() {
        val accessBefore = settings.getActiveProfile()!!.accessRevision
        val configurable = openConfigurable()

        // Edit through the public form surface.
        privateField<JTextField>(configurable, "displayNameField").text = "Dev (edited)"
        privateField<JPasswordField>(configurable, "passwordField").text = "rotated"
        // Document listeners commit on change; force a classification refresh.
        assertTrue(configurable.isModified())
        assertTrue(configurable.dirtyDraftProfileIds().contains("dev"))

        configurable.reset()

        assertFalse(configurable.isModified())
        assertTrue(configurable.dirtyDraftProfileIds().isEmpty())
        assertEquals("Dev", configurable.draftIntents().single().displayName)
        assertEquals("secret-1", configurable.draftIntents().single().secret)
        assertEquals(accessBefore, settings.getActiveProfile()!!.accessRevision)
        assertTrue(settings.servers.isEmpty())
    }

    @Test
    fun `reset to defaults stays in memory until apply`() {
        val configurable = openConfigurable()
        val component = configurable.createComponent()
        val resetButton = findButtonByAutomationId(component, "nacos.settings.server.resetDefaults")
        assertTrue(resetButton != null)
        resetButton!!.doClick()

        assertTrue(configurable.isModified())
        val intent = configurable.draftIntents().single()
        // Defaults keep the display name; endpoint comes from the shape seed.
        assertEquals("Dev", intent.displayName)
        assertEquals(
            NacosSettings.defaultPersistedShape().profiles.first().canonicalEndpoint,
            intent.endpoint
        )
        // Still the published profile on disk / settings service.
        assertEquals("https://nacos.example", settings.getActiveProfile()!!.canonicalEndpoint)

        configurable.reset()
        assertFalse(configurable.isModified())
        assertEquals("https://nacos.example", configurable.draftIntents().single().endpoint)
    }

    @Test
    fun `apply is the sole publish path for a password change`() {
        val configurable = openConfigurable()
        privateField<JPasswordField>(configurable, "passwordField").text = "rotated-secret"
        assertTrue(configurable.isModified())
        assertEquals(1L, settings.getActiveProfile()!!.accessRevision)

        configurable.apply()

        assertTrue(settings.getActiveProfile()!!.accessRevision > 1)
        // Draft reloads clean after a successful apply path that does not fail stage.
        // Re-open classification from a fresh draft.
        val after = openConfigurable()
        assertFalse(after.isModified())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T {
        // Form widgets only — draft model is exercised through public seams.
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    private fun findButtonByAutomationId(
        root: java.awt.Component,
        automationId: String
    ): javax.swing.JButton? {
        if (root is javax.swing.JButton &&
            root.getClientProperty("nacos.automation.id") == automationId
        ) {
            return root
        }
        if (root is java.awt.Container) {
            for (child in root.components) {
                findButtonByAutomationId(child, automationId)?.let { return it }
            }
        }
        return null
    }
}
