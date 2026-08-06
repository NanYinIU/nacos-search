package com.nanyin.nacos.search.settings

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.models.NacosServerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Issue #107 — each project session owns its environment and Namespace selection.
 * Seams: [NacosProjectSessionState], [NacosProjectSession], and seed-only
 * [NacosSettings.resolveDefaultProfileId] / [NacosSettings.migratedDefaultProfileId].
 */
@TestApplication
class ProjectLocalSelectionTest {

    private fun defaults(
        defaultProfileId: String = "dev",
        defaultNamespaceId: String = "team-a",
        profileIds: List<String> = listOf("dev", "prod")
    ): LegacyMigrationResult = LegacyMigrationResult(
        profiles = profileIds.map {
            EnvironmentProfile(
                id = it,
                displayName = it,
                canonicalEndpoint = "https://$it.example",
                apiPolicy = NacosApiPolicy.V1,
                authMode = AuthMode.ANONYMOUS
            )
        },
        defaultProfileId = defaultProfileId,
        defaultNamespaceId = defaultNamespaceId
    )

    @Test
    fun `blank session seeds once from the migration default and marks itself initialized`() {
        val session = NacosProjectSessionState()
        val first = defaults(defaultProfileId = "dev", defaultNamespaceId = "team-a")

        session.ensureInitialized(first)

        assertTrue(session.sessionInitialized)
        assertEquals("dev", session.selectedProfileId)
        assertEquals("team-a", session.namespaceId)

        // A later change to the shared seed must not retarget this project.
        session.ensureInitialized(defaults(defaultProfileId = "prod", defaultNamespaceId = "other"))
        assertEquals("dev", session.selectedProfileId)
        assertEquals("team-a", session.namespaceId)
    }

    @Test
    fun `two project sessions stay independent when one switches environment and Namespace`() {
        val shared = defaults(defaultProfileId = "dev", defaultNamespaceId = "team-a")
        val first = NacosProjectSessionState()
        val second = NacosProjectSessionState()
        first.ensureInitialized(shared)
        second.ensureInitialized(shared)

        first.select("prod", "team-b")

        assertEquals("prod", first.selectedProfileId)
        assertEquals("team-b", first.namespaceId)
        assertEquals("dev", second.selectedProfileId)
        assertEquals("team-a", second.namespaceId)
        assertFalse(second.selectionWasExplicit)
    }

    @Test
    fun `restart restores each project session from its own persisted workspace state`() {
        val shared = defaults(defaultProfileId = "dev", defaultNamespaceId = "public")
        val alpha = NacosProjectSessionState().also {
            it.ensureInitialized(shared)
            it.select("prod", "ns-alpha")
        }
        val beta = NacosProjectSessionState().also {
            it.ensureInitialized(shared)
            it.select("dev", "ns-beta")
        }

        // Simulate IDE restart: reload each project's workspace XML into a fresh component.
        val alphaRestored = NacosProjectSession().also { it.loadState(alpha) }
        val betaRestored = NacosProjectSession().also { it.loadState(beta) }

        assertEquals("prod", alphaRestored.sessionState.selectedProfileId)
        assertEquals("ns-alpha", alphaRestored.sessionState.namespaceId)
        assertTrue(alphaRestored.sessionState.sessionInitialized)
        assertEquals("dev", betaRestored.sessionState.selectedProfileId)
        assertEquals("ns-beta", betaRestored.sessionState.namespaceId)
        assertTrue(betaRestored.sessionState.sessionInitialized)

        // Restored sessions must not re-seed from a moved application default.
        val movedSeed = defaults(defaultProfileId = "ghost", defaultNamespaceId = "moved")
        alphaRestored.ensureInitialized(movedSeed)
        betaRestored.ensureInitialized(movedSeed)
        assertEquals("prod", alphaRestored.sessionState.selectedProfileId)
        assertEquals("ns-alpha", alphaRestored.sessionState.namespaceId)
        assertEquals("dev", betaRestored.sessionState.selectedProfileId)
        assertEquals("ns-beta", betaRestored.sessionState.namespaceId)
    }

    @Test
    fun `initialized session never silently retargets when its profile is missing`() {
        val settings = NacosSettings().also { it.resetToDefaults() }
        settings.applyServers(
            listOf(
                NacosServerConfig(
                    id = "live",
                    displayName = "Live",
                    serverUrl = "https://nacos.example"
                )
            ),
            "live"
        )
        val session = NacosProjectSessionState().apply {
            selectedProfileId = "deleted"
            namespaceId = "qa"
            sessionInitialized = true
            selectionWasExplicit = false
        }

        session.healSelection(settings.migrationDefaults()) { id -> settings.getProfile(id) != null }

        assertEquals("deleted", session.selectedProfileId)
        assertEquals("qa", session.namespaceId)
        assertTrue(session.sessionInitialized)
    }

    @Test
    fun `adopting an environment updates only the project session and leaves the migration seed alone`() {
        val settings = NacosSettings().also { it.resetToDefaults() }
        settings.applyServers(
            listOf(
                NacosServerConfig(id = "s_local", displayName = "Local", serverUrl = "http://localhost:8848"),
                NacosServerConfig(id = "s_qa", displayName = "QA", serverUrl = "http://47.95.169.10:8848")
            ),
            "s_local"
        )
        val seedBefore = settings.migratedDefaultProfileId
        val activeBefore = settings.activeServerId
        assertEquals("s_local", seedBefore)

        val session = NacosProjectSession()
        session.ensureInitialized(settings.migrationDefaults())
        session.adoptEnvironment("s_qa", "team-qa")

        assertEquals("s_qa", session.sessionState.selectedProfileId)
        assertEquals("team-qa", session.sessionState.namespaceId)
        assertEquals(seedBefore, settings.migratedDefaultProfileId)
        assertEquals(activeBefore, settings.activeServerId, "runtime selection must not write the dual-write active id")
    }

    @Test
    fun `changing the dual-write active id does not move the migration seed used for new projects`() {
        val settings = NacosSettings().also { it.resetToDefaults() }
        settings.applyServers(
            listOf(
                NacosServerConfig(id = "s_local", displayName = "Local", serverUrl = "http://localhost:8848"),
                NacosServerConfig(id = "s_qa", displayName = "QA", serverUrl = "http://47.95.169.10:8848")
            ),
            "s_local"
        )
        settings.setActiveServer("s_qa")

        assertEquals("s_qa", settings.activeServerId)
        assertEquals("s_local", settings.migratedDefaultProfileId)
        assertEquals("s_local", settings.resolveDefaultProfileId())
    }

    @Test
    fun `namespace resolution for an initialized session ignores any external namespace id`() {
        val session = NacosProjectSessionState().apply {
            ensureInitialized(defaults(defaultProfileId = "dev", defaultNamespaceId = "from-seed"))
            select("dev", "project-ns")
        }

        val resolved = resolveProjectNamespaceId(
            sessionState = session,
            externalNamespaceId = "app-wide-other"
        )

        assertEquals("project-ns", resolved)
        assertNotEquals("app-wide-other", resolved)
    }

    @Test
    fun `legacy workspace XML with a selection but no initialized flag is treated as initialized`() {
        val loaded = NacosProjectSessionState(
            selectedProfileId = "prod",
            namespaceId = "legacy-ns",
            selectionWasExplicit = true,
            sessionInitialized = false
        )
        val component = NacosProjectSession().also { it.loadState(loaded) }
        component.ensureInitialized(defaults(defaultProfileId = "dev", defaultNamespaceId = "public"))

        assertTrue(component.sessionState.sessionInitialized)
        assertEquals("prod", component.sessionState.selectedProfileId)
        assertEquals("legacy-ns", component.sessionState.namespaceId)
    }
}
