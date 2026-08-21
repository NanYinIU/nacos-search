package com.nanyin.nacos.search.settings

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosServerConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@TestApplication
class NacosSettingsTest {

    private lateinit var settings: NacosSettings

    @BeforeEach
    fun setUp() {
        settings = NacosSettings()
        settings.resetToDefaults()
    }

    @Test
    fun `test default settings values`() {
        assertTrue(settings.servers.isEmpty())
        assertEquals("", settings.activeServerId)
        assertEquals("", settings.serverUrl)
        assertEquals("s_local", settings.resolveDefaultProfileId())
        assertEquals("http://localhost:8848", settings.getMigrationDefaultProfile()?.canonicalEndpoint)
        assertEquals(AuthMode.NACOS_PASSWORD, settings.getMigrationDefaultProfile()?.authMode)
        assertTrue(settings.cacheEnabled)
        assertEquals(5, settings.cacheTtlMinutes)
        assertEquals("en", settings.language)
        assertFalse(settings.preferencesFor("s_local").allowCrossNamespaceNavigation)
    }

    @Test
    fun `getState strips passwords from the persisted snapshot`() {
        settings.applyProfileIntents(
            listOf(
                profileIntentFixture(id = "s1", displayName = "One", serverUrl = "http://localhost:8848", password = "secret1"),
                profileIntentFixture(id = "s2", displayName = "Two", serverUrl = "http://localhost:8849", password = "secret2")
            ),
            "s1"
        )

        val state = settings.getState()

        assertEquals("", state.password)
        assertTrue(state.servers.isEmpty())
        assertEquals("", state.activeServerId)
        assertEquals("", state.serverUrl)
    }

    @Test
    fun `test validate with valid settings`() {
        val errors = settings.validate()
        assertTrue(errors.isEmpty())
        assertTrue(settings.isValid())
    }

    @Test
    fun `test validate with empty server url`() {
        settings.profiles.single().canonicalEndpoint = ""
        val errors = settings.validate()
        assertTrue(errors.contains("Server URL cannot be empty"))
        assertFalse(settings.isValid())
    }

    @Test
    fun `test validate with invalid server url`() {
        settings.profiles.single().canonicalEndpoint = "not a url"
        val errors = settings.validate()
        assertTrue(errors.contains("Invalid server URL format"))
        assertFalse(settings.isValid())
    }

    @Test
    fun `test validate with invalid cache ttl`() {
        settings.cacheTtlMinutes = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Cache TTL must be at least 1 minute"))
    }

    @Test
    fun `legacy unused settings deserialize without affecting validation`() {
        // XmlSerializer ignores unknown attributes on load. Fields removed in
        // issue #156 must not block startup validation of a migrated install.
        val state = NacosSettings()
        state.settingsSchemaVersion = SettingsSchema.CURRENT
        state.profileMigrationCompleted = true
        state.credentialSlotsPublished = true
        state.activeServerId = "s_local"
        state.migratedDefaultProfileId = "s_local"
        state.profiles = mutableListOf(
            com.nanyin.nacos.search.models.EnvironmentProfile(
                id = "s_local",
                displayName = "Local",
                canonicalEndpoint = "http://localhost:8848",
                authMode = AuthMode.ANONYMOUS
            )
        )
        val element = com.intellij.util.xmlb.XmlSerializer.serialize(state)
        // Inject removed-field attributes the way an older build would have written them.
        element.addContent(
            org.jdom.Element("option").apply {
                setAttribute("name", "autoRefreshEnabled")
                setAttribute("value", "true")
            }
        )
        element.addContent(
            org.jdom.Element("option").apply {
                setAttribute("name", "rememberLastSearch")
                setAttribute("value", "true")
            }
        )
        element.addContent(
            org.jdom.Element("option").apply {
                setAttribute("name", "connectionTimeoutSeconds")
                setAttribute("value", "5")
            }
        )
        element.addContent(
            org.jdom.Element("option").apply {
                setAttribute("name", "searchResultLimit")
                setAttribute("value", "50")
            }
        )

        val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(element, NacosSettings::class.java)
        val loaded = NacosSettings()
        loaded.loadState(restored)

        assertTrue(loaded.validate().isEmpty())
        assertTrue(loaded.isValid())
        assertEquals("http://localhost:8848", loaded.getMigrationDefaultProfile()?.canonicalEndpoint)
    }

    @Test
    fun `test isValidServerUrl with http url`() {
        assertTrue(settings.isValidServerUrl("http://localhost:8848"))
    }

    @Test
    fun `test isValidServerUrl with https url`() {
        assertTrue(settings.isValidServerUrl("https://example.com"))
    }

    @Test
    fun `test isValidServerUrl with invalid protocol`() {
        assertFalse(settings.isValidServerUrl("ftp://example.com"))
    }

    @Test
    fun `test isValidServerUrl with empty url`() {
        assertFalse(settings.isValidServerUrl(""))
    }

    @Test
    fun `test resetToDefaults`() {
        settings.profiles.single().canonicalEndpoint = "http://custom:8848"
        settings.cacheEnabled = false

        settings.resetToDefaults()

        assertEquals("http://localhost:8848", settings.getMigrationDefaultProfile()?.canonicalEndpoint)
        assertTrue(settings.servers.isEmpty())
        assertTrue(settings.cacheEnabled)
    }

    @Test
    fun `test getCacheTtlMillis`() {
        settings.cacheTtlMinutes = 5
        assertEquals(5 * 60 * 1000L, settings.getCacheTtlMillis())
    }

    @Test
    fun `test state persistence`() {
        // Persist via the profile write path — flat fields alone are not the
        // runtime source of truth after migration (issue #104).
        settings.applyProfileIntents(
            listOf(
                profileIntentFixture(
                    id = "s_local",
                    displayName = "本地 Local",
                    serverUrl = "http://persist:8848"
                )
            ),
            "s_local"
        )
        val state = settings.getState()

        val newSettings = NacosSettings()
        newSettings.loadState(state)

        assertEquals("", newSettings.serverUrl)
        assertEquals("http://persist:8848", newSettings.getMigrationDefaultProfile()?.canonicalEndpoint)
    }

    @Test
    fun `test toString masks password`() {
        settings.username = "user"
        settings.password = "secret"
        val str = settings.toString()

        assertTrue(str.contains("profiles"))
        assertFalse(str.contains("secret"))
    }

    @Test
    fun `omitted authMode in XML deserializes as ANONYMOUS not product default`() {
        // Product default for new profiles is NACOS_PASSWORD, but the XmlSerializer
        // field default on EnvironmentProfile / NacosServerConfig must stay
        // ANONYMOUS so existing installs whose XML omits authMode (historical
        // skip-defaults) do not migrate to NACOS_PASSWORD (ADR 0040).
        val state = NacosSettings()
        state.settingsSchemaVersion = SettingsSchema.CURRENT
        state.profileMigrationCompleted = true
        state.credentialSlotsPublished = true
        state.activeServerId = "s_local"
        state.migratedDefaultProfileId = "s_local"
        state.authMode = AuthMode.ANONYMOUS
        state.profiles = mutableListOf(
            com.nanyin.nacos.search.models.EnvironmentProfile(
                id = "s_local",
                displayName = "Local",
                canonicalEndpoint = "http://localhost:8848",
                authMode = AuthMode.ANONYMOUS
            )
        )
        state.servers = mutableListOf(
            NacosServerConfig(
                id = "s_local",
                displayName = "Local",
                serverUrl = "http://localhost:8848",
                authMode = AuthMode.ANONYMOUS
            )
        )

        val element = com.intellij.util.xmlb.XmlSerializer.serialize(state)
        // Real IDE files for ANONYMOUS-by-default installs often omit the option
        // entirely. Strip any authMode nodes to reproduce that on-disk shape.
        stripAuthModeOptions(element)
        val xml = com.intellij.openapi.util.JDOMUtil.writeElement(element)
        assertFalse(xml.contains("authMode"), "test fixture must omit authMode like legacy installs")

        val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(element, NacosSettings::class.java)
        assertEquals(AuthMode.ANONYMOUS, restored.profiles.single().authMode)
        assertEquals(AuthMode.ANONYMOUS, restored.servers.single().authMode)
        assertEquals(AuthMode.ANONYMOUS, restored.authMode)

        val loaded = NacosSettings()
        loaded.loadState(restored)
        assertEquals(AuthMode.ANONYMOUS, loaded.getMigrationDefaultProfile()!!.authMode)
    }

    private fun stripAuthModeOptions(element: org.jdom.Element) {
        val toRemove = element.children.filter { child ->
            (child.name == "option" && child.getAttributeValue("name") == "authMode") ||
                (child.name == "authMode")
        }
        toRemove.forEach { element.removeContent(it) }
        element.children.toList().forEach { stripAuthModeOptions(it) }
    }

    @Test
    fun `xml serializer round trips environment profiles without Instantiator failure`() {
        settings.applyProfileIntents(
            listOf(
                profileIntentFixture(
                    id = "s_prod",
                    displayName = "Prod",
                    serverUrl = "http://47.95.169.10:8848",
                    username = "nacos",
                    password = "nacos",
                    authMode = AuthMode.TOKEN
                )
            ),
            "s_prod"
        )
        val profile = settings.getMigrationDefaultProfile()
        assertNotNull(profile)
        assertEquals("s_prod", profile!!.id)
        assertEquals("s_prod:v1", profile.credentialSlotId)

        // Persist the way the IDE does (XML), not a same-JVM bean copy.
        val element = com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState())
        val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(element, NacosSettings::class.java)
        val loaded = NacosSettings()
        loaded.loadState(restored)

        val restoredProfile = loaded.getMigrationDefaultProfile()
        assertNotNull(restoredProfile)
        assertEquals("s_prod", restoredProfile!!.id)
        assertEquals("Prod", restoredProfile.displayName)
        assertEquals("http://47.95.169.10:8848", restoredProfile.canonicalEndpoint)
        assertEquals("s_prod:v1", restoredProfile.credentialSlotId)
        assertEquals(AuthMode.TOKEN, restoredProfile.authMode)
        assertEquals("nacos", restoredProfile.principal)
    }

    @Test
    fun `loadState drops blank-id profiles and remigrates from servers when schema is legacy`() {
        val state = NacosSettings()
        state.servers = mutableListOf(
            NacosServerConfig(
                id = "s_ok",
                displayName = "OK",
                serverUrl = "http://localhost:8848",
                username = "nacos",
                password = "secret",
                authMode = AuthMode.NACOS_PASSWORD
            )
        )
        // Simulate corrupt XML debris that Instantiator used to blow up on, or
        // partially written profile rows with an empty id under a legacy schema.
        state.profiles = mutableListOf(
            com.nanyin.nacos.search.models.EnvironmentProfile(
                id = "",
                displayName = "broken",
                canonicalEndpoint = "http://localhost:8848"
            )
        )
        state.settingsSchemaVersion = 0
        state.profileMigrationCompleted = false
        state.credentialSlotsPublished = false

        val loaded = NacosSettings()
        loaded.loadState(state)

        val profile = loaded.getMigrationDefaultProfile()
        assertNotNull(profile)
        assertEquals("s_ok", profile!!.id)
        assertEquals("OK", profile.displayName)
        assertTrue(profile.credentialSlotId.isNotBlank())
        assertEquals(SettingsSchema.CURRENT, loaded.settingsSchemaVersion)
    }

    @Test
    fun `environment profile no-arg construction is Instantiator safe`() {
        val blank = com.nanyin.nacos.search.models.EnvironmentProfile()
        assertEquals("", blank.id)
        assertEquals("", blank.credentialSlotId)

        val withId = com.nanyin.nacos.search.models.EnvironmentProfile(id = "srv_1")
        assertEquals("srv_1:v1", withId.credentialSlotId)
    }

    @Test
    fun `loadState backfills missing profile credential slots from legacy server secrets`() {
        val state = NacosSettings()
        // Profiles present under a legacy schema version with a missing slot —
        // the failure mode after Instantiator aborted mid-save.
        state.profiles = mutableListOf(
            com.nanyin.nacos.search.models.EnvironmentProfile(
                id = "s_auth",
                displayName = "Auth",
                canonicalEndpoint = "http://47.95.169.10:8848",
                authMode = AuthMode.TOKEN,
                principal = "nacos",
                credentialSlotId = "s_auth:v1"
            )
        )
        state.settingsSchemaVersion = 0
        state.profileMigrationCompleted = true
        state.credentialSlotsPublished = true
        NacosCredentialStore.remove("s_auth:v1")
        NacosCredentialStore.setDurable("s_auth", "correct-horse")

        val loaded = NacosSettings()
        loaded.loadState(state)

        assertEquals("correct-horse", NacosCredentialStore.get("s_auth:v1"))
        val context = loaded.captureOperationContext("s_auth").getOrThrow()
        assertEquals("correct-horse", context.credential.secret)
        assertEquals("nacos", context.identity.principal)
        assertEquals(SettingsSchema.CURRENT, loaded.settingsSchemaVersion)
    }

    @Test
    fun `captureOperationContext fails closed for missing explicit profile ids`() {
        settings.applyProfileIntents(
            listOf(
                profileIntentFixture(
                    id = "s_live",
                    displayName = "Live",
                    serverUrl = "http://localhost:8848",
                    username = "nacos",
                    password = "secret",
                    authMode = AuthMode.NACOS_PASSWORD
                )
            ),
            "s_live"
        )

        val result = settings.captureOperationContext("deleted-profile")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ConfigurationRequired)
    }

    @Test
    fun `pure getProfile does not remigrate when profiles were cleared in memory`() {
        settings.applyProfileIntents(
            listOf(
                profileIntentFixture(
                    id = "s_only",
                    displayName = "Only",
                    serverUrl = "http://localhost:8848",
                    username = "nacos",
                    password = "pw",
                    authMode = AuthMode.NACOS_PASSWORD
                )
            ),
            "s_only"
        )
        settings.profiles = mutableListOf()
        // Reads never re-derive profiles from servers (issue #104).
        assertNull(settings.getProfile("s_only"))
    }

    @Test
    fun `project session healSelection seeds blank sessions once but never retargets explicit deleted ones`() {
        settings.applyProfileIntents(
            listOf(
                profileIntentFixture(
                    id = "s_ok",
                    displayName = "OK",
                    serverUrl = "http://localhost:8848",
                    username = "nacos",
                    password = "pw",
                    authMode = AuthMode.NACOS_PASSWORD
                )
            ),
            "s_ok"
        )
        val blank = NacosProjectSessionState(
            selectedProfileId = "",
            namespaceId = "public",
            selectionWasExplicit = false
        )
        blank.ensureInitialized(settings.defaultPublishedEnvironment())
        assertEquals("s_ok", blank.selectedProfileId)
        assertTrue(blank.sessionInitialized)

        val explicitDeleted = NacosProjectSessionState(
            selectedProfileId = "ghost",
            namespaceId = "public",
            selectionWasExplicit = true
        )
        explicitDeleted.ensureInitialized(settings.defaultPublishedEnvironment())
        assertEquals("ghost", explicitDeleted.selectedProfileId)
        assertTrue(explicitDeleted.sessionInitialized)
    }
}
