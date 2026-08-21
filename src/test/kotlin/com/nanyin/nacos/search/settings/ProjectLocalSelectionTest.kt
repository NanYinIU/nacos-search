package com.nanyin.nacos.search.settings

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile
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

    private fun environment(
        profileId: String = "dev",
        suggestedNamespace: String = "team-a"
    ) = PublishedEnvironment(
        profileId = profileId,
        displayName = profileId,
        canonicalEndpoint = "https://$profileId.example",
        suggestedNamespace = suggestedNamespace
    )

    @Test
    fun `blank session seeds once from the published migration default and marks itself initialized`() {
        val session = NacosProjectSessionState()
        val first = environment(profileId = "dev", suggestedNamespace = "team-a")

        session.ensureInitialized(first)

        assertTrue(session.sessionInitialized)
        assertEquals("dev", session.selectedProfileId)
        assertEquals("team-a", session.namespaceId)

        // A later change to the shared seed must not retarget this project.
        session.ensureInitialized(environment(profileId = "prod", suggestedNamespace = "other"))
        assertEquals("dev", session.selectedProfileId)
        assertEquals("team-a", session.namespaceId)
    }

    @Test
    fun `two project sessions stay independent when one switches environment and Namespace`() {
        val shared = environment(profileId = "dev", suggestedNamespace = "team-a")
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
        val shared = environment(profileId = "dev", suggestedNamespace = "public")
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
        val movedSeed = environment(profileId = "ghost", suggestedNamespace = "moved")
        alphaRestored.sessionState.ensureInitialized(movedSeed)
        betaRestored.sessionState.ensureInitialized(movedSeed)
        assertEquals("prod", alphaRestored.sessionState.selectedProfileId)
        assertEquals("ns-alpha", alphaRestored.sessionState.namespaceId)
        assertEquals("dev", betaRestored.sessionState.selectedProfileId)
        assertEquals("ns-beta", betaRestored.sessionState.namespaceId)
    }

    @Test
    fun `initialized session never silently retargets when its profile is missing`() {
        val session = NacosProjectSessionState().apply {
            selectedProfileId = "deleted"
            namespaceId = "qa"
            sessionInitialized = true
            selectionWasExplicit = false
        }

        session.ensureInitialized(environment(profileId = "live", suggestedNamespace = "public"))

        assertEquals("deleted", session.selectedProfileId)
        assertEquals("qa", session.namespaceId)
        assertTrue(session.sessionInitialized)
    }

    @Test
    fun `adopting an environment updates only the project session and leaves the migration seed alone`() {
        val settings = settingsWithProfiles(
            defaultProfileId = "s_local",
            environment("s_local", "public"),
            environment("s_qa", "team-qa")
        )
        val seedBefore = settings.migratedDefaultProfileId
        assertEquals("s_local", seedBefore)

        val session = NacosProjectSession()
        session.ensureInitialized(settings)
        session.adoptEnvironment(settings.publishedEnvironment("s_qa")!!)

        assertEquals("s_qa", session.sessionState.selectedProfileId)
        assertEquals("team-qa", session.sessionState.namespaceId)
        assertEquals(seedBefore, settings.migratedDefaultProfileId)
        assertEquals("", settings.activeServerId, "runtime selection must not write the legacy active id")
    }

    @Test
    fun `adopting a different environment without a namespace does not keep the previous servers namespace`() {
        val session = NacosProjectSession()
        session.sessionState.ensureInitialized(environment(profileId = "dev", suggestedNamespace = "ns-local-uuid"))
        assertEquals("ns-local-uuid", session.sessionState.namespaceId)

        // Switcher / settings must pass the new environment's suggested Namespace.
        // When they omit it, public is the safe default — never the previous
        // server's id (configs would query the wrong tenant on the new server).
        session.adoptEnvironment("prod")

        assertEquals("prod", session.sessionState.selectedProfileId)
        assertEquals("public", session.sessionState.namespaceId)
    }

    @Test
    fun `adopting the same environment without a namespace keeps the current Namespace`() {
        val session = NacosProjectSession()
        session.sessionState.ensureInitialized(environment(profileId = "dev", suggestedNamespace = "team-a"))
        session.select("dev", "team-custom")

        session.adoptEnvironment("dev")

        assertEquals("dev", session.sessionState.selectedProfileId)
        assertEquals("team-custom", session.sessionState.namespaceId)
    }

    @Test
    fun `adopting a published environment uses its suggestion but preserves an explicit Namespace`() {
        val dev = PublishedEnvironment(
            profileId = "dev",
            displayName = "Development",
            canonicalEndpoint = "https://dev.example",
            suggestedNamespace = "team-a"
        )
        val prod = PublishedEnvironment(
            profileId = "prod",
            displayName = "Production",
            canonicalEndpoint = "https://prod.example",
            suggestedNamespace = "team-prod"
        )
        val session = NacosProjectSession()

        session.sessionState.ensureInitialized(dev)
        session.adoptEnvironment(prod)
        assertEquals("prod", session.sessionState.selectedProfileId)
        assertEquals("team-prod", session.sessionState.namespaceId)

        session.select("prod", "manual-prod")
        session.adoptEnvironment(prod)
        assertEquals("manual-prod", session.sessionState.namespaceId)
    }

    @Test
    fun `adopting public via blank namespace id leaves public on the session`() {
        // NamespaceInfo.createPublicNamespace spells public as ""; the panel
        // passes that into adoptEnvironment on a same-profile switch. Blank
        // must not be treated as "omitted" or the previous tenant sticks and
        // gutter ranking stays on the old Namespace (#193).
        val session = NacosProjectSession()
        session.sessionState.ensureInitialized(environment(profileId = "dev", suggestedNamespace = "team-a"))
        session.select("dev", "team-a")

        session.adoptEnvironment("dev", "")

        assertEquals("dev", session.sessionState.selectedProfileId)
        assertEquals("public", session.sessionState.namespaceId)
    }

    @Test
    fun `adopting public via literal public id leaves public on the session`() {
        val session = NacosProjectSession()
        session.sessionState.ensureInitialized(environment(profileId = "dev", suggestedNamespace = "team-a"))
        session.select("dev", "team-a")

        session.adoptEnvironment("dev", "public")

        assertEquals("dev", session.sessionState.selectedProfileId)
        assertEquals("public", session.sessionState.namespaceId)
    }

    @Test
    fun `namespace resolution for an initialized session ignores any external namespace id`() {
        val session = NacosProjectSessionState().apply {
            ensureInitialized(environment(profileId = "dev", suggestedNamespace = "from-seed"))
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
        component.sessionState.ensureInitialized(environment(profileId = "dev", suggestedNamespace = "public"))

        assertTrue(component.sessionState.sessionInitialized)
        assertEquals("prod", component.sessionState.selectedProfileId)
        assertEquals("legacy-ns", component.sessionState.namespaceId)
    }

    private fun settingsWithProfiles(
        defaultProfileId: String,
        vararg environments: PublishedEnvironment
    ) = NacosSettings().apply {
        profiles = environments.map { environment ->
            EnvironmentProfile(
                id = environment.profileId,
                displayName = environment.displayName,
                canonicalEndpoint = environment.canonicalEndpoint,
                authMode = AuthMode.ANONYMOUS
            )
        }.toMutableList()
        environmentPreferences = environments.map { environment ->
            EnvironmentPreferences(
                profileId = environment.profileId,
                suggestedNamespace = environment.suggestedNamespace
            )
        }.toMutableList()
        migratedDefaultProfileId = defaultProfileId
        activeServerId = ""
    }
}
