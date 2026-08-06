package com.nanyin.nacos.search.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.ProfileTombstoneRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.swing.JCheckBox

/** Test-only: lift shared tombstones so fixtures can re-use stable profile ids. */
internal fun clearProfileTombstones(profileIds: Iterable<String>) {
    val registry = ApplicationManager.getApplication()
        .getService(ProfileTombstoneRegistry::class.java) ?: return
    profileIds.forEach { registry.clear(it) }
}

/**
 * Issue #101: per-environment preference records are the runtime source of
 * truth, independent of the legacy server entry.
 */
@TestApplication
class EnvironmentPreferencesUnitTest {

    @Test
    fun `preferencesFor returns defaults without consulting servers`() {
        val settings = NacosSettings()
        settings.resetToDefaults()
        // Wipe server-entry dual-write fields so a consumer that still reads
        // servers would see the wrong answer.
        settings.servers.forEach {
            it.allowCrossNamespaceNavigation = true
            it.navigationDetailPrefetchEnabled = false
        }
        settings.environmentPreferences = mutableListOf(
            EnvironmentPreferences(
                profileId = "s_local",
                allowCrossNamespaceNavigation = false,
                navigationDetailPrefetchEnabled = true
            )
        )

        val prefs = settings.preferencesFor("s_local")
        assertFalse(prefs.allowCrossNamespaceNavigation)
        assertTrue(prefs.navigationDetailPrefetchEnabled)
        assertFalse(settings.allowCrossNamespaceNavigation("s_local"))
        assertTrue(settings.navigationDetailPrefetchEnabled("s_local"))
    }

    @Test
    fun `preference records survive persistence without runtime server fields`() {
        val original = NacosSettings()
        original.applyServers(
            listOf(
                NacosServerConfig(
                    id = "dev",
                    displayName = "Dev",
                    serverUrl = "http://localhost:8848",
                    allowCrossNamespaceNavigation = true,
                    navigationDetailPrefetchEnabled = false
                )
            ),
            "dev"
        )

        // Snapshot as the platform would persist it.
        val persisted = original.getState()
        // Simulate a future state where legacy server preference fields are gone
        // but preference records remain (ADR-0049 deserialization-only path).
        persisted.servers.forEach {
            it.allowCrossNamespaceNavigation = false
            it.navigationDetailPrefetchEnabled = true
        }

        val restored = NacosSettings()
        restored.loadState(persisted)

        // Clear dual-write fields after load so lookup cannot cheat via servers.
        restored.servers.forEach {
            it.allowCrossNamespaceNavigation = false
            it.navigationDetailPrefetchEnabled = true
        }

        val prefs = restored.preferencesFor("dev")
        assertEquals("dev", prefs.profileId)
        assertTrue(prefs.allowCrossNamespaceNavigation)
        assertFalse(prefs.navigationDetailPrefetchEnabled)
        assertTrue(restored.allowCrossNamespaceNavigation("dev"))
        assertFalse(restored.navigationDetailPrefetchEnabled("dev"))
    }

    @Test
    fun `legacy server fields seed preference records on first load`() {
        val legacy = NacosSettings()
        legacy.servers = mutableListOf(
            NacosServerConfig(
                id = "legacy",
                displayName = "Legacy",
                serverUrl = "http://localhost:8848",
                allowCrossNamespaceNavigation = true,
                navigationDetailPrefetchEnabled = false
            )
        )
        legacy.activeServerId = "legacy"
        // Empty list simulates a pre-#101 persisted state.
        legacy.environmentPreferences = mutableListOf()

        val restored = NacosSettings()
        restored.loadState(legacy)

        val prefs = restored.preferencesFor("legacy")
        assertTrue(prefs.allowCrossNamespaceNavigation)
        assertFalse(prefs.navigationDetailPrefetchEnabled)
    }

    @Test
    fun `preference-only apply does not advance profile or access revisions`() {
        val settings = NacosSettings()
        settings.applyServers(
            listOf(
                NacosServerConfig(
                    id = "dev",
                    displayName = "Dev",
                    serverUrl = "https://nacos.example",
                    username = "alice",
                    password = "secret",
                    authMode = AuthMode.NACOS_PASSWORD
                )
            ),
            "dev"
        )
        val before = settings.getProfile("dev")!!
        val profileRev = before.profileRevision
        val accessRev = before.accessRevision
        val activeIdBefore = settings.activeServerId
        val idsBefore = settings.servers.map { it.id }.toSet()
        val namespacesBefore = settings.servers.associate { it.id to it.namespace }

        val servers = settings.cloneServers().map {
            it.copy(
                allowCrossNamespaceNavigation = true,
                navigationDetailPrefetchEnabled = false
            )
        }
        settings.applyServers(servers, "dev")

        val after = settings.getProfile("dev")!!
        assertEquals(profileRev, after.profileRevision)
        assertEquals(accessRev, after.accessRevision)
        // Epoch relevance in applyServers is active id / membership / revisions /
        // namespace only — preference-only writes leave every input unchanged, so
        // ProjectSessionEpochs.bumpAllOpenProjects is not called (ADR-0042).
        assertEquals(activeIdBefore, settings.activeServerId)
        assertEquals(idsBefore, settings.servers.map { it.id }.toSet())
        assertEquals(namespacesBefore, settings.servers.associate { it.id to it.namespace })
        assertTrue(settings.preferencesFor("dev").allowCrossNamespaceNavigation)
        assertFalse(settings.preferencesFor("dev").navigationDetailPrefetchEnabled)
    }

    @Test
    fun `removing an environment drops its preference record`() {
        val settings = NacosSettings()
        settings.applyServers(
            listOf(
                NacosServerConfig(id = "keep", displayName = "Keep", serverUrl = "http://localhost:8848"),
                NacosServerConfig(
                    id = "drop",
                    displayName = "Drop",
                    serverUrl = "http://localhost:8849",
                    allowCrossNamespaceNavigation = true
                )
            ),
            "keep"
        )
        assertTrue(settings.allEnvironmentPreferences().containsKey("drop"))

        settings.applyServers(
            listOf(NacosServerConfig(id = "keep", displayName = "Keep", serverUrl = "http://localhost:8848")),
            "keep"
        )
        assertFalse(settings.allEnvironmentPreferences().containsKey("drop"))
        assertTrue(settings.allEnvironmentPreferences().containsKey("keep"))
    }
}

@TestApplication
class EnvironmentPreferencesNotificationTest {

    private lateinit var settings: NacosSettings
    private var originalServers: List<NacosServerConfig> = emptyList()
    private var originalActiveId: String = ""
    private var originalPreferences: List<EnvironmentPreferences> = emptyList()

    @BeforeEach
    fun setUp() {
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        originalServers = settings.servers.map { it.copy() }
        originalActiveId = settings.activeServerId
        originalPreferences = settings.environmentPreferences.map { it.copyPreferences() }
        settings.applyServers(
            listOf(
                NacosServerConfig(
                    id = "dev",
                    displayName = "Dev",
                    serverUrl = "https://nacos.example",
                    username = "alice",
                    password = "secret-1",
                    authMode = AuthMode.NACOS_PASSWORD
                )
            ),
            "dev"
        )
    }

    @AfterEach
    fun tearDown() {
        // applyServers entombs removed ids permanently (issue #105). Lift
        // originals before restore so they can re-publish, then lift fixture
        // ids entombed by the restore so the next setUp can reuse "dev".
        val touched = (settings.servers.map { it.id } + originalServers.map { it.id }).toSet()
        clearProfileTombstones(touched)
        settings.applyServers(originalServers.map { it.copy() }, originalActiveId)
        settings.environmentPreferences = originalPreferences.map { it.copyPreferences() }.toMutableList()
        clearProfileTombstones(touched)
    }

    @Test
    fun `navigation prefetch toggle publishes preferences-only and keeps revisions`() {
        val beforeProfile = settings.getActiveProfile()!!.profileRevision
        val beforeAccess = settings.getActiveProfile()!!.accessRevision
        val events = recordNotifications {
            val configurable = NacosConfigurable()
            configurable.createComponent()
            privateField<JCheckBox>(configurable, "navigationDetailPrefetchCheckBox").isSelected = false
            configurable.apply()
        }
        assertEquals(listOf("preferencesChanged"), events)
        assertEquals(beforeProfile, settings.getActiveProfile()!!.profileRevision)
        assertEquals(beforeAccess, settings.getActiveProfile()!!.accessRevision)
        assertFalse(settings.navigationDetailPrefetchEnabled("dev"))
        assertFalse(settings.preferencesFor("dev").navigationDetailPrefetchEnabled)
    }

    @Test
    fun `cross-namespace toggle publishes preferences-only and keeps revisions`() {
        val beforeProfile = settings.getActiveProfile()!!.profileRevision
        val beforeAccess = settings.getActiveProfile()!!.accessRevision
        val events = recordNotifications {
            val configurable = NacosConfigurable()
            configurable.createComponent()
            privateField<JCheckBox>(configurable, "crossNamespaceNavigationCheckBox").isSelected = true
            configurable.apply()
        }
        assertEquals(listOf("preferencesChanged"), events)
        assertEquals(beforeProfile, settings.getActiveProfile()!!.profileRevision)
        assertEquals(beforeAccess, settings.getActiveProfile()!!.accessRevision)
        assertTrue(settings.allowCrossNamespaceNavigation("dev"))
        assertTrue(settings.preferencesFor("dev").allowCrossNamespaceNavigation)
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
