package com.nanyin.nacos.search.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.models.NacosServerConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.swing.JComboBox
import javax.swing.JPasswordField
import javax.swing.JTextField

/**
 * Settings-apply boundary: connection-changed vs preferences-only is decided
 * by the active profile's access revision (issue #41), not a hand-built
 * signature that omitted password and API policy.
 */
@TestApplication
class SettingsApplyConnectionChangeTest {

    private lateinit var settings: NacosSettings
    private var originalServers: List<NacosServerConfig> = emptyList()
    private var originalActiveId: String = ""

    @BeforeEach
    fun setUp() {
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        originalServers = settings.servers.map { it.copy() }
        originalActiveId = settings.activeServerId
        settings.applyServers(
            listOf(
                NacosServerConfig(
                    id = "dev",
                    displayName = "Dev",
                    serverUrl = "https://nacos.example",
                    username = "alice",
                    password = "secret-1",
                    authMode = AuthMode.NACOS_PASSWORD,
                    apiPolicy = NacosApiPolicy.AUTO
                ),
                NacosServerConfig(
                    id = "prod",
                    displayName = "Prod",
                    serverUrl = "https://prod.example",
                    username = "bob",
                    password = "secret-2",
                    authMode = AuthMode.NACOS_PASSWORD,
                    apiPolicy = NacosApiPolicy.V1
                )
            ),
            "dev"
        )
    }

    @AfterEach
    fun tearDown() {
        settings.applyServers(originalServers.map { it.copy() }, originalActiveId)
    }

    @Test
    fun `password-only change publishes connection-changed`() {
        val events = recordNotifications {
            val configurable = openConfigurable()
            privateField<JPasswordField>(configurable, "passwordField").text = "rotated-secret"
            configurable.apply()
        }
        assertEquals(listOf("settingsChanged"), events)
        // Access revision must advance; credential itself never enters a signature.
        assertTrue(settings.getActiveProfile()!!.accessRevision > 1)
    }

    @Test
    fun `API generation policy-only change publishes connection-changed`() {
        val events = recordNotifications {
            val configurable = openConfigurable()
            @Suppress("UNCHECKED_CAST")
            privateField<JComboBox<NacosApiPolicy>>(configurable, "apiPolicyComboBox")
                .selectedItem = NacosApiPolicy.V3
            configurable.apply()
        }
        assertEquals(listOf("settingsChanged"), events)
    }

    @Test
    fun `display-name-only change publishes preferences-only`() {
        val before = settings.getActiveProfile()!!.accessRevision
        val events = recordNotifications {
            val configurable = openConfigurable()
            privateField<JTextField>(configurable, "displayNameField").text = "Dev (renamed)"
            configurable.apply()
        }
        assertEquals(listOf("preferencesChanged"), events)
        assertEquals(before, settings.getActiveProfile()!!.accessRevision)
    }

    @Test
    fun `namespace-only change publishes connection-changed`() {
        // Namespace is not part of the access revision, but it selects which
        // dataset the tool window shows: preferences-only would leave the list
        // on the previous namespace's configurations.
        val before = settings.getActiveProfile()!!.accessRevision
        val events = recordNotifications {
            val configurable = openConfigurable()
            privateField<JTextField>(configurable, "namespaceField").text = "dev-ns"
            configurable.apply()
        }
        assertEquals(listOf("settingsChanged"), events)
        assertEquals("dev-ns", settings.getActiveServer().namespace)
        // The credential/endpoint identity did not change, so the revision holds.
        assertEquals(before, settings.getActiveProfile()!!.accessRevision)
    }

    @Test
    fun `switching the active environment publishes connection-changed`() {
        val events = recordNotifications {
            val configurable = openConfigurable()
            val activeField = configurable.javaClass.getDeclaredField("draftActiveId")
            activeField.isAccessible = true
            activeField.set(configurable, "prod")
            configurable.apply()
        }
        assertEquals(listOf("settingsChanged"), events)
        assertEquals("prod", settings.activeServerId)
    }

    private fun openConfigurable(): NacosConfigurable {
        val configurable = NacosConfigurable()
        configurable.createComponent()
        return configurable
    }

    private fun recordNotifications(block: () -> Unit): List<String> {
        val events = mutableListOf<String>()
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(NacosSettingsListener.TOPIC, object : NacosSettingsListener {
            override fun settingsChanged() {
                events += "settingsChanged"
            }

            override fun preferencesChanged() {
                events += "preferencesChanged"
            }
        })
        try {
            block()
        } finally {
            connection.disconnect()
        }
        return events
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }
}
