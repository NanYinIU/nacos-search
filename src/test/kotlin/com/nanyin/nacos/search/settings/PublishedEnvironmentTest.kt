package com.nanyin.nacos.search.settings

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@TestApplication
class PublishedEnvironmentTest {

    @Test
    fun `published environment projection reads only profiles and preferences`() {
        val profileId = "projection-${System.nanoTime()}"
        val credentialKey = CredentialSlotStore.slotKey(profileId, 17)
        NacosCredentialStore.remove(credentialKey)
        val settings = NacosSettings().apply {
            profiles = mutableListOf(
                EnvironmentProfile(
                    id = profileId,
                    displayName = "Published QA",
                    canonicalEndpoint = "https://qa.example",
                    credentialSlotVersion = 17
                )
            )
            environmentPreferences = mutableListOf(
                EnvironmentPreferences(
                    profileId = profileId,
                    suggestedNamespace = "  team-qa  "
                )
            )
        }

        val environments = settings.publishedEnvironments()

        assertEquals(
            listOf(
                PublishedEnvironment(
                    profileId = profileId,
                    displayName = "Published QA",
                    canonicalEndpoint = "https://qa.example",
                    suggestedNamespace = "team-qa"
                )
            ),
            environments
        )
        assertFalse(
            credentialKey in NacosCredentialStore.cachedKeys(),
            "published environment reads must not consult PasswordSafe"
        )
    }

    @Test
    fun `published environment projection canonicalizes blank and public Namespace suggestions`() {
        val settings = NacosSettings().apply {
            profiles = mutableListOf(
                EnvironmentProfile(id = "blank", canonicalEndpoint = "https://blank.example"),
                EnvironmentProfile(id = "public", canonicalEndpoint = "https://public.example")
            )
            environmentPreferences = mutableListOf(
                EnvironmentPreferences(profileId = "blank", suggestedNamespace = " "),
                EnvironmentPreferences(profileId = "public", suggestedNamespace = " public ")
            )
        }

        assertEquals(
            listOf("public", "public"),
            settings.publishedEnvironments().map { it.suggestedNamespace }
        )
    }

    @Test
    fun `project default resolves the migration profile with its own suggested Namespace`() {
        val settings = NacosSettings().apply {
            profiles = mutableListOf(
                EnvironmentProfile(id = "dev", canonicalEndpoint = "https://dev.example"),
                EnvironmentProfile(id = "prod", canonicalEndpoint = "https://prod.example")
            )
            environmentPreferences = mutableListOf(
                EnvironmentPreferences(profileId = "dev", suggestedNamespace = "team-dev"),
                EnvironmentPreferences(profileId = "prod", suggestedNamespace = "team-prod")
            )
            migratedDefaultProfileId = "prod"
            migratedDefaultNamespaceId = "legacy-global"
        }

        assertEquals(
            PublishedEnvironment(
                profileId = "prod",
                displayName = "",
                canonicalEndpoint = "https://prod.example",
                suggestedNamespace = "team-prod"
            ),
            settings.defaultPublishedEnvironment()
        )
    }
}
