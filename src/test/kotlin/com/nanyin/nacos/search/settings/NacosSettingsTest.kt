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
        assertEquals("http://localhost:8848", settings.serverUrl)
        assertEquals("", settings.username)
        assertEquals("", settings.password)
        assertEquals("public", settings.namespace)
        assertEquals(AuthMode.NACOS_PASSWORD, settings.authMode)
        assertTrue(settings.enableTokenAuth)
        assertEquals(30, settings.tokenCacheDurationMinutes)
        assertTrue(settings.autoTokenRefresh)
        assertTrue(settings.cacheEnabled)
        assertEquals(5, settings.cacheTtlMinutes)
        assertEquals(1000, settings.maxCacheSize)
        assertFalse(settings.autoRefreshEnabled)
        assertEquals(10, settings.autoRefreshIntervalMinutes)
        assertEquals(100, settings.searchResultLimit)
        assertTrue(settings.enableRegexSearch)
        assertFalse(settings.caseSensitiveSearch)
        assertTrue(settings.highlightMatches)
        assertTrue(settings.showToolWindow)
        assertEquals("right", settings.toolWindowLocation)
        assertTrue(settings.rememberLastSearch)
        assertEquals("en", settings.language)
        assertEquals(30, settings.connectionTimeoutSeconds)
        assertEquals(60, settings.readTimeoutSeconds)
        assertEquals(3, settings.retryAttempts)
        assertEquals(2, settings.retryDelaySeconds)
        assertFalse(settings.getActiveServer().allowCrossNamespaceNavigation)
    }

    @Test
    fun `getState strips passwords from the persisted snapshot`() {
        settings.applyServers(
            listOf(
                NacosServerConfig(id = "s1", displayName = "One", serverUrl = "http://localhost:8848", password = "secret1"),
                NacosServerConfig(id = "s2", displayName = "Two", serverUrl = "http://localhost:8849", password = "secret2")
            ),
            "s1"
        )

        val state = settings.getState()

        assertEquals("", state.password)
        assertTrue(state.servers.all { it.password.isEmpty() })
        // The live in-memory settings still carry the password for runtime use.
        assertEquals("secret1", settings.getActiveServer().password)
    }

    @Test
    fun `setActiveServer updates dual-write active id without rewriting migration seed`() {
        settings.applyServers(
            listOf(
                NacosServerConfig(id = "s_local", displayName = "本地 Local", serverUrl = "http://localhost:8848"),
                NacosServerConfig(id = "s_qa", displayName = "QA", serverUrl = "http://47.95.169.10:8848")
            ),
            "s_local"
        )
        assertEquals("s_local", settings.activeServerId)
        assertEquals("s_local", settings.resolveDefaultProfileId())
        val seed = settings.migratedDefaultProfileId

        settings.setActiveServer("s_qa")

        assertEquals("s_qa", settings.activeServerId)
        // Seed is migration-owned (issue #104); switching active must not retarget
        // the default a newly opened project would inherit.
        assertEquals(seed, settings.migratedDefaultProfileId)
        assertEquals("s_local", settings.resolveDefaultProfileId())
        assertEquals("http://47.95.169.10:8848", settings.serverUrl)
    }

    @Test
    fun `server config cross namespace navigation defaults off and copies`() {
        val server = NacosServerConfig.createDefault()

        assertFalse(server.allowCrossNamespaceNavigation)

        server.allowCrossNamespaceNavigation = true
        val copy = server.copyConfig()

        assertTrue(copy.allowCrossNamespaceNavigation)
    }

    @Test
    fun `test validate with valid settings`() {
        val errors = settings.validate()
        assertTrue(errors.isEmpty())
        assertTrue(settings.isValid())
    }

    @Test
    fun `test validate with empty server url`() {
        settings.serverUrl = ""
        val errors = settings.validate()
        assertTrue(errors.contains("Server URL cannot be empty"))
        assertFalse(settings.isValid())
    }

    @Test
    fun `test validate with invalid server url`() {
        settings.serverUrl = "not a url"
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
    fun `test validate with invalid max cache size`() {
        settings.maxCacheSize = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Max cache size must be at least 1"))
    }

    @Test
    fun `test validate no longer checks auto refresh interval`() {
        // Auto-refresh validation was removed — the feature is gone.
        settings.autoRefreshIntervalMinutes = 0
        val errors = settings.validate()
        assertFalse(errors.contains("Auto refresh interval must be at least 1 minute"))
    }

    @Test
    fun `test validate with invalid search result limit`() {
        settings.searchResultLimit = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Search result limit must be at least 1"))
    }

    @Test
    fun `test validate with invalid connection timeout`() {
        settings.connectionTimeoutSeconds = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Connection timeout must be at least 1 second"))
    }

    @Test
    fun `test validate with invalid read timeout`() {
        settings.readTimeoutSeconds = 0
        val errors = settings.validate()
        assertTrue(errors.contains("Read timeout must be at least 1 second"))
    }

    @Test
    fun `test validate with negative retry attempts`() {
        settings.retryAttempts = -1
        val errors = settings.validate()
        assertTrue(errors.contains("Retry attempts cannot be negative"))
    }

    @Test
    fun `test validate with negative retry delay`() {
        settings.retryDelaySeconds = -1
        val errors = settings.validate()
        assertTrue(errors.contains("Retry delay cannot be negative"))
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
        settings.serverUrl = "http://custom:8848"
        settings.username = "user"
        settings.cacheEnabled = false

        settings.resetToDefaults()

        assertEquals("http://localhost:8848", settings.serverUrl)
        assertEquals("", settings.username)
        assertTrue(settings.cacheEnabled)
    }

    @Test
    fun `test copy creates independent instance`() {
        settings.serverUrl = "http://custom:8848"
        val copy = settings.copy()

        assertEquals("http://custom:8848", copy.serverUrl)
        copy.serverUrl = "http://modified:8848"
        assertEquals("http://custom:8848", settings.serverUrl)
    }

    @Test
    fun `test copyFrom`() {
        val source = NacosSettings().apply {
            serverUrl = "http://source:8848"
            username = "sourceUser"
        }

        settings.copyFrom(source)

        assertEquals("http://source:8848", settings.serverUrl)
        assertEquals("sourceUser", settings.username)
    }

    @Test
    fun `test hasAuthentication`() {
        assertFalse(settings.hasAuthentication())

        settings.username = "user"
        settings.password = "pass"
        assertTrue(settings.hasAuthentication())
    }

    @Test
    fun `test hasTokenAuthentication`() {
        settings.enableTokenAuth = true
        assertFalse(settings.hasTokenAuthentication())

        settings.username = "user"
        settings.password = "pass"
        assertTrue(settings.hasTokenAuthentication())

        settings.enableTokenAuth = false
        assertFalse(settings.hasTokenAuthentication())
    }

    @Test
    fun `test getTokenCacheDurationMillis`() {
        settings.tokenCacheDurationMinutes = 30
        assertEquals(30 * 60 * 1000L, settings.getTokenCacheDurationMillis())
    }

    @Test
    fun `test getCacheTtlMillis`() {
        settings.cacheTtlMinutes = 5
        assertEquals(5 * 60 * 1000L, settings.getCacheTtlMillis())
    }

    @Test
    fun `test getAutoRefreshIntervalMillis`() {
        settings.autoRefreshIntervalMinutes = 10
        assertEquals(10 * 60 * 1000L, settings.getAutoRefreshIntervalMillis())
    }

    @Test
    fun `test getConnectionTimeoutMillis`() {
        settings.connectionTimeoutSeconds = 30
        assertEquals(30000, settings.getConnectionTimeoutMillis())
    }

    @Test
    fun `test getReadTimeoutMillis`() {
        settings.readTimeoutSeconds = 60
        assertEquals(60000, settings.getReadTimeoutMillis())
    }

    @Test
    fun `test getRetryDelayMillis`() {
        settings.retryDelaySeconds = 2
        assertEquals(2000L, settings.getRetryDelayMillis())
    }

    @Test
    fun `test updateLastSearch when remember enabled`() {
        settings.rememberLastSearch = true
        settings.updateLastSearch("query", "group", "tenant")

        assertEquals("query", settings.lastSearchQuery)
        assertEquals("group", settings.lastGroupFilter)
        assertEquals("tenant", settings.lastTenantFilter)
    }

    @Test
    fun `test updateLastSearch when remember disabled`() {
        settings.rememberLastSearch = false
        settings.updateLastSearch("query", "group", "tenant")

        assertEquals("", settings.lastSearchQuery)
        assertEquals("", settings.lastGroupFilter)
        assertEquals("", settings.lastTenantFilter)
    }

    @Test
    fun `test clearLastSearch`() {
        settings.rememberLastSearch = true
        settings.updateLastSearch("query", "group", "tenant")
        settings.clearLastSearch()

        assertEquals("", settings.lastSearchQuery)
        assertEquals("", settings.lastGroupFilter)
        assertEquals("", settings.lastTenantFilter)
    }

    @Test
    fun `test state persistence`() {
        // Persist via the profile write path — flat fields alone are not the
        // runtime source of truth after migration (issue #104).
        settings.applyServers(
            listOf(
                NacosServerConfig(
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

        assertEquals("http://persist:8848", newSettings.serverUrl)
        assertEquals("http://persist:8848", newSettings.getActiveProfile()?.canonicalEndpoint)
    }

    @Test
    fun `test active server sync updates runtime connection settings`() {
        val fast = com.nanyin.nacos.search.models.NacosServerConfig(
            id = "fast",
            displayName = "Fast Local",
            serverUrl = "http://localhost:8848",
            connectionTimeoutMs = 5000,
            autoRefreshOnOpen = false
        )
        settings.applyServers(listOf(fast), "fast")

        assertEquals("http://localhost:8848", settings.serverUrl)
        assertEquals(5, settings.connectionTimeoutSeconds)
        // autoRefreshEnabled is a legacy field that is no longer synced from
        // per-server config; it defaults to false and stays false.
        assertFalse(settings.autoRefreshEnabled)
    }

    @Test
    fun `test toString masks password`() {
        settings.username = "user"
        settings.password = "secret"
        val str = settings.toString()

        assertTrue(str.contains("serverUrl"))
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
        assertEquals(AuthMode.ANONYMOUS, loaded.getActiveProfile()!!.authMode)
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
        settings.applyServers(
            listOf(
                NacosServerConfig(
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
        val profile = settings.getActiveProfile()
        assertNotNull(profile)
        assertEquals("s_prod", profile!!.id)
        assertEquals("s_prod:v1", profile.credentialSlotId)

        // Persist the way the IDE does (XML), not a same-JVM bean copy.
        val element = com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState())
        val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(element, NacosSettings::class.java)
        val loaded = NacosSettings()
        loaded.loadState(restored)

        val restoredProfile = loaded.getActiveProfile()
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
        settings.applyServers(
            listOf(
                NacosServerConfig(
                    id = "s_ok",
                    displayName = "OK",
                    serverUrl = "http://localhost:8848",
                    username = "nacos",
                    password = "secret",
                    authMode = AuthMode.NACOS_PASSWORD
                )
            ),
            "s_ok"
        )
        val state = settings.getState()
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

        val profile = loaded.getActiveProfile()
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
        settings.applyServers(
            listOf(
                NacosServerConfig(
                    id = "s_auth",
                    displayName = "Auth",
                    serverUrl = "http://47.95.169.10:8848",
                    username = "nacos",
                    password = "correct-horse",
                    authMode = AuthMode.NACOS_PASSWORD
                )
            ),
            "s_auth"
        )
        val state = settings.getState()
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
        NacosCredentialStore.set("s_auth", "correct-horse")

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
        settings.applyServers(
            listOf(
                NacosServerConfig(
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
        settings.applyServers(
            listOf(
                NacosServerConfig(
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
        settings.applyServers(
            listOf(
                NacosServerConfig(
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
        blank.healSelection(settings.migrationDefaults()) { id -> settings.getProfile(id) != null }
        assertEquals("s_ok", blank.selectedProfileId)
        assertTrue(blank.sessionInitialized)

        val explicitDeleted = NacosProjectSessionState(
            selectedProfileId = "ghost",
            namespaceId = "public",
            selectionWasExplicit = true
        )
        explicitDeleted.healSelection(settings.migrationDefaults()) { id -> settings.getProfile(id) != null }
        assertEquals("ghost", explicitDeleted.selectedProfileId)
        assertTrue(explicitDeleted.sessionInitialized)
    }
}
