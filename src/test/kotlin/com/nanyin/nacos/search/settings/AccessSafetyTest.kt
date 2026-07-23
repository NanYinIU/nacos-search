package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosApiPolicy
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
    fun `credential rotation stages its new slot before publishing the new access revision`() {
        val credentials = InMemoryCredentialSlots()
        val original = EnvironmentProfile.fromLegacy(
            NacosServerConfig(id = "dev", serverUrl = "https://nacos.example", username = "alice")
        )
        credentials.put(original.credentialSlotId, "old-secret")
        val published = mutableListOf<EnvironmentProfile>()

        val nextVersion = original.credentialSlotVersion + 1
        val updated = original.withUpdated(
            credentialSlotId = credentialSlotId(original.id, nextVersion),
            credentialSlotVersion = nextVersion
        )
        CredentialSlotStager(credentials).stage(updated, "new-secret")

        assertEquals("new-secret", credentials[updated.credentialSlotId])
        assertEquals(original.accessRevision + 1, updated.accessRevision)
        published += updated

        assertEquals("old-secret", credentials[original.credentialSlotId])
        assertEquals(updated, published.single())
    }

    @Test
    fun `missing current credential slot fails closed without falling back to a legacy secret`() {
        val settings = NacosSettings()
        settings.applyServers(
            listOf(
                NacosServerConfig(
                    id = "dev",
                    serverUrl = "https://nacos.example",
                    username = "alice",
                    password = "old-secret"
                )
            ),
            "dev"
        )
        val profile = requireNotNull(settings.getActiveProfile())
        NacosCredentialStore.remove(profile.credentialSlotId)

        assertInstanceOf(
            ConfigurationRequired::class.java,
            settings.captureOperationContext().exceptionOrNull()
        )
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
    fun `V1 anonymous profile captures a complete identity without a credential`() {
        val profile = EnvironmentProfile.fromLegacy(
            NacosServerConfig(
                id = "public-read",
                serverUrl = "https://nacos.example/",
                apiPolicy = NacosApiPolicy.V1,
                authMode = AuthMode.ANONYMOUS
            )
        )

        val context = OperationContextResolver.resolve(profile, "").getOrThrow()

        assertEquals("public-read", context.identity.profileId)
        assertEquals(profile.accessRevision, context.identity.accessRevision)
        assertEquals("https://nacos.example", context.identity.canonicalEndpoint)
        assertEquals(NacosApiGeneration.V1, context.identity.resolvedGeneration)
        assertEquals(AuthMode.ANONYMOUS, context.identity.authMode)
        assertEquals("<anonymous>", context.identity.principal)
        assertTrue(context.credential.secret.isEmpty())
    }

    @Test
    fun `V1 context pins one explicit authentication strategy without embedding its secret in identity`() {
        val profile = EnvironmentProfile(
            id = "password-v1",
            displayName = "Password V1",
            canonicalEndpoint = "https://nacos.example",
            apiPolicy = NacosApiPolicy.V1,
            authMode = AuthMode.NACOS_PASSWORD,
            principal = "alice"
        )

        val context = OperationContextResolver.resolve(profile, "p@ss&word").getOrThrow()

        assertEquals(V1AuthenticationStrategy.NACOS_PASSWORD, context.authenticationStrategy)
        assertEquals(AuthMode.NACOS_PASSWORD, context.identity.authMode)
        assertEquals("alice", context.identity.principal)
        assertFalse(context.toString().contains("p@ss&word"))
        assertFalse(context.identity.toString().contains("p@ss&word"))
    }

    @Test
    fun `V1 bearer token context permits a secret without a username`() {
        val profile = EnvironmentProfile(
            id = "bearer-v1",
            displayName = "Bearer V1",
            canonicalEndpoint = "https://nacos.example",
            apiPolicy = NacosApiPolicy.V1,
            authMode = AuthMode.BEARER_TOKEN
        )

        val context = OperationContextResolver.resolve(profile, "bearer-token").getOrThrow()

        assertEquals(V1AuthenticationStrategy.BEARER_TOKEN, context.authenticationStrategy)
        assertEquals("<anonymous>", context.identity.principal)
    }

    @Test
    fun `hybrid is rejected and explicit strategies are valid for AUTO and V3 locked profiles`() {
        val hybridV1 = EnvironmentProfile(
            id = "hybrid-v1",
            displayName = "Hybrid V1",
            canonicalEndpoint = "https://nacos.example",
            apiPolicy = NacosApiPolicy.V1,
            authMode = AuthMode.HYBRID,
            principal = "alice"
        )
        val autoAnonymous = EnvironmentProfile(
            id = "auto-anonymous",
            displayName = "Auto anonymous",
            canonicalEndpoint = "https://nacos.example",
            apiPolicy = NacosApiPolicy.AUTO,
            authMode = AuthMode.ANONYMOUS
        )
        val autoBasic = EnvironmentProfile(
            id = "auto-basic",
            displayName = "Auto basic",
            canonicalEndpoint = "https://nacos.example",
            apiPolicy = NacosApiPolicy.AUTO,
            authMode = AuthMode.HTTP_BASIC,
            principal = "alice"
        )
        val v3Password = EnvironmentProfile(
            id = "v3-password",
            displayName = "V3 password",
            canonicalEndpoint = "https://nacos.example",
            apiPolicy = NacosApiPolicy.V3,
            authMode = AuthMode.NACOS_PASSWORD,
            principal = "alice"
        )

        assertInstanceOf(ConfigurationRequired::class.java, OperationContextResolver.resolve(hybridV1, "secret").exceptionOrNull())
        assertInstanceOf(ConfigurationRequired::class.java, OperationContextResolver.resolve(autoBasic, "").exceptionOrNull())
        // AUTO with anonymous and complete explicit strategies now resolves to UNKNOWN generation.
        val anonCtx = OperationContextResolver.resolve(autoAnonymous, "").getOrThrow()
        assertEquals(NacosApiGeneration.UNKNOWN, anonCtx.resolvedGeneration)
        val basicCtx = OperationContextResolver.resolve(autoBasic, "secret").getOrThrow()
        assertEquals(NacosApiGeneration.UNKNOWN, basicCtx.resolvedGeneration)
        // V3-locked with explicit password strategy resolves to V3 generation.
        val v3Ctx = OperationContextResolver.resolve(v3Password, "secret").getOrThrow()
        assertEquals(NacosApiGeneration.V3, v3Ctx.resolvedGeneration)
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
    @Test
    fun `identity can be derived from a profile without reading any credential`() {
        // PSI/Swing hot paths must obtain the access identity without touching
        // PasswordSafe (a slow EDT operation). Identity fields come entirely from
        // the profile, so the credential is never needed and never read.
        val profile = EnvironmentProfile(
            id = "psi-profile",
            displayName = "PSI",
            canonicalEndpoint = "https://nacos.example",
            apiPolicy = NacosApiPolicy.V3,
            authMode = AuthMode.NACOS_PASSWORD,
            principal = "bob",
            accessRevision = 7
        )

        val identity = OperationContextResolver.identityFromProfile(profile)

        assertEquals("psi-profile", identity.profileId)
        assertEquals(7, identity.accessRevision)
        assertEquals("https://nacos.example", identity.canonicalEndpoint)
        assertEquals(NacosApiGeneration.V3, identity.resolvedGeneration)
        assertEquals(AuthMode.NACOS_PASSWORD, identity.authMode)
        assertEquals("bob", identity.principal)
    }

    @Test
    fun `identity from an AUTO profile resolves to UNKNOWN generation without probing`() {
        val profile = EnvironmentProfile(
            id = "auto-profile",
            displayName = "Auto",
            canonicalEndpoint = "https://nacos.example",
            apiPolicy = NacosApiPolicy.AUTO,
            authMode = AuthMode.ANONYMOUS
        )

        val identity = OperationContextResolver.identityFromProfile(profile)

        assertEquals(NacosApiGeneration.UNKNOWN, identity.resolvedGeneration)
        assertEquals("<anonymous>", identity.principal)
    }

    @Test
    fun `identity from a profile with a blank principal normalises to anonymous`() {
        val profile = EnvironmentProfile(
            id = "blank-principal",
            displayName = "Blank",
            canonicalEndpoint = "https://nacos.example",
            apiPolicy = NacosApiPolicy.V1,
            authMode = AuthMode.HTTP_BASIC,
            principal = "   "
        )

        val identity = OperationContextResolver.identityFromProfile(profile)

        assertEquals("<anonymous>", identity.principal)
    }

}
