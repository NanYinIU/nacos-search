package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosServerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AccessSafetyTest {

    @Test
    fun `canonical endpoint accepts an origin and removes its cosmetic trailing slash`() {
        val endpoint = CanonicalNacosEndpoint.parse("HTTPS://NACOS.example:443/").getOrThrow()

        assertEquals("https://nacos.example", endpoint.value)
    }

    @Test
    fun `canonical endpoint rejects non-origin and unsafe URL components`() {
        listOf(
            "https://nacos.example/nacos",
            "https://user:pass@nacos.example",
            "https://nacos.example?token=secret",
            "https://nacos.example#fragment",
            "https://",
            "ftp://nacos.example"
        ).forEach { raw ->
            assertTrue(CanonicalNacosEndpoint.parse(raw).isFailure, raw)
        }
    }

    @Test
    fun `legacy migration is deterministic idempotent and creates the first credential slot`() {
        val legacy = listOf(
            NacosServerConfig(
                id = "dev",
                displayName = "Development",
                serverUrl = "https://nacos.example/",
                username = "alice",
                password = "secret",
                namespace = "team-a",
                authMode = AuthMode.TOKEN
            )
        )
        val credentials = InMemoryCredentialSlots()

        val first = LegacyProfileMigrator(credentials).migrate(legacy, "dev", "team-a")
        val second = LegacyProfileMigrator(credentials).migrate(legacy, "dev", "team-a")

        assertEquals(first, second)
        assertEquals("dev", first.defaultProfileId)
        assertEquals("team-a", first.defaultNamespaceId)
        assertEquals("https://nacos.example", first.profiles.single().canonicalEndpoint)
        assertEquals("secret", credentials[first.profiles.single().credentialSlotId])
        assertEquals(1L, first.profiles.single().credentialSlotVersion)
    }

    @Test
    fun `legacy entries without ids receive deterministic distinct profile and credential slots`() {
        val legacy = listOf(
            NacosServerConfig(id = "", serverUrl = "https://dev.nacos.example", password = "dev-secret"),
            NacosServerConfig(id = "", serverUrl = "https://prod.nacos.example", password = "prod-secret")
        )
        val credentials = InMemoryCredentialSlots()

        val migrated = LegacyProfileMigrator(credentials).migrate(legacy, "", "public")

        assertEquals(2, migrated.profiles.map { it.id }.toSet().size)
        assertEquals(2, migrated.profiles.map { it.credentialSlotId }.toSet().size)
        assertEquals("dev-secret", credentials[migrated.profiles[0].credentialSlotId])
        assertEquals("prod-secret", credentials[migrated.profiles[1].credentialSlotId])
    }

    @Test
    fun `profile revisions distinguish access changes from write intent changes`() {
        val original = EnvironmentProfile.fromLegacy(
            NacosServerConfig(id = "dev", serverUrl = "https://nacos.example", username = "alice")
        )

        val writeIntentChange = original.withUpdated(writeIntent = true)
        val principalChange = original.withUpdated(principal = "bob")
        val credentialChange = original.withUpdated(credentialSlotId = "dev:v2", credentialSlotVersion = 2)

        assertEquals(original.accessRevision, writeIntentChange.accessRevision)
        assertEquals(original.profileRevision + 1, writeIntentChange.profileRevision)
        assertEquals(original.accessRevision + 1, principalChange.accessRevision)
        assertEquals(original.profileRevision + 1, principalChange.profileRevision)
        assertEquals(original.accessRevision + 1, credentialChange.accessRevision)
        assertEquals(original.profileRevision + 1, credentialChange.profileRevision)
    }

    @Test
    fun `operation context fails closed before a request when endpoint or credentials are incomplete`() {
        val invalidEndpoint = EnvironmentProfile.fromLegacy(
            NacosServerConfig(id = "bad", serverUrl = "https://nacos.example/path")
        )
        val incompleteCredentials = EnvironmentProfile.fromLegacy(
            NacosServerConfig(id = "credentials", serverUrl = "https://nacos.example", username = "alice")
        )

        assertInstanceOf(
            ConfigurationRequired::class.java,
            OperationContextResolver.resolve(invalidEndpoint, "").exceptionOrNull()
        )
        assertInstanceOf(
            ConfigurationRequired::class.java,
            OperationContextResolver.resolve(incompleteCredentials, "").exceptionOrNull()
        )
    }

    @Test
    fun `captured operation context retains the original endpoint principal and credential snapshot`() {
        val profile = EnvironmentProfile.fromLegacy(
            NacosServerConfig(
                id = "dev",
                serverUrl = "https://dev.nacos.example",
                username = "alice",
                authMode = AuthMode.BASIC
            )
        )
        val captured = OperationContextResolver.resolve(profile, "dev-secret").getOrThrow()
        val changed = profile.withUpdated(
            canonicalEndpoint = "https://prod.nacos.example",
            principal = "bob",
            credentialSlotId = "dev:v2",
            credentialSlotVersion = 2
        )

        assertEquals("https://dev.nacos.example", captured.endpoint.value)
        assertEquals("alice", captured.identity.principal)
        assertEquals("dev-secret", captured.credential.secret)
        assertEquals("https://prod.nacos.example", changed.canonicalEndpoint)
        assertEquals("bob", changed.principal)
    }

    @Test
    fun `project selections stay independent after the shared default is seeded`() {
        val first = NacosProjectSessionState()
        val second = NacosProjectSessionState()
        val defaults = LegacyMigrationResult(
            profiles = emptyList(),
            defaultProfileId = "dev",
            defaultNamespaceId = "team-a"
        )

        first.seedIfNew(defaults)
        second.seedIfNew(defaults)
        first.select("prod", "team-b")

        assertEquals("prod", first.selectedProfileId)
        assertEquals("team-b", first.namespaceId)
        assertEquals("dev", second.selectedProfileId)
        assertEquals("team-a", second.namespaceId)
        assertFalse(second.selectionWasExplicit)
    }
}
